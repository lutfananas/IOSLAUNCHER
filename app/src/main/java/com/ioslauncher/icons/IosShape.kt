package com.ioslauncher.icons

import android.graphics.Path
import android.graphics.RectF

/**
 * Apple's "continuous corner" rounded rectangle (the squircle used for iOS app icons, the dock,
 * folders and App Library cards). Same Bézier construction UIKit uses for
 * `UIBezierPath(roundedRect:cornerRadius:)` with `.continuous` corner curve: each corner spans
 * 1.52866 × radius along each edge and is built from three cubic segments with curvature continuity.
 */
object IosShape {
    /** iOS app icon corner radius relative to icon side (Apple icon grid: 0.2237 × size). */
    const val ICON_CORNER_RATIO = 0.2237f

    private const val EXTENT = 1.52866483f

    // Control/end points for one corner, as (along-edge distance from corner, inward distance).
    private val CORNER = floatArrayOf(
        1.08849323f, 0f, 0.86840689f, 0f, 0.63149399f, 0.07491100f,
        0.37282392f, 0.16905899f, 0.16906013f, 0.37282401f, 0.07491176f, 0.63149399f,
        0f, 0.86840701f, 0f, 1.08849299f, 0f, EXTENT,
    )

    fun iconPath(size: Float, out: Path = Path()): Path =
        roundRect(RectF(0f, 0f, size, size), size * ICON_CORNER_RATIO, out)

    fun roundRect(rect: RectF, radius: Float, out: Path = Path()): Path {
        val l = rect.left; val t = rect.top; val r = rect.right; val b = rect.bottom
        val rad = minOf(radius, minOf(rect.width(), rect.height()) / 2f / EXTENT).coerceAtLeast(0f)
        out.reset()
        out.moveTo(l + EXTENT * rad, t)
        out.lineTo(r - EXTENT * rad, t)
        corner(out, rad) { a, d -> floatArrayOf(r - a, t + d) }   // top-right
        out.lineTo(r, b - EXTENT * rad)
        corner(out, rad) { a, d -> floatArrayOf(r - d, b - a) }   // bottom-right
        out.lineTo(l + EXTENT * rad, b)
        corner(out, rad) { a, d -> floatArrayOf(l + a, b - d) }   // bottom-left
        out.lineTo(l, t + EXTENT * rad)
        corner(out, rad) { a, d -> floatArrayOf(l + d, t + a) }   // top-left
        out.close()
        return out
    }

    private inline fun corner(path: Path, rad: Float, map: (Float, Float) -> FloatArray) {
        var i = 0
        while (i < CORNER.size) {
            val p1 = map(CORNER[i] * rad, CORNER[i + 1] * rad)
            val p2 = map(CORNER[i + 2] * rad, CORNER[i + 3] * rad)
            val p3 = map(CORNER[i + 4] * rad, CORNER[i + 5] * rad)
            path.cubicTo(p1[0], p1[1], p2[0], p2[1], p3[0], p3[1])
            i += 6
        }
    }
}
