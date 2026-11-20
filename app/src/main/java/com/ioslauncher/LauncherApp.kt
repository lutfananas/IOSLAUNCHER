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
        prefs = Prefs(this)
        icons = IconProvider(this, prefs)
        repository = AppRepository(this)
        layout = LayoutStore(this, prefs)
    }

    companion object {
        lateinit var instance: LauncherApp; private set
    }
}
