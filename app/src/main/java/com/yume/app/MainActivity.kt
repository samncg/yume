package com.yume.app

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.yume.app.debug.YumeHarness
import com.yume.app.engine.TileStoreConfig
import com.yume.app.ui.EditorController
import com.yume.app.ui.EditorScreen
import com.yume.app.ui.GalleryScreen
import com.yume.app.ui.YumeTheme

class MainActivity : ComponentActivity() {

    private fun penHaptic() {
        window.decorView.performHapticFeedback(
            if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM
            else android.view.HapticFeedbackConstants.VIRTUAL_KEY
        )
    }

    // Smart-pen squeeze and double-tap arrive as keys / mouse buttons from the
    // pen's own input devices, not as touches.
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (com.yume.app.input.PenGestures.handleKey(event, ::penHaptic)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
        if (com.yume.app.input.PenGestures.handleButton(ev, ::penHaptic)) return true
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        // Debug-only storage switch. The default stays SingleTileStore; sparse
        // layers are exercised by the harness long before they become the live
        // store (Stage 2b).
        if (debuggable) {
            TileStoreConfig.useSparseLayers =
                intent?.getBooleanExtra("sparse_layers", false) == true
            TileStoreConfig.tileSize = intent?.getIntExtra(
                "tile_size",
                TileStoreConfig.DEFAULT_TILE_SIZE
            ) ?: TileStoreConfig.DEFAULT_TILE_SIZE
        }

        // Debug-only golden-hash harness; never reachable in a release build.
        if (debuggable && intent?.getBooleanExtra("yume_harness", false) == true) {
            YumeHarness.runAsync(
                applicationContext as YumeApp,
                intent?.getStringExtra("yume_harness_only")
            ) {
                runOnUiThread { finish() }
            }
            return
        }
        com.yume.app.input.PenGestures.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            YumeTheme {
                YumeRoot()
            }
        }
    }
}

@Composable
private fun YumeRoot() {
    val context = LocalContext.current
    val app = remember { context.applicationContext as YumeApp }
    var projectId by rememberSaveable { mutableStateOf<String?>(null) }

    val openId = projectId
    if (openId == null) {
        GalleryScreen(store = app.projectStore, onOpenProject = { projectId = it })
    } else {
        key(openId) {
            val controller = remember { EditorController(app.projectStore, app.presetStore, openId) }
            LaunchedEffect(Unit) { controller.load() }
            DisposableEffect(Unit) {
                onDispose { controller.dispose() }
            }
            EditorScreen(
                controller = controller,
                // Keep the editor composed until the document is on disk: the
                // render thread must still be alive when the final snapshot is
                // taken, otherwise the last property change can be lost.
                onExit = {
                    controller.saveThen(
                        onWarning = { message ->
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        },
                        action = { projectId = null }
                    )
                },
                // A cropped / resized copy was written: save this one, open it.
                onOpenProject = { newId ->
                    controller.saveThen(
                        onWarning = { message ->
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        },
                        action = { projectId = newId }
                    )
                }
            )
        }
    }
}
