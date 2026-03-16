package com.devson.devsonplayer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.text.style.TextOverflow
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

//  Root composable

@Composable
fun PlayerControlsV2(
    playerState: PlayerController.PlayerState,
    subtitleCue: String?,
    decoderLabel: String?,
    videoTitle: String,
    scalingMode: ScalingMode,
    preferences: PlayerPreferencesViewModel,
    audioTracks: List<AudioTrack>,
    subtitleTracks: List<SubtitleTrack>,
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
    val isLocked        by preferences.isScreenLocked.collectAsStateWithLifecycle()
    val iconSize        by preferences.iconSize.collectAsStateWithLifecycle()
    val showSeekButtons by preferences.showSeekButtons.collectAsStateWithLifecycle()
    val seekBarStyle    by preferences.seekBarStyle.collectAsStateWithLifecycle()
    val statsVisible    by preferences.statsVisible.collectAsStateWithLifecycle()
    val deviceStats     by preferences.deviceStats.collectAsStateWithLifecycle()
    val selectedAudio   by preferences.selectedAudioTrackIndex.collectAsStateWithLifecycle()
    val selectedSubs    by preferences.selectedSubtitleIndices.collectAsStateWithLifecycle()

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

    // Auto-hide controls after 3 seconds
    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying && !isLocked) {
            delay(3_000)
            onToggleControls()
        }
    }

    var showAudioSheet    by remember { mutableStateOf(false) }
    var showSubtitleSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {

        //  Subtitle overlay (always visible, above everything) 
        if (!subtitleCue.isNullOrBlank()) {
            Text(
                text = subtitleCue,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 96.dp)
                    .background(Color(0x88000000))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        //  Lock mode: only show lock button 
        if (isLocked) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
            ) {
                IconButton(
                    onClick = { preferences.toggleScreenLock() },
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0x88000000), CircleShape)
                ) {
                    Icon(
                        Icons.Default.Lock,
                        "Unlock",
                        tint = Color(0xFFFFD700),
                        modifier = Modifier.size(iconDp)
                    )
                }
            }
            return@Box
        }

        //  Corner Device Stats HUD (slides in from top-end corner) 
        // Visible only when toggled AND controls are visible
        AnimatedVisibility(
            visible = statsVisible && controlsVisible,
            enter = slideInHorizontally { it } + fadeIn(),
            exit  = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 64.dp, end = 16.dp)   // sits just below the TopRow
        ) {
            DeviceStatsHud(stats = deviceStats)
        }

        //  Full controls overlay 
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit  = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f  to Color(0xBB000000),
                            0.30f to Color.Transparent,
                            0.70f to Color.Transparent,
                            1.0f  to Color(0xBB000000)
                        )
                    )
            ) {
                // Top row
                TopRow(
                    videoTitle        = videoTitle,
                    iconDp            = iconDp,
                    statsActive       = statsVisible,
                    onBack            = onBack,
                    onToggleStats     = { preferences.toggleStats() },
                    onShowAudioSheet  = { showAudioSheet = true },
                    onShowSubSheet    = { showSubtitleSheet = true },
                    onShowSettings    = { showSettingsSheet = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .padding(top = 16.dp, start = 8.dp, end = 8.dp)
                )

                // Seekbar + time + bottom row (pinned to bottom with nav insets)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomStart)
                        .padding(bottom = 24.dp, start = 12.dp, end = 12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatMs(positionMs), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(formatMs(durationMs),  color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    }

                    PlaybackSeekBar(
                        value               = seekBarValue,
                        style               = seekBarStyle,
                        onValueChange       = { v -> isUserSeeking = true; seekBarValue = v },
                        onValueChangeFinished = {
                            onSeek((seekBarValue * durationMs).toLong())
                            isUserSeeking = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(2.dp))

                    BottomRow(
                        isPlaying         = isPlaying,
                        showSeekButtons   = showSeekButtons,
                        seekLabel         = let { ms ->
                            val s = preferences.effectiveSeekMs() / 1000L
                            "${s}s"
                        },
                        scalingMode       = scalingMode,
                        iconDp            = iconDp,
                        onLock            = { preferences.toggleScreenLock() },
                        onPrevious        = onPrevious,
                        onRewind          = { onSeek((positionMs - preferences.effectiveSeekMs()).coerceAtLeast(0L)) },
                        onPlayPause       = { if (isPlaying) onPause() else onPlay() },
                        onFastForward     = { onSeek((positionMs + preferences.effectiveSeekMs()).coerceAtMost(durationMs)) },
                        onNext            = onNext,
                        onScalingToggle   = onScalingToggle
                    )
                }
            }
        }

        //  Bottom Sheets 
        if (showAudioSheet) {
            AudioTracksBottomSheet(
                tracks        = audioTracks,
                selectedIndex = selectedAudio,
                onSelect      = { preferences.selectAudioTrack(it); showAudioSheet = false },
                onDismiss     = { showAudioSheet = false }
            )
        }
        if (showSubtitleSheet) {
            SubtitlesBottomSheet(
                tracks          = subtitleTracks,
                selectedIndices = selectedSubs,
                onToggle        = { preferences.toggleSubtitleTrack(it) },
                onDismiss       = { showSubtitleSheet = false }
            )
        }
        if (showSettingsSheet) {
            SettingsBottomSheet(
                preferences = preferences,
                onDismiss   = { showSettingsSheet = false }
            )
        }
    }
}

