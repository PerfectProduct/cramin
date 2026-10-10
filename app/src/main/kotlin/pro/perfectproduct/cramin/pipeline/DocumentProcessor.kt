package pro.perfectproduct.cramin.pipeline

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import pro.perfectproduct.cramin.data.db.CardEntity
import pro.perfectproduct.cramin.data.db.CardStatus
import pro.perfectproduct.cramin.data.db.CardStatusSnapshot
import pro.perfectproduct.cramin.data.db.CraminDatabase
import pro.perfectproduct.cramin.data.db.DocStatus
import pro.perfectproduct.cramin.data.db.DocumentEntity
import pro.perfectproduct.cramin.data.db.JobEntity
import pro.perfectproduct.cramin.data.db.JobKind
import pro.perfectproduct.cramin.data.db.JobStatus
import pro.perfectproduct.cramin.data.db.OccurrenceEntity
import pro.perfectproduct.cramin.data.db.SegmentEntity
import pro.perfectproduct.cramin.data.db.SentenceEntity
import pro.perfectproduct.cramin.data.db.SourceType
import pro.perfectproduct.cramin.data.repo.DocumentFiles
import pro.perfectproduct.cramin.data.repo.UsageRepository
import pro.perfectproduct.cramin.ingest.Extracted
import pro.perfectproduct.cramin.ingest.SourceExtractor
import pro.perfectproduct.cramin.llm.Brief
import pro.perfectproduct.cramin.llm.CatalogView
import pro.perfectproduct.cramin.llm.ConsolidateResponse
import pro.perfectproduct.cramin.llm.ProcessingSnapshot
import pro.perfectproduct.cramin.llm.EffectiveRole
import pro.perfectproduct.cramin.llm.EffectiveConfig
import pro.perfectproduct.cramin.llm.ExtractResponse
import pro.perfectproduct.cramin.llm.ExtractedUnit
import pro.perfectproduct.cramin.llm.GlossaryEntry
import pro.perfectproduct.cramin.llm.LlmClient
import pro.perfectproduct.cramin.llm.LlmException
import pro.perfectproduct.cramin.llm.LlmJson
import pro.perfectproduct.cramin.llm.LlmRequest
import pro.perfectproduct.cramin.llm.LlmResponse
import pro.perfectproduct.cramin.llm.ModelRole
import pro.perfectproduct.cramin.llm.Prompts
import pro.perfectproduct.cramin.llm.Schemas
import pro.perfectproduct.cramin.llm.StoredBrief
import pro.perfectproduct.cramin.llm.StoredExtraction
import pro.perfectproduct.cramin.llm.StoredTranslation
import pro.perfectproduct.cramin.llm.TranslateResponse
import pro.perfectproduct.cramin.llm.TranslatedSegment
import pro.perfectproduct.cramin.transcribe.AudioSegmenter
import pro.perfectproduct.cramin.transcribe.Transcriber
import pro.perfectproduct.cramin.util.Clock
import pro.perfectproduct.cramin.util.Lang
import pro.perfectproduct.cramin.util.Log
import java.io.File
import kotlinx.coroutines.sync.withLock
import pro.perfectproduct.cramin.data.repo.ReprocessProgress

/** Исход обработки для воркера. */
sealed interface ProcessOutcome {
    data class Ready(val cardCount: Int) : ProcessOutcome
    data class Failed(val code: ErrorCode, val detail: String) : ProcessOutcome
    data object Skipped : ProcessOutcome
}

/** Зависимости пайплайна, которые различаются в проде и тестах. */
class ProcessorDeps(
    val db: CraminDatabase,
    val files: DocumentFiles,
    val llm: LlmClient,
    val extractors: Map<SourceType, SourceExtractor>,
    val transcriber: Transcriber?,
    val audioSegmenter: AudioSegmenter?,
    val configProvider: suspend () -> EffectiveConfig,
    val catalogProvider: suspend () -> CatalogView?,
    val stoplists: Stoplists,
    val segmenter: Segmenter,
    val usage: UsageRepository,
    val clock: Clock,
    val checkpoint: (String) -> Unit = {},
    val legacyTopicRole: suspend () -> EffectiveRole? = { null },
    val sttReady: suspend () -> Boolean = { true },
)

/**
 * Оркестрация обработки документа (SPEC §6.11): FETCHING → (TRANSCRIBING) → BRIEFING → TRANSLATING
 * (последовательно) → EXTRACTING (параллельно) → CONSOLIDATING → READY. Каждый вызов модели — строка
 * Job; DONE-задачи при повторе пропускаются, поэтому обработка возобновляется с места сбоя.
 * Тексты документов в лог не попадают: только идентификаторы, счётчики и статусы.
 */
class DocumentProcessor(private val deps: ProcessorDeps) {

    private val db get() = deps.db
    private val now get() = deps.clock.now()

    /** `onProgress(status, progress)` вызывается при смене стадии и по мере выполнения задач. */
    suspend fun process(documentId: Long, localConsolidationOnly: Boolean = false, consolidationOnly: Boolean = false, onProgress: suspend (DocStatus, Float) -> Unit = { _, _ -> }): ProcessOutcome {
        return kotlinx.coroutines.withContext(ConsolidationTrace(localConsolidationOnly, consolidationOnly || localConsolidationOnly)) {
            db.documentLock(documentId).withLock { processLocked(documentId, onProgress) }
        }
    }

    suspend fun enrichCategories(documentId: Long): ProcessOutcome = db.documentLock(documentId).withLock {
        val doc = db.documentDao().getById(documentId) ?: return@withLock ProcessOutcome.Skipped
        if (doc.status != DocStatus.READY) return@withLock ProcessOutcome.Skipped
        try {
            db.documentDao().setTopicError(documentId, null)
            val classifier = TopicClassifier(deps)
            val saved = doc.topicSnapshotJson
            val topic = if (saved != null) Json.decodeFromString<TopicSnapshot>(saved)
                else classifier.snapshot(documentId, doc.modelsSnapshotJson?.let { ProcessingSnapshot.decode(it).config }
                    ?: deps.configProvider(), deps.catalogProvider())
            val repository = pro.perfectproduct.cramin.data.repo.CardRepository(db, deps.clock)
            val cards = db.cardDao().getByDocument(documentId).filter { it.category == null }
            val items = repository.cardsByIds(cards.map { it.id }).map { c ->
                pro.perfectproduct.cramin.llm.TopicItem(c.id.toString(), c.lemma, c.pos.name,
                    c.senses.joinToString("; ") { it.translation }, c.senses.joinToString("; ") { it.translation },
                    c.senses.mapNotNull { it.example?.sentence })
            }
            classifier.classify(documentId, topic, items) { part ->
                part.forEach { (id, category) -> db.cardDao().setCategory(documentId, id.toLong(), category) }
            }
            ProcessOutcome.Ready(db.cardDao().getByDocument(documentId).size)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val code = PipelineException.from(e).code
            db.documentDao().setTopicError(documentId, code.name)
            ProcessOutcome.Failed(code, code.name)
        }
    }

