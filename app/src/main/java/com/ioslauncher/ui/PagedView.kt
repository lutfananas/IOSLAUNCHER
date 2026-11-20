package com.ioslauncher.ui

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Horizontal pager for home pages + the App Library (last child). Spring-snapped like iOS,
 * rubber-bands at the edges, and reports vertical swipes / empty-space taps and long presses.
 */
class PagedView(context: Context) : ViewGroup(context) {
    interface Callbacks {
        fun onPageScroll(position: Float)
        fun onPageSettled(page: Int)
        fun onSwipeDown(startY: Float, startX: Float)
        fun onEmptyLongPress()
        fun onEmptyTap()
        fun canScroll(): Boolean
    }

    var callbacks: Callbacks? = null
    var currentPage = 0; private set
    val pageCount get() = childCount

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 3
    private var velocity: VelocityTracker? = null
    private var downX = 0f; private var downY = 0f; private var lastX = 0f
    private var dragging = false
    private var verticalHandled = false
    private var longPressFired = false
    private val longPress = Runnable {
        if (!dragging) { longPressFired = true; performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); callbacks?.onEmptyLongPress() }
    }

    private val scrollProp = object : FloatPropertyCompat<PagedView>("scroll") {
        override fun getValue(v: PagedView) = v.scrollX.toFloat()
        override fun setValue(v: PagedView, value: Float) = v.scrollTo(value.roundToInt(), 0)
    }
    private val spring = SpringAnimation(this, scrollProp).apply {
        spring = SpringForce().setStiffness(380f).setDampingRatio(1f)
        addEndListener { _, canceled, _, _ -> if (!canceled) callbacks?.onPageSettled(currentPage) }
    }

    init { clipChildren = false }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
        val cw = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        for (i in 0 until childCount) getChildAt(i).layout(i * w, 0, (i + 1) * w, b - t)
        if (!spring.isRunning && !dragging) scrollTo(currentPage.coerceIn(0, (childCount - 1).coerceAtLeast(0)) * w, 0)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (width > 0) callbacks?.onPageScroll(l / width.toFloat())
    }

    val position: Float get() = if (width > 0) scrollX / width.toFloat() else currentPage.toFloat()

    fun snapTo(page: Int, velocityX: Float = 0f, animate: Boolean = true) {
        currentPage = page.coerceIn(0, (childCount - 1).coerceAtLeast(0))
        val target = (currentPage * width).toFloat()
        if (!animate || width == 0) {
            spring.cancel(); scrollTo(target.toInt(), 0); callbacks?.onPageSettled(currentPage); return
        }
        spring.cancel()
        spring.setStartValue(scrollX.toFloat())
        spring.setStartVelocity(velocityX)
        spring.animateToFinalPosition(target)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastX = ev.x
                dragging = false; verticalHandled = false; longPressFired = false
                velocity?.recycle(); velocity = VelocityTracker.obtain()
                velocity?.addMovement(ev)
                if (spring.isRunning) { spring.cancel(); dragging = true; return true }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                val dx = ev.x - downX; val dy = ev.y - downY
                if (callbacks?.canScroll() != false && abs(dx) > slop && abs(dx) > abs(dy) * 1.2f) {
                    dragging = true; lastX = ev.x
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                if (!verticalHandled && dy > slop * 2 && dy > abs(dx) * 1.5f && currentPage < childCount - 1) {
                    verticalHandled = true
                    callbacks?.onSwipeDown(downY, downX)
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        velocity?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastX = ev.x
                dragging = false; verticalHandled = false; longPressFired = false
                if (velocity == null) velocity = VelocityTracker.obtain()
                velocity?.addMovement(ev)
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX; val dy = ev.y - downY
                if (!dragging && !verticalHandled) {
                    if (abs(dx) > slop || abs(dy) > slop) removeCallbacks(longPress)
                    if (longPressFired) return true
                    if (callbacks?.canScroll() != false && abs(dx) > slop && abs(dx) > abs(dy)) { dragging = true; lastX = ev.x }
                    else if (dy > slop * 2 && dy > abs(dx) * 1.5f && currentPage < childCount - 1) {
                        verticalHandled = true; callbacks?.onSwipeDown(downY, downX)
                    }
                }
                if (dragging) {
                    var delta = lastX - ev.x
                    lastX = ev.x
                    val max = (childCount - 1) * width
                    if (scrollX + delta < 0 || scrollX + delta > max) delta *= 0.35f   // rubber band
                    scrollBy(delta.roundToInt(), 0)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (dragging) {
                    velocity?.computeCurrentVelocity(1000)
                    val vx = velocity?.xVelocity ?: 0f
                    val pos = scrollX / width.toFloat()
                    val target = when {
                        vx < -minFling -> kotlin.math.floor(pos).toInt() + 1
                        vx > minFling -> kotlin.math.ceil(pos).toInt() - 1
                        else -> pos.roundToInt()
                    }
                    snapTo(target, -vx)
                } else if (ev.actionMasked == MotionEvent.ACTION_UP && !longPressFired && !verticalHandled) {
                    callbacks?.onEmptyTap()
                }
                dragging = false
                velocity?.recycle(); velocity = null
            }
        }
        return true
    }

    fun pageAt(index: Int): View? = getChildAt(index)
}
