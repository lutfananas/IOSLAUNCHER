/*
 * iOS Launcher — Copyright (c) 2026 lutfananas. All rights reserved. See LICENSE.
 */
package com.ioslauncher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ioslauncher.LauncherApp
import com.ioslauncher.R
import com.ioslauncher.icons.IconPack
import com.ioslauncher.icons.IosShape
import com.ioslauncher.model.AppInfo
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Per-app icon: pick your own image (photo picker) or any icon from an installed icon pack.
 * Whatever is chosen is fitted into the exact iOS squircle.
 */
class IconChooserActivity : ComponentActivity() {
    private val launcher get() = LauncherApp.instance
    private lateinit var app: AppInfo
    private lateinit var content: FrameLayout
    private val dp get() = resources.displayMetrics.density
    private val worker = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val thumbs = LruCache<String, Bitmap>(400)
    private var browsingPack: String? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { savePhoto(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra(EXTRA_KEY)
        val found = key?.let { launcher.repository.find(it) }
        if (found == null) { finish(); return }
        app = found
        window.setDecorFitsSystemWindows(false)
        content = FrameLayout(this)
        setContentView(content)
        content.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
            v.setPadding(0, bars.top, 0, bars.bottom); insets
        }
        showMain()
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (browsingPack != null) showMain() else finish() }
        })
    }

    private fun applyIcon(spec: String?) {
        launcher.prefs.setCustomIcon(app.key, spec)
        launcher.icons.invalidate()
        Toast.makeText(this, if (spec == null) "Icon reset" else "Icon updated", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun savePhoto(uri: Uri) {
        worker.execute {
            val path = runCatching {
                val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { dec, info, _ ->
                    val s = info.size
                    val scale = (512f / minOf(s.width, s.height)).coerceAtMost(1f)
                    dec.setTargetSize((s.width * scale).toInt().coerceAtLeast(1), (s.height * scale).toInt().coerceAtLeast(1))
                    dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                val dir = File(filesDir, "icons").apply { mkdirs() }
                val f = File(dir, "${app.key.hashCode().toUInt()}_${System.currentTimeMillis()}.png")
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                // Remove older custom image for this app.
                launcher.prefs.customIcon(app.key)?.takeIf { it.startsWith("file:") }?.let { File(it.removePrefix("file:")).delete() }
                f.absolutePath
            }.getOrNull()
            main.post { if (path != null) applyIcon("file:$path") else Toast.makeText(this, "Couldn't read that image", Toast.LENGTH_SHORT).show() }
        }
    }

    // ------------------------------------------------------------------------------------------

    private fun showMain() {
        browsingPack = null
        content.removeAllViews()
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        content.addView(scroll)

        list.addView(title("Edit Icon"))
        // Current icon preview
        val preview = ImageView(this)
        val size = (96 * dp).toInt()
        list.addView(preview, LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = (8 * dp).toInt() })
        launcher.icons.load(app, size) { preview.setImageBitmap(it) }
        list.addView(TextView(this).apply {
            text = app.label; gravity = Gravity.CENTER; setTextColor(getColor(R.color.settings_text)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        })

        section(list, null)
        group(list, listOfNotNull(
            row("Choose Photo…", accent = true) {
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            if (launcher.prefs.customIcon(app.key) != null) row("Reset to Default Icon", destructive = true) { applyIcon(null) } else null,
        ))

        section(list, "FROM ICON PACK")
        val packs = IconPack.findInstalled(this)
        if (packs.isEmpty()) {
            group(list, listOf(row("No icon packs installed") {}))
        } else {
            group(list, packs.map { p -> row(p.label, chevron = true) { showPack(p.packageName, p.label) } })
        }
        footer(list, "Any image or icon you choose is fitted to the iOS app icon shape.")
    }

    private fun showPack(pkg: String, label: String) {
        browsingPack = pkg
        content.removeAllViews()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(col)
        col.addView(TextView(this).apply {
            text = "‹ Back"; setTextColor(Color.rgb(0, 122, 255)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), 0)
            setOnClickListener { showMain() }
        })
        col.addView(title(label))
        val search = EditText(this).apply {
            hint = "Search icons"; isSingleLine = true
            setTextColor(getColor(R.color.settings_text)); setHintTextColor(getColor(R.color.settings_secondary))
            background = SquircleDrawable(getColor(R.color.settings_cell), 10 * dp)
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
        }
        col.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = (16 * dp).toInt(); rightMargin = (16 * dp).toInt(); bottomMargin = (8 * dp).toInt()
        })
        val loading = TextView(this).apply { text = "Loading icons…"; gravity = Gravity.CENTER; setTextColor(getColor(R.color.settings_secondary)) }
        col.addView(loading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (60 * dp).toInt()))
        val rv = RecyclerView(this)
        val span = 5
        rv.layoutManager = GridLayoutManager(this, span)
        col.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        rv.setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), (16 * dp).toInt())
        rv.clipToPadding = false

        worker.execute {
            val pack = launcher.icons.pack(pkg)
            val all = pack?.allIconNames ?: emptyList()
            val suggested = pack?.suggestionsFor(app.packageName) ?: emptyList()
            main.post {
                if (browsingPack != pkg) return@post
                loading.visibility = View.GONE
                val adapter = PackAdapter(pkg, suggested, all)
                rv.adapter = adapter
                search.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) = adapter.filter(s?.toString().orEmpty())
                })
            }
        }
    }

    private inner class PackAdapter(val pkg: String, val suggested: List<String>, val all: List<String>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<String> = (suggested + all).distinct()
        fun filter(q: String) {
            val query = q.trim().lowercase().replace(" ", "_")
            items = if (query.isEmpty()) (suggested + all).distinct() else all.filter { it.lowercase().contains(query) }
            notifyDataSetChanged()
        }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = ImageView(this@IconChooserActivity)
            val s = (parent.width / 5).coerceAtLeast((56 * dp).toInt())
            v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, s)
            val pad = (10 * dp).toInt(); v.setPadding(pad, pad, pad, pad)
            return object : RecyclerView.ViewHolder(v) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView as ImageView
            val name = items[position]
            v.tag = name
            v.setImageBitmap(thumbs.get(name))
            v.contentDescription = name
            v.setOnClickListener { applyIcon("pack:$pkg/$name") }
            if (thumbs.get(name) == null) worker.execute {
                val bmp = launcher.icons.customRaw("pack:$pkg/$name", (64 * dp).toInt()) ?: return@execute
                thumbs.put(name, bmp)
                main.post { if (v.tag == name) v.setImageBitmap(bmp) }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // iOS grouped-list helpers (same look as Settings)

    private fun title(t: String) = TextView(this).apply {
        text = t; setTextColor(getColor(R.color.settings_text))
        typeface = Typeface.create(Typeface.DEFAULT, 700, false)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (10 * dp).toInt())
    }

    private fun section(list: LinearLayout, t: String?) = list.addView(TextView(this).apply {
        text = t ?: ""; setTextColor(getColor(R.color.settings_secondary)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding((36 * dp).toInt(), (20 * dp).toInt(), 0, (6 * dp).toInt())
    })

    private fun footer(list: LinearLayout, t: String) = list.addView(TextView(this).apply {
        text = t; setTextColor(getColor(R.color.settings_secondary)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding((36 * dp).toInt(), (6 * dp).toInt(), (36 * dp).toInt(), (30 * dp).toInt())
    })

    private fun group(list: LinearLayout, rows: List<View>) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SquircleDrawable(getColor(R.color.settings_cell), 12 * dp); clipToOutline = true
        }
        rows.forEachIndexed { i, r ->
            if (i > 0) box.addView(View(this).apply { setBackgroundColor(getColor(R.color.settings_separator)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { leftMargin = (16 * dp).toInt() })
            box.addView(r)
        }
        list.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = (16 * dp).toInt(); rightMargin = (16 * dp).toInt()
        })
    }

    private fun row(t: String, accent: Boolean = false, destructive: Boolean = false, chevron: Boolean = false, onClick: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (48 * dp).toInt()
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(40, 128, 128, 128)), null, null)
            setOnClickListener { onClick() }
            addView(TextView(context).apply {
                text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(when { destructive -> Color.rgb(255, 59, 48); accent -> Color.rgb(0, 122, 255); else -> getColor(R.color.settings_text) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (chevron) addView(TextView(context).apply { text = "›"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); setTextColor(getColor(R.color.settings_secondary)) })
        }

    companion object { const val EXTRA_KEY = "app_key" }
}
