package dev.spike.launcherprobe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** A view with a few hard-edged circles, so that a blur applied to it is clearly visible. */
class BlobView(ctx: Context, private val color: Int, private val seed: Int) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        paint.color = color
        val w = width.toFloat()
        val h = height.toFloat()
        for (k in 0 until 4) {
            val fx = ((seed * 37 + k * 53) % 100) / 100f
            val fy = ((seed * 61 + k * 29) % 100) / 100f
            canvas.drawCircle(w * (0.15f + 0.7f * fx), h * (0.1f + 0.8f * fy), w * 0.16f, paint)
        }
    }
}
