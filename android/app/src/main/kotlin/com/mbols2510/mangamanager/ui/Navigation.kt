package com.mbols2510.mangamanager.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mbols2510.mangamanager.R
import kotlinx.coroutines.flow.StateFlow

/** Navigation routes. Tasks 10-12 replace the placeholder content of the four main destinations. */
object Routes {
    const val PAIRING = "pairing"
    const val UPDATES = "updates"
    const val DOUBLES = "doubles"
    const val JOBS = "jobs"
    const val SETTINGS = "settings"
}

private data class TopLevelDestination(
    val route: String,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
)

private val topLevelDestinations = listOf(
    TopLevelDestination(Routes.UPDATES, R.string.nav_updates, R.drawable.ic_nav_updates),
    TopLevelDestination(Routes.DOUBLES, R.string.nav_doubles, R.drawable.ic_nav_doubles),
    TopLevelDestination(Routes.JOBS, R.string.nav_jobs, R.drawable.ic_nav_jobs),
    TopLevelDestination(Routes.SETTINGS, R.string.nav_settings, R.drawable.ic_nav_settings),
)

/** Start on pairing when the device is not paired or its token was rejected. */
fun startRoute(isPaired: Boolean, needsRepair: Boolean): String =
    if (!isPaired || needsRepair) Routes.PAIRING else Routes.UPDATES

/**
 * The app's navigation shell. [pairingScreen] receives the callback to run after a successful pairing.
 * Whenever [needsRepair] becomes true (a 401 anywhere), the back stack is replaced by the pairing route.
 * [updatesScreen], [doublesScreen] and [jobsScreen] are the contents of those destinations (placeholders when not
 * given).
 */
@Composable
fun AppNavHost(
    isPaired: Boolean,
    needsRepair: StateFlow<Boolean>,
    pairingScreen: @Composable (onPaired: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    updatesScreen: @Composable () -> Unit = { ComingSoon(R.string.nav_updates) },
    doublesScreen: @Composable () -> Unit = { ComingSoon(R.string.nav_doubles) },
    jobsScreen: @Composable () -> Unit = { ComingSoon(R.string.nav_jobs) },
    navController: NavHostController = rememberNavController(),
) {
    val repair by needsRepair.collectAsStateWithLifecycle()
    val start = remember { startRoute(isPaired, needsRepair.value) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    LaunchedEffect(repair, currentRoute) {
        if (repair && currentRoute != null && currentRoute != Routes.PAIRING) {
            navController.navigate(Routes.PAIRING) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (currentRoute != null && currentRoute != Routes.PAIRING) {
                BottomBar(currentRoute = currentRoute, navController = navController)
            }
        },
    ) { padding ->
        NavHost(navController = navController, startDestination = start, modifier = Modifier.padding(padding)) {
            composable(Routes.PAIRING) {
                pairingScreen {
                    navController.navigate(Routes.UPDATES) {
                        popUpTo(Routes.PAIRING) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
            composable(Routes.UPDATES) { updatesScreen() }
            composable(Routes.DOUBLES) { doublesScreen() }
            composable(Routes.JOBS) { jobsScreen() }
            composable(Routes.SETTINGS) { ComingSoon(R.string.nav_settings) }
        }
    }
}

@Composable
private fun BottomBar(currentRoute: String, navController: NavHostController) {
    NavigationBar {
        topLevelDestinations.forEach { destination ->
            NavigationBarItem(
                selected = currentRoute == destination.route,
                onClick = {
                    navController.navigate(destination.route) {
                        // Updates is the root of the paired back stack (pairing is always popped).
                        popUpTo(Routes.UPDATES) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                label = { Text(stringResource(destination.label)) },
            )
        }
    }
}

/** Placeholder for destinations built by later tasks. */
@Composable
private fun ComingSoon(@StringRes title: Int) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(text = stringResource(R.string.coming_soon), style = MaterialTheme.typography.bodyMedium)
    }
}
