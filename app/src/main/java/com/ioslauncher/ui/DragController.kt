package com.ioslauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.ioslauncher.model.LayoutStore

/** Root view. While a drag is active it steals the gesture from whatever child started it. */
@SuppressLint("ViewConstructor")
class DragLayer(context: Context, private val drag: DragController) : FrameLayout(context) {
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (drag.dragging) {
            if (!drag.cancelSent) {
                drag.cancelSent = true
                val c = MotionEvent.obtain(ev); c.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(c); c.recycle()
            }
            drag.onTouch(ev)
            return true
        }
        return super.dispatchTouchEvent(ev)
    }
}

/**
 * iOS-style dragging: neighbours shift live, edge-dwell flips pages, drop into the dock,
 * and hovering an app over another app (or a folder) for a moment makes / fills a folder.
 */
class DragController(private val a: MainActivity) {
    var dragging = false; private set
    var cancelSent = false
    var dragKey: String? = null; private set

    private var ghost: ImageView? = null
    private var offX = 0f; private var offY = 0f
    private val handler = Handler(Looper.getMainLooper())
    private var edgeDir = 0
    private var folderCandidate: String? = null
    private var folderReady = false
    private val retarget = Runnable { applyTarget() }
    private val edgeFlip = Runnable { flipPage() }
    private val folderArm = Runnable {
        folderReady = folderCandidate != null
        if (folderReady) a.root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private val isFolderDrag get() = dragKey?.let { a.layoutStore.isFolder(it) } == true

    fun start(view: View, rawX: Float, rawY: Float) {
        val item = view as? DragItem ?: return
        val key = item.itemKey ?: return
        val bmp = item.dragSnapshot() ?: return
        dragKey = key
        dragging = true
        cancelSent = false
        val r = item.dragBoundsOnScreen()
        val g = ImageView(a).apply { setImageBitmap(bmp); elevation = 20f; scaleType = ImageView.ScaleType.FIT_XY }
        a.root.addView(g, FrameLayout.LayoutParams(r.width(), r.height()))
        g.x = r.left.toFloat(); g.y = r.top.toFloat()
        offX = rawX - r.left; offY = rawY - r.top
        g.animate().scaleX(1.12f).scaleY(1.12f).alpha(0.92f).setDuration(160).start()
        ghost = g
        view.invalidate()
    }

    fun onTouch(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> move(ev.rawX, ev.rawY)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> end()
        }
    }

    private fun move(x: Float, y: Float) {
        ghost?.let { it.x = x - offX; it.y = y - offY }
        val zone = a.dimens.pt(24f)
        val dir = when { x < zone -> -1; x > a.dimens.width - zone -> 1; else -> 0 }
        if (dir != edgeDir) {
            edgeDir = dir
            handler.removeCallbacks(edgeFlip)
            if (dir != 0) handler.postDelayed(edgeFlip, 550)
        }
        handler.removeCallbacks(retarget)
        handler.postDelayed(retarget, 90)
    }

    private fun flipPage() {
        val dir = edgeDir
        if (!dragging || dir == 0) return
        val layout = a.layoutStore
        val target = a.pager.currentPage + dir
        if (dir > 0 && target >= layout.pages.size) {
            val last = layout.pages.last()
            if (last.isEmpty() || (last.size == 1 && last[0] == dragKey)) return
            layout.pages += mutableListOf<String>()
            a.applyLayout(false)
        }
        if (target in 0 until layout.pages.size) { setCandidate(null); a.pager.snapTo(target) }
        handler.postDelayed(edgeFlip, 900)
    }

    private fun setCandidate(key: String?) {
        if (folderCandidate == key) return
        folderCandidate?.let { highlight(it, false) }
        folderCandidate = key
        folderReady = false
        handler.removeCallbacks(folderArm)
        if (key != null) { highlight(key, true); handler.postDelayed(folderArm, 380) }
    }

    private fun highlight(key: String, on: Boolean) {
        when (val v = a.viewForKey(key)) {
            is AppIconView -> v.setDropHighlight(on)
            is FolderView -> v.setDropHighlight(on)
        }
    }

