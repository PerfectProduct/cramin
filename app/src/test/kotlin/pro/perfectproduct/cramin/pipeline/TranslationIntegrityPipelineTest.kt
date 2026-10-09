package pro.perfectproduct.cramin.pipeline

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pro.perfectproduct.cramin.data.db.*
import pro.perfectproduct.cramin.data.repo.NewDocument
import pro.perfectproduct.cramin.llm.*
import pro.perfectproduct.cramin.testing.FakeLlmClient
import pro.perfectproduct.cramin.testing.TestPipeline
import pro.perfectproduct.cramin.util.Lang

@RunWith(RobolectricTestRunner::class)
class TranslationIntegrityPipelineTest {
    @get:Rule val tmp = TemporaryFolder()

    // Synthetic paraphrases of the reported failure classes; no owner text or paid requests.
    private val text = "### 7. Датчик измеряет температуру в 80% случаев.\n\n### 9. Клапан перекрывает поток в 20% случаев."

    private fun reply(request: LlmRequest, translate: (SentenceDraft) -> String): LlmResponse {
        val ids = Messages.parseHeader(request.user, "SOURCE_IDS")?.let { Json.parseToJsonElement(it).jsonObject }
        val body = buildJsonObject { put("seg", buildJsonArray {
            Messages.parseSentences(request.user).forEach { s -> add(buildJsonObject {
                put("from", s.idx); put("to", s.idx); put("t", translate(s))
                // On the old HEAD this field is ignored. With the new protocol the echo is CORRECT:
                // rejection must be due to foreign translation anchors, not a missing id/hash.
                if (ids != null) put("sourceIds", JsonArray(listOf(ids.getValue(s.idx.toString()))))
            }) }
        }) }
        return LlmResponse(body.toString(), "stop", LlmUsage(1, 1, 0.0), request.model)
    }

    private fun normal(s: SentenceDraft): String = s.text.replace("Датчик измеряет температуру", "The sensor measures temperature")
        .replace("Клапан перекрывает поток", "The valve shuts the flow").replace("в", "in").replace("случаев", "of cases")

    @Test fun foreignNumberedHeadingCannotReachExtractOrReadyDespiteCorrectIndices() = runTest {
        val fake = FakeLlmClient()
        fake.interceptor = { request, _ -> if (request.role == ModelRole.TRANSLATE) reply(request) {
            normal(it).replace("### 7.", "### 999.").replace("### 9.", "### 7.").replace("### 999.", "### 9.")
        } else null }
        TestPipeline(tmp.newFolder(), fake).use { p ->
            val id = p.documents.create(NewDocument.Text(text, "Synthetic alignment", Lang.EN, Lang.RU))
            val outcome = p.processor().process(id)
            assertTrue("Foreign heading accepted: $outcome", outcome is ProcessOutcome.Failed && outcome.code == ErrorCode.INVALID_RESPONSE)
            assertEquals(0, fake.callsFor(ModelRole.EXTRACT))
            assertTrue(p.db.segmentDao().getByDocument(id).isEmpty())
            assertEquals(DocStatus.FAILED, p.documents.get(id)!!.status)
        }
    }

    @Test fun foreignCalibrationNumberCannotReachExtractOrReady() = runTest {
        val fake = FakeLlmClient()
        fake.interceptor = { request, _ -> if (request.role == ModelRole.TRANSLATE) reply(request) {
            normal(it).replace("80%", "20%")
        } else null }
        TestPipeline(tmp.newFolder(), fake).use { p ->
            val id = p.documents.create(NewDocument.Text(text, null, Lang.EN, Lang.RU))
            val outcome = p.processor().process(id)
            assertTrue("Foreign calibration accepted: $outcome", outcome is ProcessOutcome.Failed && outcome.code == ErrorCode.INVALID_RESPONSE)
            assertEquals(0, fake.callsFor(ModelRole.EXTRACT))
            assertEquals(1, fake.callsFor(ModelRole.TRANSLATE))
        }
    }

    @Test fun correctTranslationIsStoredWithEvidenceAndReady() = runTest {
        val fake = FakeLlmClient()
        fake.interceptor = { req, _ -> if (req.role == ModelRole.TRANSLATE) reply(req, ::normal) else null }
        TestPipeline(tmp.newFolder(), fake).use { p ->
            val id = p.documents.create(NewDocument.Text(text, null, Lang.EN, Lang.RU))
            assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
            val saved = p.db.jobDao().getByKind(id, JobKind.TRANSLATE).map { LlmJson.parse<StoredTranslation>(it.responseJson!!) }
            assertTrue(saved.all { it.integrityVersion == TranslationIntegrity.VERSION && it.seg.all { s -> s.sourceIds != null } })
            assertTrue(fake.callsFor(ModelRole.EXTRACT) > 0)
        }
    }

