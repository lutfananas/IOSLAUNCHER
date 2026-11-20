/*
 * iOS Launcher — Copyright (c) 2026 lutfananas. All rights reserved. See LICENSE.
 */
package com.ioslauncher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import com.ioslauncher.icons.IosShape

/**
 * iOS-style "app returns to its icon": a card in the app's colour, carrying its icon, shrinks from
 * (nearly) full screen into the icon's exact place and turns into the icon. Driven by [progress]
 * 0 → 1 from a spring, so it can overshoot slightly before settling.
 */
class CloseToIconView(context: Context, private val from: RectF, private val to: Rect, private val icon: Bitmap) : View(context) {
    var progress = 0f
        set(v) { field = v; invalidate() }

    private val color = backgroundColor(icon)
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val startRadius = context.resources.displayMetrics.density * 44f
    private val endRadius = to.width() * IosShape.ICON_CORNER_RATIO

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun smooth(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    override fun onDraw(canvas: Canvas) {
        val p = progress
        rect.set(lerp(from.left, to.left.toFloat(), p), lerp(from.top, to.top.toFloat(), p),
            lerp(from.right, to.right.toFloat(), p), lerp(from.bottom, to.bottom.toFloat(), p))
        val radius = lerp(startRadius, endRadius, p.coerceIn(0f, 1f))
        IosShape.roundRect(rect, radius, path)

        // Soft shadow while it's still big, so it reads as a layer above the home screen.
        val sh = 1f - smooth(0.5f, 1f, p)
        if (sh > 0.01f) {
            shadow.color = Color.argb((60 * sh).toInt(), 0, 0, 0)
            shadow.setShadowLayer(30f * sh + 1f, 0f, 10f * sh, Color.argb((90 * sh).toInt(), 0, 0, 0))
            canvas.drawPath(path, shadow)
        }
        // App-coloured card fades out as it reaches icon size…
        val cardAlpha = 1f - smooth(0.55f, 0.98f, p)
        cardPaint.color = color
        cardPaint.alpha = (255 * cardAlpha).toInt()
        canvas.drawPath(path, cardPaint)
        // …while the icon grows from the card's centre to fill it exactly.
        val endSize = to.width().toFloat()
        val size = minOf(lerp(endSize * 1.7f, endSize, p), minOf(rect.width(), rect.height()))
        val cx = rect.centerX(); val cy = rect.centerY()
        iconPaint.alpha = (255 * smooth(0.0f, 0.45f, p)).toInt()
        canvas.save()
        canvas.clipPath(path)
        canvas.drawBitmap(icon, null, RectF(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2), iconPaint)
        canvas.restore()
    }

    companion object {
        /**
         * The colour behind the icon artwork — what apps use for their splash background (WhatsApp:
         * green). Sampled just inside the left edge; falls back to the average colour for odd shapes.
         */
        fun backgroundColor(b: Bitmap): Int {
            val x = (b.width * 0.07f).toInt(); val y = b.height / 2
            val c = b.getPixel(x.coerceIn(0, b.width - 1), y)
            return if (Color.alpha(c) > 220) (c or 0xFF000000.toInt()) else averageColor(b)
        }

        /** Opaque average colour of the icon (alpha-weighted), used for the shrinking card. */
        fun averageColor(b: Bitmap): Int {
            val small = Bitmap.createScaledBitmap(b, 16, 16, true)
            var r = 0L; var g = 0L; var bl = 0L; var w = 0L
            for (y in 0 until 16) for (x in 0 until 16) {
                val c = small.getPixel(x, y); val a = Color.alpha(c).toLong()
                r += Color.red(c) * a; g += Color.green(c) * a; bl += Color.blue(c) * a; w += a
            }
            if (w == 0L) return Color.DKGRAY
            return Color.rgb((r / w).toInt(), (g / w).toInt(), (bl / w).toInt())
        }
    }
}
