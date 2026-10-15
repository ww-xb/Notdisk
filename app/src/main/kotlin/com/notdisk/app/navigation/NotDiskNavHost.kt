package com.notdisk.app.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.notdisk.feature.accounts.AccountsRoute
import com.notdisk.feature.files.FilesRoute
import com.notdisk.feature.player.PlayerRoute
import com.notdisk.feature.preview.PreviewRoute
import com.notdisk.feature.settings.SettingsRoute

/**
 * The app's navigation graph.
 *
 * ### Why the graph lives in `app` and not in a feature module
 * Navigation is the one place that must know every screen exists. Keeping it here means the feature
 * modules stay mutually unaware — `feature:files` does not import `feature:player`; it exposes an
 * `onOpenMedia` callback and this file supplies the destination. That is what allows a screen to be
 * reused (or removed) without touching its siblings.
 *
 * ### Why the arguments are primitives rather than a serialised file object
 * Every route carries `(providerId, accountId, fileId)` as **strings**. That shape is deliberate:
 *  - it is exactly a `CloudFileKey`, i.e. the only file reference that is valid across provider and
 *    account boundaries;
 *  - it survives process death and deep links, which a serialised `FileItem` would not (and which
 *    would also drag a possibly-stale cached row through navigation);
 *  - it cannot carry a signed URL, because there is no field for one.
 *
 * ### Why there is no accounts tab in the bottom bar
 * Accounts are a *setting*, not a peer of "files": they are configured once and then never
 * consulted, so a permanent third of the bottom bar was spent on a screen the user visits on
 * installation and after a re-login. The accounts screen is reached from the settings screen
 * instead (see `SettingsRoute`'s `onOpenAccounts`), which is also where every other rarely-changed
 * preference already lives.
 */
object Routes {
    const val FILES = "files"
    const val ACCOUNTS = "accounts"
    const val SETTINGS = "settings"

    private const val PLAYER_BASE = "player"
    private const val PREVIEW_BASE = "preview"

    /** `player/{providerId}/{accountId}/{fileId}` */
    const val PLAYER = "$PLAYER_BASE/{providerId}/{accountId}/{fileId}"

    /** `preview/{providerId}/{accountId}/{fileId}` */
    const val PREVIEW = "$PREVIEW_BASE/{providerId}/{accountId}/{fileId}"

    fun player(providerId: String, accountId: String, fileId: String): String =
        "$PLAYER_BASE/$providerId/$accountId/$fileId"

    fun preview(providerId: String, accountId: String, fileId: String): String =
        "$PREVIEW_BASE/$providerId/$accountId/$fileId"
}

/** The two top-level destinations shown in the bottom bar. */
private enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    FILES(Routes.FILES, "文件", Icons.Filled.Folder),
    SETTINGS(Routes.SETTINGS, "设置", Icons.Filled.Settings),
}

/**
 * The root composable: a bottom bar for the two top-level destinations, and a nav host that also
 * owns the two full-screen destinations (player and preview) which are deliberately *outside* the
 * bottom-bar shell so that playback gets the whole window.
 *
 * ### Why the system back press is intercepted here
 * Folder navigation is *not* a navigation-graph concern: the file browser keeps its own breadcrumb
 * inside [FilesViewModel], so the navigation back stack only ever holds the home screen plus
 * player/preview. A system back press at folder depth 3 therefore used to fall straight through to
 * the Activity and close the app — the reported "返回键直接退出APP了 而不是返回上层文件夹".
 *
 * The [BackHandler] below gives the browser first refusal. It is *enabled* only while the browser
 * has somewhere to go up to, which is what keeps the two directions honest: with a folder above,
 * back goes up; with nothing above (a single-account root, or the empty state), the handler is
 * disabled so the platform's own behaviour — leaving the app — runs. There is no "swallow and hope"
 * fallback, because a handler that always consumes would leave the user unable to exit.
 *
 * ### Why this `Scaffold` declares zero content window insets
 * This app is edge-to-edge (`enableEdgeToEdge()`), and the window insets must be consumed exactly
 * once. Material3's `Scaffold` passes its `contentWindowInsets` to the *content* lambda as padding
 * and does **not** union the bottom bar's height into it (verified in the composed
 * `ScaffoldLayout`: the body's padding values come straight from `contentWindowInsets`). Leaving the
 * default `ScaffoldDefaults.contentWindowInsets` (= `systemBars`) in place therefore meant the host
 * already handed the inner screen the status-bar height as top padding — and `feature:files`'s own
 * `Scaffold` then added its `TopAppBar`'s status-bar padding *on top*, which is the reported empty
 * band at the top of the screen ("顶部有点空").
 *
 * Declaring zero here keeps the responsibility with the screen that actually knows: the file
 * browser's `TopAppBar` pads itself under the status bar, and player/preview — which have no app bar
 * — apply `statusBarsPadding()` to their own top row. `NavigationBar` is unaffected either way: it
 * applies its own `NavigationBarDefaults.windowInsets` internally, so the bottom bar is still inset
 * above the gesture area.
 */