    private val six = "Датчик измеряет температуру. Клапан перекрывает поток. Насос подаёт воду. Манометр показывает давление. Фильтр очищает воду. Бак хранит воду."

    @Test fun holeFillLengthSplitsOnlyTheMissingRangeAndPreservesGlobalIds() = runTest {
        val fake = FakeLlmClient(); var translate = 0
        fake.interceptor = { req, _ -> if (req.role != ModelRole.TRANSLATE) null else when (++translate) {
            1 -> {
                val full = reply(req, ::normal)
                val parsed = LlmJson.parse<TranslateResponse>(full.content)
                full.copy(content = Json.encodeToString(parsed.copy(seg = parsed.seg.filter { it.from == 0 || it.from == 5 })))
            }
            2 -> reply(req, ::normal).copy(content = "{", finishReason = "length")
            else -> reply(req, ::normal)
        } }
        TestPipeline(tmp.newFolder(), fake).use { p ->
            val id = p.documents.create(NewDocument.Text(six, null, Lang.EN, Lang.RU))
            assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
            val requests = fake.requests.filter { it.role == ModelRole.TRANSLATE }.map { Messages.parseSentences(it.user).map { s -> s.idx } }
            assertEquals(listOf((0..5).toList(), (1..4).toList(), listOf(1, 2), listOf(3, 4)), requests)
            assertTrue(p.db.sentenceDao().getByDocument(id).all { it.segmentId != null })
        }
    }

    @Test fun nonContiguousHolesAreRequestedSeparatelyAndRepeatedHolesFailBoundedly() = runTest {
        for (emptyFill in listOf(false, true)) {
            val fake = FakeLlmClient(); var translate = 0
            fake.interceptor = { req, _ -> if (req.role != ModelRole.TRANSLATE) null else {
                val full = reply(req, ::normal)
                val parsed = LlmJson.parse<TranslateResponse>(full.content)
                full.copy(content = Json.encodeToString(parsed.copy(seg = when {
                    ++translate == 1 -> parsed.seg.filter { it.from !in setOf(1, 4) }
                    emptyFill -> emptyList()
                    else -> parsed.seg
                })))
            } }
            TestPipeline(tmp.newFolder(), fake).use { p ->
                val id = p.documents.create(NewDocument.Text(six, null, Lang.EN, Lang.RU))
                val outcome = p.processor().process(id)
                val requests = fake.requests.filter { it.role == ModelRole.TRANSLATE }.map { Messages.parseSentences(it.user).map { s -> s.idx } }
                if (emptyFill) {
                    assertTrue(outcome is ProcessOutcome.Failed && outcome.code == ErrorCode.INVALID_RESPONSE)
                    assertEquals(2, translate); assertEquals(0, fake.callsFor(ModelRole.EXTRACT))
                } else {
                    assertTrue("$outcome", outcome is ProcessOutcome.Ready)
                    assertEquals(listOf((0..5).toList(), listOf(1), listOf(4)), requests)
                }
            }
        }
    }

