package com.ioslauncher.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
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
import kotlin.math.hypot
import kotlin.random.Random

enum class IconSource { HOME, DOCK, LIBRARY, FOLDER }

/** One app icon: squircle bitmap + label, iOS press-dim, long-press, jiggle and remove badge. */
class AppIconView(context: Context, private val host: LauncherHost, val source: IconSource) : View(context) {
    var app: AppInfo? = null; private set
    var bitmap: Bitmap? = null; private set
    var iconSize = host.dimens.iconSize
    var showLabel = true
    var iconTop = 0f

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 0, 0, 0) }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, 400, false)
        setShadowLayer(4f, 0f, 1f, Color.argb(70, 0, 0, 0))
    }
    private val shapePath = Path()
    private var label: CharSequence = ""
    private var pressedDim = false
    private var editMode = false
    private var jiggle: ObjectAnimator? = null

    private var downX = 0f; private var downY = 0f
    private var longPressFired = false
    private var tracking = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPress = Runnable {
        longPressFired = true
        pressedDim = false
        invalidate()
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        host.onIconLongPress(this)
    }

    var labelColor: Int
        get() = labelPaint.color
        set(v) { labelPaint.color = v; if (v != Color.WHITE) labelPaint.clearShadowLayer() }

    init {
        labelPaint.textSize = host.dimens.labelTextSize
    }

    fun bind(app: AppInfo) {
        val same = this.app?.key == app.key && bitmap != null
        this.app = app
        contentDescription = app.label
        label = app.label
        if (same) return
        bitmap = null
        reloadIcon()
    }

    fun reloadIcon() {
        val a = app ?: return
        val size = iconSize
        val provider = LauncherApp.instance.icons
        provider.cached(a, size)?.let { bitmap = it; invalidate(); return }
        provider.load(a, size) { bmp -> if (this.app?.key == a.key) { bitmap = bmp; invalidate() } }
    }

    fun iconBoundsOnScreen(): Rect {
        val loc = IntArray(2); getLocationOnScreen(loc)
        val l = loc[0] + ((width - iconSize) / 2f * scaleX).toInt()
        val t = loc[1] + (iconTop * scaleY).toInt()
        return Rect(l, t, l + (iconSize * scaleX).toInt(), t + (iconSize * scaleY).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        pivotX = w / 2f
        pivotY = iconTop + iconSize / 2f
    }

    override fun onDraw(canvas: Canvas) {
        val a = app ?: return
        if (host.isHiddenForDrag(a.key)) return
        val left = (width - iconSize) / 2f
        val top = iconTop
        bitmap?.let { canvas.drawBitmap(it, null, RectF(left, top, left + iconSize, top + iconSize), bitmapPaint) }
        if (pressedDim) {
            canvas.save(); canvas.translate(left, top)
            canvas.drawPath(IosShape.iconPath(iconSize.toFloat(), shapePath), dimPaint)
            canvas.restore()
        }
        if (showLabel && label.isNotEmpty()) {
            val maxW = width - 4f
            val text = TextUtils.ellipsize(label, labelPaint, maxW, TextUtils.TruncateAt.END)
            val y = top + iconSize + host.dimens.labelGap - labelPaint.ascent()
            canvas.drawText(text, 0, text.length, width / 2f, y, labelPaint)
        }
        if (editMode && source != IconSource.LIBRARY) {
            val r = iconSize * 0.135f
            val cx = left + r * 0.45f
            val cy = top + r * 0.45f
            badgePaint.style = Paint.Style.FILL
            badgePaint.color = Color.argb(235, 224, 224, 228)
            canvas.drawCircle(cx, cy, r, badgePaint)
            badgePaint.color = Color.argb(255, 40, 40, 42)
            badgePaint.strokeWidth = r * 0.22f
            badgePaint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(cx - r * 0.45f, cy, cx + r * 0.45f, cy, badgePaint)
        }
    }

    private fun inBadge(x: Float, y: Float): Boolean {
        val r = iconSize * 0.135f
        val left = (width - iconSize) / 2f
        return hypot(x - (left + r * 0.45f), y - (iconTop + r * 0.45f)) < r * 2.2f
    }

    fun setEditMode(edit: Boolean) {
        if (editMode == edit) return
        editMode = edit
        jiggle?.cancel(); jiggle = null
        rotation = 0f
        if (edit && source != IconSource.LIBRARY) {
            val amp = 1.6f + Random.nextFloat() * 0.6f
            jiggle = ObjectAnimator.ofFloat(this, ROTATION, -amp, amp).apply {
                duration = 130L + Random.nextLong(30)
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                currentPlayTime = Random.nextLong(130)
                start()
            }
        }
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                longPressFired = false
                tracking = true
                if (host.isEditMode && source != IconSource.LIBRARY) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                } else {
                    pressedDim = true
                    invalidate()
                }
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return true
                val moved = hypot(event.x - downX, event.y - downY) > slop
                if (moved) {
                    if (longPressFired || (host.isEditMode && source != IconSource.LIBRARY)) {
                        removeCallbacks(longPress)
                        tracking = false
                        host.onIconDragStart(this, event.rawX, event.rawY)
                    } else {
                        removeCallbacks(longPress)
                        pressedDim = false
                        tracking = false
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val wasTracking = tracking
                tracking = false
                pressedDim = false
                invalidate()
                if (wasTracking && !longPressFired) {
                    val a = app
                    if (host.isEditMode && source != IconSource.LIBRARY) {
                        if (inBadge(event.x, event.y)) host.onRemoveRequested(this)
                    } else if (a != null) {
                        playSoundEffect(android.view.SoundEffectConstants.CLICK)
                        host.launchApp(this, a, iconBoundsOnScreen())
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                tracking = false
                pressedDim = false
                invalidate()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        jiggle?.cancel(); jiggle = null
        editMode = false
        removeCallbacks(longPress)
    }
}
