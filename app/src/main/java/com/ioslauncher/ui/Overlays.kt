package com.ioslauncher.ui

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
import android.graphics.drawable.Drawable
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ioslauncher.LauncherApp
import com.ioslauncher.R
import com.ioslauncher.icons.IosShape
import com.ioslauncher.model.AppInfo
import java.util.Locale

/** Squircle background drawable (continuous corners). */
class SquircleDrawable(var color: Int, private val radius: Float, private val stroke: Int = 0) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    override fun draw(canvas: Canvas) {
        IosShape.roundRect(RectF(bounds), radius, path)
        paint.style = Paint.Style.FILL; paint.color = color
        canvas.drawPath(path, paint)
        if (stroke != 0) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = stroke
            canvas.drawPath(path, paint)
        }
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Base for full-screen overlays: scrim + blur, tap-outside to close, animated in/out. */
abstract class Overlay(context: Context, protected val host: OverlayHost) : FrameLayout(context) {
    interface OverlayHost : AppLibraryView.LibraryHost {
        fun setOverlayBlur(fraction: Float)
        fun removeOverlay(overlay: Overlay)
        fun renameFolder(folderId: String, name: String) {}
        fun onFolderClosed(folderId: String) {}
    }

    var closing = false; private set
    protected open val scrimColor = Color.argb(70, 0, 0, 0)

    init {
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
    }

    /** Backdrop behind the overlay content; flat scrim by default. */
    protected open fun backdrop(): android.graphics.drawable.Drawable = android.graphics.drawable.ColorDrawable(scrimColor)

    open fun show() {
        background = backdrop()
        alpha = 0f
        animate().alpha(1f).setDuration(220).setUpdateListener { host.setOverlayBlur(alpha) }.start()
    }

    open fun close(after: (() -> Unit)? = null) {
        if (closing) return
        closing = true
        animate().alpha(0f).setDuration(200).setUpdateListener { host.setOverlayBlur(alpha) }
            .withEndAction { host.setOverlayBlur(0f); host.removeOverlay(this); after?.invoke() }.start()
    }
}

// ---------------------------------------------------------------------------------------------------
// Context menu (long press on an icon)
// ---------------------------------------------------------------------------------------------------

data class MenuItem(val label: String, val destructive: Boolean = false, val action: () -> Unit)

