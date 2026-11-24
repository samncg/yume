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

    lateinit var paletteStore: com.yume.app.data.PaletteStore
        private set

    override fun onCreate() {
        super.onCreate()
        MemoryBudget.configure(getSystemService(ActivityManager::class.java))
        com.yume.app.engine.BrushAssets.root = filesDir
        runCatching { com.yume.app.engine.BuiltinBrushAssets.ensure(filesDir) }
        // Tile caches are scratch: anything left over is from a crash.
        com.yume.app.engine.TileCache.removeStale(cacheDir)
        MemoryBudget.diskRoot = cacheDir
        projectStore = ProjectStore(this)
        com.yume.app.ai.Onnx.prepare(this)
        com.yume.app.ai.ModelStore(this).let { models ->
            com.yume.app.ai.AiDownloads.init(models)
            // Installed models get their NPU copies made in the background.
            com.yume.app.ai.Onnx.warmUp(com.yume.app.ai.AiModel.entries.filter { models.isReady(it) }.map { models.file(it) })
        }
        presetStore = PresetStore(this)
        com.yume.app.data.AppSettings.init(this)
        com.yume.app.data.Customization.init(this)
        com.yume.app.data.BrushPrefs.init(this)
        paletteStore = com.yume.app.data.PaletteStore(this)
    }
}
