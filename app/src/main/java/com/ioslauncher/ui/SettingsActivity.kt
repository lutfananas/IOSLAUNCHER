/*
 * iOS Launcher — Copyright (c) 2026 lutfananas. All rights reserved. See LICENSE.
 */
package com.ioslauncher.ui

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.ioslauncher.LauncherApp
import com.ioslauncher.R
import com.ioslauncher.icons.IconPack
import com.ioslauncher.icons.IosShape
import kotlin.math.roundToInt

/** iOS-style grouped settings: icon pack picker, icon & home options, wallpaper, default launcher. */
class SettingsActivity : Activity() {
    companion object { const val MIN_SCALE = 50; const val MAX_SCALE = 130 }

    private val prefs get() = LauncherApp.instance.prefs
    private lateinit var list: LinearLayout
    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        build()
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        list.removeAllViews()
        list.addView(TextView(this).apply {
            text = getString(R.string.settings_title)
            setTextColor(getColor(R.color.settings_text))
            typeface = Typeface.create(Typeface.DEFAULT, 700, false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            setPadding((20 * dp).toInt(), (24 * dp).toInt(), 0, (10 * dp).toInt())
        })

        val roleManager = getSystemService(RoleManager::class.java)
        if (!roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
            section(null)
            group(listOf(row("Set as Default Home App", accent = true) {
                startActivityForResult(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME), 1)
            }))
        }

        section("ICON PACK")
        val packs = IconPack.findInstalled(this)
        val current = prefs.iconPack
        val packRows = ArrayList<View>()
        packRows += row("System Icons", checked = current.isEmpty(), icon = appIcon(null)) { selectPack("") }
        for (p in packs) packRows += row(p.label, checked = current == p.packageName, icon = appIcon(p.packageName)) { selectPack(p.packageName) }
        group(packRows)
        footer(if (packs.isEmpty()) "No icon packs installed. Install any ADW / Nova / Apex compatible icon pack from the Play Store and it will show up here."
        else "Icons from the pack are drawn into the exact iOS app icon shape. Apps the pack doesn't cover use the pack's own mask, or the system icon.")

        section("HOME SCREEN GRID")
        group(listOf(
            stepperRow("Columns", prefs.gridCols) { prefs.gridCols = it },
            stepperRow("Rows", prefs.gridRows) { prefs.gridRows = it },
            switchRow("Free Placement (No Auto-Fill)", prefs.freePlacement) { prefs.freePlacement = it },
        ))
        footer("Free Placement: icons stay exactly in the cell you drop them and empty spaces stay empty; dropping on another icon swaps them. Up to 8 × 8. Icons and names shrink automatically on dense grids. Page 1 keeps its top row for the clock and status.")

        section("DOCK")
        group(listOf(
            switchRow("Show Dock", prefs.dockEnabled) { prefs.dockEnabled = it },
            switchRow("Hide Dock on Page 1 Only", prefs.hideDockOnFirstPage) { prefs.hideDockOnFirstPage = it },
        ))
        footer("When the dock is off, its apps move onto the Home Screen and the grid uses the freed space. The dock glass shrinks to fit the number of apps in it.")

