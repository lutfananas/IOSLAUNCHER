/*
 * iOS Launcher — Copyright (c) 2026 lutfananas. All rights reserved. See LICENSE.
 */
package com.ioslauncher.ui

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import com.ioslauncher.LauncherApp
import com.ioslauncher.icons.IosShape
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * "Liquid Glass" material, iOS 26/27 style.
 *
 * Each glass surface samples a blurred copy of the wallpaper at its own screen position, bends it
 * near the rim like a thick lens (AGSL refraction with slight chromatic split, Android 13+), adds a
 * light tint, a soft top sheen and a specular gradient rim. When the wallpaper can't be read
 * (live wallpaper, or no permission on Android 14+), it falls back to frosted translucent glass;
 * the window-level background blur still blurs the wallpaper behind overlays.
 */
object Glass {
    private const val DOWNSCALE = 4
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile private var blurred: Bitmap? = null

    /** Where glass gets its look from — shown in Settings so it's clear why blur does or doesn't apply. */
    enum class Status { UNKNOWN, REAL, NO_ACCESS, LIVE_WALLPAPER }
    @Volatile var status = Status.UNKNOWN; private set
    @Volatile private var fallbackTint = Color.rgb(120, 130, 160)
    @Volatile private var fallbackTint2 = Color.rgb(90, 100, 140)
    private var bitmapShader: BitmapShader? = null
    private var runtimeShader: Any? = null      // RuntimeShader on API 33+
    private var shaderFailed = false
    private val listeners = mutableListOf<() -> Unit>()
    val hasWallpaper get() = bitmapShader != null

    private val loc = IntArray(2)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shaderMatrix = Matrix()

    /** Light direction from device tilt (-1..1); moves the sheen and rim highlight like iOS lensing. */
    var lightX = 0f; private set
    var lightY = 0f; private set
    fun setLight(x: Float, y: Float) { lightX = x; lightY = y }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    /** (Re)load the wallpaper for glass sampling. Safe to call often. */
    // User tuning (Settings → Liquid Glass), refreshed on every load.
    private var blurAmt = 1f
    private var refractAmt = 1f
    private var frostAmt = 1f
    private var shineAmt = 1f

    private fun readSettings() {
        val p = LauncherApp.instance.prefs
        blurAmt = p.glassBlur; refractAmt = p.glassRefraction; frostAmt = p.glassFrost; shineAmt = p.glassShine
    }

    private fun mix(a: Int, b: Int, t: Float) = (a + (b - a) * t).toInt().coerceIn(0, 255)