// 
//  Top Row
// 

@Composable
private fun TopRow(
    videoTitle: String,
    iconDp: Dp,
    statsActive: Boolean,
    onBack: () -> Unit,
    onToggleStats: () -> Unit,
    onShowAudioSheet: () -> Unit,
    onShowSubSheet: () -> Unit,
    onShowSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(iconDp))
        }

        // Video title
        Text(
            text = videoTitle,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
        )

        // Stats toggle (Info icon)
        IconButton(onClick = onToggleStats) {
            Icon(
                Icons.Default.Info,
                "Device Stats",
                tint = if (statsActive) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier.size(iconDp)
            )
        }
        // Audio tracks
        IconButton(onClick = onShowAudioSheet) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, "Audio Tracks", tint = Color.White, modifier = Modifier.size(iconDp))
        }
        // Subtitles
        IconButton(onClick = onShowSubSheet) {
            Icon(Icons.Default.Subtitles, "Subtitles", tint = Color.White, modifier = Modifier.size(iconDp))
        }
        // Settings
        IconButton(onClick = onShowSettings) {
            Icon(Icons.Default.Settings, "Settings", tint = Color.White, modifier = Modifier.size(iconDp))
        }
    }
}

// 
//  Device Stats HUD  (corner overlay, not inside TopRow)
// 

