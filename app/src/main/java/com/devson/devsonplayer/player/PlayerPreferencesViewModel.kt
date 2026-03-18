package com.devson.devsonplayer.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random
import android.content.Context
import androidx.media3.common.Tracks

enum class SeekDuration(val label: String, val ms: Long) {
    SEC_5("5s",   5_000L),
    SEC_10("10s", 10_000L),
    SEC_15("15s", 15_000L),
    SEC_20("20s", 20_000L),
    CUSTOM("Custom", 10_000L)
}

enum class SeekBarStyle(val label: String) {
    DEFAULT("Default"),
    FLAT("Flat")
}

enum class IconSize(val label: String, val dp: Int) {
    SMALL("Small", 20),
    MEDIUM("Medium", 28),
    LARGE("Large", 36)
}

//  Data models (shared between ViewModel and UI)

data class AudioTrack(
    val index: Int,
    val language: String,
    val label: String,
    val group: Tracks.Group,
    val trackIndex: Int
)
data class SubtitleTrack(val index: Int, val language: String, val label: String = language)

data class DeviceStats(
    val activeDecoder: String,
    val cpuPercent: Int,
    val batteryPercent: Int,
    val fps: Int,
    val temperatureCelsius: Float
)

//  ViewModel — preferences + device stats only (no mock track data)
class PlayerPreferencesViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)

    // Load initial values from SharedPreferences, falling back to defaults if not found
    private val _seekDuration = MutableStateFlow(
        SeekDuration.valueOf(prefs.getString("seekDuration", SeekDuration.SEC_10.name) ?: SeekDuration.SEC_10.name)
    )
    val seekDuration: StateFlow<SeekDuration> = _seekDuration.asStateFlow()

    private val _customSeekMs = MutableStateFlow(prefs.getLong("customSeekMs", 10_000L))
    val customSeekMs: StateFlow<Long> = _customSeekMs.asStateFlow()

    fun effectiveSeekMs(): Long =
        if (_seekDuration.value == SeekDuration.CUSTOM) _customSeekMs.value
        else _seekDuration.value.ms

    private val _seekBarStyle = MutableStateFlow(
        SeekBarStyle.valueOf(prefs.getString("seekBarStyle", SeekBarStyle.DEFAULT.name) ?: SeekBarStyle.DEFAULT.name)
    )
    val seekBarStyle: StateFlow<SeekBarStyle> = _seekBarStyle.asStateFlow()

    private val _iconSize = MutableStateFlow(
        IconSize.valueOf(prefs.getString("iconSize", IconSize.MEDIUM.name) ?: IconSize.MEDIUM.name)
    )
    val iconSize: StateFlow<IconSize> = _iconSize.asStateFlow()

    private val _autoPlay = MutableStateFlow(prefs.getBoolean("autoPlay", true))
    val autoPlay: StateFlow<Boolean> = _autoPlay.asStateFlow()

    private val _showSeekButtons = MutableStateFlow(prefs.getBoolean("showSeekButtons", true))
    val showSeekButtons: StateFlow<Boolean> = _showSeekButtons.asStateFlow()

    // Note: Screen lock, stats visibility, and track selection are usually session-specific,
    // so we leave them in memory rather than saving them permanently.
    private val _isScreenLocked = MutableStateFlow(false)
    val isScreenLocked: StateFlow<Boolean> = _isScreenLocked.asStateFlow()

    private val _statsVisible = MutableStateFlow(false)
    val statsVisible: StateFlow<Boolean> = _statsVisible.asStateFlow()

    private val _selectedAudioTrackIndex = MutableStateFlow(0)
    val selectedAudioTrackIndex: StateFlow<Int> = _selectedAudioTrackIndex.asStateFlow()

    private val _selectedSubtitleIndices = MutableStateFlow(setOf<Int>())
    val selectedSubtitleIndices: StateFlow<Set<Int>> = _selectedSubtitleIndices.asStateFlow()

    private val _deviceStats = MutableStateFlow(
        DeviceStats("MediaCodec", 18, 87, 60, 34.5f)
    )
    val deviceStats: StateFlow<DeviceStats> = _deviceStats.asStateFlow()

    init { simulateDeviceStats() }

    private fun simulateDeviceStats() {
        viewModelScope.launch {
            while (true) {
                val current = _deviceStats.value
                _deviceStats.value = current.copy(
                    cpuPercent = Random.nextInt(5, 30),
                    batteryPercent = (current.batteryPercent - (if (Random.nextFloat() > 0.95) 1 else 0)).coerceAtLeast(1),
                    fps = Random.nextInt(58, 62),
                    temperatureCelsius = 30f + Random.nextFloat() * 10f
                )
                delay(2000)
            }
        }
    }

    fun updateDecoder(decoder: String) {
        _deviceStats.value = _deviceStats.value.copy(activeDecoder = decoder)
    }

    // Save values to SharedPreferences immediately when they change
    fun setSeekDuration(d: SeekDuration) {
        _seekDuration.value = d
        prefs.edit().putString("seekDuration", d.name).apply()
    }

    fun setCustomSeekMs(ms: Long) {
        val coercedMs = ms.coerceAtLeast(1_000L)
        _customSeekMs.value = coercedMs
        prefs.edit().putLong("customSeekMs", coercedMs).apply()
    }

    fun setSeekBarStyle(s: SeekBarStyle) {
        _seekBarStyle.value = s
        prefs.edit().putString("seekBarStyle", s.name).apply()
    }

    fun setIconSize(s: IconSize) {
        _iconSize.value = s
        prefs.edit().putString("iconSize", s.name).apply()
    }

    fun setAutoPlay(v: Boolean) {
        _autoPlay.value = v
        prefs.edit().putBoolean("autoPlay", v).apply()
    }

    fun setShowSeekButtons(v: Boolean) {
        _showSeekButtons.value = v
        prefs.edit().putBoolean("showSeekButtons", v).apply()
    }

    // Unchanged
    fun toggleScreenLock() { _isScreenLocked.value = !_isScreenLocked.value }
    fun toggleStats() { _statsVisible.value = !_statsVisible.value }
    fun selectAudioTrack(i: Int) { _selectedAudioTrackIndex.value = i }
    fun toggleSubtitleTrack(i: Int) {
        val cur = _selectedSubtitleIndices.value
        _selectedSubtitleIndices.value = if (i in cur) cur - i else cur + i
    }
}