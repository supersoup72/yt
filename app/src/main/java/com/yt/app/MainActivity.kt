package com.yt.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import com.yt.app.ui.*

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Home : Screen("home", "Home", Icons.Default.Home)
    object Search : Screen("search", "Search", Icons.Default.Search)
    object Subscriptions : Screen("subs", "Subscriptions", Icons.Default.Subscriptions)
    object Library : Screen("library", "Library", Icons.Default.VideoLibrary)
}

val SCREENS = listOf(Screen.Home, Screen.Search, Screen.Subscriptions, Screen.Library)

class MainActivity : ComponentActivity() {

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions result — INTERNET is normal and auto-granted */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request permissions
        val perms = mutableListOf(Manifest.permission.INTERNET)
        if (android.os.Build.VERSION.SDK_INT <= 32) {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermission.launch(missing.toTypedArray())

        val repo = VideoRepository(this)

        setContent {
            YtAppTheme {
                var currentScreen by remember { mutableStateOf<Screen>(Screen.Search) }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            SCREENS.forEach { screen ->
                                NavigationBarItem(
                                    selected = currentScreen == screen,
                                    onClick = { currentScreen = screen },
                                    icon = { Icon(screen.icon, contentDescription = screen.label) },
                                    label = { Text(screen.label) }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Surface(modifier = Modifier.padding(innerPadding)) {
                        when (currentScreen) {
                            Screen.Home -> HomeScreen(repo)
                            Screen.Search -> SearchScreen(repo)
                            Screen.Subscriptions -> SubsScreen(repo)
                            Screen.Library -> LibraryScreen(repo)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun YtAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFFFF0000),
            secondary = androidx.compose.ui.graphics.Color(0xFFAAAAAA),
            background = androidx.compose.ui.graphics.Color(0xFF0F0F0F),
            surface = androidx.compose.ui.graphics.Color(0xFF1A1A1A),
            onBackground = androidx.compose.ui.graphics.Color.White,
            onSurface = androidx.compose.ui.graphics.Color.White
        ),
        content = content
    )
}
