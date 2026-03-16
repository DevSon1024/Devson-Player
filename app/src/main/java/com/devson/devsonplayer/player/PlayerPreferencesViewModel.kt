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

//  Preference enums

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

// 
//  Data models (shared between ViewModel and UI)
// 

data class AudioTrack(val index: Int, val language: String, val label: String = language)
data class SubtitleTrack(val index: Int, val language: String, val label: String = language)

data class DeviceStats(
    val activeDecoder: String,
    val cpuPercent: Int,
    val batteryPercent: Int,
    val fps: Int,
    val temperatureCelsius: Float
)

// 
//  ViewModel — preferences + device stats only (no mock track data)
// 

class PlayerPreferencesViewModel(application: Application) : AndroidViewModel(application) {

    //  Playback preferences 

    private val _seekDuration = MutableStateFlow(SeekDuration.SEC_10)
    val seekDuration: StateFlow<SeekDuration> = _seekDuration.asStateFlow()

    private val _customSeekMs = MutableStateFlow(10_000L)
    val customSeekMs: StateFlow<Long> = _customSeekMs.asStateFlow()

    fun effectiveSeekMs(): Long =
        if (_seekDuration.value == SeekDuration.CUSTOM) _customSeekMs.value
        else _seekDuration.value.ms

    private val _seekBarStyle = MutableStateFlow(SeekBarStyle.DEFAULT)
    val seekBarStyle: StateFlow<SeekBarStyle> = _seekBarStyle.asStateFlow()

    private val _iconSize = MutableStateFlow(IconSize.MEDIUM)
    val iconSize: StateFlow<IconSize> = _iconSize.asStateFlow()

    private val _autoPlay = MutableStateFlow(true)
    val autoPlay: StateFlow<Boolean> = _autoPlay.asStateFlow()

    private val _showSeekButtons = MutableStateFlow(true)
    val showSeekButtons: StateFlow<Boolean> = _showSeekButtons.asStateFlow()

    //  Screen lock 

    private val _isScreenLocked = MutableStateFlow(false)
    val isScreenLocked: StateFlow<Boolean> = _isScreenLocked.asStateFlow()

    //  Stats HUD toggle 

    private val _statsVisible = MutableStateFlow(false)
    val statsVisible: StateFlow<Boolean> = _statsVisible.asStateFlow()

    //  Track selection state (index only — real track lists come from PlayerViewModel) 

    private val _selectedAudioTrackIndex = MutableStateFlow(0)
    val selectedAudioTrackIndex: StateFlow<Int> = _selectedAudioTrackIndex.asStateFlow()

    private val _selectedSubtitleIndices = MutableStateFlow(setOf<Int>())
    val selectedSubtitleIndices: StateFlow<Set<Int>> = _selectedSubtitleIndices.asStateFlow()

    //  Device stats (simulated every 2s) 

    private val _deviceStats = MutableStateFlow(
        DeviceStats("MediaCodec", 18, 87, 60, 34.5f)
    )
    val deviceStats: StateFlow<DeviceStats> = _deviceStats.asStateFlow()

    init { simulateDeviceStats() }

    private fun simulateDeviceStats() {
        viewModelScope.launch {
            while (true) {
                delay(2_000)
                _deviceStats.value = DeviceStats(
                    activeDecoder      = if (Random.nextFloat() > 0.1f) "MediaCodec" else "FFmpeg",
                    cpuPercent         = Random.nextInt(10, 45),
                    batteryPercent     = (_deviceStats.value.batteryPercent - Random.nextInt(0, 2)).coerceAtLeast(1),
                    fps                = Random.nextInt(55, 62),
                    temperatureCelsius = 33f + Random.nextFloat() * 6f
                )
            }
        }
    }

    //  Mutation helpers 

    fun setSeekDuration(d: SeekDuration)  { _seekDuration.value = d }
    fun setCustomSeekMs(ms: Long)         { _customSeekMs.value = ms.coerceAtLeast(1_000L) }
    fun setSeekBarStyle(s: SeekBarStyle)  { _seekBarStyle.value = s }
    fun setIconSize(s: IconSize)          { _iconSize.value = s }
    fun setAutoPlay(v: Boolean)           { _autoPlay.value = v }
    fun setShowSeekButtons(v: Boolean)    { _showSeekButtons.value = v }
    fun toggleScreenLock()               { _isScreenLocked.value = !_isScreenLocked.value }
    fun toggleStats()                    { _statsVisible.value = !_statsVisible.value }
    fun selectAudioTrack(i: Int)          { _selectedAudioTrackIndex.value = i }
    fun toggleSubtitleTrack(i: Int) {
        val cur = _selectedSubtitleIndices.value
        _selectedSubtitleIndices.value = if (i in cur) cur - i else cur + i
    }
}
