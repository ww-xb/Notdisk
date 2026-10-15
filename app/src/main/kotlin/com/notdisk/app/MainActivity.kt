package com.notdisk.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notdisk.app.navigation.NotDiskNavHost
import com.notdisk.core.database.settings.NotDiskSettingsStore
import com.notdisk.core.designsystem.theme.NotDiskTheme
import com.notdisk.core.designsystem.theme.ThemeMode
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The single Activity.
 *
 * ### Why there is one Activity and no more
 * The app is entirely Compose, so screens are composables rather than Activities. That is not merely
 * stylistic: an Activity boundary is where state is lost, and a media player or a preview WebView
 * surviving a screen change is much easier to reason about inside one Activity than across several.
 *
 * ### What this class deliberately does
 * Only two things: apply the theme (from persisted settings) and host the navigation graph. All logic
 * lives in ViewModels; all UI lives in feature modules. An Activity that starts making decisions is
 * the beginning of an untestable screen.
 *
 * The theme is read from [NotDiskSettingsStore] rather than from a hardcoded default so that the
 * user's choice applies before the first screen composes — reading it later would produce a visible
 * flash from the default theme to the chosen one on every cold start.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * The settings store, injected because the theme choice must be available *before* the first
     * composition. Only the two theme-related flows are read here; everything else is the settings
     * screen's business.
     */
    @Inject
    lateinit var settings: NotDiskSettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        // Enabled before `setContent` so the first frame already accounts for the system bars;
        // doing it afterwards produces a one-frame layout jump.
        //
        // The argument-free overload is used deliberately. On Android 15 (this app's floor) every
        // app is edge-to-edge by default and the system bar *backgrounds* are transparent and not
        // controllable, so passing explicit `SystemBarStyle`s would be either a no-op or a lie. What
        // the app controls is where, and how often, the *insets* are consumed — which is decided in
        // the Compose tree (see `NotDiskNavHost`), not here.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            NotDiskApp()
        }
    }

    /**
     * Applies the persisted theme and hosts the navigation graph.
     *
     * `collectAsStateWithLifecycle` (rather than `collectAsState`) is used so the settings flow stops
     * being collected when the Activity is stopped. That matters because the flow reads from
     * DataStore, and leaving it collected in the background would keep a disk-backed subscription
     * alive for no reason.
     */
    @Composable
    private fun NotDiskApp() {
        val themeChoice by settings.themeChoice.collectAsStateWithLifecycle(initialValue = null)
        val dynamicColor by settings.dynamicColor.collectAsStateWithLifecycle(initialValue = true)

        val themeMode = when (themeChoice) {
            NotDiskSettingsStore.ThemeChoice.LIGHT -> ThemeMode.LIGHT
            NotDiskSettingsStore.ThemeChoice.DARK -> ThemeMode.DARK
            // `null` is the pre-first-emission state; SYSTEM is the correct initial value because it
            // matches what the platform is already showing.
            else -> ThemeMode.SYSTEM
        }

        NotDiskTheme(
            themeMode = themeMode,
            dynamicColor = dynamicColor,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                NotDiskNavHost()
            }
        }
    }
}
