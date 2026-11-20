package com.ioslauncher.model

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import java.text.Collator
import java.util.concurrent.Executors

/** Loads every launchable activity of every user profile and keeps it fresh via [LauncherApps.Callback]. */
class AppRepository(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val listeners = mutableListOf<(List<AppInfo>) -> Unit>()

    @Volatile var apps: List<AppInfo> = emptyList(); private set
    var loaded = false; private set

    private val reloadRunnable = Runnable { reload() }

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = scheduleReload()
        override fun onPackageAdded(packageName: String, user: UserHandle) = scheduleReload()
        override fun onPackageChanged(packageName: String, user: UserHandle) = scheduleReload()
        override fun onPackagesAvailable(p: Array<out String>, user: UserHandle, replacing: Boolean) = scheduleReload()
        override fun onPackagesUnavailable(p: Array<out String>, user: UserHandle, replacing: Boolean) = scheduleReload()
    }

    init {
        launcherApps.registerCallback(callback, main)
        reload()
    }

    fun addListener(l: (List<AppInfo>) -> Unit) {
        listeners += l
        if (loaded) l(apps)
    }

    fun removeListener(l: (List<AppInfo>) -> Unit) { listeners -= l }

    fun find(key: String): AppInfo? = apps.firstOrNull { it.key == key }

    fun findByComponent(cn: ComponentName, user: UserHandle? = null): AppInfo? =
        apps.firstOrNull { it.component == cn && (user == null || it.user == user) }

    fun scheduleReload() {
        main.removeCallbacks(reloadRunnable)
        main.postDelayed(reloadRunnable, 250)
    }

    fun reload() {
        worker.execute {
            val result = ArrayList<AppInfo>()
            val myUser = android.os.Process.myUserHandle()
            for (user in userManager.userProfiles) {
                if (user != myUser && userManager.isQuietModeEnabled(user)) continue
                val serial = userManager.getSerialNumberForUser(user)
                val list = runCatching { launcherApps.getActivityList(null, user) }.getOrDefault(emptyList())
                for (info in list) {
                    if (info.componentName.packageName == context.packageName &&
                        info.componentName.className.endsWith("SettingsActivity").not()) continue
                    val ai = info.applicationInfo
                    result += AppInfo(
                        component = info.componentName,
                        user = user,
                        userSerial = serial,
                        label = info.label?.toString()?.trim().orEmpty().ifEmpty { info.componentName.packageName },
                        category = Categories.of(ai),
                        installTime = info.firstInstallTime,
                        isWorkProfile = user != myUser,
                    )
                }
            }
            val collator = Collator.getInstance()
            result.sortWith { a, b -> collator.compare(a.label, b.label) }
            main.post {
                apps = result
                loaded = true
                listeners.toList().forEach { it(result) }
            }
        }
    }
}

/** Maps Android app metadata to the App Library categories used by iOS. */
object Categories {
    const val SUGGESTIONS = "Suggestions"
    const val RECENT = "Recently Added"
    const val SOCIAL = "Social"
    const val PRODUCTIVITY = "Productivity & Finance"
    const val UTILITIES = "Utilities"
    const val ENTERTAINMENT = "Entertainment"
    const val CREATIVITY = "Photo & Video"
    const val GAMES = "Games"
    const val INFO = "Information & Reading"
    const val TRAVEL = "Travel"
    const val SHOPPING = "Shopping & Food"
    const val HEALTH = "Health & Fitness"
    const val OTHER = "Other"

    private val keywordMap = listOf(
        SOCIAL to listOf("whatsapp", "telegram", "instagram", "facebook", "messenger", "twitter", "x.android", "tiktok",
            "musically", "snapchat", "discord", "naver.line", "wechat", "thoughtcrime", "threads", "reddit", "linkedin", "mms",
            "messaging", "dialer", "contacts", "phone", "zhiliaoapp", "pinterest"),
        PRODUCTIVITY to listOf("bank", "bca", "bri", "mandiri", "bni", "pay", "wallet", "dana", "ovo", "finance",
            "office", "docs", "sheets", "slides", "drive", "mail", "gmail", "outlook", "calendar", "notes", "keep",
            "notion", "trello", "slack", "zoom", "teams", "meet", "invest", "stock", "crypto", "money", "jenius"),
        SHOPPING to listOf("shop", "tokopedia", "lazada", "blibli", "amazon", "store.android", "ebay", "food",
            "gojek", "grab", "mall", "bukalapak", "zalora", "alfagift", "mcd", "kfc", "starbucks"),
        ENTERTAINMENT to listOf("youtube", "netflix", "spotify", "music", "video", "vidio", "disney", "hotstar",
            "primevideo", ".tv", "joox", "soundcloud", "twitch", "player", "podcast", "radio", "viu", "wetv", "iqiyi"),
        CREATIVITY to listOf("camera", "gallery", "photo", "photos", "snapseed", "lightroom", "capcut", "picsart",
            "canva", "vsco", "editor", "inshot", "screenrecord"),
        HEALTH to listOf("health", "fit", "strava", "workout", "sleep", "halodoc", "alodokter", "peduli", "satusehat"),
        TRAVEL to listOf("maps", "waze", "traveloka", "tiket", "booking", "airbnb", "uber", "agoda", "kai.access", "krl"),
        INFO to listOf("news", "kompas", "detik", "kindle", "books", "reader", "wiki", "weather", "quran"),
        GAMES to listOf("game", "games", "mobile.legends", "moonton", "pubg", "tencent.ig", "garena", "supercell",
            "roblox", "mojang", "miHoYo", "hoyoverse", "freefire"),
        UTILITIES to listOf("settings", "clock", "calculator", "files", "filemanager", "documentsui", "browser",
            "chrome", "firefox", "opera", "brave", "vending", "recorder", "compass", "flashlight", "backup",
            "security", "vpn", "launcher", "keyboard", "translate", "authenticator", "googlequicksearchbox",
            "android.settings", "deskclock", "calendar"),
    )

    fun of(ai: ApplicationInfo): String {
        val pkg = ai.packageName.lowercase()
        // Keyword matching first — Android's own category field is unset for most apps.
        for ((cat, words) in keywordMap) {
            if (words.any { pkg.contains(it.lowercase()) }) return cat
        }
        return when (ai.category) {
            ApplicationInfo.CATEGORY_GAME -> GAMES
            ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO -> ENTERTAINMENT
            ApplicationInfo.CATEGORY_IMAGE -> CREATIVITY
            ApplicationInfo.CATEGORY_SOCIAL -> SOCIAL
            ApplicationInfo.CATEGORY_NEWS -> INFO
            ApplicationInfo.CATEGORY_MAPS -> TRAVEL
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> PRODUCTIVITY
            ApplicationInfo.CATEGORY_ACCESSIBILITY -> UTILITIES
            else -> if (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) UTILITIES else OTHER
        }
    }
}
