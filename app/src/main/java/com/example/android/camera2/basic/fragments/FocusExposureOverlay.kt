package com.reilandeubank.unprocess.fragments

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/** A light touch layer over the preview, with a focus mark and an exposure scale. */
class FocusExposureOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var onFocusTap: ((Float, Float) -> Unit)? = null
    var onExposureChange: ((Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private val hideTask = Runnable { visible = false; invalidate() }
    private val accent = Color.rgb(245, 214, 139)
    private var visible = false
    private var exposureEnabled = true
    private var focusX = 0f
    private var focusY = 0f
    private var sliderX = 0f
    private var sliderY = 0f
    private var progress = 0.5f
    private var exposureText = "0.0 EV"
    private var downX = 0f
    private var downY = 0f
    private var draggingExposure = false

    fun setExposureEnabled(enabled: Boolean) {
        exposureEnabled = enabled
        invalidate()
    }

    fun setExposure(progress: Float, label: String) {
        this.progress = progress.coerceIn(0f, 1f)
        exposureText = label
        invalidate()
    }

    fun showAt(x: Float, y: Float) {
        focusX = x.coerceIn(0f, width.toFloat())
        focusY = y.coerceIn(0f, height.toFloat())
        sliderX = if (focusX + dp(110) < width) focusX + dp(72) else focusX - dp(72)
        sliderX = sliderX.coerceIn(dp(36), width - dp(36))
        sliderY = focusY.coerceIn(dp(125), height - dp(125))
        visible = true
        scheduleHide()
        invalidate()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideTask)
        handler.postDelayed(hideTask, 5000)
    }

    private fun dp(value: Int) = value * density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!visible) return

        paint.color = accent
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2)
        canvas.drawCircle(focusX, focusY, dp(26), paint)
        canvas.drawLine(focusX - dp(7), focusY, focusX + dp(7), focusY, paint)
        canvas.drawLine(focusX, focusY - dp(7), focusX, focusY + dp(7), paint)

        if (!exposureEnabled) return
        val top = sliderY - dp(76)
        val bottom = sliderY + dp(76)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(105, 12, 15, 17)
        canvas.drawRoundRect(RectF(sliderX - dp(25), top - dp(37), sliderX + dp(25), bottom + dp(36)), dp(20), dp(20), paint)
        paint.color = Color.argb(135, 255, 255, 255)
        paint.strokeWidth = dp(2)
        canvas.drawLine(sliderX, top, sliderX, bottom, paint)
        canvas.drawLine(sliderX - dp(8), sliderY, sliderX + dp(8), sliderY, paint)
        paint.color = accent
        canvas.drawCircle(sliderX, bottom - progress * (bottom - top), dp(9), paint)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = dp(12)
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        canvas.drawText(exposureText, sliderX, top - dp(13), paint)
        paint.textSize = dp(16)
        canvas.drawText("+", sliderX, top + dp(20), paint)
        canvas.drawText("−", sliderX, bottom - dp(12), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                draggingExposure = visible && exposureEnabled &&
                    abs(event.x - sliderX) <= dp(36) &&
                    abs(event.y - sliderY) <= dp(118)
                if (draggingExposure) {
                    handler.removeCallbacks(hideTask)
                    updateExposure(event.y)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingExposure) updateExposure(event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (draggingExposure) {
                    updateExposure(event.y)
                    scheduleHide()
                } else if (abs(event.x - downX) <= touchSlop && abs(event.y - downY) <= touchSlop) {
                    showAt(event.x, event.y)
                    onFocusTap?.invoke(event.x, event.y)
                    performClick()
                }
                draggingExposure = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                draggingExposure = false
                scheduleHide()
                return true
            }
        }
        return true
    }

    private fun updateExposure(y: Float) {
        val top = sliderY - dp(76)
        val bottom = sliderY + dp(76)
        val newProgress = ((bottom - y) / (bottom - top)).coerceIn(0f, 1f)
        if (abs(newProgress - progress) > 0.002f) {
            progress = newProgress
            onExposureChange?.invoke(newProgress)
            invalidate()
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(hideTask)
        super.onDetachedFromWindow()
    }
}
