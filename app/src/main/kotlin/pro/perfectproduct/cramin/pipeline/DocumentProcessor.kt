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
    suspend fun process(documentId: Long, onProgress: suspend (DocStatus, Float) -> Unit = { _, _ -> }): ProcessOutcome {
        val doc = db.documentDao().getById(documentId) ?: return ProcessOutcome.Skipped
        if (doc.status == DocStatus.READY) return ProcessOutcome.Skipped
        Log.i(TAG, "process doc=$documentId status=${doc.status} type=${doc.sourceType}")
        return try {
            val count = run(doc, onProgress)
            ProcessOutcome.Ready(count)
        } catch (e: CancellationException) {
            Log.i(TAG, "doc=$documentId cancelled")
            throw e
        } catch (t: Throwable) {
            val pe = PipelineException.from(t)
            Log.w(TAG, "doc=$documentId failed: ${pe.code} ${pe.message}", if (pe.code == ErrorCode.UNKNOWN) t else null)
            val current = db.documentDao().getById(documentId)
            db.documentDao().setStatus(documentId, DocStatus.FAILED, current?.progress ?: 0f, pe.code.name, pe.message?.take(200), now)
            ProcessOutcome.Failed(pe.code, pe.message.orEmpty())
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
        fun sentence(idx: Int): String? = byIdx[idx]?.text
        fun text(range: IntRange): String = sentences.filter { it.idx in range }.joinToString(" ") { it.text }
    }

    private suspend fun run(doc0: DocumentEntity, onProgress: suspend (DocStatus, Float) -> Unit): Int {
        val id = doc0.id
        val config = deps.configProvider()
        val catalog = deps.catalogProvider()
        db.documentDao().setPipelineSnapshot(id, config.snapshotJson(), PIPELINE_VERSION, now)

        // --- FETCHING / TRANSCRIBING -------------------------------------------------------
        val progressGuess = Progress(hasTranscription = doc0.sourceType == SourceType.YOUTUBE)
        val (lang, targetLang, sentences) = fetchStage(doc0, progressGuess, onProgress)
        val doc = db.documentDao().getById(id) ?: error("document vanished")
        val progress = Progress(hasTranscription = doc.audioSeconds > 0)
        val sentenceIds = db.sentenceDao().getByDocument(id).associate { it.idx to it.id }
        val ctx = Ctx(doc, config, catalog, lang, targetLang, sentences, sentenceIds, progress, onProgress)

        briefStage(ctx)
        translateStage(ctx)
        extractStage(ctx)
        val count = consolidateAndBuildStage(ctx)

        db.documentDao().setStatus(id, DocStatus.READY, 1f, null, null, now)
        onProgress(DocStatus.READY, 1f)
        Log.i(TAG, "doc=$id READY cards=$count")
        return count
    }

    // ---------------------------------------------------------------------------------------
    // Стадия получения текста: extractor → (транскрипция) → source.txt → язык → предложения.
    // ---------------------------------------------------------------------------------------
    private suspend fun fetchStage(doc: DocumentEntity, progress: Progress, onProgress: suspend (DocStatus, Float) -> Unit): Triple<Lang, Lang, List<SentenceDraft>> {
        val id = doc.id
        val targetLang = Lang.fromCode(doc.targetLang) ?: throw PipelineException(ErrorCode.UNKNOWN, "bad target lang")
        val existing = db.sentenceDao().getByDocument(id)
        val storedLang = Lang.fromCode(doc.sourceLang)
        if (existing.isNotEmpty() && storedLang != null) {
            return Triple(storedLang, targetLang, existing.map { SentenceDraft(it.idx, it.paragraphIdx, it.text) })
        }
        setStatus(id, DocStatus.FETCHING, progress.start(DocStatus.FETCHING), onProgress)
        val sourceFile = deps.files.sourceText(id)
        var langHint: Lang? = storedLang
        var title: String? = null
        if (!sourceFile.isFile) {
            val extractor = deps.extractors[doc.sourceType] ?: throw PipelineException(ErrorCode.UNKNOWN, "no extractor for ${doc.sourceType}")
            when (val extracted = extractor.extract(doc, deps.files)) {
                is Extracted.Text -> {
                    sourceFile.writeText(extracted.text)
                    title = extracted.title
                    langHint = langHint ?: extracted.langHint
                }
                is Extracted.Audio -> {
                    title = extracted.title
                    langHint = langHint ?: extracted.langHint
                    val text = transcribeStage(doc, extracted, langHint, progress, onProgress)
                    sourceFile.writeText(text)
                }
            }
            onProgress(DocStatus.FETCHING, progress.within(DocStatus.FETCHING, 1f))
        }
        val text = sourceFile.readText()
        val lang = storedLang ?: langHint ?: LangDetector.detect(text)
            ?: throw PipelineException(ErrorCode.LANG_UNDETECTED, "script share below threshold")
        if (lang == targetLang) throw PipelineException(ErrorCode.SAME_LANGUAGE, "source == target (${lang.code})")
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

    private suspend fun transcribeStage(doc: DocumentEntity, audio: Extracted.Audio, langHint: Lang?, progress: Progress, onProgress: suspend (DocStatus, Float) -> Unit): String {
        val id = doc.id
        val transcriber = deps.transcriber ?: throw PipelineException(ErrorCode.TRANSCRIPTION, "transcriber unavailable")
        val segmenter = deps.audioSegmenter ?: throw PipelineException(ErrorCode.TRANSCRIPTION, "audio segmenter unavailable")
        val lang = langHint ?: throw PipelineException(ErrorCode.YOUTUBE_NO_LANG, "video language unknown")
        setStatus(id, DocStatus.TRANSCRIBING, progress.start(DocStatus.TRANSCRIBING), onProgress)
        val parts = segmenter.split(audio.file, deps.files.audioDir(id))
        if (parts.isEmpty()) throw PipelineException(ErrorCode.TRANSCRIPTION, "no audio parts")
        val model = deps.configProvider().role(ModelRole.STT).model
        val texts = ArrayList<String>()
        for ((i, part) in parts.withIndex()) {
            val job = ensureJob(id, JobKind.STT, i, part.durationSeconds, null, model)
            val text = if (job.status == JobStatus.DONE && job.responseJson != null) {
                job.responseJson
            } else {
                val t = try {
                    transcriber.transcribe(part, lang, model)
                } catch (e: PipelineException) {
                    db.jobDao().update(job.copy(status = JobStatus.FAILED, attempts = job.attempts + 1, updatedAt = now))
                    throw e
                }
                db.jobDao().update(job.copy(status = JobStatus.DONE, attempts = job.attempts + 1, responseJson = t.text, costUsd = t.costUsd, updatedAt = now))
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
        val input = BriefInput.select(paragraphs, ctx.config.pipeline.briefMaxInputWords)
        val request = LlmRequest(ModelRole.BRIEF, role.model, Prompts.BRIEF, Messages.brief(ctx.lang, ctx.targetLang, input), Schemas.BRIEF_NAME, Schemas.BRIEF, role.temperature, role.maxTokens, role.reasoning)
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
            if (job.status == JobStatus.DONE) continue
            val range = job.rangeStart!!..job.rangeEnd!! // диапазоны заданы при планировании
            val context = continuationContext(id, range.first)
            val segments = translateRange(ctx, job, range, context, maxCompletion)
            db.withTransaction {
                db.segmentDao().deleteFrom(id, range.first)
                for (s in segments) {
                    val segId = db.segmentDao().insert(SegmentEntity(documentId = id, firstSentenceIdx = s.from, lastSentenceIdx = s.to, translation = s.t))
                    db.sentenceDao().assignSegment(id, s.from, s.to, segId)
                }
                markJob(db.jobDao().getById(job.id) ?: job, JobStatus.DONE, LlmJson.strict.encodeToString(StoredTranslation(segments)), null)
            }
            ctx.onProgress(DocStatus.TRANSLATING, ctx.progress.within(DocStatus.TRANSLATING, (n + 1f) / jobs.size))
        }
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

    private suspend fun translateRange(ctx: Ctx, job: JobEntity, range: IntRange, context: List<ContextLine>, maxCompletion: Int): List<TranslatedSegment> {
        val role = ctx.config.role(ModelRole.TRANSLATE)
        val sentences = ctx.sentences.filter { it.idx in range }
        val glossary = glossaryFor(ctx, ctx.text(range))
        val request = LlmRequest(
            ModelRole.TRANSLATE, role.model, Prompts.TRANSLATE,
            Messages.translate(ctx.lang, ctx.targetLang, ctx.brief, glossary, context, sentences),
            Schemas.TRANSLATE_NAME, Schemas.TRANSLATE, role.temperature, maxCompletion, role.reasoning,
        )
        val response = callRaw(job, request)
        if (response.truncated) {
            // finish_reason == length: делим пополам по абзацам и переводим обе половины заново (SPEC §6.5).
            val halves = SectionPlanner.splitHalf(ctx.sentences, range)
                ?: throw LlmException.InvalidResponse("length on a single sentence")
            Log.i(TAG, "doc=${ctx.doc.id} section ${range.first}-${range.last} truncated; split")
            val first = translateRange(ctx, job, halves.first, context, maxCompletion)
            val secondContext = first.takeLast(CONTEXT_SENTENCES).map { s -> ContextLine(s.from, s.to, (s.from..s.to).mapNotNull { ctx.sentence(it) }.joinToString(" "), s.t) }
            val second = translateRange(ctx, job, halves.second, secondContext, maxCompletion)
            return first + second
        }
        val parsed = parseOrRetry<TranslateResponse>(job, request, response)
        var validation = TranslationValidator.validate(range, parsed.first.seg)
        var accepted = validation.accepted
        if (!validation.isComplete) {
            // Дыры дозапрашиваются одним вызовом: только пропущенные предложения с контекстом вокруг.
            Log.i(TAG, "doc=${ctx.doc.id} section ${range.first}-${range.last}: ${validation.missing.size} holes, ${validation.rejected} rejected")
            val missing = validation.missing.toSet()
            val holeSentences = sentences.filter { it.idx in missing }
            val holeContext = accepted.filter { s -> missing.any { m -> m in (s.from - 2)..(s.to + 2) } }
                .map { s -> ContextLine(s.from, s.to, (s.from..s.to).mapNotNull { ctx.sentence(it) }.joinToString(" "), s.t) }
            val fillRequest = request.copy(user = Messages.translate(ctx.lang, ctx.targetLang, ctx.brief, glossary, context + holeContext, holeSentences))
            val fillResponse = callRaw(job, fillRequest)
            val fillParsed = parseOrRetry<TranslateResponse>(job, fillRequest, fillResponse).first
            val covered = accepted.flatMap { (it.from..it.to).toList() }.toSet()
            val fill = TranslationValidator.validate(range, fillParsed.seg, covered)
            accepted = TranslationValidator.merge(accepted, fill.accepted)
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
        val role = ctx.config.role(ModelRole.EXTRACT)
        val pairs = segments.filter { it.firstSentenceIdx >= range.first && it.lastSentenceIdx <= range.last }
            .map { s -> SegmentPair(ctx.sentences.filter { it.idx in s.firstSentenceIdx..s.lastSentenceIdx }, s.translation) }
        val request = LlmRequest(
            ModelRole.EXTRACT, role.model, Prompts.EXTRACT,
            Messages.extract(ctx.lang, ctx.targetLang, glossaryFor(ctx, ctx.text(range)), pairs),
            Schemas.EXTRACT_NAME, Schemas.EXTRACT, role.temperature, role.maxTokens, role.reasoning,
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
        val role = ctx.config.role(ModelRole.CONSOLIDATE)
        val segments = db.segmentDao().getByDocument(id)
        val segmentByIdx = HashMap<Int, SegmentEntity>()
        for (s in segments) for (i in s.firstSentenceIdx..s.lastSentenceIdx) segmentByIdx[i] = s
        val unitContext = object : UnitContext {
            override fun sentence(idx: Int) = ctx.sentence(idx)
            override fun segmentTranslation(idx: Int) = segmentByIdx[idx]?.translation
        }
        val stoplist = deps.stoplists.forLang(ctx.lang)
        val units = ArrayList<ValidatedUnit>()
        for (job in db.jobDao().getByKind(id, JobKind.EXTRACT)) {
            if (job.status != JobStatus.DONE || job.responseJson == null) throw PipelineException(ErrorCode.UNKNOWN, "extract job ${job.idx} not done")
            val raw = LlmJson.strict.decodeFromString<StoredExtraction>(job.responseJson).u
            units += UnitValidator.validate(job.rangeStart!!..job.rangeEnd!!, raw, unitContext, stoplist, ctx.lang, ctx.targetLang)
        }
        val cards = UnitMerger.merge(units, ctx.lang, ctx.targetLang)
        Log.i(TAG, "doc=$id units=${units.size} cards=${cards.size} toConsolidate=${cards.count { it.needsConsolidation }}")

        val senses = HashMap<String, List<SenseDraft>>()
        for (card in cards) if (!card.needsConsolidation) senses[card.lemmaKey] = Consolidation.single(card)
        val batches = Consolidation.batches(cards)
        for ((i, batch) in batches.withIndex()) {
            val job = ensureJob(id, JobKind.CONSOLIDATE, i, null, null, role.model)
            val built = Consolidation.buildBatch(batch) { ctx.sentence(it) }
            val response: ConsolidateResponse? = if (job.status == JobStatus.DONE) {
                job.responseJson?.let { runCatching { LlmJson.lenient.decodeFromString<ConsolidateResponse>(it) }.getOrNull() }
            } else {
                val request = LlmRequest(ModelRole.CONSOLIDATE, role.model, Prompts.CONSOLIDATE, Messages.consolidate(ctx.lang, ctx.targetLang, built.items), Schemas.CONSOLIDATE_NAME, Schemas.CONSOLIDATE, role.temperature, role.maxTokens, role.reasoning)
                try {
                    val (parsed, raw) = parseOrRetry<ConsolidateResponse>(job, request, callRaw(job, request))
                    markJob(db.jobDao().getById(job.id) ?: job, JobStatus.DONE, raw, null)
                    parsed
                } catch (e: LlmException.InvalidResponse) {
                    // Невалидный ответ после повтора — фолбэк §6.8, документ не падает.
                    Log.w(TAG, "doc=$id consolidate batch $i invalid; fallback")
                    markJob(db.jobDao().getById(job.id) ?: job, JobStatus.DONE, null, "fallback")
                    null
                }
            }
            senses.putAll(Consolidation.apply(batch, built, response))
            ctx.onProgress(DocStatus.CONSOLIDATING, ctx.progress.within(DocStatus.CONSOLIDATING, (i + 1f) / (batches.size + 1)))
        }

        val snapshot = readStatusSnapshot(id)
        val count = writeCards(ctx, cards, senses, snapshot)
        return count
    }

    private suspend fun writeCards(ctx: Ctx, cards: List<MergedCard>, senses: Map<String, List<SenseDraft>>, snapshot: Map<String, CardStatusSnapshot>): Int {
        val id = ctx.doc.id
        val t = now
        db.withTransaction {
            db.cardDao().deleteByDocument(id)
            for (card in cards) {
                val drafts = senses[card.lemmaKey] ?: Consolidation.fallback(card)
                val cardId = db.cardDao().insertCard(
                    CardEntity(
                        documentId = id, lemmaKey = card.lemmaKey, lemma = card.lemma, lemmaVocalized = card.lemmaVocalized, pos = card.pos,
                        lang = ctx.lang.code, targetLang = ctx.targetLang.code, status = CardStatus.NEW, starred = false,
                        firstSentenceIdx = card.firstSentenceIdx, updatedAt = t,
                    ),
                )
                val senseOfUnit = HashMap<Int, Long>()
                val exampleUnitOfSense = HashMap<Long, Int>()
                for ((si, draft) in drafts.withIndex()) {
                    val senseId = db.cardDao().insertSense(pro.perfectproduct.cramin.data.db.SenseEntity(cardId = cardId, idx = si, translation = draft.translation, exampleOccurrenceId = null))
                    for (ui in draft.unitIndices) senseOfUnit[ui] = senseId
                    exampleUnitOfSense[senseId] = ExamplePicker.pick(card, draft) { ctx.sentence(it) }
                }
                val occupied = HashMap<Int, MutableList<IntRange>>()
                for ((ui, u) in card.units.withIndex()) {
                    val sentenceId = ctx.sentenceIds[u.sentenceIdx] ?: continue
                    val senseId = senseOfUnit[ui]
                    val isExample = senseId != null && exampleUnitOfSense[senseId] == ui
                    val occId = db.cardDao().insertOccurrence(
                        OccurrenceEntity(
                            cardId = cardId, senseId = senseId, sentenceId = sentenceId, surface = u.surface, targetSurface = u.targetSurface,
                            start = u.start, end = u.end, targetStart = u.targetStart, targetEnd = u.targetEnd, isExample = isExample,
                        ),
                    )
                    if (isExample && senseId != null) db.cardDao().setSenseExample(senseId, occId)
                    if (u.start != null && u.end != null) occupied.getOrPut(u.sentenceIdx) { mutableListOf() }.add(u.start until u.end)
                }
                // Все вхождения поверхностных форм для подчёркивания (SPEC §6.9); лемма — тоже известная форма.
                val surfaces = (card.units.map { it.surface } + card.lemma).toSet()
                val matches = SurfaceMatcher.findAll(surfaces, ctx.sentences, ctx.lang).filter { m ->
                    occupied[m.sentenceIdx].orEmpty().none { r -> r.first < m.end && m.start < r.last + 1 }
                }
                if (matches.isNotEmpty()) {
                    db.cardDao().insertOccurrences(
                        matches.mapNotNull { m ->
                            val sentenceId = ctx.sentenceIds[m.sentenceIdx] ?: return@mapNotNull null
                            OccurrenceEntity(cardId = cardId, senseId = null, sentenceId = sentenceId, surface = m.surface, targetSurface = null, start = m.start, end = m.end, targetStart = null, targetEnd = null, isExample = false)
                        },
                    )
                }
                snapshot[card.lemmaKey]?.let { s ->
                    db.cardDao().restoreSnapshot(id, card.lemmaKey, s.status, s.starred, s.dueAt, s.intervalDays, s.ease, s.reps, s.lapses, t)
                }
            }
        }
        deps.files.dir(id).resolve(STATUS_SNAPSHOT_FILE).delete()
        return cards.size
    }

    // ---------------------------------------------------------------------------------------
    // Вспомогательное.
    // ---------------------------------------------------------------------------------------
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
        val response = deps.llm.complete(request)
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
        return response
    }

    /** Разбор JSON по схеме; невалидный ответ — один повтор запроса, затем InvalidResponse (SPEC §6.3). */
    private suspend inline fun <reified T> parseOrRetry(job: JobEntity, request: LlmRequest, first: LlmResponse): Pair<T, String> {
        runCatching { LlmJson.parse<T>(first.content) }.getOrNull()?.let { return it to first.content }
        Log.w(TAG, "job=${job.id} invalid JSON; retrying once")
        val second = callRaw(job, request)
        val parsed = runCatching { LlmJson.parse<T>(second.content) }.getOrNull()
            ?: throw LlmException.InvalidResponse("schema violation after retry")
        return parsed to second.content
    }

    private suspend inline fun <reified T> callParsed(job: JobEntity, request: LlmRequest, check: (T) -> T): T {
        val first = callRaw(job, request)
        val parsed = runCatching { check(LlmJson.parse<T>(first.content)) }.getOrNull()
        if (parsed != null) return parsed
        Log.w(TAG, "job=${job.id} invalid response; retrying once")
        val second = callRaw(job, request)
        return runCatching { check(LlmJson.parse<T>(second.content)) }.getOrNull()
            ?: throw LlmException.InvalidResponse("schema violation after retry")
    }

    private fun readStatusSnapshot(id: Long): Map<String, CardStatusSnapshot> {
        val f = deps.files.dir(id).resolve(STATUS_SNAPSHOT_FILE)
        if (!f.isFile) return emptyMap()
        return runCatching { Json.decodeFromString<List<StatusSnapshotRow>>(f.readText()) }.getOrNull()
            ?.associate { it.lemmaKey to CardStatusSnapshot(it.lemmaKey, CardStatus.valueOf(it.status), it.starred, it.dueAt, it.intervalDays, it.ease, it.reps, it.lapses) }
            .orEmpty()
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
    )

    companion object {
        private const val TAG = "Processor"
        const val PIPELINE_VERSION = 1
        const val MAX_TITLE = 120
        const val DEFAULT_EMOJI = "📄"
        const val DEFAULT_MAX_COMPLETION_TOKENS = 8_000
        const val CONTEXT_SENTENCES = 3
        const val STATUS_SNAPSHOT_FILE = "status-snapshot.json"

        /** Снимок статусов для «Обработать заново» пишет вызывающий (репозиторий) в файл документа. */
        fun writeStatusSnapshot(files: DocumentFiles, id: Long, snapshot: List<CardStatusSnapshot>) {
            val rows = snapshot.map { StatusSnapshotRow(it.lemmaKey, it.status.name, it.starred, it.dueAt, it.intervalDays, it.ease, it.reps, it.lapses) }
            files.dir(id).resolve(STATUS_SNAPSHOT_FILE).writeText(Json.encodeToString(rows))
        }
    }
}