@Composable
private fun DeviceStatsHud(
    stats: DeviceStats,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(
                color = Color(0xCC000000),
                shape = RoundedCornerShape(bottomStart = 10.dp, topStart = 10.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        HudRow("⚙  Decoder", stats.activeDecoder,   Color(0xFF4FC3F7))
        HudRow("CPU",        "${stats.cpuPercent}%",  Color.White)
        HudRow("BAT",        "${stats.batteryPercent}%",
            if (stats.batteryPercent < 20) Color(0xFFFF5252) else Color(0xFF69F0AE))
        HudRow("FPS",        "${stats.fps}",          Color(0xFFFFD740))
        HudRow("TEMP",       "${"%.1f".format(stats.temperatureCelsius)}°C",
            if (stats.temperatureCelsius > 45f) Color(0xFFFF5252) else Color.White)
    }
}

@Composable
private fun HudRow(label: String, value: String, valueColor: Color) {
    Row {
        Text(
            "$label  ",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            value,
            color = valueColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

// 
//  Playback Seekbar
// 

@Composable
private fun PlaybackSeekBar(
    value: Float,
    style: SeekBarStyle,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        colors = when (style) {
            SeekBarStyle.DEFAULT -> SliderDefaults.colors(
                thumbColor         = Color.White,
                activeTrackColor   = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
            )
            SeekBarStyle.FLAT -> SliderDefaults.colors(
                thumbColor         = Color.Transparent,
                activeTrackColor   = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                activeTickColor    = Color.Transparent,
                inactiveTickColor  = Color.Transparent
            )
        },
        modifier = modifier
    )
}

// 
//  Bottom Row
// 

@Composable
private fun BottomRow(
    isPlaying: Boolean,
    showSeekButtons: Boolean,
    seekLabel: String,
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
        IconButton(onClick = onLock, modifier = Modifier.size(iconDp + 20.dp)) {
            Icon(Icons.Default.LockOpen, "Lock", tint = Color.White, modifier = Modifier.size(iconDp))
        }

        // Previous
        IconButton(onClick = onPrevious, modifier = Modifier.size(iconDp + 20.dp)) {
            Icon(Icons.Default.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(iconDp))
        }

        // Rewind (conditional)
        if (showSeekButtons) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable(onClick = onRewind)
            ) {
                Icon(Icons.Default.FastRewind, null, tint = Color.White, modifier = Modifier.size(iconDp))
                Text(seekLabel, color = Color.White.copy(alpha = 0.85f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Play / Pause (larger, highlighted)
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

        // Fast-Forward (conditional)
        if (showSeekButtons) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable(onClick = onFastForward)
            ) {
                Icon(Icons.Default.FastForward, null, tint = Color.White, modifier = Modifier.size(iconDp))
                Text(seekLabel, color = Color.White.copy(alpha = 0.85f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Next
        IconButton(onClick = onNext, modifier = Modifier.size(iconDp + 20.dp)) {
            Icon(Icons.Default.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(iconDp))
        }

        // Scale mode
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable(onClick = onScalingToggle)
        ) {
            Icon(Icons.Default.AspectRatio, "Scale Mode", tint = Color.White, modifier = Modifier.size(iconDp))
            Text(scalingMode.label, color = Color.White.copy(alpha = 0.8f), fontSize = 8.sp)
        }
    }
}

// 
//  Audio Tracks Bottom Sheet  — uses app MaterialTheme (no hardcoded colors)
// 

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
        sheetState = sheetState
        // ← no containerColor / contentColor override → uses MaterialTheme defaults
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SheetTitle("Audio Tracks")
            HorizontalDivider()

            if (tracks.isEmpty()) {
                // No tracks detected — show "Default"
                SheetDefaultRow(selected = true, label = "Default")
            } else {
                tracks.forEach { track ->
                    SheetRadioRow(
                        label    = track.label,
                        subLabel = track.language,
                        selected = track.index == selectedIndex,
                        onClick  = { onSelect(track.index) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// 
//  Subtitles Bottom Sheet
// 

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
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SheetTitle("Subtitles")
            HorizontalDivider()

            if (tracks.isEmpty()) {
                SheetDefaultRow(selected = false, label = "No subtitle tracks available")
            } else {
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
                                    checkedColor   = MaterialTheme.colorScheme.primary,
                                    checkmarkColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                track.label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (track.index in selectedIndices) FontWeight.SemiBold else FontWeight.Normal
                            )
                            if (track.label != track.language) {
                                Text(track.language, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// 
//  Settings Bottom Sheet
// 

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsBottomSheet(
    preferences: PlayerPreferencesViewModel,
    onDismiss: () -> Unit
) {
    val seekDuration  by preferences.seekDuration.collectAsStateWithLifecycle()
    val customSeekMs  by preferences.customSeekMs.collectAsStateWithLifecycle()
    val seekBarStyle  by preferences.seekBarStyle.collectAsStateWithLifecycle()
    val iconSize      by preferences.iconSize.collectAsStateWithLifecycle()
    val autoPlay      by preferences.autoPlay.collectAsStateWithLifecycle()
    val showSeekBtns  by preferences.showSeekButtons.collectAsStateWithLifecycle()

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SheetTitle("Settings")
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            //  Seek Duration 
            SectionLabel("Seek Duration")
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SeekDuration.entries.forEach { opt ->
                    val sel = seekDuration == opt
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (sel) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .border(
                                1.dp,
                                if (sel) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { preferences.setSeekDuration(opt) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            opt.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            if (seekDuration == SeekDuration.CUSTOM) {
                Spacer(Modifier.height(8.dp))
                var customText by remember { mutableStateOf((customSeekMs / 1000L).toString()) }
                OutlinedTextField(
                    value = customText,
                    onValueChange = { v ->
                        customText = v
                        v.toLongOrNull()?.let { preferences.setCustomSeekMs(it * 1000L) }
                    },
                    label = { Text("Seek (seconds)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SheetDivider()

            //  Seek Bar Style 
            SectionLabel("Seek Bar Style")
            SeekBarStyle.entries.forEach { opt ->
                RadioRow(opt.label, seekBarStyle == opt) { preferences.setSeekBarStyle(opt) }
            }

            SheetDivider()

            //  Icon Size 
            SectionLabel("Control Icon Size")
            IconSize.entries.forEach { opt ->
                RadioRow(opt.label, iconSize == opt) { preferences.setIconSize(opt) }
            }

            SheetDivider()

            //  Auto Play 
            SettingsSwitchRow("Auto Play", "Automatically play next video", autoPlay) {
                preferences.setAutoPlay(it)
            }

            SheetDivider()

            //  Show Seek Buttons 
            SettingsSwitchRow("Show Seek Buttons", "Forward/backward seek buttons", showSeekBtns) {
                preferences.setShowSeekButtons(it)
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// 
//  Shared sheet helper composables (theme-aware)
// 

@Composable
private fun SheetTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun SheetDivider() {
    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun SheetDefaultRow(selected: Boolean, label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            RadioButton(
                selected = selected,
                onClick  = null,
                colors   = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SheetRadioRow(
    label: String,
    subLabel: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            RadioButton(
                selected = selected,
                onClick  = onClick,
                colors   = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            if (label != subLabel) {
                Text(subLabel, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
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
                onClick  = onClick,
                colors   = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun SettingsSwitchRow(
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
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// 
//  Formatter
// 

internal fun formatMs(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}