    private fun scaleAlpha(c: Int, k: Float) = Color.argb((Color.alpha(c) * k).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    fun load(context: Context, screenW: Int, screenH: Int) {
        readSettings()
        val app = context.applicationContext
        worker.execute {
            val bmp = runCatching { buildBlurred(app, screenW, screenH) }.getOrNull()
            main.post {
                blurred = bmp
                bitmapShader = bmp?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
                    ?.also { s -> shaderMatrix.setScale(DOWNSCALE.toFloat(), DOWNSCALE.toFloat()); s.setLocalMatrix(shaderMatrix) }
                runtimeShader = null
                listeners.toList().forEach { it() }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildBlurred(context: Context, screenW: Int, screenH: Int): Bitmap? {
        val wm = WallpaperManager.getInstance(context)
        // Wallpaper tint for the frosted fallback (colours are readable without any permission).
        runCatching { wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }.getOrNull()?.let { wc ->
            fallbackTint = wc.primaryColor.toArgb()
            fallbackTint2 = (wc.secondaryColor ?: wc.primaryColor).toArgb()
        }
        if (wm.wallpaperInfo != null) { status = Status.LIVE_WALLPAPER; return null }   // live wallpaper: no static image
        val d: Drawable = runCatching { wm.drawable }.getOrNull() ?: run { status = Status.NO_ACCESS; return null }
        status = Status.REAL
        val w = max(1, screenW / DOWNSCALE); val h = max(1, screenH / DOWNSCALE)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: screenW
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: screenH
        val s = max(w / iw.toFloat(), h / ih.toFloat())               // centre-crop like the system renderer
        val dw = iw * s; val dh = ih * s
        val l = (w - dw) / 2f; val t = (h - dh) / 2f
        if (d is BitmapDrawable && d.bitmap != null) {
            c.drawBitmap(d.bitmap, null, RectF(l, t, l + dw, t + dh), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            d.setBounds(l.roundToInt(), t.roundToInt(), (l + dw).roundToInt(), (t + dh).roundToInt()); d.draw(c)
        }
        // Blur 0 = clear glass, 1 = default (~20px), 2 = heavy frost.
        val r = (5 * blurAmt).roundToInt()
        if (r > 0) { boxBlur(out, r); boxBlur(out, r); boxBlur(out, (4 * blurAmt).roundToInt().coerceAtLeast(1)) }
        return out
    }

    /** Separable box blur (3 passes ≈ gaussian). */
    private fun boxBlur(b: Bitmap, r: Int) {
        val w = b.width; val h = b.height
        val src = IntArray(w * h); b.getPixels(src, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        fun pass(input: IntArray, output: IntArray, len: Int, lines: Int, stride: Int, step: Int) {
            val div = 2 * r + 1
            for (line in 0 until lines) {
                var ra = 0; var rr = 0; var rg = 0; var rb = 0
                val base = line * stride
                for (i in -r..r) {
                    val p = input[base + i.coerceIn(0, len - 1) * step]
                    ra += p ushr 24; rr += (p shr 16) and 0xFF; rg += (p shr 8) and 0xFF; rb += p and 0xFF
                }
                for (i in 0 until len) {
                    output[base + i * step] = ((ra / div) shl 24) or ((rr / div) shl 16) or ((rg / div) shl 8) or (rb / div)
                    val pOut = input[base + (i - r).coerceIn(0, len - 1) * step]
                    val pIn = input[base + (i + r + 1).coerceIn(0, len - 1) * step]
                    ra += (pIn ushr 24) - (pOut ushr 24)
                    rr += ((pIn shr 16) and 0xFF) - ((pOut shr 16) and 0xFF)
                    rg += ((pIn shr 8) and 0xFF) - ((pOut shr 8) and 0xFF)
                    rb += (pIn and 0xFF) - (pOut and 0xFF)
                }
            }
        }
        pass(src, tmp, w, h, w, 1)       // horizontal
        pass(tmp, src, h, w, 1, w)       // vertical
        b.setPixels(src, 0, w, 0, 0, w, h)
    }

    @RequiresApi(33)
    private fun refraction(): RuntimeShader {
        (runtimeShader as? RuntimeShader)?.let { return it }
        val s = RuntimeShader(AGSL)
        bitmapShader?.let { s.setInputShader("wall", it) }
        runtimeShader = s
        return s
    }

    /**
     * Draw a glass surface shaped by [path] (in [view]'s local coordinates) covering [rect].
     * [tint] adds colour on top (e.g. white 10% for clear glass, darker for menus).
     */
    fun draw(canvas: Canvas, view: View, path: Path, rect: RectF, radius: Float, tint: Int = DEFAULT_TINT, shadow: Boolean = true) {
        val liquid = LauncherApp.instance.prefs.liquidGlass
        val dm = view.resources.displayMetrics.density
        val bs = bitmapShader
        if (bs != null) {
            view.getLocationOnScreen(loc)
            if (shadow) {
                shadowPaint.color = Color.argb(30, 0, 0, 0)
                shadowPaint.setShadowLayer(14 * dm, 0f, 4 * dm, Color.argb(45, 0, 0, 0))
                canvas.drawPath(path, shadowPaint)
            }
            // RuntimeShader is GPU-only: snapshots drawn into a Bitmap canvas use the plain blurred sample.
            val rs = if (liquid && Build.VERSION.SDK_INT >= 33 && !shaderFailed && canvas.isHardwareAccelerated) {
                runCatching { refraction() }.onFailure { shaderFailed = true }.getOrNull()
            } else null
            if (rs != null && Build.VERSION.SDK_INT >= 33) {
                rs.setFloatUniform("viewOrigin", loc[0].toFloat(), loc[1].toFloat())
                rs.setFloatUniform("rect", rect.left, rect.top, rect.right, rect.bottom)
                rs.setFloatUniform("radius", radius)
                rs.setFloatUniform("bezel", (minOf(rect.width(), rect.height()) * 0.32f * (0.6f + 0.4f * refractAmt)).coerceIn(1f, 30 * dm))
                rs.setFloatUniform("strength", (minOf(rect.width(), rect.height()) * 0.16f).coerceAtMost(14 * dm) * refractAmt)
                fill.shader = rs
            } else {
                val m = Matrix(shaderMatrix); m.postTranslate(-loc[0].toFloat(), -loc[1].toFloat())
                bs.setLocalMatrix(m)
                fill.shader = bs
            }
            fill.alpha = 255
            canvas.drawPath(path, fill)
            fill.shader = null
            bs.setLocalMatrix(shaderMatrix)
        } else {
            // Frosted fallback (wallpaper not readable): a milky glass tinted with the wallpaper's own colours.
            // The Blur slider controls how frosted/opaque it is, so it still has a visible effect here.
            val frost = (0.35f + 0.65f * (blurAmt / 2f).coerceIn(0f, 1f))
            if (shadow) {
                shadowPaint.color = Color.argb(20, 0, 0, 0)
                shadowPaint.setShadowLayer(14 * dm, 0f, 4 * dm, Color.argb(40, 0, 0, 0))
                canvas.drawPath(path, shadowPaint)
            }
            val a1 = (150 * frost).toInt(); val a2 = (110 * frost).toInt()
            fill.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                Color.argb(a1, mix(Color.red(fallbackTint), 255, 0.45f), mix(Color.green(fallbackTint), 255, 0.45f), mix(Color.blue(fallbackTint), 255, 0.45f)),
                Color.argb(a2, mix(Color.red(fallbackTint2), 255, 0.3f), mix(Color.green(fallbackTint2), 255, 0.3f), mix(Color.blue(fallbackTint2), 255, 0.3f)),
                Shader.TileMode.CLAMP)
            canvas.drawPath(path, fill)
            fill.shader = null
        }
        tintPaint.color = scaleAlpha(tint, frostAmt)
        canvas.drawPath(path, tintPaint)

        if (!liquid || shineAmt <= 0.01f) return
        fun a(v: Int) = (v * shineAmt).toInt().coerceIn(0, 255)
        // Soft sheen from the top-left, like light caught inside the glass.
        canvas.save()
        canvas.clipPath(path)
        sheenPaint.shader = RadialGradient(rect.left + rect.width() * (0.25f + 0.3f * lightX), rect.top + rect.height() * 0.25f * lightY,
            max(rect.width(), rect.height()) * 0.8f,
            Color.argb(a(40), 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(rect, sheenPaint)
        canvas.restore()
        // Specular rim: bright top-left and bottom-right edges, fading in between.
        rimPaint.strokeWidth = 1.2f * dm
        // Rim gradient axis rotates with the light direction.
        val ang = Math.toRadians((45.0 + lightX * 40.0 + lightY * 20.0)).toFloat()
        val hx = kotlin.math.cos(ang) * rect.width() / 2f; val hy = kotlin.math.sin(ang) * rect.height() / 2f
        rimPaint.shader = LinearGradient(rect.centerX() - hx, rect.centerY() - hy, rect.centerX() + hx, rect.centerY() + hy,
            intArrayOf(Color.argb(a(190), 255, 255, 255), Color.argb(a(25), 255, 255, 255), Color.argb(a(25), 255, 255, 255), Color.argb(a(120), 255, 255, 255)),
            floatArrayOf(0f, 0.38f, 0.62f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(path, rimPaint)
    }

    /** Convenience: squircle glass for a rect. */
    fun drawRect(canvas: Canvas, view: View, rect: RectF, radius: Float, tint: Int = DEFAULT_TINT, shadow: Boolean = true, path: Path = Path()) {
        IosShape.roundRect(rect, radius, path)
        draw(canvas, view, path, rect, radius, tint, shadow)
    }

    /** Invalidate every view below [v] so glass re-samples the wallpaper at its new position. */
    fun invalidateTree(v: View) {
        v.invalidate()
        if (v is ViewGroup) for (i in 0 until v.childCount) invalidateTree(v.getChildAt(i))
    }

    val DEFAULT_TINT = Color.argb(26, 255, 255, 255)

    private const val AGSL = """
        uniform shader wall;
        uniform float2 viewOrigin;
        uniform float4 rect;
        uniform float radius;
        uniform float bezel;
        uniform float strength;

        float sdf(float2 p, float2 b, float r) {
            float2 q = abs(p) - b + float2(r, r);
            return length(max(q, float2(0.0, 0.0))) + min(max(q.x, q.y), 0.0) - r;
        }

        half4 main(float2 coord) {
            float2 c = float2((rect.x + rect.z) * 0.5, (rect.y + rect.w) * 0.5);
            float2 b = float2((rect.z - rect.x) * 0.5, (rect.w - rect.y) * 0.5);
            float r = min(radius, min(b.x, b.y));
            float2 p = coord - c;
            float d = sdf(p, b, r);
            float t = clamp(1.0 + d / bezel, 0.0, 1.0);
            float dx = sdf(p + float2(1.0, 0.0), b, r) - sdf(p - float2(1.0, 0.0), b, r);
            float dy = sdf(p + float2(0.0, 1.0), b, r) - sdf(p - float2(0.0, 1.0), b, r);
            float2 n = float2(dx, dy);
            n = n / max(length(n), 0.0001);
            float bend = t * t * t * strength;
            float2 s = viewOrigin + coord - n * bend;
            float ca = bend * 0.10;
            half4 col = half4(wall.eval(s - n * ca).r, wall.eval(s).g, wall.eval(s + n * ca).b, 1.0);
            col.rgb = col.rgb + half3(0.06 * t * t);
            return col;
        }
    """
}

/** Glass background for any view (pills, menus, alerts, panels, cards). */
class GlassDrawable(private val radius: Float, var glassTint: Int = Glass.DEFAULT_TINT, private val shadow: Boolean = false) : Drawable() {
    private val path = Path()
    private val rect = RectF()
    override fun draw(canvas: Canvas) {
        val v = callback as? View ?: return
        rect.set(bounds)
        Glass.drawRect(canvas, v, rect, radius, glassTint, shadow, path)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
