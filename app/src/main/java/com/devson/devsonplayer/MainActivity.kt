package com.devson.devsonplayer

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.devson.devsonplayer.ui.PlayerScreen
import com.devson.devsonplayer.ui.VideoListScreen
import com.devson.devsonplayer.ui.theme.DevsonPlayerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DevsonPlayerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color    = MaterialTheme.colorScheme.background
                ) {
                    DevsonPlayerApp()
                }
            }
        }
    }
}

@Composable
fun DevsonPlayerApp() {
    val navController = rememberNavController()

    // ─── Permission handling ──────────────────────────────────────────────────
    val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_VIDEO
    else
        Manifest.permission.READ_EXTERNAL_STORAGE

    var hasPermission by rememberSaveable { mutableStateOf(false) }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        permissionDenied = !granted
    }

    LaunchedEffect(Unit) {
        // Check current state
        hasPermission = ContextCompat.checkSelfPermission(
            navController.context, requiredPermission
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            permissionLauncher.launch(requiredPermission)
        }
    }

    // ─── Navigation ───────────────────────────────────────────────────────────
    when {
        hasPermission -> {
            NavHost(
                navController    = navController,
                startDestination = Screen.VideoList.route
            ) {
                composable(Screen.VideoList.route) {
                    VideoListScreen(
                        onVideoSelected = { uri ->
                            navController.currentBackStackEntry
                                ?.savedStateHandle?.set("videoUri", uri.toString())
                            navController.navigate(Screen.Player.route)
                        }
                    )
                }

                composable(Screen.Player.route) { entry ->
                    val uriString = navController.previousBackStackEntry
                        ?.savedStateHandle?.get<String>("videoUri")

                    if (uriString != null) {
                        PlayerScreen(
                            videoUri = Uri.parse(uriString),
                            onBack   = { navController.popBackStack() }
                        )
                    }
                }
            }
        }

        permissionDenied -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Storage permission required to browse videos.\n" +
                    "Please grant it in Settings.",
                    textAlign = TextAlign.Center
                )
            }
        }

        else -> {
            // Waiting for permission dialog
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Requesting permission…")
            }
        }
    }
}

sealed class Screen(val route: String) {
    object VideoList : Screen("video_list")
    object Player   : Screen("player")
}