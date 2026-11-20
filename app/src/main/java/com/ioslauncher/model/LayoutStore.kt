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

/** A home-screen widget (Android AppWidget) occupying [spanX] × [spanY] cells. */
data class WidgetSpec(val appWidgetId: Int, var spanX: Int, var spanY: Int)

/**
 * Home screen layout. Each page is an ordered list of items (app keys or folder ids). Items flow
 * left-to-right, top-to-bottom; multi-cell folders take the first free spot that fits them
 * ([GridPacker]). Page 1 keeps its first row free for the clock / status cluster.
 */
class LayoutStore(private val context: Context, private val prefs: Prefs) {
    val pages: MutableList<MutableList<String>> = mutableListOf()
    val dock: MutableList<String> = mutableListOf()
    val folders: LinkedHashMap<String, Folder> = LinkedHashMap()
    val widgets: LinkedHashMap<String, WidgetSpec> = LinkedHashMap()
    /** Free-placement cell (col, row) of each item on its page. */
    val positions: HashMap<String, android.graphics.Point> = HashMap()
    val free get() = prefs.freePlacement
    private val known: MutableSet<String> = mutableSetOf()
    private var initialized = false

    val cols get() = prefs.gridCols
    val rows get() = prefs.gridRows
    /** Usable rows: page 1 loses its status row, and gains one when the dock is hidden there. */
    fun rowsFor(page: Int) = if (page == 0) rows - 1 + (if (prefs.dockEnabled && prefs.hideDockOnFirstPage) 1 else 0) else rows

    init { load() }

    fun isFolder(key: String) = key.startsWith(FOLDER_PREFIX)
    fun isWidget(key: String) = key.startsWith(WIDGET_PREFIX)
    /** Folders and widgets: multi-cell items that never go into the dock or into folders. */
    fun isBlock(key: String) = isFolder(key) || isWidget(key)

    fun spanOf(key: String, page: Int): Pair<Int, Int> {
        folders[key]?.let { return it.spanX.coerceIn(1, cols) to it.spanY.coerceIn(1, rowsFor(page)) }
        widgets[key]?.let { return it.spanX.coerceIn(1, cols) to it.spanY.coerceIn(1, rowsFor(page)) }
        return 1 to 1
    }

    fun setSpan(key: String, sx: Int, sy: Int) {
        folders[key]?.let { it.spanX = sx; it.spanY = sy }
        widgets[key]?.let { it.spanX = sx; it.spanY = sy }
    }

    /** Add a block (widget) to [page]: first free spot (free placement) or top of the page (flow). */
    fun addToPage(key: String, page: Int) {
        while (pages.size <= page) pages += mutableListOf<String>()
        if (free) pages[page] += key else pages[page].add(0, key)
        normalize(); save()
    }

    /** Cell rectangles (col, row, col+spanX, row+spanY) for [page]'s items; null = doesn't fit. */
    fun placements(page: Int, keys: List<String> = pages[page]): List<Rect?> =
        if (free) GridPacker.packFree(keys, cols, rowsFor(page), { spanOf(it, page) }, { positions[it] })
        else GridPacker.pack(keys, cols, rowsFor(page)) { spanOf(it, page) }

    /** True if [rect] (cells) on [page] is inside the grid and not covered by anything except [ignore]. */
    fun isFree(page: Int, rect: Rect, ignore: String?): Boolean {
        if (rect.left < 0 || rect.top < 0 || rect.right > cols || rect.bottom > rowsFor(page)) return false
        val list = pages.getOrNull(page) ?: return true
        val pl = placements(page, list)
        list.forEachIndexed { i, k -> if (k != ignore) pl[i]?.let { if (Rect.intersects(it, rect)) return false } }
        return true
    }