@SuppressLint("ViewConstructor")
class MenuOverlay(
    context: Context, host: OverlayHost, private val bitmap: Bitmap?, private val iconRect: Rect,
    items: List<MenuItem>,
) : Overlay(context, host) {
    private val d = host.dimens
    private val panel = LinearLayout(context)
    private val iconView = object : View(context) {
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) { bitmap?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), p) } }
    }

    init {
        addView(iconView, LayoutParams(iconRect.width(), iconRect.height()).apply { leftMargin = iconRect.left; topMargin = iconRect.top })
        panel.orientation = LinearLayout.VERTICAL
        panel.background = GlassDrawable(d.pt(18f), context.getColor(R.color.menu_bg), shadow = true)
        items.forEachIndexed { i, item ->
            if (i > 0) panel.addView(View(context).apply { setBackgroundColor(context.getColor(R.color.menu_separator)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            panel.addView(TextView(context).apply {
                text = item.label
                setTextSize(TypedValue.COMPLEX_UNIT_PX, d.pt(17f))
                setTextColor(if (item.destructive) Color.rgb(255, 59, 48) else context.getColor(R.color.menu_text))
                setPadding(d.pt(16f).toInt(), d.pt(11f).toInt(), d.pt(16f).toInt(), d.pt(11f).toInt())
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.argb(40, 128, 128, 128)), null, null)
                setOnClickListener { close { item.action() } }
            })
        }
        val menuW = d.pt(250f).toInt()
        panel.measure(MeasureSpec.makeMeasureSpec(menuW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val menuH = panel.measuredHeight
        val gap = d.pt(10f).toInt()
        val below = iconRect.bottom + gap + menuH < d.height - d.bottomInset - gap
        var left = iconRect.left
        if (left + menuW > d.width - d.pt(12f)) left = iconRect.right - menuW
        left = left.coerceIn(d.pt(12f).toInt(), d.width - menuW - d.pt(12f).toInt())
        val top = if (below) iconRect.bottom + gap else iconRect.top - gap - menuH
        addView(panel, LayoutParams(menuW, menuH).apply { leftMargin = left; topMargin = top })
        panel.pivotX = (iconRect.centerX() - left).toFloat().coerceIn(0f, menuW.toFloat())
        panel.pivotY = if (below) 0f else menuH.toFloat()
        setOnClickListener { close() }
    }

    override fun show() {
        super.show()
        panel.scaleX = 0.4f; panel.scaleY = 0.4f; panel.alpha = 0f
        panel.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(320).setInterpolator(SPRING).start()
        iconView.animate().scaleX(1.06f).scaleY(1.06f).setDuration(260).setInterpolator(SPRING).start()
    }

    override fun close(after: (() -> Unit)?) {
        panel.animate().scaleX(0.6f).scaleY(0.6f).alpha(0f).setDuration(160).start()
        iconView.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        super.close(after)
    }

    companion object {
        val SPRING = android.view.animation.OvershootInterpolator(1.1f)
    }
}

// ---------------------------------------------------------------------------------------------------
// iOS-style alert
// ---------------------------------------------------------------------------------------------------

data class AlertButton(val label: String, val style: Int = NORMAL, val action: () -> Unit = {}) {
    companion object { const val NORMAL = 0; const val BOLD = 1; const val DESTRUCTIVE = 2 }
}

@SuppressLint("ViewConstructor")
class AlertOverlay(context: Context, host: OverlayHost, title: String, message: String, buttons: List<AlertButton>) :
    Overlay(context, host) {
    override val scrimColor = Color.argb(100, 0, 0, 0)
    private val card = LinearLayout(context)

    init {
        val d = host.dimens
        card.orientation = LinearLayout.VERTICAL
        card.background = GlassDrawable(d.pt(22f), context.getColor(R.color.menu_bg), shadow = true)
        val textColor = context.getColor(R.color.menu_text)
        card.addView(TextView(context).apply {
            text = title; gravity = Gravity.CENTER; setTextColor(textColor)
            typeface = Typeface.create(Typeface.DEFAULT, 600, false)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, d.pt(17f))
            setPadding(d.pt(16f).toInt(), d.pt(19f).toInt(), d.pt(16f).toInt(), d.pt(2f).toInt())
        })
        card.addView(TextView(context).apply {
            text = message; gravity = Gravity.CENTER; setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, d.pt(13f))
            setPadding(d.pt(16f).toInt(), d.pt(2f).toInt(), d.pt(16f).toInt(), d.pt(19f).toInt())
        })
        for (b in buttons) {
            card.addView(View(context).apply { setBackgroundColor(context.getColor(R.color.menu_separator)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            card.addView(TextView(context).apply {
                text = b.label; gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_PX, d.pt(17f))
                setTextColor(if (b.style == AlertButton.DESTRUCTIVE) Color.rgb(255, 59, 48) else Color.rgb(10, 132, 255))
                if (b.style == AlertButton.BOLD) typeface = Typeface.create(Typeface.DEFAULT, 600, false)
                setPadding(0, d.pt(11f).toInt(), 0, d.pt(11f).toInt())
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.argb(40, 128, 128, 128)), null, null)
                setOnClickListener { close { b.action() } }
            })
        }
        addView(card, LayoutParams(d.pt(270f).toInt(), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setOnClickListener { }
    }

    override fun show() {
        super.show()
        card.scaleX = 1.15f; card.scaleY = 1.15f
        card.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(HomePageView.EASE).start()
    }
}

// ---------------------------------------------------------------------------------------------------
// Expanded App Library category
// ---------------------------------------------------------------------------------------------------

@SuppressLint("ViewConstructor")
class CategoryOverlay(context: Context, host: OverlayHost, category: LibraryCategory, private val from: Rect) :
    Overlay(context, host) {
    override val scrimColor = Color.argb(60, 0, 0, 0)
    private val panel = FrameLayout(context)
    private val title = TextView(context)

    init {
        val d = host.dimens
        title.text = category.name
        title.setTextColor(Color.WHITE)
        title.typeface = Typeface.create(Typeface.DEFAULT, 700, false)
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, d.pt(28f))
        title.gravity = Gravity.CENTER
        addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = d.gridTop })

        panel.background = GlassDrawable(d.pt(38f), Color.argb(30, 255, 255, 255))
        val rv = RecyclerView(context)
        rv.layoutManager = GridLayoutManager(context, 4)
        rv.overScrollMode = OVER_SCROLL_NEVER
        val cellH = d.cellHeight
        val apps = category.apps
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = apps.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = AppIconView(context, host, IconSource.FOLDER)
                v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellH)
                v.iconTop = d.pt(10f)
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                (holder.itemView as AppIconView).bind(apps[position])
            }
        }
        val pad = d.pt(14f).toInt()
        rv.setPadding(pad, pad, pad, pad)
        rv.clipToPadding = false
        panel.addView(rv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val rows = (apps.size + 3) / 4
        val maxH = d.height - d.gridTop - d.pt(70f).toInt() - d.bottomInset - d.pt(40f).toInt()
        val h = (rows * cellH + 2 * pad).coerceAtMost(maxH)
        addView(panel, LayoutParams(d.width - 2 * d.pt(16f).toInt(), h, Gravity.CENTER_HORIZONTAL).apply {
            topMargin = d.gridTop + d.pt(62f).toInt()
        })
        panel.isClickable = true
        setOnClickListener { close() }
    }

    override fun show() {
        super.show()
        post {
            val loc = IntArray(2); panel.getLocationOnScreen(loc)
            panel.pivotX = (from.centerX() - loc[0]).toFloat()
            panel.pivotY = (from.centerY() - loc[1]).toFloat()
            panel.scaleX = 0.25f; panel.scaleY = 0.25f; panel.alpha = 0f
            panel.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(380).setInterpolator(HomePageView.EASE).start()
        }
        title.alpha = 0f
        title.animate().alpha(1f).setDuration(300).start()
    }

    override fun close(after: (() -> Unit)?) {
        panel.animate().scaleX(0.3f).scaleY(0.3f).alpha(0f).setDuration(220).start()
        super.close(after)
    }
}

// ---------------------------------------------------------------------------------------------------
// Search: two-row 3D icon drum + live filter + A–Z strip + suggestions
// ---------------------------------------------------------------------------------------------------

@SuppressLint("ViewConstructor")
class SearchOverlay(context: Context, host: OverlayHost, private val allApps: List<AppInfo>) : Overlay(context, host) {
    override val scrimColor = Color.argb(120, 0, 0, 0)

