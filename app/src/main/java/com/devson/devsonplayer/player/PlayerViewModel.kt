package com.devson.devsonplayer.player

import com.devson.devsonplayer.player.SubtitleManager

import android.app.Application
import android.net.Uri
import android.view.Surface
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.devson.devsonplayer.ui.ScalingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * PlayerViewModel
 *
 * Lifecycle-aware wrapper around PlayerController.
 * Survives configuration changes (screen rotation).
 */
@OptIn(UnstableApi::class)
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    val controller      = PlayerController(application)
    val subtitleManager = SubtitleManager()

    init {
        // Wire real ExoPlayer track info into this ViewModel
        controller.onTracksChanged = { tracks -> updateTracksFromExo(tracks) }
    }

    val state = controller.state.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlayerController.PlayerState.Idle
    )

    val decoderType = controller.decoderType.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = null as DecoderSelector.DecoderType?
    )

    val currentSubtitle = subtitleManager.currentCue.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = null as SubtitleManager.SubtitleCue?
    )

    val videoInfo = controller.videoInfo.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = null as DecoderSelector.VideoInfo?
    )

    private val _scalingMode = MutableStateFlow<ScalingMode>(ScalingMode.FIT)
    val scalingMode = _scalingMode.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = ScalingMode.FIT
    )

    //  Real audio / subtitle tracks from ExoPlayer 

    /** Populated after ExoPlayer loads tracks. Empty = show "Default" in UI. */
    private val _audioTracks = MutableStateFlow<List<AudioTrack>>(emptyList())
    val audioTracks: StateFlow<List<AudioTrack>> = _audioTracks.asStateFlow()

    private val _subtitleTracks = MutableStateFlow<List<SubtitleTrack>>(emptyList())
    val subtitleTracks: StateFlow<List<SubtitleTrack>> = _subtitleTracks.asStateFlow()

    // Called by PlayerController (or directly) when ExoPlayer is ready with tracks
    fun updateTracksFromExo(tracks: Tracks) {
        val audio = mutableListOf<AudioTrack>()
        val subs  = mutableListOf<SubtitleTrack>()

        for (group in tracks.groups) {
            val type = group.type
            for (i in 0 until group.length) {
                val format   = group.getTrackFormat(i)
                val language = format.language ?: "und"
                val langName = displayLanguage(language)

                when (type) {
                    C.TRACK_TYPE_AUDIO -> {
                        val channels = format.channelCount.takeIf { it > 0 }
                        val label    = buildString {
                            append(langName)
                            if (channels != null) append(" ($channels ch)")
                            format.sampleMimeType?.let { mime ->
                                append(" · ${mime.substringAfter('/')}")
                            }
                        }
                        audio += AudioTrack(index = audio.size, language = langName, label = label)
                    }
                    C.TRACK_TYPE_TEXT -> {
                        subs += SubtitleTrack(index = subs.size, language = langName, label = langName)
                    }
                }
            }
        }

        _audioTracks.value    = audio
        _subtitleTracks.value = subs
    }

    private fun displayLanguage(tag: String): String =
        try {
            val locale = java.util.Locale.forLanguageTag(tag)
            locale.getDisplayLanguage(locale).replaceFirstChar { it.uppercase() }.ifBlank { tag }
        } catch (_: Exception) { tag }

    //  ScalingMode 

    fun toggleScalingMode() {
        _scalingMode.value = when (_scalingMode.value) {
            ScalingMode.FIT      -> ScalingMode.CROP
            ScalingMode.CROP     -> ScalingMode.ORIGINAL
            ScalingMode.ORIGINAL -> ScalingMode.FIT
            else                 -> ScalingMode.FIT
        }
    }

    //  Delegation 

    fun load(uri: Uri)              = controller.load(uri)
    fun play()                      = controller.play()
    fun pause()                     = controller.pause()
    fun seekTo(ms: Long)            = controller.seekTo(ms)
    fun setSpeed(speed: Float)      = controller.setSpeed(speed)
    fun setHardwareSurface(s: Surface?) = controller.setHardwareSurface(s)
    fun setSoftwareSurface(s: Surface?) = controller.setSoftwareSurface(s)
    fun onPositionUpdate(posMs: Long)   = subtitleManager.onPositionChanged(posMs)

    override fun onCleared() {
        super.onCleared()
        controller.release()
    }
}
