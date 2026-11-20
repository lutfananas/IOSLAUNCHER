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
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ioslauncher.LauncherApp
import com.ioslauncher.icons.IosShape
import com.ioslauncher.model.AppInfo
import com.ioslauncher.model.Categories

data class LibraryCategory(val name: String, val apps: List<AppInfo>, val fourBig: Boolean)

object LibraryModel {
    fun build(apps: List<AppInfo>): List<LibraryCategory> {
        val prefs = LauncherApp.instance.prefs
        val now = System.currentTimeMillis()
        val result = ArrayList<LibraryCategory>()
        val suggestions = apps
            .map { it to score(prefs.launchCount(it.key), prefs.lastLaunch(it.key), now) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(4).map { it.first }
        result += LibraryCategory(Categories.SUGGESTIONS, suggestions.ifEmpty { apps.take(4) }, true)
        val recent = apps.filter { it.installTime > 0 }.sortedByDescending { it.installTime }.take(4)
        if (recent.isNotEmpty()) result += LibraryCategory(Categories.RECENT, recent, true)
        val grouped = apps.groupBy { it.category }
        val ordered = grouped.entries.sortedWith(
            compareByDescending<Map.Entry<String, List<AppInfo>>> { e -> e.value.sumOf { prefs.launchCount(it.key) } }
                .thenByDescending { it.value.size }
        )
        for ((name, list) in ordered) {
            val sorted = list.sortedByDescending { prefs.launchCount(it.key) }
            result += LibraryCategory(name, sorted, false)
        }
        return result
    }

    private fun score(count: Int, last: Long, now: Long): Double {
        if (count == 0) return 0.0
        val hours = ((now - last) / 3_600_000.0).coerceAtLeast(0.0)
        return count / (1.0 + hours / 24.0)
    }
}

/** The App Library page: fixed search pill on top, scrolling 2-column grid of category cards. */
@SuppressLint("ViewConstructor")
class AppLibraryView(context: Context, private val host: LibraryHost) : FrameLayout(context) {
    interface LibraryHost : LauncherHost {
        fun openSearch()
        fun openCategory(category: LibraryCategory, from: Rect)
        fun onLibraryLongPress(app: AppInfo, iconBounds: Rect)
    }

    private val recycler = RecyclerView(context)
    private val adapter = Adapter()
    private val searchPill = SearchPill(context, host.dimens)

    init {
        clipChildren = false
        val d = host.dimens
        recycler.layoutManager = GridLayoutManager(context, 2)
        recycler.adapter = adapter
        recycler.clipToPadding = false
        recycler.overScrollMode = OVER_SCROLL_NEVER
        recycler.setPadding(d.librarySide, d.searchBarTop + d.searchBarHeight + d.pt(32f).toInt(), d.librarySide, d.bottomInset + d.pt(30f).toInt())
        recycler.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(out: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                val pos = parent.getChildAdapterPosition(view)
                val half = d.libraryCardGap / 2
                if (pos % 2 == 0) out.right = half else out.left = half
                out.bottom = d.pt(20f).toInt()
            }
        })
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = Glass.invalidateTree(rv)
        })
        addView(recycler, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, d.searchBarHeight).apply {
            topMargin = d.searchBarTop
            leftMargin = d.librarySide; rightMargin = d.librarySide
        }
        addView(searchPill, lp)
        searchPill.setOnClickListener { host.openSearch() }
        searchPill.text = "App Library"
    }

    fun setApps(apps: List<AppInfo>) {
        adapter.items = LibraryModel.build(apps)
        adapter.notifyDataSetChanged()
    }

    fun refreshIcons() = adapter.notifyDataSetChanged()

    /** Glass cards sample the wallpaper by screen position — redraw them when they move. */
    fun invalidateGlass() { Glass.invalidateTree(this) }

    fun scrollToTop() = recycler.scrollToPosition(0)

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<LibraryCategory> = emptyList()
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val d = host.dimens
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val card = CategoryCardView(context, host)
            col.addView(card, LinearLayout.LayoutParams(d.libraryCard, d.libraryCard))
            val label = TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 0f
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, d.pt(14f))
                typeface = Typeface.create(Typeface.DEFAULT, 500, false)
                gravity = Gravity.CENTER
                maxLines = 1
                setShadowLayer(4f, 0f, 1f, Color.argb(70, 0, 0, 0))
                setPadding(0, d.pt(9f).toInt(), 0, 0)
            }
            col.addView(label, LinearLayout.LayoutParams(d.libraryCard, ViewGroup.LayoutParams.WRAP_CONTENT))
            col.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return object : RecyclerView.ViewHolder(col) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val col = holder.itemView as LinearLayout
            val cat = items[position]
            (col.getChildAt(0) as CategoryCardView).bind(cat)
            (col.getChildAt(1) as TextView).text = cat.name
        }
    }
}