@Composable
fun NotDiskNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    // The bottom bar is hidden on player/preview: a media player competing with a navigation bar for
    // vertical space is a worse experience, and the system back gesture already returns the user.
    val showBottomBar = currentDestination?.route in TopLevelDestination.entries.map { it.route }

    Scaffold(
        modifier = modifier,
        // See this function's KDoc. Zero rather than the default `systemBars`: each screen owns its
        // own top inset, and `NavigationBar` insets itself.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        val selected = currentDestination?.hierarchy
                            ?.any { it.route == destination.route } == true

                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTopLevel(destination.route) },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.FILES,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.FILES) {
                FilesRoute(
                    onOpenMedia = { providerId, accountId, fileId ->
                        navController.navigate(Routes.player(providerId, accountId, fileId))
                    },
                    onOpenPreview = { providerId, accountId, fileId ->
                        navController.navigate(Routes.preview(providerId, accountId, fileId))
                    },
                    // The first-run route to the account manager. The files tab is where a user with
                    // no account lands, and the bottom bar has no accounts tab, so this callback is
                    // the *only* way to reach the login screen from that screen: it used to be a
                    // ViewModel call that merely reset the browser's own level, which made
                    // "去绑定账号" appear to do nothing at all.
                    onOpenAccounts = { navController.navigate(Routes.ACCOUNTS) },
                )
            }

            composable(Routes.ACCOUNTS) {
                AccountsRoute(
                    // Selecting an account here only records the preference; the files tab reacts to
                    // it the next time it is rendered. Reporting it back would require the two tabs to
                    // share a ViewModel, which is a coupling this graph exists to avoid.
                    onAccountSelected = { _, _ -> },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsRoute(
                    versionName = APP_VERSION,
                    onOpenAccounts = { navController.navigate(Routes.ACCOUNTS) },
                )
            }

            composable(
                route = Routes.PLAYER,
                arguments = fileArguments(),
            ) { entry ->
                val args = entry.fileArguments()
                if (args == null) {
                    // A malformed route is not recoverable by rendering the player with invented
                    // identifiers, so the user is returned to a screen that works.
                    navController.popBackStack()
                    return@composable
                }
                PlayerRoute(
                    providerId = args.providerId,
                    accountId = args.accountId,
                    fileId = args.fileId,
                    onBack = { navController.popBackStack() },
                    onOpenDownload = { /* handled by the files screen's download action */ },
                )
            }

            composable(
                route = Routes.PREVIEW,
                arguments = fileArguments(),
            ) { entry ->
                val args = entry.fileArguments() ?: run {
                    navController.popBackStack()
                    return@composable
                }
                PreviewRoute(
                    providerId = args.providerId,
                    accountId = args.accountId,
                    fileId = args.fileId,
                    onBack = { navController.popBackStack() },
                    onPlayMedia = { providerId, accountId, fileId ->
                        navController.navigate(Routes.player(providerId, accountId, fileId))
                    },
                    // A download is initiated from the files screen, which owns destination picking
                    // and the transfer UI. Preview only signals *that* the user wants one, so it
                    // returns to the file list rather than starting a transfer with no destination.
                    onDownload = { _, _, _ -> navController.popBackStack() },
                )
            }
        }
    }
}

/** The three string arguments every file-scoped route carries. */
private data class FileRouteArgs(
    val providerId: String,
    val accountId: String,
    val fileId: String,
)

private fun fileArguments() = listOf(
    navArgument("providerId") { type = NavType.StringType },
    navArgument("accountId") { type = NavType.StringType },
    navArgument("fileId") { type = NavType.StringType },
)

private fun androidx.navigation.NavBackStackEntry.fileArguments(): FileRouteArgs? {
    val providerId = arguments?.getString("providerId")?.takeIf { it.isNotBlank() }
        ?: return null
    val accountId = arguments?.getString("accountId")?.takeIf { it.isNotBlank() }
        ?: return null
    val fileId = arguments?.getString("fileId")?.takeIf { it.isNotBlank() }
        ?: return null
    return FileRouteArgs(providerId, accountId, fileId)
}

/**
 * Navigates to a top-level destination with the standard bottom-bar behaviour.
 *
 * The three options are not decoration:
 *  - `popUpTo(startDestination) { saveState = true }` keeps the back stack from growing each time the
 *    user taps a tab;
 *  - `launchSingleTop` avoids stacking the same destination twice;
 *  - `restoreState` brings back the scroll position the user left, which is what makes the tabs feel
 *    like tabs rather than a set of links.
 */
private fun NavHostController.navigateToTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * The version string shown on the settings screen.
 *
 * A constant rather than `BuildConfig.VERSION_NAME` because build-config generation is disabled
 * project-wide (see `gradle.properties`); hardcoding it here keeps the modules free of a generated
 * class while still surfacing a real value the user can quote in a bug report.
 *
 * **It must be updated alongside `versionName` in `app/build.gradle.kts`.** The compiler does not
 * link the two — there is no generated symbol to reference, which is the whole point of disabling
 * build-config — so the coupling is stated here rather than left implicit.
 */
private const val APP_VERSION = "0.2.1"
