package pro.perfectproduct.cramin.pipeline

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pro.perfectproduct.cramin.data.db.CardStatus
import pro.perfectproduct.cramin.data.db.DocStatus
import pro.perfectproduct.cramin.data.db.JobKind
import pro.perfectproduct.cramin.data.db.JobStatus
import pro.perfectproduct.cramin.data.repo.DeckFilter
import pro.perfectproduct.cramin.data.repo.NewDocument
import pro.perfectproduct.cramin.llm.LlmResponse
import pro.perfectproduct.cramin.llm.LlmUsage
import pro.perfectproduct.cramin.llm.ModelRole
import pro.perfectproduct.cramin.llm.TranslateResponse
import pro.perfectproduct.cramin.llm.LlmJson
import pro.perfectproduct.cramin.testing.FakeLlmClient
import pro.perfectproduct.cramin.testing.Fixtures
import pro.perfectproduct.cramin.testing.TestPipeline
import pro.perfectproduct.cramin.util.Lang

/** Сквозной пайплайн на фейке (SPEC §14.1): фикстуры → карточки, смыслы, примеры; возобновление после «падения». */
@RunWith(RobolectricTestRunner::class)
class PipelineEndToEndTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // Свой каталог файлов на каждый пайплайн: id документов в новой БД начинаются заново.
    private fun pipeline(fake: FakeLlmClient = FakeLlmClient()) = TestPipeline(tmp.newFolder(), fake)

    private suspend fun TestPipeline.importText(lang: Lang, target: Lang): Long =
        documents.create(NewDocument.Text(Fixtures.text(lang), title = null, targetLang = target, sourceLang = null))

    @Test
    fun enToRuProducesCardsSensesExamplesAndOccurrences() = runTest {
        val fake = FakeLlmClient()
        pipeline(fake).use { p ->
            val id = p.importText(Lang.EN, Lang.RU)
            val statuses = ArrayList<DocStatus>()
            val outcome = p.processor().process(id) { s, _ -> if (statuses.lastOrNull() != s) statuses += s }
            assertTrue("$outcome", outcome is ProcessOutcome.Ready)
            assertEquals(listOf(DocStatus.FETCHING, DocStatus.BRIEFING, DocStatus.TRANSLATING, DocStatus.EXTRACTING, DocStatus.CONSOLIDATING, DocStatus.READY), statuses)

            val doc = p.documents.get(id)!!
            assertEquals(DocStatus.READY, doc.status)
            assertEquals("en", doc.sourceLang)
            assertEquals("📘", doc.emoji)
            assertTrue(doc.title.startsWith("The Riverside Library"))
            assertNotNull(doc.briefJson)
            assertTrue(doc.wordCount in 300..360)

            // Предложения покрыты сегментами; есть объединённые сегменты фейка.
            val sentences = p.db.sentenceDao().getByDocument(id)
            val segments = p.db.segmentDao().getByDocument(id)
            assertTrue(sentences.isNotEmpty())
            assertTrue(sentences.all { it.segmentId != null })
            assertEquals(sentences.size, segments.sumOf { it.lastSentenceIdx - it.firstSentenceIdx + 1 })
            assertTrue(segments.any { it.lastSentenceIdx > it.firstSentenceIdx })
            assertTrue(segments.all { it.translation.startsWith("tr_") })

            // Несколько секций и чанков (маленькие лимиты в TestPipeline.FAKE_CONFIG), все DONE.
            val jobs = p.db.jobDao().getByDocument(id)
            assertTrue(jobs.count { it.kind == JobKind.TRANSLATE } >= 2)
            assertTrue(jobs.count { it.kind == JobKind.EXTRACT } >= 3)
            assertEquals(1, jobs.count { it.kind == JobKind.CONSOLIDATE })
            assertTrue(jobs.all { it.status == JobStatus.DONE })
            assertTrue(jobs.all { it.attempts >= 1 && it.promptTokens > 0 })

            // Стоимость: fake.costPerCall за каждый вызов, суммируется в документе.
            val calls = fake.requests.size
            assertEquals(calls * fake.costPerCall, doc.costUsd!!, 1e-9)
            assertTrue(doc.promptTokens > 0 && doc.completionTokens > 0)

            // Карточки: многозначное слово получило два смысла с примерами; стоп-слово отсеяно.
            val cards = p.cards.deckCards(id, DeckFilter.ALL)
            assertTrue("cards=${cards.size}", cards.size >= 40)
            assertTrue(cards.none { it.lemma == "the" })
            val bank = cards.filter { it.lemmaKey == "bank|NOUN" }
            assertEquals(setOf("берег", "банк"), bank.flatMap { it.senses }.map { it.translation }.toSet())
            for (sense in bank.flatMap { it.senses }) {
                val ex = sense.example
                assertNotNull("example for ${sense.translation}", ex)
                assertTrue(ex!!.sentence.contains("bank", ignoreCase = true))
                assertNotNull(ex.start)
                assertEquals("bank", ex.sentence.substring(ex.start!!, ex.end!!).lowercase())
                assertNotNull(ex.translation)
                assertEquals("tr_bank", ex.translation!!.substring(ex.targetStart!!, ex.targetEnd!!))
            }
            val library = cards.first { it.lemma == "library" }
            assertEquals(1, library.senses.size)
            assertEquals(CardStatus.NEW, library.status)
            // Вхождения для подчёркивания (SurfaceMatcher): «library» встречается много раз.
            val occurrences = p.cards.observeOccurrences(id).first()
            assertTrue(occurrences.count { it.occurrence.cardId == library.id } >= 6)
            assertTrue(occurrences.all { it.occurrence.start != null && it.occurrence.end != null })
            assertTrue(cards.all { it.firstSentenceIdx >= 0 })
            assertEquals(cards.sortedBy { it.firstSentenceIdx }.map { it.id }, cards.map { it.id })
        }
    }

    @Test
    fun ruToEnAndHeToRuReachReady() = runTest {
        for ((src, tgt) in listOf(Lang.RU to Lang.EN, Lang.HE to Lang.RU, Lang.EN to Lang.HE)) {
            pipeline().use { p ->
                val id = p.importText(src, tgt)
                val outcome = p.processor().process(id)
                assertTrue("$src→$tgt: $outcome", outcome is ProcessOutcome.Ready)
                val cards = p.cards.deckCards(id, DeckFilter.ALL)
                assertTrue("$src→$tgt cards=${cards.size}", cards.size >= 25)
                val poly = when (src) {
                    Lang.RU -> "ключ|NOUN"
                    Lang.HE -> "רשת|NOUN"
                    Lang.EN -> "bank|NOUN"
                }
                val meanings = cards.filter { it.lemmaKey == poly }
                assertEquals(2, meanings.size)
                assertTrue(meanings.all { it.senses.size == 1 })
                if (src == Lang.HE) assertTrue(meanings.all { it.lemmaVocalized != null })
                assertEquals(src.code, p.documents.get(id)!!.sourceLang)
            }
        }
    }

    @Test
    fun sameLanguageAndUndetectedLanguageFail() = runTest {
        pipeline().use { p ->
            val same = p.documents.create(NewDocument.Text(Fixtures.text(Lang.EN), null, Lang.EN, null))
            val out = p.processor().process(same)
            assertTrue(out is ProcessOutcome.Failed && out.code == ErrorCode.SAME_LANGUAGE)
            assertEquals("SAME_LANGUAGE", p.documents.get(same)!!.errorCode)

            val mixed = p.documents.create(NewDocument.Text("Hello world friends привет мир друзья", null, Lang.RU, null))
            val out2 = p.processor().process(mixed)
            assertTrue(out2 is ProcessOutcome.Failed && out2.code == ErrorCode.LANG_UNDETECTED)
            // Пользователь выбрал язык вручную → повтор проходит.
            p.documents.setSourceLang(mixed, Lang.EN)
            p.documents.requeue(mixed)
            assertTrue(p.processor().process(mixed) is ProcessOutcome.Ready)
        }
    }

    @Test
    fun crashInTheMiddleOfExtractionResumesWithoutRedoingDoneJobs() = runTest {
        val fake = FakeLlmClient()
        pipeline(fake).use { p ->
            val id = p.importText(Lang.EN, Lang.RU)
            var extractCalls = 0
            fake.errorInjector = { req, _ ->
                if (req.role == ModelRole.EXTRACT && ++extractCalls == 2) RuntimeException("injected extraction failure") else null
            }
            val first = p.processor().process(id)
            assertTrue("$first", first is ProcessOutcome.Failed && (first as ProcessOutcome.Failed).code == ErrorCode.UNKNOWN)
            assertEquals(DocStatus.FAILED, p.documents.get(id)!!.status)
            val translateCallsBefore = fake.callsFor(ModelRole.TRANSLATE)
            val briefCallsBefore = fake.callsFor(ModelRole.BRIEF)
            val doneExtract = p.db.jobDao().getByKind(id, JobKind.EXTRACT).count { it.status == JobStatus.DONE }
            val totalExtract = p.db.jobDao().getByKind(id, JobKind.EXTRACT).size
            assertTrue(totalExtract >= 3)

            fake.errorInjector = null
            p.documents.requeue(id)
            val second = p.processor().process(id)
            assertTrue("$second", second is ProcessOutcome.Ready)
            assertEquals(translateCallsBefore, fake.callsFor(ModelRole.TRANSLATE))
            assertEquals(briefCallsBefore, fake.callsFor(ModelRole.BRIEF))
            // Во втором прогоне вызваны только незавершённые чанки.
            assertEquals(totalExtract - doneExtract, fake.callsFor(ModelRole.EXTRACT) - extractCalls)
            assertTrue(p.cards.deckCards(id, DeckFilter.ALL).size >= 40)
        }
    }

    @Test
    fun truncatedTranslationIsSplitInHalves() = runTest {
        val fake = FakeLlmClient()
        var truncated = false
        fake.interceptor = { req, _ ->
            if (req.role == ModelRole.TRANSLATE && !truncated) {
                truncated = true
                LlmResponse("{\"seg\":[]}", "length", LlmUsage(10, 10, 0.0001), req.model)
            } else {
                null
            }
        }
        pipeline(fake).use { p ->
            val id = p.importText(Lang.EN, Lang.RU)
            assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
            val sections = p.db.jobDao().getByKind(id, JobKind.TRANSLATE)
            // Первая секция: усечённый вызов + две половины = 3 попытки в одной задаче.
            assertEquals(3, sections[0].attempts)
            assertTrue(sections.drop(1).all { it.attempts == 1 })
            val sentences = p.db.sentenceDao().getByDocument(id)
            assertTrue(sentences.all { it.segmentId != null })
        }
    }

    @Test
    fun holesAreRefilledWithOneExtraCall() = runTest {
        val fake = FakeLlmClient()
        var dropped = false
        fake.interceptor = { req, _ ->
            if (req.role == ModelRole.TRANSLATE && !dropped) {
                dropped = true
                val base = kotlinx.coroutines.runBlocking { FakeLlmClient().complete(req) }
                val segs = LlmJson.parse<TranslateResponse>(base.content).seg
                // Выбрасываем второй сегмент: дыра, которую надо дозапросить.
                val holed = TranslateResponse(segs.filterIndexed { i, _ -> i != 1 })
                base.copy(content = kotlinx.serialization.json.Json.encodeToString(holed))
            } else {
                null
            }
        }
        pipeline(fake).use { p ->
            val id = p.importText(Lang.EN, Lang.RU)
            assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
            val sections = p.db.jobDao().getByKind(id, JobKind.TRANSLATE)
            assertEquals(2, sections[0].attempts)
            val sentences = p.db.sentenceDao().getByDocument(id)
            assertTrue(sentences.all { it.segmentId != null })
            val fillRequest = fake.requests.filter { it.role == ModelRole.TRANSLATE }[1]
            assertTrue(fillRequest.user.contains("CONTEXT (already translated):"))
            assertFalse(fillRequest.user.substringAfter("SENTENCES:").trim().isEmpty())
        }
    }

    @Test
    fun reprocessKeepsCardStatusesByLemmaKey() = runTest {
        pipeline().use { p ->
            val id = p.importText(Lang.EN, Lang.RU)
            assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
            val library = p.cards.deckCards(id, DeckFilter.ALL).first { it.lemma == "library" }
            p.cards.setStatus(library.id, CardStatus.KNOWN)
            p.cards.setStarred(library.id, true)
            p.documents.prepareReprocess(id)
            p.documents.prepareReprocess(id) // repeated request must retain the original progress
            assertEquals(DocStatus.QUEUED, p.documents.get(id)!!.status)
            assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
            val again = p.cards.deckCards(id, DeckFilter.ALL).first { it.lemma == "library" }
            assertEquals(CardStatus.KNOWN, again.status)
            assertTrue(again.starred)
            assertTrue(again.id != library.id)
        }
    }
    @Test
    fun reprocessPreparationFaultsNeverLoseProgress() = runTest {
        for (point in listOf("snapshot", "deleted", "prepared")) {
            pipeline().use { p ->
                val id = p.importText(Lang.EN, Lang.RU)
                assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
                val card = p.cards.deckCards(id, DeckFilter.ALL).first()
                p.cards.setStatus(card.id, CardStatus.LEARNING)
                p.cards.setStarred(card.id, true)
                val repository = pro.perfectproduct.cramin.data.repo.DocumentRepository(p.db, p.files, p.clock) {
                    if (it == point) error("injected $point")
                }
                assertTrue(runCatching { repository.prepareReprocess(id) }.isFailure)
                // Retry the request whether the first transaction rolled back or committed.
                p.documents.prepareReprocess(id)
                assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
                val restored = p.cards.deckCards(id, DeckFilter.ALL).first { it.lemmaKey == card.lemmaKey }
                assertEquals(CardStatus.LEARNING, restored.status)
                assertTrue(restored.starred)
            }
        }
    }

    @Test
    fun replacementAndReadyFaultsRetainSnapshotOrCompletedProgress() = runTest {
        for (point in listOf("replacementDeleted", "restored", "ready", "committed")) {
            var armed = false
            TestPipeline(tmp.newFolder(), FakeLlmClient(), checkpoint = { if (armed && it == point) error("injected $point") }).use { p ->
                val id = p.importText(Lang.EN, Lang.RU)
                assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
                val card = p.cards.deckCards(id, DeckFilter.ALL).first()
                p.cards.setStatus(card.id, CardStatus.KNOWN)
                p.cards.setStarred(card.id, true)
                p.documents.prepareReprocess(id)
                armed = true
                val outcome = p.processor().process(id)
                assertTrue(if (point == "committed") outcome is ProcessOutcome.Ready else outcome is ProcessOutcome.Failed)
                armed = false
                if (point == "committed") {
                    assertEquals(DocStatus.READY, p.documents.get(id)!!.status)
                    assertTrue(p.processor().process(id) is ProcessOutcome.Skipped)
                } else {
                    assertEquals(DocStatus.FAILED, p.documents.get(id)!!.status)
                    assertTrue(p.db.reprocessDao().get(id)!!.pending)
                    p.documents.prepareReprocess(id)
                    assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
                }
                val restored = p.cards.deckCards(id, DeckFilter.ALL).first { it.lemmaKey == card.lemmaKey }
                assertEquals(CardStatus.KNOWN, restored.status)
                assertTrue(restored.starred)
                assertFalse(p.db.reprocessDao().get(id)!!.pending)
            }
        }
    }

    @Test
    fun legacySnapshotAndInterruptedV1CardsAreRecovered() = runTest {
        for (withFile in listOf(true, false)) {
            pipeline().use { p ->
                val id = p.importText(Lang.EN, Lang.RU)
                assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
                val card = p.cards.deckCards(id, DeckFilter.ALL).first()
                p.cards.setStatus(card.id, CardStatus.KNOWN)
                p.cards.setStarred(card.id, true)
                val snapshot = p.db.cardDao().snapshotStatuses(id)
                // Recreate a v1 interrupted document: no v2 marker, optional surviving file.
                p.db.openHelper.writableDatabase.execSQL("DELETE FROM ReprocessState")
                if (withFile) {
                    DocumentProcessor.writeStatusSnapshot(p.files, id, snapshot)
                    p.db.cardDao().deleteByDocument(id)
                }
                p.documents.requeue(id)
                assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
                val restored = p.cards.deckCards(id, DeckFilter.ALL).first { it.lemmaKey == card.lemmaKey }
                assertEquals(CardStatus.KNOWN, restored.status)
                assertTrue(restored.starred)
                // Stale legacy file after completion must not override newer user progress.
                DocumentProcessor.writeStatusSnapshot(p.files, id, snapshot)
                p.cards.setStatus(restored.id, CardStatus.LEARNING)
                p.cards.setStarred(restored.id, false)
                p.documents.prepareReprocess(id)
                assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
                val fresh = p.cards.deckCards(id, DeckFilter.ALL).first { it.lemmaKey == card.lemmaKey }
                assertEquals(CardStatus.LEARNING, fresh.status)
                assertFalse(fresh.starred)
            }
        }
    }

    @Test
    fun cancelledProcessorReleasesReprocessLockAndRetainsProgress() = runTest {
        val fake = FakeLlmClient()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var pause = false
        val client = object : pro.perfectproduct.cramin.llm.LlmClient {
            override suspend fun complete(request: pro.perfectproduct.cramin.llm.LlmRequest): pro.perfectproduct.cramin.llm.LlmResponse {
                if (pause) { entered.complete(Unit); release.await() }
                return fake.complete(request)
            }
        }
        TestPipeline(tmp.newFolder(), client).use { p ->
            val id = p.importText(Lang.EN, Lang.RU)
            assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
            val card = p.cards.deckCards(id, DeckFilter.ALL).first()
            p.cards.setStatus(card.id, CardStatus.LEARNING)
            p.cards.setStarred(card.id, true)
            p.documents.prepareReprocess(id)
            pause = true
            val old = launch { p.processor().process(id) }
            entered.await()
            val request = async { p.documents.prepareReprocess(id) }
            yield()
            assertFalse(request.isCompleted)
            old.cancelAndJoin()
            request.await()
            pause = false
            assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
            val restored = p.cards.deckCards(id, DeckFilter.ALL).first { it.lemmaKey == card.lemmaKey }
            assertEquals(CardStatus.LEARNING, restored.status)
            assertTrue(restored.starred)
        }
    }

}
