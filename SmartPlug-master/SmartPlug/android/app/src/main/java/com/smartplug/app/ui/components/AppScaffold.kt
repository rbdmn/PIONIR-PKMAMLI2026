package com.smartplug.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import com.smartplug.app.ui.navigation.TopLevelDestination
import com.smartplug.app.ui.localization.LocalAppLanguage
import com.smartplug.app.ui.localization.localized

private const val WIDE_SCREEN_BREAKPOINT_DP = 600

/**
 * Adaptive navigation shell (spec: "Sediakan sidebar/navigation rail yang dapat hide dan unhide";
 * "Tablet/landscape: sidebar dapat terlihat. Ponsel: sidebar berubah menjadi drawer atau bottom
 * navigation yang ringan."). Width class decides rail-vs-bottom-bar; [railHidden] only affects the
 * rail on wide screens, where collapsing it back to a menu button reclaims horizontal space for
 * measurement grids/history charts.
 */
@Composable
fun AppScaffold(
    destinations: List<TopLevelDestination>,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    railHidden: Boolean,
    onToggleRailHidden: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val isWideScreen = screenWidthDp >= WIDE_SCREEN_BREAKPOINT_DP
    val language = LocalAppLanguage.current

    if (isWideScreen) {
        Row(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(visible = !railHidden, enter = expandHorizontally(), exit = shrinkHorizontally()) {
                NavigationRail {
                    IconButton(onClick = onToggleRailHidden) {
                        Icon(Icons.Filled.Menu, contentDescription = "Sembunyikan navigasi")
                    }
                    destinations.forEach { destination ->
                        NavigationRailItem(
                            selected = currentRoute == destination.route,
                            onClick = { onNavigate(destination.route) },
                            icon = { Icon(destination.icon, contentDescription = localized(language, destination.labelIndonesian, destination.labelEnglish)) },
                            label = { Text(localized(language, destination.labelIndonesian, destination.labelEnglish)) },
                        )
                    }
                }
            }
            if (railHidden) {
                IconButton(onClick = onToggleRailHidden) {
                    Icon(Icons.Filled.Menu, contentDescription = "Tampilkan navigasi")
                }
            }
            content(Modifier.fillMaxSize())
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    destinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = { onNavigate(destination.route) },
                            icon = { Icon(destination.icon, contentDescription = localized(language, destination.labelIndonesian, destination.labelEnglish)) },
                            label = { Text(localized(language, destination.labelIndonesian, destination.labelEnglish)) },
                        )
                    }
                }
            }
        ) { innerPadding ->
            content(Modifier.fillMaxSize().padding(innerPadding))
        }
    }
}