    /**
     * Layered backdrop over the blurred home screen: a deep vertical gradient (darker at the top for the
     * search field and at the bottom for suggestions) plus a soft "stage light" behind the tile carousel.
     */
    override fun backdrop(): android.graphics.drawable.Drawable = object : android.graphics.drawable.Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val b = bounds; val h = b.height().toFloat(); val w = b.width().toFloat()
            p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h,
                intArrayOf(Color.argb(150, 6, 8, 16), Color.argb(70, 10, 12, 24), Color.argb(95, 8, 10, 20), Color.argb(175, 4, 5, 10)),
                floatArrayOf(0f, 0.32f, 0.6f, 1f), android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRect(b, p)
            val cy = stageY.takeIf { it > 0f } ?: h * 0.3f
            p.shader = android.graphics.RadialGradient(w / 2f, cy, w * 0.75f,
                intArrayOf(Color.argb(46, 160, 190, 255), Color.argb(14, 140, 170, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
            canvas.save(); canvas.scale(1f, 0.55f, w / 2f, cy)
            canvas.drawCircle(w / 2f, cy, w * 0.75f, p)
            canvas.restore()
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** Vertical centre of the tile carousel, for the stage light. */
    private var stageY = 0f
    private val d = host.dimens
    private val field = EditText(context)
    private val content = FrameLayout(context)
    private val drum = IconDrum(context, d)
    private val countView = TextView(context)
    private val letters = LetterStrip(context)
    private val suggestions = LinearLayout(context)
    private val suggestionsTitle = TextView(context)
    private var results: List<AppInfo> = emptyList()
    private val sorted = allApps.sortedWith(compareBy<AppInfo> { letterOf(it) == "#" }.thenBy { it.label.lowercase() })

    init {
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // ---- search field (a quarter of a 100% icon lower than the grid top) ------------------
        val bar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val fieldBox = FrameLayout(context).apply { background = GlassDrawable(d.searchBarHeight / 2f, Color.argb(30, 255, 255, 255)) }
        val glyph = object : View(context) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
            override fun onDraw(canvas: Canvas) = SearchPill.drawMagnifier(canvas, width / 2f, height / 2f, d.pt(15f), p)
        }
        fieldBox.addView(glyph, FrameLayout.LayoutParams(d.pt(36f).toInt(), FrameLayout.LayoutParams.MATCH_PARENT))
        field.apply {
            hint = "Search Apps"
            setHintTextColor(Color.argb(150, 255, 255, 255))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, d.pt(17f))
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(0, 0, d.pt(10f).toInt(), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
            })
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    drum.focusedApp()?.let { launch(it, drum.iconRectOnScreen(it)) }
                    true
                } else false
            }
        }
        fieldBox.addView(field, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            leftMargin = d.pt(36f).toInt()
        })
        bar.addView(fieldBox, LinearLayout.LayoutParams(0, d.searchBarHeight, 1f))
        bar.addView(TextView(context).apply {
            text = "Cancel"; setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, d.pt(17f))
            setPadding(d.pt(14f).toInt(), 0, 0, 0)
            setOnClickListener { close() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, d.searchBarHeight))
        val barTop = d.searchBarTop          // same spot as the App Library field it opens from
        content.addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, d.searchBarHeight).apply {
            topMargin = barTop; leftMargin = d.librarySide; rightMargin = d.librarySide
        })

        countView.apply {
            setTextColor(Color.argb(160, 255, 255, 255)); gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_PX, d.pt(13f))
        }
        val countTop = barTop + d.searchBarHeight + d.pt(12f).toInt()
        content.addView(countView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = countTop })

        // ---- 3D drum --------------------------------------------------------------------------
        val drumTop = countTop + d.pt(18f).toInt()
        val drumH = drum.preferredHeight()
        drum.onLaunch = { a, r -> launch(a, r) }
        drum.onLongPress = { a, r -> hideKeyboard(); host.onLibraryLongPress(a, r) }
        drum.onDragStart = { hideKeyboard() }
        drum.onFocusChanged = { a -> letters.current = a?.let { letterOf(it) } ?: "" }
        content.addView(drum, LayoutParams(LayoutParams.MATCH_PARENT, drumH).apply { topMargin = drumTop })
        stageY = drumTop + drumH * 0.42f

        // ---- A–Z strip -------------------------------------------------------------------------
        letters.onLetter = { l -> val pos = results.indexOfFirst { letterOf(it) == l }; if (pos >= 0) drum.scrollToItem(pos, animate = true) }
        val lettersTop = drumTop + drumH + d.pt(4f).toInt()
        content.addView(letters, LayoutParams(LayoutParams.MATCH_PARENT, d.pt(30f).toInt()).apply {
            topMargin = lettersTop; leftMargin = d.pt(12f).toInt(); rightMargin = d.pt(12f).toInt()
        })

        // ---- suggestions -----------------------------------------------------------------------
        suggestionsTitle.apply {
            text = "SUGGESTIONS"; setTextColor(Color.argb(170, 255, 255, 255))
            typeface = Typeface.create(Typeface.DEFAULT, 600, false)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, d.pt(13f))
        }
        val sugTop = lettersTop + d.pt(44f).toInt()
        content.addView(suggestionsTitle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = sugTop; leftMargin = d.librarySide + d.pt(6f).toInt()
        })
        suggestions.orientation = LinearLayout.HORIZONTAL
        suggestions.background = GlassDrawable(d.pt(28f), Color.argb(26, 255, 255, 255))
        suggestions.setPadding(d.pt(8f).toInt(), d.pt(12f).toInt(), d.pt(8f).toInt(), d.pt(6f).toInt())
        val prefs = LauncherApp.instance.prefs
        val sug = allApps.filter { prefs.launchCount(it.key) > 0 }
            .sortedByDescending { prefs.launchCount(it.key) / (1.0 + (System.currentTimeMillis() - prefs.lastLaunch(it.key)) / 86_400_000.0) }
            .take(4).ifEmpty { allApps.take(4) }
        val sugIcon = d.pt(56f).toInt()
        for (a in sug) {
            val v = AppIconView(context, host, IconSource.LIBRARY)
            v.iconSize = sugIcon; v.iconTop = 0f; v.showLabel = true
            v.bind(a)
            suggestions.addView(v, LinearLayout.LayoutParams(0, sugIcon + d.pt(26f).toInt(), 1f))
        }
        content.addView(suggestions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = sugTop + d.pt(22f).toInt(); leftMargin = d.librarySide; rightMargin = d.librarySide
        })

        filter("")

        // Suggestions follow the keyboard: they melt away as it rises and flow back as it closes.
        setWindowInsetsAnimationCallback(object : android.view.WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onProgress(insets: WindowInsets, running: MutableList<android.view.WindowInsetsAnimation>): WindowInsets {
                val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
                val max = (d.height * 0.38f).coerceAtLeast(1f)
                setKeyboardFraction((ime / max).coerceIn(0f, 1f))
                return insets
            }
        })
        setOnApplyWindowInsetsListener { _, insets ->
            val visible = insets.isVisible(WindowInsets.Type.ime())
            animateKeyboardFraction(if (visible) 1f else 0f)
            insets
        }
    }

    private var kbFraction = 0f
    private var kbAnim: android.animation.ValueAnimator? = null

    /** 0 = keyboard hidden (suggestions fully shown), 1 = keyboard up (suggestions gone). */
    private fun setKeyboardFraction(f: Float) {
        kbAnim?.cancel()
        applyKeyboardFraction(f)
    }

    private fun animateKeyboardFraction(target: Float) {
        if (kotlin.math.abs(target - kbFraction) < 0.01f) return
        kbAnim?.cancel()
        kbAnim = android.animation.ValueAnimator.ofFloat(kbFraction, target).apply {
            duration = 340; interpolator = HomePageView.EASE
            addUpdateListener { applyKeyboardFraction(it.animatedValue as Float) }
            start()
        }
    }

    private fun applyKeyboardFraction(f: Float) {
        kbFraction = f
        val e = f * f * (3 - 2 * f)                     // smoothstep for a liquid feel
        for (v in listOf<View>(suggestions, suggestionsTitle)) {
            v.alpha = 1f - e
            v.translationY = e * d.pt(46f)
            v.scaleX = 1f - 0.08f * e; v.scaleY = 1f - 0.08f * e
        }
        suggestions.pivotY = 0f; suggestions.pivotX = suggestions.width / 2f
    }

    private fun letterOf(app: AppInfo): String {
        val c = app.label.firstOrNull()?.uppercaseChar() ?: '#'
        return if (c in 'A'..'Z') c.toString() else "#"
    }

    private fun filter(q: String) {
        val query = q.trim().lowercase(Locale.getDefault())
        results = if (query.isEmpty()) sorted else allApps
            .filter { it.label.lowercase().contains(query) || it.packageName.contains(query) || it.category.lowercase().contains(query) }
            .sortedWith(compareBy<AppInfo> {
                val l = it.label.lowercase()
                when { l.startsWith(query) -> 0; l.split(' ').any { w -> w.startsWith(query) } -> 1; l.contains(query) -> 2; else -> 3 }
            }.thenBy { it.label.lowercase() })
        drum.setApps(results)
        val searching = query.isNotEmpty()
        letters.visibility = if (searching) INVISIBLE else VISIBLE
        suggestions.visibility = if (searching) GONE else VISIBLE
        suggestionsTitle.visibility = suggestions.visibility
        countView.text = when {
            !searching -> "${results.size} Apps"
            results.isEmpty() -> "No Results"
            results.size == 1 -> "1 Result"
            else -> "${results.size} Results"
        }
    }

    private fun launch(app: AppInfo, bounds: Rect?) {
        hideKeyboard()
        host.launchApp(this, app, bounds)
        close()
    }

    override fun show() {
        super.show()
        content.translationY = -d.pt(30f)
        content.animate().translationY(0f).setDuration(320).setInterpolator(HomePageView.EASE).start()
        drum.playIntro()
        field.requestFocus()
        field.post { field.windowInsetsController?.show(WindowInsets.Type.ime()) }
    }

    fun hideKeyboard() { field.windowInsetsController?.hide(WindowInsets.Type.ime()); field.clearFocus() }

    override fun close(after: (() -> Unit)?) {
        hideKeyboard()
        content.animate().translationY(-d.pt(30f)).setDuration(200).start()
        super.close(after)
    }
}

