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

    open fun show() {
        setBackgroundColor(scrimColor)
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
        val barTop = d.gridTop - d.pt(4f).toInt() + d.pt(15f).toInt()
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
 * Two-row 3D icon drum. Apps sit on the outside of a horizontal cylinder (column-major: two apps per
 * column) and the whole drum spins as you swipe. Each icon is a real perspective-projected quad
 * (Matrix.setPolyToPoly), drawn back-to-front with depth fading, a floor glow, a mirrored reflection
 * under the bottom row and labels that fade as icons turn away. Swipes have momentum and the drum
 * snaps to a column with a small overshoot.
 */
class IconDrum(context: Context, private val d: Dimens) : View(context) {
    var onLaunch: (AppInfo, Rect) -> Unit = { _, _ -> }
    var onLongPress: (AppInfo, Rect) -> Unit = { _, _ -> }
    var onDragStart: () -> Unit = {}
    var onFocusChanged: (AppInfo?) -> Unit = {}

    private var apps: List<AppInfo> = emptyList()
    private val bitmaps = HashMap<String, Bitmap>()
    private val icon = d.pt(74f)                     // front icon size
    private val rowGap = d.pt(44f)                   // gap between the two rows
    private val stepDeg = 30f                        // angle between columns (tighter drum = stronger 3D)
    private val radius = (icon + d.pt(16f)) / (2f * kotlin.math.sin(Math.toRadians(stepDeg / 2.0)).toFloat())
    private val focal = d.pt(460f)                   // perspective strength
    private val tilt = Math.toRadians(12.0)          // camera looks slightly down onto the drum
    private val cosT = kotlin.math.cos(tilt).toFloat()
    private val sinT = kotlin.math.sin(tilt).toFloat()
    private val iconPx = icon.toInt()

    /** Column at the front, fractional while spinning. */
    private var pos = 0f
    private var lastFocusCol = -1
    private var anim: android.animation.ValueAnimator? = null
    private var introT = 1f

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        setShadowLayer(6f, 0f, 1f, Color.argb(90, 0, 0, 0))
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fadePaint = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) }
    private val m = android.graphics.Matrix()
    private val src = FloatArray(8)
    private val dst = FloatArray(8)

    private data class Item(val index: Int, val app: AppInfo, val z: Float, val quad: FloatArray, val facing: Float, val row: Int, val col: Int)
    private var drawn: List<Item> = emptyList()

    fun preferredHeight() = (2 * icon + rowGap + d.pt(96f)).toInt()

    private val columns get() = (apps.size + 1) / 2

    fun setApps(list: List<AppInfo>) {
        apps = list
        anim?.cancel()
        pos = 0f; lastFocusCol = -1
        val want = list.take(80).map { it.key }.toSet()
        bitmaps.keys.retainAll(want)
        list.take(80).forEach { load(it) }
        notifyFocus()
        invalidate()
    }

    private fun load(a: AppInfo) {
        if (bitmaps.containsKey(a.key)) return
        LauncherApp.instance.icons.load(a, iconPx) { b -> bitmaps[a.key] = b; invalidate() }
    }

    fun focusedApp(): AppInfo? = apps.getOrNull(Math.round(pos).coerceAtLeast(0) * 2)

    fun scrollToItem(index: Int, animate: Boolean) {
        val col = (index / 2).toFloat()
        if (animate) animateTo(col, 520) else { pos = col; invalidate(); notifyFocus() }
    }

    fun playIntro() {
        introT = 0f
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700; interpolator = android.view.animation.OvershootInterpolator(0.9f)
            addUpdateListener { introT = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun notifyFocus() {
        val c = Math.round(pos)
        if (c != lastFocusCol) {
            if (lastFocusCol != -1) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            lastFocusCol = c
            onFocusChanged(focusedApp())
        }
    }

    private fun animateTo(target: Float, duration: Long) {
        anim?.cancel()
        val t = target.coerceIn(0f, (columns - 1).coerceAtLeast(0).toFloat())
        anim = android.animation.ValueAnimator.ofFloat(pos, t).apply {
            this.duration = duration
            interpolator = android.view.animation.OvershootInterpolator(1.05f)
            addUpdateListener { pos = it.animatedValue as Float; notifyFocus(); invalidate() }
            start()
        }
    }

    // ---- projection --------------------------------------------------------------------------

    private fun project(x: Float, y: Float, z: Float, out: FloatArray, o: Int) {
        // Tilt the drum about its own axis (z = radius) so the far side rises, then perspective-divide.
        val zr = z - radius
        val y2 = y * cosT - zr * sinT
        val z2 = y * sinT + zr * cosT + radius * cosT
        val p = focal / (focal + z2)
        out[o] = width / 2f + x * p
        out[o + 1] = centerY() + y2 * p
    }

    private fun centerY() = icon / 2f + d.pt(18f) + rowGap / 2f + icon / 2f

    override fun onDraw(canvas: Canvas) {
        if (apps.isEmpty()) return
        val items = ArrayList<Item>()
        val spin = (1f - introT) * 3.5f                       // intro: drum spins into place
        val first = (pos - 5).toInt().coerceAtLeast(0)
        val last = (pos + 5).toInt().coerceAtMost(columns - 1)
        val h = icon / 2f
        for (col in first..last) {
            val thetaDeg = (col - pos - spin) * stepDeg
            if (kotlin.math.abs(thetaDeg) > 80f) continue
            val th = Math.toRadians(thetaDeg.toDouble())
            val s = kotlin.math.sin(th).toFloat(); val c = kotlin.math.cos(th).toFloat()
            val cx = radius * s
            val cz = radius * (1 - c)
            for (row in 0..1) {
                val idx = col * 2 + row
                val a = apps.getOrNull(idx) ?: continue
                val cy = if (row == 0) -rowGap / 2f - h else rowGap / 2f + h
                val q = FloatArray(8)
                // Corners: tangent half-vector (c, 0, s)·h  and vertical ±h.
                project(cx - c * h, cy - h, cz - s * h, q, 0)
                project(cx + c * h, cy - h, cz + s * h, q, 2)
                project(cx + c * h, cy + h, cz + s * h, q, 4)
                project(cx - c * h, cy + h, cz - s * h, q, 6)
                items += Item(idx, a, cz, q, c, row, col)
            }
        }
        items.sortByDescending { it.z }
        drawn = items

        // Floor glow under the drum, just below the front bottom icon.
        val front = items.filter { it.row == 1 }.minByOrNull { it.z }
        val floorY = front?.let { maxOf(it.quad[5], it.quad[7]) + d.pt(2f) } ?: (centerY() + rowGap / 2f + icon)
        glowPaint.shader = android.graphics.RadialGradient(width / 2f, floorY, width * 0.45f,
            Color.argb(70, 255, 255, 255), Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP)
        canvas.save(); canvas.scale(1f, 0.16f, width / 2f, floorY)
        canvas.drawCircle(width / 2f, floorY, width * 0.45f, glowPaint)
        canvas.restore()

        // Reflections of the bottom row (faded with a vertical mask).
        val reflTop = floorY - d.pt(4f)
        val reflH = icon * 0.55f
        val layer = canvas.saveLayer(0f, reflTop, width.toFloat(), reflTop + reflH, null)
        for (it in items) {
            if (it.row != 1 || it.facing < 0.2f) continue
            val b = bitmaps[it.app.key] ?: continue
            val q = it.quad
            // Mirror the quad about its bottom edge.
            dst[0] = q[6]; dst[1] = q[7]; dst[2] = q[4]; dst[3] = q[5]
            dst[4] = q[2]; dst[5] = 2 * q[5] - q[3]; dst[6] = q[0]; dst[7] = 2 * q[7] - q[1]
            drawQuad(canvas, b, dst, (70 * it.facing).toInt())
        }
        glowPaint.shader = null
        fadePaint.shader = android.graphics.LinearGradient(0f, reflTop, 0f, reflTop + reflH,
            Color.argb(255, 0, 0, 0), Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, reflTop, width.toFloat(), reflTop + reflH, fadePaint)
        canvas.restoreToCount(layer)

        // Icons back-to-front, darker and fainter the further they turn away.
        for (it in items) {
            val b = bitmaps[it.app.key] ?: continue
            val k = it.facing.coerceIn(0f, 1f)
            val alpha = (255 * (0.2f + 0.8f * k * kotlin.math.sqrt(k)) * introT.coerceIn(0f, 1f)).toInt()
            // Side icons turn away from the light: darken them.
            val lum = (255 * (0.55f + 0.45f * k)).toInt()
            paint.colorFilter = if (lum < 252) android.graphics.LightingColorFilter(Color.rgb(lum, lum, lum), 0) else null
            drawQuad(canvas, b, it.quad, alpha)
            paint.colorFilter = null
            if (it.facing > 0.55f) {
                val q = it.quad
                val p = focal / (focal + it.z)
                labelPaint.textSize = d.pt(12.5f) * p
                labelPaint.alpha = (255 * ((it.facing - 0.55f) / 0.45f).coerceIn(0f, 1f) * introT.coerceIn(0f, 1f)).toInt()
                val text = android.text.TextUtils.ellipsize(it.app.label, android.text.TextPaint(labelPaint), icon * 1.25f * p,
                    android.text.TextUtils.TruncateAt.END).toString()
                canvas.drawText(text, (q[4] + q[6]) / 2f, maxOf(q[5], q[7]) + d.pt(15f) * p, labelPaint)
            }
        }
    }

    private fun drawQuad(canvas: Canvas, b: Bitmap, q: FloatArray, alpha: Int) {
        src[0] = 0f; src[1] = 0f; src[2] = b.width.toFloat(); src[3] = 0f
        src[4] = b.width.toFloat(); src[5] = b.height.toFloat(); src[6] = 0f; src[7] = b.height.toFloat()
        m.setPolyToPoly(src, 0, q, 0, 4)
        paint.alpha = alpha.coerceIn(0, 255)
        canvas.drawBitmap(b, m, paint)
    }

    fun iconRectOnScreen(a: AppInfo): Rect? {
        val it = drawn.firstOrNull { x -> x.app.key == a.key } ?: return null
        val loc = IntArray(2); getLocationOnScreen(loc)
        val q = it.quad
        val l = minOf(q[0], q[6]); val r = maxOf(q[2], q[4]); val t = minOf(q[1], q[3]); val btm = maxOf(q[5], q[7])
        return Rect((loc[0] + l).toInt(), (loc[1] + t).toInt(), (loc[0] + r).toInt(), (loc[1] + btm).toInt())
    }

    private fun hit(x: Float, y: Float): Item? = drawn.asReversed().firstOrNull { it ->
        val q = it.quad
        it.facing > 0.2f && x in minOf(q[0], q[6])..maxOf(q[2], q[4]) && y in minOf(q[1], q[3])..maxOf(q[5], q[7])
    }

    // ---- touch: drag to spin, momentum, snap; tap = focus or launch; long-press = menu ---------

    private var downX = 0f; private var downY = 0f; private var lastX = 0f
    private var dragging = false
    private var longFired = false
    private var vt: android.view.VelocityTracker? = null
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val colPx get() = radius * Math.toRadians(stepDeg.toDouble()).toFloat()
    private val longPress = Runnable {
        val it = hit(downX, downY) ?: return@Runnable
        longFired = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        iconRectOnScreen(it.app)?.let { r -> onLongPress(it.app, r) }
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
                    var delta = (lastX - e.x) / colPx
                    if (pos + delta < 0 || pos + delta > columns - 1) delta *= 0.35f      // rubber band at the ends
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
                    val target = Math.round(pos - v / colPx * 0.28f).toFloat()
                    animateTo(target, (380 + kotlin.math.abs(target - pos) * 45).toLong().coerceAtMost(1100))
                } else if (!longFired) {
                    hit(e.x, e.y)?.let { it ->
                        if (it.col == Math.round(pos)) iconRectOnScreen(it.app)?.let { r -> onLaunch(it.app, r) }
                        else animateTo(it.col.toFloat(), 420)
                    }
                }
                vt?.recycle(); vt = null
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (dragging) animateTo(Math.round(pos).toFloat(), 360)
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
