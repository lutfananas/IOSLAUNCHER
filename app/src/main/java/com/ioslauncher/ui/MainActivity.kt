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
    private lateinit var doneButton: TextView
    private lateinit var settingsButton: TextView
    private val overlays = mutableListOf<Overlay>()
    private var built = false

    override var isEditMode = false; private set

    private var libProgress = 0f
    private var libraryBlur = 0f
    private var overlayBlur = 0f
    private var lastBlurRadius = -1

    private var pendingUnlock = false
    private var unlockPrepared = false
    private var returningFromApp = false
    private var settingsDirty = false
    private var resumed = false

    private lateinit var statusMonitor: DeviceStatusMonitor

    private val appsListener: (List<AppInfo>) -> Unit = { apps -> onAppsChanged(apps) }
    private val iconListener: () -> Unit = { reloadIcons() }
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
        runCatching { unregisterReceiver(screenReceiver) }
    }

    override fun onStart() {
        super.onStart()
        statusMonitor.start()
    }

    override fun onStop() {
        super.onStop()
        statusMonitor.stop()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        applyFullscreen()
        if (!built) return
        if (settingsDirty) {
            settingsDirty = false
            reloadIcons()
            applyLayout(false)
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (pendingUnlock && !keyguard.isKeyguardLocked) {
            playUnlock()
        } else if (returningFromApp) {
            playReturnFromApp()
        }
        returningFromApp = false
        library.setApps(launcher.repository.apps)
    }

    override fun onPause() {
        super.onPause()
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

        dock = DockView(this, this)
        root.addView(dock, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dimens.dockHeight, Gravity.BOTTOM).apply {
            bottomMargin = dimens.dockBottom
        })

        doneButton = pillButton("Done") { exitEditMode() }
        settingsButton = pillButton("Settings") { startActivity(Intent(this, SettingsActivity::class.java)) }
        val pillTop = (dimens.gridTop - dimens.pt(34f)).roundToInt().coerceAtLeast(dimens.pt(8f).roundToInt())
        root.addView(doneButton, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dimens.pt(30f).roundToInt(), Gravity.END).apply {
            topMargin = pillTop; rightMargin = dimens.pt(16f).roundToInt()
        })
        root.addView(settingsButton, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dimens.pt(30f).roundToInt(), Gravity.START).apply {
            topMargin = pillTop; leftMargin = dimens.pt(16f).roundToInt()
        })

        built = true
        if (launcher.repository.loaded) onAppsChanged(launcher.repository.apps)
    }

    private fun pillButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, dimens.pt(15f))
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 600, false)
        gravity = Gravity.CENTER
        setPadding(dimens.pt(14f).roundToInt(), 0, dimens.pt(14f).roundToInt(), 0)
        background = SquircleDrawable(Color.argb(80, 255, 255, 255), dimens.pt(15f))
        visibility = View.GONE
        setOnClickListener { onClick() }
    }

    private fun onAppsChanged(apps: List<AppInfo>) {
        if (!built) return
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
    }

    private fun reloadIcons() {
        if (!built) return
        homePages.forEach { it.reloadIcons() }
        dock.reloadIcons()
        library.refreshIcons()
    }

    fun viewForKey(key: String?): AppIconView? {
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
        if (!unlockPrepared) {
            dock.translationY = libProgress * (dimens.dockHeight + dimens.dockBottom)
            dock.alpha = 1f - libProgress
            indicator.alpha = 1f - libProgress
        }
        indicator.position = position
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
        listOf(doneButton, settingsButton).forEach {
            it.visibility = View.VISIBLE; it.alpha = 0f; it.animate().alpha(1f).setDuration(200).start()
        }
        homePages.firstOrNull()?.statusView?.animate()?.alpha(0f)?.setDuration(200)?.start()
    }

    fun exitEditMode() {
        if (!isEditMode) return
        isEditMode = false
        homePages.forEach { it.setEditMode(false) }
        dock.setEditMode(false)
        listOf(doneButton, settingsButton).forEach { b ->
            b.animate().alpha(0f).setDuration(200).withEndAction { b.visibility = View.GONE }.start()
        }
        homePages.firstOrNull()?.statusView?.animate()?.alpha(1f)?.setDuration(200)?.start()
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
        val opts = ActivityOptions.makeScaleUpAnimation(root, bounds.left, bounds.top, bounds.width(), bounds.height())
        try {
            la.startMainActivity(app.component, app.user, bounds, opts.toBundle())
            launcher.prefs.recordLaunch(app.key)
            returningFromApp = true
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onIconLongPress(view: AppIconView) {
        val app = view.app ?: return
        if (isEditMode && (view.source == IconSource.HOME || view.source == IconSource.DOCK)) return
        val items = when (view.source) {
            IconSource.HOME, IconSource.DOCK -> listOf(
                MenuItem("Edit Home Screen") { enterEditMode() },
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
        add(MenuItem("App Info") { appInfo(app) })
        add(MenuItem("Delete App", destructive = true) { uninstall(app) })
    }

    private fun showMenu(bitmap: android.graphics.Bitmap?, rect: Rect, items: List<MenuItem>) {
        addOverlay(MenuOverlay(this, this, bitmap, rect, items))
    }

    override fun onIconDragStart(view: AppIconView, rawX: Float, rawY: Float) {
        if (view.source != IconSource.HOME && view.source != IconSource.DOCK) return
        overlays.filterIsInstance<MenuOverlay>().forEach { removeOverlay(it) }
        setOverlayBlur(0f)
        if (!isEditMode) enterEditMode()
        drag.start(view, rawX, rawY)
    }

    override fun onRemoveRequested(view: AppIconView) {
        view.app?.let { showRemoveAlert(it) }
    }

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
        return buildList { pv.statusView?.let { add(it) }; addAll(pv.iconViews) }
    }

    /** Called while the screen is off: put icons in their "outside the screen, zoomed" start state. */
    private fun prepareUnlock() {
        if (unlockPrepared) return
        unlockPrepared = true
        unlockViews.clear()
        val cx = dimens.width / 2f
        val cy = dimens.height / 2f
        for (v in unlockTargets()) {
            v.animate().cancel()
            v.translationX = 0f; v.translationY = 0f; v.scaleX = 1f; v.scaleY = 1f
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            val vx = loc[0] + v.width / 2f
            val vy = loc[1] + v.height / 2f
            v.translationX = (vx - cx) * 0.55f
            v.translationY = (vy - cy) * 0.55f
            v.scaleX = 1.7f; v.scaleY = 1.7f
            v.alpha = 0f
            unlockViews += v
        }
        dock.animate().cancel()
        dock.translationY = dimens.dockHeight * 0.7f
        dock.scaleX = 1.15f; dock.scaleY = 1.15f
        dock.alpha = 0f
        indicator.alpha = 0f
    }

    /** iOS unlock: icons fly in from beyond the screen edges, shrinking into place with a soft spring. */
    private fun playUnlock() {
        pendingUnlock = false
        if (!unlockPrepared) return
        unlockPrepared = false
        for (v in unlockViews) {
            spring(v, DynamicAnimation.TRANSLATION_X, 0f)
            spring(v, DynamicAnimation.TRANSLATION_Y, 0f)
            spring(v, DynamicAnimation.SCALE_X, 1f)
            spring(v, DynamicAnimation.SCALE_Y, 1f)
            v.animate().alpha(1f).setDuration(260).start()
        }
        unlockViews.clear()
        val dockTarget = libProgress * (dimens.dockHeight + dimens.dockBottom)
        spring(dock, DynamicAnimation.TRANSLATION_Y, dockTarget)
        spring(dock, DynamicAnimation.SCALE_X, 1f)
        spring(dock, DynamicAnimation.SCALE_Y, 1f)
        dock.animate().alpha(1f - libProgress).setDuration(260).start()
        indicator.animate().alpha(1f - libProgress).setStartDelay(180).setDuration(250).start()
    }

    private fun cancelUnlock() {
        pendingUnlock = false
        if (!unlockPrepared) return
        unlockPrepared = false
        for (v in unlockViews) { v.translationX = 0f; v.translationY = 0f; v.scaleX = 1f; v.scaleY = 1f; v.alpha = 1f }
        unlockViews.clear()
        dock.translationY = libProgress * (dimens.dockHeight + dimens.dockBottom)
        dock.scaleX = 1f; dock.scaleY = 1f; dock.alpha = 1f - libProgress
        indicator.alpha = 1f - libProgress
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

    private fun spring(v: View, prop: DynamicAnimation.ViewProperty, target: Float, stiffness: Float = 190f) {
        SpringAnimation(v, prop, target).apply {
            spring.stiffness = stiffness
            spring.dampingRatio = 0.83f
            start()
        }
    }
}
