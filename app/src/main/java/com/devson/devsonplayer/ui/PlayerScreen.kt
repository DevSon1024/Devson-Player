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
import androidx.compose.ui.unit.dp

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.devson.devsonplayer.player.DecoderSelector
import com.devson.devsonplayer.player.PlayerController
import com.devson.devsonplayer.player.PlayerPreferencesViewModel
import com.devson.devsonplayer.player.PlayerViewModel
import com.devson.devsonplayer.player.SubtitleManager
import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.runtime.collectAsState
import com.devson.devsonplayer.MainViewModel
import com.devson.devsonplayer.ThemeMode
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize


/**
 * PlayerScreen
 *
 * Top-level composable for video playback.
 * Embeds SurfaceView (hardware) or SurfaceView (software) based on active decoder.
 * Hosts PlayerControlsV2 overlay.
 */
@Composable
fun PlayerScreen(
    playlistUris: List<Uri>,
    playlistTitles: List<String>,
    startIndex: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = viewModel(),
    prefsViewModel: PlayerPreferencesViewModel = viewModel(),
    mainViewModel: MainViewModel = viewModel()
) {
    val context  = LocalContext.current
    val view     = LocalView.current
    val activity = context as? Activity
    val themeMode by mainViewModel.themeMode.collectAsState()


    val playerState: PlayerController.PlayerState by viewModel.state.collectAsStateWithLifecycle()
    val decoderType: DecoderSelector.DecoderType? by viewModel.decoderType.collectAsStateWithLifecycle()
    val subtitle: SubtitleManager.SubtitleCue?    by viewModel.currentSubtitle.collectAsStateWithLifecycle()
    val videoInfo: DecoderSelector.VideoInfo?     by viewModel.videoInfo.collectAsStateWithLifecycle()
    val scalingMode: ScalingMode                  by viewModel.scalingMode.collectAsStateWithLifecycle()
    val audioTracks    by viewModel.audioTracks.collectAsStateWithLifecycle()
    val subtitleTracks by viewModel.subtitleTracks.collectAsStateWithLifecycle()
    val currentTitle   by viewModel.currentTitle.collectAsStateWithLifecycle()
    val autoPlay       by prefsViewModel.autoPlay.collectAsStateWithLifecycle()

    var controlsVisible by remember { mutableStateOf(true) }

    LaunchedEffect(playlistUris, playlistTitles, startIndex) {
        viewModel.setPlaylist(playlistUris, playlistTitles, startIndex)
    }

    LaunchedEffect(playerState, autoPlay) {
        if (playerState is PlayerController.PlayerState.Ended && autoPlay) {
            viewModel.playNext()
        }
    }

    LaunchedEffect(playerState) {
        if (playerState is PlayerController.PlayerState.Playing) {
            viewModel.onPositionUpdate((playerState as PlayerController.PlayerState.Playing).positionMs)
        }
    }

    // Sync decoder info to the Stats HUD
    LaunchedEffect(decoderType) {
        val label = when (decoderType) {
            DecoderSelector.DecoderType.HARDWARE -> "Hardware (MediaCodec)"
            DecoderSelector.DecoderType.SOFTWARE -> "Software (FFmpeg)"
            else -> "Selecting..."
        }
        prefsViewModel.updateDecoder(label)
    }

    // Auto-orientation based on video dimensions
    LaunchedEffect(videoInfo) {
        videoInfo?.let { info ->
            activity?.requestedOrientation = if (info.width > info.height)
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Always hide system bars (full immersive mode) so controls overlay isn't overlapping
    // or hidden behind transparent system navigation. They swipe transiently to show.
    LaunchedEffect(Unit) {
        activity?.window?.let { win ->
            val controller = WindowCompat.getInsetsController(win, view)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // 1. Video surface with scaling
        val videoModifier = when (scalingMode) {
            ScalingMode.FIT -> {
                if (videoInfo != null && videoInfo!!.width > 0 && videoInfo!!.height > 0)
                    Modifier.aspectRatio(videoInfo!!.width.toFloat() / videoInfo!!.height.toFloat())
                else Modifier.fillMaxSize()
            }
            ScalingMode.CROP     -> Modifier.fillMaxSize()
            ScalingMode.ORIGINAL -> {
                if (videoInfo != null)
                    Modifier.aspectRatio(videoInfo!!.width.toFloat() / videoInfo!!.height.toFloat())
                else Modifier.fillMaxSize()
            }
            else -> Modifier.fillMaxSize()
        }

        Box(modifier = videoModifier, contentAlignment = Alignment.Center) {
            // Replace the 'when (decoderType)' block with a single unified surface
            UnifiedRenderSurface(context, viewModel, Modifier.fillMaxSize())
        }

        // 2. Gesture overlay
        AndroidView(
            factory = { ctx ->
                com.devson.devsonplayer.ui.gestures.GestureOverlayView(ctx).apply {
                    onSingleTapAction = { controlsVisible = !controlsVisible }
                    onToggleAction    = {
                        if (playerState is PlayerController.PlayerState.Playing) viewModel.pause()
                        else viewModel.play()
                    }
                    onSeekAction = { delta ->
                        val cur = viewModel.controller.getCurrentPositionMs()
                        val dur = viewModel.controller.getDurationMs()
                        viewModel.seekTo((cur + delta).coerceIn(0, dur))
                    }
                    onSpeedAction = { speed -> viewModel.setSpeed(speed) }
                }
            },
            update = { view ->
                // Ensures the gesture view always uses the user's chosen seek duration
                view.seekDurationMs = prefsViewModel.effectiveSeekMs()
            },
            modifier = Modifier.fillMaxSize()
        )

        // 3. Controls overlay (V2)
        PlayerControlsV2(
            playerState      = playerState,
            subtitleCue      = subtitle?.text,
            decoderLabel     = decoderType?.name,
            videoTitle       = currentTitle,
            scalingMode      = scalingMode,
            preferences      = prefsViewModel,
            audioTracks      = audioTracks,
            subtitleTracks   = subtitleTracks,
            onPlay           = { viewModel.play() },
            onPause          = { viewModel.pause() },
            onSeek           = { viewModel.seekTo(it) },
            onSpeedChange    = { viewModel.setSpeed(it) },
            onScalingToggle  = { viewModel.toggleScalingMode() },
            onBack           = onBack,
            onPrevious       = { viewModel.playPrevious() },
            onNext           = { viewModel.playNext() },
            onAudioTrackSelect = { viewModel.selectAudioTrack(it) },
            controlsVisible  = controlsVisible,
            onToggleControls = { controlsVisible = !controlsVisible },
            modifier         = Modifier.fillMaxSize()
        )
    }
    DisposableEffect(activity) {
        onDispose {
            viewModel.controller.release()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { win ->
                WindowCompat.getInsetsController(win, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

@Composable
private fun UnifiedRenderSurface(
    context: Context,
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = {
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) {
                        // Pass the same surface to both hardware and software paths
                        viewModel.setHardwareSurface(h.surface)
                        viewModel.setSoftwareSurface(h.surface)
                    }
                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {}
                    override fun surfaceDestroyed(h: SurfaceHolder) {
                        viewModel.setHardwareSurface(null)
                        viewModel.setSoftwareSurface(null)
                    }
                })
            }
        },
        modifier = modifier
    )
}