    private suspend fun processLocked(documentId: Long, onProgress: suspend (DocStatus, Float) -> Unit): ProcessOutcome {
        val doc = db.documentDao().getById(documentId) ?: return ProcessOutcome.Skipped
        if (doc.status == DocStatus.READY) return ProcessOutcome.Skipped
        Log.i(TAG, "process doc=$documentId status=${doc.status} type=${doc.sourceType}")
        val trace = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[ConsolidationTrace])
        var stage = if (trace.consolidationOnly) FailureStage.CONSOLIDATING else FailureStage.CONFIG
        trace.active = trace.consolidationOnly
        return try {
            // Imports surviving v1 snapshots, or captures cards restored before an old crash.
            if (trace.active) trace.step = ConsolidationStep.CAPTURE_PROGRESS
            db.withTransaction { ReprocessProgress(db, deps.files).capture(documentId) }
            if (trace.active) trace.step = ConsolidationStep.LOAD_SNAPSHOT
            val progressCallback: suspend (DocStatus, Float) -> Unit = { status, progress ->
                stage = runCatching { FailureStage.valueOf(status.name) }.getOrDefault(stage)
                onProgress(status, progress)
            }
            val count = if (trace.consolidationOnly) runCachedConsolidation(doc, progressCallback) else run(doc, progressCallback)
            ProcessOutcome.Ready(count)
        } catch (e: CancellationException) {
            Log.i(TAG, "doc=$documentId cancelled")
            throw e
        } catch (t: Throwable) {
            val current = db.documentDao().getById(documentId)
            // Completion committed: a later notification/file cleanup failure cannot undo READY.
            if (current?.status == DocStatus.READY)
                return ProcessOutcome.Ready(db.cardDao().getByDocument(documentId).size)
            val pe = PipelineException.from(t)
            val rejection = (t as? LlmException.BadRequest) ?: (pe.cause as? LlmException.BadRequest)
            val failure = FailureDiagnostic(doc.sourceType, pe.stage ?: stage, pe.code,
                rejection?.category, rejection?.httpStatus?.takeIf { it in 100..599 },
                rejection?.status?.takeIf { it in 100..599 }, now,
                ((t as? LlmException) ?: (pe.cause as? LlmException))?.diagnostic,
                if (trace.active) trace.failure(t) else null, origin = DiagnosticOrigin.TERMINAL_PROCESS_FAILURE)
            Log.w(TAG, "doc=$documentId failed: ${failure.stage} ${pe.code}")
            db.withTransaction {
                db.documentDao().setFailure(documentId, failure.encode())
                db.documentDao().setStatus(documentId, DocStatus.FAILED, current?.progress ?: 0f, pe.code.name, pe.code.name, now)
            }
            ProcessOutcome.Failed(pe.code, pe.code.name)
        }
    }

    private class Ctx(
        val doc: DocumentEntity,
        val config: EffectiveConfig,
        val catalog: CatalogView?,
        val lang: Lang,
        val targetLang: Lang,
        val sentences: List<SentenceDraft>,
        val sentenceIds: Map<Int, Long>,
        val progress: Progress,
        val onProgress: suspend (DocStatus, Float) -> Unit,
    ) {
        val byIdx: Map<Int, SentenceDraft> = sentences.associateBy { it.idx }
        var brief: Brief? = null
        var topic: TopicSnapshot? = null
        fun sentence(idx: Int): String? = byIdx[idx]?.text
        fun text(range: IntRange): String = sentences.filter { it.idx in range }.joinToString(" ") { it.text }
    }

    private suspend fun run(doc0: DocumentEntity, onProgress: suspend (DocStatus, Float) -> Unit): Int {
        val id = doc0.id
        var snapshot = doc0.modelsSnapshotJson?.let { ProcessingSnapshot.decode(it) }
            ?: ProcessingSnapshot.capture(deps.configProvider(), deps.catalogProvider()).also {
                db.documentDao().setPipelineSnapshot(id, it.encode(), PIPELINE_VERSION, now)
            }
        if (snapshot.legacyParametersUnknown && !snapshot.legacyCapabilitiesResolved)
            snapshot = snapshot.resolveLegacyCapabilities(deps.catalogProvider())
        if (doc0.modelsSnapshotJson != null && snapshot.legacyParametersUnknown && doc0.modelsSnapshotJson != snapshot.encode())
            db.documentDao().setPipelineSnapshot(id, snapshot.encode(), PIPELINE_VERSION, now)
        val config = snapshot.config
        val catalog: CatalogView = snapshot


        // --- FETCHING / TRANSCRIBING -------------------------------------------------------
        val progressGuess = Progress(hasTranscription = doc0.sourceType == SourceType.YOUTUBE)
        val (lang, targetLang, sentences) = fetchStage(doc0, config, progressGuess, onProgress)
        val doc = db.documentDao().getById(id) ?: error("document vanished")
        val progress = Progress(hasTranscription = doc.audioSeconds > 0)
        val sentenceIds = db.sentenceDao().getByDocument(id).associate { it.idx to it.id }
        val ctx = Ctx(doc, config, catalog, lang, targetLang, sentences, sentenceIds, progress, onProgress)

        briefStage(ctx)
        translateStage(ctx)
        extractStage(ctx)
        val count = consolidateAndBuildStage(ctx)

        onProgress(DocStatus.READY, 1f)
        Log.i(TAG, "doc=$id READY cards=$count")
        return count
    }

    /** Network-free recovery: no configuration refresh, extraction, BRIEF, translation or LLM. */
    private suspend fun runCachedConsolidation(doc: DocumentEntity, onProgress: suspend (DocStatus, Float) -> Unit): Int {
        var snapshot = doc.modelsSnapshotJson?.let { ProcessingSnapshot.decode(it) } ?: throw ConsolidationCacheMissing()
        val trace = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[ConsolidationTrace])
        if (!trace.cacheOnly && snapshot.legacyParametersUnknown && !snapshot.legacyCapabilitiesResolved) {
            snapshot = snapshot.resolveLegacyCapabilities(deps.catalogProvider())
            db.documentDao().setPipelineSnapshot(doc.id, snapshot.encode(), PIPELINE_VERSION, now)
        }
        val lang = Lang.fromCode(doc.sourceLang) ?: throw ConsolidationCacheMissing()
        val target = Lang.fromCode(doc.targetLang) ?: throw ConsolidationCacheMissing()
        val stored = db.sentenceDao().getByDocument(doc.id)
        if (stored.isEmpty()) throw ConsolidationCacheMissing()
        val ctx = Ctx(doc, snapshot.config, snapshot, lang, target,
            stored.map { SentenceDraft(it.idx, it.paragraphIdx, it.text) }, stored.associate { it.idx to it.id },
            Progress(doc.audioSeconds > 0), onProgress)
        // Cache-only recovery also must not build READY from a detectably corrupt translation.
        // This is a local check, never a request to regenerate missing legacy evidence.
        try {
            for (job in db.jobDao().getByKind(doc.id, JobKind.TRANSLATE).filter { it.status == JobStatus.DONE })
                verifyTranslationCache(ctx, job, job.rangeStart!!..job.rangeEnd!!)
            TranslationIntegrity.check(ctx.sentences, db.segmentDao().getByDocument(doc.id).map {
                TranslatedSegment(it.firstSentenceIdx, it.lastSentenceIdx, it.translation)
            }, requireProof = false)
        } catch (e: LlmException.InvalidResponse) {
            throw PipelineException(ErrorCode.INVALID_RESPONSE, "translation cache integrity", e, FailureStage.TRANSLATING)
        }
        return consolidateAndBuildStage(ctx).also { onProgress(DocStatus.READY, 1f) }
    }

    // ---------------------------------------------------------------------------------------
    // Стадия получения текста: extractor → (транскрипция) → source.txt → язык → предложения.
    // ---------------------------------------------------------------------------------------
    private suspend fun fetchStage(doc: DocumentEntity, config: EffectiveConfig, progress: Progress, onProgress: suspend (DocStatus, Float) -> Unit): Triple<Lang, Lang, List<SentenceDraft>> {
        val id = doc.id
        val targetLang = Lang.fromCode(doc.targetLang) ?: throw PipelineException(ErrorCode.UNKNOWN, "bad target lang")
        val existing = db.sentenceDao().getByDocument(id)
        val storedLang = Lang.fromCode(doc.sourceLang)
        if (existing.isNotEmpty() && storedLang != null) {
            return Triple(storedLang, targetLang, existing.map { SentenceDraft(it.idx, it.paragraphIdx, it.text) })
        }
        setStatus(id, DocStatus.FETCHING, progress.start(DocStatus.FETCHING), onProgress)
        val sourceFile = deps.files.sourceText(id)
        val storedOrigin = deps.files.textProvenance(id)
        var langHint: Lang? = Lang.fromCode(storedOrigin.sttLanguage)
            ?: Lang.fromCode(storedOrigin.trackLanguage?.substringBefore('-')) ?: storedLang
        var title: String? = null
        if (!sourceFile.isFile) {
            val extractor = deps.extractors[doc.sourceType] ?: throw PipelineException(ErrorCode.UNKNOWN, "no extractor for ${doc.sourceType}")
            when (val extracted = extractor.extract(doc, deps.files)) {
                is Extracted.Text -> {
                    deps.files.writeSourceText(id, extracted.text, extracted.provenance)
                    title = extracted.title
                    langHint = extracted.langHint ?: langHint
                }
                is Extracted.Audio -> {
                    if (!deps.sttReady()) throw PipelineException(ErrorCode.STT_ACCESS_REQUIRED, "Для распознавания аудио нужен отдельный доступ OpenRouter. ChatGPT plan не поддерживает STT; платная подмена не выполняется.")
                    title = extracted.title
                    langHint = extracted.langHint ?: langHint
                    if (langHint == targetLang) throw PipelineException(ErrorCode.SAME_LANGUAGE,
                        "source == target (${targetLang.code})", stage = FailureStage.LANGUAGE)
                    val text = transcribeStage(doc, extracted, langHint, config, progress, onProgress)
                    deps.files.writeSourceText(id, text, extracted.provenance.copy(
                        sttLanguage = langHint?.code, sttModel = config.role(ModelRole.STT).model))
                }
            }
            onProgress(DocStatus.FETCHING, progress.within(DocStatus.FETCHING, 1f))
        }
        val text = sourceFile.readText()
        val lang = langHint ?: LangDetector.detect(text)
            ?: throw PipelineException(ErrorCode.LANG_UNDETECTED, "script share below threshold", stage = FailureStage.LANGUAGE)
        if (lang == targetLang) throw PipelineException(ErrorCode.SAME_LANGUAGE, "source == target (${lang.code})", stage = FailureStage.LANGUAGE)
        val sentences = deps.segmenter.segment(text, lang, pdfMode = doc.sourceType == SourceType.PDF)
        if (sentences.isEmpty()) throw PipelineException(ErrorCode.EMPTY_TEXT, "no sentences")
        db.withTransaction {
            db.sentenceDao().deleteByDocument(id)
            db.sentenceDao().insertAll(sentences.map { SentenceEntity(documentId = id, idx = it.idx, paragraphIdx = it.paragraphIdx, text = it.text, segmentId = null) })
            db.documentDao().setSourceLang(id, lang.code, now)
            db.documentDao().setWordCount(id, WordCounter.count(sentences), now)
            if (title != null && doc.sourceType != SourceType.TEXT) db.documentDao().setTitle(id, title.take(MAX_TITLE), now)
        }
        Log.i(TAG, "doc=$id fetched: lang=${lang.code} sentences=${sentences.size} words=${WordCounter.count(sentences)}")
        return Triple(lang, targetLang, sentences)
    }

    private suspend fun transcribeStage(doc: DocumentEntity, audio: Extracted.Audio, langHint: Lang?, config: EffectiveConfig, progress: Progress, onProgress: suspend (DocStatus, Float) -> Unit): String {
        val id = doc.id
        val transcriber = deps.transcriber ?: throw PipelineException(ErrorCode.TRANSCRIPTION, "transcriber unavailable")
        val segmenter = deps.audioSegmenter ?: throw PipelineException(ErrorCode.TRANSCRIPTION, "audio segmenter unavailable")
        val lang = langHint ?: throw PipelineException(ErrorCode.YOUTUBE_NO_LANG, "video language unknown")
        setStatus(id, DocStatus.TRANSCRIBING, progress.start(DocStatus.TRANSCRIBING), onProgress)
        val parts = segmenter.split(audio.file, deps.files.audioDir(id))
        if (parts.isEmpty()) throw PipelineException(ErrorCode.TRANSCRIPTION, "no audio parts")
        val model = config.role(ModelRole.STT).model
        // A retry downloads audio again. Bind cached parts to bytes, language and model,
        // before making any new request; index alone is not an identity.
        val audioHash = pro.perfectproduct.cramin.util.Hashing.sha256Hex(audio.file)
        val originHash = pro.perfectproduct.cramin.util.Hashing.sha256Hex(
            LlmJson.strict.encodeToString(pro.perfectproduct.cramin.ingest.TextProvenance.serializer(), audio.provenance).toByteArray(Charsets.UTF_8))
        val binding = "stt-v2:$audioHash:$originHash:${lang.code}:$model:${parts.size}"
        val previous = db.jobDao().getByKind(id, JobKind.STT).filter { it.status == JobStatus.DONE || it.finishReason != null }
        if (previous.any { it.finishReason != binding || it.idx !in parts.indices }) {
            throw PipelineException(ErrorCode.TRANSCRIPTION, "cached audio identity unavailable or changed")
        }
        val texts = ArrayList<String>()
        for ((i, part) in parts.withIndex()) {
            val job = ensureJob(id, JobKind.STT, i, part.durationSeconds, null, model).let { saved ->
                // Persist identity before the first request, including a failed/interrupted attempt.
                if (saved.status == JobStatus.DONE) saved else saved.copy(finishReason = binding).also { db.jobDao().update(it) }
            }
            val text = if (job.status == JobStatus.DONE && job.responseJson != null) {
                job.responseJson
            } else {
                val t = try {
                    transcriber.transcribe(part, lang, model)
                } catch (e: PipelineException) {
                    db.jobDao().update(job.copy(status = JobStatus.FAILED, attempts = job.attempts + 1, updatedAt = now))
                    throw e
                }
                db.jobDao().update(job.copy(status = JobStatus.DONE, attempts = job.attempts + 1, responseJson = t.text, finishReason = binding, costUsd = t.costUsd, updatedAt = now))
                deps.usage.refreshDocumentTotals(id)
                t.text
            }
            texts += text
            onProgress(DocStatus.TRANSCRIBING, progress.within(DocStatus.TRANSCRIBING, (i + 1f) / parts.size))
        }
        db.documentDao().setAudioSeconds(id, audio.durationSeconds.takeIf { it > 0 } ?: parts.sumOf { it.durationSeconds }, now)
        // Временные файлы удаляются после успешной транскрипции (SPEC §8).
        deps.files.audioDir(id).deleteRecursively()
        audio.file.delete()
        Log.i(TAG, "doc=$id transcribed parts=${parts.size}")
        return texts.joinToString("\n\n")
    }

    // ---------------------------------------------------------------------------------------
    // Стадия 1: бриф (SPEC §6.4). Неудача — фолбэк §6.10, документ не падает.
    // ---------------------------------------------------------------------------------------
    private suspend fun briefStage(ctx: Ctx) {
        val id = ctx.doc.id
        setStatus(id, DocStatus.BRIEFING, ctx.progress.start(DocStatus.BRIEFING), ctx.onProgress)
        val role = ctx.config.role(ModelRole.BRIEF)
        val job = ensureJob(id, JobKind.BRIEF, 0, null, null, role.model)
        if (job.status == JobStatus.DONE && job.responseJson != null) {
            ctx.brief = runCatching { LlmJson.strict.decodeFromString<StoredBrief>(job.responseJson).brief }.getOrNull()
            return
        }
        val paragraphs = ctx.sentences.groupBy { it.paragraphIdx }.values.map { p -> p.joinToString(" ") { it.text } }
        val request = BriefRequestFactory.build(paragraphs, ctx.lang, ctx.targetLang, ctx.config, ctx.catalog)
        val brief = try {
            callParsed<Brief>(job, request) { b ->
                if (b.title.isBlank()) throw LlmException.InvalidResponse("empty title") else b.copy(glossary = b.glossary.filter { it.src.isNotBlank() && it.tgt.isNotBlank() })
            }
        } catch (e: LlmException.InvalidResponse) {
            // Бриф не удался: продолжаем без него (SPEC §6.4, §6.10).
            Log.w(TAG, "doc=$id brief invalid after retry; fallback")
            markJob(job, JobStatus.FAILED, LlmJson.strict.encodeToString(StoredBrief(null, failed = true)), "invalid")
            null
        }
        if (brief != null) {
            markJob(job, JobStatus.DONE, LlmJson.strict.encodeToString(StoredBrief(brief)), null)
            val title = brief.title.trim().take(MAX_TITLE)
            val emoji = brief.emoji.trim().ifEmpty { DEFAULT_EMOJI }.take(8)
            db.documentDao().setBrief(id, title, emoji, LlmJson.strict.encodeToString(brief), now)
            ctx.brief = brief
        }
        ctx.onProgress(DocStatus.BRIEFING, ctx.progress.within(DocStatus.BRIEFING, 1f))
    }

    // ---------------------------------------------------------------------------------------
    // Стадия 2: перевод секциями, последовательно (SPEC §6.5).
    // ---------------------------------------------------------------------------------------
    private suspend fun translateStage(ctx: Ctx) {
        val id = ctx.doc.id
        setStatus(id, DocStatus.TRANSLATING, ctx.progress.start(DocStatus.TRANSLATING), ctx.onProgress)
        val role = ctx.config.role(ModelRole.TRANSLATE)
        val maxCompletion = role.maxTokens ?: ctx.catalog?.find(role.model)?.maxCompletionTokens ?: DEFAULT_MAX_COMPLETION_TOKENS
        var jobs = db.jobDao().getByKind(id, JobKind.TRANSLATE)
        if (jobs.isEmpty()) {
            val sectionWords = SectionPlanner.sectionWords(ctx.config.pipeline.translateMaxSectionWords, maxCompletion, ctx.config.pipeline.tokensPerWord(ctx.targetLang.code))
            val plan = SectionPlanner.plan(ctx.sentences, sectionWords)
            db.jobDao().insertAll(plan.mapIndexed { i, r -> newJob(id, JobKind.TRANSLATE, i, r.first, r.last, role.model) })
            jobs = db.jobDao().getByKind(id, JobKind.TRANSLATE)
            Log.i(TAG, "doc=$id translate plan: sections=${plan.size} sectionWords=$sectionWords")
        }
        for ((n, job) in jobs.withIndex()) {
            val range = job.rangeStart!!..job.rangeEnd!! // диапазоны заданы при планировании
            if (job.status == JobStatus.DONE) {
                verifyTranslationCache(ctx, job, range)
                continue
            }
            val context = continuationContext(id, range.first)
            val segments = translateRange(ctx, job, range, context, maxCompletion)
            db.withTransaction {
                db.segmentDao().deleteFrom(id, range.first)
                for (s in segments) {
                    val segId = db.segmentDao().insert(SegmentEntity(documentId = id, firstSentenceIdx = s.from, lastSentenceIdx = s.to, translation = s.t))
                    db.sentenceDao().assignSegment(id, s.from, s.to, segId)
                }
                markJob(db.jobDao().getById(job.id) ?: job, JobStatus.DONE,
                    LlmJson.strict.encodeToString(StoredTranslation(segments, TranslationIntegrity.VERSION)), null)
            }
            ctx.onProgress(DocStatus.TRANSLATING, ctx.progress.within(DocStatus.TRANSLATING, (n + 1f) / jobs.size))
        }
    }

    /** Validate DONE evidence before it is consumed by EXTRACT; never silently regenerate old cache. */
    private suspend fun verifyTranslationCache(ctx: Ctx, job: JobEntity, range: IntRange) {
        val stored = job.responseJson?.let { LlmJson.parse<StoredTranslation>(it) }
        if (stored?.integrityVersion != null && stored.integrityVersion != TranslationIntegrity.VERSION)
            throw LlmException.InvalidResponse("translation integrity: unsupported cache version")
        val actual = db.segmentDao().getByDocument(ctx.doc.id)
            .filter { it.firstSentenceIdx >= range.first && it.lastSentenceIdx <= range.last }
            .map { TranslatedSegment(it.firstSentenceIdx, it.lastSentenceIdx, it.translation) }
        if (stored != null && stored.seg.map { it.copy(sourceIds = null) } != actual)
            throw LlmException.InvalidResponse("translation integrity: cache differs from segments")
        val segments = stored?.seg ?: actual
        val validation = TranslationValidator.validate(range, segments)
        if (!validation.isComplete || validation.rejected != 0)
            throw LlmException.InvalidResponse("translation integrity: cache coverage")
        val evidence = TranslationIntegrity.check(ctx.sentences.filter { it.idx in range }, segments,
            requireProof = stored?.integrityVersion == TranslationIntegrity.VERSION)
        if (evidence == TranslationIntegrity.Evidence.UNKNOWN)
            Log.i(TAG, "job=${job.id} translation integrity UNKNOWN (legacy cache)")
    }

    /** Последние сегменты предыдущей секции, покрывающие ≥ 3 предложений, как контекст продолжения. */
    private suspend fun continuationContext(id: Long, firstIdx: Int): List<ContextLine> {
        if (firstIdx == 0) return emptyList()
        val segments = db.segmentDao().getByDocument(id).filter { it.lastSentenceIdx < firstIdx }
        val sentences = db.sentenceDao().getByDocument(id).associateBy { it.idx }
        val picked = ArrayList<SegmentEntity>()
        var covered = 0
        for (s in segments.asReversed()) {
            picked.add(0, s)
            covered += s.lastSentenceIdx - s.firstSentenceIdx + 1
            if (covered >= CONTEXT_SENTENCES) break
        }
        return picked.map { s ->
            ContextLine(s.firstSentenceIdx, s.lastSentenceIdx, (s.firstSentenceIdx..s.lastSentenceIdx).mapNotNull { sentences[it]?.text }.joinToString(" "), s.translation)
        }
    }

    private class TruncatedResponse : Exception()

    private suspend fun translateRange(ctx: Ctx, job: JobEntity, range: IntRange, context: List<ContextLine>, maxCompletion: Int, allowFill: Boolean = true): List<TranslatedSegment> {
        return try {
            // Also bound pending jobs planned by older versions, without rewriting their ranges/cache.
            if (range.count() > SectionPlanner.MAX_SECTION_SENTENCES) throw TruncatedResponse()
            translateRangeAttempt(ctx, job, range, context, maxCompletion, allowFill)
        }
        catch (_: TruncatedResponse) {
            val halves = SectionPlanner.splitHalf(ctx.sentences, range)
                ?: throw LlmException.InvalidResponse("length on a single sentence")
            val first = translateRange(ctx, job, halves.first, context, maxCompletion, allowFill)
            val nextContext = first.takeLast(CONTEXT_SENTENCES).map { segment ->
                ContextLine(segment.from, segment.to, (segment.from..segment.to).mapNotNull { ctx.sentence(it) }.joinToString(" "), segment.t)
            }
            first + translateRange(ctx, job, halves.second, nextContext, maxCompletion, allowFill)
        }
    }

    private suspend fun translateRangeAttempt(ctx: Ctx, job: JobEntity, range: IntRange, context: List<ContextLine>, maxCompletion: Int, allowFill: Boolean): List<TranslatedSegment> {
        val role = ctx.config.role(ModelRole.TRANSLATE)
        val sentences = ctx.sentences.filter { it.idx in range }
        val glossary = glossaryFor(ctx, ctx.text(range))
        val request = LlmRequest(
            ModelRole.TRANSLATE, role.model, Prompts.TRANSLATE,
            Messages.translate(ctx.lang, ctx.targetLang, ctx.brief, glossary, context, sentences),
            Schemas.TRANSLATE_NAME, Schemas.TRANSLATE, role.temperature, maxCompletion, role.reasoning,
            provider = role.provider,
        )
        val response = callRaw(job, request)
        val parsed = parseOrRetry<TranslateResponse>(job, request, response)
        TranslationIntegrity.check(sentences, parsed.first.seg, requireProof = true)
        var validation = TranslationValidator.validate(range, parsed.first.seg)
        var accepted = validation.accepted
        if (!validation.isComplete) {
            if (!allowFill) throw LlmException.InvalidResponse("translation holes remain after fill")
            // One fill per contiguous hole run. Never offer a non-contiguous range for merging.
            Log.i(TAG, "doc=${ctx.doc.id} section ${range.first}-${range.last}: ${validation.missing.size} holes, ${validation.rejected} rejected")
            val missing = validation.missing.toSet()
            val holeContext = accepted.filter { s -> missing.any { m -> m in (s.from - 2)..(s.to + 2) } }
                .map { s -> ContextLine(s.from, s.to, (s.from..s.to).mapNotNull { ctx.sentence(it) }.joinToString(" "), s.t) }
            val runs = ArrayList<IntRange>()
            for (idx in validation.missing) {
                val last = runs.lastOrNull()
                if (last != null && idx == last.last + 1) runs[runs.lastIndex] = last.first..idx
                else runs += idx..idx
            }
            for (hole in runs) {
                val fill = translateRange(ctx, job, hole, context + holeContext, maxCompletion, allowFill = false)
                accepted = TranslationValidator.merge(accepted, fill)
            }
            validation = TranslationValidator.validate(range, accepted)
            if (!validation.isComplete) throw LlmException.InvalidResponse("holes remain: ${validation.missing.size}")
        }
        return validation.accepted
    }

    // ---------------------------------------------------------------------------------------
    // Стадия 3: извлечение по чанкам, параллельно (SPEC §6.6).
    // ---------------------------------------------------------------------------------------
    private suspend fun extractStage(ctx: Ctx) {
        val id = ctx.doc.id
        setStatus(id, DocStatus.EXTRACTING, ctx.progress.start(DocStatus.EXTRACTING), ctx.onProgress)
        val role = ctx.config.role(ModelRole.EXTRACT)
        val segments = db.segmentDao().getByDocument(id)
        val spans = segments.map { s -> SegmentSpan(s.firstSentenceIdx, s.lastSentenceIdx, ctx.sentences.filter { it.idx in s.firstSentenceIdx..s.lastSentenceIdx }.sumOf { it.words }) }
        var jobs = db.jobDao().getByKind(id, JobKind.EXTRACT)
        if (jobs.isEmpty()) {
            val plan = ChunkPlanner.plan(spans, ctx.config.pipeline.extractChunkWords)
            db.jobDao().insertAll(plan.mapIndexed { i, r -> newJob(id, JobKind.EXTRACT, i, r.first, r.last, role.model) })
            jobs = db.jobDao().getByKind(id, JobKind.EXTRACT)
            Log.i(TAG, "doc=$id extract plan: chunks=${plan.size}")
        }
        val pending = jobs.filter { it.status != JobStatus.DONE }
        val semaphore = Semaphore(ctx.config.pipeline.extractConcurrency.coerceAtLeast(1))
        var done = jobs.size - pending.size
        val lock = Any()
        coroutineScope {
            for (job in pending) {
                launch {
                    semaphore.withPermit {
                        val range = job.rangeStart!!..job.rangeEnd!! // диапазоны заданы при планировании
                        val units = extractRange(ctx, job, segments, spans, range)
                        markJob(db.jobDao().getById(job.id) ?: job, JobStatus.DONE, LlmJson.strict.encodeToString(StoredExtraction(units)), null)
                        val d = synchronized(lock) { ++done }
                        ctx.onProgress(DocStatus.EXTRACTING, ctx.progress.within(DocStatus.EXTRACTING, d.toFloat() / jobs.size))
                    }
                }
            }
        }
    }

    private suspend fun extractRange(ctx: Ctx, job: JobEntity, segments: List<SegmentEntity>, spans: List<SegmentSpan>, range: IntRange): List<ExtractedUnit> {
        return try { extractRangeAttempt(ctx, job, segments, spans, range) }
        catch (_: TruncatedResponse) {
            val halves = ChunkPlanner.splitHalf(spans, range)
                ?: throw LlmException.InvalidResponse("length on a single segment")
            extractRange(ctx, job, segments, spans, halves.first) + extractRange(ctx, job, segments, spans, halves.second)
        }
    }

    private suspend fun extractRangeAttempt(ctx: Ctx, job: JobEntity, segments: List<SegmentEntity>, spans: List<SegmentSpan>, range: IntRange): List<ExtractedUnit> {
        val role = ctx.config.role(ModelRole.EXTRACT)
        val pairs = segments.filter { it.firstSentenceIdx >= range.first && it.lastSentenceIdx <= range.last }
            .map { s -> SegmentPair(ctx.sentences.filter { it.idx in s.firstSentenceIdx..s.lastSentenceIdx }, s.translation) }
        val request = LlmRequest(
            ModelRole.EXTRACT, role.model, Prompts.EXTRACT,
            Messages.extract(ctx.lang, ctx.targetLang, glossaryFor(ctx, ctx.text(range)), pairs),
            Schemas.EXTRACT_NAME, Schemas.EXTRACT, role.temperature, role.maxTokens, role.reasoning,
            provider = role.provider,
        )
        val response = callRaw(job, request)
        if (response.truncated) {
            val halves = ChunkPlanner.splitHalf(spans, range) ?: throw LlmException.InvalidResponse("length on a single segment")
            Log.i(TAG, "doc=${ctx.doc.id} chunk ${range.first}-${range.last} truncated; split")
            return extractRange(ctx, job, segments, spans, halves.first) + extractRange(ctx, job, segments, spans, halves.second)
        }
        return parseOrRetry<ExtractResponse>(job, request, response).first.u
    }

    // ---------------------------------------------------------------------------------------
    // Стадия 4: слияние, консолидация (SPEC §6.7–§6.8), примеры и вхождения (§6.9), запись карточек.
    // ---------------------------------------------------------------------------------------
    private suspend fun consolidateAndBuildStage(ctx: Ctx): Int {
        val id = ctx.doc.id
        setStatus(id, DocStatus.CONSOLIDATING, ctx.progress.start(DocStatus.CONSOLIDATING), ctx.onProgress)
        val trace = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[ConsolidationTrace])
        trace.active = true
        trace.step = ConsolidationStep.LOAD_INPUTS
        trace.counts["sentences"] = ctx.sentences.size
        val role = ctx.config.role(ModelRole.CONSOLIDATE)
        val segments = db.segmentDao().getByDocument(id)
        trace.counts["segments"] = segments.size
        val segmentByIdx = HashMap<Int, SegmentEntity>()
        for (s in segments) for (i in ctx.byIdx.keys) if (i in s.firstSentenceIdx..s.lastSentenceIdx) {
            if (segmentByIdx.put(i, s) != null) throw ConsolidationCacheInvalid()
        }
        val unitContext = object : UnitContext {
            override fun sentence(idx: Int) = ctx.sentence(idx)
            override fun segmentTranslation(idx: Int) = segmentByIdx[idx]?.translation
        }
        val stoplist = deps.stoplists.forLang(ctx.lang)
        val units = ArrayList<ValidatedUnit>()
        val extractionJobs = db.jobDao().getByKind(id, JobKind.EXTRACT)
        trace.counts["extractionJobs"] = extractionJobs.size
        trace.counts["extractionDone"] = extractionJobs.count { it.status == JobStatus.DONE }
        if (extractionJobs.isEmpty() || ctx.sentences.any { segmentByIdx[it.idx] == null }) throw ConsolidationCacheMissing()
        val covered = hashSetOf<Int>()
        var rawTotal = 0
        for (job in extractionJobs) {
            trace.step = ConsolidationStep.READ_EXTRACTION
            trace.counts["extractionIndex"] = job.idx
            if (job.status != JobStatus.DONE || job.responseJson == null) throw ConsolidationCacheMissing()
            val raw = readConsolidationCache { LlmJson.strict.decodeFromString<StoredExtraction>(job.responseJson).u }
            val start = job.rangeStart ?: throw ConsolidationCacheInvalid()
            val end = job.rangeEnd ?: throw ConsolidationCacheInvalid()
            if (start > end || start !in ctx.byIdx || end !in ctx.byIdx) throw ConsolidationCacheInvalid()
            val indices = ctx.byIdx.keys.filter { it in start..end }
            if (indices.any { it in covered }) throw ConsolidationCacheInvalid()
            covered += indices
            trace.step = ConsolidationStep.VALIDATE_UNITS
            trace.counts["rawUnitsLastJob"] = raw.size
            rawTotal += raw.size
            trace.counts["rawUnitsTotal"] = rawTotal
            units += UnitValidator.validate(start..end, raw, unitContext, stoplist, ctx.lang, ctx.targetLang)
        }
        if (covered != ctx.byIdx.keys) throw ConsolidationCacheMissing()
        trace.step = ConsolidationStep.MERGE_UNITS
        trace.counts["validatedUnits"] = units.size
        val cards = UnitMerger.merge(units, ctx.lang, ctx.targetLang)
        trace.counts["lexicalGroups"] = cards.size
        Log.i(TAG, "doc=$id units=${units.size} cards=${cards.size} toConsolidate=${cards.count { it.needsConsolidation }}")

        val classifier = TopicClassifier(deps)
        if (trace.cacheOnly) {
            ctx.topic = ctx.doc.topicSnapshotJson?.let { Json.decodeFromString<TopicSnapshot>(it) }
            if (ctx.doc.pipelineVersion >= 2 && ctx.topic == null) throw ConsolidationCacheUnfinished()
        } else {
            val config = if (ModelRole.TOPIC in ctx.config.roles) ctx.config else {
                val fallback = deps.legacyTopicRole() ?: deps.configProvider().role(ModelRole.TOPIC)
                if (fallback.provider != ctx.config.provider) throw LlmException.InvalidResponse("legacy topic provider mismatch")
                ctx.config.copy(roles = ctx.config.roles + (ModelRole.TOPIC to fallback))
            }
            ctx.topic = classifier.snapshot(id, config, ctx.catalog)
        }
        val senses = HashMap<String, List<SenseDraft>>()
        for (card in cards) if (!card.needsConsolidation) senses[card.lemmaKey] = Consolidation.single(card)
        val batches = Consolidation.batches(cards)
        val legacy = (ctx.catalog as? ProcessingSnapshot)?.legacyParametersUnknown == true
        val legacyBatches = if (legacy) Consolidation.batches(cards.map { card ->
            card.copy(translations = card.units.withIndex().groupBy(
                { TextNormalizer.translationKey(it.value.translation, ctx.targetLang) }, { it.index }))
        }) else emptyList()
        trace.counts["batches"] = batches.size
        for ((i, batch) in batches.withIndex()) {
            trace.step = ConsolidationStep.BUILD_BATCH
            trace.counts["batchIndex"] = i
            val job = ensureJob(id, JobKind.CONSOLIDATE, i, null, null, role.model)
            trace.savedBatchState = when {
                job.status != JobStatus.DONE && job.finishReason == "length" -> SavedBatchState.UNFINISHED_LENGTH
                job.status != JobStatus.DONE -> SavedBatchState.UNFINISHED_OTHER
                job.responseJson != null -> SavedBatchState.DONE_JSON
                job.finishReason == "fallback" -> SavedBatchState.DONE_FALLBACK
                else -> SavedBatchState.MISSING_RESPONSE
            }
            trace.counts["savedBatchAttempts"] = job.attempts
            val built = Consolidation.buildBatch(batch) { ctx.sentence(it) }
            val parts = readConsolidationCache { ConsolidationParts.decode(job.responseJson) }
            if (job.status == JobStatus.DONE && parts == null) {
                trace.step = ConsolidationStep.READ_CACHED_RESPONSE
                if (job.responseJson == null && job.finishReason != "fallback") throw ConsolidationCacheMissing()
                val response = job.responseJson?.let { Consolidation.readCached(it, built,
                    legacyBatches.getOrNull(i)?.let { old -> Consolidation.buildBatch(old) { ctx.sentence(it) } }, legacy) }
                senses.putAll(Consolidation.apply(batch, built, response))
            } else {
                senses.putAll(resumeConsolidationParts(ctx, job, batch, parts))
            }
            ctx.onProgress(DocStatus.CONSOLIDATING, ctx.progress.within(DocStatus.CONSOLIDATING, (i + 1f) / (batches.size + 1)))
        }

        // Normalize final meanings BEFORE classification; category never participates in identity.
        for (card in cards) senses[card.lemmaKey] = senses.getValue(card.lemmaKey)
            .groupBy { MeaningKey.of(it.translation) }.values.map { same ->
                SenseDraft(same.first().translation, same.flatMap { it.unitIndices }.distinct(),
                    same.map { it.category }.distinct().singleOrNull())
            }
        if (ctx.topic != null) {
            fun itemId(card: MergedCard, sense: SenseDraft) = pro.perfectproduct.cramin.util.Hashing.sha256Hex(
                (card.lemmaKey + "\u0000" + MeaningKey.of(sense.translation)).toByteArray())
            val missing = cards.flatMap { card -> senses.getValue(card.lemmaKey).filter { it.category == null }.map { sense ->
                pro.perfectproduct.cramin.llm.TopicItem(itemId(card, sense), card.lemma, card.pos.name,
                    sense.translation, sense.translation, sense.unitIndices.mapNotNull { ctx.sentence(card.units[it].sentenceIdx) }.distinct().take(3))
            } }
            val classified = classifier.classify(id, requireNotNull(ctx.topic), missing, allowNetwork = !trace.cacheOnly)
            for (card in cards) senses[card.lemmaKey] = senses.getValue(card.lemmaKey).map { sense ->
                sense.copy(category = sense.category ?: classified.getValue(itemId(card, sense)))
            }
        }
        trace.step = ConsolidationStep.CAPTURE_PROGRESS
        val snapshot = db.withTransaction { ReprocessProgress(db, deps.files).capture(id) }
        val count = writeCards(ctx, cards, senses, snapshot)
        return count
    }

    private suspend fun resumeConsolidationParts(ctx: Ctx, job: JobEntity, cards: List<MergedCard>, saved: ConsolidationParts?): Map<String, List<SenseDraft>> {
        val trace = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[ConsolidationTrace])
        val root = Consolidation.buildBatch(cards) { ctx.sentence(it) }
        var state = saved ?: ConsolidationParts(inputHash = Consolidation.inputHash(root))
        if (state.inputHash != Consolidation.inputHash(root)) throw ConsolidationCacheInvalid()
        // Old length at the root, or a crash between receiving length and persisting the split.
        val historicalLengthPath = if (job.finishReason == "length") saved?.active ?: "" else null
        suspend fun save(next: ConsolidationParts) {
            state = next
            markJob(db.jobDao().getById(job.id) ?: job, JobStatus.PENDING, state.encode(), "partial")
            deps.checkpoint("consolidationPartSaved")
        }
        suspend fun visit(path: String, group: List<MergedCard>): Map<String, List<SenseDraft>> {
            trace.partPath = path
            val built = Consolidation.buildBatch(group) { ctx.sentence(it) }
            trace.counts["partDepth"] = path.length
            trace.counts["partGroups"] = group.size
            trace.counts["completedParts"] = state.done.size
            trace.step = ConsolidationStep.READ_CACHED_RESPONSE
            state.done[path]?.let { raw ->
                return Consolidation.apply(group, built, if (raw == "fallback") null else Consolidation.readCached(raw, built, null, false))
            }
            if (path in state.blocked) throw ConsolidationLimit()
            suspend fun split(): Map<String, List<SenseDraft>> {
                if (group.size == 1) {
                    save(state.copy(blocked = state.blocked + path, active = null))
                    throw ConsolidationLimit()
                }
                if (path !in state.split) save(state.copy(split = state.split + path, active = null))
                val middle = group.size / 2
                return visit(path + "L", group.take(middle)) + visit(path + "R", group.drop(middle))
            }
            if (path in state.split) return split()
            if (trace.cacheOnly) {
                trace.step = ConsolidationStep.REQUEST
                throw ConsolidationCacheUnfinished()
            }
            if (historicalLengthPath == path) return split()
            val role = ctx.config.role(ModelRole.CONSOLIDATE)
            var request = LlmRequest(ModelRole.CONSOLIDATE, role.model, Prompts.CONSOLIDATE,
                Messages.consolidate(ctx.lang, ctx.targetLang, built.items), Schemas.CONSOLIDATE_NAME,
                Schemas.CONSOLIDATE, role.temperature, role.maxTokens, role.reasoning, provider = role.provider)
            val integrated = ctx.topic?.takeIf { it.role.model == role.model }
            if (integrated != null) request = request.copy(
                system = request.system + "\n" + pro.perfectproduct.cramin.llm.TopicCategories.integratedPrompt,
                user = kotlinx.serialization.json.buildJsonObject {
                    put("document", Json.encodeToJsonElement(pro.perfectproduct.cramin.llm.TopicContext.serializer(), integrated.context))
                    put("consolidation", kotlinx.serialization.json.JsonPrimitive(request.user))
                }.toString(), schema = pro.perfectproduct.cramin.llm.TopicCategories.consolidationSchema,
                reasoning = integrated.role.reasoning, strictTopic = true)
            val expectedOutput = 1024 + built.items.sumOf { item -> item.k.toByteArray().size + item.o.sumOf { 80 + it.g.toByteArray().size } }
            request = request.copy(maxTokens = role.maxTokens ?: minOf(expectedOutput, ctx.catalog?.find(role.model)?.maxCompletionTokens ?: 8192))
            val budget = ConsolidationBudget.evaluate(request, ctx.catalog?.find(role.model))
            trace.counts["requestBytes"] = budget.inputBytes
            trace.counts["outputLimit"] = budget.output
            trace.counts["contextBudget"] = budget.context
            trace.counts["contextKnown"] = if (budget.knownContext) 1 else 0
            if (!budget.fits) return split()
            request = request.copy(maxTokens = budget.output)
            // Durable active path lets a subsequent process associate saved finishReason=length with this part.
            save(state.copy(active = path))
            val response = try {
                parseOrRetry<ConsolidateResponse>(job, request, callRaw(job, request)).first.also { parsed ->
                    if (integrated != null) Consolidation.validateCategories(built, parsed)
                }
            } catch (_: TruncatedResponse) {
                return split()
            } catch (_: LlmException.InvalidResponse) {
                // Preserve existing invalid-JSON fallback; never discard occurrences.
                null
            }
            trace.step = ConsolidationStep.SAVE_RESPONSE
            save(state.copy(done = state.done + (path to (response?.let { Consolidation.encodeCached(built, it) } ?: "fallback")), active = null))
            return Consolidation.apply(group, built, response)
        }
        val result = visit("", cards)
        // Collapse completed parts back to the old, readable parent response using root-local IDs.
        val response = ConsolidateResponse(cards.map { card ->
            pro.perfectproduct.cramin.llm.ConsolidatedItem(card.lemmaKey, result.getValue(card.lemmaKey).map { sense ->
                pro.perfectproduct.cramin.llm.ConsolidatedSense(sense.translation, root.ids.filterValues {
                    it.first == card.lemmaKey && it.second in sense.unitIndices
                }.keys.toList(), sense.category)
            })
        })
        markJob(db.jobDao().getById(job.id) ?: job, JobStatus.DONE, Consolidation.encodeCached(root, response), "stop")
        return result
    }

    private suspend fun writeCards(ctx: Ctx, cards: List<MergedCard>, senses: Map<String, List<SenseDraft>>, snapshot: List<CardStatusSnapshot>): Int {
        val id = ctx.doc.id
        val t = now
        var count = 0
        val trace = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[ConsolidationTrace])
        trace.step = ConsolidationStep.REPLACE_CARDS
        val unmatched = snapshot.toMutableList()
        db.withTransaction {
            db.cardDao().deleteByDocument(id)
            deps.checkpoint("replacementDeleted")
            for (card in cards) {
                val drafts = (senses[card.lemmaKey] ?: Consolidation.fallback(card))
                    .groupBy { MeaningKey.of(it.translation) }.values.map { same ->
                        SenseDraft(same.first().translation, same.flatMap { it.unitIndices }.distinct(), same.first().category)
                    }
                for (draft in drafts) {
                    val key = MeaningKey.of(draft.translation)
                    val progress = snapshot.filter { it.lemmaKey == card.lemmaKey && it.meaningKey == key }.singleOrNull()
                    val cardId = db.cardDao().insertCard(CardEntity(
                        documentId = id, lemmaKey = card.lemmaKey, meaningKey = key, lemma = card.lemma,
                        lemmaVocalized = card.lemmaVocalized, pos = card.pos, lang = ctx.lang.code, targetLang = ctx.targetLang.code,
                        status = progress?.status ?: CardStatus.NEW, starred = progress?.starred ?: false,
                        firstSentenceIdx = draft.unitIndices.minOf { card.units[it].sentenceIdx }, updatedAt = t,
                        dueAt = progress?.dueAt, intervalDays = progress?.intervalDays, ease = progress?.ease,
                        reps = progress?.reps, lapses = progress?.lapses, category = draft.category,
                    ))
                    if (progress != null) unmatched.remove(progress)
                    trace.step = ConsolidationStep.WRITE_SENSE
                    val senseId = db.cardDao().insertSense(pro.perfectproduct.cramin.data.db.SenseEntity(cardId = cardId, idx = 0, translation = draft.translation, exampleOccurrenceId = null))
                    val example = ExamplePicker.pick(card, draft) { ctx.sentence(it) }
                    trace.step = ConsolidationStep.WRITE_OCCURRENCES
                    for (ui in draft.unitIndices) {
                        val u = card.units[ui]
                        val sentenceId = ctx.sentenceIds[u.sentenceIdx] ?: continue
                        val occurrence = db.cardDao().insertOccurrence(OccurrenceEntity(
                            cardId = cardId, senseId = senseId, sentenceId = sentenceId, surface = u.surface,
                            targetSurface = u.targetSurface, start = u.start, end = u.end, targetStart = u.targetStart,
                            targetEnd = u.targetEnd, isExample = ui == example,
                        ))
                        if (ui == example) db.cardDao().setSenseExample(senseId, occurrence)
                    }
                    count++
                    trace.counts["cardsWritten"] = count
                    trace.step = ConsolidationStep.REPLACE_CARDS
                }
            }
            trace.step = ConsolidationStep.COMMIT_READY
            deps.checkpoint("restored")
            if (unmatched.isNotEmpty()) db.documentDao().setStudyNotice(id, true)
            db.documentDao().getById(id)?.let { current ->
                FailureDiagnostic.forDocument(current)?.let { failure ->
                    db.documentDao().setFailure(id, failure.copy(recoveredAtEpochMs = now, recoveryAttemptId = trace.attemptId).encode())
                }
            }
            db.documentDao().setStatus(id, DocStatus.READY, 1f, null, null, now)
            ReprocessProgress(db, deps.files).complete(id, unmatched)
            deps.checkpoint("ready")
        }
        deps.checkpoint("committed")
        deps.files.dir(id).resolve(STATUS_SNAPSHOT_FILE).delete()
        return count
    }

    private fun glossaryFor(ctx: Ctx, text: String): List<GlossaryEntry> =
        ctx.brief?.let { GlossaryFilter.filter(it.glossary, text, ctx.lang) }.orEmpty()

    private suspend fun setStatus(id: Long, status: DocStatus, progress: Float, onProgress: suspend (DocStatus, Float) -> Unit) {
        db.documentDao().setStatus(id, status, progress, null, null, now)
        onProgress(status, progress)
    }

    private fun newJob(id: Long, kind: JobKind, idx: Int, from: Int?, to: Int?, model: String) = JobEntity(
        documentId = id, kind = kind, idx = idx, rangeStart = from, rangeEnd = to, status = JobStatus.PENDING, attempts = 0,
        model = model, responseJson = null, finishReason = null, promptTokens = 0, completionTokens = 0, costUsd = null, updatedAt = now,
    )

    private suspend fun ensureJob(id: Long, kind: JobKind, idx: Int, from: Int?, to: Int?, model: String): JobEntity =
        db.jobDao().get(id, kind, idx) ?: run {
            val jobId = db.jobDao().insert(newJob(id, kind, idx, from, to, model))
            db.jobDao().getById(jobId) ?: error("job insert failed")
        }

    private suspend fun markJob(job: JobEntity, status: JobStatus, responseJson: String?, finishReason: String?) {
        val fresh = db.jobDao().getById(job.id) ?: job
        db.jobDao().update(fresh.copy(status = status, responseJson = responseJson, finishReason = finishReason ?: fresh.finishReason, updatedAt = now))
    }

    /** Один HTTP-вызов с учётом токенов и стоимости в Job и Document. */
    private suspend fun callRaw(job: JobEntity, request: LlmRequest): LlmResponse {
        val trace = kotlinx.coroutines.currentCoroutineContext()[ConsolidationTrace]
        if (trace?.cacheOnly == true) throw ConsolidationCacheMissing()
        val saved = db.documentDao().getById(job.documentId)?.modelsSnapshotJson?.let { ProcessingSnapshot.decode(it) }
        if (saved?.legacyParametersUnknown == true && saved.find(request.model) == null)
            throw LlmException.BadRequest(0, pro.perfectproduct.cramin.llm.RequestRejection.CAPABILITIES_UNKNOWN.name)
        val frozenRequest = request.copy(parametersFrozen = saved != null,
            processingAttemptId = trace?.attemptId, logicalRequestId = java.util.UUID.randomUUID().toString(),
            jobIndex = job.idx, partPath = trace?.partPath,
            supportedParameters = saved?.find(request.model)?.supportedParameters?.toSet(),
            configOrigin = when { saved?.legacyParametersUnknown == true -> pro.perfectproduct.cramin.llm.ConfigOrigin.LEGACY_FALLBACK
                saved != null -> pro.perfectproduct.cramin.llm.ConfigOrigin.NEW_SNAPSHOT
                else -> pro.perfectproduct.cramin.llm.ConfigOrigin.UNKNOWN },
            onFailureDiagnostic = { event ->
                event.responseEvidence?.let { deps.files.recordResponseEvidence(job.documentId, job.id, it) }
                val doc = db.documentDao().getById(job.documentId)
                if (doc != null) {
                    val stage = when (request.role) {
                        ModelRole.BRIEF -> FailureStage.BRIEFING
                        ModelRole.TRANSLATE -> FailureStage.TRANSLATING
                        ModelRole.EXTRACT -> FailureStage.EXTRACTING
                        ModelRole.CONSOLIDATE -> FailureStage.CONSOLIDATING
                        ModelRole.STT -> FailureStage.TRANSCRIBING
                        ModelRole.TOPIC -> FailureStage.CONSOLIDATING
                    }
                    val code = if (event.rejection != null) ErrorCode.BAD_REQUEST else ErrorCode.UNKNOWN
                    db.documentDao().setFailure(job.documentId, FailureDiagnostic(doc.sourceType, stage, code,
                        event.rejection, event.httpStatus, event.apiStatus, now, event, origin = DiagnosticOrigin.CLIENT_ATTEMPT_ISSUE).encode())
                }
            })
        if (trace?.active == true) { trace.step = ConsolidationStep.REQUEST; trace.invocations.incrementAndGet() }
        val response = deps.llm.complete(frozenRequest)
        response.terminalMetadata?.let { deps.files.recordResponseEvidence(job.documentId, job.id, it) }
        if (trace?.active == true) { trace.responses.incrementAndGet(); trace.step = ConsolidationStep.SAVE_RESPONSE }
        db.withTransaction {
        val fresh = db.jobDao().getById(job.id) ?: job
        db.jobDao().update(
            fresh.copy(
                attempts = fresh.attempts + 1,
                model = response.model.ifEmpty { request.model },
                finishReason = response.finishReason,
                promptTokens = fresh.promptTokens + response.usage.promptTokens,
                completionTokens = fresh.completionTokens + response.usage.completionTokens,
                costUsd = if (fresh.costUsd == null && response.usage.costUsd == null) null else (fresh.costUsd ?: 0.0) + (response.usage.costUsd ?: 0.0),
                updatedAt = now,
            ),
        )
        deps.usage.refreshDocumentTotals(job.documentId)
        }
        return response
    }

    /** Разбор JSON по схеме; невалидный ответ — один повтор запроса, затем InvalidResponse (SPEC §6.3). */
    private suspend inline fun <reified T> parseOrRetry(job: JobEntity, request: LlmRequest, first: LlmResponse): Pair<T, String> {
        kotlinx.coroutines.currentCoroutineContext()[ConsolidationTrace]?.takeIf { it.active }?.let { it.step = ConsolidationStep.PARSE_RESPONSE }
        if (first.truncated) throw TruncatedResponse()
        if (request.strictTopic && first.finishReason != "stop") throw LlmException.InvalidResponse("unfinished categories")
        runCatching { LlmJson.parse<T>(first.content) }.getOrNull()?.let { return it to first.content }
        Log.w(TAG, "job=${job.id} invalid JSON; retrying once")
        val second = callRaw(job, request)
        kotlinx.coroutines.currentCoroutineContext()[ConsolidationTrace]?.takeIf { it.active }?.let { it.step = ConsolidationStep.PARSE_RESPONSE }
        if (second.truncated) throw TruncatedResponse()
        if (request.strictTopic && second.finishReason != "stop") throw LlmException.InvalidResponse("unfinished categories")
        val parsed = runCatching { LlmJson.parse<T>(second.content) }.getOrNull()
            ?: throw LlmException.InvalidResponse("schema violation after retry")
        return parsed to second.content
    }

    private suspend inline fun <reified T> callParsed(job: JobEntity, request: LlmRequest, check: (T) -> T): T {
        val first = callRaw(job, request)
        if (first.truncated) throw LlmException.InvalidResponse("length in ${request.role}")
        val parsed = runCatching { check(LlmJson.parse<T>(first.content)) }.getOrNull()
        if (parsed != null) return parsed
        Log.w(TAG, "job=${job.id} invalid response; retrying once")
        val second = callRaw(job, request)
        if (second.truncated) throw LlmException.InvalidResponse("length in ${request.role} retry")
        return runCatching { check(LlmJson.parse<T>(second.content)) }.getOrNull()
            ?: throw LlmException.InvalidResponse("schema violation after retry")
    }

    @kotlinx.serialization.Serializable
    data class StatusSnapshotRow(
        val lemmaKey: String,
        val status: String,
        val starred: Boolean,
        val dueAt: Long? = null,
        val intervalDays: Int? = null,
        val ease: Double? = null,
        val reps: Int? = null,
        val lapses: Int? = null,
        val meaningKey: String? = null,
    )

    companion object {
        private const val TAG = "Processor"
        const val PIPELINE_VERSION = 2
        const val MAX_TITLE = 120
        const val DEFAULT_EMOJI = "📄"
        const val DEFAULT_MAX_COMPLETION_TOKENS = 8_000
        const val CONTEXT_SENTENCES = 3
        const val STATUS_SNAPSHOT_FILE = "status-snapshot.json"

        /** Снимок статусов для «Обработать заново» пишет вызывающий (репозиторий) в файл документа. */
        fun writeStatusSnapshot(files: DocumentFiles, id: Long, snapshot: List<CardStatusSnapshot>) {
            val rows = snapshot.map { StatusSnapshotRow(it.lemmaKey, it.status.name, it.starred, it.dueAt, it.intervalDays, it.ease, it.reps, it.lapses, it.meaningKey) }
            files.dir(id).resolve(STATUS_SNAPSHOT_FILE).writeText(Json.encodeToString(rows))
        }
    }
}
