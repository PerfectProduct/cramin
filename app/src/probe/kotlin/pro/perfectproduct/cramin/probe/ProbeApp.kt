package pro.perfectproduct.cramin.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import pro.perfectproduct.cramin.app.*
import pro.perfectproduct.cramin.data.db.CardStatus
import pro.perfectproduct.cramin.data.repo.*
import pro.perfectproduct.cramin.llm.*
import pro.perfectproduct.cramin.pipeline.*
import pro.perfectproduct.cramin.testing.FakeLlmClient
import pro.perfectproduct.cramin.util.*
import java.io.File

/** Explicit -PlifecycleProbe=true fixture: persistent Room + real WorkManager, no paid clients. */
class ProbeApp : CraminApp() {
    override fun createContainer(): AppContainer = ProbeContainer(this)
}

class ProbeContainer(context: Context) : AppContainer(context) {
    private val fake = FakeLlmClient()
    private val config = ModelConfigResolver.resolve(null, null, ModelsConfigFile(1,
        roles = ModelRole.entries.associate { it.key to RoleConfig("fake/" + it.key, 0.1, 8000) }), null)
    override val llmClient: LlmClient get() = fake
    override fun processorDeps(): ProcessorDeps = super.processorDeps().let {
        ProcessorDeps(it.db, it.files, fake, it.extractors, null, null, { config }, { null },
            it.stoplists, it.segmenter, it.usage, it.clock, checkpoint = { point -> pauseAt(appContext, point) })
    }
}

private fun pauseAt(context: Context, point: String) {
    val marker = File(context.filesDir, "probe-stop")
    if (marker.isFile && marker.readText().trim() == point) {
        File(context.filesDir, "probe-reached").writeText(point)
        while (marker.exists()) Thread.sleep(100)
    }
}

/** Only exported by the opt-in fixture manifest. No real text, secrets or network parameters. */
class ProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val container = (context.applicationContext as CraminApp).container
        container.appScope.launch {
            try {
                val prefs = context.getSharedPreferences("probe", Context.MODE_PRIVATE)
                when (intent.getStringExtra("command")) {
                    "seed" -> {
                        container.settingsStore.setOnboardingDone(true)
                        val text = List(12) { i -> if (i % 2 == 0) "The bank near the river opened in the morning." else "The library has many books and the teacher reads stories." }.joinToString("\n\n")
                        val id = container.documentRepository.create(NewDocument.Text(text, "Synthetic lifecycle document", Lang.RU, Lang.EN))
                        prefs.edit().putLong("document", id).commit()
                        container.processScheduler.enqueue(id)
                    }
                    "mark" -> {
                        val id = prefs.getLong("document", -1)
                        val cards = container.db.cardDao().getByDocument(id)
                        check(cards.size >= 2)
                        container.cardRepository.setStatus(cards[0].id, CardStatus.KNOWN)
                        container.cardRepository.setStarred(cards[0].id, true)
                        container.cardRepository.setStatus(cards[1].id, CardStatus.LEARNING)
                        File(context.filesDir, "probe-expected").writeText(fingerprint(container, id))
                    }
                    "reprocess" -> {
                        val id = prefs.getLong("document", -1)
                        container.processScheduler.cancelAndAwait(id)
                        DocumentRepository(container.db, container.files, Clock { System.currentTimeMillis() }, checkpoint = { pauseAt(context, "repo-$it") }).prepareReprocess(id)
                        container.processScheduler.enqueue(id)
                    }
                    "report" -> {
                        val id = prefs.getLong("document", -1)
                        val doc = container.documentRepository.get(id)
                        val cards = container.db.cardDao().getByDocument(id)
                        val hash = fingerprint(container, id)
                        val expected = File(context.filesDir, "probe-expected").takeIf { it.isFile }?.readText()
                        File(context.filesDir, "probe-report").writeText("status=${doc?.status}; cards=${cards.size}; known=${cards.count { it.status == CardStatus.KNOWN }}; learning=${cards.count { it.status == CardStatus.LEARNING }}; starred=${cards.count { it.starred }}; progressMatches=${expected == hash}; fingerprint=$hash; pid=${android.os.Process.myPid()}\n")
                    }
                }
            } catch (t: Throwable) {
                File(context.filesDir, "probe-error").writeText(t.javaClass.simpleName)
            } finally { pending.finish() }
        }
    }
    private suspend fun fingerprint(container: AppContainer, id: Long): String = Hashing.sha256Hex(
        container.db.cardDao().getByDocument(id).sortedWith(compareBy({ it.lemmaKey }, { it.meaningKey }))
            .joinToString("\n") { "${it.lemmaKey}|${it.meaningKey}|${it.status}|${it.starred}" }.toByteArray())
}
