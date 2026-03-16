package com.devson.devsonplayer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devson.devsonplayer.player.AudioTrack
import com.devson.devsonplayer.player.DeviceStats
import com.devson.devsonplayer.player.IconSize
import com.devson.devsonplayer.player.PlayerController
import com.devson.devsonplayer.player.PlayerPreferencesViewModel
import com.devson.devsonplayer.player.SeekBarStyle
import com.devson.devsonplayer.player.SeekDuration
import com.devson.devsonplayer.player.SubtitleTrack
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────────────────────────────────────
//  Root composable
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun PlayerControlsV2(
    playerState: PlayerController.PlayerState,
    subtitleCue: String?,
    decoderLabel: String?,
    scalingMode: ScalingMode,
    preferences: PlayerPreferencesViewModel,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onScalingToggle: () -> Unit,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
    modifier: Modifier = Modifier
) {
    // ── Collect state ─────────────────────────────────────────────────────────
    val isLocked by preferences.isScreenLocked.collectAsStateWithLifecycle()
    val iconSize by preferences.iconSize.collectAsStateWithLifecycle()
    val showSeekButtons by preferences.showSeekButtons.collectAsStateWithLifecycle()
    val seekBarStyle by preferences.seekBarStyle.collectAsStateWithLifecycle()
    val deviceStats by preferences.deviceStats.collectAsStateWithLifecycle()
    val selectedAudioIdx by preferences.selectedAudioTrackIndex.collectAsStateWithLifecycle()
    val selectedSubtitles by preferences.selectedSubtitleIndices.collectAsStateWithLifecycle()

    val iconDp: Dp = iconSize.dp.dp

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

    var seekBarValue by remember { mutableFloatStateOf(0f) }
    var isUserSeeking by remember { mutableStateOf(false) }

    LaunchedEffect(positionMs, durationMs) {
        if (!isUserSeeking && durationMs > 0) {
            seekBarValue = positionMs.toFloat() / durationMs.toFloat()
        }
    }

    // Auto-hide controls after 3s
    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying && !isLocked) {
            delay(3_000)
            onToggleControls()
        }
    }

    // Bottom sheet display flags
    var showAudioSheet by remember { mutableStateOf(false) }
    var showSubtitleSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {

        // ── Subtitle overlay (always visible) ─────────────────────────────────
        if (!subtitleCue.isNullOrBlank()) {
            Text(
                text = subtitleCue,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 96.dp)
                    .background(Color(0x88000000))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        // ── Lock mode: show only the lock button ──────────────────────────────
        if (isLocked) {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                IconButton(
                    onClick = { preferences.toggleScreenLock() },
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .size(48.dp)
                        .background(Color(0x88000000), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Unlock",
                        tint = Color(0xFFFFD700),
                        modifier = Modifier.size(iconDp)
                    )
                }
            }
            return@Box
        }

        // ── Full controls overlay ─────────────────────────────────────────────
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color(0xBB000000),
                            0.35f to Color.Transparent,
                            0.65f to Color.Transparent,
                            1.0f to Color(0xBB000000)
                        )
                    )
            ) {
                // Top row
                TopRow(
                    decoderLabel = decoderLabel,
                    deviceStats = deviceStats,
                    iconDp = iconDp,
                    onBack = onBack,
                    onShowAudioSheet = { showAudioSheet = true },
                    onShowSubtitleSheet = { showSubtitleSheet = true },
                    onShowSettingsSheet = { showSettingsSheet = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .windowInsetsPadding(WindowInsets.statusBars)
                )

                // Seekbar + time + bottom row (pinned to bottom)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomStart)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    // Time row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            formatMs(positionMs),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            formatMs(durationMs),
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp
                        )
                    }

                    // Seek bar
                    PlaybackSeekBar(
                        value = seekBarValue,
                        style = seekBarStyle,
                        onValueChange = { v ->
                            isUserSeeking = true
                            seekBarValue = v
                        },
                        onValueChangeFinished = {
                            onSeek((seekBarValue * durationMs).toLong())
                            isUserSeeking = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(2.dp))

                    // Bottom row controls
                    BottomRow(
                        isPlaying = isPlaying,
                        isLocked = false,
                        showSeekButtons = showSeekButtons,
                        seekDurationLabel = preferences.effectiveSeekMs().let { ms ->
                            if (ms % 1000L == 0L) "${ms / 1000}s" else "${ms / 1000}s"
                        },
                        scalingMode = scalingMode,
                        iconDp = iconDp,
                        onLock = { preferences.toggleScreenLock() },
                        onPrevious = onPrevious,
                        onRewind = {
                            onSeek((positionMs - preferences.effectiveSeekMs()).coerceAtLeast(0L))
                        },
                        onPlayPause = { if (isPlaying) onPause() else onPlay() },
                        onFastForward = {
                            onSeek((positionMs + preferences.effectiveSeekMs()).coerceAtMost(durationMs))
                        },
                        onNext = onNext,
                        onScalingToggle = onScalingToggle
                    )
                }
            }
        }

        // ── Bottom Sheets ─────────────────────────────────────────────────────
        if (showAudioSheet) {
            AudioTracksBottomSheet(
                tracks = preferences.audioTracks,
                selectedIndex = selectedAudioIdx,
                onSelect = { idx ->
                    preferences.selectAudioTrack(idx)
                    showAudioSheet = false
                },
                onDismiss = { showAudioSheet = false }
            )
        }

        if (showSubtitleSheet) {
            SubtitlesBottomSheet(
                tracks = preferences.subtitleTracks,
                selectedIndices = selectedSubtitles,
                onToggle = { preferences.toggleSubtitleTrack(it) },
                onDismiss = { showSubtitleSheet = false }
            )
        }

        if (showSettingsSheet) {
            SettingsBottomSheet(
                preferences = preferences,
                onDismiss = { showSettingsSheet = false }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Top Row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TopRow(
    decoderLabel: String?,
    deviceStats: DeviceStats,
    iconDp: Dp,
    onBack: () -> Unit,
    onShowAudioSheet: () -> Unit,
    onShowSubtitleSheet: () -> Unit,
    onShowSettingsSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(iconDp)
            )
        }

        // Device stats HUD
        DeviceStatsHud(
            stats = deviceStats,
            modifier = Modifier.padding(start = 4.dp)
        )

        Spacer(Modifier.weight(1f))

        // Audio tracks
        IconButton(onClick = onShowAudioSheet) {
            Icon(
                imageVector = Icons.Default.VolumeUp,
                contentDescription = "Audio Tracks",
                tint = Color.White,
                modifier = Modifier.size(iconDp)
            )
        }

        // Subtitles
        IconButton(onClick = onShowSubtitleSheet) {
            Icon(
                imageVector = Icons.Default.Subtitles,
                contentDescription = "Subtitles",
                tint = Color.White,
                modifier = Modifier.size(iconDp)
            )
        }

        // Settings
        IconButton(onClick = onShowSettingsSheet) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Settings",
                tint = Color.White,
                modifier = Modifier.size(iconDp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Device Stats HUD
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DeviceStatsHud(
    stats: DeviceStats,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color(0x99000000), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudChip(label = "⚙", value = stats.activeDecoder, valueColor = Color(0xFF4FC3F7))
                HudChip(label = "CPU", value = "${stats.cpuPercent}%")
                HudChip(label = "BAT", value = "${stats.batteryPercent}%",
                    valueColor = if (stats.batteryPercent < 20) Color(0xFFFF5252) else Color(0xFF69F0AE))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudChip(label = "FPS", value = "${stats.fps}", valueColor = Color(0xFFFFD740))
                HudChip(label = "TEMP", value = "${"%.1f".format(stats.temperatureCelsius)}°C",
                    valueColor = if (stats.temperatureCelsius > 45f) Color(0xFFFF5252) else Color.White)
            }
        }
    }
}