    private fun applyTarget() {
        val key = dragKey ?: return
        if (!dragging) return
        val g = ghost ?: return
        val d = a.dimens
        val cx = g.x + g.width / 2f
        val cy = g.y + g.height / 2f
        val layout = a.layoutStore
        val overDock = a.dockAvailable && cy > d.dockTop - d.pt(8f)
        if (overDock) {
            setCandidate(null)
            if (isFolderDrag) { a.dock.phantomSlots = 0; return }
            val cur = layout.dock.indexOf(key)
            if (cur < 0 && layout.dock.size >= LayoutStore.DOCK_MAX) return
            val slots = if (cur >= 0) layout.dock.size else layout.dock.size + 1
            val idx = a.dock.indexAt(cx, slots).coerceAtMost(slots - 1)
            if (idx == cur) return
            layout.detach(key)
            layout.dock.add(idx.coerceAtMost(layout.dock.size), key)
            a.applyLayout(true)
            return
        }
        val page = a.pager.currentPage
        if (page >= layout.pages.size) return
        val pv = a.homePages[page]

        // Hovering over another item's icon → folder candidate (apps only can go into folders).
        if (!isFolderDrag) {
            val over = pv.itemAtIconCenter(cx, cy, d.iconSize * 0.42f)?.takeIf { it != key }
            if (over != null) { setCandidate(over); return }
        }
        setCandidate(null)

        if (layout.free) { applyFree(key, page, pv, cx, cy); return }

        val list = layout.pages[page]
        val cur = list.indexOf(key)
        val count = if (cur >= 0) list.size - 1 else list.size
        val idx = pv.indexAt(cx, cy, count, key)
        if (idx == cur) return
        layout.detach(key)
        list.add(idx.coerceAtMost(list.size), key)
        layout.normalize(removeEmpty = false)
        a.applyLayout(true)
    }

    /** Free placement: drop exactly on the cell under the finger; empty cells stay empty. */
    private fun applyFree(key: String, page: Int, pv: HomePageView, cx: Float, cy: Float) {
        val d = a.dimens
        val layout = a.layoutStore
        val (sx, sy) = layout.spanOf(key, page)
        val cell = pv.cellAt(cx - (sx - 1) * d.cellWidth / 2f, cy - (sy - 1) * d.cellHeight / 2f)
        val col = cell.x.coerceIn(0, d.cols - sx)
        val row = cell.y.coerceIn(0, layout.rowsFor(page) - sy)
        val curPage = layout.pageOf(key)
        val curPos = layout.positions[key]
        if (curPage == page && curPos != null && curPos.x == col && curPos.y == row) return
        val rect = android.graphics.Rect(col, row, col + sx, row + sy)
        if (layout.isFree(page, rect, key)) {
            layout.place(key, page, col, row)
            a.applyLayout(true)
            return
        }
        // Occupied by a single 1×1 icon: swap places (same page only).
        val occ = layout.itemAtCell(page, col, row) ?: return
        if (sx == 1 && sy == 1 && layout.spanOf(occ, page) == (1 to 1) && curPage == page && curPos != null) {
            layout.positions[occ] = android.graphics.Point(curPos)
            layout.positions[key] = android.graphics.Point(col, row)
            a.applyLayout(true)
        }
    }

    private fun end() {
        handler.removeCallbacks(retarget)
        handler.removeCallbacks(edgeFlip)
        handler.removeCallbacks(folderArm)
        val key = dragKey
        val layout = a.layoutStore
        var mergedInto: String? = null
        val candidate = folderCandidate
        if (key != null && candidate != null && folderReady && !layout.isFolder(key)) {
            mergedInto = if (layout.isFolder(candidate)) {
                layout.addToFolder(candidate, key); candidate
            } else {
                val name = a.folderNameFor(candidate, key)
                layout.createFolder(candidate, key, name)?.id
            }
        } else if (candidate == null) {
            applyTarget()
        }
        setCandidate(null)
        edgeDir = 0
        dragging = false
        a.dock.phantomSlots = 0
        val g = ghost
        ghost = null
        val finish: () -> Unit = {
            if (g != null) a.root.removeView(g)
            dragKey = null
            layout.normalize()
            layout.save()
            a.applyLayout(true)
            if (a.pager.currentPage >= a.homePages.size) a.pager.snapTo(a.homePages.size - 1)
            (a.viewForKey(mergedInto ?: key))?.invalidate()
        }
        if (mergedInto != null) {
            // Ghost shrinks into the folder.
            a.applyLayout(true)
            val target = a.viewForKey(mergedInto) as? DragItem
            if (g != null && target != null) {
                (target as View).post {
                    val r = target.dragBoundsOnScreen()
                    g.animate().x(r.centerX() - g.width / 2f).y(r.centerY() - g.height / 2f).scaleX(0.3f).scaleY(0.3f).alpha(0f)
                        .setDuration(240).setInterpolator(HomePageView.EASE).withEndAction { finish() }.start()
                }
            } else finish()
            return
        }
        val target = key?.let { a.viewForKey(it) } as? DragItem
        if (g == null || target == null) { finish(); return }
        (target as View).post {
            val r = target.dragBoundsOnScreen()
            g.animate().x(r.left.toFloat()).y(r.top.toFloat()).scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(260).setInterpolator(HomePageView.EASE).withEndAction { finish() }.start()
        }
    }
}
