package com.ioslauncher

import android.app.Application
import com.ioslauncher.icons.IconProvider
import com.ioslauncher.model.AppRepository
import com.ioslauncher.model.LayoutStore
import com.ioslauncher.model.Prefs

class LauncherApp : Application() {
    lateinit var prefs: Prefs; private set
    lateinit var repository: AppRepository; private set
    lateinit var icons: IconProvider; private set
    lateinit var layout: LayoutStore; private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashRecorder()
        prefs = Prefs(this)
        icons = IconProvider(this, prefs)
        repository = AppRepository(this)
        layout = LayoutStore(this, prefs)
    }

    /** Saves the last crash's stack trace so it can be copied from Settings and sent for debugging. */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = java.io.StringWriter()
                error.printStackTrace(java.io.PrintWriter(sw))
                val info = packageManager.getPackageInfo(packageName, 0)
                crashFile().writeText("iOS Launcher ${info.versionName} · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
                    "${java.util.Date()} · thread ${thread.name}\n\n$sw")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun crashFile() = java.io.File(filesDir, "last_crash.txt")

    companion object {
        lateinit var instance: LauncherApp; private set
    }
}
