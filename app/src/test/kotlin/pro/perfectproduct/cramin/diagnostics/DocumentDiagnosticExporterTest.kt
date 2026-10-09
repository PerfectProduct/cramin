package pro.perfectproduct.cramin.diagnostics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipInputStream
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
import pro.perfectproduct.cramin.testing.FakeLlmClient
import pro.perfectproduct.cramin.testing.TestPipeline
import pro.perfectproduct.cramin.util.Hashing
import pro.perfectproduct.cramin.util.Lang

@RunWith(RobolectricTestRunner::class)
class DocumentDiagnosticExporterTest {
    @get:Rule val tmp = TemporaryFolder()
    private val app = ExportAppInfo("pro.perfectproduct.cramin", "0.1.TEST", 999)
    private val url = "https://user:PRIVATE_PASSWORD@cloud.example/file.md?Signature=SIGNED_SECRET&Expires=123#PRIVATE_FRAGMENT"

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                assertFalse(entry.name.contains("..")); assertFalse(entry.name.startsWith("/"))
                assertFalse(containsKey(entry.name)); put(entry.name, zip.readBytes())
            }
        }
    }
    private suspend fun seed(p: TestPipeline, label: String): Long {
        val id = p.documents.create(NewDocument.Text("$label input", label, Lang.EN, Lang.RU))
        val doc = p.documents.get(id)!!
        p.db.documentDao().update(doc.copy(status = DocStatus.READY, progress = 1f,
            sourceType = SourceType.URL, sourceRef = url, title = url.substringAfter("://"),
            modelsSnapshotJson = "{\"model\":\"$label model\"}", topicSnapshotJson = "{\"topic\":\"$label\"}"))
        p.files.writeSourceText(id, "$label source\n\nLink: $url")
        val sentence = p.db.sentenceDao().insertAll(listOf(SentenceEntity(documentId = id, idx = 0, paragraphIdx = 0, text = "$label sentence", segmentId = null))).single()
        val segment = p.db.segmentDao().insert(SegmentEntity(documentId = id, firstSentenceIdx = 0, lastSentenceIdx = 0, translation = "$label translation"))
        p.db.sentenceDao().assignSegment(id, 0, 0, segment)
        val card = p.db.cardDao().insertCard(CardEntity(documentId = id, lemmaKey = "$label|NOUN", lemma = label,
            lemmaVocalized = null, pos = Pos.NOUN, lang = "en", targetLang = "ru", status = CardStatus.KNOWN,
            starred = true, firstSentenceIdx = 0, updatedAt = 123L, dueAt = 456L, reps = 2, meaningKey = label, category = TopicCategory.CORE))
        val sense = p.db.cardDao().insertSense(SenseEntity(cardId = card, idx = 0, translation = "$label sense", exampleOccurrenceId = null))
        val occurrence = p.db.cardDao().insertOccurrence(OccurrenceEntity(cardId = card, senseId = sense, sentenceId = sentence,
            surface = label, targetSurface = null, start = 0, end = 1, targetStart = null, targetEnd = null, isExample = true))
        p.db.cardDao().setSenseExample(sense, occurrence)
        for (kind in listOf(JobKind.EXTRACT, JobKind.CONSOLIDATE)) p.db.jobDao().insert(JobEntity(documentId = id,
            kind = kind, idx = 0, rangeStart = 0, rangeEnd = 0, status = JobStatus.DONE, attempts = 1,
            model = "$label model", responseJson = " { \"u\": [\"$label saved $kind\"] } ", finishReason = "stop",
            promptTokens = 10, completionTokens = 20, costUsd = 0.01, updatedAt = 123L))
        p.db.reprocessDao().put(ReprocessState(id, "[]", false))
        p.db.studySessionDao().upsert(StudySessionEntity("doc:$id:ALL", "$label progress", 123L))
        return id
    }

    @Test fun archiveHasScopedRowsOriginalResponsesHashesAndNoSecretsWithoutMutation() = runTest {
        val root = tmp.newFolder(); val llm = FakeLlmClient()
        TestPipeline(root, llm).use { p ->
            val id = seed(p, "SELECTED"); val other = seed(p, "OTHER_DOCUMENT")
            val filesRoot = File(root, "files")
            File(filesRoot, "datastore").mkdirs()
            File(filesRoot, "datastore/secrets.preferences_pb").writeText("API_KEY_DO_NOT_EXPORT")
            File(filesRoot, "datastore/settings.preferences_pb").writeText("PRIVATE_SETTINGS")
            File(p.files.dir(id), "not-allowlisted.txt").writeText("API_KEY_DO_NOT_EXPORT")
            val documents = p.db.documentDao().getAll(); val cards = p.db.cardDao().getByDocument(id)
            val jobs = p.db.jobDao().getByDocument(id); val study = p.db.studySessionDao().get("doc:$id:ALL")
            val source = p.files.sourceText(id).readBytes()
            val out = ByteArrayOutputStream()
            DocumentDiagnosticExporter(p.db, filesRoot, app).save(id, { out }, { fail("Successful export must not discard") })
            val entries = unzip(out.toByteArray()); val all = entries.values.joinToString { it.toString(Charsets.UTF_8) }
            assertTrue(all.contains("SELECTED saved EXTRACT")); assertTrue(all.contains("SELECTED saved CONSOLIDATE"))
            for (forbidden in listOf("OTHER_DOCUMENT", "API_KEY_DO_NOT_EXPORT", "PRIVATE_SETTINGS", "SIGNED_SECRET", "PRIVATE_PASSWORD", "PRIVATE_FRAGMENT")) assertFalse(forbidden, all.contains(forbidden))
            assertEquals(setOf("manifest.json", "data/Document.json", "data/Sentence.json", "data/Segment.json", "data/Job.json",
                "data/Card.json", "data/ReprocessState.json", "data/Sense.json", "data/Occurrence.json", "snapshots/processing.json",
                "snapshots/topic.json", "files/source.txt", "files/input.txt", "files/text-provenance.json"), entries.keys)
            val manifest = Json.parseToJsonElement(entries.getValue("manifest.json").decodeToString()).jsonObject
            assertEquals("0.1.TEST", manifest.getValue("exporter").jsonObject.getValue("versionName").jsonPrimitive.content)
            assertEquals(id, manifest.getValue("documentId").jsonPrimitive.long)
            val hashes = manifest.getValue("files").jsonObject
            assertEquals(entries.keys - "manifest.json", hashes.keys)
            hashes.forEach { (name, record) ->
                assertEquals(Hashing.sha256Hex(entries.getValue(name)), record.jsonObject.getValue("sha256").jsonPrimitive.content)
                assertEquals(entries.getValue(name).size, record.jsonObject.getValue("size").jsonPrimitive.int)
            }
            val exportedJobs = Json.parseToJsonElement(entries.getValue("data/Job.json").decodeToString()).jsonArray
            assertEquals(jobs.associate { it.id to it.responseJson }, exportedJobs.associate {
                it.jsonObject.getValue("id").jsonPrimitive.long to it.jsonObject.getValue("responseJson").jsonPrimitive.content
            })
            assertTrue(manifest.getValue("missingData").jsonArray.any { it.jsonPrimitive.content.contains("brief.json") })
            assertTrue(hashes.getValue("files/source.txt").jsonObject.getValue("redacted").jsonPrimitive.boolean)
            assertEquals(documents, p.db.documentDao().getAll()); assertEquals(cards, p.db.cardDao().getByDocument(id))
            assertEquals(jobs, p.db.jobDao().getByDocument(id)); assertEquals(study, p.db.studySessionDao().get("doc:$id:ALL"))
            assertArrayEquals(source, p.files.sourceText(id).readBytes()); assertTrue(p.files.sourceText(other).readText().contains("OTHER_DOCUMENT"))
            assertTrue(llm.requests.isEmpty())
        }
    }

    @Test fun absentInputsAndResponsesAreExplicitAndNoDirectoryIsCreated() = runTest {
        val root = tmp.newFolder()
        TestPipeline(root, FakeLlmClient()).use { p ->
            val id = seed(p, "SELECTED")
            p.files.deleteAll(id)
            val job = p.db.jobDao().getByKind(id, JobKind.EXTRACT).single()
            p.db.jobDao().update(job.copy(responseJson = null))
            val out = ByteArrayOutputStream()
            DocumentDiagnosticExporter(p.db, File(root, "files"), app).capture(id).writeTo(out)
            val manifest = Json.parseToJsonElement(unzip(out.toByteArray()).getValue("manifest.json").decodeToString()).jsonObject
            val missing = manifest.getValue("missingData").jsonArray.map { it.jsonPrimitive.content }
            assertTrue(missing.contains("files/source.txt: absent")); assertTrue(missing.contains("files/input.txt: absent"))
            assertTrue(missing.contains("Job ${job.id}: responseJson absent"))
            assertFalse(File(root, "files/docs/$id").exists())
        }
    }

    @Test fun destinationNullPermissionAndPartialWriteFailuresDiscardAndDoNotChangeRows() = runTest {
        val root = tmp.newFolder()
        TestPipeline(root, FakeLlmClient()).use { p ->
            val id = seed(p, "SELECTED"); val before = p.documents.get(id)
            val exporter = DocumentDiagnosticExporter(p.db, File(root, "files"), app)
            var discarded = 0
            var written = 0
            var closed = false
            for (open in listOf<() -> OutputStream?>(
                { null }, { throw SecurityException("Provider denied") },
                { object : OutputStream() {
                    override fun write(b: Int) { if (++written > 128) throw IOException("Full") }
                    override fun close() { closed = true }
                } },
            )) {
                try { exporter.save(id, open) { discarded++ }; fail("Expected save failure") }
                catch (_: IOException) { } catch (_: SecurityException) { }
            }
            assertEquals(3, discarded); assertEquals(before, p.documents.get(id))
            assertTrue(written > 128); assertTrue(closed)
        }
    }

    @Test fun linkedFileOutsideSelectedDocumentIsRejected() = runTest {
        val root = tmp.newFolder()
        TestPipeline(root, FakeLlmClient()).use { p ->
            val id = seed(p, "SELECTED"); val other = seed(p, "OTHER_DOCUMENT")
            p.files.sourceText(id).delete()
            java.nio.file.Files.createSymbolicLink(p.files.sourceText(id).toPath(), p.files.sourceText(other).toPath())
            try { DocumentDiagnosticExporter(p.db, File(root, "files"), app).capture(id); fail("No foreign file reads") }
            catch (_: IOException) { }
        }
    }

    @Test fun signedOriginalUrlsInsideNestedSavedJsonAreRemovedWithoutInvalidJson() {
        val raw = buildJsonObject { put("responseJson", buildJsonObject { put("text", "See $url") }.toString()) }.toString()
        val clean = SourceReferenceRedactor(url).text(raw)
        val nested = Json.parseToJsonElement(Json.parseToJsonElement(clean).jsonObject.getValue("responseJson").jsonPrimitive.content)
        assertEquals("See https://cloud.example/file.md", nested.jsonObject.getValue("text").jsonPrimitive.content)
        assertFalse(clean.contains("SIGNED_SECRET"))
        assertEquals("https://cloud.example/file.md", SourceReferenceRedactor(url).text(url.substringAfter("://").take(80)))
    }
}
