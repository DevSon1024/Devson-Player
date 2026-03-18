package com.devson.devsonplayer.player

import android.util.Log
import android.view.Surface

/**
 * NativePlayer
 *
 * Kotlin-side JNI wrapper for the native C++ player (native_player.cpp).
 * Exposes an idiomatic Kotlin API over raw JNI calls.
 */
class NativePlayer {

    private var handle: Long = 0L
    private var initialized = false

    companion object {
        private const val TAG = "NativePlayer"

        init {
            System.loadLibrary("devson_player")
        }
    }

    /** Initialize player with a file path and a GLSurfaceView surface. */
    fun init(path: String, surface: Surface, width: Int, height: Int): Boolean {
        handle = nativeInitPlayer(path, surface, width, height)
        initialized = handle != 0L
        if (!initialized) Log.e(TAG, "nativeInitPlayer returned 0 for path=$path")
        return initialized
    }

    fun startDecoding() {
        if (!checkHandle("startDecoding")) return
        nativeStartDecoding(handle)
    }

    fun pause() {
        if (!checkHandle("pause")) return
        nativePause(handle)
    }

    fun resume() {
        if (!checkHandle("resume")) return
        nativeResume(handle)
    }

    fun setAudioStream(streamIndex: Int) {
        if (!checkHandle("setAudioStream")) return
        nativeSetAudioStream(handle, streamIndex)
    }

    fun seekTo(positionUs: Long) {
        if (!checkHandle("seekTo")) return
        nativeSeek(handle, positionUs)
    }

    fun setSpeed(speed: Float) {
        if (!checkHandle("setSpeed")) return
        nativeSetSpeed(handle, speed)
    }

    /** Pause the OpenGL renderer — call on Activity.onPause(). */
    fun pauseRenderer() {
        if (!checkHandle("pauseRenderer", quiet = true)) return
        nativePauseRenderer(handle)
    }

    /** Resume the OpenGL renderer — call on Activity.onResume(). */
    fun resumeRenderer() {
        if (!checkHandle("resumeRenderer", quiet = true)) return
        nativeResumeRenderer(handle)
    }

    /** Returns the current playback position in microseconds (interpolated from master clock). */
    fun getCurrentPositionUs(): Long =
        if (checkHandle("getCurrentPositionUs", quiet = true)) nativeGetCurrentPositionUs(handle) else 0L

    fun getWidth(): Int        = if (checkHandle("getWidth",    quiet = true)) nativeGetWidth(handle)      else 0
    fun getHeight(): Int       = if (checkHandle("getHeight",   quiet = true)) nativeGetHeight(handle)     else 0
    fun getDurationUs(): Long  = if (checkHandle("getDuration", quiet = true)) nativeGetDurationUs(handle) else 0L

    fun release() {
        if (handle == 0L) return
        nativeRelease(handle)
        handle = 0L
        initialized = false
    }

    private fun checkHandle(fn: String, quiet: Boolean = false): Boolean {
        if (handle == 0L) {
            if (!quiet) Log.e(TAG, "$fn called without valid handle")
            return false
        }
        return true
    }

    //  JNI declarations 
    private external fun nativeInitPlayer(path: String, surface: Surface, width: Int, height: Int): Long
    private external fun nativeStartDecoding(handle: Long)
    private external fun nativePause(handle: Long)
    private external fun nativeResume(handle: Long)
    private external fun nativeSeek(handle: Long, positionUs: Long)
    private external fun nativeSetSpeed(handle: Long, speed: Float)
    private external fun nativePauseRenderer(handle: Long)
    private external fun nativeResumeRenderer(handle: Long)
    private external fun nativeGetCurrentPositionUs(handle: Long): Long
    private external fun nativeGetWidth(handle: Long): Int
    private external fun nativeGetHeight(handle: Long): Int
    private external fun nativeGetDurationUs(handle: Long): Long
    private external fun nativeRelease(handle: Long)
    private external fun nativeSetAudioStream(handle: Long, streamIndex: Int)
}
