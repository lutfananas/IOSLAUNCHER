package com.ioslauncher.ui

import android.content.Context
import com.ioslauncher.model.LayoutStore
import kotlin.math.roundToInt

/** iOS home-screen metrics, scaled from the 390pt-wide iPhone layout to the actual screen width. */
class Dimens(context: Context, val width: Int, val height: Int, val topInset: Int, val bottomInset: Int) {
    val density = context.resources.displayMetrics.density
    private val pt = width / 390f                         // 1 iOS point on this screen

    private val prefs = com.ioslauncher.LauncherApp.instance.prefs
    val showLabels = prefs.showLabels
    val labelTextSize = 12f * pt
    val labelGap = 5 * pt
    val sidePadding = (27 * pt).roundToInt()
    val cellWidth = (width - 2 * sidePadding) / LayoutStore.COLS

    /** Home Screen / App Library icon size (user scale, capped so icons never overlap). */
    val iconSize = (60 * pt * prefs.homeIconScale).roundToInt().coerceAtMost((cellWidth * 0.94f).toInt())
    /** Dock icon size — set separately from the Home Screen. */
    val dockIconSize = (60 * pt * prefs.dockIconScale).roundToInt().coerceAtMost((cellWidth * 0.94f).toInt())

    val dockMargin = (12 * pt).roundToInt()
    val dockHeight = maxOf((96 * pt).roundToInt(), dockIconSize + (30 * pt).roundToInt())
    val dockRadius = 34 * pt
    val dockBottom = bottomInset.coerceAtLeast((8 * pt).roundToInt()) + (4 * pt).roundToInt()
    val dockTop = height - dockBottom - dockHeight

    val indicatorHeight = (30 * pt).roundToInt()
    val gridTop = topInset.coerceAtLeast((24 * pt).roundToInt()) + (14 * pt).roundToInt()
    val gridBottom = dockTop - indicatorHeight
    val cellHeight = (gridBottom - gridTop) / LayoutStore.ROWS

    val libraryCardGap = (20 * pt).roundToInt()
    val librarySide = (20 * pt).roundToInt()
    val libraryCard = (width - 2 * librarySide - libraryCardGap) / 2

    val searchBarHeight = (40 * pt).roundToInt()

    fun dp(v: Float) = v * density
    fun pt(v: Float) = v * pt
}

interface LauncherHost {
    val isEditMode: Boolean
    val dimens: Dimens
    fun launchApp(view: android.view.View, app: com.ioslauncher.model.AppInfo, iconBounds: android.graphics.Rect?)
    fun onIconLongPress(view: AppIconView)
    fun onIconDragStart(view: AppIconView, rawX: Float, rawY: Float)
    fun onRemoveRequested(view: AppIconView)
    fun isHiddenForDrag(key: String): Boolean
}
