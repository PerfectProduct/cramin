package pro.perfectproduct.cramin.pipeline

import androidx.room.withTransaction
import pro.perfectproduct.cramin.data.db.*
import pro.perfectproduct.cramin.llm.*
import pro.perfectproduct.cramin.util.Lang

/** Read-only coherent database snapshot. Export only enum values, times and counts, never content. */
object DocumentStateSummary {
    suspend fun copyText(db: CraminDatabase, id: Long, stoplists: Stoplists, version: String, api: Int, files: pro.perfectproduct.cramin.data.repo.DocumentFiles? = null): String = db.withTransaction {
        val doc = db.documentDao().getById(id) ?: return@withTransaction "Document state: NOT_FOUND"
        val sentences = db.sentenceDao().getByDocument(id)
        val segments = db.segmentDao().getByDocument(id)
        val jobs = db.jobDao().getByDocument(id)
        val savedCards = db.cardDao().getByDocument(id)
        val occurrenceCount = db.cardDao().countOccurrences(id)
        val numbers = linkedMapOf("sentences" to sentences.size, "segments" to segments.size,
            "savedCards" to savedCards.size, "savedOccurrences" to occurrenceCount)
        val proof = try {
            val lang = Lang.fromCode(doc.sourceLang) ?: throw ConsolidationCacheMissing()
            val target = Lang.fromCode(doc.targetLang) ?: throw ConsolidationCacheMissing()
            if (sentences.isEmpty()) throw ConsolidationCacheMissing()
            val byIdx = sentences.associateBy { it.idx }
            val translations = sentences.associate { sentence ->
                val covering = segments.filter { sentence.idx in it.firstSentenceIdx..it.lastSentenceIdx }
                if (covering.size != 1 || covering.single().translation.isBlank()) throw ConsolidationCacheInvalid()
                sentence.idx to covering.single().translation
            }
            val context = object : UnitContext {
                override fun sentence(idx: Int) = byIdx[idx]?.text
                override fun segmentTranslation(idx: Int) = translations[idx]
            }
            val extraction = jobs.filter { it.kind == JobKind.EXTRACT }
            if (extraction.isEmpty()) throw ConsolidationCacheMissing()
            val covered = hashSetOf<Int>()
            val units = extraction.flatMap { job ->
                if (job.status != JobStatus.DONE) throw ConsolidationCacheUnfinished()
                val start = job.rangeStart ?: throw ConsolidationCacheInvalid()
                val end = job.rangeEnd ?: throw ConsolidationCacheInvalid()
                if (start > end || start !in byIdx || end !in byIdx) throw ConsolidationCacheInvalid()
                val indices = byIdx.keys.filter { it in start..end }
                if (indices.any { it in covered }) throw ConsolidationCacheInvalid()
                covered += indices
                val raw = job.responseJson ?: throw ConsolidationCacheMissing()
                UnitValidator.validate(start..end, LlmJson.strict.decodeFromString<StoredExtraction>(raw).u,
                    context, stoplists.forLang(lang), lang, target)
            }
            if (covered != byIdx.keys) throw ConsolidationCacheMissing()
            val groups = UnitMerger.merge(units, lang, target)
            val batches = Consolidation.batches(groups)
            numbers["validatedUnits"] = units.size
            numbers["lexicalGroups"] = groups.size
            numbers["requiredConsolidationBatches"] = batches.size
            val legacy = doc.modelsSnapshotJson?.let { ProcessingSnapshot.decode(it).legacyParametersUnknown } == true
            val oldBatches = if (legacy) Consolidation.batches(groups.map { card ->
                card.copy(translations = card.units.withIndex().groupBy(
                    { TextNormalizer.translationKey(it.value.translation, target) }, { it.index }))
            }) else emptyList()
            val senses = groups.filterNot { it.needsConsolidation }.associate { it.lemmaKey to Consolidation.single(it) }.toMutableMap()
            for ((i, batch) in batches.withIndex()) {
                val job = jobs.singleOrNull { it.kind == JobKind.CONSOLIDATE && it.idx == i } ?: throw ConsolidationCacheMissing()
                if (job.status != JobStatus.DONE) throw ConsolidationCacheUnfinished()
                val built = Consolidation.buildBatch(batch) { byIdx[it]?.text }
                val response = job.responseJson?.let { raw ->
                    if (ConsolidationParts.decode(raw) != null) throw ConsolidationCacheUnfinished()
                    Consolidation.readCached(raw, built, oldBatches.getOrNull(i)?.let { old ->
                        Consolidation.buildBatch(old) { byIdx[it]?.text }
                    }, legacy)
                }
                if (response == null && job.finishReason != "fallback") throw ConsolidationCacheMissing()
                senses.putAll(Consolidation.apply(batch, built, response))
            }
            val expected = senses.flatMap { (lemma, drafts) -> drafts.map { lemma to MeaningKey.of(it.translation) } }.toSet()
            val actual = savedCards.map { it.lemmaKey to it.meaningKey }.toSet()
            numbers["expectedMeaningCards"] = expected.size
            numbers["duplicateMeaningCards"] = savedCards.size - actual.size
            val expectedOccurrences = senses.values.sumOf { drafts -> drafts.groupBy { MeaningKey.of(it.translation) }
                .values.sumOf { same -> same.flatMap { it.unitIndices }.distinct().size } }
            numbers["expectedOccurrences"] = expectedOccurrences
            if (actual == expected && savedCards.size == expected.size && occurrenceCount == expectedOccurrences)
                "MATCHES_SAVED_PIPELINE_RESULTS" else "CARDS_OR_OCCURRENCES_DIFFER"
        } catch (_: ConsolidationCacheMissing) { "MISSING_CACHE"
        } catch (_: ConsolidationCacheUnfinished) { "UNFINISHED_JOBS"
        } catch (_: Exception) { "UNREADABLE_OR_INCOMPATIBLE_CACHE" }
        val failure = FailureDiagnostic.forDocument(doc)
        buildString {
            append("State snapshot copied with Cramin: $version\nCopy device Android API: $api")
            append("\nState observed at: ${System.currentTimeMillis()}\nDocument status: ${doc.status}\nProgress: ${doc.progress}")
            append("\nDocument updated at (not completion timestamp): ${doc.updatedAt}\nSource: ${doc.sourceType}")
            append("\n" + (files?.textProvenance(id) ?: pro.perfectproduct.cramin.ingest.TextProvenance()).safeSummary())
            val stt = jobs.filter { it.kind == JobKind.STT && it.status == JobStatus.DONE }
            append("\nSaved STT evidence: completedParts=${stt.size}; models=" +
                stt.map { pro.perfectproduct.cramin.ingest.TextProvenance.safe(it.model) }.distinct().joinToString(",").ifEmpty { "UNKNOWN" })
            append("\nLegacy STT jobs alone do not identify the current source track or request language")
            append("\nStructural check: $proof\nSemantic completeness: NOT_VERIFIABLE_FROM_CACHE")
            append("\nConsolidation policy: ALL_OCCURRENCES_OR_CONSERVATIVE_FALLBACK; historical fallback provenance may be unavailable")
            append("\nCounts: ${numbers.entries.joinToString { "${it.key}=${it.value}" }}")
            for (kind in JobKind.entries) {
                val group = jobs.filter { it.kind == kind }
                append("\nJobs $kind: total=${group.size}, done=${group.count { it.status == JobStatus.DONE }}, pending=${group.count { it.status == JobStatus.PENDING }}, failed=${group.count { it.status == JobStatus.FAILED }}")
            }
            append("\nHistorical diagnostic disposition: ${failure?.disposition(doc.status) ?: "NONE"}")
            append("\nHistorical diagnostic (not current status):\n${failure?.copyText(version, api) ?: "NONE"}")
        }
    }
}
