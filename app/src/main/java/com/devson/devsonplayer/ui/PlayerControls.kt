package com.devson.devsonplayer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devson.devsonplayer.player.PlayerController
import kotlinx.coroutines.delay

private val SPEED_OPTIONS = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

@Composable
fun PlayerControls(
    playerState: PlayerController.PlayerState,
    subtitleCue: String?,
    decoderLabel: String?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val positionMs = when (playerState) {
        is PlayerController.PlayerState.Playing -> playerState.positionMs
        is PlayerController.PlayerState.Paused  -> playerState.positionMs
        else -> 0L
    }
    val durationMs = when (playerState) {
        is PlayerController.PlayerState.Playing -> playerState.durationMs
        is PlayerController.PlayerState.Paused  -> playerState.durationMs
        else -> 0L
    }
    val isPlaying = playerState is PlayerController.PlayerState.Playing

    var controlsVisible by remember { mutableStateOf(true) }
    var seekDelta by remember { mutableLongStateOf(0L) }
    var speedMenuVisible by remember { mutableStateOf(false) }
    var currentSpeed by remember { mutableFloatStateOf(1.0f) }
    var seekBarValue by remember { mutableFloatStateOf(0f) }
    var isUserSeeking by remember { mutableStateOf(false) }

    // Sync slider with position
    LaunchedEffect(positionMs, durationMs) {
        if (!isUserSeeking && durationMs > 0) {
            seekBarValue = positionMs.toFloat() / durationMs.toFloat()
        }
    }

    // Auto-hide controls after 3s of inactivity
    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying) {
            delay(3_000)
            controlsVisible = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // ─── Gesture zones ──────────────────────────────────────────────
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        // Double-tap left → seek -10s, right → seek +10s
                        val delta = if (offset.x < size.width / 2) -10_000L else +10_000L
                        onSeek((positionMs + delta).coerceIn(0, durationMs))
                        seekDelta = delta
                    },
                    onTap = { controlsVisible = !controlsVisible }
                )
            }
            .pointerInput(Unit) {
                // Horizontal drag → seek
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val targetMs = (seekBarValue * durationMs).toLong()
                        onSeek(targetMs)
                        isUserSeeking = false
                    }
                ) { _, dragAmount ->
                    isUserSeeking = true
                    val seekFraction = dragAmount / size.width.toFloat() * 0.1f
                    seekBarValue = (seekBarValue + seekFraction).coerceIn(0f, 1f)
                }
            }
    ) {
        // ─── Subtitle overlay (always visible) ─────────────────────────────
        if (!subtitleCue.isNullOrBlank()) {
            SubtitleOverlay(
                text     = subtitleCue,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp)
            )
        }

        // ─── Controls overlay ───────────────────────────────────────────────
        AnimatedVisibility(
            visible = controlsVisible,
            enter   = fadeIn(),
            exit    = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {

                // Top bar gradient + back + decoder badge
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xCC000000), Color.Transparent)
                            )
                        )
                        .align(Alignment.TopStart)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint               = Color.White
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (decoderLabel != null) {
                            DecoderBadge(label = decoderLabel)
                        }
                    }
                }

                // Center playback buttons
                Row(
                    modifier            = Modifier.align(Alignment.Center),
                    verticalAlignment   = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(32.dp)
                ) {
                    IconButton(
                        onClick = { onSeek((positionMs - 10_000L).coerceAtLeast(0L)) },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(Icons.Default.FastRewind, "Rewind 10s",
                            tint = Color.White, modifier = Modifier.size(40.dp))
                    }

                    IconButton(
                        onClick = { if (isPlaying) onPause() else onPlay() },
                        modifier = Modifier.size(72.dp)
                    ) {
                        Icon(
                            imageVector        = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint               = Color.White,
                            modifier           = Modifier.size(56.dp)
                        )
                    }

                    IconButton(
                        onClick = { onSeek((positionMs + 10_000L).coerceAtMost(durationMs)) },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(Icons.Default.FastForward, "Forward 10s",
                            tint = Color.White, modifier = Modifier.size(40.dp))
                    }
                }

                // Bottom bar: seek slider + time + speed
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0xCC000000))
                            )
                        )
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Column {
                        // Seek Slider
                        Slider(
                            value = seekBarValue,
                            onValueChange = { v ->
                                isUserSeeking = true
                                seekBarValue = v
                            },
                            onValueChangeFinished = {
                                val targetMs = (seekBarValue * durationMs).toLong()
                                onSeek(targetMs)
                                isUserSeeking = false
                            },
                            colors = SliderDefaults.colors(
                                thumbColor        = Color.White,
                                activeTrackColor  = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Time row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text  = formatMs(positionMs),
                                color = Color.White,
                                fontSize = 13.sp
                            )

                            // Speed button
                            Box {
                                IconButton(onClick = { speedMenuVisible = true }) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Speed, "Speed",
                                            tint = Color.White, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("${currentSpeed}x", color = Color.White, fontSize = 12.sp)
                                    }
                                }
                                DropdownMenu(
                                    expanded = speedMenuVisible,
                                    onDismissRequest = { speedMenuVisible = false }
                                ) {
                                    SPEED_OPTIONS.forEach { spd ->
                                        DropdownMenuItem(
                                            text = { Text("${spd}x") },
                                            onClick = {
                                                currentSpeed = spd
                                                onSpeedChange(spd)
                                                speedMenuVisible = false
                                            }
                                        )
                                    }
                                }
                            }

                            Text(
                                text  = formatMs(durationMs),
                                color = Color.White,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Sub-composables ──────────────────────────────────────────────────────────

@Composable
private fun SubtitleOverlay(text: String, modifier: Modifier = Modifier) {
    Text(
        text      = text,
        color     = Color.White,
        fontSize  = 18.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier  = modifier
            .fillMaxWidth()
            .background(Color(0x88000000))
            .padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
private fun DecoderBadge(label: String) {
    val color = if (label == "HARDWARE") Color(0xFF4CAF50) else Color(0xFFFF9800)
    Text(
        text      = label,
        color     = Color.White,
        fontSize  = 11.sp,
        modifier  = Modifier
            .background(color, shape = MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

// ─── Formatter ────────────────────────────────────────────────────────────────

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val h  = totalSec / 3600
    val m  = (totalSec % 3600) / 60
    val s  = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
           else       "%02d:%02d".format(m, s)
}
