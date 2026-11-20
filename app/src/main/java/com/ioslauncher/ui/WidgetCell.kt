package com.ioslauncher.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import com.ioslauncher.icons.IosShape
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Home-screen widget: an [AppWidgetHostView] aligned to the icon grid like iOS widgets, clipped to a
 * continuous-corner rectangle. Long-press → menu (or drag after moving); in edit mode it jiggles, has
 * a "−" badge and left/right/bottom handles that resize it cell by cell.
 */
@SuppressLint("ViewConstructor")
class WidgetCell(context: Context, private val host: LauncherHost, val key: String, val hostView: AppWidgetHostView) :
    FrameLayout(context), DragItem {
    var spanX = 2; private set
    var spanY = 2; private set
    private val content = RectF()
    private val path = Path()
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var editMode = false
    private var jiggle: ObjectAnimator? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f; private var downY = 0f
    private var longPressFired = false
    private var tracking = false
    private var resizeEdge = 0
    private var startSX = 1; private var startSY = 1
    private val longPress = Runnable {
        longPressFired = true
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        host.onWidgetLongPress(this)
    }

    override val itemKey: String get() = key

    init {
        clipChildren = true
        setWillNotDraw(false)
        addView(hostView)
        hostView.clipToOutline = true
        hostView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val r = radius()
                if (Build.VERSION.SDK_INT >= 33) {
                    outline.setPath(IosShape.roundRect(RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()), r))
                } else outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
    }

    private fun d() = host.dimens
    private val page get() = (parent as? HomePageView)?.pageIndex ?: 1
    private fun radius() = d().iconSize * IosShape.ICON_CORNER_RATIO * 1.25f

    fun bind(sx: Int, sy: Int) {
        if (sx != spanX || sy != spanY) { spanX = sx; spanY = sy; requestLayout() }
    }

    private fun computeContent(): RectF {
        val d = d()
        val ch = d.cellH(page)
        val left = (d.cellWidth - d.iconSize) / 2f
        val top = d.iconTopIn(page)
        content.set(left, top, left + spanX * d.cellWidth - (d.cellWidth - d.iconSize), top + spanY * ch - (ch - d.iconSize))
        return content
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val r = computeContent()
        hostView.measure(MeasureSpec.makeMeasureSpec(r.width().roundToInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(r.height().roundToInt(), MeasureSpec.EXACTLY))
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    private var lastSize = 0L

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val c = computeContent()
        hostView.layout(c.left.roundToInt(), c.top.roundToInt(), c.right.roundToInt(), c.bottom.roundToInt())
        pivotX = c.centerX(); pivotY = c.centerY()
        // Tell the widget its size in dp so it can pick the right layout.
        val dm = resources.displayMetrics.density
        val wDp = c.width() / dm; val hDp = c.height() / dm
        val packed = (wDp.toLong() shl 32) or hDp.toLong()
        if (packed != lastSize && wDp > 0 && hDp > 0) {
            lastSize = packed
            post { runCatching { hostView.updateAppWidgetSize(Bundle(), listOf(SizeF(wDp, hDp))) } }
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (host.isHiddenForDrag(key)) return
        super.dispatchDraw(canvas)
        if (!editMode) return
        val r = computeContent()
        val br = d().iconSize * 0.135f
        badgePaint.style = Paint.Style.FILL; badgePaint.color = Color.argb(235, 224, 224, 228)
        canvas.drawCircle(r.left + br * 0.45f, r.top + br * 0.45f, br, badgePaint)
        badgePaint.color = Color.argb(255, 40, 40, 42); badgePaint.strokeWidth = br * 0.22f; badgePaint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(r.left, r.top + br * 0.45f, r.left + br * 0.9f, r.top + br * 0.45f, badgePaint)
        handlePaint.color = Color.argb(235, 255, 255, 255)
        val hl = minOf(r.height() * 0.4f, d().iconSize * 0.45f); val hw = d().dp(5f)
        canvas.drawRoundRect(RectF(r.right - hw / 2, r.centerY() - hl / 2, r.right + hw / 2, r.centerY() + hl / 2), hw, hw, handlePaint)
        canvas.drawRoundRect(RectF(r.left - hw / 2, r.centerY() - hl / 2, r.left + hw / 2, r.centerY() + hl / 2), hw, hw, handlePaint)
        val bl = minOf(r.width() * 0.4f, d().iconSize * 0.45f)
        canvas.drawRoundRect(RectF(r.centerX() - bl / 2, r.bottom - hw / 2, r.centerX() + bl / 2, r.bottom + hw / 2), hw, hw, handlePaint)
    }

    fun setEditMode(edit: Boolean) {
        if (editMode == edit) return
        editMode = edit
        jiggle?.cancel(); jiggle = null; rotation = 0f
        if (edit) {
            val amp = 1.2f / kotlin.math.sqrt((spanX * spanY).coerceAtLeast(1).toFloat())
            jiggle = ObjectAnimator.ofFloat(this, ROTATION, -amp, amp).apply {
                duration = 150L + Random.nextLong(30); repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                currentPlayTime = Random.nextLong(140); start()
            }
        }
        invalidate()
    }

    override fun dragBoundsOnScreen(): Rect {
        val r = computeContent(); val loc = IntArray(2); getLocationOnScreen(loc)
        return Rect((loc[0] + r.left).toInt(), (loc[1] + r.top).toInt(), (loc[0] + r.right).toInt(), (loc[1] + r.bottom).toInt())
    }

    override fun dragSnapshot(): Bitmap? {
        if (hostView.width == 0 || hostView.height == 0) return null
        val b = Bitmap.createBitmap(hostView.width, hostView.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.clipPath(IosShape.roundRect(RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()), radius()))
        hostView.draw(c)
        return b
    }

    private fun edgeAt(x: Float, y: Float): Int {
        val r = computeContent(); val t = d().dp(22f)
        if (abs(x - r.right) < t && y in r.top..r.bottom) return 1
        if (abs(x - r.left) < t && y in r.top + d().iconSize * 0.3f..r.bottom) return -1
        if (abs(y - r.bottom) < t && x in r.left..r.right) return 2
        return 0
    }

    private fun inBadge(x: Float, y: Float): Boolean {
        val r = computeContent(); val br = d().iconSize * 0.135f
        return hypot(x - (r.left + br * 0.45f), y - (r.top + br * 0.45f)) < br * 2.2f
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; longPressFired = false; tracking = true; resizeEdge = 0
                if (!computeContent().apply { inset(-d().dp(20f), -d().dp(20f)) }.contains(ev.x, ev.y)) { tracking = false; return false }
                if (host.isEditMode) return true          // edit mode: the cell handles everything
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (longPressFired) return true             // take over after a long press (drag / menu)
                if (hypot(ev.x - downX, ev.y - downY) > slop) removeCallbacks(longPress)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (longPressFired) return true             // don't let the widget see a click after the menu opened
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!tracking) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                resizeEdge = edgeAt(event.x, event.y)
                startSX = spanX; startSY = spanY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return true
                if (resizeEdge != 0) {
                    val d = d()
                    val dx = event.x - downX; val dy = event.y - downY
                    var sx = startSX; var sy = startSY
                    when (resizeEdge) {
                        1 -> sx = startSX + (dx / d.cellWidth).roundToInt()
                        -1 -> sx = startSX - (dx / d.cellWidth).roundToInt()
                        2 -> sy = startSY + (dy / d.cellH(page)).roundToInt()
                    }
                    sx = sx.coerceIn(1, d.cols); sy = sy.coerceIn(1, d.gridRows(page))
                    if (sx != spanX || sy != spanY) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        host.onFolderResize(key, sx, sy, false)
                    }
                    return true
                }
                if (hypot(event.x - downX, event.y - downY) > slop && (host.isEditMode || longPressFired)) {
                    tracking = false
                    host.onIconDragStart(this, event.rawX, event.rawY)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (resizeEdge != 0) { host.onFolderResize(key, spanX, spanY, true); resizeEdge = 0 }
                else if (tracking && host.isEditMode && inBadge(event.x, event.y)) host.onRemoveRequested(this)
                tracking = false
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (resizeEdge != 0) { host.onFolderResize(key, spanX, spanY, true); resizeEdge = 0 }
                tracking = false
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
