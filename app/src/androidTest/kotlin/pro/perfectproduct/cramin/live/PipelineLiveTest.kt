package pro.perfectproduct.cramin.live

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pro.perfectproduct.cramin.LiveApi
import pro.perfectproduct.cramin.app.AppContainer
import pro.perfectproduct.cramin.data.db.CraminDatabase
import pro.perfectproduct.cramin.data.db.DocStatus
import pro.perfectproduct.cramin.data.repo.DeckFilter
import pro.perfectproduct.cramin.data.repo.NewDocument
import pro.perfectproduct.cramin.llm.LlmClient
import pro.perfectproduct.cramin.llm.OpenRouterClient
import pro.perfectproduct.cramin.pipeline.ProcessOutcome
import pro.perfectproduct.cramin.testing.Fixtures
import pro.perfectproduct.cramin.testing.PlainCipher
import pro.perfectproduct.cramin.util.Lang
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File

/** Сквозной пайплайн на устройстве через реальный OpenRouter (SPEC §14.3а): en→ru, ru→en, he→ru, en→he. */
@LiveApi
@RunWith(AndroidJUnit4::class)
class PipelineLiveTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class LiveContainer(context: Context, key: String, dir: File) : AppContainer(
        appContext = context,
        databaseProvider = { CraminDatabase.inMemory(context) },
        settingsDataStoreProvider = { scope -> PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") } },
        secretsDataStoreProvider = { scope -> PreferenceDataStoreFactory.create(scope = scope) { File(dir, "secrets.preferences_pb") } },
        secretCipher = PlainCipher(),
    ) {
        override val llmClient: LlmClient = DeviceLiveEnv.client(
            OpenRouterClient(httpClient, keyProvider = { key }, paramSupport = { m -> modelCatalog.cached()?.find(m)?.supportedParameters?.toSet() }),
        )
    }

    @Before
    fun setUp() = DeviceLiveEnv.quietLogs()

    private fun run(src: Lang, tgt: Lang) = runBlocking {
        val key = DeviceLiveEnv.requireKey()
        val container = LiveContainer(context, key, File(context.cacheDir, "live-${System.nanoTime()}").apply { mkdirs() })
        val id = container.documentRepository.create(NewDocument.Text(Fixtures.text(src), null, tgt, null))
        val t0 = System.currentTimeMillis()
        val outcome = container.newProcessor().process(id)
        val sec = (System.currentTimeMillis() - t0) / 1000
        assertTrue("$src→$tgt: $outcome", outcome is ProcessOutcome.Ready)
        val doc = container.documentRepository.get(id)!!
        assertEquals(DocStatus.READY, doc.status)
        val cards = container.cardRepository.deckCards(id, DeckFilter.ALL)
        assertTrue("cards=${cards.size}", cards.size >= 20)
        assertTrue(cards.all { it.senses.isNotEmpty() })
        android.util.Log.i("CraminLive", "pipeline ${src.code}→${tgt.code}: ${sec}s cost=${doc.costUsd} cards=${cards.size} spent=${DeviceLiveEnv.spentUsd}")
        container.db.close()
    }

    @Test
    fun enToRu() = run(Lang.EN, Lang.RU)

    @Test
    fun ruToEn() = run(Lang.RU, Lang.EN)

    @Test
    fun heToRu() = run(Lang.HE, Lang.RU)

    @Test
    fun enToHe() = run(Lang.EN, Lang.HE)
}
