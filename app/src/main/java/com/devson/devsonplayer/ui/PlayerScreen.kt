package com.devson.devsonplayer.ui

import android.content.Context
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.devson.devsonplayer.player.DecoderSelector
import com.devson.devsonplayer.player.PlayerController
import com.devson.devsonplayer.player.PlayerViewModel

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
    viewModel: PlayerViewModel = viewModel()
) {
    val context = LocalContext.current
    val playerState by viewModel.state.collectAsStateWithLifecycle()
    val decoderType by viewModel.decoderType.collectAsStateWithLifecycle()
    val subtitle by viewModel.currentSubtitle.collectAsStateWithLifecycle()

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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Video surface — hardware or software
        when (decoderType) {
            DecoderSelector.DecoderType.SOFTWARE -> {
                SoftwareRenderSurface(
                    context = context,
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
            else -> {
                // Default: hardware SurfaceView (also covers null/initial state)
                HardwareRenderSurface(
                    context = context,
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Player controls overlay
        PlayerControls(
            playerState   = playerState,
            subtitleCue   = subtitle?.text,
            decoderLabel  = decoderType?.name,
            onPlay        = { viewModel.play() },
            onPause       = { viewModel.pause() },
            onSeek        = { viewModel.seekTo(it) },
            onSpeedChange = { viewModel.setSpeed(it) },
            onBack        = onBack,
            modifier      = Modifier.fillMaxSize()
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.controller.release()
        }
    }
}

// ─── Hardware SurfaceView ─────────────────────────────────────────────────────

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

// ─── Software GLSurfaceView ────────────────────────────────────────────────────

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