@Composable
private fun HudChip(
    label: String,
    value: String,
    valueColor: Color = Color.White
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$label ",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Playback Seek Bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PlaybackSeekBar(
    value: Float,
    style: SeekBarStyle,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (style) {
        SeekBarStyle.DEFAULT -> {
            Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                ),
                modifier = modifier
            )
        }
        SeekBarStyle.FLAT -> {
            Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                colors = SliderDefaults.colors(
                    thumbColor = Color.Transparent,   // invisible thumb → flat style
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent
                ),
                modifier = modifier
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Bottom Row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BottomRow(
    isPlaying: Boolean,
    isLocked: Boolean,
    showSeekButtons: Boolean,
    seekDurationLabel: String,
    scalingMode: ScalingMode,
    iconDp: Dp,
    onLock: () -> Unit,
    onPrevious: () -> Unit,
    onRewind: () -> Unit,
    onPlayPause: () -> Unit,
    onFastForward: () -> Unit,
    onNext: () -> Unit,
    onScalingToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Lock
        ControlIconButton(
            onClick = onLock,
            contentDescription = "Lock",
            iconDp = iconDp
        ) {
            Icon(
                imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                contentDescription = null,
                tint = if (isLocked) Color(0xFFFFD700) else Color.White,
                modifier = Modifier.size(iconDp)
            )
        }

        // Previous
        ControlIconButton(
            onClick = onPrevious,
            contentDescription = "Previous",
            iconDp = iconDp
        ) {
            Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(iconDp))
        }

        // Rewind (conditional)
        if (showSeekButtons) {
            SeekButton(
                onClick = onRewind,
                icon = { Icon(Icons.Default.FastRewind, null, tint = Color.White, modifier = Modifier.size(iconDp)) },
                label = seekDurationLabel
            )
        }

        // Play / Pause (larger)
        IconButton(
            onClick = onPlayPause,
            modifier = Modifier
                .size(64.dp)
                .background(Color.White.copy(alpha = 0.15f), CircleShape)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size((iconDp.value + 12).dp)
            )
        }

        // Fast Forward (conditional)
        if (showSeekButtons) {
            SeekButton(
                onClick = onFastForward,
                icon = { Icon(Icons.Default.FastForward, null, tint = Color.White, modifier = Modifier.size(iconDp)) },
                label = seekDurationLabel
            )
        }

        // Next
        ControlIconButton(
            onClick = onNext,
            contentDescription = "Next",
            iconDp = iconDp
        ) {
            Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(iconDp))
        }

        // Scale Mode
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable(onClick = onScalingToggle)
        ) {
            Icon(
                imageVector = Icons.Default.AspectRatio,
                contentDescription = "Scale Mode",
                tint = Color.White,
                modifier = Modifier.size(iconDp)
            )
            Text(
                text = scalingMode.label,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 8.sp
            )
        }
    }
}