    /** Key of the item covering cell (col,row) on [page], if any. */
    fun itemAtCell(page: Int, col: Int, row: Int): String? {
        val list = pages.getOrNull(page) ?: return null
        val pl = placements(page, list)
        list.forEachIndexed { i, k -> pl[i]?.let { if (it.contains(col, row)) return k } }
        return null
    }

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
            o.optJSONObject("pos")?.let { po ->
                po.keys().forEach { k -> val a = po.getJSONArray(k); positions[k] = android.graphics.Point(a.getInt(0), a.getInt(1)) }
            }
            o.optJSONArray("widgets")?.let { wa ->
                for (i in 0 until wa.length()) {
                    val w = wa.getJSONObject(i)
                    val spec = WidgetSpec(w.getInt("id"), w.optInt("sx", 2), w.optInt("sy", 2))
                    widgets[WIDGET_PREFIX + spec.appWidgetId] = spec
                }
            }
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
        o.put("widgets", JSONArray().apply {
            widgets.values.forEach { put(JSONObject().put("id", it.appWidgetId).put("sx", it.spanX).put("sy", it.spanY)) }
        })
        o.put("pos", JSONObject().apply { positions.forEach { (k, p) -> put(k, JSONArray().put(p.x).put(p.y)) } })
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
        val valid = { k: String -> k in keys || (isFolder(k) && folders.containsKey(k)) || (isWidget(k) && widgets.containsKey(k)) }
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
        if (positions.keys.retainAll { k -> pages.any { k in it } }) changed = true
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
        positions.remove(key)
        folders.remove(key)
        widgets.remove(key)
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

    /** Free placement: put [key] at (col,row) on [page]. */
    fun place(key: String, page: Int, col: Int, row: Int) {
        detach(key)
        while (pages.size <= page) pages += mutableListOf<String>()
        pages[page] += key
        positions[key] = android.graphics.Point(col, row)
    }

    /** Turn [targetKey] (an app on a page) into a folder holding it and [draggedKey]. */
    fun createFolder(targetKey: String, draggedKey: String, name: String): Folder? {
        val dockIdx = dock.indexOf(targetKey)
        if (dockIdx >= 0) {
            // Folder made right inside the dock.
            detach(draggedKey)
            val folder = Folder(FOLDER_PREFIX + UUID.randomUUID().toString(), name, mutableListOf(targetKey, draggedKey))
            folders[folder.id] = folder
            dock[dock.indexOf(targetKey)] = folder.id
            positions.remove(draggedKey)
            return folder
        }
        val page = pages.indexOfFirst { targetKey in it }
        if (page < 0) return null
        detach(draggedKey)
        val list = pages[page]
        val idx = list.indexOf(targetKey)
        val folder = Folder(FOLDER_PREFIX + UUID.randomUUID().toString(), name, mutableListOf(targetKey, draggedKey))
        folders[folder.id] = folder
        list[idx] = folder.id
        positions.remove(draggedKey)
        positions.remove(targetKey)?.let { positions[folder.id] = it }
        return folder
    }

    fun addToFolder(folderId: String, appKey: String) {
        val f = folders[folderId] ?: return
        detach(appKey)
        positions.remove(appKey)
        if (appKey !in f.apps) f.apps += appKey
    }

    /** Take an app out of a folder and place it right after the folder. Empty folders disappear. */
    fun removeFromFolder(folderId: String, appKey: String) {
        val f = folders[folderId] ?: return
        f.apps.remove(appKey)
        val inDock = dock.indexOf(folderId)
        if (inDock >= 0) {
            // Out of a dock folder: next to it in the dock if there's room, else onto the Home Screen.
            if (dock.size < DOCK_MAX) dock.add(inDock + 1, appKey) else appendToHome(appKey)
            if (f.apps.isEmpty()) { dock.remove(folderId); folders.remove(folderId) }
            normalize(); save(); return
        }
        val page = pageOf(folderId).coerceAtLeast(0)
        if (pages.isEmpty()) pages += mutableListOf<String>()
        val list = pages[page]
        val idx = list.indexOf(folderId)
        list.add(if (idx >= 0) idx + 1 else list.size, appKey)
        positions.remove(appKey)   // free placement: lands in the first empty cell
        if (f.apps.isEmpty()) { list.remove(folderId); folders.remove(folderId); positions.remove(folderId) }
        normalize(); save()
    }

