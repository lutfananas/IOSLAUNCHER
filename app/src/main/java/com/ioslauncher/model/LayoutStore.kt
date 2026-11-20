package com.ioslauncher.model

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A home-screen folder. It can span several grid cells ([spanX] × [spanY]); large folders show their apps inline. */
data class Folder(
    val id: String,
    var name: String,
    val apps: MutableList<String>,
    var spanX: Int = 1,
    var spanY: Int = 1,
)

/**
 * Home screen layout. Each page is an ordered list of items (app keys or folder ids). Items flow
 * left-to-right, top-to-bottom; multi-cell folders take the first free spot that fits them
 * ([GridPacker]). Page 1 keeps its first row free for the clock / status cluster.
 */
class LayoutStore(private val context: Context, private val prefs: Prefs) {
    val pages: MutableList<MutableList<String>> = mutableListOf()
    val dock: MutableList<String> = mutableListOf()
    val folders: LinkedHashMap<String, Folder> = LinkedHashMap()
    private val known: MutableSet<String> = mutableSetOf()
    private var initialized = false

    val cols get() = prefs.gridCols
    val rows get() = prefs.gridRows
    fun rowsFor(page: Int) = if (page == 0) rows - 1 else rows

    init { load() }

    fun isFolder(key: String) = key.startsWith(FOLDER_PREFIX)

    fun spanOf(key: String, page: Int): Pair<Int, Int> {
        val f = folders[key] ?: return 1 to 1
        return f.spanX.coerceIn(1, cols) to f.spanY.coerceIn(1, rowsFor(page))
    }

    /** Cell rectangles (col, row, col+spanX, row+spanY) for [page]'s items; null = doesn't fit. */
    fun placements(page: Int, keys: List<String> = pages[page]): List<Rect?> =
        GridPacker.pack(keys, cols, rowsFor(page)) { spanOf(it, page) }

    fun folderOf(appKey: String): Folder? = folders.values.firstOrNull { appKey in it.apps }

    fun isOnHome(appKey: String) = appKey in dock || pages.any { appKey in it } || folderOf(appKey) != null

