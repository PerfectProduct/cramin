package pro.perfectproduct.cramin.pipeline

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pro.perfectproduct.cramin.data.db.*
import pro.perfectproduct.cramin.data.repo.NewDocument
import pro.perfectproduct.cramin.llm.*
import pro.perfectproduct.cramin.testing.*
import pro.perfectproduct.cramin.util.Lang

@RunWith(RobolectricTestRunner::class)
class ConsolidationRecoveryTest {
    @get:Rule val tmp = TemporaryFolder()
    private suspend fun TestPipeline.create() = documents.create(NewDocument.Text(
        "The bank beside the river holds water. The bank accepts money from customers.", null, Lang.RU, Lang.EN))

    @Test fun lengthOnIndivisibleGroupStopsWithoutLosingOccurrences() = runTest {
        for (invalidFirst in listOf(false, true)) {
            val fake = FakeLlmClient()
            var n = 0
            fake.interceptor = { req, _ -> if (req.role == ModelRole.CONSOLIDATE) {
                n++
                LlmResponse("invalid", if (invalidFirst && n == 1) "stop" else "length", LlmUsage(1, 1, 0.001), req.model)
            } else null }
            TestPipeline(tmp.newFolder(), fake).use { p ->
                val id = p.create()
                assertEquals(ErrorCode.CONSOLIDATION_LIMIT, (p.processor().process(id) as ProcessOutcome.Failed).code)
                assertEquals(if (invalidFirst) 2 else 1, n)
                assertTrue(p.db.cardDao().getByDocument(id).isEmpty())
                val before = fake.requests.size
                assertEquals(ErrorCode.CONSOLIDATION_LIMIT, (p.processor().process(id) as ProcessOutcome.Failed).code)
                assertEquals(before, fake.requests.size)
            }
        }
    }