/** Search field look-alike used as the App Library header. */
class SearchPill(context: Context, private val d: Dimens) : View(context) {
    var text = ""; set(v) { field = v; invalidate() }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 255, 255); textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, 400, false)
    }
    private val path = Path()

    init { isClickable = true }

    override fun onDraw(canvas: Canvas) {
        Glass.drawRect(canvas, this, RectF(0f, 0f, width.toFloat(), height.toFloat()), height / 2f, Color.argb(28, 255, 255, 255), false, path)
        tp.textSize = d.pt(19f)
        val tw = tp.measureText(text)
        val g = d.pt(19f)
        val cx = width / 2f + g * 0.7f
        val y = height / 2f - (tp.ascent() + tp.descent()) / 2
        canvas.drawText(text, cx, y, tp)
        drawMagnifier(canvas, cx - tw / 2 - g * 1.2f, height / 2f, g, glyph)
    }

    companion object {
        fun drawMagnifier(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint) {
            paint.strokeWidth = size * 0.13f
            val r = size * 0.32f
            canvas.drawCircle(cx - size * 0.08f, cy - size * 0.08f, r, paint)
            canvas.drawLine(cx + r * 0.6f, cy + r * 0.6f, cx + size * 0.42f, cy + size * 0.42f, paint)
        }
    }
}

/**
 * App Library folder card: glass squircle with three large icons and a 2×2 cluster of small icons
 * (or four large icons for Suggestions / Recently Added, or when the category has ≤ 4 apps).
 */
@SuppressLint("ViewConstructor")
class CategoryCardView(context: Context, private val host: AppLibraryView.LibraryHost) : View(context) {
    private var category: LibraryCategory? = null
    private val bitmaps = HashMap<String, Bitmap>()
    private val path = Path()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 255, 255, 255) }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 0, 0, 0) }
    private var pressedSlot = -1

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { pressedSlot = slotAt(e.x, e.y); invalidate(); return true }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val cat = category ?: return true
            when (val slot = slotAt(e.x, e.y)) {
                -1 -> {}
                3 -> if (cluster()) host.openCategory(cat, screenRect(slotRect(3))) else cat.apps.getOrNull(3)?.let { launch(it, 3) }
                else -> cat.apps.getOrNull(slot)?.let { launch(it, slot) }
            }
            pressedSlot = -1; invalidate()
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            val cat = category ?: return
            val slot = slotAt(e.x, e.y)
            pressedSlot = -1; invalidate()
            if (slot == -1 || (slot == 3 && cluster())) return
            val app = cat.apps.getOrNull(slot) ?: return
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            host.onLibraryLongPress(app, screenRect(slotRect(slot)))
        }
    })

    private fun launch(app: AppInfo, slot: Int) = host.launchApp(this, app, screenRect(slotRect(slot)))

    private fun screenRect(r: RectF): Rect {
        val loc = IntArray(2); getLocationOnScreen(loc)
        return Rect((loc[0] + r.left).toInt(), (loc[1] + r.top).toInt(), (loc[0] + r.right).toInt(), (loc[1] + r.bottom).toInt())
    }

    private fun cluster(): Boolean { val c = category ?: return false; return !c.fourBig && c.apps.size > 4 }

    fun bind(cat: LibraryCategory) {
        category = cat
        bitmaps.clear()
        val big = bigSize()
        val small = smallSize()
        val icons = LauncherApp.instance.icons
        val show = if (cluster()) cat.apps.take(3).map { it to big } + cat.apps.drop(3).take(4).map { it to small }
        else cat.apps.take(4).map { it to big }
        for ((app, size) in show) {
            icons.load(app, size) { bmp -> if (category === cat) { bitmaps["${app.key}@$size"] = bmp; invalidate() } }
        }
        invalidate()
    }

    // iOS proportions: padding 7.5% of the card, big icons 38.7%, small icons 41% of a big one,
    // the 2×2 cluster centred in the fourth cell.
    private fun pad() = host.dimens.libraryCard * 0.075f
    private fun bigSize() = ((host.dimens.libraryCard - 3 * pad()) / 2f).toInt()
    private fun smallGap() = bigSize() * 0.09f
    private fun smallSize() = (bigSize() * 0.41f).toInt()

    private fun slotRect(slot: Int): RectF {
        val p = pad(); val b = bigSize()
        val col = slot % 2; val row = slot / 2
        val l = p + col * (b + p); val t = p + row * (b + p)
        return RectF(l, t, l + b, t + b)
    }

    private fun slotAt(x: Float, y: Float): Int {
        for (i in 0..3) if (slotRect(i).contains(x, y)) return i
        return -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val r = gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            pressedSlot = -1; invalidate()
        }
        return r || true
    }

    override fun onDraw(canvas: Canvas) {
        val cat = category ?: return
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        IosShape.roundRect(r, width * 0.147f, path)
        Glass.draw(canvas, this, path, r, width * 0.147f, Color.argb(30, 255, 255, 255), shadow = false)
        val big = bigSize(); val small = smallSize()
        val bigApps = if (cluster()) cat.apps.take(3) else cat.apps.take(4)
        bigApps.forEachIndexed { i, app ->
            val r = slotRect(i)
            bitmaps["${app.key}@$big"]?.let { canvas.drawBitmap(it, null, r, bmpPaint) }
            if (pressedSlot == i) drawDim(canvas, r)
        }
        if (cluster()) {
            val r = slotRect(3)
            val g = smallGap()
            cat.apps.drop(3).take(4).forEachIndexed { i, app ->
                val off = (r.width() - (2 * small + g)) / 2f
                val l = r.left + off + (i % 2) * (small + g)
                val t = r.top + off + (i / 2) * (small + g)
                bitmaps["${app.key}@$small"]?.let { canvas.drawBitmap(it, null, RectF(l, t, l + small, t + small), bmpPaint) }
            }
        }
    }

    private fun drawDim(canvas: Canvas, r: RectF) {
        canvas.drawPath(IosShape.roundRect(r, r.width() * IosShape.ICON_CORNER_RATIO), dimPaint)
    }
}
