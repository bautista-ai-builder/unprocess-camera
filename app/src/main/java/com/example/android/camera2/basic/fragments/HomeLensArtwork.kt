package com.reilandeubank.unprocess.fragments

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Decorative sensor/lens motif behind the home headline. */
class HomeLensArtwork @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val unit = min(width, height).toFloat()
        val cx = width * 0.80f
        val cy = height * 0.53f
        for (index in 5 downTo 1) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (index == 1) 4f * resources.displayMetrics.density else 1f * resources.displayMetrics.density
            paint.color = Color.argb(if (index == 1) 210 else 48, 216, 237, 176)
            canvas.drawCircle(cx, cy, unit * (0.11f + index * 0.085f), paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(165, 216, 237, 176)
        canvas.drawCircle(cx, cy, unit * 0.075f, paint)
        paint.color = Color.argb(38, 216, 237, 176)
        canvas.drawCircle(cx, cy, unit * 0.26f, paint)
    }
}
