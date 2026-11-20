package com.ioslauncher.ui

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.LauncherApps
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import com.ioslauncher.LauncherApp
import com.ioslauncher.model.AppInfo
import com.ioslauncher.model.Folder
import com.ioslauncher.model.LayoutStore
import kotlin.math.roundToInt

class MainActivity : ComponentActivity(), Overlay.OverlayHost, PagedView.Callbacks {
    private val launcher get() = LauncherApp.instance
    val layoutStore: LayoutStore get() = launcher.layout

    override lateinit var dimens: Dimens
    private val drag = DragController(this)
    lateinit var root: DragLayer
    lateinit var pager: PagedView
    lateinit var dock: DockView
    val homePages = mutableListOf<HomePageView>()
    private lateinit var library: AppLibraryView
    private lateinit var indicator: PageIndicator
    private var searchButton: SearchButton? = null
    /** Views that fade with the page dots (dots + search button). */
    private val bottomBar get() = listOfNotNull<View>(indicator, searchButton)
    /** Target alpha of a bottom-bar view: the search button only exists where the dock is hidden. */
    private fun barAlpha(v: View): Float =
        if (v === searchButton && dimens.dockEnabled) firstPageHide * (1f - libProgress) else 1f - libProgress
    private lateinit var doneButton: TextView
    private lateinit var settingsButton: TextView
    private lateinit var addWidgetButton: TextView
    private val editButtons get() = listOf(doneButton, settingsButton, addWidgetButton)
    private var editButtonsRow: View? = null
    private var editRowLift = 0f

