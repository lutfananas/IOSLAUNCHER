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
// Search: icon carousel (cover-flow) + live filter + A–Z strip + suggestions
// ---------------------------------------------------------------------------------------------------

@SuppressLint("ViewConstructor")
class SearchOverlay(context: Context, host: OverlayHost, private val allApps: List<AppInfo>) : Overlay(context, host) {
    override val scrimColor = Color.argb(120, 0, 0, 0)
    private val d = host.dimens
    private val field = EditText(context)
    private val content = FrameLayout(context)
    private val carousel = RecyclerView(context)
    private val lm = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
    private val adapter = CarouselAdapter()
    private val nameView = TextView(context)
    private val subView = TextView(context)
    private val countView = TextView(context)
    private val letters = LetterStrip(context)
    private val suggestions = LinearLayout(context)
    private val suggestionsTitle = TextView(context)

    /** Big centre icon, spacing between icon centres. */
    private val bigIcon = d.pt(96f).toInt()
    private val itemW = d.pt(104f).toInt()
    private var centered = -1
    private var results: List<AppInfo> = emptyList()
    private val sorted = allApps.sortedWith(compareBy<AppInfo> { letterOf(it) == "#" }.thenBy { it.label.lowercase() })

    init {
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // ---- search field ------------------------------------------------------------------
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
                    results.getOrNull(centered.coerceAtLeast(0))?.let { launch(it, centerIconRect()) }
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
        val barTop = d.gridTop - d.pt(4f).toInt()
        content.addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, d.searchBarHeight).apply {
            topMargin = barTop; leftMargin = d.librarySide; rightMargin = d.librarySide
        })

        // ---- carousel ----------------------------------------------------------------------
        val carouselTop = barTop + d.searchBarHeight + d.pt(26f).toInt()
        val carouselH = (bigIcon * 1.25f).toInt()
        val side = (d.width - itemW) / 2
        carousel.layoutManager = lm
        carousel.adapter = adapter
        carousel.clipToPadding = false
        carousel.clipChildren = false
        carousel.overScrollMode = OVER_SCROLL_NEVER
        carousel.setPadding(side, 0, side, 0)
        androidx.recyclerview.widget.LinearSnapHelper().attachToRecyclerView(carousel)
        carousel.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = transformChildren()
            override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) hideKeyboard()
            }
        })
        content.addView(carousel, LayoutParams(LayoutParams.MATCH_PARENT, carouselH).apply { topMargin = carouselTop })

        // ---- centred app name / category ----------------------------------------------------
        nameView.apply {
            setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 1
            typeface = Typeface.create(Typeface.DEFAULT, 700, false)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, d.pt(22f))
            setShadowLayer(8f, 0f, 1f, Color.argb(80, 0, 0, 0))
        }
        subView.apply {
            setTextColor(Color.argb(190, 255, 255, 255)); gravity = Gravity.CENTER; maxLines = 1
            setTextSize(TypedValue.COMPLEX_UNIT_PX, d.pt(14f))
        }
        val nameTop = carouselTop + carouselH + d.pt(4f).toInt()
        content.addView(nameView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = nameTop; leftMargin = d.librarySide; rightMargin = d.librarySide
        })
        content.addView(subView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = nameTop + d.pt(30f).toInt()
        })
        countView.apply {
            setTextColor(Color.argb(160, 255, 255, 255)); gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_PX, d.pt(13f))
        }
        content.addView(countView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = carouselTop - d.pt(20f).toInt()
        })

        // ---- A–Z strip -----------------------------------------------------------------------
        letters.onLetter = { l -> jumpToLetter(l) }
        content.addView(letters, LayoutParams(LayoutParams.MATCH_PARENT, d.pt(30f).toInt()).apply {
            topMargin = nameTop + d.pt(60f).toInt(); leftMargin = d.pt(12f).toInt(); rightMargin = d.pt(12f).toInt()
        })

        // ---- suggestions (most used) -----------------------------------------------------------
        suggestionsTitle.apply {
            text = "SUGGESTIONS"; setTextColor(Color.argb(170, 255, 255, 255))
            typeface = Typeface.create(Typeface.DEFAULT, 600, false)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, d.pt(13f))
        }
        val sugTop = nameTop + d.pt(108f).toInt()
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
        adapter.notifyDataSetChanged()
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
        centered = -1
        lm.scrollToPositionWithOffset(0, 0)
        carousel.post { transformChildren() }
        if (results.isEmpty()) { nameView.text = ""; subView.text = "" }
    }

    /** Cover-flow: centre item full size; neighbours shrink, fade, tilt away and dip on an arc. */
    private fun transformChildren() {
        val cx = carousel.width / 2f
        var best = -1; var bestDist = Float.MAX_VALUE
        for (i in 0 until carousel.childCount) {
            val c = carousel.getChildAt(i)
            val mid = (c.left + c.right) / 2f
            val dist = mid - cx
            val t = (kotlin.math.abs(dist) / (itemW * 2.2f)).coerceIn(0f, 1f)
            val s = 1f - 0.5f * t
            c.scaleX = s; c.scaleY = s
            c.alpha = 1f - 0.65f * t
            c.rotationY = (-dist / itemW * 22f).coerceIn(-55f, 55f)
            c.translationY = t * t * d.pt(26f)
            c.cameraDistance = 8000 * resources.displayMetrics.density
            if (kotlin.math.abs(dist) < bestDist) { bestDist = kotlin.math.abs(dist); best = carousel.getChildAdapterPosition(c) }
        }
        if (best >= 0 && best != centered) {
            centered = best
            val a = results.getOrNull(best) ?: return
            nameView.text = a.label
            subView.text = a.category
            letters.current = letterOf(a)
            carousel.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun jumpToLetter(l: String) {
        val pos = results.indexOfFirst { letterOf(it) == l }
        if (pos >= 0) { lm.scrollToPositionWithOffset(pos, 0); carousel.post { transformChildren() } }
    }

    private fun centerOn(position: Int) {
        val v = lm.findViewByPosition(position)
        if (v == null) { lm.scrollToPositionWithOffset(position, 0); carousel.post { transformChildren() }; return }
        carousel.smoothScrollBy(((v.left + v.right) / 2 - carousel.width / 2), 0)
    }

    private fun centerIconRect(): Rect? {
        val v = lm.findViewByPosition(centered) ?: return null
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        val l = loc[0] + (v.width - bigIcon) / 2; val t = loc[1] + (v.height - bigIcon) / 2
        return Rect(l, t, l + bigIcon, t + bigIcon)
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
        // Carousel sweeps in from the right.
        carousel.translationX = d.width * 0.4f; carousel.alpha = 0f
        carousel.animate().translationX(0f).alpha(1f).setDuration(420).setInterpolator(HomePageView.EASE).start()
        field.requestFocus()
        field.post { field.windowInsetsController?.show(WindowInsets.Type.ime()) }
    }

    fun hideKeyboard() { field.windowInsetsController?.hide(WindowInsets.Type.ime()); field.clearFocus() }

    override fun close(after: (() -> Unit)?) {
        hideKeyboard()
        content.animate().translationY(-d.pt(30f)).setDuration(200).start()
        super.close(after)
    }

    private inner class CarouselAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = results.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = CarouselIcon(context)
            v.layoutParams = RecyclerView.LayoutParams(itemW, ViewGroup.LayoutParams.MATCH_PARENT)
            return object : RecyclerView.ViewHolder(v) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView as CarouselIcon
            val a = results[position]
            v.bind(a)
            v.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos == centered) launch(a, centerIconRect()) else centerOn(pos)
            }
            v.setOnLongClickListener {
                val loc = IntArray(2); v.getLocationOnScreen(loc)
                val l = loc[0] + (v.width - bigIcon) / 2; val t = loc[1] + (v.height - bigIcon) / 2
                hideKeyboard()
                host.onLibraryLongPress(a, Rect(l, t, l + bigIcon, t + bigIcon)); true
            }
        }
    }

    /** One carousel tile: big squircle icon with a soft reflection-like shadow. */
    private inner class CarouselIcon(context: Context) : View(context) {
        private var app: AppInfo? = null
        private var bmp: Bitmap? = null
        private val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(60, 0, 0, 0)
            maskFilter = android.graphics.BlurMaskFilter(d.pt(10f), android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        init { isClickable = true; isLongClickable = true; setLayerType(LAYER_TYPE_SOFTWARE, null) }

        fun bind(a: AppInfo) {
            app = a; bmp = null; contentDescription = a.label
            LauncherApp.instance.icons.load(a, bigIcon) { if (app?.key == a.key) { bmp = it; invalidate() } }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val l = (width - bigIcon) / 2f; val t = (height - bigIcon) / 2f
            canvas.drawOval(RectF(l + bigIcon * 0.12f, t + bigIcon * 0.9f, l + bigIcon * 0.88f, t + bigIcon * 1.08f), shadow)
            bmp?.let { canvas.drawBitmap(it, null, RectF(l, t, l + bigIcon, t + bigIcon), p) }
        }
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
