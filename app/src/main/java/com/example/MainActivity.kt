package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.neptuno.ai.gemini.configureAppCheck
import com.example.neptuno.app.NeptunoAppShell
import com.example.neptuno.app.NeptunoApplication
import com.example.neptuno.auth.AuthScreen
import com.example.neptuno.auth.authStateFlow
import com.example.neptuno.auth.signOutWithCredentialClear
import com.example.neptuno.core.model.ThemeMode
import com.example.neptuno.core.model.UserPreferences
import com.example.ui.theme.NeptunoTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

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

            val auth = remember { Firebase.auth }
            val currentUser by auth.authStateFlow()
                .collectAsStateWithLifecycle(initialValue = auth.currentUser)
            val credentialManager = remember(this) { CredentialManager.create(this) }
            val appScope = rememberCoroutineScope()

            NeptunoTheme(darkTheme = darkTheme) {
                val user = currentUser
                if (user == null) {
                    AuthScreen(onAuthSuccess = {})
                } else {
                    key(user.uid) {
                        NeptunoAppShell(
                            container = container,
                            currentUserId = user.uid,
                            currentUserName = user.displayName ?: "Usuario Neptuno",
                            currentUserEmail = user.email,
                            onSignOut = {
                                signOutWithCredentialClear(
                                    credentialManager = credentialManager,
                                    scope = appScope
                                )
                            }
                        )
                    }
                }
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
