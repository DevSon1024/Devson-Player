package com.devson.devsonplayer.player.gestures

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.Window

import kotlin.math.abs

/**
 * GestureController
 *
 * Interprets touch events for:
 * - Double tap left/right: Seek -10s / +10s
 * - Double tap center: Play/Pause
 * - Vertical swipe left: Brightness
 * - Vertical swipe right: Volume
 * - Long press: 2x Speed
 */
class GestureController(
    private val context: Context,
    private val listener: GestureListener
) : GestureDetector.SimpleOnGestureListener() {

    interface GestureListener {
        fun onSeekExtended(deltaMs: Long)
        fun onTogglePlayPause()
        fun onBrightnessChanged(value: Float)
        fun onVolumeChanged(value: Int, max: Int)
        fun onFastForwardMode(active: Boolean)
        fun onSingleTap()
    }

    private val detector = GestureDetector(context, this)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var viewWidth = 0
    private var viewHeight = 0

    private var isLongPressing = false

    fun setDimensions(width: Int, height: Int) {
        this.viewWidth = width
        this.viewHeight = height
    }

    fun handleTouchEvent(event: MotionEvent): Boolean {
        val handled = detector.onTouchEvent(event)

        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            if (isLongPressing) {
                isLongPressing = false
                listener.onFastForwardMode(false)
            }
        }

        return handled
    }

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        listener.onSingleTap()
        return true
    }

    var seekDurationMs: Long = 10_000L

    override fun onDoubleTap(e: MotionEvent): Boolean {
        val x = e.x
        val leftZone = viewWidth / 3
        val rightZone = (viewWidth / 3) * 2

        when {
            x < leftZone -> listener.onSeekExtended(-seekDurationMs)
            x > rightZone -> listener.onSeekExtended(seekDurationMs)
            else -> listener.onTogglePlayPause()
        }
        return true
    }

    override fun onLongPress(e: MotionEvent) {
        isLongPressing = true
        listener.onFastForwardMode(true)
    }

    private var startVolume: Int = 0
    private var startBrightness: Float = 1.0f

    override fun onDown(e: MotionEvent): Boolean {
        startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        // Note: Brightness will be managed by the activity/view, we just report delta
        return true
    }

    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ): Boolean {
        if (e1 == null || isLongPressing) return false

        val x = e1.x
        val totalDeltaY = e1.y - e2.y // Positive when swiping up
        val leftSide = x < viewWidth / 2

        if (abs(distanceY) > abs(distanceX)) {
            val progress = totalDeltaY / viewHeight.toFloat()
            if (leftSide) {
                listener.onBrightnessChanged(progress)
            } else {
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val volumeDelta = (progress * maxVolume).toInt()
                listener.onVolumeChanged(startVolume + volumeDelta, maxVolume)
            }
            return true
        }

        return false
    }
}