        section("LIQUID GLASS")
        val wallpaperOk = android.os.Environment.isExternalStorageManager()
        group(listOf(
            switchRow("Liquid Glass Refraction", prefs.liquidGlass) { prefs.liquidGlass = it },
            amountRow("Blur", prefs.glassBlur) { prefs.glassBlur = it },
            amountRow("Refraction (edge bend)", prefs.glassRefraction) { prefs.glassRefraction = it },
            amountRow("Frost / Tint", prefs.glassFrost) { prefs.glassFrost = it },
            amountRow("Shine (rim & highlight)", prefs.glassShine) { prefs.glassShine = it },
            row("Reset Glass to Default") {
                prefs.glassBlur = 1f; prefs.glassRefraction = 1f; prefs.glassFrost = 1f; prefs.glassShine = 1f; build()
            },
            row(if (wallpaperOk) "Wallpaper Access: On" else "Allow Wallpaper Access", accent = !wallpaperOk) {
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.fromParts("package", packageName, null)))
                }
            },
        ))
        footer(when (Glass.status) {
            Glass.Status.REAL -> "● Glass is sampling your wallpaper: Blur and Refraction are active."
            Glass.Status.NO_ACCESS -> "● Glass can't read the wallpaper yet, so it uses frosted glass (Blur sets how frosted). Tap “Allow Wallpaper Access” for real blur and refraction."
            Glass.Status.LIVE_WALLPAPER -> "● A live wallpaper is set: Android doesn't expose it as an image, so glass is frosted (Blur sets how frosted). Use a static wallpaper for real blur and refraction."
            Glass.Status.UNKNOWN -> "● Open the Home Screen once to detect the wallpaper."
        })
        footer("Glass surfaces (dock, folders, App Library, menus) bend and blur the wallpaper like iOS. Android 14+ only lets an app read the wallpaper with “All files access”; without it, glass falls back to a frosted look. Live wallpapers always use the frosted look.")

        section("ICON SIZE")
        group(listOf(
            sliderRow("Home Screen", prefs.homeIconScale) { prefs.homeIconScale = it },
            sliderRow("Dock", prefs.dockIconScale) { prefs.dockIconScale = it },
        ))
        footer("100% = iOS standard icon size. Home Screen and Dock are sized separately.")

        section("ICONS")
        group(listOf(
            switchRow("Hide App Names", !prefs.showLabels) { prefs.showLabels = !it },
            switchRow("Fit Icons to iOS Shape", prefs.normalizePackIcons) { prefs.normalizePackIcons = it; LauncherApp.instance.icons.invalidate() },
        ))

        section("HOME SCREEN")
        group(listOf(
            switchRow("Hide Status Bar", prefs.hideStatusBar) { prefs.hideStatusBar = it; restartLauncher() },
            switchRow("Unlock Animation", prefs.unlockAnimation) { prefs.unlockAnimation = it },
            switchRow("Close App into Icon (Experimental)", prefs.closeToIcon) { prefs.closeToIcon = it },
            switchRow("Parallax & Glass Tilt", prefs.parallax) { prefs.parallax = it },
            switchRow("Add New Apps to Home Screen", prefs.autoAddNewApps) { prefs.autoAddNewApps = it },
            row("Change Wallpaper") {
                runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper")) }
            },
        ))
        footer("Close App into Icon is experimental: on some phones it makes the next app open show the app's splash screen.")
        footer("Page 1 keeps its top row free for the battery, Wi-Fi and signal indicator. Long-press empty space to edit the Home Screen; swipe left past the last page for the App Library.")

        val crash = LauncherApp.instance.crashFile()
        if (crash.exists()) {
            section("LAST CRASH")
            group(listOf(
                row("Copy Crash Report", accent = true) {
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("iOS Launcher crash", crash.readText()))
                    android.widget.Toast.makeText(this, "Crash report copied — paste it in the chat", android.widget.Toast.LENGTH_LONG).show()
                },
                row("Share Crash Report") {
                    runCatching {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, crash.readText()), "Share crash report"))
                    }
                },
                row("Clear Crash Report") { crash.delete(); build() },
            ))
            footer(crash.readLines().firstOrNull().orEmpty())
        }

        section("ABOUT")
        group(listOf(
            row("© 2026 lutfananas. All rights reserved.") {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/lutfananas"))) }
            },
            row("Battery / Wi-Fi / signal indicator adapted from Duo Launcher (MIT License)") {},
        ))
        list.addView(View(this), LinearLayout.LayoutParams(1, (40 * dp).toInt()))
    }

    private fun restartLauncher() {
        // The launcher re-reads insets when rebuilt.
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(i)
        finish()
    }

    private fun selectPack(pkg: String) {
        prefs.iconPack = pkg
        LauncherApp.instance.icons.invalidate()
        build()
    }

    private fun appIcon(pkg: String?): Drawable? {
        val d = runCatching { if (pkg == null) packageManager.getApplicationIcon("com.android.settings") else packageManager.getApplicationIcon(pkg) }.getOrNull()
            ?: return null
        val size = (29 * dp).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.clipPath(IosShape.iconPath(size.toFloat()))
        if (d is android.graphics.drawable.AdaptiveIconDrawable) {
            val inset = size / 4
            d.background?.let { it.setBounds(-inset, -inset, size + inset, size + inset); it.draw(c) }
            d.foreground?.let { it.setBounds(-inset, -inset, size + inset, size + inset); it.draw(c) }
        } else { d.setBounds(0, 0, size, size); d.draw(c) }
        return BitmapDrawable(resources, bmp)
    }

    private fun section(title: String?) {
        list.addView(TextView(this).apply {
            text = title ?: ""
            setTextColor(getColor(R.color.settings_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding((36 * dp).toInt(), (22 * dp).toInt(), 0, (6 * dp).toInt())
        })
    }

    private fun footer(text: String) {
        list.addView(TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.settings_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding((36 * dp).toInt(), (6 * dp).toInt(), (36 * dp).toInt(), 0)
        })
    }

    private fun group(rows: List<View>) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SquircleDrawable(getColor(R.color.settings_cell), 12 * dp)
            clipToOutline = true
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

    private fun row(title: String, checked: Boolean = false, icon: Drawable? = null, accent: Boolean = false, onClick: () -> Unit): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (48 * dp).toInt()
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(40, 128, 128, 128)), null, null)
            setOnClickListener { onClick() }
        }
        if (icon != null) r.addView(ImageView(this).apply { setImageDrawable(icon) },
            LinearLayout.LayoutParams((29 * dp).toInt(), (29 * dp).toInt()).apply { rightMargin = (14 * dp).toInt() })
        r.addView(TextView(this).apply {
            text = title
            setTextColor(if (accent) Color.rgb(0, 122, 255) else getColor(R.color.settings_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (checked) r.addView(CheckMark(this), LinearLayout.LayoutParams((18 * dp).toInt(), (18 * dp).toInt()))
        return r
    }

    @Suppress("DEPRECATION")
    private fun switchRow(title: String, value: Boolean, onChange: (Boolean) -> Unit): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (48 * dp).toInt()
            setPadding((16 * dp).toInt(), 0, (12 * dp).toInt(), 0)
        }
        r.addView(TextView(this).apply {
            text = title; setTextColor(getColor(R.color.settings_text)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(this).apply { isChecked = value; setOnCheckedChangeListener { _, c -> onChange(c) } }
        r.addView(sw)
        r.setOnClickListener { sw.toggle() }
        return r
    }

    /** "Label    −  4  +" stepper for grid size. */
    private fun stepperRow(title: String, value: Int, onChange: (Int) -> Unit): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (48 * dp).toInt()
            setPadding((16 * dp).toInt(), 0, (12 * dp).toInt(), 0)
        }
        r.addView(TextView(this).apply {
            text = title; setTextColor(getColor(R.color.settings_text)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        var current = value
        val num = TextView(this).apply {
            text = "$current"; gravity = Gravity.CENTER; setTextColor(getColor(R.color.settings_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        }
        fun btn(label: String, delta: Int) = TextView(this).apply {
            text = label; gravity = Gravity.CENTER
            setTextColor(Color.rgb(0, 122, 255)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            background = SquircleDrawable(Color.argb(30, 120, 120, 128), 8 * dp)
            setOnClickListener {
                val n = (current + delta).coerceIn(com.ioslauncher.model.Prefs.GRID_MIN, com.ioslauncher.model.Prefs.GRID_MAX)
                if (n != current) { current = n; num.text = "$n"; onChange(n) }
            }
        }
        val size = (36 * dp).toInt()
        r.addView(btn("−", -1), LinearLayout.LayoutParams(size, size))
        r.addView(num, LinearLayout.LayoutParams((40 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        r.addView(btn("+", 1), LinearLayout.LayoutParams(size, size))
        return r
    }

    /** 0%–200% slider (100% = default look) for the Liquid Glass tuning. */
    private fun amountRow(title: String, value: Float, onChange: (Float) -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt())
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(TextView(this).apply {
            text = title; setTextColor(getColor(R.color.settings_text)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val pct = TextView(this).apply { setTextColor(getColor(R.color.settings_secondary)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f) }
        header.addView(pct)
        box.addView(header)
        val bar = android.widget.SeekBar(this).apply { max = 20; progress = (value * 10).roundToInt().coerceIn(0, 20) }
        fun label(p: Int) { pct.text = if (p == 0) "Off" else "${p * 10}%" }
        label(bar.progress)
        bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) { label(p); if (fromUser) onChange(p / 10f) }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
        })
        box.addView(bar)
        return box
    }

    /** Slider 50%–130% in 5% steps, with a live preview icon and percentage. */
    private fun sliderRow(title: String, value: Float, onChange: (Float) -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (6 * dp).toInt())
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = title; setTextColor(getColor(R.color.settings_text)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val pct = TextView(this).apply { setTextColor(getColor(R.color.settings_secondary)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f) }
        header.addView(pct)
        box.addView(header)
        val preview = SizePreview(this)
        val bar = android.widget.SeekBar(this).apply {
            max = (MAX_SCALE - MIN_SCALE) / 5
            progress = ((value * 100).toInt() - MIN_SCALE) / 5
        }
        fun show(p: Int) { val s = MIN_SCALE + p * 5; pct.text = "$s%"; preview.scale = s / 100f }
        show(bar.progress)
        bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) { show(p); if (fromUser) onChange((MIN_SCALE + p * 5) / 100f) }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
        })
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line.addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(preview, LinearLayout.LayoutParams((62 * dp).toInt(), (62 * dp).toInt()))
        box.addView(line)
        return box
    }

    /** Squircle preview at the chosen scale (60pt reference = 100%). */
    private class SizePreview(context: android.content.Context) : View(context) {
        var scale = 1f; set(v) { field = v; invalidate() }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 122, 255) }
        override fun onDraw(canvas: Canvas) {
            val s = width / 1.3f * scale
            canvas.translate((width - s) / 2f, (height - s) / 2f)
            canvas.drawPath(IosShape.iconPath(s), p)
        }
    }

    private class CheckMark(context: android.content.Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 122, 255); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        override fun onDraw(canvas: Canvas) {
            p.strokeWidth = width * 0.13f
            val path = android.graphics.Path().apply {
                moveTo(width * 0.12f, height * 0.55f); lineTo(width * 0.4f, height * 0.82f); lineTo(width * 0.9f, height * 0.18f)
            }
            canvas.drawPath(path, p)
        }
    }

}
