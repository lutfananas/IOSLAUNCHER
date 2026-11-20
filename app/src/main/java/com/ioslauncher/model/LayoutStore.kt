package com.ioslauncher.model

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject

/**
 * Home screen layout, stored iOS-style: each page is an ordered list that fills cells left-to-right,
 * top-to-bottom with no gaps. Page 1 keeps its first row free for the status cluster.
 */
class LayoutStore(private val context: Context, private val prefs: Prefs) {
    val pages: MutableList<MutableList<String>> = mutableListOf()
    val dock: MutableList<String> = mutableListOf()
    private val known: MutableSet<String> = mutableSetOf()
    private var initialized = false

    init { load() }

    fun capacity(page: Int) = if (page == 0) COLS * (ROWS - 1) else COLS * ROWS

    fun isOnHome(key: String) = key in dock || pages.any { key in it }

    private fun load() {
        val json = prefs.layoutJson ?: return
        runCatching {
            val o = JSONObject(json)
            val p = o.getJSONArray("pages")
            for (i in 0 until p.length()) {
                val arr = p.getJSONArray(i)
                pages += MutableList(arr.length()) { arr.getString(it) }
            }
            val d = o.getJSONArray("dock")
            for (i in 0 until d.length()) dock += d.getString(i)
            val k = o.getJSONArray("known")
            for (i in 0 until k.length()) known += k.getString(i)
            initialized = true
        }
    }

    fun save() {
        val o = JSONObject()
        o.put("pages", JSONArray().apply { pages.forEach { put(JSONArray(it)) } })
        o.put("dock", JSONArray(dock))
        o.put("known", JSONArray(known.toList()))
        prefs.layoutJson = o.toString()
    }

    /** Reconcile layout with the installed apps. Returns true when anything changed. */
    fun sync(apps: List<AppInfo>): Boolean {
        if (apps.isEmpty()) return false   // never wipe the layout on a failed/empty load
        val keys = apps.map { it.key }.toSet()
        var changed = false
        if (!initialized) {
            buildDefault(apps)
            initialized = true
            save()
            return true
        }
        pages.forEach { if (it.retainAll(keys)) changed = true }
        if (dock.retainAll(keys)) changed = true
        if (known.retainAll(keys)) changed = true
        for (app in apps) {
            if (app.key !in known) {
                known += app.key
                changed = true
                if (prefs.autoAddNewApps && !isOnHome(app.key)) appendToHome(app.key)
            }
        }
        if (normalize()) changed = true
        if (changed) save()
        return changed
    }

    fun appendToHome(key: String) {
        if (isOnHome(key)) return
        val idx = pages.indexOfLast { true }
        if (idx < 0 || pages[idx].size >= capacity(idx)) pages += mutableListOf(key) else pages[idx] += key
    }

    fun removeFromHome(key: String) {
        pages.forEach { it.remove(key) }
        dock.remove(key)
        normalize()
        save()
    }

    /** Remove [key] from wherever it is on the home screen (without saving). */
    fun detach(key: String) {
        pages.forEach { it.remove(key) }
        dock.remove(key)
    }

    /** Cascade overflowing pages forward, drop trailing empty pages (always keep page 1). */
    fun normalize(removeEmpty: Boolean = true): Boolean {
        var changed = false
        if (pages.isEmpty()) { pages += mutableListOf<String>(); changed = true }
        var i = 0
        while (i < pages.size) {
            val page = pages[i]
            while (page.size > capacity(i)) {
                val overflow = page.removeAt(page.lastIndex)
                if (i + 1 >= pages.size) pages += mutableListOf<String>()
                pages[i + 1].add(0, overflow)
                changed = true
            }
            i++
        }
        if (!removeEmpty) return changed
        // Remove empty pages except the first.
        val it = pages.listIterator()
        var index = 0
        while (it.hasNext()) {
            val p = it.next()
            if (index > 0 && p.isEmpty()) { it.remove(); changed = true } else index++
        }
        while (dock.size > DOCK_MAX) { val k = dock.removeAt(dock.lastIndex); pages.last().add(k); changed = true }
        return changed
    }

    private fun buildDefault(apps: List<AppInfo>) {
        pages.clear(); dock.clear(); known.clear()
        apps.forEach { known += it.key }
        val pm = context.packageManager
        fun pkgFor(intent: Intent): String? =
            pm.resolveActivity(intent, 0)?.activityInfo?.packageName?.takeIf { it != "android" }
        val dockPkgs = listOfNotNull(
            pkgFor(Intent(Intent.ACTION_DIAL)),
            Telephony.Sms.getDefaultSmsPackage(context),
            pkgFor(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))),
            pkgFor(Intent(MediaStore.ACTION_IMAGE_CAPTURE)),
        ).distinct()
        val myUser = android.os.Process.myUserHandle()
        for (pkg in dockPkgs) {
            apps.firstOrNull { it.packageName == pkg && it.user == myUser }?.let { if (it.key !in dock) dock += it.key }
        }
        val rest = apps.filter { it.key !in dock }
        // Page 1: own-profile apps first, like a fresh iPhone.
        val ordered = rest.sortedBy { if (it.isWorkProfile) 1 else 0 }
        var page = mutableListOf<String>()
        pages += page
        for (app in ordered) {
            if (page.size >= capacity(pages.size - 1)) { page = mutableListOf(); pages += page }
            page += app.key
        }
    }

    companion object {
        const val COLS = 4
        const val ROWS = 6
        const val DOCK_MAX = 4
    }
}