    @Test fun modernAndLegacyDoneCacheResumeWithoutRetranslationButAlteredEvidenceFails() = runTest {
        for (mode in listOf("modern", "legacy", "absent", "altered", "unsupported")) {
            val fake = FakeLlmClient()
            fake.errorInjector = { req, _ -> if (req.role == ModelRole.EXTRACT) RuntimeException("Synthetic process interruption") else null }
            TestPipeline(tmp.newFolder(), fake).use { p ->
                val id = p.documents.create(NewDocument.Text(six, null, Lang.EN, Lang.RU))
                assertTrue(p.processor().process(id) is ProcessOutcome.Failed)
                val job = p.db.jobDao().getByKind(id, JobKind.TRANSLATE).single()
                val saved = LlmJson.parse<StoredTranslation>(job.responseJson!!)
                val cache = when (mode) {
                    "legacy" -> saved.copy(integrityVersion = null, seg = saved.seg.map { it.copy(sourceIds = null) })
                    "altered" -> saved.copy(seg = saved.seg.map { it.copy(sourceIds = listOf("foreign")) })
                    "unsupported" -> saved.copy(integrityVersion = 999)
                    else -> saved
                }
                val json = if (mode == "absent") null else LlmJson.strict.encodeToString(cache)
                p.db.jobDao().update(job.copy(responseJson = json))
                val translationsBefore = fake.callsFor(ModelRole.TRANSLATE); val extractsBefore = fake.callsFor(ModelRole.EXTRACT)
                fake.errorInjector = null; p.documents.requeue(id)
                val resumed = p.processor().process(id)
                if (mode == "altered" || mode == "unsupported") {
                    assertTrue(resumed is ProcessOutcome.Failed && resumed.code == ErrorCode.INVALID_RESPONSE)
                    assertEquals(extractsBefore, fake.callsFor(ModelRole.EXTRACT))
                } else assertTrue("$mode $resumed", resumed is ProcessOutcome.Ready)
                assertEquals(translationsBefore, fake.callsFor(ModelRole.TRANSLATE))
                assertEquals(json, p.db.jobDao().getById(job.id)!!.responseJson)
            }
        }
    }

    @Test fun legacyOversizedPendingJobIsBoundedWithoutRenumbering() = runTest {
        val fake = FakeLlmClient()
        fake.errorInjector = { req, _ -> if (req.role == ModelRole.TRANSLATE) LlmException.Network("Synthetic interruption") else null }
        TestPipeline(tmp.newFolder(), fake).use { p ->
            val id = p.documents.create(NewDocument.Text(List(65) { "Датчик измеряет температуру." }.joinToString(" "), null, Lang.EN, Lang.RU))
            assertTrue(p.processor().process(id) is ProcessOutcome.Failed)
            val job = p.db.jobDao().getByKind(id, JobKind.TRANSLATE).first()
            p.db.jobDao().deleteByKind(id, JobKind.TRANSLATE)
            p.db.jobDao().insert(job.copy(id = 0, rangeStart = 0, rangeEnd = 64))
            val before = fake.requests.size
            fake.errorInjector = null; p.documents.requeue(id)
            assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
            val translated = fake.requests.drop(before).filter { it.role == ModelRole.TRANSLATE }.map { Messages.parseSentences(it.user) }
            assertTrue(translated.all { it.size <= SectionPlanner.MAX_SECTION_SENTENCES })
            assertEquals((0..64).toList(), translated.flatten().map { it.idx })
            assertEquals(1, p.db.jobDao().getByKind(id, JobKind.TRANSLATE).size)
        }
    }

    @Test fun cacheOnlyRecoveryCannotBypassModernProofOrForeignLegacyHeading() = runTest {
        for (legacy in listOf(false, true)) {
            val fake = FakeLlmClient()
            fake.interceptor = { req, _ -> if (req.role == ModelRole.TRANSLATE) reply(req, ::normal) else null }
            TestPipeline(tmp.newFolder(), fake).use { p ->
                val id = p.documents.create(NewDocument.Text(text, null, Lang.EN, Lang.RU))
                assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
                val job = p.db.jobDao().getByKind(id, JobKind.TRANSLATE).single()
                val saved = LlmJson.parse<StoredTranslation>(job.responseJson!!)
                val changed = if (!legacy) saved.copy(seg = saved.seg.map { it.copy(sourceIds = listOf("foreign")) })
                    else saved.copy(integrityVersion = null, seg = saved.seg.map {
                        it.copy(sourceIds = null, t = it.t.replace("### 7.", "### 9."))
                    })
                p.db.jobDao().update(job.copy(responseJson = LlmJson.strict.encodeToString(changed)))
                if (legacy) for (s in changed.seg) p.db.openHelper.writableDatabase.execSQL(
                    "UPDATE Segment SET translation = ? WHERE documentId = ? AND firstSentenceIdx = ?", arrayOf<Any>(s.t, id, s.from))
                // Synthetic interrupted consolidation, not an automatic audit of a READY owner document.
                p.db.documentDao().setStatus(id, DocStatus.FAILED, .9f, null, null, 123L)
                val before = fake.requests.size
                val outcome = p.processor().process(id, localConsolidationOnly = true)
                assertTrue("$outcome", outcome is ProcessOutcome.Failed && outcome.code == ErrorCode.INVALID_RESPONSE)
                assertEquals(before, fake.requests.size)
                assertEquals(DocStatus.FAILED, p.documents.get(id)!!.status)
            }
        }
    }
}