    @Test fun fencedConsolidationIsIdenticalAfterResume() = runTest {
        val fake = FakeLlmClient()
        fake.interceptor = { req, _ -> if (req.role == ModelRole.CONSOLIDATE) {
            val items = LlmJson.lenient.decodeFromString<List<ConsolidateItemInput>>(req.user.substringAfter("ITEMS:\n"))
            val response = ConsolidateResponse(items.map { ConsolidatedItem(it.k, listOf(ConsolidatedSense("единое значение", it.o.map { o -> o.id }))) })
            LlmResponse("```json\n${kotlinx.serialization.json.Json.encodeToString(ConsolidateResponse.serializer(), response)}\n```", "stop", LlmUsage(1, 1, 0.001), req.model)
        } else null }
        TestPipeline(tmp.newFolder(), fake).use { p ->
            val id = p.create()
            assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
            assertEquals(1, p.db.cardDao().getByDocument(id).count { it.lemma == "bank" })
            val saved = p.db.jobDao().getByKind(id, JobKind.CONSOLIDATE).single()
            val parsed = LlmJson.parse<ConsolidateResponse>(saved.responseJson!!)
            p.db.jobDao().update(saved.copy(responseJson = "```json\n${kotlinx.serialization.json.Json.encodeToString(ConsolidateResponse.serializer(), parsed)}\n```"))
            val calls = fake.requests.size
            p.documents.requeue(id)
            assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
            assertEquals(calls, fake.requests.size)
            assertEquals(1, p.db.cardDao().getByDocument(id).count { it.lemma == "bank" })
        }
    }
    @Test fun cachedRecoverySkipsFailedBriefAndPreservesProgressWithLegacySnapshot() = runTest {
        val fake = FakeLlmClient()
        TestPipeline(tmp.newFolder(), fake).use { p ->
            val id = p.create()
            assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
            val card = p.db.cardDao().getByDocument(id).first { it.lemma == "bank" }
            p.cards.setStatus(card.id, CardStatus.KNOWN)
            p.cards.setStarred(card.id, true)
            val brief = p.db.jobDao().getByKind(id, JobKind.BRIEF).single()
            p.db.jobDao().update(brief.copy(status = JobStatus.FAILED))
            p.db.documentDao().setPipelineSnapshot(id,
                kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.MapSerializer(
                    kotlinx.serialization.serializer<String>(), RoleConfig.serializer()), TestPipeline.FAKE_CONFIG.roles), 1, p.clock.now())
            p.documents.requeue(id)
            val calls = fake.requests.size
            fake.errorInjector = { _, _ -> AssertionError("API MUST NOT RUN") }
            assertTrue(p.processor().process(id, localConsolidationOnly = true) is ProcessOutcome.Ready)
            assertEquals(calls, fake.requests.size)
            val restored = p.db.cardDao().getByDocument(id).single { it.lemmaKey == card.lemmaKey && it.meaningKey == card.meaningKey }
            assertEquals(CardStatus.KNOWN, restored.status)
            assertTrue(restored.starred)
        }
    }

    @Test fun missingBatchNeverCallsApiAndDoesNotDeleteCards() = runTest {
        val fake = FakeLlmClient()
        TestPipeline(tmp.newFolder(), fake).use { p ->
            val id = p.create()
            assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
            val before = p.db.cardDao().getByDocument(id)
            val job = p.db.jobDao().getByKind(id, JobKind.CONSOLIDATE).single()
            p.db.jobDao().update(job.copy(status = JobStatus.PENDING, responseJson = null))
            p.documents.requeue(id)
            val calls = fake.requests.size
            assertTrue(p.processor().process(id, localConsolidationOnly = true) is ProcessOutcome.Failed)
            assertEquals(calls, fake.requests.size)
            assertEquals(before, p.db.cardDao().getByDocument(id))
            val diagnostic = FailureDiagnostic.forDocument(p.documents.get(id)!!)!!
            assertEquals(FailureStage.CONSOLIDATING, diagnostic.stage)
            assertEquals(ConsolidationStep.REQUEST, diagnostic.local!!.step)
            assertEquals(0, diagnostic.local!!.clientInvocations)
            assertTrue(diagnostic.copyText("copy-version", 34).contains("NOT_INVOKED"))
            assertTrue(diagnostic.local!!.exceptionTypes.first().endsWith("ConsolidationCacheUnfinished"))
        }
    }

    @Test fun corruptPersistedExtractionRecordsSafeLocalCauseAndRetainsCards() = runTest {
        val fake = FakeLlmClient()
        TestPipeline(tmp.newFolder(), fake).use { p ->
            val id = p.create()
            p.processor().process(id)
            val before = p.db.cardDao().getByDocument(id)
            val job = p.db.jobDao().getByKind(id, JobKind.EXTRACT).single()
            p.db.jobDao().update(job.copy(responseJson = "PERSONAL_DOCUMENT_SECRET https://secret.invalid/key"))
            p.documents.requeue(id)
            val calls = fake.requests.size
            assertTrue(p.processor().process(id, localConsolidationOnly = true) is ProcessOutcome.Failed)
            assertEquals(calls, fake.requests.size)
            assertEquals(before, p.db.cardDao().getByDocument(id))
            val diagnostic = FailureDiagnostic.forDocument(p.documents.get(id)!!)!!
            assertEquals(ConsolidationStep.READ_EXTRACTION, diagnostic.local!!.step)
            assertEquals(ErrorCode.CONSOLIDATION_CACHE_INVALID, diagnostic.code)
            assertTrue(diagnostic.local!!.exceptionTypes.any { it.contains("JsonDecodingException") })
            assertTrue(diagnostic.local!!.appFrames.isNotEmpty())
            assertEquals(1, diagnostic.local!!.counts["extractionDone"])
            val text = diagnostic.encode() + diagnostic.copyText("COPY_VERSION", 34)
            for (secret in listOf("PERSONAL_DOCUMENT_SECRET", "secret.invalid", "https://")) assertFalse(text.contains(secret))
        }
    }

    @Test fun localWriteFailureHasCauseChainAndRollsBackReplacement() = runTest {
        var armed = false
        val fake = FakeLlmClient()
        TestPipeline(tmp.newFolder(), fake, checkpoint = { if (armed && it == "replacementDeleted")
            throw IllegalStateException("private text", IllegalArgumentException("key=secret")) }).use { p ->
            val id = p.create()
            p.processor().process(id)
            val before = p.db.cardDao().getByDocument(id)
            p.documents.requeue(id)
            armed = true
            assertTrue(p.processor().process(id, localConsolidationOnly = true) is ProcessOutcome.Failed)
            assertEquals(before, p.db.cardDao().getByDocument(id))
            val d = FailureDiagnostic.forDocument(p.documents.get(id)!!)!!
            assertEquals(ConsolidationStep.REPLACE_CARDS, d.local!!.step)
            assertEquals(listOf("java.lang.IllegalStateException", "java.lang.IllegalArgumentException"), d.local!!.exceptionTypes.distinct())
            assertFalse(d.encode().contains("private text"))
            assertFalse(d.encode().contains("key=secret"))
            assertNotEquals("COPY_VERSION", d.local!!.build)
        }
    }

    @Test fun oldFailureRemainsUnknownAndDoesNotAcquireCurrentBuild() {
        val old = """{"source":"URL","stage":"CONSOLIDATING","code":"UNKNOWN","observedAtEpochMs":1791027829315}"""
        val d = kotlinx.serialization.json.Json.decodeFromString(FailureDiagnostic.serializer(), old)
        assertNull(d.local)
        assertNull(d.request)
        val text = d.copyText("NEW_BUILD", 34)
        assertTrue(text.contains("Local event: UNKNOWN (not recorded)"))
        assertFalse(text.contains("Local event build:"))
        assertFalse(text.contains("NOT_INVOKED"))
    }

    @Test fun incompatibleBatchBindingCannotReplaceProgress() = runTest {
        val fake = FakeLlmClient()
        TestPipeline(tmp.newFolder(), fake).use { p ->
            val id = p.create()
            p.processor().process(id)
            val before = p.db.cardDao().getByDocument(id)
            val job = p.db.jobDao().getByKind(id, JobKind.CONSOLIDATE).single()
            p.db.jobDao().update(job.copy(responseJson = job.responseJson!!.replace("cacheInputSha256\":\"", "cacheInputSha256\":\"wrong")))
            val calls = fake.requests.size
            p.documents.requeue(id)
            assertTrue(p.processor().process(id, localConsolidationOnly = true) is ProcessOutcome.Failed)
            assertEquals(calls, fake.requests.size)
            assertEquals(before, p.db.cardDao().getByDocument(id))
            val d = FailureDiagnostic.forDocument(p.documents.get(id)!!)!!
            assertEquals(ConsolidationStep.READ_CACHED_RESPONSE, d.local!!.step)
            assertTrue(d.local!!.exceptionTypes.first().endsWith("ConsolidationCacheInvalid"))
        }
    }

    @Test fun failureAfterResponseDistinguishesClientUseFromMissingHttpEvent() = runTest {
        TestPipeline(tmp.newFolder(), FakeLlmClient(), checkpoint = { if (it == "replacementDeleted") error("private") }).use { p ->
            val id = p.create()
            assertTrue(p.processor().process(id) is ProcessOutcome.Failed)
            val d = FailureDiagnostic.forDocument(p.documents.get(id)!!)!!
            assertNull(d.request) // fake success has no HTTP failure event
            // Consolidation and the existing separate topic classification both use the client.
            assertEquals(2, d.local!!.clientInvocations)
            assertEquals(2, d.local!!.clientResponses)
            assertTrue(d.copyText("copy", 34).contains("INVOKED (HTTP details only in request event)"))
        }
    }

}