    // ---- Widgets ----
    private val widgetManager by lazy { android.appwidget.AppWidgetManager.getInstance(this) }
    private val widgetHost by lazy { android.appwidget.AppWidgetHost(applicationContext, WIDGET_HOST_ID) }
    private var pendingWidget: Pair<Int, android.appwidget.AppWidgetProviderInfo>? = null
    private val bindWidget = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val p = pendingWidget ?: return@registerForActivityResult
        if (r.resultCode == RESULT_OK) configureOrPlaceWidget(p.first, p.second)
        else { widgetHost.deleteAppWidgetId(p.first); pendingWidget = null }
    }
    private val overlays = mutableListOf<Overlay>()
    private var built = false

    override var isEditMode = false; private set

    private var libProgress = 0f
    /** 1 on page 1 when "Hide Dock on Page 1" is on, fading to 0 as you swipe to page 2. */
    private var firstPageHide = 0f

    private fun dockHidden() = maxOf(libProgress, firstPageHide)
    private fun dockTargetAlpha() = 1f - dockHidden()
    private fun dockTargetTY() = dockHidden() * (dimens.dockHeight + dimens.dockBottom)
    /** Whether the dock can currently take a dropped icon. */
    val dockAvailable get() = built && dimens.dockEnabled && dockHidden() < 0.5f
    private var libraryBlur = 0f
    private var overlayBlur = 0f
    private var lastBlurRadius = -1

    private var pendingUnlock = false
    private var unlockPrepared = false
    private var returningFromApp = false
    private var settingsDirty = false
    private var resumed = false

    private lateinit var statusMonitor: DeviceStatusMonitor
    private var lastLightX = 0f; private var lastLightY = 0f
    private val parallax by lazy {
        TiltParallax(this) { tx, ty ->
            if (!built || drag.dragging) return@TiltParallax
            val max = dimens.pt(7f)
            // Icons drift against the wallpaper.
            pager.translationX = tx * max
            pager.translationY = ty * max
            Glass.setLight(tx, ty)
            if (kotlin.math.abs(tx - lastLightX) + kotlin.math.abs(ty - lastLightY) > 0.04f) {
                lastLightX = tx; lastLightY = ty
                invalidateVisibleGlass()
            }
        }
    }

    private fun invalidateVisibleGlass() {
        dock.invalidate(); indicator.invalidate(); searchButton?.invalidate()
        homePages.getOrNull(pager.currentPage)?.folderViews?.forEach { it.invalidate() }
    }

    private val appsListener: (List<AppInfo>) -> Unit = { apps -> onAppsChanged(apps) }
    private val iconListener: () -> Unit = { reloadIcons() }
    private val glassListener: () -> Unit = { if (built) Glass.invalidateTree(root) }
    private val wallpaperColors = android.app.WallpaperManager.OnColorsChangedListener { _, _ ->
        if (built) Glass.load(this, dimens.width, dimens.height)
    }
    private var storageAccess = false
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != "layout" && key?.startsWith("launch_") != true) settingsDirty = true
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    closeAllOverlays(animate = false)
                    if (isEditMode) exitEditMode()
                    if (launcher.prefs.unlockAnimation && built) {
                        pendingUnlock = true
                        prepareUnlock()
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    if (resumed) playUnlock()
                    else root.postDelayed({ if (!resumed && pendingUnlock) cancelUnlock() }, 1200)
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        root = DragLayer(this, drag)
        setContentView(root)
        applyFullscreen()

        statusMonitor = DeviceStatusMonitor(this) { s -> homePages.firstOrNull()?.statusView?.status = s }

        root.doOnLayout { if (!built) build(it.width, it.height) }

        launcher.repository.addListener(appsListener)
        launcher.icons.addChangeListener(iconListener)
        launcher.prefs.registerListener(prefsListener)
        Glass.addListener(glassListener)
        android.app.WallpaperManager.getInstance(this).addOnColorsChangedListener(wallpaperColors, android.os.Handler(mainLooper))
        storageAccess = android.os.Environment.isExternalStorageManager()

        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_EXPORTED)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    overlays.isNotEmpty() -> overlays.last().let { if (it is SearchOverlay || !it.closing) it.close() }
                    isEditMode -> exitEditMode()
                    built && pager.currentPage != 0 -> pager.snapTo(0)
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        launcher.repository.removeListener(appsListener)
        launcher.icons.removeChangeListener(iconListener)
        launcher.prefs.unregisterListener(prefsListener)
        Glass.removeListener(glassListener)
        runCatching { android.app.WallpaperManager.getInstance(this).removeOnColorsChangedListener(wallpaperColors) }
        runCatching { unregisterReceiver(screenReceiver) }
    }

    override fun onStart() {
        super.onStart()
        statusMonitor.start()
        runCatching { widgetHost.startListening() }
    }

    private var closeSearchOnStop = false

    override fun onStop() {
        super.onStop()
        clearOpenCard()
        if (closeSearchOnStop) {
            closeSearchOnStop = false
            overlays.filterIsInstance<SearchOverlay>().forEach { it.hideKeyboard(); removeOverlay(it) }
            if (overlays.isEmpty()) setOverlayBlur(0f)
        }
        statusMonitor.stop()
        runCatching { widgetHost.stopListening() }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        applyFullscreen()
        if (!built) return
        if (settingsDirty) {
            // Icon sizes / labels change the whole grid metrics: rebuild the launcher UI.
            settingsDirty = false
            recreate()
            return
        }
        clearOpenCard()
        resetHomeTransform()
        if (launcher.prefs.parallax) parallax.start()
        val access = android.os.Environment.isExternalStorageManager()
        if (access != storageAccess) { storageAccess = access; Glass.load(this, dimens.width, dimens.height) }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (pendingUnlock && !keyguard.isKeyguardLocked) {
            unlockDelays.clear()
            playUnlock()
        } else if (returningFromApp) {
            if (!playCloseToIcon()) playReturnFromApp()
        }
        returningFromApp = false
        library.setApps(launcher.repository.apps)
    }

    override fun onPause() {
        super.onPause()
        parallax.stop()
        resumed = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    override fun onNewIntent(intent: Intent) {
        val alreadyOnHome = hasWindowFocus() &&
            (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) != Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT
        super.onNewIntent(intent)
        if (!built || Intent.ACTION_MAIN != intent.action) return
        if (alreadyOnHome) {
            when {
                overlays.isNotEmpty() -> closeAllOverlays(true)
                isEditMode -> exitEditMode()
                else -> { pager.snapTo(0); library.scrollToTop() }
            }
        } else {
            closeAllOverlays(false)
        }
    }

    /** Full screen: status bar hidden (swipe from the top edge shows it transiently). */
    private fun applyFullscreen() {
        val c = window.insetsController ?: return
        if (launcher.prefs.hideStatusBar) {
            c.hide(WindowInsets.Type.statusBars())
            c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            c.show(WindowInsets.Type.statusBars())
        }
        c.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
    }

    // ------------------------------------------------------------------------------------------
    // Building the UI
    // ------------------------------------------------------------------------------------------

    private fun build(width: Int, height: Int) {
        val insets = window.decorView.rootWindowInsets
        val top = if (launcher.prefs.hideStatusBar) insets?.getInsets(WindowInsets.Type.displayCutout())?.top ?: 0
        else insets?.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())?.top ?: 0
        val bottom = insets?.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())?.bottom ?: 0
        dimens = Dimens(this, width, height, top, bottom)

        pager = PagedView(this)
        pager.callbacks = this
        library = AppLibraryView(this, this)
        pager.addView(library)
        root.addView(pager, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        indicator = PageIndicator(this)
        root.addView(indicator, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dimens.indicatorHeight, Gravity.BOTTOM).apply {
            bottomMargin = dimens.dockBottom + dimens.dockHeight
        })
        indicator.capsule = dimens.dockEnabled && dimens.page0ExtraRows == 0
        if (dimens.showSearchButton) {
            val sb = SearchButton(this)
            sb.setOnClickListener { openSearch() }
            val size = dimens.searchButtonSize
            root.addView(sb, FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.END).apply {
                bottomMargin = (dimens.dockBottom + dimens.searchCenterFromBottom - size / 2f).roundToInt()
                rightMargin = dimens.sidePadding + (dimens.cellWidth - size) / 2
            })
            searchButton = sb
            // No dock at all: dots raised above the search button.
            if (!dimens.dockEnabled) indicator.translationY = -(dimens.dotsCenterFromBottom - dimens.indicatorHeight / 2f)
        }
        Glass.load(this, width, height)

        dock = DockView(this, this)
        root.addView(dock, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dimens.dockHeight.coerceAtLeast(1), Gravity.BOTTOM).apply {
            bottomMargin = dimens.dockBottom
        })
        if (!dimens.dockEnabled) dock.visibility = View.GONE

        doneButton = pillButton("Done") { exitEditMode() }
        settingsButton = pillButton("Settings") { startActivity(Intent(this, SettingsActivity::class.java)) }
        addWidgetButton = pillButton("+") { openWidgetPicker() }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, dimens.pt(22f)); setPadding(0, 0, 0, dimens.pt(2f).roundToInt())
            contentDescription = "Add Widget"
        }
        val pillH = dimens.pt(30f).roundToInt()
        // One row — Settings, +, Done — level with the battery/Wi-Fi/signal glyph and just to its left.
        // Glyph geometry mirrors StatusClusterView: cell lifted by icon/4, glyph = 70% icon, bottom-aligned to the icon.
        val glyph = dimens.iconSize * StatusClusterView.GLYPH_RATIO
        val glyphCenterY = dimens.gridTop - dimens.iconSize / 4f + dimens.iconTopIn(0) + dimens.iconSize - glyph / 2f
        val glyphLeft = dimens.sidePadding + (dimens.cols - 1) * dimens.cellWidth + (dimens.cellWidth - glyph) / 2f
        val gapPt = dimens.pt(8f).roundToInt()
        val editRow = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        editRow.addView(settingsButton, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, pillH))
        editRow.addView(addWidgetButton, android.widget.LinearLayout.LayoutParams(pillH, pillH).apply { leftMargin = gapPt })
        editRow.addView(doneButton, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, pillH).apply { leftMargin = gapPt })
        val rowTop = (glyphCenterY - pillH / 2f).roundToInt().coerceAtLeast(dimens.pt(6f).roundToInt())
        root.addView(editRow, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, pillH, Gravity.END).apply {
            topMargin = rowTop
            rightMargin = (dimens.width - glyphLeft + dimens.pt(10f)).roundToInt()
        })
        // Other pages have icons in their top row: there the buttons ride up above the grid.
        editRowLift = (rowTop - (dimens.gridTop - dimens.pt(34f)).coerceAtLeast(dimens.pt(6f))).coerceAtLeast(0f)
        editButtonsRow = editRow

        built = true
        cleanupWidgetIds()
        if (launcher.repository.loaded) onAppsChanged(launcher.repository.apps)
    }

    private fun pillButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, dimens.pt(15f))
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 600, false)
        gravity = Gravity.CENTER
        setPadding(dimens.pt(14f).roundToInt(), 0, dimens.pt(14f).roundToInt(), 0)
        background = GlassDrawable(dimens.pt(15f), Color.argb(40, 255, 255, 255))
        visibility = View.GONE
        setOnClickListener { onClick() }
    }

    private fun onAppsChanged(apps: List<AppInfo>) {
        if (!built) return
        // Widgets whose provider was uninstalled disappear with it.
        layoutStore.widgets.keys.toList().forEach { k ->
            val id = widgetId(k) ?: return@forEach
            if (widgetManager.getAppWidgetInfo(id) == null) { runCatching { widgetHost.deleteAppWidgetId(id) }; layoutStore.removeFromHome(k) }
        }
        layoutStore.sync(apps)
        applyLayout(false)
        library.setApps(apps)
    }

    /** Push [LayoutStore] into the page/dock views. */
    fun applyLayout(animate: Boolean) {
        if (!built) return
        val pages = layoutStore.pages
        val wasOnLibrary = homePages.isNotEmpty() && pager.currentPage >= homePages.size
        val countBefore = homePages.size
        while (homePages.size < pages.size) {
            val pv = HomePageView(this, this, homePages.size)
            pager.addView(pv, homePages.size)
            homePages += pv
        }
        while (homePages.size > pages.size) {
            val pv = homePages.removeAt(homePages.lastIndex)
            pager.removeView(pv)
        }
        homePages.forEachIndexed { i, pv ->
            pv.updatePageIndex(i)
            pv.setItems(pages[i], animate)
        }
        homePages.firstOrNull()?.statusView?.status = statusMonitor.state
        dock.setItems(layoutStore.dock, animate)
        indicator.count = homePages.size
        if (wasOnLibrary && countBefore != homePages.size) pager.snapTo(homePages.size, animate = false)
        indicator.position = pager.position
        onPageScroll(pager.position)   // apply dock / indicator state for the current page
    }

    private fun reloadIcons() {
        if (!built) return
        homePages.forEach { it.reloadIcons() }
        dock.reloadIcons()
        library.refreshIcons()
    }

    fun viewForKey(key: String?): View? {
        key ?: return null
        homePages.forEach { p -> p.viewFor(key)?.let { return it } }
        return dock.viewFor(key)
    }

    // ------------------------------------------------------------------------------------------
    // Pager callbacks
    // ------------------------------------------------------------------------------------------

    override fun onPageScroll(position: Float) {
        val lastHome = (homePages.size - 1).coerceAtLeast(0)
        libProgress = (position - lastHome).coerceIn(0f, 1f)
        firstPageHide = if (launcher.prefs.hideDockOnFirstPage && homePages.size > 1) (1f - position).coerceIn(0f, 1f)
            else if (launcher.prefs.hideDockOnFirstPage) 1f else 0f
        if (!unlockPrepared) {
            dock.translationY = dockTargetTY()
            dock.alpha = dockTargetAlpha()
            dock.visibility = if (dimens.dockEnabled && dockTargetAlpha() > 0.01f) View.VISIBLE else if (dimens.dockEnabled) View.INVISIBLE else View.GONE
            bottomBar.forEach { it.alpha = barAlpha(it) }
            // Page 1 grid uses the dock's space: the dots slide down into the bottom band.
            if (dimens.page0ExtraRows > 0)
                indicator.translationY = firstPageHide * (dimens.dockHeight + dimens.indicatorHeight / 2f - dimens.dotsCenterFromBottom)
        }
        // Don't let the invisible search button catch taps on the App Library.
        searchButton?.let { it.visibility = if (barAlpha(it) < 0.02f) View.INVISIBLE else View.VISIBLE }
        indicator.position = position
        editButtonsRow?.translationY = -editRowLift * position.coerceIn(0f, 1f)
        // Glass on moving pages must re-sample the (static) wallpaper.
        val lo = kotlin.math.floor(position).toInt(); val hi = kotlin.math.ceil(position).toInt()
        for (i in lo..hi) homePages.getOrNull(i)?.folderViews?.forEach { it.invalidate() }
        if (libProgress > 0f && libProgress < 1f) library.invalidateGlass()
        libraryBlur = libProgress
        updateBlur()
    }

    override fun onPageSettled(page: Int) {}

    override fun onSwipeDown(startY: Float, startX: Float) {
        if (isEditMode) return
        if (startY < dimens.gridTop + dimens.cellHeight * 0.4f) {
            expandSystemPanel(settings = startX > dimens.width / 2f)   // left: notifications, right: Control Center
        } else {
            openSearch()
        }
    }

    override fun onEmptyLongPress() { if (!isEditMode && pager.currentPage < homePages.size) enterEditMode() }

    override fun onEmptyTap() { if (isEditMode) exitEditMode() }

    override fun canScroll() = !drag.dragging && overlays.isEmpty()

    @SuppressLint("WrongConstant")
    private fun expandSystemPanel(settings: Boolean) {
        runCatching {
            val sbm = getSystemService("statusbar")
            sbm.javaClass.getMethod(if (settings) "expandSettingsPanel" else "expandNotificationsPanel").invoke(sbm)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Blur
    // ------------------------------------------------------------------------------------------

    override fun setOverlayBlur(fraction: Float) {
        overlayBlur = fraction
        updateBlur()
        applyHomeBackdrop(fraction)
    }

    private var lastBackdropRadius = -1

    /**
     * While an overlay (search, menu, folder, alert…) is up, the home screen recedes like iOS:
     * a live blur of the icons/dock/widgets (RenderEffect), a slight zoom-out for depth and a dim.
     */
    private fun applyHomeBackdrop(fraction: Float) {
        if (!built) return
        val f = fraction.coerceIn(0f, 1f)
        val e = f * f * (3 - 2 * f)
        val radius = (e * dimens.dp(26f)).roundToInt()
        val layers = listOfNotNull<View>(pager, dock, indicator, searchButton, editButtonsRow)
        if (radius != lastBackdropRadius) {
            lastBackdropRadius = radius
            val effect = if (radius >= 1) android.graphics.RenderEffect.createBlurEffect(
                radius.toFloat(), radius.toFloat(), android.graphics.Shader.TileMode.CLAMP) else null
            layers.forEach { it.setRenderEffect(effect) }
        }
        if (unlockPrepared || closeAnim != null) return     // those animations own scale/alpha for now
        val scale = 1f - 0.07f * e
        for (v in listOf<View>(pager, dock)) {
            v.pivotX = dimens.width / 2f
            v.pivotY = if (v === dock) -(dimens.dockTop - dimens.height / 2f) else dimens.height / 2f
            v.scaleX = scale; v.scaleY = scale
        }
        pager.alpha = 1f - 0.35f * e
        dock.alpha = dockTargetAlpha() * (1f - 0.35f * e)
    }

    private fun updateBlur() {
        val f = maxOf(libraryBlur, overlayBlur).coerceIn(0f, 1f)
        val wm = getSystemService(WindowManager::class.java)
        val blurOk = wm.isCrossWindowBlurEnabled
        val radius = if (blurOk) (f * dimens.pt(40f)).roundToInt() else 0
        if (radius != lastBlurRadius) {
            lastBlurRadius = radius
            window.setBackgroundBlurRadius(radius)
        }
        val dim = if (blurOk) 0.18f else 0.45f
        root.setBackgroundColor(Color.argb((255 * dim * libraryBlur).roundToInt(), 0, 0, 0))
    }

    // ------------------------------------------------------------------------------------------
    // Edit mode
    // ------------------------------------------------------------------------------------------

    fun enterEditMode() {
        if (isEditMode) return
        isEditMode = true
        homePages.forEach { it.setEditMode(true) }
        dock.setEditMode(true)
        editButtons.forEach {
            it.visibility = View.VISIBLE; it.alpha = 0f; it.animate().alpha(1f).setDuration(200).start()
        }
    }

    fun exitEditMode() {
        if (!isEditMode) return
        isEditMode = false
        homePages.forEach { it.setEditMode(false) }
        dock.setEditMode(false)
        editButtons.forEach { b ->
            b.animate().alpha(0f).setDuration(200).withEndAction { b.visibility = View.GONE }.start()
        }
        layoutStore.normalize()
        layoutStore.save()
        applyLayout(true)
        if (pager.currentPage > homePages.size && pager.currentPage != pager.pageCount - 1) pager.snapTo(homePages.size - 1)
    }

    // ------------------------------------------------------------------------------------------
    // LauncherHost
    // ------------------------------------------------------------------------------------------

    override fun launchApp(view: View, app: AppInfo, iconBounds: Rect?) {
        val la = getSystemService(LauncherApps::class.java)
        val bounds = iconBounds ?: Rect(dimens.width / 2, dimens.height / 2, dimens.width / 2 + 1, dimens.height / 2 + 1)
        // App zooms up out of its icon (the user's preferred open animation).
        val opts = ActivityOptions.makeScaleUpAnimation(root, bounds.left, bounds.top, bounds.width(), bounds.height())
        try {
            la.startMainActivity(app.component, app.user, bounds, opts.toBundle())
            launcher.prefs.recordLaunch(app.key)
            returningFromApp = true
            // Remember the icon so the app can shrink back into it when closed.
            lastLaunchKey = (view as? AppIconView)?.takeIf { it.source == IconSource.HOME || it.source == IconSource.DOCK }?.app?.key
            // Search closes once an app opens (carousel or its suggestions row).
            overlays.filterIsInstance<SearchOverlay>().forEach { it.close() }
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onIconLongPress(view: AppIconView) {
        val app = view.app ?: return
        if (isEditMode && (view.source == IconSource.HOME || view.source == IconSource.DOCK)) return
        val inFolder = openFolderId?.let { id -> layoutStore.folders[id]?.takeIf { app.key in it.apps } }
        val items = when {
            view.source == IconSource.FOLDER && inFolder != null -> listOf(
                MenuItem("Remove from Folder") {
                    layoutStore.removeFromFolder(inFolder.id, app.key)
                    overlays.filterIsInstance<FolderOverlay>().forEach { it.close() }
                    applyLayout(true)
                },
                MenuItem("Edit Icon") { editIcon(app) },
                MenuItem("App Info") { appInfo(app) },
                MenuItem("Delete App", destructive = true) { uninstall(app) },
            )
            view.source == IconSource.HOME || view.source == IconSource.DOCK -> listOf(
                MenuItem("Edit Home Screen") { enterEditMode() },
                MenuItem("Edit Icon") { editIcon(app) },
                MenuItem("App Info") { appInfo(app) },
                MenuItem("Remove App", destructive = true) { showRemoveAlert(app) },
            )
            else -> libraryMenu(app)
        }
        showMenu(view.bitmap ?: launcher.icons.cached(app, view.iconSize), view.iconBoundsOnScreen(), items)
    }

    override fun onLibraryLongPress(app: AppInfo, iconBounds: Rect) {
        val bmp = launcher.icons.loadSync(app, iconBounds.width().coerceAtLeast(1))
        showMenu(bmp, iconBounds, libraryMenu(app))
    }

    private fun libraryMenu(app: AppInfo): List<MenuItem> = buildList {
        if (!layoutStore.isOnHome(app.key)) add(MenuItem("Add to Home Screen") {
            layoutStore.appendToHome(app.key); layoutStore.normalize(); layoutStore.save(); applyLayout(false)
            Toast.makeText(this@MainActivity, "Added to Home Screen", Toast.LENGTH_SHORT).show()
        })
        add(MenuItem("Edit Icon") { editIcon(app) })
        add(MenuItem("App Info") { appInfo(app) })
        add(MenuItem("Delete App", destructive = true) { uninstall(app) })
    }

    private fun showMenu(bitmap: android.graphics.Bitmap?, rect: Rect, items: List<MenuItem>) {
        addOverlay(MenuOverlay(this, this, bitmap, rect, items))
    }

    override fun onIconDragStart(view: View, rawX: Float, rawY: Float) {
        if (view is AppIconView && view.source != IconSource.HOME && view.source != IconSource.DOCK) return
        if (view !is DragItem) return
        overlays.filterIsInstance<MenuOverlay>().forEach { removeOverlay(it) }
        setOverlayBlur(0f)
        if (!isEditMode) enterEditMode()
        drag.start(view, rawX, rawY)
    }

    override fun onRemoveRequested(view: View) {
        when (view) {
            is AppIconView -> view.app?.let { showRemoveAlert(it) }
            is FolderView -> view.folder?.let { showRemoveFolderAlert(it) }
            is WidgetCell -> showRemoveWidgetAlert(view.key)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Widgets
    // ------------------------------------------------------------------------------------------

    private fun widgetId(key: String) = key.removePrefix(LayoutStore.WIDGET_PREFIX).toIntOrNull()

    override fun createWidgetView(key: String): View? {
        val id = widgetId(key) ?: return null
        val info = widgetManager.getAppWidgetInfo(id) ?: return null
        val hv = runCatching { widgetHost.createView(this, id, info) }.getOrNull() ?: return null
        return WidgetCell(this, this, key, hv)
    }

    /** Grid cells a widget wants: Android 12+ target cells, else its minimum size in our cells. */
    private fun spanFor(info: android.appwidget.AppWidgetProviderInfo): Pair<Int, Int> {
        val sx = if (info.targetCellWidth > 0) info.targetCellWidth
            else kotlin.math.ceil(info.minWidth / dimens.cellWidth.toFloat()).toInt()
        val sy = if (info.targetCellHeight > 0) info.targetCellHeight
            else kotlin.math.ceil(info.minHeight / dimens.cellHeight.toFloat()).toInt()
        return sx.coerceIn(1, dimens.cols) to sy.coerceIn(1, dimens.rows - 1)
    }

    private fun openWidgetPicker() {
        val providers = runCatching { widgetManager.getInstalledProvidersForProfile(android.os.Process.myUserHandle()) }
            .getOrDefault(emptyList())
            .filter { it.widgetCategory and android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 }
        addOverlay(WidgetPickerOverlay(this, this, providers, ::spanFor) { info -> addWidget(info) })
    }

    private fun addWidget(info: android.appwidget.AppWidgetProviderInfo) {
        val id = widgetHost.allocateAppWidgetId()
        pendingWidget = id to info
        val allowed = runCatching { widgetManager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null) }.getOrDefault(false)
        if (allowed) configureOrPlaceWidget(id, info)
        else bindWidget.launch(Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile))
    }

    private fun configureOrPlaceWidget(id: Int, info: android.appwidget.AppWidgetProviderInfo) {
        val optional = info.widgetFeatures and android.appwidget.AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        if (info.configure != null && !optional) {
            val started = runCatching { widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_WIDGET_CONFIG, null) }.isSuccess
            if (started) return
        }
        placeWidget(id, info)
    }

    private fun placeWidget(id: Int, info: android.appwidget.AppWidgetProviderInfo) {
        pendingWidget = null
        val (sx, sy) = spanFor(info)
        val key = LayoutStore.WIDGET_PREFIX + id
        layoutStore.widgets[key] = com.ioslauncher.model.WidgetSpec(id, sx, sy)
        val page = pager.currentPage.coerceAtMost(homePages.size - 1).coerceAtLeast(0)
        layoutStore.addToPage(key, page)
        applyLayout(true)
        if (pager.currentPage >= homePages.size) pager.snapTo(page)
        val p = layoutStore.pageOf(key)
        if (p >= 0 && p != pager.currentPage) pager.snapTo(p)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_WIDGET_CONFIG) {
            val p = pendingWidget
            if (p != null) { if (resultCode == RESULT_OK) placeWidget(p.first, p.second) else { widgetHost.deleteAppWidgetId(p.first); pendingWidget = null } }
            return
        }
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onWidgetLongPress(view: WidgetCell) {
        if (isEditMode) return
        val id = widgetId(view.key) ?: return
        val info = widgetManager.getAppWidgetInfo(id)
        val cols = dimens.cols
        val items = buildList {
            val reconfigurable = info?.configure != null &&
                info.widgetFeatures and android.appwidget.AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0
            if (reconfigurable) add(MenuItem("Edit Widget") {
                runCatching { widgetHost.startAppWidgetConfigureActivityForResult(this@MainActivity, id, 0, REQ_WIDGET_RECONFIG, null) }
            })
            add(MenuItem("Small  ·  2 × 2") { onFolderResize(view.key, minOf(2, cols), 2, true) })
            add(MenuItem("Medium  ·  $cols × 2") { onFolderResize(view.key, cols, 2, true) })
            add(MenuItem("Large  ·  $cols × 4") { onFolderResize(view.key, cols, 4, true) })
            add(MenuItem("Edit Home Screen") { enterEditMode() })
            add(MenuItem("Remove Widget", destructive = true) { showRemoveWidgetAlert(view.key) })
        }
        showMenu(view.dragSnapshot(), view.dragBoundsOnScreen(), items)
    }

    private fun showRemoveWidgetAlert(key: String) {
        addOverlay(AlertOverlay(this, this, "Remove Widget?",
            "Removing this widget will not delete any apps or data.",
            listOf(
                AlertButton("Remove", AlertButton.DESTRUCTIVE) { removeWidget(key) },
                AlertButton("Cancel", AlertButton.BOLD),
            )))
    }

    private fun removeWidget(key: String) {
        widgetId(key)?.let { runCatching { widgetHost.deleteAppWidgetId(it) } }
        layoutStore.removeFromHome(key)
        applyLayout(true)
    }

    /** Free widget ids the host still holds but the layout no longer uses (e.g. after a crash mid-add). */
    private fun cleanupWidgetIds() {
        val used = layoutStore.widgets.values.map { it.appWidgetId }.toSet()
        runCatching { widgetHost.appWidgetIds }.getOrNull()?.forEach { id ->
            if (id !in used && pendingWidget?.first != id) runCatching { widgetHost.deleteAppWidgetId(id) }
        }
    }

    private fun showRemoveFolderAlert(f: Folder) {
        addOverlay(AlertOverlay(this, this, "Remove “${f.name}”?",
            "The apps in this folder will stay in your App Library.",
            listOf(
                AlertButton("Remove Folder", AlertButton.DESTRUCTIVE) { layoutStore.removeFromHome(f.id); applyLayout(true) },
                AlertButton("Cancel", AlertButton.BOLD),
            )))
    }

    // ------------------------------------------------------------------------------------------
    // Folders
    // ------------------------------------------------------------------------------------------

    private var openFolderId: String? = null

    /** iOS names a new folder after the apps' category. */
    fun folderNameFor(targetKey: String, draggedKey: String): String {
        val repo = launcher.repository
        val c1 = repo.find(targetKey)?.category
        val c2 = repo.find(draggedKey)?.category
        val c = c1 ?: c2
        return if (c == null || c == com.ioslauncher.model.Categories.OTHER) "Folder" else c
    }

    override fun openFolder(folderId: String, from: Rect) {
        val f = layoutStore.folders[folderId] ?: return
        if (isEditMode && drag.dragging) return
        openFolderId = folderId
        addOverlay(FolderOverlay(this, this, f, from))
    }

    override fun renameFolder(folderId: String, name: String) {
        val f = layoutStore.folders[folderId] ?: return
        val n = name.trim().ifEmpty { "Folder" }
        if (n != f.name) { f.name = n; layoutStore.save(); applyLayout(false) }
    }

    override fun onFolderClosed(folderId: String) { if (openFolderId == folderId) openFolderId = null }

    override fun onFolderLongPress(view: FolderView) {
        val f = view.folder ?: return
        if (isEditMode) return
        val cols = dimens.cols
        val items = listOf(
            MenuItem("Open Folder") { openFolder(f.id, view.dragBoundsOnScreen()) },
            MenuItem("Small  ·  1 × 1") { onFolderResize(f.id, 1, 1, true) },
            MenuItem("Medium  ·  2 × 2") { onFolderResize(f.id, minOf(2, cols), 2, true) },
            MenuItem("Wide  ·  ${cols} × 2") { onFolderResize(f.id, cols, 2, true) },
            MenuItem("Edit Home Screen") { enterEditMode() },
            MenuItem("Remove Folder", destructive = true) { showRemoveFolderAlert(f) },
        )
        showMenu(view.dragSnapshot(), view.dragBoundsOnScreen(), items)
    }

    /** Resize a folder or widget (cell units). */
    override fun onFolderResize(folderId: String, spanX: Int, spanY: Int, done: Boolean) {
        if (!layoutStore.isBlock(folderId)) return
        layoutStore.setSpan(folderId, spanX.coerceIn(1, dimens.cols),
            spanY.coerceIn(1, dimens.gridRows(layoutStore.pageOf(folderId).coerceAtLeast(0))))
        layoutStore.normalize(removeEmpty = done)
        if (done) layoutStore.save()
        applyLayout(true)
    }

    override fun isFolderDropTarget(key: String) = layoutStore.isFolder(key)

    override fun isHiddenForDrag(key: String) = drag.dragKey == key

    private fun showRemoveAlert(app: AppInfo) {
        addOverlay(AlertOverlay(this, this, "Remove “${app.label}”?",
            "Removing from Home Screen will keep the app in your App Library.",
            listOf(
                AlertButton("Delete App", AlertButton.DESTRUCTIVE) { uninstall(app) },
                AlertButton("Remove from Home Screen") {
                    layoutStore.removeFromHome(app.key); applyLayout(true)
                },
                AlertButton("Cancel", AlertButton.BOLD),
            )))
    }

    private fun editIcon(app: AppInfo) {
        startActivity(Intent(this, IconChooserActivity::class.java).putExtra(IconChooserActivity.EXTRA_KEY, app.key))
    }

    private fun appInfo(app: AppInfo) {
        runCatching { getSystemService(LauncherApps::class.java).startAppDetailsActivity(app.component, app.user, null, null) }
    }

    private fun uninstall(app: AppInfo) {
        runCatching {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null))
                .putExtra(Intent.EXTRA_USER, app.user))
        }
    }

    override fun openSearch() {
        if (overlays.any { it is SearchOverlay }) return
        addOverlay(SearchOverlay(this, this, launcher.repository.apps))
    }

    override fun openCategory(category: LibraryCategory, from: Rect) {
        addOverlay(CategoryOverlay(this, this, category, from))
    }

    private fun addOverlay(o: Overlay) {
        root.addView(o, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        overlays += o
        o.show()
    }

    override fun removeOverlay(overlay: Overlay) {
        overlay.animate().cancel()
        root.removeView(overlay)
        overlays -= overlay
    }

    private fun closeAllOverlays(animate: Boolean) {
        overlays.toList().forEach { if (animate) it.close() else { (it as? SearchOverlay)?.hideKeyboard(); removeOverlay(it) } }
        if (!animate) setOverlayBlur(0f)
    }

    // ------------------------------------------------------------------------------------------
    // iOS unlock + return-from-app animations
    // ------------------------------------------------------------------------------------------

    private val unlockViews = mutableListOf<View>()

    private fun unlockTargets(): List<View> {
        val page = pager.currentPage
        if (page >= homePages.size) return listOf(library)
        val pv = homePages[page]
        return buildList { pv.statusView?.let { add(it) }; addAll(pv.itemViews) }
    }

    private val unlockDelays = mutableListOf<Runnable>()
    private val unlockStart = HashMap<View, FloatArray>()   // view → (startDelayMs)

    /**
     * Called while the screen is off: every icon / folder / widget is parked beyond the nearest screen
     * edge (pushed outward from the screen centre), slightly enlarged, on its own layer.
     */
    private fun prepareUnlock() {
        if (unlockPrepared) return
        unlockPrepared = true
        unlockViews.clear(); unlockStart.clear()
        val w = dimens.width.toFloat(); val h = dimens.height.toFloat()
        val cx = w / 2f; val cy = h * 0.45f
        val targets = unlockTargets()
        val loc = IntArray(2)
        val centers = targets.map { v ->
            v.animate().cancel()
            v.translationX = 0f; v.translationY = 0f; v.scaleX = 1f; v.scaleY = 1f; v.alpha = 1f
            v.getLocationOnScreen(loc)
            Triple(v, loc[0] + v.width / 2f, loc[1] + v.height / 2f)
        }
        val minY = centers.minOfOrNull { it.third } ?: 0f
        val maxY = centers.maxOfOrNull { it.third } ?: 1f
        for ((v, vx, vy) in centers) {
            var dx = vx - cx; var dy = vy - cy
            val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
            dx /= len; dy /= len
            if (len < 2f) { dx = 0f; dy = -1f }
            // Distance needed to leave the screen along that direction, plus a margin.
            val tx = if (dx > 0) (w - vx) / dx else if (dx < 0) -vx / dx else Float.MAX_VALUE
            val ty = if (dy > 0) (h - vy) / dy else if (dy < 0) -vy / dy else Float.MAX_VALUE
            val out = minOf(tx, ty) + maxOf(v.width, v.height) * 0.9f + dimens.pt(30f)
            v.translationX = dx * out
            v.translationY = dy * out
            v.scaleX = 1.22f; v.scaleY = 1.22f
            // Rows land one after another (top first), with a hint of column ripple.
            val rowT = if (maxY > minY) (vy - minY) / (maxY - minY) else 0f
            val colT = kotlin.math.abs(vx - w / 2f) / (w / 2f)
            unlockStart[v] = floatArrayOf(rowT * 190f + colT * 40f)
            unlockViews += v
        }
        dock.animate().cancel()
        dock.translationY = dimens.dockHeight + dimens.dockBottom + dimens.pt(60f)
        dock.scaleX = 1.08f; dock.scaleY = 1.08f
        dock.alpha = dockTargetAlpha()
        bottomBar.forEach { it.alpha = 0f }
    }

    /**
     * iOS 26-style unlock: icons fly in from the screen edges, staggered row by row, and land with a
     * slight spring bounce; the dock rises from below on its own layer; then parallax takes over.
     */
    private fun playUnlock() {
        pendingUnlock = false
        if (!unlockPrepared) return
        unlockPrepared = false
        for (v in unlockViews) {
            val delay = (unlockStart[v]?.get(0) ?: 0f).toLong()
            val r = Runnable {
                spring(v, DynamicAnimation.TRANSLATION_X, 0f, 230f, 0.68f)
                spring(v, DynamicAnimation.TRANSLATION_Y, 0f, 230f, 0.68f)
                spring(v, DynamicAnimation.SCALE_X, 1f, 260f, 0.62f)
                spring(v, DynamicAnimation.SCALE_Y, 1f, 260f, 0.62f)
            }
            unlockDelays += r
            root.postDelayed(r, delay)
        }
        unlockViews.clear(); unlockStart.clear()
        val dockRun = Runnable {
            spring(dock, DynamicAnimation.TRANSLATION_Y, dockTargetTY(), 240f, 0.7f)
            spring(dock, DynamicAnimation.SCALE_X, 1f, 260f, 0.65f)
            spring(dock, DynamicAnimation.SCALE_Y, 1f, 260f, 0.65f)
        }
        unlockDelays += dockRun
        root.postDelayed(dockRun, 140)
        bottomBar.forEach { it.animate().alpha(barAlpha(it)).setStartDelay(320).setDuration(260).start() }
    }

    private fun cancelUnlock() {
        pendingUnlock = false
        unlockDelays.forEach { root.removeCallbacks(it) }; unlockDelays.clear()
        if (!unlockPrepared) return
        unlockPrepared = false
        for (v in unlockViews) { v.translationX = 0f; v.translationY = 0f; v.scaleX = 1f; v.scaleY = 1f; v.alpha = 1f }
        unlockViews.clear(); unlockStart.clear()
        dock.translationY = dockTargetTY()
        dock.scaleX = 1f; dock.scaleY = 1f; dock.alpha = dockTargetAlpha()
        bottomBar.forEach { it.alpha = barAlpha(it) }
    }

    private var lastLaunchKey: String? = null
    private var closeAnim: CloseToIconView? = null
    private var openCard: CloseToIconView? = null
    private var openHiddenIcon: View? = null

    private fun resetHomeTransform() {
        for (v in listOf<View>(pager, dock)) { v.animate().cancel(); v.scaleX = 1f; v.scaleY = 1f }
        pager.alpha = 1f
        dock.alpha = dockTargetAlpha()
    }

    private fun clearOpenCard() {
        openCard?.let { root.removeView(it) }
        openCard = null
        openHiddenIcon?.alpha = 1f
        openHiddenIcon = null
    }

    /**
     * Closing an app (swipe up / back / home): it shrinks back into its icon on the grid or dock.
     * Returns false when that icon isn't on screen (opened from search, App Library, another page…).
     */
    private fun playCloseToIcon(): Boolean {
        val key = lastLaunchKey ?: return false
        lastLaunchKey = null
        val v = viewForKey(key) as? AppIconView ?: return false
        val bmp = v.bitmap ?: return false
        val onScreen = when (v.source) {
            IconSource.DOCK -> dockAvailable
            else -> homePages.getOrNull(pager.currentPage)?.viewFor(key) === v && overlays.isEmpty()
        }
        if (!onScreen) return false
        closeAnim?.let { root.removeView(it) }
        val target = v.iconBoundsOnScreen()
        // The system has already shrunk the window a bit during the gesture: start just inside the screen.
        val inset = dimens.width * 0.06f
        val from = android.graphics.RectF(inset, inset * 1.6f, dimens.width - inset, dimens.height - inset * 1.6f)
        val card = CloseToIconView(this, from, target, bmp)
        root.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        closeAnim = card
        v.alpha = 0f
        // Home screen eases forward slightly underneath, like iOS.
        for (h in listOf<View>(pager, dock)) {
            h.pivotX = dimens.width / 2f
            h.pivotY = if (h === dock) dimens.dockHeight / 2f else dimens.height / 2f
            h.scaleX = 1.05f; h.scaleY = 1.05f
            spring(h, DynamicAnimation.SCALE_X, 1f, 220f)
            spring(h, DynamicAnimation.SCALE_Y, 1f, 220f)
        }
        val holder = androidx.dynamicanimation.animation.FloatValueHolder(0f)
        androidx.dynamicanimation.animation.SpringAnimation(holder).apply {
            spring = androidx.dynamicanimation.animation.SpringForce(1f).setStiffness(240f).setDampingRatio(0.8f)
            setStartValue(0f)
            addUpdateListener { _, value, _ -> card.progress = value }
            addEndListener { _, _, _, _ ->
                v.alpha = 1f
                root.removeView(card)
                if (closeAnim === card) closeAnim = null
            }
            start()
        }
        return true
    }

    /** Coming back from an app: the home screen settles back from a slight zoom, like iOS. */
    private fun playReturnFromApp() {
        for (v in listOf<View>(pager, dock)) {
            v.pivotX = dimens.width / 2f
            v.pivotY = if (v === dock) dimens.dockHeight / 2f else dimens.height / 2f
            v.scaleX = 1.08f; v.scaleY = 1.08f
            spring(v, DynamicAnimation.SCALE_X, 1f, 260f)
            spring(v, DynamicAnimation.SCALE_Y, 1f, 260f)
        }
        pager.alpha = 0.4f
        pager.animate().alpha(1f).setDuration(220).start()
    }

    private fun spring(v: View, prop: DynamicAnimation.ViewProperty, target: Float, stiffness: Float = 190f, damping: Float = 0.83f) {
        SpringAnimation(v, prop, target).apply {
            spring.stiffness = stiffness
            spring.dampingRatio = damping
            start()
        }
    }

    companion object {
        private const val WIDGET_HOST_ID = 1024
        private const val REQ_WIDGET_CONFIG = 41
        private const val REQ_WIDGET_RECONFIG = 42
    }
}
