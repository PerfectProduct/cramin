package pro.perfectproduct.cramin.pipeline

import androidx.room.withTransaction
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pro.perfectproduct.cramin.data.db.*
import pro.perfectproduct.cramin.llm.*
import pro.perfectproduct.cramin.util.Hashing

@Serializable
data class TopicSnapshot(val promptVersion: String = TopicCategories.VERSION, val role: EffectiveRole,
    val context: TopicContext, val model: CatalogModel? = null)
@Serializable
private data class Classified(val hash: String, val category: TopicCategory)
@Serializable
private data class TopicParts(val version: String = TopicCategories.VERSION,
    val done: Map<String, Classified> = emptyMap(), val split: Set<String> = emptySet())

/** Caller owns documentLock. Durable results are bound to the complete item, never just its lemma. */
class TopicClassifier(private val deps: ProcessorDeps) {
    private val db get() = deps.db
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    suspend fun snapshot(documentId: Long, config: EffectiveConfig, catalog: CatalogView?): TopicSnapshot {
        val doc = requireNotNull(db.documentDao().getById(documentId))
        doc.topicSnapshotJson?.let { return json.decodeFromString<TopicSnapshot>(it).also { s -> require(s.promptVersion == TopicCategories.VERSION) } }
        val brief = doc.briefJson?.let { runCatching { LlmJson.parse<Brief>(it) }.getOrNull() }
        // If BRIEF failed, preserve actual source evidence, not invented subtopics.
        val evidence = if (brief == null) BriefInput.select(db.sentenceDao().getByDocument(documentId).map { it.text }, 1500) else brief.summary
        val role = config.role(ModelRole.TOPIC)
        val result = TopicSnapshot(role = role, context = TopicContext(doc.title, evidence, brief?.subtopics.orEmpty()), model = catalog?.find(role.model))
        db.documentDao().setTopicSnapshot(documentId, json.encodeToString(result))
        return result
    }

    suspend fun classify(documentId: Long, snapshot: TopicSnapshot, items: List<TopicItem>,
        allowNetwork: Boolean = true, savedPart: suspend (Map<String, TopicCategory>) -> Unit = {}): Map<String, TopicCategory> {
        require(items.map { it.id }.distinct().size == items.size)
        val existing = db.jobDao().get(documentId, JobKind.TOPIC, 0)
        val jobId = existing?.id ?: db.jobDao().insert(JobEntity(documentId = documentId, kind = JobKind.TOPIC, idx = 0,
            rangeStart = null, rangeEnd = null, status = JobStatus.PENDING, attempts = 0, model = snapshot.role.model,
            responseJson = null, finishReason = null, promptTokens = 0, completionTokens = 0, costUsd = null, updatedAt = deps.clock.now()))
        var state = existing?.responseJson?.let { json.decodeFromString<TopicParts>(it) } ?: TopicParts()
        require(state.version == snapshot.promptVersion)
        fun hash(item: TopicItem) = Hashing.sha256Hex(json.encodeToString(item).toByteArray())
        val hashes = items.associate { it.id to hash(it) }
        val answer = state.done.filter { (id, v) -> hashes[id] == v.hash }.mapValues { it.value.category }.toMutableMap()
        suspend fun save() {
            val fresh = requireNotNull(db.jobDao().getById(jobId))
            db.jobDao().update(fresh.copy(responseJson = json.encodeToString(state), status = JobStatus.PENDING, updatedAt = deps.clock.now()))
        }
        if (answer.size < items.size) save()
        savedPart(answer)
        suspend fun visit(group: List<TopicItem>) {
            if (group.isEmpty()) return
            if (!allowNetwork) throw ConsolidationCacheUnfinished()
            val request = TopicCategories.request(snapshot.role, snapshot.context, group, snapshot.model).copy(
                onFailureDiagnostic = { event -> event.responseEvidence?.let { deps.files.recordResponseEvidence(documentId, jobId, it) } })
            val signature = Hashing.sha256Hex(request.user.toByteArray())
            suspend fun split() {
                // Split between lemmas to keep a lexical group intact.
                val groups = group.groupBy { it.lemma to it.pos }.values.toList()
                if (groups.size < 2) throw LlmException.InvalidResponse("topic single lemma exceeds context/output")
                state = state.copy(split = state.split + signature); save()
                val middle = groups.size / 2
                visit(groups.take(middle).flatten()); visit(groups.drop(middle).flatten())
            }
            if (signature in state.split || !TopicCategories.fits(request, snapshot.model)) { split(); return }
            repeat(2) { attempt ->
                val fresh = requireNotNull(db.jobDao().getById(jobId))
                db.jobDao().update(fresh.copy(attempts = fresh.attempts + 1))
                val trace = kotlinx.coroutines.currentCoroutineContext()[ConsolidationTrace]
                trace?.takeIf { it.active }?.let { it.step = ConsolidationStep.REQUEST; it.invocations.incrementAndGet() }
                val response = deps.llm.complete(request)
                response.terminalMetadata?.let { deps.files.recordResponseEvidence(documentId, jobId, it) }
                trace?.takeIf { it.active }?.let { it.responses.incrementAndGet(); it.step = ConsolidationStep.SAVE_RESPONSE }
                val used = requireNotNull(db.jobDao().getById(jobId))
                db.jobDao().update(used.copy(finishReason = response.finishReason,
                    promptTokens = used.promptTokens + response.usage.promptTokens,
                    completionTokens = used.completionTokens + response.usage.completionTokens,
                    costUsd = if (used.costUsd == null && response.usage.costUsd == null) null else (used.costUsd ?: 0.0) + (response.usage.costUsd ?: 0.0)))
                deps.usage.refreshDocumentTotals(documentId)
                if (response.truncated) { split(); return }
                val parsed = try { TopicCategories.validate(response, group.map { it.id }) }
                catch (e: LlmException.InvalidResponse) { if (attempt == 1) throw e else null }
                if (parsed != null) {
                    state = state.copy(done = state.done + parsed.mapValues { (id, category) -> Classified(hashes.getValue(id), category) })
                    db.withTransaction { save(); savedPart(parsed) }
                    answer.putAll(parsed)
                    deps.checkpoint("topicPartSaved")
                    return
                }
            }
        }
        // Fixed maximum group count is only a prepartition; serialized bytes AND expected output govern every request.
        items.filter { it.id !in answer }.groupBy { it.lemma to it.pos }.values.toList().chunked(32).forEach { visit(it.flatten()) }
        val fresh = requireNotNull(db.jobDao().getById(jobId))
        db.jobDao().update(fresh.copy(status = JobStatus.DONE, finishReason = "stop"))
        db.documentDao().setTopicError(documentId, null)
        return answer
    }
}
