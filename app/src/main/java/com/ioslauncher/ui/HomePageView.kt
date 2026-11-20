package com.ioslauncher.ui

import android.content.Context
import android.graphics.Point
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import com.ioslauncher.LauncherApp
import com.ioslauncher.model.LayoutStore

/** One home screen page: 4×6 grid filled in order. Page 1 reserves row 1 for the status cluster (top-right). */
class HomePageView(context: Context, private val host: LauncherHost, var pageIndex: Int) : ViewGroup(context) {
    private val views = LinkedHashMap<String, AppIconView>()
    var keys: List<String> = emptyList(); private set
    private val previous = HashMap<String, Point>()
    private var animateNext = false
    var statusView: StatusClusterView? = null; private set

    init {
        clipChildren = false
        clipToPadding = false
        ensureStatusView()
    }

    private fun ensureStatusView() {
        if (pageIndex == 0 && statusView == null) {
            statusView = StatusClusterView(context).also { addView(it) }
        } else if (pageIndex != 0 && statusView != null) {
            removeView(statusView); statusView = null
        }
    }

    fun updatePageIndex(index: Int) {
        if (pageIndex != index) { pageIndex = index; ensureStatusView(); requestLayout() }
    }

    val iconViews: Collection<AppIconView> get() = views.values

    fun setItems(newKeys: List<String>, animate: Boolean) {
        val repo = LauncherApp.instance.repository
        if (animate) {
            previous.clear()
            for ((k, v) in views) previous[k] = Point(v.left + v.translationX.toInt(), v.top + v.translationY.toInt())
            animateNext = true
        }
        val keep = newKeys.toSet()
        val it = views.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in keep) { removeView(e.value); it.remove() }
        }
        for (k in newKeys) {
            val app = repo.find(k) ?: continue
            val v = views.getOrPut(k) { AppIconView(context, host, IconSource.HOME).also { addView(it) } }
            v.showLabel = LauncherApp.instance.prefs.showLabels
            v.bind(app)
            v.setEditMode(host.isEditMode)
        }
        keys = newKeys.toList()
        requestLayout()
        invalidate()
    }

    fun viewFor(key: String): AppIconView? = views[key]

    fun setEditMode(edit: Boolean) = views.values.forEach { it.setEditMode(edit) }

    fun reloadIcons() = views.values.forEach { it.reloadIcon(); it.showLabel = LauncherApp.instance.prefs.showLabels; it.invalidate() }

    private fun cellOrigin(cell: Int): Point {
        val d = host.dimens
        val col = cell % LayoutStore.COLS
        val row = cell / LayoutStore.COLS
        return Point(d.sidePadding + col * d.cellWidth, d.gridTop + row * d.cellHeight)
    }

    private fun cellFor(index: Int) = if (pageIndex == 0) index + LayoutStore.COLS else index

    /** Position of an item index (center of its icon) in this view's coordinates. */
    fun iconCenter(index: Int): Point {
        val d = host.dimens
        val o = cellOrigin(cellFor(index))
        return Point(o.x + d.cellWidth / 2, o.y + iconTop().toInt() + d.iconSize / 2)
    }

    private fun iconTop(): Float {
        val d = host.dimens
        val block = if (d.showLabels) d.iconSize + d.labelGap + d.labelTextSize * 1.2f else d.iconSize.toFloat()
        return ((d.cellHeight - block) / 2f).coerceAtLeast(0f)
    }

    /** Insertion index for a drop at (x, y), in this view's coordinates. */
    fun indexAt(x: Float, y: Float, itemCount: Int): Int {
        val d = host.dimens
        val col = ((x - d.sidePadding) / d.cellWidth).toInt().coerceIn(0, LayoutStore.COLS - 1)
        val row = ((y - d.gridTop) / d.cellHeight).toInt().coerceIn(0, LayoutStore.ROWS - 1)
        var idx = row * LayoutStore.COLS + col
        if (pageIndex == 0) idx -= LayoutStore.COLS
        return idx.coerceIn(0, itemCount)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val d = host.dimens
        val cw = MeasureSpec.makeMeasureSpec(d.cellWidth, MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec(d.cellHeight, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val d = host.dimens
        val top = iconTop()
        statusView?.let { sv ->
            val o = cellOrigin(LayoutStore.COLS - 1)
            sv.glyphSize = d.iconSize
            sv.iconTop = top
            sv.labelTextSize = d.labelTextSize
            sv.showLabel = LauncherApp.instance.prefs.showLabels
            // Raised by half an icon above the first-row cell.
            val lift = d.iconSize / 2
            sv.layout(o.x, o.y - lift, o.x + d.cellWidth, o.y + d.cellHeight - lift)
        }
        keys.forEachIndexed { index, key ->
            val v = views[key] ?: return@forEachIndexed
            if (v.iconSize != d.iconSize) { v.iconSize = d.iconSize; v.reloadIcon() }
            v.iconTop = top
            val o = cellOrigin(cellFor(index))
            v.layout(o.x, o.y, o.x + d.cellWidth, o.y + d.cellHeight)
            v.pivotX = d.cellWidth / 2f
            v.pivotY = top + d.iconSize / 2f
            if (animateNext) {
                val prev = previous[key]
                if (prev != null && (prev.x != o.x || prev.y != o.y)) {
                    v.translationX = (prev.x - o.x).toFloat()
                    v.translationY = (prev.y - o.y).toFloat()
                    v.animate().translationX(0f).translationY(0f).setDuration(320).setInterpolator(EASE).start()
                }
            }
        }
        animateNext = false
        previous.clear()
    }

    override fun shouldDelayChildPressedState() = false

    companion object {
        val EASE = PathInterpolator(0.25f, 1f, 0.5f, 1f)
    }
}

/** iOS dock: glass squircle panel with up to four label-less icons aligned to the grid columns. */
class DockView(context: Context, private val host: LauncherHost) : ViewGroup(context) {
    private val views = LinkedHashMap<String, AppIconView>()
    var keys: List<String> = emptyList(); private set
    private val previous = HashMap<String, Int>()
    private var animateNext = false
    private val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
    }
    private val path = android.graphics.Path()

    init {
        setWillNotDraw(false)
        clipChildren = false
    }

    val iconViews: Collection<AppIconView> get() = views.values

    fun setItems(newKeys: List<String>, animate: Boolean) {
        val repo = LauncherApp.instance.repository
        if (animate) {
            previous.clear()
            for ((k, v) in views) previous[k] = v.left + v.translationX.toInt()
            animateNext = true
        }
        val keep = newKeys.toSet()
        val it = views.entries.iterator()
        while (it.hasNext()) { val e = it.next(); if (e.key !in keep) { removeView(e.value); it.remove() } }
        for (k in newKeys) {
            val app = repo.find(k) ?: continue
            val v = views.getOrPut(k) { AppIconView(context, host, IconSource.DOCK).also { addView(it) } }
            v.showLabel = false
            v.bind(app)
            v.setEditMode(host.isEditMode)
        }
        keys = newKeys.toList()
        requestLayout()
    }

    fun viewFor(key: String) = views[key]
    fun setEditMode(edit: Boolean) = views.values.forEach { it.setEditMode(edit) }
    fun reloadIcons() = views.values.forEach { it.reloadIcon() }

    private fun slotLeft(index: Int, count: Int): Int {
        val d = host.dimens
        val n = count.coerceAtLeast(1)
        val totalW = n * d.cellWidth
        val start = (width - totalW) / 2
        return start + index * d.cellWidth
    }

    fun indexAt(x: Float, count: Int): Int {
        val d = host.dimens
        val n = (count).coerceAtLeast(1)
        val start = (width - n * d.cellWidth) / 2f
        return ((x - start) / d.cellWidth).toInt().coerceIn(0, count)
    }

    fun iconCenterX(index: Int, count: Int) = slotLeft(index, count) + host.dimens.cellWidth / 2

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val d = host.dimens
        val cw = MeasureSpec.makeMeasureSpec(d.cellWidth, MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec(d.dockHeight, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val d = host.dimens
        val top = (height - d.dockIconSize) / 2f
        keys.forEachIndexed { index, key ->
            val v = views[key] ?: return@forEachIndexed
            if (v.iconSize != d.dockIconSize) { v.iconSize = d.dockIconSize; v.reloadIcon() }
            v.iconTop = top
            val x = slotLeft(index, keys.size)
            v.layout(x, 0, x + d.cellWidth, height)
            v.pivotX = d.cellWidth / 2f; v.pivotY = height / 2f
            if (animateNext) {
                val prev = previous[key]
                if (prev != null && prev != x) {
                    v.translationX = (prev - x).toFloat()
                    v.animate().translationX(0f).setDuration(320).setInterpolator(HomePageView.EASE).start()
                }
            }
        }
        animateNext = false
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val d = host.dimens
        val inset = d.dockMargin.toFloat()
        val rect = android.graphics.RectF(inset, 0f, width - inset, height.toFloat())
        com.ioslauncher.icons.IosShape.roundRect(rect, d.dockRadius, path)
        bgPaint.color = android.graphics.Color.argb(70, 255, 255, 255)
        canvas.drawPath(path, bgPaint)
        borderPaint.strokeWidth = d.dp(0.8f)
        borderPaint.color = android.graphics.Color.argb(60, 255, 255, 255)
        canvas.drawPath(path, borderPaint)
    }
}

/** Page dots. Fades out as the App Library slides in. */
class PageIndicator(context: Context) : View(context) {
    var count = 1; set(v) { field = v; invalidate() }
    var position = 0f; set(v) { field = v; invalidate() }
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val dm = context.resources.displayMetrics.density

    override fun onDraw(canvas: android.graphics.Canvas) {
        if (count <= 1) return
        val r = 3.6f * dm
        val gap = 15f * dm
        val total = (count - 1) * gap
        val start = width / 2f - total / 2f
        for (i in 0 until count) {
            val dist = kotlin.math.abs(position - i).coerceAtMost(1f)
            paint.color = android.graphics.Color.WHITE
            paint.alpha = (255 * (1f - 0.62f * dist)).toInt()
            canvas.drawCircle(start + i * gap, height / 2f, r, paint)
        }
    }
}
