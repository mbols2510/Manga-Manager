package com.mbols2510.mangamanager.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mbols2510.mangamanager.core.CachingTokenStore
import com.mbols2510.mangamanager.data.CachingSettingsStore
import com.mbols2510.mangamanager.data.repo.AuthState
import com.mbols2510.mangamanager.ui.doubles.DoublesScreen
import com.mbols2510.mangamanager.ui.doubles.DoublesViewModel
import com.mbols2510.mangamanager.ui.jobs.JobsScreen
import com.mbols2510.mangamanager.ui.jobs.JobsViewModel
import com.mbols2510.mangamanager.ui.pairing.PairingScreen
import com.mbols2510.mangamanager.ui.pairing.PairingViewModel
import com.mbols2510.mangamanager.ui.settings.NotificationBannerViewModel
import com.mbols2510.mangamanager.ui.settings.NotificationPrompt
import com.mbols2510.mangamanager.ui.settings.PostPairingPermissionRequest
import com.mbols2510.mangamanager.ui.settings.SettingsScreen
import com.mbols2510.mangamanager.ui.settings.UpdatesNotificationBanner
import com.mbols2510.mangamanager.ui.settings.SettingsViewModel
import com.mbols2510.mangamanager.ui.updates.UpdatesScreen
import com.mbols2510.mangamanager.ui.updates.UpdatesViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Decides, off the main thread, whether a pairing (token and server URL) is stored. After a successful pairing,
 * [onPaired] asks [notificationPrompt] whether to show the one automatic notification-permission prompt.
 */
class AppViewModel(
    tokenStore: CachingTokenStore,
    settings: CachingSettingsStore,
    authState: AuthState,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val notificationPrompt: NotificationPrompt? = null,
) : ViewModel() {
    private val paired = MutableStateFlow<Boolean?>(null)
    private val permissionRequests = Channel<Unit>(Channel.CONFLATED)

    /** One element per automatic prompt to show; collected by [PostPairingPermissionRequest] in the app shell. */
    val notificationPermissionRequests: Flow<Unit> = permissionRequests.receiveAsFlow()

    /** Every successful pairing: at most one prompt per install ever ([NotificationPrompt.claimAfterPairing]). */
    fun onPaired() {
        val prompt = notificationPrompt ?: return
        viewModelScope.launch {
            if (withContext(ioDispatcher) { prompt.claimAfterPairing() }) permissionRequests.send(Unit)
        }
    }

    /** null while loading. */
    val isPaired: StateFlow<Boolean?> = paired.asStateFlow()
    val needsRepair: StateFlow<Boolean> = authState.needsRepair

    init {
        viewModelScope.launch {
            paired.value = withContext(ioDispatcher) {
                runCatching {
                    tokenStore.warmUp() // the one Keystore decrypt, off the main thread; also fills the cache
                    tokenStore.load() != null && settings.serverUrl() != null
                }.getOrDefault(false)
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
            // Outlives the pairing screen, so the permission result has a registered receiver.
            PostPairingPermissionRequest(appViewModel.notificationPermissionRequests)
            AppNavHost(
                isPaired = isPaired,
                needsRepair = appViewModel.needsRepair,
                pairingScreen = { onPaired ->
                    PairingScreen(
                        viewModel = viewModel<PairingViewModel>(factory = viewModelFactory),
                        onPaired = {
                            appViewModel.onPaired()
                            onPaired()
                        },
                    )
                },
                // Scoped to the Updates back-stack entry: a re-pair pops it, so the next open starts a fresh screen.
                updatesScreen = {
                    UpdatesScreen(
                        viewModel = viewModel<UpdatesViewModel>(factory = viewModelFactory),
                        notificationBanner = {
                            UpdatesNotificationBanner(
                                viewModel = viewModel<NotificationBannerViewModel>(factory = viewModelFactory),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        },
                    )
                },
                doublesScreen = { DoublesScreen(viewModel = viewModel<DoublesViewModel>(factory = viewModelFactory)) },
                jobsScreen = { JobsScreen(viewModel = viewModel<JobsViewModel>(factory = viewModelFactory)) },
                settingsScreen = { toPairing ->
                    SettingsScreen(viewModel = viewModel<SettingsViewModel>(factory = viewModelFactory), onNavigateToPairing = toPairing)
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