/**
 * 3D tile carousel. Apps are grouped as one big tile + two small tiles stacked beside it (like a
 * gallery widget); the small ones form the two rows. Groups sit on a slightly tilted drum and turn in
 * real perspective (Matrix.setPolyToPoly per tile). Every tile is a thick glossy slab: an extruded,
 * darker edge built from depth slices of the icon itself, a gloss highlight and a bright rim, plus a
 * floor glow and a mirrored reflection. Swipes spin the drum with momentum and snap with a bounce.
 */
class IconDrum(context: Context, private val d: Dimens) : View(context) {
    var onLaunch: (AppInfo, Rect) -> Unit = { _, _ -> }
    var onLongPress: (AppInfo, Rect) -> Unit = { _, _ -> }
    var onDragStart: () -> Unit = {}
    var onFocusChanged: (AppInfo?) -> Unit = {}

    private var apps: List<AppInfo> = emptyList()
    private val glossy = HashMap<String, Bitmap>()

    private val big = d.pt(100f)                     // big tile side
    private val gap = d.pt(9f)
    private val small = (big - gap) / 2f              // small tile side (two stacked = big)
    private val groupW = big + gap + small
    private val stepDeg = 38f                         // angle between groups on the drum
    private val radius = (groupW + d.pt(26f)) / (2f * kotlin.math.sin(Math.toRadians(stepDeg / 2.0)).toFloat())
    private val focal = d.pt(440f)
    private val tilt = Math.toRadians(10.0)
    private val cosT = kotlin.math.cos(tilt).toFloat()
    private val sinT = kotlin.math.sin(tilt).toFloat()
    private val bitmapPx = big.toInt()

