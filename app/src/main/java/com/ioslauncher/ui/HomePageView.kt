package com.ioslauncher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import com.ioslauncher.LauncherApp

/**
 * One home screen page: a cols × rows grid (user configurable up to 8×8). Items flow in order; folders
 * can span several cells. Page 1 reserves its first row for the clock / status cluster (top-right).
 */
class HomePageView(context: Context, private val host: LauncherHost, var pageIndex: Int) : ViewGroup(context) {
    private val views = LinkedHashMap<String, View>()
    var keys: List<String> = emptyList(); private set
    private var placements: List<Rect?> = emptyList()
    private val previous = HashMap<String, Point>()
    private var animateNext = false
    var statusView: StatusClusterView? = null; private set

    init {
        clipChildren = false
        clipToPadding = false
        ensureStatusView()
    }

    private val layout get() = LauncherApp.instance.layout
    private val rowOffset get() = if (pageIndex == 0) 1 else 0

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

    val iconViews: Collection<AppIconView> get() = views.values.filterIsInstance<AppIconView>()
    val itemViews: Collection<View> get() = views.values
    val folderViews: Collection<FolderView> get() = views.values.filterIsInstance<FolderView>()

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
        val shown = ArrayList<String>()
        for (k in newKeys) {
            val folder = layout.folders[k]
            if (folder != null) {
                val v = views.getOrPut(k) { FolderView(context, host).also { addView(it) } } as FolderView
                val (sx, sy) = layout.spanOf(k, pageIndex)
                v.bind(folder, sx, sy)
                v.setEditMode(host.isEditMode)
                shown += k
                continue
            }
            val app = repo.find(k) ?: continue
            val v = views.getOrPut(k) { AppIconView(context, host, IconSource.HOME).also { addView(it) } } as AppIconView
            v.showLabel = host.dimens.showLabels
            v.bind(app)
            v.setEditMode(host.isEditMode)
            shown += k
        }
        keys = shown
        placements = layout.placements(pageIndex, keys)
        requestLayout()
        invalidate()
    }

    fun viewFor(key: String): View? = views[key]

    fun setEditMode(edit: Boolean) = views.values.forEach {
        when (it) { is AppIconView -> it.setEditMode(edit); is FolderView -> it.setEditMode(edit) }
    }

    fun reloadIcons() = views.values.forEach { if (it is AppIconView) it.reloadIcon() else it.invalidate() }

    private fun cellOrigin(col: Int, row: Int): Point {
        val d = host.dimens
        return Point(d.sidePadding + col * d.cellWidth, d.gridTop + (row + rowOffset) * d.cellHeight)
    }

    /** Item whose icon centre is near (x, y) — used to form folders by hovering. */
    fun itemAtIconCenter(x: Float, y: Float, radius: Float): String? {
        val d = host.dimens
        keys.forEachIndexed { i, k ->
            val p = placements.getOrNull(i) ?: return@forEachIndexed
            val o = cellOrigin(p.left, p.top)
            val v = views[k]
            if (v is FolderView) {
                val r = RectF(o.x + (d.cellWidth - d.iconSize) / 2f, o.y + d.iconTopInCell,
                    o.x + p.width() * d.cellWidth - (d.cellWidth - d.iconSize) / 2f, o.y + p.height() * d.cellHeight - (d.cellHeight - d.iconSize - d.iconTopInCell))
                if (r.contains(x, y)) return k
            } else {
                val cx = o.x + d.cellWidth / 2f; val cy = o.y + d.iconTopInCell + d.iconSize / 2f
                if (kotlin.math.hypot(x - cx, y - cy) < radius) return k
            }
        }
        return null
    }

    /** Insertion index for a drop at (x, y), in this view's coordinates. */
    fun indexAt(x: Float, y: Float, itemCount: Int, excludeKey: String?): Int {
        val d = host.dimens
        val col = ((x - d.sidePadding) / d.cellWidth).toInt().coerceIn(0, d.cols - 1)
        val row = ((y - d.gridTop) / d.cellHeight).toInt().coerceIn(rowOffset, d.rows - 1) - rowOffset
        val others = keys.filter { it != excludeKey }
        val pl = layout.placements(pageIndex, others)
        others.forEachIndexed { i, _ -> val p = pl[i] ?: return@forEachIndexed; if (p.contains(col, row) || (col >= p.left && col < p.right && row >= p.top && row < p.bottom)) return i }
        val cell = row * d.cols + col
        var idx = 0
        others.forEachIndexed { i, _ -> val p = pl[i] ?: return@forEachIndexed; if (p.top * d.cols + p.left < cell) idx = i + 1 }
        return idx.coerceIn(0, itemCount)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val d = host.dimens
        keys.forEachIndexed { i, k ->
            val p = placements.getOrNull(i) ?: Rect(0, 0, 1, 1)
            views[k]?.measure(MeasureSpec.makeMeasureSpec(d.cellWidth * p.width(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(d.cellHeight * p.height(), MeasureSpec.EXACTLY))
        }
        statusView?.measure(MeasureSpec.makeMeasureSpec(d.cellWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(d.cellHeight, MeasureSpec.EXACTLY))
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val d = host.dimens
        val top = d.iconTopInCell
        statusView?.let { sv ->
            val o = Point(d.sidePadding + (d.cols - 1) * d.cellWidth, d.gridTop)
            sv.glyphSize = d.iconSize
            sv.iconTop = top
            sv.labelTextSize = d.labelTextSize
            sv.showLabel = d.showLabels
            // Raised by a quarter icon above the first-row cell.
            val lift = d.iconSize / 4
            sv.layout(o.x, o.y - lift, o.x + d.cellWidth, o.y + d.cellHeight - lift)
        }
        keys.forEachIndexed { index, key ->
            val v = views[key] ?: return@forEachIndexed
            val p = placements.getOrNull(index)
            if (p == null) { v.layout(0, 0, 0, 0); return@forEachIndexed }
            if (v is AppIconView) {
                if (v.iconSize != d.iconSize) { v.iconSize = d.iconSize; v.reloadIcon() }
                v.iconTop = top
                v.pivotX = d.cellWidth / 2f
                v.pivotY = top + d.iconSize / 2f
            }
            val o = cellOrigin(p.left, p.top)
            v.layout(o.x, o.y, o.x + d.cellWidth * p.width(), o.y + d.cellHeight * p.height())
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

/**
 * iOS dock on Liquid Glass. The glass panel hugs its icons: remove one and the dock shrinks
 * (animated) to fit the remaining ones.
 */
class DockView(context: Context, private val host: LauncherHost) : ViewGroup(context) {
    private val views = LinkedHashMap<String, AppIconView>()
    var keys: List<String> = emptyList(); private set
    private val previous = HashMap<String, Int>()
    private var animateNext = false
    private val path = Path()
    private val rect = RectF()
    private var bgWidth = -1f
    private var bgAnim: ValueAnimator? = null
    /** Extra empty slot shown while an icon is dragged over the dock. */
    var phantomSlots = 0
        set(v) { if (field != v) { field = v; requestLayout(); animateBg() } }

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
        val shown = ArrayList<String>()
        for (k in newKeys) {
            val app = repo.find(k) ?: continue
            val v = views.getOrPut(k) { AppIconView(context, host, IconSource.DOCK).also { addView(it) } }
            v.showLabel = false
            v.bind(app)
            v.setEditMode(host.isEditMode)
            shown += k
        }
        keys = shown
        requestLayout()
        if (animate) animateBg() else { bgWidth = targetBgWidth(); invalidate() }
    }

    fun viewFor(key: String) = views[key]
    fun setEditMode(edit: Boolean) = views.values.forEach { it.setEditMode(edit) }
    fun reloadIcons() = views.values.forEach { it.reloadIcon() }

    private val slotCount get() = keys.size + phantomSlots

    private fun targetBgWidth(): Float {
        val d = host.dimens
        val n = slotCount
        return if (n == 0) 0f else (n * d.dockSlot + 2 * d.dockPad).toFloat().coerceAtMost(width - 2f * d.pt(12f))
    }

    private fun animateBg() {
        val target = targetBgWidth()
        if (bgWidth < 0 || width == 0) { bgWidth = target; invalidate(); return }
        bgAnim?.cancel()
        bgAnim = ValueAnimator.ofFloat(bgWidth, target).apply {
            duration = 340; interpolator = HomePageView.EASE
            addUpdateListener { bgWidth = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun slotLeft(index: Int, count: Int): Int {
        val d = host.dimens
        val start = (width - count * d.dockSlot) / 2
        return start + index * d.dockSlot
    }

    fun indexAt(x: Float, count: Int): Int {
        val d = host.dimens
        val start = (width - count.coerceAtLeast(1) * d.dockSlot) / 2f
        return ((x - start) / d.dockSlot).toInt().coerceIn(0, count)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val d = host.dimens
        val cw = MeasureSpec.makeMeasureSpec(d.dockSlot, MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec(d.dockHeight, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val d = host.dimens
        if (bgWidth < 0) bgWidth = targetBgWidth()
        val top = (height - d.dockIconSize) / 2f
        val count = slotCount
        keys.forEachIndexed { index, key ->
            val v = views[key] ?: return@forEachIndexed
            if (v.iconSize != d.dockIconSize) { v.iconSize = d.dockIconSize; v.reloadIcon() }
            v.iconTop = top
            val x = slotLeft(index, count)
            v.layout(x, 0, x + d.dockSlot, height)
            v.pivotX = d.dockSlot / 2f; v.pivotY = height / 2f
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

    override fun onDraw(canvas: Canvas) {
        val w = bgWidth
        if (w <= 1f) return
        val d = host.dimens
        rect.set((width - w) / 2f, 0f, (width + w) / 2f, height.toFloat())
        val radius = minOf(d.dockRadius, height / 2f)
        Glass.drawRect(canvas, this, rect, radius, Color.argb(22, 255, 255, 255), true, path)
    }
}

/** Page dots on a small Liquid Glass capsule. Fades out as the App Library slides in. */
class PageIndicator(context: Context) : View(context) {
    var count = 1; set(v) { field = v; invalidate() }
    var position = 0f; set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dm = context.resources.displayMetrics.density
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        if (count <= 1) return
        val r = 3.4f * dm
        val gap = 14f * dm
        val total = (count - 1) * gap
        val start = width / 2f - total / 2f
        val capH = 20f * dm
        val cap = RectF(start - 11f * dm, height / 2f - capH / 2, start + total + 11f * dm, height / 2f + capH / 2)
        Glass.drawRect(canvas, this, cap, capH / 2, Color.argb(20, 255, 255, 255), false, path)
        for (i in 0 until count) {
            val dist = kotlin.math.abs(position - i).coerceAtMost(1f)
            paint.color = Color.WHITE
            paint.alpha = (255 * (1f - 0.6f * dist)).toInt()
            canvas.drawCircle(start + i * gap, height / 2f, r, paint)
        }
    }
}
