package com.tera.down

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tera.down.core.model.AppSettings
import com.tera.down.core.model.ThemeMode
import com.tera.down.ui.navigation.TeledownMainScreen
import com.tera.down.ui.theme.TeledownTheme
import kotlinx.coroutines.flow.flowOf

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as? TeradownApplication
        val settingsRepo = app?.container?.settingsRepository

        setContent {
            val settings by (settingsRepo?.settingsFlow ?: flowOf(AppSettings()))
                .collectAsStateWithLifecycle(initialValue = AppSettings())

            val isDarkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            TeledownTheme(darkTheme = isDarkTheme) {
                TeledownMainScreen()
            }
        }
    }
}
