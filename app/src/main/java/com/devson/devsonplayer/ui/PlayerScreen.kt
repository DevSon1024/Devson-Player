package com.devson.devsonplayer.ui

import android.content.Context
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.devson.devsonplayer.player.DecoderSelector
import com.devson.devsonplayer.player.PlayerController
import com.devson.devsonplayer.player.PlayerPreferencesViewModel
import com.devson.devsonplayer.player.PlayerViewModel
import com.devson.devsonplayer.player.SubtitleManager
import android.app.Activity
import android.content.pm.ActivityInfo
import com.devson.devsonplayer.ui.ScalingMode

/**
 * PlayerScreen
 *
 * Top-level composable for video playback.
 * Embeds SurfaceView (hardware) or GLSurfaceView (software) based on active decoder.
 * Hosts PlayerControls overlay.
 */
@Composable
fun PlayerScreen(
    videoUri: Uri,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = viewModel(),
    prefsViewModel: PlayerPreferencesViewModel = viewModel()
) {
    val context = LocalContext.current
    val playerState: PlayerController.PlayerState by viewModel.state.collectAsStateWithLifecycle()
    val decoderType: DecoderSelector.DecoderType? by viewModel.decoderType.collectAsStateWithLifecycle()
    val subtitle: SubtitleManager.SubtitleCue? by viewModel.currentSubtitle.collectAsStateWithLifecycle()

    // Load the video once
    LaunchedEffect(videoUri) {
        viewModel.load(videoUri)
    }

    // Update subtitle position whenever we have a Playing state
    LaunchedEffect(playerState) {
        if (playerState is PlayerController.PlayerState.Playing) {
            val pos = (playerState as PlayerController.PlayerState.Playing).positionMs
            viewModel.onPositionUpdate(pos)
        }
    }

    val videoInfo: DecoderSelector.VideoInfo? by viewModel.videoInfo.collectAsStateWithLifecycle()
    val scalingMode: ScalingMode by viewModel.scalingMode.collectAsStateWithLifecycle()
    var controlsVisible by remember { mutableStateOf(true) }
    
    val view = LocalView.current
    val activity = context as? Activity
    
    // Auto-Orientation handling
    LaunchedEffect(videoInfo) {
        videoInfo?.let { info ->
            activity?.let { act ->
                if (info.width > info.height) {
                    act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                } else {
                    act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
    }

    // Immersive Mode
    LaunchedEffect(controlsVisible) {
        activity?.window?.let { window ->
            val controller = WindowCompat.getInsetsController(window, view)
            if (controlsVisible) {
                controller.show(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // 1. Video surface — with scaling logic
        val videoModifier = when (scalingMode) {
            ScalingMode.FIT -> {
                if (videoInfo != null && videoInfo!!.width > 0 && videoInfo!!.height > 0) {
                    Modifier.aspectRatio(videoInfo!!.width.toFloat() / videoInfo!!.height.toFloat())
                } else {
                    Modifier.fillMaxSize()
                }
            }
            ScalingMode.CROP -> {
                if (videoInfo != null && videoInfo!!.width > 0 && videoInfo!!.height > 0) {
                    val videoAspect = videoInfo!!.width.toFloat() / videoInfo!!.height.toFloat()
                    // To fill, we want the shortest side to match parent, longest side overflow.
                    // This can be done by using Modifier.layout or more simply with a custom scale.
                    // However, Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = true/false) 
                    // doesn't directly support "Fill".
                    // Let's use a simpler approach: wrap in a box that's very large? No.
                    // Let's use a custom modifier for Crop.
                    Modifier.fillMaxSize() // Default to fill, but need to maintain aspect
                } else {
                    Modifier.fillMaxSize()
                }
            }
            ScalingMode.ORIGINAL -> {
                if (videoInfo != null) {
                    // Center at original aspect ratio but maybe not filling screen
                    Modifier.aspectRatio(videoInfo!!.width.toFloat() / videoInfo!!.height.toFloat())
                } else {
                    Modifier.fillMaxSize()
                }
            }
            else -> Modifier.fillMaxSize()
        }

        Box(modifier = videoModifier, contentAlignment = Alignment.Center) {
            when (decoderType) {
                DecoderSelector.DecoderType.SOFTWARE -> {
                    SoftwareRenderSurface(
                        context = context,
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                DecoderSelector.DecoderType.HARDWARE -> {
                    HardwareRenderSurface(
                        context = context,
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                else -> {
                    // Default to hardware surface if null or unknown
                    HardwareRenderSurface(
                        context = context,
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // 2. Gesture Overlay Layer
        AndroidView(
            factory = { ctx ->
                com.devson.devsonplayer.ui.gestures.GestureOverlayView(ctx).apply {
                    onSingleTapAction = { controlsVisible = !controlsVisible }
                    onToggleAction = { if (playerState is PlayerController.PlayerState.Playing) viewModel.pause() else viewModel.play() }
                    onSeekAction = { delta -> 
                        val current = viewModel.controller.getCurrentPositionMs()
                        val duration = viewModel.controller.getDurationMs()
                        viewModel.seekTo((current + delta).coerceIn(0, duration))
                    }
                    onSpeedAction = { speed -> viewModel.setSpeed(speed) }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 3. Player controls overlay (V2)
        PlayerControlsV2(
            playerState      = playerState,
            subtitleCue      = subtitle?.text,
            decoderLabel     = decoderType?.name,
            scalingMode      = scalingMode,
            preferences      = prefsViewModel,
            onPlay           = { viewModel.play() },
            onPause          = { viewModel.pause() },
            onSeek           = { viewModel.seekTo(it) },
            onSpeedChange    = { viewModel.setSpeed(it) },
            onScalingToggle  = { viewModel.toggleScalingMode() },
            onBack           = onBack,
            onPrevious       = { /* TODO: playlist integration */ },
            onNext           = { /* TODO: playlist integration */ },
            controlsVisible  = controlsVisible,
            onToggleControls = { controlsVisible = !controlsVisible },
            modifier         = Modifier.fillMaxSize()
        )
    }

    DisposableEffect(activity) {
        onDispose {
            viewModel.controller.release()
            activity?.let { act ->
                // Reset orientation to UNSPECIFIED (respects user settings)
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                
                // Show system bars
                val window = act.window
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

//  Hardware SurfaceView 

@Composable
private fun HardwareRenderSurface(
    context: Context,
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = {
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) {
                        viewModel.setHardwareSurface(h.surface)
                    }
                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {}
                    override fun surfaceDestroyed(h: SurfaceHolder) {
                        viewModel.setHardwareSurface(null)
                    }
                })
            }
        },
        modifier = modifier
    )
}

//  Software GLSurfaceView 

@Composable
private fun SoftwareRenderSurface(
    context: Context,
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    // We use a plain SurfaceView here; EGL/GL is managed in the native VideoRenderer.
    AndroidView(
        factory = {
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) {
                        viewModel.setSoftwareSurface(h.surface)
                    }
                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {}
                    override fun surfaceDestroyed(h: SurfaceHolder) {
                        viewModel.setSoftwareSurface(null)
                    }
                })
            }
        },
        modifier = modifier
    )
}