    fun pageOf(key: String) = pages.indexOfFirst { key in it }

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
            o.optJSONArray("folders")?.let { fa ->
                for (i in 0 until fa.length()) {
                    val f = fa.getJSONObject(i)
                    val apps = f.getJSONArray("apps")
                    val folder = Folder(f.getString("id"), f.getString("name"),
                        MutableList(apps.length()) { apps.getString(it) }, f.optInt("sx", 1), f.optInt("sy", 1))
                    folders[folder.id] = folder
                }
            }
            initialized = true
        }
    }

    fun save() {
        val o = JSONObject()
        o.put("pages", JSONArray().apply { pages.forEach { put(JSONArray(it)) } })
        o.put("dock", JSONArray(dock))
        o.put("known", JSONArray(known.toList()))
        o.put("folders", JSONArray().apply {
            folders.values.forEach { f ->
                put(JSONObject().put("id", f.id).put("name", f.name).put("apps", JSONArray(f.apps)).put("sx", f.spanX).put("sy", f.spanY))
            }
        })
        prefs.layoutJson = o.toString()
    }

    /** Reconcile layout with the installed apps. Returns true when anything changed. */
    fun sync(apps: List<AppInfo>): Boolean {
        if (apps.isEmpty()) return false   // never wipe the layout on a failed/empty load
        val keys = apps.map { it.key }.toSet()
        if (!initialized) {
            buildDefault(apps)
            initialized = true
            normalize()
            save()
            return true
        }
        var changed = false
        val valid = { k: String -> k in keys || (isFolder(k) && folders.containsKey(k)) }
        folders.values.forEach { if (it.apps.retainAll(keys)) changed = true }
        val empty = folders.values.filter { it.apps.isEmpty() }.map { it.id }
        empty.forEach { folders.remove(it); changed = true }
        pages.forEach { if (it.retainAll(valid)) changed = true }
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
        if (!isFolder(key) && isOnHome(key)) return
        if (pages.isEmpty()) pages += mutableListOf<String>()
        val last = pages.lastIndex
        val trial = pages[last] + key
        if (placements(last, trial).last() != null) pages[last] += key else pages += mutableListOf(key)
    }

    /** Remove an app or a whole folder from the Home Screen (apps stay in the App Library). */
    fun removeFromHome(key: String) {
        detach(key)
        folders.remove(key)
        folders.values.forEach { it.apps.remove(key) }
        folders.values.filter { it.apps.isEmpty() }.map { it.id }.forEach { id -> detach(id); folders.remove(id) }
        normalize()
        save()
    }

    /** Remove [key] from wherever it is on pages/dock (without saving). */
    fun detach(key: String) {
        pages.forEach { it.remove(key) }
        dock.remove(key)
    }

    /** Turn [targetKey] (an app on a page) into a folder holding it and [draggedKey]. */
    fun createFolder(targetKey: String, draggedKey: String, name: String): Folder? {
        val page = pages.indexOfFirst { targetKey in it }
        if (page < 0) return null
        detach(draggedKey)
        val list = pages[page]
        val idx = list.indexOf(targetKey)
        val folder = Folder(FOLDER_PREFIX + UUID.randomUUID().toString(), name, mutableListOf(targetKey, draggedKey))
        folders[folder.id] = folder
        list[idx] = folder.id
        return folder
    }

    fun addToFolder(folderId: String, appKey: String) {
        val f = folders[folderId] ?: return
        detach(appKey)
        if (appKey !in f.apps) f.apps += appKey
    }

    /** Take an app out of a folder and place it right after the folder. Empty folders disappear. */
    fun removeFromFolder(folderId: String, appKey: String) {
        val f = folders[folderId] ?: return
        f.apps.remove(appKey)
        val page = pageOf(folderId).coerceAtLeast(0)
        if (pages.isEmpty()) pages += mutableListOf<String>()
        val list = pages[page]
        val idx = list.indexOf(folderId)
        list.add(if (idx >= 0) idx + 1 else list.size, appKey)
        if (f.apps.isEmpty()) { list.remove(folderId); folders.remove(folderId) }
        normalize(); save()
    }

    /** Move items that don't fit onto the next page; optionally drop empty pages (page 1 always stays). */
    fun normalize(removeEmpty: Boolean = true): Boolean {
        var changed = false
        if (pages.isEmpty()) { pages += mutableListOf<String>(); changed = true }
        if (!prefs.dockEnabled && dock.isNotEmpty()) {
            dock.forEach { pages.last().add(it) }; dock.clear(); changed = true
        }
        // Folders never live in the dock.
        dock.filter { isFolder(it) }.forEach { dock.remove(it); pages.last().add(it); changed = true }
        var i = 0
        while (i < pages.size) {
            val page = pages[i]
            val placed = placements(i)
            val firstMissing = placed.indexOfFirst { it == null }
            if (firstMissing >= 0) {
                val overflow = page.subList(firstMissing, page.size).toList()
                repeat(overflow.size) { page.removeAt(page.lastIndex) }
                if (i + 1 >= pages.size) pages += mutableListOf<String>()
                pages[i + 1].addAll(0, overflow)
                changed = true
            }
            i++
        }
        while (dock.size > DOCK_MAX) { val k = dock.removeAt(dock.lastIndex); pages.last().add(k); changed = true }
        if (!removeEmpty) return changed
        val it = pages.listIterator()
        var index = 0
        while (it.hasNext()) {
            val p = it.next()
            if (index > 0 && p.isEmpty()) { it.remove(); changed = true } else index++
        }
        return changed
    }

    private fun buildDefault(apps: List<AppInfo>) {
        pages.clear(); dock.clear(); known.clear(); folders.clear()
        apps.forEach { known += it.key }
        val pm = context.packageManager
        fun pkgFor(intent: Intent): String? =
            pm.resolveActivity(intent, 0)?.activityInfo?.packageName?.takeIf { it != "android" }
        if (prefs.dockEnabled) {
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
        }
        pages += apps.filter { it.key !in dock }.sortedBy { if (it.isWorkProfile) 1 else 0 }.map { it.key }.toMutableList()
    }

    companion object {
        const val DOCK_MAX = 4
        const val FOLDER_PREFIX = "folder:"
    }
}

/** Flow layout with multi-cell items: each item takes the first free spot (reading order) that fits. */
object GridPacker {
    fun pack(keys: List<String>, cols: Int, rows: Int, span: (String) -> Pair<Int, Int>): List<Rect?> {
        val used = BooleanArray(cols * rows)
        val out = ArrayList<Rect?>(keys.size)
        var full = false
        for (k in keys) {
            if (full) { out += null; continue }
            val (sx0, sy0) = span(k)
            val sx = sx0.coerceIn(1, cols); val sy = sy0.coerceIn(1, rows)
            var placed: Rect? = null
            search@ for (r in 0..rows - sy) for (c in 0..cols - sx) {
                var ok = true
                check@ for (yy in r until r + sy) for (xx in c until c + sx) if (used[yy * cols + xx]) { ok = false; break@check }
                if (ok) {
                    for (yy in r until r + sy) for (xx in c until c + sx) used[yy * cols + xx] = true
                    placed = Rect(c, r, c + sx, r + sy)
                    break@search
                }
            }
            if (placed == null) full = true
            out += placed
        }
        return out
    }
}
