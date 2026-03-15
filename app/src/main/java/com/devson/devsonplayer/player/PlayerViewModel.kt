package com.devson.devsonplayer.player

import android.app.Application
import android.net.Uri
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

/**
 * PlayerViewModel
 *
 * Lifecycle-aware wrapper around PlayerController.
 * Survives configuration changes (screen rotation).
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    val controller = PlayerController(application)
    val subtitleManager = SubtitleManager()

    val state = controller.state.stateIn(
        scope           = viewModelScope,
        started         = SharingStarted.WhileSubscribed(5_000),
        initialValue    = PlayerController.PlayerState.Idle
    )

    val decoderType = controller.decoderType.stateIn(
        scope           = viewModelScope,
        started         = SharingStarted.WhileSubscribed(5_000),
        initialValue    = null
    )

    val currentSubtitle = subtitleManager.currentCue.stateIn(
        scope           = viewModelScope,
        started         = SharingStarted.WhileSubscribed(5_000),
        initialValue    = null
    )

    fun load(uri: Uri) = controller.load(uri)
    fun play()         = controller.play()
    fun pause()        = controller.pause()
    fun seekTo(ms: Long) = controller.seekTo(ms)
    fun setSpeed(speed: Float) = controller.setSpeed(speed)

    fun setHardwareSurface(surface: Surface?) = controller.setHardwareSurface(surface)
    fun setSoftwareSurface(surface: Surface?) = controller.setSoftwareSurface(surface)

    fun onPositionUpdate(posMs: Long) = subtitleManager.onPositionChanged(posMs)

    override fun onCleared() {
        super.onCleared()
        controller.release()
    }
}