    private var pos = 0f                              // front group, fractional while spinning
    private var lastFocus = -1
    private var anim: android.animation.ValueAnimator? = null
    private var introT = 1f

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        setShadowLayer(6f, 0f, 1f, Color.argb(90, 0, 0, 0))
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fadePaint = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) }
    private val m = android.graphics.Matrix()
    private val src = FloatArray(8)
    private val tmp = FloatArray(8)

    private class Tile(val index: Int, val app: AppInfo, val group: Int, val isBig: Boolean,
                       val cx: Float, val cy: Float, val cz: Float, val s: Float, val c: Float, val half: Float) {
        val quad = FloatArray(8)
    }
    private var drawn: List<Tile> = emptyList()

    fun preferredHeight() = (big + d.pt(118f)).toInt()

    private val groups get() = (apps.size + 2) / 3

    fun setApps(list: List<AppInfo>) {
        apps = list
        anim?.cancel()
        pos = 0f; lastFocus = -1
        val want = list.take(90).map { it.key }.toSet()
        glossy.keys.retainAll(want)
        list.take(90).forEach { load(it) }
        notifyFocus()
        invalidate()
    }

    private fun load(a: AppInfo) {
        if (glossy.containsKey(a.key)) return
        LauncherApp.instance.icons.load(a, bitmapPx) { b -> glossy[a.key] = makeGlossy(b); invalidate() }
    }

    /** Icon + gloss sheet on the upper half + bright specular rim, all inside the squircle. */
    private fun makeGlossy(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val w = out.width.toFloat(); val h = out.height.toFloat()
        val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_ATOP)
            shader = android.graphics.LinearGradient(0f, 0f, w * 0.35f, h * 0.75f,
                intArrayOf(Color.argb(120, 255, 255, 255), Color.argb(30, 255, 255, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f), android.graphics.Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w, h, gp)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = w * 0.025f
            shader = android.graphics.LinearGradient(0f, 0f, w, h,
                intArrayOf(Color.argb(220, 255, 255, 255), Color.argb(40, 255, 255, 255), Color.argb(140, 255, 255, 255)),
                floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP)
        }
        val inset = rim.strokeWidth / 2f
        c.drawPath(IosShape.roundRect(RectF(inset, inset, w - inset, h - inset), (w - 2 * inset) * IosShape.ICON_CORNER_RATIO), rim)
        return out
    }

    fun focusedApp(): AppInfo? = apps.getOrNull(Math.round(pos).coerceAtLeast(0) * 3)

    fun scrollToItem(index: Int, animate: Boolean) {
        val g = (index / 3).toFloat()
        if (animate) animateTo(g, 520) else { pos = g; invalidate(); notifyFocus() }
    }

    fun playIntro() {
        introT = 0f
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 750; interpolator = android.view.animation.OvershootInterpolator(0.9f)
            addUpdateListener { introT = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun notifyFocus() {
        val g = Math.round(pos)
        if (g != lastFocus) {
            if (lastFocus != -1) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            lastFocus = g
            onFocusChanged(focusedApp())
        }
    }

    private fun animateTo(target: Float, duration: Long) {
        anim?.cancel()
        val t = target.coerceIn(0f, (groups - 1).coerceAtLeast(0).toFloat())
        anim = android.animation.ValueAnimator.ofFloat(pos, t).apply {
            this.duration = duration
            interpolator = android.view.animation.OvershootInterpolator(1.1f)
            addUpdateListener { pos = it.animatedValue as Float; notifyFocus(); invalidate() }
            start()
        }
    }

    // ---- projection --------------------------------------------------------------------------

    private fun centerY() = d.pt(20f) + big / 2f

    private fun project(x: Float, y: Float, z: Float, out: FloatArray, o: Int) {
        val zr = z - radius
        val y2 = y * cosT - zr * sinT
        val z2 = y * sinT + zr * cosT + radius * cosT
        val p = focal / (focal + z2)
        out[o] = width / 2f + x * p
        out[o + 1] = centerY() + y2 * p
    }

    /** Quad of a tile, pushed [depth] behind its face (along the inward normal). */
    private fun quadOf(t: Tile, depth: Float, out: FloatArray) {
        val h = t.half
        val x = t.cx - t.s * depth; val z = t.cz + t.c * depth
        project(x - t.c * h, t.cy - h, z - t.s * h, out, 0)
        project(x + t.c * h, t.cy - h, z + t.s * h, out, 2)
        project(x + t.c * h, t.cy + h, z + t.s * h, out, 4)
        project(x - t.c * h, t.cy + h, z - t.s * h, out, 6)
    }

    override fun onDraw(canvas: Canvas) {
        if (apps.isEmpty()) return
        val spin = (1f - introT) * 2.5f
        val tiles = ArrayList<Tile>()
        val first = (pos - 4).toInt().coerceAtLeast(0)
        val last = (pos + 4).toInt().coerceAtMost(groups - 1)
        for (g in first..last) {
            val thetaDeg = (g - pos - spin) * stepDeg
            if (kotlin.math.abs(thetaDeg) > 78f) continue
            val th = Math.toRadians(thetaDeg.toDouble())
            val s = kotlin.math.sin(th).toFloat(); val c = kotlin.math.cos(th).toFloat()
            val gx = radius * s; val gz = radius * (1 - c)
            // Group layout on its own plane: big tile left, two small tiles stacked on the right.
            fun place(i: Int, u: Float, v: Float, half: Float, isBig: Boolean) {
                val a = apps.getOrNull(i) ?: return
                tiles += Tile(i, a, g, isBig, gx + c * u, v, gz + s * u, s, c, half)
            }
            place(g * 3, -groupW / 2f + big / 2f, 0f, big / 2f, true)
            place(g * 3 + 1, groupW / 2f - small / 2f, -(gap / 2f + small / 2f), small / 2f, false)
            place(g * 3 + 2, groupW / 2f - small / 2f, gap / 2f + small / 2f, small / 2f, false)
        }
        tiles.forEach { quadOf(it, 0f, it.quad) }
        tiles.sortByDescending { it.cz }
        drawn = tiles

        val bottomFront = tiles.minByOrNull { it.cz }?.let { maxOf(it.quad[5], it.quad[7]) } ?: (centerY() + big / 2f)
        val floorY = bottomFront + d.pt(4f)
        // Floor glow.
        glowPaint.shader = android.graphics.RadialGradient(width / 2f, floorY, width * 0.42f,
            Color.argb(80, 255, 255, 255), Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP)
        canvas.save(); canvas.scale(1f, 0.15f, width / 2f, floorY)
        canvas.drawCircle(width / 2f, floorY, width * 0.42f, glowPaint)
        canvas.restore()
        glowPaint.shader = null

        // Reflection of the tiles' bottom edges.
        val reflH = big * 0.45f
        val layer = canvas.saveLayer(0f, floorY - d.pt(4f), width.toFloat(), floorY + reflH, null)
        for (t in tiles) {
            if (t.c < 0.25f || (!t.isBig && t.cy < 0)) continue
            val b = glossy[t.app.key] ?: continue
            val q = t.quad
            tmp[0] = q[6]; tmp[1] = q[7]; tmp[2] = q[4]; tmp[3] = q[5]
            tmp[4] = q[2]; tmp[5] = 2 * q[5] - q[3]; tmp[6] = q[0]; tmp[7] = 2 * q[7] - q[1]
            drawQuad(canvas, b, tmp, (80 * t.c).toInt(), null)
        }
        fadePaint.shader = android.graphics.LinearGradient(0f, floorY, 0f, floorY + reflH,
            Color.argb(255, 0, 0, 0), Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, floorY - d.pt(4f), width.toFloat(), floorY + reflH, fadePaint)
        canvas.restoreToCount(layer)

        val fade = introT.coerceIn(0f, 1f)
        for (t in tiles) {
            val b = glossy[t.app.key] ?: continue
            val k = t.c.coerceIn(0f, 1f)
            val alpha = (255 * (0.25f + 0.75f * k) * fade).toInt()
            // Thick slab: depth slices of the icon, darker towards the back.
            val thick = t.half * 0.24f
            val slices = 7
            for (i in slices downTo 1) {
                val depth = thick * i / slices
                quadOf(t, depth, tmp)
                val lum = (255 * (0.28f + 0.22f * (1f - i / slices.toFloat())) * (0.6f + 0.4f * k)).toInt().coerceIn(0, 255)
                drawQuad(canvas, b, tmp, alpha, android.graphics.LightingColorFilter(Color.rgb(lum, lum, lum), Color.rgb(lum / 6, lum / 6, lum / 5)))
            }
            // Front face, slightly darker when turned away.
            val face = (255 * (0.62f + 0.38f * k)).toInt()
            drawQuad(canvas, b, t.quad, alpha, if (face < 252) android.graphics.LightingColorFilter(Color.rgb(face, face, face), 0) else null)

            if (t.isBig && t.c > 0.55f) {
                val q = t.quad
                val p = focal / (focal + t.cz)
                labelPaint.textSize = d.pt(13f) * p
                labelPaint.alpha = (255 * ((t.c - 0.55f) / 0.45f).coerceIn(0f, 1f) * fade).toInt()
                val text = android.text.TextUtils.ellipsize(t.app.label, android.text.TextPaint(labelPaint), groupW * p,
                    android.text.TextUtils.TruncateAt.END).toString()
                canvas.drawText(text, (q[4] + q[6]) / 2f + (small + gap) / 2f * p * t.c, maxOf(q[5], q[7]) + d.pt(19f) * p, labelPaint)
            }
        }
    }

    private fun drawQuad(canvas: Canvas, b: Bitmap, q: FloatArray, alpha: Int, filter: android.graphics.ColorFilter?) {
        src[0] = 0f; src[1] = 0f; src[2] = b.width.toFloat(); src[3] = 0f
        src[4] = b.width.toFloat(); src[5] = b.height.toFloat(); src[6] = 0f; src[7] = b.height.toFloat()
        m.setPolyToPoly(src, 0, q, 0, 4)
        paint.alpha = alpha.coerceIn(0, 255)
        paint.colorFilter = filter
        canvas.drawBitmap(b, m, paint)
        paint.colorFilter = null
    }

    fun iconRectOnScreen(a: AppInfo): Rect? {
        val t = drawn.firstOrNull { it.app.key == a.key } ?: return null
        val loc = IntArray(2); getLocationOnScreen(loc)
        val q = t.quad
        val l = minOf(q[0], q[6]); val r = maxOf(q[2], q[4]); val tp = minOf(q[1], q[3]); val btm = maxOf(q[5], q[7])
        return Rect((loc[0] + l).toInt(), (loc[1] + tp).toInt(), (loc[0] + r).toInt(), (loc[1] + btm).toInt())
    }

    private fun hit(x: Float, y: Float): Tile? = drawn.asReversed().firstOrNull { t ->
        val q = t.quad
        t.c > 0.2f && x in minOf(q[0], q[6])..maxOf(q[2], q[4]) && y in minOf(q[1], q[3])..maxOf(q[5], q[7])
    }

    // ---- touch: drag to spin, momentum, snap; tap = focus or launch; long-press = menu ---------

    private var downX = 0f; private var downY = 0f; private var lastX = 0f
    private var dragging = false
    private var longFired = false
    private var vt: android.view.VelocityTracker? = null
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val groupPx get() = radius * Math.toRadians(stepDeg.toDouble()).toFloat()
    private val longPress = Runnable {
        val t = hit(downX, downY) ?: return@Runnable
        longFired = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        iconRectOnScreen(t.app)?.let { r -> onLongPress(t.app, r) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                anim?.cancel()
                downX = e.x; downY = e.y; lastX = e.x; dragging = false; longFired = false
                vt?.recycle(); vt = android.view.VelocityTracker.obtain(); vt?.addMovement(e)
                parent?.requestDisallowInterceptTouchEvent(true)
                postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                if (!dragging && kotlin.math.abs(e.x - downX) > slop) { dragging = true; removeCallbacks(longPress); onDragStart() }
                if (dragging) {
                    var delta = (lastX - e.x) / groupPx
                    if (pos + delta < 0 || pos + delta > groups - 1) delta *= 0.35f
                    pos += delta
                    lastX = e.x
                    notifyFocus(); invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (dragging) {
                    vt?.computeCurrentVelocity(1000)
                    val v = vt?.xVelocity ?: 0f
                    val target = Math.round(pos - v / groupPx * 0.25f).toFloat()
                    animateTo(target, (420 + kotlin.math.abs(target - pos) * 60).toLong().coerceAtMost(1200))
                } else if (!longFired) {
                    hit(e.x, e.y)?.let { t ->
                        if (t.group == Math.round(pos)) iconRectOnScreen(t.app)?.let { r -> onLaunch(t.app, r) }
                        else animateTo(t.group.toFloat(), 460)
                    }
                }
                vt?.recycle(); vt = null
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (dragging) animateTo(Math.round(pos).toFloat(), 380)
                vt?.recycle(); vt = null
            }
        }
        return true
    }
}

/** Horizontal A–Z strip; tap or drag to jump, current letter highlighted. */
class LetterStrip(context: Context) : View(context) {
    var onLetter: (String) -> Unit = {}
    var current = ""; set(v) { if (field != v) { field = v; invalidate() } }
    private val letters = ('A'..'Z').map { it.toString() } + "#"
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, 600, false) }
    private val hl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
    private var last = ""

    override fun onDraw(canvas: Canvas) {
        val step = width / letters.size.toFloat()
        p.textSize = (step * 0.78f).coerceAtMost(height * 0.55f)
        letters.forEachIndexed { i, s ->
            val cx = step * i + step / 2
            if (s == current) canvas.drawCircle(cx, height / 2f, minOf(step, height.toFloat()) * 0.48f, hl)
            p.color = if (s == current) Color.WHITE else Color.argb(170, 255, 255, 255)
            canvas.drawText(s, cx, height / 2f - (p.ascent() + p.descent()) / 2, p)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val i = (event.x / width * letters.size).toInt().coerceIn(0, letters.lastIndex)
        val l = letters[i]
        if (l != last) { last = l; performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); onLetter(l) }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) last = ""
        return true
    }
}

