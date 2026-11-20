package com.ioslauncher.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import com.ioslauncher.LauncherApp
import com.ioslauncher.icons.IosShape
import com.ioslauncher.model.AppInfo
import com.ioslauncher.model.Folder
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Home-screen folder on Liquid Glass. 1×1 shows a 3×3 preview and opens on tap. Larger folders
 * (spanX × spanY cells) show the apps inline — tapping an app launches it directly.
 * In edit mode, handles on the left / right edges change the width and the bottom handle the height,
 * snapping to whole grid cells.
 */
@SuppressLint("ViewConstructor")
class FolderView(context: Context, private val host: LauncherHost) : View(context), DragItem {
    var folder: Folder? = null; private set
    var spanX = 1; private set
    var spanY = 1; private set
    private var apps: List<AppInfo> = emptyList()
    private val bitmaps = HashMap<String, Bitmap>()
    private val path = Path()
    private val bg = RectF()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, 400, false)
        setShadowLayer(4f, 0f, 1f, Color.argb(70, 0, 0, 0))
    }
    private var editMode = false
    private var jiggle: ObjectAnimator? = null
    private var dropHighlight = false

    // touch
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f; private var downY = 0f
    private var tracking = false
    private var longPressFired = false
    private var pressedSlot = -2
    private var resizeEdge = 0          // 0 none, 1 right, -1 left, 2 bottom
    private var resizeStartX = 1; private var resizeStartY = 1
    private val longPress = Runnable {
        longPressFired = true; pressedSlot = -2; invalidate()
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        host.onFolderLongPress(this)
    }

    override val itemKey: String? get() = folder?.id

    fun bind(f: Folder, sx: Int, sy: Int) {
        folder = f
        spanX = sx; spanY = sy
        contentDescription = "Folder ${f.name}"
        val repo = LauncherApp.instance.repository
        apps = f.apps.mapNotNull { repo.find(it) }
        loadIcons()
        invalidate()
    }

    private fun d() = host.dimens

    /** Glass background rect in local coordinates: spans the icon areas of the covered cells. */
    private fun computeBg(): RectF {
        val d = d()
        val left = (d.cellWidth - d.iconSize) / 2f
        val top = d.iconTopInCell
        val w = spanX * d.cellWidth - (d.cellWidth - d.iconSize)
        val h = spanY * d.cellHeight - (d.cellHeight - d.iconSize).toFloat()
        bg.set(left, top, left + w, top + h)
        return bg
    }

    private data class Grid(val cols: Int, val rows: Int, val size: Float, val gap: Float, val ox: Float, val oy: Float)

    private fun grid(): Grid {
        val r = computeBg()
        if (spanX == 1 && spanY == 1) {
            val pad = r.width() * 0.13f; val gap = r.width() * 0.055f
            val size = (r.width() - 2 * pad - 2 * gap) / 3f
            return Grid(3, 3, size, gap, r.left + pad, r.top + pad)
        }
        val target = d().iconSize * 0.62f
        val gap = d().iconSize * 0.16f
        val pad = d().iconSize * 0.16f
        val cols = ((r.width() - 2 * pad + gap) / (target + gap)).toInt().coerceAtLeast(1)
        val rows = ((r.height() - 2 * pad + gap) / (target + gap)).toInt().coerceAtLeast(1)
        val size = minOf((r.width() - 2 * pad - (cols - 1) * gap) / cols, (r.height() - 2 * pad - (rows - 1) * gap) / rows)
        val gw = cols * size + (cols - 1) * gap; val gh = rows * size + (rows - 1) * gap
        return Grid(cols, rows, size, gap, r.left + (r.width() - gw) / 2f, r.top + (r.height() - gh) / 2f)
    }

    private fun slotRect(i: Int, g: Grid): RectF {
        val c = i % g.cols; val rr = i / g.cols
        val l = g.ox + c * (g.size + g.gap); val t = g.oy + rr * (g.size + g.gap)
        return RectF(l, t, l + g.size, t + g.size)
    }

    private fun overflowing(g: Grid) = spanX * spanY > 1 && apps.size > g.cols * g.rows

    private fun loadIcons() {
        val g = grid()
        val size = g.size.roundToInt().coerceAtLeast(8)
        val icons = LauncherApp.instance.icons
        val f = folder
        apps.take(g.cols * g.rows + 4).forEach { a ->
            icons.load(a, size) { bmp -> if (folder === f) { bitmaps[a.key] = bmp; invalidate() } }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val r = computeBg(); pivotX = r.centerX(); pivotY = r.centerY()
        loadIcons()
    }

    fun setDropHighlight(on: Boolean) { if (dropHighlight != on) { dropHighlight = on; animate().scaleX(if (on) 1.08f else 1f).scaleY(if (on) 1.08f else 1f).setDuration(160).start() } }

    override fun onDraw(canvas: Canvas) {
        val f = folder ?: return
        if (host.isHiddenForDrag(f.id)) return
        val r = computeBg()
        val radius = d().iconSize * IosShape.ICON_CORNER_RATIO * (if (spanX * spanY > 1) 1.25f else 1f)
        IosShape.roundRect(r, radius, path)
        Glass.draw(canvas, this, path, r, radius, Color.argb(if (dropHighlight) 60 else 30, 255, 255, 255), shadow = true)

        val g = grid()
        val overflow = overflowing(g)
        val visible = if (overflow) g.cols * g.rows - 1 else g.cols * g.rows
        apps.take(visible).forEachIndexed { i, a ->
            val sr = slotRect(i, g)
            bitmaps[a.key]?.let { canvas.drawBitmap(it, null, sr, bmpPaint) }
            if (pressedSlot == i) canvas.drawPath(IosShape.roundRect(sr, sr.width() * IosShape.ICON_CORNER_RATIO), dimPaint)
        }
        if (overflow) {
            // Last slot: a tiny 2×2 stack of the remaining apps (tap opens the folder).
            val sr = slotRect(visible, g)
            val gap = sr.width() * 0.1f; val s = (sr.width() - gap) / 2f
            apps.drop(visible).take(4).forEachIndexed { i, a ->
                val l = sr.left + (i % 2) * (s + gap); val t = sr.top + (i / 2) * (s + gap)
                bitmaps[a.key]?.let { canvas.drawBitmap(it, null, RectF(l, t, l + s, t + s), bmpPaint) }
            }
        }

        if (d().showLabels) {
            labelPaint.textSize = d().labelTextSize
            val text = TextUtils.ellipsize(f.name, labelPaint, r.width() + d().cellWidth - d().iconSize - 4f, TextUtils.TruncateAt.END)
            canvas.drawText(text, 0, text.length, r.centerX(), r.bottom + d().labelGap - labelPaint.ascent(), labelPaint)
        }

        if (editMode) {
            val br = d().iconSize * 0.135f
            badgePaint.style = Paint.Style.FILL; badgePaint.color = Color.argb(235, 224, 224, 228)
            canvas.drawCircle(r.left + br * 0.45f, r.top + br * 0.45f, br, badgePaint)
            badgePaint.color = Color.argb(255, 40, 40, 42); badgePaint.strokeWidth = br * 0.22f; badgePaint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(r.left - br * 0.0f, r.top + br * 0.45f, r.left + br * 0.9f, r.top + br * 0.45f, badgePaint)
            // Resize handles (left, right, bottom).
            handlePaint.color = Color.argb(235, 255, 255, 255)
            val hl = minOf(r.height() * 0.4f, d().iconSize * 0.45f); val hw = d().dp(5f)
            canvas.drawRoundRect(RectF(r.right - hw / 2, r.centerY() - hl / 2, r.right + hw / 2, r.centerY() + hl / 2), hw, hw, handlePaint)
            canvas.drawRoundRect(RectF(r.left - hw / 2, r.centerY() - hl / 2, r.left + hw / 2, r.centerY() + hl / 2), hw, hw, handlePaint)
            val bl = minOf(r.width() * 0.4f, d().iconSize * 0.45f)
            canvas.drawRoundRect(RectF(r.centerX() - bl / 2, r.bottom - hw / 2, r.centerX() + bl / 2, r.bottom + hw / 2), hw, hw, handlePaint)
        }
    }

    fun setEditMode(edit: Boolean) {
        if (editMode == edit) return
        editMode = edit
        jiggle?.cancel(); jiggle = null; rotation = 0f
        if (edit) {
            val amp = (1.4f + Random.nextFloat() * 0.5f) / (spanX * spanY).coerceAtLeast(1).let { kotlin.math.sqrt(it.toFloat()) }
            jiggle = ObjectAnimator.ofFloat(this, ROTATION, -amp, amp).apply {
                duration = 140L + Random.nextLong(30); repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                currentPlayTime = Random.nextLong(130); start()
            }
        }
        invalidate()
    }

    override fun dragBoundsOnScreen(): Rect {
        val r = computeBg(); val loc = IntArray(2); getLocationOnScreen(loc)
        return Rect((loc[0] + r.left).toInt(), (loc[1] + r.top).toInt(), (loc[0] + r.right).toInt(), (loc[1] + r.bottom).toInt())
    }

    override fun dragSnapshot(): Bitmap? {
        if (width == 0 || height == 0) return null
        val r = computeBg()
        val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val wasEdit = editMode; editMode = false
        val rot = rotation; rotation = 0f
        draw(Canvas(full))
        editMode = wasEdit; rotation = rot
        return Bitmap.createBitmap(full, r.left.toInt().coerceAtLeast(0), r.top.toInt().coerceAtLeast(0),
            r.width().toInt().coerceAtMost(width - r.left.toInt()), r.height().toInt().coerceAtMost(height - r.top.toInt()))
    }

    private fun edgeAt(x: Float, y: Float): Int {
        val r = computeBg(); val t = d().dp(22f)
        if (abs(x - r.right) < t && y in r.top..r.bottom) return 1
        if (abs(x - r.left) < t && y in r.top + d().iconSize * 0.3f..r.bottom) return -1
        if (abs(y - r.bottom) < t && x in r.left..r.right) return 2
        return 0
    }

    private fun inBadge(x: Float, y: Float): Boolean {
        val r = computeBg(); val br = d().iconSize * 0.135f
        return hypot(x - (r.left + br * 0.45f), y - (r.top + br * 0.45f)) < br * 2.2f
    }

    private fun slotAt(x: Float, y: Float): Int {
        if (spanX * spanY == 1) return -1
        val g = grid()
        val n = minOf(apps.size, g.cols * g.rows)
        for (i in 0 until n) if (slotRect(i, g).contains(x, y)) return if (overflowing(g) && i == n - 1) -1 else i
        return -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val f = folder ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                longPressFired = false; tracking = true; resizeEdge = 0
                if (!computeBg().apply { inset(-d().dp(20f), -d().dp(20f)) }.contains(event.x, event.y)) { tracking = false; return false }
                if (host.isEditMode) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    resizeEdge = edgeAt(event.x, event.y)
                    resizeStartX = spanX; resizeStartY = spanY
                    if (resizeEdge == 0) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                } else {
                    pressedSlot = slotAt(event.x, event.y); invalidate()
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return true
                if (resizeEdge != 0) {
                    val d = d()
                    val dx = event.x - downX; val dy = event.y - downY
                    var sx = resizeStartX; var sy = resizeStartY
                    when (resizeEdge) {
                        1 -> sx = resizeStartX + (dx / d.cellWidth).roundToInt()
                        -1 -> sx = resizeStartX - (dx / d.cellWidth).roundToInt()
                        2 -> sy = resizeStartY + (dy / d.cellHeight).roundToInt()
                    }
                    sx = sx.coerceIn(1, d.cols); sy = sy.coerceIn(1, d.rows)
                    if (sx != spanX || sy != spanY) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        host.onFolderResize(f.id, sx, sy, false)
                    }
                    return true
                }
                if (hypot(event.x - downX, event.y - downY) > slop) {
                    removeCallbacks(longPress)
                    if (longPressFired || host.isEditMode) {
                        tracking = false
                        host.onIconDragStart(this, event.rawX, event.rawY)
                    } else { tracking = false; pressedSlot = -2; invalidate() }
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val was = tracking; tracking = false
                val slot = pressedSlot; pressedSlot = -2; invalidate()
                if (resizeEdge != 0) { host.onFolderResize(f.id, spanX, spanY, true); resizeEdge = 0; return true }
                if (!was || longPressFired) return true
                if (host.isEditMode && inBadge(event.x, event.y)) { host.onRemoveRequested(this); return true }
                if (!host.isEditMode && slot >= 0) {
                    apps.getOrNull(slot)?.let { a ->
                        val sr = slotRect(slot, grid()); val loc = IntArray(2); getLocationOnScreen(loc)
                        host.launchApp(this, a, Rect((loc[0] + sr.left).toInt(), (loc[1] + sr.top).toInt(), (loc[0] + sr.right).toInt(), (loc[1] + sr.bottom).toInt()))
                    }
                    return true
                }
                host.openFolder(f.id, dragBoundsOnScreen())
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress); tracking = false; pressedSlot = -2
                if (resizeEdge != 0) { host.onFolderResize(f.id, spanX, spanY, true); resizeEdge = 0 }
                invalidate()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        jiggle?.cancel(); jiggle = null; editMode = false
        removeCallbacks(longPress)
    }
}
