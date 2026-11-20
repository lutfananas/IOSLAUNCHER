package com.ioslauncher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import com.ioslauncher.LauncherApp
import com.ioslauncher.model.AppInfo
import kotlin.math.roundToInt

/** iOS home-screen metrics, scaled from the 390pt-wide iPhone layout to the actual screen width. */
class Dimens(context: Context, val width: Int, val height: Int, val topInset: Int, val bottomInset: Int) {
    val density = context.resources.displayMetrics.density
    private val pt = width / 390f                         // 1 iOS point on this screen

    private val prefs = LauncherApp.instance.prefs
    val cols = prefs.gridCols
    val rows = prefs.gridRows
    val showLabels = prefs.showLabels
    val dockEnabled = prefs.dockEnabled

    val sidePadding = (if (cols > 5) 14 * pt else 27 * pt).roundToInt()
    val cellWidth = (width - 2 * sidePadding) / cols

    // Labels shrink on dense grids so they stay readable without clipping.
    val labelTextSize = 12f * pt * (cellWidth / (84f * pt)).coerceIn(0.72f, 1f)
    val labelGap = 5 * pt * (cellWidth / (84f * pt)).coerceIn(0.6f, 1f)

    /** Dock icon size — set separately from the Home Screen. */
    val dockIconSize = (60 * pt * prefs.dockIconScale).roundToInt().coerceAtMost((width - 2 * 24 * pt).toInt() / 4 - (16 * pt).toInt())
    val dockSlot = dockIconSize + (24 * pt).roundToInt()   // icon + spacing inside the dock
    val dockPad = (12 * pt).roundToInt()                    // glass edge to first icon slot
    val dockHeight = if (dockEnabled) maxOf((92 * pt).roundToInt(), dockIconSize + (30 * pt).roundToInt()) else 0
    val dockRadius = 34 * pt
    val dockBottom = bottomInset.coerceAtLeast((8 * pt).roundToInt()) + (4 * pt).roundToInt()
    val dockTop = height - dockBottom - dockHeight

    /** Without a dock the bottom row is taller: page dots + the round glass search button. */
    val indicatorHeight = (if (dockEnabled) 30 * pt else 58 * pt).roundToInt()
    val searchButtonSize = (46 * pt).roundToInt()
    val gridTop = topInset.coerceAtLeast((24 * pt).roundToInt()) + (14 * pt).roundToInt()
    val gridBottom = (if (dockEnabled) dockTop else height - dockBottom) - indicatorHeight
    val cellHeight = (gridBottom - gridTop) / rows

    /** "Hide Dock on Page 1 Only": page 1 extends into the dock space with one extra row. */
    val page0ExtraRows = if (dockEnabled && prefs.hideDockOnFirstPage) 1 else 0
    /** Grid rows drawn on [page] (including page 1's status row). */
    fun gridRows(page: Int) = if (page == 0) rows + page0ExtraRows else rows
    private val page0CellHeight = if (page0ExtraRows > 0) (height - dockBottom - indicatorHeight - gridTop) / (rows + page0ExtraRows) else cellHeight
    fun cellH(page: Int) = if (page == 0) page0CellHeight else cellHeight

    private val labelBlock = if (showLabels) labelGap + labelTextSize * 1.25f else 0f
    /** Home Screen / App Library icon size (user scale, capped so icons never overlap on dense grids). */
    val iconSize = (60 * pt * prefs.homeIconScale).roundToInt()
        .coerceAtMost((cellWidth * 0.86f).toInt())
        .coerceAtMost(((minOf(cellHeight, page0CellHeight) - labelBlock) * 0.92f).toInt())
        .coerceAtLeast((20 * pt).toInt())

    val libraryCardGap = (20 * pt).roundToInt()
    val librarySide = (20 * pt).roundToInt()
    val libraryCard = (width - 2 * librarySide - libraryCardGap) / 2

    val searchBarHeight = (40 * pt).roundToInt()

    /** Top of the icon inside a grid cell (icon + label block vertically centred). */
    val iconTopInCell = ((cellHeight - iconSize - labelBlock) / 2f).coerceAtLeast(0f)
    fun iconTopIn(page: Int) = ((cellH(page) - iconSize - labelBlock) / 2f).coerceAtLeast(0f)

    fun dp(v: Float) = v * density
    fun pt(v: Float) = v * pt
}

/** Anything on the Home Screen / dock that can be picked up and dragged. */
interface DragItem {
    val itemKey: String?
    fun dragBoundsOnScreen(): Rect
    fun dragSnapshot(): Bitmap?
}

interface LauncherHost {
    val isEditMode: Boolean
    val dimens: Dimens
    fun launchApp(view: View, app: AppInfo, iconBounds: Rect?)
    fun onIconLongPress(view: AppIconView)
    fun onIconDragStart(view: View, rawX: Float, rawY: Float)
    fun onRemoveRequested(view: View)
    fun isHiddenForDrag(key: String): Boolean
    fun openFolder(folderId: String, from: Rect) {}
    fun onFolderLongPress(view: FolderView) {}
    fun onFolderResize(folderId: String, spanX: Int, spanY: Int, done: Boolean) {}
    fun isFolderDropTarget(key: String): Boolean = false
}
