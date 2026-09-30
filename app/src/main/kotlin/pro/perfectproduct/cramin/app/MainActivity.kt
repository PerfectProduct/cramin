package pro.perfectproduct.cramin.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.map
import pro.perfectproduct.cramin.app.theme.CraminTheme
import pro.perfectproduct.cramin.ingest.UrlClassifier
import pro.perfectproduct.cramin.pipeline.Notifications

class MainActivity : ComponentActivity() {
    private val container: AppContainer get() = (application as CraminApp).container

    /** Вход из «Поделиться» или уведомления; сбрасывается после обработки. */
    private var pendingShared by mutableStateOf<SharedInput?>(null)
    private var pendingDocument by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val c = container
            val onboardingDone by remember { c.settingsStore.settings.map { it.onboardingDone } }.collectAsState(initial = null)
            CraminTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CompositionLocalProvider(LocalContainer provides c) {
                        if (onboardingDone != null) {
                            val navController = rememberNavController()
                            val shared = pendingShared
                            val docId = pendingDocument
                            CraminNavHost(
                                startDestination = if (onboardingDone == true) Routes.LIBRARY else Routes.ONBOARDING,
                                navController = navController,
                                initialShared = shared,
                            )
                            if (shared != null) pendingShared = null
                            if (docId != null) {
                                androidx.compose.runtime.LaunchedEffect(docId) {
                                    navController.navigate(Routes.document(docId))
                                    pendingDocument = null
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.hasExtra(Notifications.EXTRA_DOCUMENT_ID)) {
            pendingDocument = intent.getLongExtra(Notifications.EXTRA_DOCUMENT_ID, -1L).takeIf { it > 0 }
            return
        }
        if (intent.action != Intent.ACTION_SEND) return
        val type = intent.type.orEmpty()
        when {
            type.startsWith("text/") -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                if (text.isEmpty()) return
                // Текст, который целиком является URL, считается ссылкой (SPEC §7.5).
                pendingShared = UrlClassifier.extractUrl(text)?.let { SharedInput(url = it) } ?: SharedInput(text = text)
            }
            type == "application/pdf" -> {
                val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return
                pendingShared = SharedInput(pdfUri = uri)
            }
        }
    }
}
