package com.overstuffed.monologue_orbital_companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.overstuffed.monologue_orbital_companion.ui.theme.MonologueorbitalcompanionTheme
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize the singleton sync coordinator with the application context so it outlives
        // this Activity. Idempotent — safe to call on every creation.
        SyncCoordinator.initialize(applicationContext)

        enableEdgeToEdge()

        setContent {
            MonologueorbitalcompanionTheme {
                MonologueNavHost()
            }
        }
    }

    override fun onDestroy() {
        SyncCoordinator.shutdown()
        super.onDestroy()
    }
}

/**
 * The single configuration route for this app. Annotated [Serializable] and implements [NavKey] so
 * the back stack can survive configuration changes and process death per Navigation 3 requirements.
 */
@Serializable
private data object ConfigurationRoute : NavKey

/**
 * State-driven Navigation 3 host. This single-screen app has exactly one destination, but using
 * [NavDisplay] keeps navigation architecture in place for any future screens.
 */
@Composable
private fun MonologueNavHost() {
    val backStack = rememberNavBackStack(ConfigurationRoute)

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = { key ->
            when (key) {
                is ConfigurationRoute -> NavEntry(key) {
                    ConfigurationScreen()
                }

                else -> error("Unknown route: $key")
            }
        },
    )
}