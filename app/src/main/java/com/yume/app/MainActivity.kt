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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import androidx.compose.ui.Modifier
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.togetherWith

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
                intent?.getBooleanExtra("sparse_layers", true) != false
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
        // Debug only: lay the app out in portrait without touching the
        // device's rotation settings (layout checks).
        if (debuggable && intent?.getBooleanExtra("force_portrait", false) == true) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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

    // Gallery and editor crossfade. The incoming screen always draws on top, so
    // the gallery fades in over the editor's (opaque) curtain on the way out.
    androidx.compose.animation.AnimatedContent(
        targetState = projectId,
        transitionSpec = {
            val enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260)) +
                androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(320), initialScale = 0.985f)
            // The editor leaves under the gallery without fading itself: its
            // canvas surface cannot take alpha, and its curtain already hides it.
            val exit = if (initialState != null) androidx.compose.animation.ExitTransition.KeepUntilTransitionsFinished
            else androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200))
            (enter togetherWith exit).apply { targetContentZIndex = 1f }
        },
        label = "screen"
    ) { openId ->
        if (openId == null) {
            GalleryScreen(store = app.projectStore, onOpenProject = { projectId = it })
        } else {
            key(openId) {
                EditorHost(app, openId, onLeave = { next -> projectId = next })
            }
        }
    }
}

/**
 * One open project: the editor plus its curtain. Opening, the curtain stays up
 * until the document is loaded and the canvas has drawn, then fades away.
 * Leaving, it fades back in first, the document saves behind it, and only
 * then does the gallery (or the next project) take over.
 */
@Composable
private fun EditorHost(app: YumeApp, openId: String, onLeave: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { EditorController(app.projectStore, app.presetStore, openId) }
    LaunchedEffect(Unit) {
        // A project closed moments ago may still be writing (8K saves take a
        // while): never read it half-written.
        app.projectStore.awaitSaved(openId)
        controller.load()
    }
    DisposableEffect(Unit) {
        onDispose { controller.dispose() }
    }
    val curtain = remember { Animatable(1f) }
    val curtainContent = com.yume.app.ui.rememberCurtainContent(app.projectStore, openId)
    var showCanvas by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Let the screen fade finish before the canvas surface exists.
        delay(280)
        controller.session.first { it != null }
        val before = com.yume.app.engine.CanvasGlass.version.value
        showCanvas = true
        // The renderer publishes a glass frame as soon as it has drawn.
        withTimeoutOrNull(1500) {
            com.yume.app.engine.CanvasGlass.version.first { it != before }
        }
        delay(60)
        if (!leaving) curtain.animateTo(0f, androidx.compose.animation.core.tween(340))
    }

    fun leave(next: String?) {
        if (leaving) return
        leaving = true
        val covered = scope.async {
            curtain.animateTo(1f, androidx.compose.animation.core.tween(200))
        }
        // Keep the editor composed until the document is on disk: the render
        // thread must still be alive when the final snapshot is taken,
        // otherwise the last property change can be lost.
        controller.saveThen(
            onWarning = { message ->
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            },
            action = {
                scope.launch {
                    covered.await()
                    onLeave(next)
                }
            }
        )
    }

    Box(Modifier.fillMaxSize()) {
        EditorScreen(
            controller = controller,
            onExit = { leave(null) },
            // A cropped / resized copy was written: save this one, open it.
            onOpenProject = { newId -> leave(newId) },
            showCanvas = showCanvas
        )
        if (curtain.value > 0.003f) {
            com.yume.app.ui.ProjectCurtain(
                content = curtainContent,
                label = if (leaving) "Saving…" else "Opening…",
                alpha = curtain.value
            )
        }
    }
}
