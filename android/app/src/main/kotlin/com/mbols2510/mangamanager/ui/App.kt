package com.mbols2510.mangamanager.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mbols2510.mangamanager.core.TokenStore
import com.mbols2510.mangamanager.data.SettingsStore
import com.mbols2510.mangamanager.data.repo.AuthState
import com.mbols2510.mangamanager.ui.pairing.PairingScreen
import com.mbols2510.mangamanager.ui.pairing.PairingViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Decides, off the main thread, whether a pairing (token and server URL) is stored. */
class AppViewModel(
    tokenStore: TokenStore,
    settings: SettingsStore,
    authState: AuthState,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val paired = MutableStateFlow<Boolean?>(null)

    /** null while loading. */
    val isPaired: StateFlow<Boolean?> = paired.asStateFlow()
    val needsRepair: StateFlow<Boolean> = authState.needsRepair

    init {
        viewModelScope.launch {
            paired.value = withContext(ioDispatcher) {
                runCatching { tokenStore.load() != null && settings.serverUrl() != null }.getOrDefault(false)
            }
        }
    }
}

@Composable
fun App(appViewModel: AppViewModel, viewModelFactory: ViewModelProvider.Factory) {
    Surface(modifier = Modifier.fillMaxSize()) {
        val paired by appViewModel.isPaired.collectAsStateWithLifecycle()
        // Blank background for the few milliseconds the stored pairing takes to read.
        val isPaired = paired
        if (isPaired != null) {
            AppNavHost(
                isPaired = isPaired,
                needsRepair = appViewModel.needsRepair,
                pairingScreen = { onPaired ->
                    PairingScreen(viewModel = viewModel<PairingViewModel>(factory = viewModelFactory), onPaired = onPaired)
                },
            )
        }
    }
}

/** Material 3, following the system light/dark setting; dynamic colors on Android 12+. */
@Composable
fun MangaTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
