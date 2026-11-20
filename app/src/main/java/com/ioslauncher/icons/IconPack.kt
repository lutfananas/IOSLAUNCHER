/*
 * iOS Launcher — Copyright (c) 2026 lutfananas. All rights reserved. See LICENSE.
 */
package com.ioslauncher.icons

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

data class IconPackInfo(val packageName: String, val label: String)

/**
 * Reads an installed icon pack in the de-facto standard ADW/Nova "appfilter.xml" format:
 *   <item component="ComponentInfo{pkg/cls}" drawable="name"/>
 *   <iconback img1=".."/> <iconmask img1=".."/> <iconupon img1=".."/> <scale factor=".."/>
 */
class IconPack(private val context: Context, val packageName: String) {
    private val res: Resources = context.packageManager.getResourcesForApplication(packageName)
    private val componentMap = HashMap<String, String>()
    private val packageMap = HashMap<String, String>()
    private val backs = ArrayList<String>()
    var maskName: String? = null; private set
    var uponName: String? = null; private set
    var scale: Float = 1f; private set

    init { parse() }

    val hasFallbackMask: Boolean get() = backs.isNotEmpty() || maskName != null || uponName != null

    private fun parse() {
        val parser: XmlPullParser = run {
            val id = res.getIdentifier("appfilter", "xml", packageName)
            if (id != 0) res.getXml(id) else {
                val stream = res.assets.open("appfilter.xml")
                XmlPullParserFactory.newInstance().newPullParser().apply { setInput(stream, "utf-8") }
            }
        }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        val comp = parser.getAttributeValue(null, "component")
                        val drawable = parser.getAttributeValue(null, "drawable")
                        if (comp != null && drawable != null) {
                            val cn = parseComponent(comp)
                            if (cn != null) {
                                componentMap.putIfAbsent(cn.flattenToString(), drawable)
                                packageMap.putIfAbsent(cn.packageName, drawable)
                            }
                        }
                    }
                    "iconback" -> for (i in 0 until parser.attributeCount) {
                        if (parser.getAttributeName(i).startsWith("img")) backs += parser.getAttributeValue(i)
                    }
                    "iconmask" -> maskName = parser.getAttributeValue(null, "img1")
                    "iconupon" -> uponName = parser.getAttributeValue(null, "img1")
                    "scale" -> scale = parser.getAttributeValue(null, "factor")?.toFloatOrNull() ?: 1f
                }
            }
            event = parser.next()
        }
    }

    private fun parseComponent(raw: String): ComponentName? {
        val s = raw.removePrefix("ComponentInfo{").removeSuffix("}").trim()
        val slash = s.indexOf('/')
        if (slash <= 0 || slash == s.lastIndex) return null
        val pkg = s.substring(0, slash)
        var cls = s.substring(slash + 1)
        if (cls.startsWith(".")) cls = pkg + cls
        return ComponentName(pkg, cls)
    }

    /** Every icon in the pack (from drawable.xml when present, else everything appfilter references). */
    val allIconNames: List<String> by lazy {
        val names = LinkedHashSet<String>()
        runCatching {
            val id = res.getIdentifier("drawable", "xml", packageName)
            val parser: XmlPullParser = if (id != 0) res.getXml(id) else {
                val stream = res.assets.open("drawable.xml")
                XmlPullParserFactory.newInstance().newPullParser().apply { setInput(stream, "utf-8") }
            }
            var e = parser.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                if (e == XmlPullParser.START_TAG && parser.name == "item") parser.getAttributeValue(null, "drawable")?.let { names += it }
                e = parser.next()
            }
        }
        if (names.isEmpty()) names += componentMap.values
        names.filter { res.getIdentifier(it, "drawable", packageName) != 0 || res.getIdentifier(it, "mipmap", packageName) != 0 }
    }

    /** Drawable names the pack maps to [pkg] (suggestions for the icon chooser). */
    fun suggestionsFor(pkg: String): List<String> =
        componentMap.entries.filter { it.key.startsWith("$pkg/") }.map { it.value }.distinct()

    fun drawableFor(component: ComponentName, density: Int): Drawable? {
        val name = componentMap[component.flattenToString()] ?: packageMap[component.packageName] ?: return null
        return load(name, density)
    }

    fun backFor(component: ComponentName, density: Int): Drawable? {
        if (backs.isEmpty()) return null
        val idx = (component.flattenToString().hashCode() and Int.MAX_VALUE) % backs.size
        return load(backs[idx], density)
    }

    fun load(name: String, density: Int): Drawable? {
        var id = res.getIdentifier(name, "drawable", packageName)
        if (id == 0) id = res.getIdentifier(name, "mipmap", packageName)
        if (id == 0) return null
        return runCatching { res.getDrawableForDensity(id, density, null) }.getOrNull()
    }

    companion object {
        val ACTIONS = listOf(
            "org.adw.launcher.THEMES", "org.adw.ActivityStarter.THEMES", "com.novalauncher.THEME",
            "com.teslacoilsw.launcher.THEME", "com.anddoes.launcher.THEME", "com.gau.go.launcherex.theme",
            "com.dlto.atom.launcher.THEME", "ch.deletescape.lawnchair.ICONPACK", "app.lawnchair.icons.THEMED_ICON",
            "com.fede.launcher.THEME_ICONPACK", "com.lge.launcher2.THEME",
        )

        /** Every installed app that advertises itself as an icon pack. */
        fun findInstalled(context: Context): List<IconPackInfo> {
            val pm = context.packageManager
            val found = LinkedHashMap<String, IconPackInfo>()
            for (action in ACTIONS) {
                val list = runCatching {
                    pm.queryIntentActivities(Intent(action), PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
                }.getOrDefault(emptyList())
                for (ri in list) {
                    val pkg = ri.activityInfo.packageName
                    if (pkg !in found) found[pkg] = IconPackInfo(pkg, ri.loadLabel(pm).toString())
                }
            }
            return found.values.sortedBy { it.label.lowercase() }
        }
    }
}
