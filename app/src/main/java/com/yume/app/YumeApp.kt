package com.yume.app

import android.app.ActivityManager
import android.app.Application
import com.yume.app.data.PresetStore
import com.yume.app.data.ProjectStore
import com.yume.app.engine.MemoryBudget

class YumeApp : Application() {

    lateinit var projectStore: ProjectStore
        private set

    lateinit var presetStore: PresetStore
        private set

    override fun onCreate() {
        super.onCreate()
        MemoryBudget.configure(getSystemService(ActivityManager::class.java))
        // Tile caches are scratch: anything left over is from a crash.
        com.yume.app.engine.TileCache.removeStale(cacheDir)
        projectStore = ProjectStore(this)
        presetStore = PresetStore(this)
    }
}