/** Vertical A–Z scrubber. */
class AlphabetIndex(context: Context) : View(context) {
    var onLetter: (String) -> Unit = {}
    private val letters = ('A'..'Z').map { it.toString() } + "#"
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(10, 132, 255); textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, 600, false)
    }
    private var last = ""

    override fun onDraw(canvas: Canvas) {
        val step = height / letters.size.toFloat()
        p.textSize = (step * 0.75f).coerceAtMost(width * 0.6f)
        letters.forEachIndexed { i, s -> canvas.drawText(s, width / 2f, step * i + step * 0.75f, p) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val i = (event.y / height * letters.size).toInt().coerceIn(0, letters.lastIndex)
        val l = letters[i]
        if (l != last) {
            last = l
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            onLetter(l)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) last = ""
        return true
    }
}



// ---------------------------------------------------------------------------------------------------
// Open folder: Liquid Glass panel, editable title, 3-column grid
// ---------------------------------------------------------------------------------------------------

@SuppressLint("ViewConstructor")
class FolderOverlay(context: Context, host: OverlayHost, private val folder: com.ioslauncher.model.Folder, private val from: Rect) :
    Overlay(context, host) {
    override val scrimColor = Color.argb(60, 0, 0, 0)
    private val panel = FrameLayout(context)
    private val title = EditText(context)

    init {
        val d = host.dimens
        title.setText(folder.name)
        title.setTextColor(Color.WHITE)
        title.typeface = Typeface.create(Typeface.DEFAULT, 700, false)
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, d.pt(30f))
        title.gravity = Gravity.CENTER
        title.background = null
        title.isSingleLine = true
        title.imeOptions = EditorInfo.IME_ACTION_DONE
        title.setShadowLayer(6f, 0f, 1f, Color.argb(60, 0, 0, 0))
        title.setOnEditorActionListener { v, _, _ ->
            host.renameFolder(folder.id, v.text.toString()); v.clearFocus()
            v.windowInsetsController?.hide(WindowInsets.Type.ime()); true
        }
        val panelW = d.width - 2 * d.pt(34f).toInt()
        addView(title, LayoutParams(panelW, LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL).apply { topMargin = d.gridTop + d.pt(40f).toInt() })

        panel.background = GlassDrawable(d.pt(40f), Color.argb(34, 255, 255, 255), shadow = true)
        val cols = 3
        val pad = d.pt(18f).toInt()
        val cellW = (panelW - 2 * pad) / cols
        val icon = minOf(d.pt(60f) * LauncherApp.instance.prefs.homeIconScale, cellW * 0.72f).toInt()
        val cellH = icon + d.pt(34f).toInt()
        val repo = LauncherApp.instance.repository
        val apps = folder.apps.mapNotNull { repo.find(it) }
        val rv = RecyclerView(context)
        rv.layoutManager = GridLayoutManager(context, cols)
        rv.overScrollMode = OVER_SCROLL_NEVER
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = apps.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = AppIconView(context, host, IconSource.FOLDER)
                v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellH)
                v.iconSize = icon
                v.iconTop = d.pt(6f)
                v.showLabel = true
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                (holder.itemView as AppIconView).bind(apps[position])
            }
        }
        rv.setPadding(pad, pad, pad, pad)
        rv.clipToPadding = false
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = panel.invalidate()
        })
        panel.addView(rv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val rows = ((apps.size + cols - 1) / cols).coerceAtLeast(1)
        val maxH = d.height - d.gridTop - d.pt(150f).toInt() - d.bottomInset
        val h = (rows * cellH + 2 * pad).coerceAtMost(maxH)
        addView(panel, LayoutParams(panelW, h, Gravity.CENTER_HORIZONTAL).apply { topMargin = d.gridTop + d.pt(100f).toInt() })
        panel.isClickable = true
        setOnClickListener { close() }
    }

    override fun show() {
        super.show()
        post {
            val loc = IntArray(2); panel.getLocationOnScreen(loc)
            panel.pivotX = (from.centerX() - loc[0]).toFloat()
            panel.pivotY = (from.centerY() - loc[1]).toFloat()
            panel.scaleX = 0.2f; panel.scaleY = 0.2f; panel.alpha = 0f
            panel.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(380).setInterpolator(HomePageView.EASE)
                .setUpdateListener { Glass.invalidateTree(panel) }.start()
        }
        title.alpha = 0f
        title.animate().alpha(1f).setDuration(300).start()
    }

    override fun close(after: (() -> Unit)?) {
        host.renameFolder(folder.id, title.text.toString())
        title.windowInsetsController?.hide(WindowInsets.Type.ime())
        host.onFolderClosed(folder.id)
        panel.animate().scaleX(0.25f).scaleY(0.25f).alpha(0f).setDuration(220).start()
        super.close(after)
    }
}

