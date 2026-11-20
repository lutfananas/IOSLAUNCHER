package com.ioslauncher.icons

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.ioslauncher.model.AppInfo
import com.ioslauncher.model.Prefs
import java.util.concurrent.Executors

/**
 * Resolves the icon for an app (icon pack → icon pack back/mask → system icon) and renders it
 * into the exact iOS squircle at the requested pixel size.
 */
class IconProvider(private val context: Context, private val prefs: Prefs) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount / 1024
    }
    private var pack: IconPack? = null
    private var packPkg: String? = null
    @Volatile var generation = 0; private set
    private val listeners = mutableListOf<() -> Unit>()

    fun addChangeListener(l: () -> Unit) { listeners += l }
    fun removeChangeListener(l: () -> Unit) { listeners -= l }

    /** Call after the icon pack or icon options changed. */
    fun invalidate() {
        synchronized(this) { pack = null; packPkg = null }
        cache.evictAll()
        generation++
        listeners.toList().forEach { it() }
    }

    fun invalidatePackage(pkg: String) {
        cache.snapshot().keys.filter { it.contains(pkg) }.forEach { cache.remove(it) }
    }

    @Synchronized
    private fun currentPack(): IconPack? {
        val want = prefs.iconPack
        if (want != packPkg) {
            packPkg = want
            pack = if (want.isEmpty()) null else runCatching { IconPack(context, want) }.getOrNull()
        }
        return pack
    }

    fun cached(app: AppInfo, size: Int): Bitmap? = cache.get("${app.key}@$size")

    fun load(app: AppInfo, size: Int, callback: (Bitmap) -> Unit) {
        cached(app, size)?.let { callback(it); return }
        val gen = generation
        executor.execute {
            val bmp = runCatching { render(app, size) }.getOrElse { placeholder(size) }
            if (gen == generation) cache.put("${app.key}@$size", bmp)
            main.post { callback(bmp) }
        }
    }

    fun loadSync(app: AppInfo, size: Int): Bitmap =
        cached(app, size) ?: runCatching { render(app, size) }.getOrElse { placeholder(size) }.also {
            cache.put("${app.key}@$size", it)
        }

    private fun systemIcon(app: AppInfo): Drawable? {
        val list = runCatching { launcherApps.getActivityList(app.packageName, app.user) }.getOrNull()
        val info = list?.firstOrNull { it.componentName == app.component } ?: list?.firstOrNull()
        return info?.getIcon(DENSITY) ?: runCatching { context.packageManager.getApplicationIcon(app.packageName) }.getOrNull()
    }

    private val packCache = HashMap<String, IconPack?>()

    @Synchronized
    fun pack(pkg: String): IconPack? = packCache.getOrPut(pkg) { runCatching { IconPack(context, pkg) }.getOrNull() }

    /** Bitmap for a custom icon spec ("file:..." or "pack:pkg/name"), unshaped, [size]×[size]. */
    fun customRaw(spec: String, size: Int): Bitmap? = runCatching {
        when {
            spec.startsWith("file:") -> {
                val src = android.graphics.BitmapFactory.decodeFile(spec.removePrefix("file:")) ?: return null
                val side = minOf(src.width, src.height)
                val crop = Rect((src.width - side) / 2, (src.height - side) / 2, (src.width + side) / 2, (src.height + side) / 2)
                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
                    Canvas(it).drawBitmap(src, crop, Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG))
                }
            }
            spec.startsWith("pack:") -> {
                val rest = spec.removePrefix("pack:")
                val slash = rest.indexOf('/')
                val d = pack(rest.substring(0, slash))?.load(rest.substring(slash + 1), DENSITY) ?: return null
                drawToBitmap(d, size)
            }
            else -> null
        }
    }.getOrNull()

    private fun render(app: AppInfo, size: Int): Bitmap {
        val pack = currentPack()
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val shape = IosShape.iconPath(size.toFloat())

        // 1. Per-app custom icon (own image or a chosen icon-pack icon), always fitted to the iOS squircle.
        prefs.customIcon(app.key)?.let { spec ->
            customRaw(spec, size)?.let { raw -> drawNormalized(canvas, raw, shape, size); return badge(app, out) }
        }

        val packDrawable = pack?.drawableFor(app.component, DENSITY)
        if (packDrawable != null) {
            val raw = drawToBitmap(packDrawable, size)
            if (prefs.normalizePackIcons) drawNormalized(canvas, raw, shape, size) else canvas.drawBitmap(raw, 0f, 0f, null)
            return badge(app, out)
        }

        val sys = systemIcon(app) ?: return placeholder(size)
        if (pack != null && pack.hasFallbackMask) {
            val raw = applyPackMask(pack, app, sys, size)
            drawNormalized(canvas, raw, shape, size)
            return badge(app, out)
        }

        drawSystemIcon(canvas, sys, shape, size)
        return badge(app, out)
    }

    private fun drawSystemIcon(canvas: Canvas, d: Drawable, shape: Path, size: Int) {
        if (d is AdaptiveIconDrawable) {
            // Adaptive icons: 108-unit layers, inner 72 units visible. Scale so the visible area fills the squircle.
            canvas.save()
            canvas.clipPath(shape)
            val inset = size / 4
            val bounds = Rect(-inset, -inset, size + inset, size + inset)
            val bg = d.background
            if (bg != null) { bg.bounds = bounds; bg.draw(canvas) } else canvas.drawColor(Color.WHITE)
            d.foreground?.let { it.bounds = bounds; it.draw(canvas) }
            canvas.restore()
            return
        }
        val raw = drawToBitmap(d, size)
        val box = contentBounds(raw)
        val fullBleed = box != null && box.width() >= size * 0.9f && box.height() >= size * 0.9f &&
            alphaAt(raw, box.left + box.width() / 2, box.top + 2) > 200 &&
            alphaAt(raw, box.left + 2, box.top + box.height() / 2) > 200
        if (fullBleed) {
            drawNormalized(canvas, raw, shape, size)
        } else {
            // Legacy (non-square) icon: put it on a white tile like iOS does for un-shaped artwork.
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            canvas.drawPath(shape, paint)
            if (box != null) {
                val target = size * 0.70f
                val s = target / maxOf(box.width(), box.height())
                val w = box.width() * s; val h = box.height() * s
                val dst = RectF((size - w) / 2f, (size - h) / 2f, (size + w) / 2f, (size + h) / 2f)
                canvas.save(); canvas.clipPath(shape)
                canvas.drawBitmap(raw, box, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                canvas.restore()
            }
        }
    }

    /** Scale the opaque content of [raw] to fill the squircle exactly, then clip to the squircle. */
    private fun drawNormalized(canvas: Canvas, raw: Bitmap, shape: Path, size: Int) {
        val box = contentBounds(raw) ?: Rect(0, 0, size, size)
        canvas.save()
        canvas.clipPath(shape)
        // Fill with the artwork's edge colour so round/odd-shaped pack icons don't leave transparent corners.
        val edge = raw.getPixel((box.left + 3).coerceAtMost(size - 1), box.centerY())
        if (Color.alpha(edge) > 200) canvas.drawColor(edge or 0xFF000000.toInt())
        canvas.drawBitmap(raw, box, Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        canvas.restore()
    }

    private fun applyPackMask(pack: IconPack, app: AppInfo, sys: Drawable, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        pack.backFor(app.component, DENSITY)?.let { it.setBounds(0, 0, size, size); it.draw(c) }
        val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val lc = Canvas(layer)
        val s = pack.scale.coerceIn(0.2f, 1.5f)
        val inset = ((size - size * s) / 2f).toInt()
        sys.setBounds(inset, inset, size - inset, size - inset)
        sys.draw(lc)
        pack.maskName?.let { name ->
            pack.load(name, DENSITY)?.let { mask ->
                val maskBmp = drawToBitmap(mask, size)
                lc.drawBitmap(maskBmp, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
            }
        }
        c.drawBitmap(layer, 0f, 0f, null)
        pack.uponName?.let { name -> pack.load(name, DENSITY)?.let { it.setBounds(0, 0, size, size); it.draw(c) } }
        return out
    }

    private fun badge(app: AppInfo, bmp: Bitmap): Bitmap {
        if (!app.isWorkProfile) return bmp
        val badged = context.packageManager.getUserBadgedIcon(BitmapDrawable(context.resources, bmp), app.user)
        return drawToBitmap(badged, bmp.width)
    }

    private fun drawToBitmap(d: Drawable, size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return b
    }

    private fun alphaAt(b: Bitmap, x: Int, y: Int): Int =
        Color.alpha(b.getPixel(x.coerceIn(0, b.width - 1), y.coerceIn(0, b.height - 1)))

    private fun contentBounds(b: Bitmap): Rect? {
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        var l = w; var t = h; var r = -1; var bt = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((px[row + x] ushr 24) > 40) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    if (y > bt) bt = y
                }
            }
        }
        return if (r < l || bt < t) null else Rect(l, t, r + 1, bt + 1)
    }

    private fun placeholder(size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(b).drawPath(IosShape.iconPath(size.toFloat()), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8E8E93.toInt() })
        return b
    }

    companion object {
        private const val DENSITY = 640
    }
}
