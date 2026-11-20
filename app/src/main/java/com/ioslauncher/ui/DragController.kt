package com.ioslauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
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

/** iOS-style icon dragging: neighbours shift live, edge-dwell flips pages, drop into the dock. */
class DragController(private val a: MainActivity) {
    var dragging = false; private set
    var cancelSent = false
    var dragKey: String? = null; private set

    private var ghost: ImageView? = null
    private var offX = 0f; private var offY = 0f
    private var lastX = 0f; private var lastY = 0f
    private val handler = Handler(Looper.getMainLooper())
    private var edgeDir = 0
    private val retarget = Runnable { applyTarget() }
    private val edgeFlip = Runnable { flipPage() }

    fun start(view: AppIconView, rawX: Float, rawY: Float) {
        val app = view.app ?: return
        val bmp = view.bitmap ?: return
        dragKey = app.key
        dragging = true
        cancelSent = false
        val r = view.iconBoundsOnScreen()
        val g = ImageView(a).apply { setImageBitmap(bmp); elevation = 20f }
        a.root.addView(g, FrameLayout.LayoutParams(r.width(), r.height()))
        g.x = r.left.toFloat(); g.y = r.top.toFloat()
        offX = rawX - r.left; offY = rawY - r.top
        lastX = rawX; lastY = rawY
        g.animate().scaleX(1.15f).scaleY(1.15f).setDuration(160).start()
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
        lastX = x; lastY = y
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
        if (target in 0 until layout.pages.size) a.pager.snapTo(target)
        handler.postDelayed(edgeFlip, 900)
    }

    private fun applyTarget() {
        val key = dragKey ?: return
        if (!dragging) return
        val g = ghost ?: return
        val d = a.dimens
        val cx = g.x + g.width / 2f
        val cy = g.y + g.height / 2f
        val layout = a.layoutStore
        if (cy > d.dockTop - d.pt(8f)) {
            val cur = layout.dock.indexOf(key)
            if (cur < 0 && layout.dock.size >= LayoutStore.DOCK_MAX) return
            val slots = if (cur >= 0) layout.dock.size else layout.dock.size + 1
            val idx = a.dock.indexAt(cx, slots).coerceAtMost(slots - 1)
            if (idx == cur) return
            layout.detach(key)
            layout.dock.add(idx.coerceAtMost(layout.dock.size), key)
        } else {
            val page = a.pager.currentPage
            if (page >= layout.pages.size) return
            val list = layout.pages[page]
            val cur = list.indexOf(key)
            val count = if (cur >= 0) list.size - 1 else list.size
            val idx = a.homePages[page].indexAt(cx, cy, count)
            if (idx == cur) return
            if (cur < 0 && list.size >= layout.capacity(page) && idx >= layout.capacity(page)) return
            layout.detach(key)
            list.add(idx.coerceAtMost(list.size), key)
            layout.normalize(removeEmpty = false)
        }
        a.applyLayout(true)
    }

    private fun end() {
        handler.removeCallbacks(retarget)
        handler.removeCallbacks(edgeFlip)
        applyTarget()
        edgeDir = 0
        dragging = false
        val key = dragKey
        val g = ghost
        ghost = null
        val finish: () -> Unit = {
            if (g != null) a.root.removeView(g)
            dragKey = null
            a.layoutStore.normalize()
            a.layoutStore.save()
            a.applyLayout(false)
            if (a.pager.currentPage >= a.homePages.size) a.pager.snapTo(a.homePages.size - 1)
            a.viewForKey(key)?.invalidate()
            Unit
        }
        val target = key?.let { a.viewForKey(it) }
        if (g == null || target == null) { finish(); return }
        target.post {
            val r = target.iconBoundsOnScreen()
            g.animate().x(r.left.toFloat()).y(r.top.toFloat()).scaleX(1f).scaleY(1f)
                .setDuration(260).setInterpolator(HomePageView.EASE).withEndAction { finish() }.start()
        }
    }
}
