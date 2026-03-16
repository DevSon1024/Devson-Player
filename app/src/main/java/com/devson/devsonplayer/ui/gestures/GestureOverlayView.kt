package com.devson.devsonplayer.ui.gestures

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.devson.devsonplayer.player.gestures.GestureController
import kotlin.math.roundToInt

/**
 * GestureOverlayView
 *
 * A custom view that overlays the video surface to capture gestures
 * and display feedback indicators (Volume, Brightness, Seek, Speed).
 */
class GestureOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), GestureController.GestureListener {

    private val gestureController = GestureController(context, this)

    // Drawing paints
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80000000") // Semi-transparent black
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 48f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val barBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        style = Paint.Style.FILL
    }

    // State
    private var indicatorType: IndicatorType = IndicatorType.NONE
    private var indicatorValue: Float = 0f
    private var indicatorText: String = ""
    private var initialBrightness: Float = 1.0f

    enum class IndicatorType {
        NONE, VOLUME, BRIGHTNESS, SEEK, SPEED
    }

    init {
        setWillNotDraw(false)
        // Try to get current screen brightness
        val window = (context.findActivity() as? android.app.Activity)?.window
        initialBrightness = window?.attributes?.screenBrightness ?: 1.0f
        if (initialBrightness < 0) initialBrightness = 0.5f // Default
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gestureController.setDimensions(w, h)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestureController.handleTouchEvent(event)
        
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            // Hide indicators after a delay if not seeking or long pressing
            if (indicatorType != IndicatorType.SPEED) {
                postDelayed({
                    indicatorType = IndicatorType.NONE
                    invalidate()
                }, 500)
            }
        }
        
        return handled || super.onTouchEvent(event)
    }

    //  GestureController.GestureListener 

    override fun onSeekExtended(deltaMs: Long) {
        indicatorType = IndicatorType.SEEK
        indicatorText = if (deltaMs > 0) "+${deltaMs / 1000}s" else "${deltaMs / 1000}s"
        invalidate()
        // Parent or external controller should handle actual seek
        onSeekAction?.invoke(deltaMs)
    }

    override fun onTogglePlayPause() {
        onToggleAction?.invoke()
    }

    override fun onBrightnessChanged(progress: Float) {
        indicatorType = IndicatorType.BRIGHTNESS
        val newBrightness = (initialBrightness + progress).coerceIn(0.01f, 1.0f)
        indicatorValue = newBrightness
        
        // Apply brightness to window
        val activity = context.findActivity() as? android.app.Activity
        activity?.window?.let { window ->
            val lp = window.attributes
            lp.screenBrightness = newBrightness
            window.attributes = lp
        }
        
        invalidate()
    }

    override fun onVolumeChanged(value: Int, max: Int) {
        indicatorType = IndicatorType.VOLUME
        val safeValue = value.coerceIn(0, max)
        indicatorValue = safeValue.toFloat() / max.toFloat()
        
        // Apply volume
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, safeValue, 0)
        
        invalidate()
    }

    override fun onFastForwardMode(active: Boolean) {
        if (active) {
            indicatorType = IndicatorType.SPEED
            indicatorText = "2x Speed"
            onSpeedAction?.invoke(2.0f)
        } else {
            indicatorType = IndicatorType.NONE
            onSpeedAction?.invoke(1.0f)
        }
        invalidate()
    }

    override fun onSingleTap() {
        onSingleTapAction?.invoke()
    }

    //  Callbacks to outside 

    var onSeekAction: ((Long) -> Unit)? = null
    var onToggleAction: (() -> Unit)? = null
    var onSpeedAction: ((Float) -> Unit)? = null
    var onSingleTapAction: (() -> Unit)? = null

    //  Drawing 

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)

        if (indicatorType == IndicatorType.NONE) return

        val centerX = width / 2f
        val centerY = height / 2f

        when (indicatorType) {
            IndicatorType.VOLUME, IndicatorType.BRIGHTNESS -> {
                drawVerticalBarIndicator(canvas, centerX, centerY, indicatorType.name)
            }
            IndicatorType.SEEK, IndicatorType.SPEED -> {
                drawTextIndicator(canvas, centerX, centerY)
            }
            else -> {}
        }
    }

    private fun drawVerticalBarIndicator(canvas: Canvas, cx: Float, cy: Float, label: String) {
        val barWidth = 60f
        val barHeight = 400f
        val padding = 30f
        
        // Background card
        val cardRect = RectF(cx - 80f, cy - 250f, cx + 80f, cy + 250f)
        overlayPaint.color = Color.parseColor("#99000000")
        canvas.drawRoundRect(cardRect, 40f, 40f, overlayPaint)
        
        // Icon/Label
        textPaint.textSize = 36f
        canvas.drawText(label, cx, cy - 180f, textPaint)

        // Progress track
        val trackRect = RectF(cx - 10f, cy - 120f, cx + 10f, cy + 120f)
        barBackgroundPaint.color = Color.parseColor("#4DFFFFFF")
        canvas.drawRoundRect(trackRect, 10f, 10f, barBackgroundPaint)
        
        // Progress fill
        val progressHeight = 240f * indicatorValue
        val fillRect = RectF(cx - 10f, cy + 120f - progressHeight, cx + 10f, cy + 120f)
        barPaint.color = Color.WHITE
        canvas.drawRoundRect(fillRect, 10f, 10f, barPaint)
        
        // Percentage text
        textPaint.textSize = 32f
        canvas.drawText("${(indicatorValue * 100).roundToInt()}%", cx, cy + 180f, textPaint)
    }

    private fun drawTextIndicator(canvas: Canvas, cx: Float, cy: Float) {
        textPaint.textSize = 56f
        val textWidth = textPaint.measureText(indicatorText)
        val rect = RectF(cx - textWidth / 2 - 60f, cy - 100f, cx + textWidth / 2 + 60f, cy + 60f)
        overlayPaint.color = Color.parseColor("#99000000")
        canvas.drawRoundRect(rect, 30f, 30f, overlayPaint)
        canvas.drawText(indicatorText, cx, cy, textPaint)
    }

    // Helper to find Activity
    private fun Context.findActivity(): android.app.Activity? {
        var ctx = this
        while (ctx is android.content.ContextWrapper) {
            if (ctx is android.app.Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }
}