    /** Move items that don't fit onto the next page; optionally drop empty pages (page 1 always stays). */
    fun normalize(removeEmpty: Boolean = true): Boolean {
        var changed = false
        if (pages.isEmpty()) { pages += mutableListOf<String>(); changed = true }
        if (!prefs.dockEnabled && dock.isNotEmpty()) {
            dock.forEach { pages.last().add(it) }; dock.clear(); changed = true
        }
        // Folders may sit in the dock (one slot, 2×2 preview); widgets never do.
        dock.filter { isWidget(it) }.forEach { dock.remove(it); pages.last().add(it); changed = true }
        if (!free && positions.isNotEmpty()) { positions.clear(); changed = true }
        var i = 0
        while (i < pages.size) {
            val page = pages[i]
            val placed = placements(i)
            if (free) {
                // Pin every placed item to its cell so nothing re-flows later.
                page.forEachIndexed { idx, k ->
                    val p = placed.getOrNull(idx) ?: return@forEachIndexed
                    val cur = positions[k]
                    if (cur == null || cur.x != p.left || cur.y != p.top) { positions[k] = android.graphics.Point(p.left, p.top); changed = true }
                }
            }
            val firstMissing = placed.indexOfFirst { it == null }
            if (firstMissing >= 0) {
                // Flow: everything from the first misfit on moves; free placement: only the misfits move.
                val overflow = if (free) page.filterIndexed { idx, _ -> placed[idx] == null }
                    else page.subList(firstMissing, page.size).toList()
                overflow.forEach { positions.remove(it) }
                page.removeAll(overflow.toSet())
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
        const val WIDGET_PREFIX = "widget:"
    }
}

/** Flow layout with multi-cell items: each item takes the first free spot (reading order) that fits. */
object GridPacker {
    /**
     * Free placement: items with a valid stored cell keep it (first come wins on overlap); the rest take
     * the first free spot. Items that fit nowhere get null.
     */
    fun packFree(keys: List<String>, cols: Int, rows: Int, span: (String) -> Pair<Int, Int>, pos: (String) -> android.graphics.Point?): List<Rect?> {
        val used = BooleanArray(cols * rows)
        val out = arrayOfNulls<Rect>(keys.size)
        fun fits(c: Int, r: Int, sx: Int, sy: Int): Boolean {
            if (c < 0 || r < 0 || c + sx > cols || r + sy > rows) return false
            for (yy in r until r + sy) for (xx in c until c + sx) if (used[yy * cols + xx]) return false
            return true
        }
        fun mark(c: Int, r: Int, sx: Int, sy: Int) { for (yy in r until r + sy) for (xx in c until c + sx) used[yy * cols + xx] = true }
        val pending = ArrayList<Int>()
        keys.forEachIndexed { i, k ->
            val (sx0, sy0) = span(k); val sx = sx0.coerceIn(1, cols); val sy = sy0.coerceIn(1, rows)
            val p = pos(k)
            if (p != null && fits(p.x, p.y, sx, sy)) { mark(p.x, p.y, sx, sy); out[i] = Rect(p.x, p.y, p.x + sx, p.y + sy) }
            else pending += i
        }
        for (i in pending) {
            val (sx0, sy0) = span(keys[i]); val sx = sx0.coerceIn(1, cols); val sy = sy0.coerceIn(1, rows)
            search@ for (r in 0..rows - sy) for (c in 0..cols - sx) if (fits(c, r, sx, sy)) {
                mark(c, r, sx, sy); out[i] = Rect(c, r, c + sx, r + sy); break@search
            }
        }
        // Keep list order meaning "overflow from here on": unplaced items go to the end.
        return out.toList()
    }

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
