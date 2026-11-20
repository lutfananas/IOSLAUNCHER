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

/** iOS-style grouped settings: icon pack picker, icon & home options, wallpaper, default launcher. */
class SettingsActivity : Activity() {
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

        section("ICONS")
        group(listOf(
            switchRow("Fit Icons to iOS Shape", prefs.normalizePackIcons) { prefs.normalizePackIcons = it; LauncherApp.instance.icons.invalidate() },
            switchRow("Show App Names", prefs.showLabels) { prefs.showLabels = it },
        ))

        section("HOME SCREEN")
        group(listOf(
            switchRow("Hide Status Bar", prefs.hideStatusBar) { prefs.hideStatusBar = it; restartLauncher() },
            switchRow("Unlock Animation", prefs.unlockAnimation) { prefs.unlockAnimation = it },
            switchRow("Add New Apps to Home Screen", prefs.autoAddNewApps) { prefs.autoAddNewApps = it },
            row("Change Wallpaper") {
                runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper")) }
            },
        ))
        footer("Page 1 keeps its top row free for the battery, Wi-Fi and signal indicator. Long-press empty space to edit the Home Screen; swipe left past the last page for the App Library.")

        section("ABOUT")
        group(listOf(row("Battery / Wi-Fi / signal indicator adapted from Duo Launcher (MIT License)") {}))
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
