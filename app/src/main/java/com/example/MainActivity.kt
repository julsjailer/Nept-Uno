package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.neptuno.ai.gemini.configureAppCheck
import com.example.neptuno.app.NeptunoAppShell
import com.example.neptuno.app.NeptunoApplication
import com.example.neptuno.core.model.ThemeMode
import com.example.neptuno.core.model.UserPreferences
import com.example.ui.theme.NeptunoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)
        enableEdgeToEdge()

        val container = (application as NeptunoApplication).appContainer

        setContent {
            val prefs by container.preferencesRepository.userPreferences
                .collectAsStateWithLifecycle(initialValue = UserPreferences())

            val darkTheme = when (prefs.themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            NeptunoTheme(darkTheme = darkTheme) {
                NeptunoAppShell(container = container)
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            (application as? NeptunoApplication)?.appContainer?.releaseAudioEngines()
        }
        super.onDestroy()
    }
}
