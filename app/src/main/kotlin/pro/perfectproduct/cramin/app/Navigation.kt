package pro.perfectproduct.cramin.app

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import pro.perfectproduct.cramin.ui.create.CreateScreen
import pro.perfectproduct.cramin.ui.document.DocumentScreen
import pro.perfectproduct.cramin.ui.library.LibraryScreen
import pro.perfectproduct.cramin.ui.settings.LicensesScreen
import pro.perfectproduct.cramin.ui.settings.ModelPickerScreen
import pro.perfectproduct.cramin.ui.settings.OnboardingScreen
import pro.perfectproduct.cramin.ui.settings.SettingsScreen
import pro.perfectproduct.cramin.ui.study.AllDeckScreen
import pro.perfectproduct.cramin.ui.study.StudyScreen

/** Контейнер зависимостей для экранов (ручной DI, SPEC §5.1). */
val LocalContainer = compositionLocalOf<AppContainer> { error("AppContainer not provided") }

/** ViewModel из контейнера без Hilt: `craminViewModel { c -> MyViewModel(c) }`. */
@Composable
inline fun <reified VM : ViewModel> craminViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = LocalContainer.current
    val factory = remember(container) { viewModelFactory { initializer { create(container) } } }
    return viewModel(key = key, factory = factory)
}

/** Что пришло через «Поделиться» (SPEC §7.5): ссылка, текст или PDF. */
data class SharedInput(val url: String? = null, val text: String? = null, val pdfUri: Uri? = null)

object Routes {
    const val ONBOARDING = "onboarding"
    const val LIBRARY = "library"
    const val CREATE = "create?url={url}&text={text}&pdf={pdf}"
    const val DOCUMENT = "document/{id}?tab={tab}"
    const val STUDY = "study/{deckKey}?shuffle={shuffle}"
    const val ALL_DECK = "alldeck"
    const val SETTINGS = "settings"
    const val MODEL_PICKER = "settings/model/{role}"
    const val LICENSES = "settings/licenses"

    fun create(shared: SharedInput? = null): String = "create?url=${enc(shared?.url)}&text=${enc(shared?.text)}&pdf=${enc(shared?.pdfUri?.toString())}"
    fun document(id: Long, tab: String = "cards") = "document/$id?tab=$tab"
    fun study(deckKey: String, shuffle: Boolean = false) = "study/${Uri.encode(deckKey)}?shuffle=$shuffle"
    fun modelPicker(role: String) = "settings/model/$role"
    private fun enc(s: String?) = if (s.isNullOrEmpty()) "" else Uri.encode(s)
}

@Composable
fun CraminNavHost(startDestination: String, navController: NavHostController = rememberNavController(), initialShared: SharedInput? = null) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onDone = { navController.navigate(Routes.LIBRARY) { popUpTo(Routes.ONBOARDING) { inclusive = true } } })
        }
        composable(Routes.LIBRARY) {
            LibraryScreen(
                onOpenDocument = { id, tab -> navController.navigate(Routes.document(id, tab)) },
                onCreate = { navController.navigate(Routes.create()) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onAllDeck = { navController.navigate(Routes.ALL_DECK) },
            )
        }
        composable(
            Routes.CREATE,
            arguments = listOf(
                navArgument("url") { type = NavType.StringType; defaultValue = "" },
                navArgument("text") { type = NavType.StringType; defaultValue = "" },
                navArgument("pdf") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val shared = SharedInput(
                url = entry.arguments?.getString("url")?.takeIf { it.isNotEmpty() },
                text = entry.arguments?.getString("text")?.takeIf { it.isNotEmpty() },
                pdfUri = entry.arguments?.getString("pdf")?.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) },
            )
            CreateScreen(
                shared = shared,
                onClose = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            Routes.DOCUMENT,
            arguments = listOf(navArgument("id") { type = NavType.LongType }, navArgument("tab") { type = NavType.StringType; defaultValue = "cards" }),
        ) { entry ->
            DocumentScreen(
                documentId = entry.arguments?.getLong("id") ?: -1L,
                initialTab = entry.arguments?.getString("tab") ?: "cards",
                onBack = { navController.popBackStack() },
                onStudy = { deckKey, shuffle -> navController.navigate(Routes.study(deckKey, shuffle)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            Routes.STUDY,
            arguments = listOf(navArgument("deckKey") { type = NavType.StringType }, navArgument("shuffle") { type = NavType.BoolType; defaultValue = false }),
        ) { entry ->
            StudyScreen(
                deckKey = Uri.decode(entry.arguments?.getString("deckKey").orEmpty()),
                shuffle = entry.arguments?.getBoolean("shuffle") ?: false,
                onClose = { navController.popBackStack() },
            )
        }
        composable(Routes.ALL_DECK) {
            AllDeckScreen(onBack = { navController.popBackStack() }, onStudy = { deckKey, shuffle -> navController.navigate(Routes.study(deckKey, shuffle)) })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onPickModel = { role -> navController.navigate(Routes.modelPicker(role)) },
                onLicenses = { navController.navigate(Routes.LICENSES) },
            )
        }
        composable(Routes.MODEL_PICKER, arguments = listOf(navArgument("role") { type = NavType.StringType })) { entry ->
            ModelPickerScreen(roleKey = entry.arguments?.getString("role").orEmpty(), onBack = { navController.popBackStack() })
        }
        composable(Routes.LICENSES) { LicensesScreen(onBack = { navController.popBackStack() }) }
    }
    if (initialShared != null) {
        androidx.compose.runtime.LaunchedEffect(initialShared) {
            navController.navigate(Routes.create(initialShared))
        }
    }
}