// ---------------------------------------------------------------------------------------------------
// Widget gallery (Liquid Glass sheet): every installed widget, grouped by app, with preview and size
// ---------------------------------------------------------------------------------------------------

@SuppressLint("ViewConstructor")
class WidgetPickerOverlay(
    context: Context, host: OverlayHost,
    private val providers: List<android.appwidget.AppWidgetProviderInfo>,
    private val spanFor: (android.appwidget.AppWidgetProviderInfo) -> Pair<Int, Int>,
    private val onPick: (android.appwidget.AppWidgetProviderInfo) -> Unit,
) : Overlay(context, host) {
    override val scrimColor = Color.argb(90, 0, 0, 0)
    private val sheet = FrameLayout(context)
    private val d = host.dimens
    private sealed interface Row {
        data class Header(val title: String) : Row
        data class Item(val info: android.appwidget.AppWidgetProviderInfo) : Row
    }

    init {
        val pm = context.packageManager
        val dm = context.resources.displayMetrics.densityDpi
        val rows = ArrayList<Row>()
        providers.groupBy { it.provider.packageName }
            .map { (pkg, list) -> (runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)) to list }
            .sortedBy { it.first.lowercase() }
            .forEach { (label, list) -> rows += Row.Header(label); list.forEach { rows += Row.Item(it) } }

        sheet.background = GlassDrawable(d.pt(34f), context.getColor(R.color.menu_bg), shadow = true)
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        sheet.addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val textColor = context.getColor(R.color.menu_text)
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(d.pt(22f).toInt(), d.pt(18f).toInt(), d.pt(18f).toInt(), d.pt(8f).toInt())
        }
        header.addView(TextView(context).apply {
            text = "Add Widget"; setTextColor(textColor)
            typeface = Typeface.create(Typeface.DEFAULT, 700, false)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, d.pt(24f))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(context).apply {
            text = "Done"; setTextColor(Color.rgb(10, 132, 255))
            typeface = Typeface.create(Typeface.DEFAULT, 600, false)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, d.pt(17f))
            setOnClickListener { close() }
        })
        col.addView(header)
        if (rows.isEmpty()) col.addView(TextView(context).apply {
            text = "No widgets available."; setTextColor(textColor); gravity = Gravity.CENTER
            setPadding(0, d.pt(40f).toInt(), 0, 0)
        })
        val rv = RecyclerView(context)
        rv.layoutManager = LinearLayoutManager(context)
        rv.clipToPadding = false
        rv.setPadding(d.pt(16f).toInt(), 0, d.pt(16f).toInt(), d.pt(24f).toInt() + d.bottomInset)
        val previewH = d.pt(110f).toInt()
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = rows.size
            override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v: View = if (viewType == 0) TextView(context).apply {
                    setTextColor(textColor); alpha = 0.6f
                    typeface = Typeface.create(Typeface.DEFAULT, 600, false)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, d.pt(13f))
                    setPadding(d.pt(6f).toInt(), d.pt(16f).toInt(), 0, d.pt(6f).toInt())
                } else LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(d.pt(10f).toInt(), d.pt(8f).toInt(), d.pt(10f).toInt(), d.pt(8f).toInt())
                    background = SquircleDrawable(Color.argb(28, 128, 128, 128), d.pt(18f))
                    addView(android.widget.ImageView(context).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER },
                        LinearLayout.LayoutParams(d.pt(130f).toInt(), previewH))
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(d.pt(14f).toInt(), 0, 0, 0)
                        addView(TextView(context).apply { setTextColor(textColor); setTextSize(TypedValue.COMPLEX_UNIT_PX, d.pt(16f)); maxLines = 2 })
                        addView(TextView(context).apply { setTextColor(textColor); alpha = 0.6f; setTextSize(TypedValue.COMPLEX_UNIT_PX, d.pt(13f)) })
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                }
                v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (viewType == 1) bottomMargin = d.pt(8f).toInt()
                }
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                when (val r = rows[position]) {
                    is Row.Header -> (holder.itemView as TextView).text = r.title.uppercase()
                    is Row.Item -> {
                        val row = holder.itemView as LinearLayout
                        val img = row.getChildAt(0) as android.widget.ImageView
                        val texts = row.getChildAt(1) as LinearLayout
                        val info = r.info
                        img.setImageDrawable(runCatching { info.loadPreviewImage(context, dm) ?: info.loadIcon(context, dm) }.getOrNull())
                        (texts.getChildAt(0) as TextView).text = runCatching { info.loadLabel(pm) }.getOrDefault(info.provider.className)
                        val (sx, sy) = spanFor(info)
                        (texts.getChildAt(1) as TextView).text = "$sx × $sy"
                        row.setOnClickListener { close { onPick(info) } }
                    }
                }
            }
        }
        col.addView(rv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        sheet.isClickable = true
        addView(sheet, LayoutParams(LayoutParams.MATCH_PARENT, d.height - d.gridTop - d.pt(10f).toInt(), Gravity.BOTTOM).apply {
            leftMargin = d.pt(8f).toInt(); rightMargin = d.pt(8f).toInt()
        })
        setOnClickListener { close() }
    }

    override fun show() {
        super.show()
        sheet.translationY = d.height.toFloat()
        sheet.animate().translationY(0f).setDuration(380).setInterpolator(HomePageView.EASE)
            .setUpdateListener { sheet.invalidate() }.start()
    }

    override fun close(after: (() -> Unit)?) {
        sheet.animate().translationY(d.height.toFloat()).setDuration(260).start()
        super.close(after)
    }
}