@Composable
private fun ControlIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    iconDp: Dp,
    content: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(iconDp + 20.dp)
    ) {
        content()
    }
}

@Composable
private fun SeekButton(
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    label: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        icon()
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Audio Tracks Bottom Sheet
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioTracksBottomSheet(
    tracks: List<AudioTrack>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1C1C1E),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            BottomSheetTitle("Audio Tracks")
            Divider(color = Color.White.copy(alpha = 0.1f))
            tracks.forEach { track ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(track.index) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                        RadioButton(
                            selected = track.index == selectedIndex,
                            onClick = { onSelect(track.index) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.primary,
                                unselectedColor = Color.White.copy(alpha = 0.5f)
                            )
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = track.label,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = if (track.index == selectedIndex) FontWeight.SemiBold else FontWeight.Normal
                        )
                        Text(
                            text = track.language,
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 12.sp
                        )
                    }
                }
                Divider(
                    color = Color.White.copy(alpha = 0.05f),
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Subtitles Bottom Sheet
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubtitlesBottomSheet(
    tracks: List<SubtitleTrack>,
    selectedIndices: Set<Int>,
    onToggle: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1C1C1E),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            BottomSheetTitle("Subtitles")
            Divider(color = Color.White.copy(alpha = 0.1f))
            tracks.forEach { track ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(track.index) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                        Checkbox(
                            checked = track.index in selectedIndices,
                            onCheckedChange = { onToggle(track.index) },
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary,
                                uncheckedColor = Color.White.copy(alpha = 0.5f),
                                checkmarkColor = Color.White
                            )
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = track.label,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = if (track.index in selectedIndices) FontWeight.SemiBold else FontWeight.Normal
                        )
                        Text(
                            text = track.language,
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 12.sp
                        )
                    }
                }
                Divider(
                    color = Color.White.copy(alpha = 0.05f),
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Settings Bottom Sheet
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsBottomSheet(
    preferences: PlayerPreferencesViewModel,
    onDismiss: () -> Unit
) {
    val seekDuration by preferences.seekDuration.collectAsStateWithLifecycle()
    val customSeekMs by preferences.customSeekMs.collectAsStateWithLifecycle()
    val seekBarStyle by preferences.seekBarStyle.collectAsStateWithLifecycle()
    val iconSize by preferences.iconSize.collectAsStateWithLifecycle()
    val autoPlay by preferences.autoPlay.collectAsStateWithLifecycle()
    val showSeekButtons by preferences.showSeekButtons.collectAsStateWithLifecycle()

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1C1C1E),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            BottomSheetTitle("Settings")
            Divider(color = Color.White.copy(alpha = 0.1f))
            Spacer(Modifier.height(12.dp))

            // ── Seek Duration ─────────────────────────────────────────────────
            SettingsSectionLabel("Seek Duration")
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SeekDuration.entries.forEach { option ->
                    val isSelected = seekDuration == option
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else Color.White.copy(alpha = 0.1f)
                            )
                            .border(
                                1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else Color.White.copy(alpha = 0.2f),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { preferences.setSeekDuration(option) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = option.label,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // Custom seek input
            if (seekDuration == SeekDuration.CUSTOM) {
                Spacer(Modifier.height(8.dp))
                var customText by remember { mutableStateOf((customSeekMs / 1000L).toString()) }
                OutlinedTextField(
                    value = customText,
                    onValueChange = { v ->
                        customText = v
                        v.toLongOrNull()?.let { preferences.setCustomSeekMs(it * 1000L) }
                    },
                    label = { Text("Custom seek (seconds)", color = Color.White.copy(alpha = 0.6f)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(16.dp))
            Divider(color = Color.White.copy(alpha = 0.08f))
            Spacer(Modifier.height(12.dp))

            // ── Seek Bar Style ────────────────────────────────────────────────
            SettingsSectionLabel("Seek Bar Style")
            SeekBarStyle.entries.forEach { option ->
                RadioOptionRow(
                    label = option.label,
                    selected = seekBarStyle == option,
                    onClick = { preferences.setSeekBarStyle(option) }
                )
            }

            Spacer(Modifier.height(12.dp))
            Divider(color = Color.White.copy(alpha = 0.08f))
            Spacer(Modifier.height(12.dp))

            // ── Icon Size ─────────────────────────────────────────────────────
            SettingsSectionLabel("Control Icon Size")
            IconSize.entries.forEach { option ->
                RadioOptionRow(
                    label = option.label,
                    selected = iconSize == option,
                    onClick = { preferences.setIconSize(option) }
                )
            }

            Spacer(Modifier.height(12.dp))
            Divider(color = Color.White.copy(alpha = 0.08f))
            Spacer(Modifier.height(12.dp))

            // ── Auto Play ─────────────────────────────────────────────────────
            SwitchRow(
                label = "Auto Play",
                subtitle = "Automatically play next video",
                checked = autoPlay,
                onCheckedChange = { preferences.setAutoPlay(it) }
            )

            Spacer(Modifier.height(8.dp))
            Divider(color = Color.White.copy(alpha = 0.08f))
            Spacer(Modifier.height(8.dp))

            // ── Show Seek Buttons ─────────────────────────────────────────────
            SwitchRow(
                label = "Show Seek Buttons",
                subtitle = "Show forward/backward seek buttons",
                checked = showSeekButtons,
                onCheckedChange = { preferences.setShowSeekButtons(it) }
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Small helper composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BottomSheetTitle(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    )
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.5f),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun RadioOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = MaterialTheme.colorScheme.primary,
                    unselectedColor = Color.White.copy(alpha = 0.4f)
                )
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.2f)
            )
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Formatting utility (kept here for self-containment)
// ─────────────────────────────────────────────────────────────────────────────

internal fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%02d:%02d".format(m, s)
}
