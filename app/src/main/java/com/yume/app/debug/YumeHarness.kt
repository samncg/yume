package com.yume.app.debug

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.RegionIterator
import android.util.Log
import com.yume.app.YumeApp
import com.yume.app.data.ProjectStore
import com.yume.app.engine.AdjustmentKind
import com.yume.app.engine.AllocTracker
import com.yume.app.engine.MemoryBudget
import com.yume.app.engine.BrushCategory
import com.yume.app.engine.BrushDab
import com.yume.app.engine.BrushPreset
import com.yume.app.engine.CompositeProfile
import com.yume.app.engine.DocumentSnapshot
import com.yume.app.engine.DrawingEngine
import com.yume.app.engine.DrawingSession
import com.yume.app.engine.FrameLogger
import com.yume.app.engine.History
import com.yume.app.engine.InputEvent
import com.yume.app.engine.Layer
import com.yume.app.engine.LayerBlend
import com.yume.app.engine.LayerSnapshotData
import com.yume.app.engine.SelectionOp
import com.yume.app.engine.SelectionUtil
import com.yume.app.engine.SolidTiles
import com.yume.app.engine.TileCache
import com.yume.app.engine.Kernels
import com.yume.app.engine.LayerBlend as LB
import com.yume.app.engine.SelectionShape
import com.yume.app.engine.SparseTileStore
import com.yume.app.engine.StrokePainter
import com.yume.app.engine.TilePool
import com.yume.app.engine.TileStoreConfig
import com.yume.app.engine.Tool
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Debug-only behaviour harness.
 *
 * Not user facing: it only runs when the app is debuggable and launched with
 * `--ez yume_harness true`. It paints a fixed sequence on a fixed canvas and
 * prints a CRC32 of every layer and of the flattened composite after each
 * phase, plus a save/reorder/reload round-trip check and a paint benchmark.
 *
 * The hashes are used as a golden reference for the tiled-store migration: the
 * numbers must not change when storage is refactored.
 */
object YumeHarness {

    private const val TAG = "YumeHarness"
    private const val W = 2048
    private const val H = 2048
    private const val BG = 0xFFFFFFFF.toInt()

    // Deterministic brushes: no grain (procedural noise is seeded from an
    // identity hash) and no jitter, so every run stamps identical pixels.
    private val hard = BrushPreset(
        id = "h-hard", name = "Harness Hard", size = 64f, opacity = 1f, flow = 1f,
        hardness = 1f, spacing = 0.05f, smoothing = 0f, grain = 0f, jitter = 0f,
        pressureSize = 0f, pressureOpacity = 0f
    )
    private val soft = BrushPreset(
        id = "h-soft", name = "Harness Soft", size = 80f, opacity = 1f, flow = 0.8f,
        hardness = 0.1f, spacing = 0.05f, smoothing = 0f, grain = 0f, jitter = 0f,
        pressureSize = 0f, pressureOpacity = 0f
    )
    private val dyn = BrushPreset(
        id = "h-dyn", name = "Harness Dynamic", size = 70f, opacity = 1f, flow = 0.9f,
        hardness = 0.7f, spacing = 0.05f, smoothing = 0f, grain = 0f, jitter = 0f,
        pressureSize = 1f, pressureOpacity = 0.5f, tiltSize = 0.5f
    )
    private val eraser = BrushPreset(
        id = "h-eraser", name = "Harness Eraser", category = BrushCategory.Eraser,
        size = 72f, opacity = 1f, flow = 1f, hardness = 0.9f, spacing = 0.05f,
        smoothing = 0f, grain = 0f, jitter = 0f, pressureSize = 0f, pressureOpacity = 0f
    )
    private val smudge = BrushPreset(
        id = "h-smudge", name = "Harness Smudge", category = BrushCategory.Smudge,
        size = 88f, opacity = 1f, flow = 0.7f, hardness = 0.5f, spacing = 0.03f,
        smoothing = 0f, grain = 0f, jitter = 0f, pressureSize = 0f, smudgeAmount = 0.5f
    )
    // Hard edges: a 1-px technical pen and a crisp marker. These are the brushes
    // where a dab decomposition that is even one pixel off shows up as a gap or
    // a doubled edge, so they get their own phases.
    private val pen = BrushPreset(
        id = "h-pen", name = "Harness Pen 1px", size = 1f, opacity = 1f, flow = 1f,
        hardness = 1f, spacing = 0.05f, smoothing = 0f, grain = 0f, jitter = 0f,
        pressureSize = 0f, pressureOpacity = 0f
    )
    private val marker = BrushPreset(
        id = "h-marker", name = "Harness Marker", size = 40f, opacity = 1f, flow = 1f,
        hardness = 1f, spacing = 0.05f, smoothing = 0f, grain = 0f, jitter = 0f,
        pressureSize = 0f, pressureOpacity = 0f
    )
    private val presets = listOf(hard, soft, dyn, eraser, smudge, pen, marker)

    fun runAsync(app: YumeApp, only: String? = null, onDone: () -> Unit) {
        cacheRoot = app.cacheDir
        Thread({
            val out = StringBuilder()
            try {
                if (only == "golden") {
                    // Debug shortcut: only the golden/v1-vs-v2 pass. Keeps an
                    // iteration over the stamping path at a few minutes.
                    out.append(scoreGolden(app))
                } else if (only == "golden-nosolids") {
                    // Same golden pass with shared solid tiles disabled, so the
                    // sparse lines (including tolerance phases) can be diffed
                    // against a solids-on run.
                    val old = TileStoreConfig.solidTiles
                    TileStoreConfig.solidTiles = false
                    try {
                        out.append(scoreGolden(app))
                    } finally {
                        TileStoreConfig.solidTiles = old
                    }
                } else if (only == "evict") {
                    out.append(scoreFuzzEvict())
                    out.append(scoreCacheFailures(app))
                    out.append(scoreGoldenEvict(app))
                    out.append(scoreCacheBig(app))
                } else if (only == "features") {
                    out.append(scoreFeatures())
                    out.append(scoreCanvasCopy(app))
                } else if (only == "accept8k") {
                    out.append(scoreAcceptance8k(app))
                } else if (only == "fill8k") {
                    out.append(scoreFill8k(app))
                } else if (only == "pancancel") {
                    out.append(scorePanCancel())
                } else if (only == "comp8k") {
                    TileStoreConfig.flatComposite = false
                    out.append("# flat fast path OFF\n").append(scoreCompositeBig(app, 8192))
                    TileStoreConfig.flatComposite = true
                    out.append("# flat fast path ON\n").append(scoreCompositeBig(app, 8192))
                } else if (only == "comp16k") {
                    out.append(scoreCompositeBig(app, 16384))
                } else if (only == "brushimport") {
                    val dir = File(app.filesDir, "brushtest")
                    for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
                        val r = com.yume.app.engine.ForeignBrushes.import(f.readBytes(), f.name, app.cacheDir)
                        out.append("brush-import ${f.name}: ${r.presets.size} preset(s) | ${r.note}")
                        for (p in r.presets) {
                            val shape = com.yume.app.engine.BrushAssets.shape(p.shapeId)
                            var cov = 0L
                            if (shape != null) {
                                val px = IntArray(shape.width * shape.height)
                                shape.getPixels(px, 0, shape.width, 0, 0, shape.width, shape.height)
                                for (c in px) cov += (c ushr 24)
                                cov /= px.size
                            }
                            out.append(" [${p.name} spacing=${"%.2f".format(p.spacing)} opacity=${"%.2f".format(p.opacity)} rot=${p.rotationMode} shape=${if (shape != null) "${shape.width}x${shape.height} meanCov=$cov" else "round"} grain=${p.grainId.isNotEmpty()}]")
                        }
                        out.append("\n")
                    }
                } else if (only == "cancel") {
                    out.append(scoreCancel(app))
                } else if (only == "hist16k") {
                    out.append(scoreHistory16k(app))
                } else if (only == "sunset" || only == "sunsetdraft") {
                    out.append(ScenePainter.paintSunset(app, save = only == "sunset"))
                } else if (only == "bleedtest") {
                    out.append(scoreBleedTest(app))
                } else if (only == "cursordab") {
                    out.append(scoreCursorDab())
                } else if (only == "importtest") {
                    out.append(scoreImportTest(app))
                } else if (only == "liftend") {
                    out.append(scoreLiftEnd(app))
                } else if (only == "smudgeperf") {
                    out.append(scoreSmudgePerf())
                } else if (only == "freeze8k") {
                    out.append(scoreFreeze8k(app))
                } else if (only == "histpersist") {
                    out.append(scoreHistPersist(app))
                } else if (only == "overlaytest") {
                    out.append(scoreOverlayTest())
                } else if (only == "bigstroke") {
                    out.append(scoreBigStroke())
                } else if (only == "bigstroke6k") {
                    repeat(2) {
                        out.append(scoreBigStroke(6144, 900f))
                        out.append(scoreBigStroke(6144, 900f, fast = true))
                    }
                } else if (only == "opacitytest") {
                    out.append(scoreOpacityTest())
                } else if (only == "motionuser") {
                    out.append(scoreMotionUser(app))
                } else if (only == "make16kempty") {
                    out.append(makeEmpty16k(app))
                } else if (only == "make8k") {
                    out.append(makeTest8k(app))
                } else if (only == "motion8k") {
                    out.append(scoreMotion8k(app))
                } else if (only == "gpu") {
                    out.append(scoreGpu())
                } else if (only == "stress16k" || only == "stress16kq") {
                    out.append(scoreStress16k(app, quick = only == "stress16kq"))
                } else if (only == "mipstale") {
                    out.append(scoreMipStale(app))
                    out.append(scoreMipStale(app, tightMb = 12))
                } else if (only == "mipstalepainted") {
                    out.append(scoreMipStale(app, painted = true))
                } else if (only == "motion16k") {
                    out.append(scoreStress16k(app, quick = true, motion = true))
                } else if (only == "motiontest") {
                    out.append(scoreMotionTest())
                } else if (only == "pickfresh") {
                    out.append(line(scorePickFresh()))
                } else if (only == "wand") {
                    out.append(line(scoreWand()))
                    out.append(line(scoreWandStores()))
                } else if (only == "journal") {
                    out.append(scoreJournal(app))
                    out.append(line(scoreHistorySpill(app)))
                } else if (only == "png") {
                    out.append(scorePngParity(app))
                    out.append(line(scoreOpsAlloc(app)))
                    out.append(line(scoreSparsePersistence(app)))
                    out.append(line(scoreExportParity()))
                    out.append(line(scoreLazyOpen(app)))
                    out.append(line(scoreSaveSafety(app)))
                } else if (only == "cachepaint") {
                    out.append(scoreCacheBig(app, quick = true))
                } else if (only == "cachebig") {
                    out.append(scoreCacheBig(app))
                } else if (only == "cachefail") {
                    out.append(scoreFuzzEvict())
                    out.append(scoreCacheFailures(app))
                } else if (only == "fuzz") {
                    out.append(scoreSparseStore())
                } else if (only == "solids") {
                    out.append(scoreSparseStore())
                    out.append(scoreSolidMemory())
                } else if (only == "shapes") {
                    out.append(scoreShapes())
                    out.append(scoreBackup(app))
                } else if (only == "dab") {
                    out.append(line(scoreDabScratch()))
                } else if (only == "paint") {
                    // Painted-stack frame tables only (the tail-latency work).
                    out.append(scoreFramesPainted())
                } else if (only == "dense") {
                    // Dense oracle only, for the determinism check: run this three
                    // times in fresh processes and diff the output.
                    for ((w, h) in listOf(2048 to 2048, 2000 to 1333)) {
                        val oldS = TileStoreConfig.useSparseLayers
                        TileStoreConfig.useSparseLayers = false
                        val g = try {
                            runGolden(w, h)
                        } finally {
                            TileStoreConfig.useSparseLayers = oldS
                        }
                        out.append("# dense ${w}x$h\n")
                        g.phases.forEach { out.append(it).append('\n') }
                        Runtime.getRuntime().gc()
                    }
                } else {
                // Perf-sensitive first, while the device is cold: the long
                // golden/fuzz work heats it up and skews frame times.
                out.append(line(scoreFrames()))
                out.append(line(scoreBench(sparse = false)))
                out.append(line(scoreBench(sparse = true)))
                out.append(scoreFramesPainted())
                out.append('\n')
                out.append(scoreFirstStroke())
                out.append('\n')
                out.append(scoreGolden(app))
                out.append(line(scoreComposite()))
                out.append(line(scoreStrokeCache()))
                out.append(line(scoreMip()))
                out.append(line(scoreOpsAlloc(app)))
                out.append(line(scoreDabScratch()))
                out.append(line(scoreHardEdge()))
                out.append(line(scoreWand()))
                out.append(line(scoreWandStores()))
                out.append(line(scoreExportParity()))
                out.append(line(scoreSaveSafety(app)))
                out.append(line(scoreLazyOpen(app)))
                out.append(line(scoreSparsePersistence(app)))
                out.append(scoreSparseStore())
                out.append(line(scoreMemory()))
                out.append(scoreSolidMemory())
                out.append(scorePngParity(app))
                out.append(scoreJournal(app))
                out.append(line(scoreHistorySpill(app)))
                out.append(scoreFeatures())
                out.append(scoreCanvasCopy(app))
                out.append(scoreFuzzEvict())
                out.append(scoreCacheFailures(app))
                out.append(scoreGoldenEvict(app))
                out.append(scoreCacheBig(app))
                out.append(scoreAcceptance8k(app))
                }
            } catch (t: Throwable) {
                Log.e(TAG, "harness failed", t)
                out.append("harness failed: ").append(t).append('\n')
            }
            val text = out.toString()
            runCatching { File(app.filesDir, "harness.txt").writeText(text) }
            for (l in text.lineSequence()) Log.i(TAG, l)
            onDone()
        }, "YumeHarness").start()
    }

    /**
     * Runs every phase on the dense store (the oracle, which also records the
     * per-phase pixels) and on sparse layers, for a square canvas and one that
     * is not a multiple of the tile size.
     *
     * Sparse must match the dense oracle byte for byte: both stores stamp
     * through the same dab scratch, so a difference means a tiling bug. The
     * dense pixels are also compared against the pre-re-baseline (v1) reference
     * to quantify what the dab-scratch path changed.
     */
    private fun scoreGolden(app: YumeApp): String {
        val out = StringBuilder()
        fun run(
            w: Int,
            h: Int,
            sparse: Boolean,
            tile: Int,
            recordDir: File? = null,
            compareDir: File? = null
        ): GoldenRun {
            val oldS = TileStoreConfig.useSparseLayers
            val oldT = TileStoreConfig.tileSize
            TileStoreConfig.useSparseLayers = sparse
            TileStoreConfig.tileSize = tile
            return try {
                runGolden(w, h, recordDir, compareDir)
            } finally {
                TileStoreConfig.useSparseLayers = oldS
                TileStoreConfig.tileSize = oldT
                Runtime.getRuntime().gc()
            }
        }
        fun compare(label: String, a: List<String>, b: List<String>): String {
            var mismatches = 0
            val diffs = StringBuilder()
            val n = minOf(a.size, b.size)
            for (i in 0 until n) {
                if (a[i] != b[i]) {
                    mismatches++
                    if (mismatches <= 4) {
                        diffs.append("\n  single: ").append(a[i])
                            .append("\n  sparse: ").append(b[i])
                    }
                }
            }
            mismatches += abs(a.size - b.size)
            val verdict = if (mismatches == 0) "MATCH" else "MISMATCH"
            return "$label: $verdict (${a.size} phases, $mismatches bad)$diffs\n"
        }
        for ((w, h) in listOf(2048 to 2048, 2000 to 1333)) {
            val refDir = File(app.filesDir, "golden-ref-$w")
            refDir.deleteRecursively()
            refDir.mkdirs()

            fun opaqueLine(label: String, r: GoldenRun): String =
                "# opaque $label: " +
                    (if (r.opaqueBad == 0) "PASS"
                    else "FAIL ${r.opaqueBad} ${r.opaqueNotes}") + '\n'

            val dense = run(w, h, false, 256, recordDir = refDir)
            out.append("# golden ${w}x$h single\n")
            dense.phases.forEach { out.append(it).append('\n') }
            out.append(opaqueLine("dense ${w}x$h", dense))

            // Keep the pre-re-baseline ("v1 band") composite pixels for the 2048
            // canvas so a later build can report exactly how much the dab-scratch
            // path moved them. Recorded once, never overwritten.
            if (w == 2048) {
                val v1 = File(app.filesDir, "v1ref")
                if (!File(v1, "done").exists()) {
                    v1.deleteRecursively()
                    v1.mkdirs()
                    refDir.listFiles()?.forEach { f ->
                        if (f.name.endsWith("_c.ref")) {
                            f.copyTo(File(v1, f.name), overwrite = true)
                        }
                    }
                    File(v1, "done").writeText("v1 band")
                    out.append("# v1 reference recorded from this build\n")
                } else {
                    out.append(v1v2Report(v1, refDir, dense.phases, w, h, app))
                }
            }

            for (tile in intArrayOf(256, 64)) {
                val diffDir = File(app.filesDir, "diffs-$w-$tile")
                diffDir.deleteRecursively()
                diffDir.mkdirs()
                diffOutDir = diffDir
                val sparseRun = run(w, h, true, tile, compareDir = refDir)
                diffOutDir = null
                out.append(opaqueLine("sparse$tile ${w}x$h", sparseRun))
                out.append(
                    compare("sparse$tile-vs-dense ${w}x$h", dense.phases, sparseRun.phases)
                )
                for (d in sparseRun.diffs) {
                    if (d.diffPixels != 0L) {
                        out.append("  diff ${d.name}: maxch=${d.maxChannel} px=${d.diffPixels}")
                        d.samples.take(4).forEach { out.append(" ").append(it) }
                        out.append('\n')
                    }
                }
                for (c in sparseRun.classes) out.append("  class ").append(c).append('\n')
            }

            refDir.deleteRecursively()
        }
        return out.toString()
    }

    // ------------------------------------------------------------------ golden

    private fun runGolden(
        width: Int,
        height: Int,
        recordDir: File? = null,
        compareDir: File? = null
    ): GoldenRun {
        val sx = width / 2048f
        val sy = height / 2048f
        fun px(v: Float) = v * sx
        fun py(v: Float) = v * sy
        fun pts(vararg p: P) = p.map { P(px(it.x), py(it.y), it.pressure) }

        val engine = DrawingEngine(width, height)
        engine.backgroundColor = BG
        val session = DrawingSession("harness", "Harness", engine, History())
        identityView(session, width, height)
        session.presets = presets
        session.activePresetId = hard.id
        session.assist = 0f
        session.quickShapeEnabled = false

        val phases = ArrayList<String>()
        val diffs = ArrayList<PhaseDiff>()
        val classes = ArrayList<String>()
        var phaseIndex = 0
        var opaqueBad = 0
        val opaqueNotes = ArrayList<String>()
        fun phase(name: String) {
            engine.flush()
            TileCache.active?.let { c ->
                if (evictAllPerPhase) c.evictAll() else c.evictTo()
            }
            val sb = StringBuilder("phase=").append(name)
            engine.layers.forEachIndexed { index, layer ->
                sb.append(" L").append(index).append('=').append(hashLayer(layer))
            }
            sb.append(" C=").append(compositeHash(engine))
            phases.add(sb.toString())
            if (TileCache.active != null) {
                for (l in engine.layers) {
                    goldenLost += (l.tiles as? SparseTileStore)?.lostTiles ?: 0
                    goldenLost += (l.maskTiles as? SparseTileStore)?.lostTiles ?: 0
                }
            }

            // Debug: the per-tile opacity cache must still agree with the pixels
            // after every operation. A stale flag would let the compositor cull
            // a layer it should have drawn.
            val bad = engine.verifyOpaqueFlags(false)
            if (bad != 0) {
                opaqueBad += bad
                opaqueNotes.add("$name:$bad")
            }

            // Write only the composite reference: the layer pixels are compared
            // by hash, and one 10 MB file per phase is enough to locate a diff.
            if (recordDir != null) {
                val cb = engine.compositeBitmap()
                writeRef(File(recordDir, "p${phaseIndex}_c.ref"), cb)
                if (engine.compositeIsSparse) cb.recycle()
            }
            if (compareDir != null) {
                // The layer/mask hashes already tell us whether the pixels under
                // test are identical, so only the composite needs a pixel diff:
                // it is the product of the whole stack.
                var maxCh = 0
                var maxPx = 0L
                var samples: List<String> = emptyList()
                val cb = engine.compositeBitmap()
                val ref = readRef(File(compareDir, "p${phaseIndex}_c.ref"))
                if (ref == null) {
                    maxCh = 255
                    maxPx = Long.MAX_VALUE / 2
                } else {
                    val st = diffStats(cb, ref)
                    maxCh = st.maxChannel
                    maxPx = st.diffPixels
                    samples = st.samples
                    // Classify where the differences sit: on a tile border or in
                    // the interior. Writes an amplified PNG per tile size.
                    val dir = diffOutDir
                    if (maxPx > 0L && dir != null) {
                        classes.add(
                            "$name: t256 ${classifyDiff(cb, ref, dir, phaseIndex, 256)}" +
                                " | t64 ${classifyDiff(cb, ref, dir, phaseIndex, 64)}"
                        )
                    }
                }
                if (engine.compositeIsSparse) cb.recycle()
                diffs.add(PhaseDiff(name, maxCh, maxPx, samples))
            }
            phaseIndex++
        }

        engine.compositeAll()
        phase("00-blank")

        pick(session, hard, BrushCategory.Brush)
        session.color = 0xFFFF0000.toInt()
        session.stroke(pts(P(300f, 300f), P(500f, 360f), P(700f, 500f), P(900f, 700f)))
        phase("01-hard")

        pick(session, soft, BrushCategory.Brush)
        session.color = 0xCC22AA44.toInt()
        session.stroke(
            pts(
                P(1150f, 400f), P(1350f, 550f), P(1450f, 750f), P(1350f, 900f), P(1150f, 1000f)
            )
        )
        phase("02-soft")

        pick(session, dyn, BrushCategory.Brush)
        session.color = 0xFF2244CC.toInt()
        strokeVarying(session, sx, sy)
        phase("03-pressure-tilt")

        pick(session, smudge, BrushCategory.Smudge)
        session.stroke(
            pts(P(750f, 600f), P(1000f, 680f), P(1250f, 750f), P(1450f, 800f))
        )
        phase("04-smudge")

        pick(session, eraser, BrushCategory.Eraser)
        session.stroke(
            pts(P(500f, 400f), P(900f, 700f), P(1300f, 850f), P(1700f, 950f))
        )
        phase("05-eraser")

        engine.symmetryX = true
        engine.symmetryY = true
        engine.symmetryXPos = width / 2f
        engine.symmetryYPos = height / 2f
        val b = engine.newLayer(name = "B")
        pick(session, hard, BrushCategory.Brush)
        session.color = 0xFFCC2288.toInt()
        session.stroke(pts(P(700f, 500f), P(820f, 560f), P(900f, 700f)))
        phase("06-symmetry")

        b.alphaLock = true
        session.color = 0xFF22CCCC.toInt()
        session.stroke(pts(P(600f, 600f), P(1000f, 650f), P(1400f, 600f)))
        phase("07-alpha-lock")

        engine.setSelection(
            Region((650 * sx).toInt(), (350 * sy).toInt(), (1350 * sx).toInt(), (900 * sy).toInt())
        )
        session.color = 0xFFAA5500.toInt()
        session.stroke(pts(P(500f, 700f), P(1000f, 720f), P(1550f, 700f)))
        engine.clearSelection()
        phase("08-selection-clip")

        engine.addMask(b)
        b.editMask = true
        pick(session, hard, BrushCategory.Brush)
        session.stroke(pts(P(800f, 400f), P(820f, 600f), P(840f, 800f), P(860f, 1000f)))
        b.editMask = false
        phase("09-mask")

        val c = engine.newLayer(name = "C")
        c.clipping = true
        pick(session, hard, BrushCategory.Brush)
        session.color = 0xFF7722CC.toInt()
        session.stroke(pts(P(600f, 500f), P(1000f, 700f), P(1300f, 600f)))
        phase("10-clipping")

        engine.selectLayer(c.id)
        session.mergeDown()
        session.runCommands()
        phase("11-merge")

        engine.selectLayer(b.id)
        session.chooseTool(Tool.Transform)
        session.runCommands()
        session.flipTransform(true)
        session.runCommands()
        session.commitTransform()
        session.runCommands()
        session.chooseTool(Tool.Brush)
        session.runCommands()
        phase("12-transform-flip")

        engine.selectLayer(b.id)
        session.previewAdjustment(AdjustmentKind.Blur, 0.6f)
        session.consumePendingPreview()
        session.commitAdjustment()
        session.runCommands()
        phase("13-blur")

        session.invertColors()
        session.runCommands()
        phase("14-invert")

        repeat(3) {
            session.undo()
            session.runCommands()
        }
        phase("15-undo3")

        repeat(2) {
            session.redo()
            session.runCommands()
        }
        phase("16-redo2")

        val index = (session.history.appliedCount - 4).coerceAtLeast(0)
        session.deleteHistoryAt(index)
        session.runCommands()
        phase("17-history-delete")

        // ------------------------------------------------------ seam stress
        // Tile borders are multiples of 256 (and therefore of 64 too).
        engine.symmetryX = false
        engine.symmetryY = false
        val s = engine.newLayer(name = "S")

        pick(session, hard, BrushCategory.Brush)
        session.color = 0xFF112233.toInt()
        session.stroke(pts(P(100f, 512f), P(400f, 512f), P(700f, 512f), P(1000f, 512f)))
        phase("18-seam-h")

        session.color = 0xFF223344.toInt()
        session.stroke(pts(P(768f, 100f), P(768f, 300f), P(768f, 500f), P(768f, 700f)))
        phase("19-seam-v")

        pick(session, soft, BrushCategory.Brush)
        session.color = 0xCC445566.toInt()
        session.stroke(
            pts(P(80f, 80f), P(400f, 300f), P(800f, 600f), P(1200f, 900f), P(1600f, 1200f))
        )
        phase("20-seam-diag")

        pick(session, eraser, BrushCategory.Eraser)
        session.stroke(pts(P(200f, 520f), P(600f, 520f), P(1000f, 520f), P(1400f, 520f)))
        phase("21-seam-eraser")

        pick(session, smudge, BrushCategory.Smudge)
        session.stroke(pts(P(300f, 700f), P(700f, 760f), P(1100f, 820f), P(1500f, 880f)))
        phase("22-seam-smudge")

        engine.symmetryX = true
        engine.symmetryY = false
        engine.symmetryXPos = px(1000f)
        pick(session, hard, BrushCategory.Brush)
        session.color = 0xFFAA2288.toInt()
        session.stroke(pts(P(700f, 400f), P(800f, 500f), P(900f, 600f)))
        engine.symmetryX = false
        phase("23-seam-symmetry")

        session.stroke(
            listOf(
                P(-200f, py(300f)),
                P(px(200f), py(600f)),
                P(px(900f), py(900f)),
                P(width + 300f, py(1100f))
            )
        )
        phase("24-seam-outside")

        s.alphaLock = true
        session.stroke(pts(P(600f, 500f), P(800f, 512f), P(1000f, 600f)))
        s.alphaLock = false
        phase("25-seam-alpha-lock")

        engine.setSelection(Region(512, 256, 1024, 768))
        session.stroke(pts(P(400f, 400f), P(800f, 512f), P(1200f, 600f)))
        engine.clearSelection()
        phase("26-seam-selection")

        engine.addMask(s)
        s.editMask = true
        pick(session, hard, BrushCategory.Brush)
        session.stroke(pts(P(512f, 700f), P(700f, 720f), P(900f, 740f)))
        s.editMask = false
        phase("27-seam-mask")

        repeat(3) {
            session.undo()
            session.runCommands()
        }
        phase("28-seam-undo3")

        repeat(2) {
            session.redo()
            session.runCommands()
        }
        phase("29-seam-redo2")

        val seamIndex = (session.history.appliedCount - 3).coerceAtLeast(0)
        session.deleteHistoryAt(seamIndex)
        session.runCommands()
        phase("30-seam-delete")

        // ------------------------------------------------ compositor stress
        // Blend modes, opacity, groups, clipping, masks, hidden layers and
        // content exactly on tile borders, then undo/redo/delete on top.
        val c0 = engine.newLayer("C0")
        val c1 = engine.newLayer("C1")
        val c2 = engine.newLayer("C2")
        val c3 = engine.newLayer("C3")

        fun fillBorder(layer: Layer, color: Int, offset: Int) {
            layer.tiles.fillRect(Rect(0, 0, width, 256), color)
            layer.tiles.fillRect(Rect(256, 256, 512, height), color)
            layer.tiles.fillRect(Rect(offset, 512, offset + 64, 576), color)
            engine.markDirtyAll()
        }
        fillBorder(c0, 0xFF3366CC.toInt(), 0)
        fillBorder(c1, 0xFFCC3366.toInt(), 64)
        fillBorder(c2, 0x8033CC66.toInt(), 128)
        fillBorder(c3, 0xFF666633.toInt(), 192)

        c1.blend = LayerBlend.Multiply
        c2.blend = LayerBlend.Screen
        c3.blend = LayerBlend.ColorDodge
        engine.markDirtyAll()
        phase("31-comp-blend")

        c0.opacity = 0.3f
        c1.opacity = 0.55f
        c2.opacity = 0.7f
        c3.opacity = 0.85f
        engine.markDirtyAll()
        phase("32-comp-opacity")

        val cg = engine.newGroup("CG")
        cg.opacity = 0.6f
        cg.blend = LayerBlend.Multiply
        c1.groupId = cg.id
        c2.groupId = cg.id
        engine.markDirtyAll()
        phase("33-comp-group")

        // A second adjacent group (groups are one level deep in this engine).
        val cg2 = engine.newGroup("CG2")
        cg2.opacity = 0.45f
        cg2.blend = LayerBlend.Screen
        c3.groupId = cg2.id
        engine.markDirtyAll()
        phase("34-comp-group2")

        // Back out of the group so it clips the group buffer below it.
        c3.groupId = 0L
        c3.clipping = true
        engine.markDirtyAll()
        phase("35-comp-clip")

        engine.addMask(c0)
        c0.maskTiles!!.fillRect(Rect(128, 128, width / 2, height / 2), 0xFF000000.toInt())
        engine.addMask(c1)
        c1.maskTiles!!.fillRect(Rect(width / 3, 0, width / 3 + 300, height), 0xFF000000.toInt())
        c3.visible = false
        engine.markDirtyAll()
        phase("36-comp-mask-hidden")

        c3.visible = true
        c3.clipping = false
        engine.markDirtyAll()
        engine.selectLayer(c3.id)
        pick(session, hard, BrushCategory.Brush)
        session.color = 0xFF112233.toInt()
        session.stroke(pts(P(100f, 100f), P(900f, 700f), P(1500f, 1100f)))
        phase("37-comp-paint")

        repeat(3) {
            session.undo()
            session.runCommands()
        }
        phase("38-comp-undo3")

        repeat(2) {
            session.redo()
            session.runCommands()
        }
        phase("39-comp-redo2")

        val compIndex = (session.history.appliedCount - 3).coerceAtLeast(0)
        session.deleteHistoryAt(compIndex)
        session.runCommands()
        phase("40-comp-delete")

        // ------------------------------------------------- one-shot operations
        // Transforms, adjustments, merges, duplicates, fill/clear and an import,
        // then undo/redo/delete across all of it.
        fun beginTransformTool() {
            session.chooseTool(Tool.Brush)
            session.runCommands()
            session.chooseTool(Tool.Transform)
            session.runCommands()
        }

        fun dragBy(dx: Float, dy: Float) {
            val cx = width / 2f
            val cy = height / 2f
            session.pushInput(InputEvent.Down(0, cx, cy, 1f, 0f, true, false))
            session.runInput()
            session.pushInput(
                InputEvent.Move(0, floatArrayOf(cx + dx), floatArrayOf(cy + dy), floatArrayOf(1f), 0f)
            )
            session.runInput()
            session.pushInput(InputEvent.Up(0))
            session.runInput()
        }

        fun finishTransform() {
            session.commitTransform()
            session.runCommands()
        }

        // 41: move a whole layer across tile borders and partly off-canvas.
        beginTransformTool()
        dragBy(px(233f), py(157f))
        finishTransform()
        phase("41-ops-transform-move")

        // 42: flip both axes.
        beginTransformTool()
        session.flipTransform(true)
        session.runCommands()
        session.flipTransform(false)
        session.runCommands()
        finishTransform()
        phase("42-ops-transform-flip")

        // 43: scale and rotate (filtered resample).
        beginTransformTool()
        session.pushInput(InputEvent.Gesture(width * 0.5f, height * 0.5f, 1.25f, 9f, 0f, 0f))
        session.runInput()
        finishTransform()
        phase("43-ops-transform-scale-rot")

        // 44: move a rectangular selection.
        engine.applySelection(
            Region(
                px(300f).toInt(), py(300f).toInt(),
                px(900f).toInt(), py(800f).toInt()
            ),
            SelectionOp.Replace
        )
        beginTransformTool()
        dragBy(px(150f), py(-90f))
        finishTransform()
        engine.setSelection(null)
        phase("44-ops-transform-selection")

        fun adjust(kind: AdjustmentKind, amount: Float) {
            session.previewAdjustment(kind, amount)
            session.consumePendingPreview()
            session.commitAdjustment()
            session.runCommands()
        }

        adjust(AdjustmentKind.Hue, 40f)
        phase("45-ops-adjust-hue")
        adjust(AdjustmentKind.Blur, 0.35f)
        phase("46-ops-adjust-blur")
        adjust(AdjustmentKind.Noise, 0.6f)
        phase("47-ops-adjust-noise")
        adjust(AdjustmentKind.Brightness, 0.2f)
        phase("48-ops-adjust-brightness")

        // 49: merge down with a non-Normal blend mode, content on tile borders.
        val mergeLower = engine.newLayer("ML")
        mergeLower.tiles.fillRect(
            Rect(px(64f).toInt(), py(64f).toInt(), px(700f).toInt(), py(600f).toInt()),
            0xFFAA2288.toInt()
        )
        engine.selectLayer(mergeLower.id)
        val mergeUpper = engine.newLayer("MU")
        mergeUpper.tiles.fillRect(
            Rect(px(128f).toInt(), py(200f).toInt(), px(760f).toInt(), py(900f).toInt()),
            0xFF33CC66.toInt()
        )
        mergeUpper.blend = LayerBlend.Overlay
        mergeUpper.opacity = 0.7f
        engine.selectLayer(mergeUpper.id)
        engine.markDirtyAll()
        session.mergeDown()
        session.runCommands()
        phase("49-ops-merge")

        // 50: duplicate the merged layer.
        session.duplicateLayer()
        session.runCommands()
        phase("50-ops-duplicate")

        // 51/52: fill and clear on the active layer.
        session.fillLayer(0xFF2277CC.toInt())
        session.runCommands()
        phase("51-ops-fill")
        session.clearLayer()
        session.runCommands()
        phase("52-ops-clear")

        // 53: import an image as a layer, straddling tile borders.
        run {
            val src = Bitmap.createBitmap(
                px(500f).toInt().coerceAtLeast(1), py(400f).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            val sc = Canvas(src)
            sc.drawColor(0xFF33AA55.toInt())
            val p = Paint()
            p.color = 0xFF882233.toInt()
            sc.drawRect(0f, 0f, src.width * 0.4f, src.height * 0.6f, p)
            session.addImageLayer(src, "Imported")
            session.runCommands()
            src.recycle()
        }
        phase("53-ops-import")

        // 54..56: undo, redo and delete across the one-shot operations.
        repeat(4) {
            session.undo()
            session.runCommands()
        }
        phase("54-ops-undo4")
        repeat(2) {
            session.redo()
            session.runCommands()
        }
        phase("55-ops-redo2")
        val opsIndex = (session.history.appliedCount - 2).coerceAtLeast(0)
        session.deleteHistoryAt(opsIndex)
        session.runCommands()
        phase("56-ops-delete")

        // ------------------------------------------- hard-edge sanity (2b-4)
        // A 1-px technical pen and a crisp marker, both crossing tile borders.
        // Their phases prove that the dab scratch does not introduce a gap or a
        // doubled edge on a hard edge (see scoreDabScratch for the direct
        // one-pass comparison).
        val edge = engine.newLayer("Edge")
        engine.selectLayer(edge.id)
        pick(session, pen, BrushCategory.Brush)
        session.color = 0xFF000000.toInt()
        session.stroke(pts(P(80f, 80f), P(1000f, 80f), P(1400f, 420f)))
        phase("57-edge-pen-1px")

        pick(session, marker, BrushCategory.Brush)
        session.color = 0xFF2266AA.toInt()
        session.stroke(pts(P(80f, 700f), P(700f, 703f), P(1500f, 706f)))
        phase("58-edge-marker")

        return GoldenRun(phases, diffs, classes, opaqueBad, opaqueNotes)
    }

    /**
     * Hard-edge sanity, independent of the v1 reference: a 1-px technical pen and
     * a crisp marker, each drawn as one straight horizontal stroke across the
     * 256 tile border. A gap shows up as columns with no paint; a doubled edge
     * shows up as columns taller than the brush. Dense and sparse must agree
     * exactly, and the column height must stay inside the brush's own width.
     */
    private fun scoreHardEdge(): String {
        fun run(preset: BrushPreset, sparse: Boolean, tile: Int): Pair<String, String> {
            val oldS = TileStoreConfig.useSparseLayers
            val oldT = TileStoreConfig.tileSize
            TileStoreConfig.useSparseLayers = sparse
            TileStoreConfig.tileSize = tile
            return try {
                val size = 512
                val engine = DrawingEngine(size, size)
                engine.backgroundColor = BG
                val session = DrawingSession("edge", "E", engine, History())
                identityView(session, size, size)
                session.presets = presets
                session.assist = 0f
                session.quickShapeEnabled = false
                pick(session, preset, BrushCategory.Brush)
                session.color = 0xFF000000.toInt()
                val y = 128
                val yf = y.toFloat()
                session.stroke(listOf(P(40f, yf), P(472f, yf)))
                engine.flush()
                val tiles = engine.layers[0].tiles
                var gapCols = 0
                var minH = Int.MAX_VALUE
                var maxH = 0
                var widestRun = 0
                for (x in 120 until 440) {
                    var h = 0
                    var run = 0
                    var best = 0
                    for (yy in (y - 40)..(y + 40)) {
                        if (yy < 0 || yy >= size) continue
                        if (Color.alpha(tiles.getPixel(x, yy)) != 0) {
                            h++
                            run++
                            if (run > best) best = run
                        } else {
                            run = 0
                        }
                    }
                    if (h == 0) gapCols++
                    if (h < minH) minH = h
                    if (h > maxH) maxH = h
                    if (best > widestRun) widestRun = best
                }
                hashLayer(engine.layers[0]) to
                    "gap=$gapCols height=$minH..$maxH longestRun=$widestRun"
            } finally {
                TileStoreConfig.useSparseLayers = oldS
                TileStoreConfig.tileSize = oldT
                Runtime.getRuntime().gc()
            }
        }
        val sb = StringBuilder("hard-edge:")
        var failures = 0
        for ((name, preset) in listOf("pen-1px" to pen, "marker" to marker)) {
            val (dh, ds) = run(preset, false, 256)
            val (s256, s256s) = run(preset, true, 256)
            val (s64, s64s) = run(preset, true, 64)
            val same = dh == s256 && dh == s64
            if (!same) failures++
            sb.append(' ').append(name).append('[').append(ds)
                .append(" storesEqual=").append(same).append(']')
            if (!same) {
                sb.append(" sparse256=").append(s256s).append(" sparse64=").append(s64s)
            }
        }
        sb.append(if (failures == 0) " PASS" else " FAIL")
        return sb.toString()
    }

    /**
     * The dab scratch is a two-pass decomposition of the pre-2b-4 one-pass
     * stamp: render the dab into a small surface, then blend that surface 1:1.
     * This compares both at fractional offsets, for the brushes and paints the
     * engine actually uses (brush colour, eraser DST_OUT, alpha-lock SRC_ATOP),
     * and reports the max per-channel difference and the number of differing
     * pixels. A structural regression such as a one-pixel gap or a doubled edge
     * shows up as a large max or a large count; a pure rounding difference stays
     * at 1-2 LSB on the dab's antialiased edge.
     */
    private fun scoreDabScratch(): String {
        val w = 160
        val h = 160

        fun base(): Bitmap {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.drawColor(0xFF403020.toInt())
            val p = Paint()
            p.color = 0xFF80C040.toInt()
            c.drawRect(0f, 0f, w * 0.55f, h * 0.45f, p)
            p.color = 0xFF2040C0.toInt()
            c.drawRect(w * 0.3f, h * 0.5f, w.toFloat(), h.toFloat(), p)
            return b
        }

        fun stats(a: Bitmap, b: Bitmap): Pair<Int, Long> {
            val n = w * h
            val pa = IntArray(n)
            val pb = IntArray(n)
            a.getPixels(pa, 0, w, 0, 0, w, h)
            b.getPixels(pb, 0, w, 0, 0, w, h)
            var maxCh = 0
            var px = 0L
            for (i in 0 until n) {
                val x = pa[i]
                val y = pb[i]
                if (x == y) continue
                px++
                var d = abs(((x ushr 24) and 0xFF) - ((y ushr 24) and 0xFF))
                var e = abs(((x ushr 16) and 0xFF) - ((y ushr 16) and 0xFF))
                if (e > d) d = e
                e = abs(((x ushr 8) and 0xFF) - ((y ushr 8) and 0xFF))
                if (e > d) d = e
                e = abs((x and 0xFF) - (y and 0xFF))
                if (e > d) d = e
                if (d > maxCh) maxCh = d
            }
            return maxCh to px
        }

        fun onePass(dab: Bitmap, dst: RectF, paint: Paint): Bitmap {
            val b = base()
            Canvas(b).drawBitmap(dab, null, dst, paint)
            return b
        }

        fun twoPass(dab: Bitmap, dst: RectF, paint: Paint): Bitmap {
            val px = Rect(
                floor(dst.left).toInt(), floor(dst.top).toInt(),
                ceil(dst.right).toInt(), ceil(dst.bottom).toInt()
            )
            px.inset(-1, -1)
            px.intersect(0, 0, w, h)
            val scratch = Bitmap.createBitmap(px.width(), px.height(), Bitmap.Config.ARGB_8888)
            val sc = Canvas(scratch)
            val sp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            sp.isFilterBitmap = true
            sp.blendMode = BlendMode.SRC_OVER
            sp.colorFilter = paint.colorFilter
            sp.alpha = paint.alpha
            val s = sc.save()
            sc.translate(-px.left.toFloat(), -px.top.toFloat())
            sc.drawBitmap(dab, null, dst, sp)
            sc.restoreToCount(s)

            val b = base()
            val bp = Paint()
            bp.blendMode = paint.blendMode
            bp.isFilterBitmap = false
            bp.isDither = paint.isDither
            Canvas(b).drawBitmap(scratch, Rect(0, 0, px.width(), px.height()), Rect(px), bp)
            scratch.recycle()
            return b
        }

        val cases = listOf(
            Triple("hard", hard, BlendMode.SRC_OVER),
            Triple("soft", soft, BlendMode.SRC_OVER),
            Triple("pen1", pen, BlendMode.SRC_OVER),
            Triple("marker", marker, BlendMode.SRC_OVER),
            Triple("eraser", eraser, BlendMode.DST_OUT),
            Triple("alpha-lock", hard, BlendMode.SRC_ATOP)
        )
        val offsets = listOf(20.37f to 30.61f, 64f to 64f, 41.9f to 22.5f)

        val sb = StringBuilder("dab-scratch:")
        var worstCh = 0
        var worstPx = 0L
        var fail = false
        for ((name, preset, blend) in cases) {
            val dab = BrushDab.dab(preset)
            val colour = 0xFF2266AA.toInt()
            val filter = if (blend == BlendMode.DST_OUT) null else
                PorterDuffColorFilter(colour, PorterDuff.Mode.SRC_IN)
            var caseCh = 0
            var casePx = 0L
            for ((ox, oy) in offsets) {
                val size = preset.size.coerceAtLeast(1.2f)
                val dst = RectF(ox, oy, ox + size, oy + size)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
                paint.isFilterBitmap = true
                paint.isDither = true
                paint.blendMode = blend
                paint.colorFilter = filter
                paint.alpha = 200
                val a = onePass(dab, dst, paint)
                val b = twoPass(dab, dst, paint)
                val (maxCh, px) = stats(a, b)
                a.recycle()
                b.recycle()
                if (maxCh > caseCh) caseCh = maxCh
                casePx += px
                if (maxCh > worstCh || (maxCh == worstCh && px > worstPx)) {
                    worstCh = maxCh
                    worstPx = px
                }
                if (maxCh > 5) fail = true
            }
            sb.append(' ').append(name).append('=').append(caseCh)
                .append('/').append(casePx)
        }
        sb.append(" worst=").append(worstCh).append('/').append(worstPx)
            .append(if (fail) " FAIL (>5)" else " PASS")
            .append(" (one-pass vs two-pass dab, 3 fractional offsets)")
        return sb.toString()
    }

    private fun identityView(session: DrawingSession, width: Int, height: Int) {
        // screen == document: scale 1, rotation 0, translation to the canvas centre.
        session.view.setViewport(width, height)
        session.view.pan(width / 2f, height / 2f)
    }

    private fun pick(session: DrawingSession, preset: BrushPreset, category: BrushCategory) {
        session.category = category
        session.activePresetId = preset.id
        session.tool = when (category) {
            BrushCategory.Brush -> Tool.Brush
            BrushCategory.Eraser -> Tool.Eraser
            BrushCategory.Smudge -> Tool.Smudge
        }
    }

    private data class P(val x: Float, val y: Float, val pressure: Float = 1f)

    private fun DrawingSession.stroke(points: List<P>, tilt: Float = 0f) {
        val first = points.first()
        pushInput(InputEvent.Down(0, first.x, first.y, first.pressure, tilt, true, false))
        runInput()
        for (i in 1 until points.size) {
            val p = points[i]
            pushInput(
                InputEvent.Move(
                    0, floatArrayOf(p.x), floatArrayOf(p.y), floatArrayOf(p.pressure), tilt
                )
            )
            runInput()
        }
        pushInput(InputEvent.Up(0))
        runInput()
    }

    private fun strokeVarying(session: DrawingSession, sx: Float = 1f, sy: Float = 1f) {
        val points = listOf(
            P(300f * sx, 1200f * sy, 0.1f), P(420f * sx, 1300f * sy, 0.3f),
            P(560f * sx, 1400f * sy, 0.6f), P(700f * sx, 1480f * sy, 0.9f),
            P(840f * sx, 1550f * sy, 1f), P(980f * sx, 1500f * sy, 0.7f),
            P(1080f * sx, 1400f * sy, 0.4f), P(1160f * sx, 1300f * sy, 0.2f)
        )
        val tilts = floatArrayOf(0f, 0.15f, 0.3f, 0.45f, 0.6f, 0.75f, 0.9f, 1f)
        val first = points.first()
        session.pushInput(
            InputEvent.Down(0, first.x, first.y, first.pressure, tilts[0], true, false)
        )
        session.runInput()
        for (i in 1 until points.size) {
            val p = points[i]
            session.pushInput(
                InputEvent.Move(
                    0, floatArrayOf(p.x), floatArrayOf(p.y), floatArrayOf(p.pressure), tilts[i]
                )
            )
            session.runInput()
        }
        session.pushInput(InputEvent.Up(0))
        session.runInput()
    }

    // -------------------------------------------------------------- save safety

    private fun scoreSaveSafety(app: YumeApp): String {
        val store = app.projectStore
        val size = 256
        val project = store.createProject("harness-save", size, size, BG)
        return try {
            fun pattern(seed: Int): Bitmap {
                val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(b)
                canvas.drawColor(0xFF000000.toInt() or (seed * 0x010101))
                val p = Paint()
                p.color = 0xFFFFFFFF.toInt()
                val x = (seed * 20).toFloat()
                canvas.drawRect(x, 8f, x + 24f, (size - 8).toFloat(), p)
                return b
            }

            val a = pattern(1)
            val b = pattern(2)
            val c = pattern(3)
            val snap1 = DocumentSnapshot(
                title = "harness-save",
                width = size,
                height = size,
                background = BG,
                activeIndex = 1,
                layers = listOf(
                    layerSnapshot("A", a, false, 1L),
                    layerSnapshot("B", b, true, 2L),
                    layerSnapshot("C", c, false, 3L)
                )
            )
            store.saveProject(project.id, snap1)

            // Reload: A and C come back swapped out (file only), B resident.
            val loaded = store.loadProject(project.id)
                ?: return "save-safety: FAIL (could not reload first save)"
            val byName = loaded.layers.associateBy { it.name }
            // Reorder to [C, B, A] while A and C are non-resident.
            val snap2 = DocumentSnapshot(
                title = "harness-save",
                width = size,
                height = size,
                background = BG,
                activeIndex = 1,
                layers = listOf(
                    byName.getValue("C").copy(name = "C"),
                    byName.getValue("B").copy(name = "B"),
                    byName.getValue("A").copy(name = "A")
                )
            )
            store.saveProject(project.id, snap2)

            val reopened = store.loadProject(project.id)?.layers
                ?: return "save-safety: FAIL (could not reopen second save)"
            fun pixels(d: LayerSnapshotData): Bitmap =
                d.bitmap ?: BitmapFactory.decodeFile(d.file!!.absolutePath)

            val ok0 = hashBitmap(pixels(reopened[0])) == hashBitmap(c)
            val ok1 = hashBitmap(pixels(reopened[1])) == hashBitmap(b)
            val ok2 = hashBitmap(pixels(reopened[2])) == hashBitmap(a)
            if (ok0 && ok1 && ok2) {
                "save-safety: PASS"
            } else {
                "save-safety: FAIL order=$ok0,$ok1,$ok2"
            }
        } catch (t: Throwable) {
            "save-safety: FAIL ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            runCatching { store.deleteProject(project.id) }
        }
    }

    private fun layerSnapshot(
        name: String,
        bitmap: Bitmap,
        visible: Boolean,
        id: Long
    ): LayerSnapshotData = LayerSnapshotData(
        name = name,
        bitmap = bitmap,
        opacity = 1f,
        blend = LayerBlend.Normal,
        visible = visible,
        alphaLock = false,
        id = id,
        revision = 1
    )

    // -------------------------------------------------------------- lazy open

    /**
     * A hidden layer must come back from disk without being decoded, and must
     * decode to exactly the saved pixels when something finally needs it.
     */
    private fun scoreLazyOpen(app: YumeApp): String {
        // This is the dense-store loading contract (a visible layer decodes on
        // load, a hidden one stays on disk). Since Stage 3c sparse is the
        // default and loads every layer lazily by design (covered by
        // sparse-persist), so pin the store this check was written for.
        val oldSparse = TileStoreConfig.useSparseLayers
        TileStoreConfig.useSparseLayers = false
        try {
            return scoreLazyOpenDense(app)
        } finally {
            TileStoreConfig.useSparseLayers = oldSparse
        }
    }

    private fun scoreLazyOpenDense(app: YumeApp): String {
        val store = app.projectStore
        val size = 256
        val project = store.createProject("harness-lazy", size, size, BG)
        return try {
            fun pattern(seed: Int): Bitmap {
                val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(b)
                canvas.drawColor(0xFF000000.toInt() or (seed * 0x010101))
                val p = Paint()
                p.color = 0xFFFFFFFF.toInt()
                val x = (seed * 17).toFloat()
                canvas.drawRect(x, 8f, x + 28f, (size - 8).toFloat(), p)
                return b
            }

            val hidden = pattern(9)
            val visible = pattern(7)
            val snap = DocumentSnapshot(
                title = "harness-lazy",
                width = size,
                height = size,
                background = BG,
                activeIndex = 1,
                layers = listOf(
                    layerSnapshot("Hidden", hidden, false, 1L),
                    layerSnapshot("Visible", visible, true, 2L)
                )
            )
            store.saveProject(project.id, snap)
            val loaded = store.loadProject(project.id)
                ?: return "lazy-open: FAIL (could not reload)"
            val savedHidden = loaded.layers.first { it.name == "Hidden" }
            val savedVisible = loaded.layers.first { it.name == "Visible" }
            if (savedHidden.bitmap != null || savedHidden.file == null) {
                return "lazy-open: FAIL (hidden layer decoded on load)"
            }
            if (savedVisible.bitmap == null) {
                return "lazy-open: FAIL (visible layer not decoded)"
            }

            // Build layers exactly as EditorController.load does.
            val engine = DrawingEngine(size, size)
            for (data in loaded.layers) {
                val layer = engine.newLayer(
                    name = data.name,
                    bitmap = data.bitmap,
                    backingFile = data.file
                )
                layer.adoptMask(data.mask, data.maskFile)
            }
            val layer = engine.layers.first { it.name == "Hidden" }
            if (layer.tiles.isResident) return "lazy-open: FAIL (store started resident)"
            if (layer.tiles.sharedBitmap() != null) return "lazy-open: FAIL (shared bitmap)"
            layer.tiles.ensureResident()
            if (!layer.tiles.isResident) return "lazy-open: FAIL (decode failed)"
            return if (hashBitmap(layer.tiles.copyAll()) == hashBitmap(hidden)) {
                "lazy-open: PASS"
            } else {
                "lazy-open: FAIL (decoded pixels differ)"
            }
        } catch (t: Throwable) {
            "lazy-open: FAIL ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            runCatching { store.deleteProject(project.id) }
        }
    }

    // -------------------------------------------------------------- sparse fuzz

    /**
     * Randomized differential test: [SparseTileStore] against a plain full-size
     * bitmap. Every operation is mirrored onto a reference bitmap through the
     * same public API, and the two are compared after every [every] steps.
     *
     * Covers tile sizes 16/64/256, a canvas that is not a multiple of the tile
     * size (2000x1333) and a tiny odd canvas (37x53), fixed seeds.
     */
    private fun scoreSparseStore(): String {
        data class Combo(
            val w: Int,
            val h: Int,
            val tile: Int,
            val ops: Int,
            val every: Int,
            val seed: Long,
            val default: Int = 0,
            val solids: Boolean = true
        )
        val white = 0xFFFFFFFF.toInt()
        val combos = listOf(
            // A debug-style run that compares after every single operation, so a
            // divergence is attributed to the exact op that caused it.
            Combo(2000, 1333, 256, 60, 1, 2013L),
            Combo(37, 53, 16, 80, 1, 3011L),
            // Large canvases: 2000 operations and two seeds per tile size.
            Combo(2048, 2048, 16, 300, 100, 1011L),
            Combo(2048, 2048, 16, 2000, 250, 1021L),
            Combo(2048, 2048, 16, 2000, 250, 1022L),
            Combo(2048, 2048, 64, 300, 100, 1012L),
            Combo(2048, 2048, 64, 2000, 250, 1031L),
            Combo(2048, 2048, 64, 2000, 250, 1032L),
            Combo(2048, 2048, 256, 2000, 250, 1041L),
            Combo(2048, 2048, 256, 2000, 250, 1042L),
            Combo(2000, 1333, 16, 400, 100, 2011L),
            Combo(2000, 1333, 64, 400, 100, 2012L),
            Combo(2000, 1333, 256, 400, 100, 2013L),
            Combo(37, 53, 16, 2000, 250, 3011L),
            Combo(37, 53, 64, 2000, 250, 3012L),
            Combo(37, 53, 256, 2000, 250, 3013L),
            // Default-pixel (mask) stores: a missing tile reads as opaque white.
            Combo(1000, 700, 16, 600, 100, 4011L, white),
            Combo(1000, 700, 64, 600, 100, 4012L, white),
            Combo(1000, 700, 256, 600, 100, 4013L, white),
            Combo(2000, 1333, 64, 2000, 250, 4021L, white),
            Combo(2000, 1333, 256, 2000, 250, 4022L, white),
            // Odd canvases at every tile size, compared after every op.
            Combo(1001, 777, 16, 300, 1, 5011L),
            Combo(1001, 777, 64, 300, 1, 5012L),
            Combo(1001, 777, 256, 300, 1, 5013L),
            Combo(301, 999, 64, 300, 1, 5021L, white),
            // Control: the same op mix with solid tiles disabled.
            Combo(2000, 1333, 256, 60, 1, 2013L, solids = false),
            Combo(1001, 777, 64, 300, 1, 5012L, solids = false),
            Combo(1000, 700, 64, 600, 100, 4012L, white, solids = false)
        )
        val out = StringBuilder()
        out.append(diagSurface())
        out.append(diagDab())
        out.append(diagPaint())
        out.append(diagStroke())
        for (c in combos) {
            val res = try {
                fuzzStore(c.w, c.h, c.tile, c.ops, c.every, c.seed, c.default, c.solids)
            } catch (t: Throwable) {
                "FAIL ${t.javaClass.simpleName}: ${t.message}"
            }
            out.append("sparse-fuzz ").append(c.w).append('x').append(c.h)
                .append(" tile=").append(c.tile)
                .append(if (c.default != 0) " mask" else "")
                .append(if (c.solids) " solids" else " nosolids")
                .append(' ').append(res).append('\n')
            Runtime.getRuntime().gc()
        }
        return out.toString()
    }

    /**
     * Isolated probe of tiled stamping. A scaled, filtered blit is NOT
     * pixel-identical when it is clipped or drawn into a differently sized
     * surface, because Skia selects a different SIMD blitter for a different
     * row stride. Rendering it into a canvas-width band keeps the stride of a
     * full-canvas draw, and compositing that band with 1:1 blits is exact. That
     * is the technique Stage 2b must use for dabs that cross tile borders.
     */
    private fun diagDab(): String {
        val out = StringBuilder()
        val dab = makeDab()
        val w = 600
        val h = 600
        val tile = 256
        val cx = 256f
        val cy = 256f
        val half = 90f
        val dst = RectF(cx - half, cy - half, cx + half, cy + half)
        val p = Paint().apply { isFilterBitmap = true }
        val x0 = (cx - half).toInt().coerceIn(0, w - 1) / tile
        val x1 = (cx + half).toInt().coerceIn(0, w - 1) / tile
        val y0 = (cy - half).toInt().coerceIn(0, h - 1) / tile
        val y1 = (cy + half).toInt().coerceIn(0, h - 1) / tile
        val txCount = (w + tile - 1) / tile
        val tyCount = (h + tile - 1) / tile

        val ref = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(ref).drawBitmap(dab, null, dst, p)

        fun state(b: Bitmap): String = if (b.sameAs(ref)) "identical" else diffNote(b, ref)

        val region = Rect(
            (cx - half).toInt() - 1, (cy - half).toInt() - 1,
            (cx + half).toInt() + 2, (cy + half).toInt() + 2
        )
        region.intersect(0, 0, w, h)

        // 1: clip the scaled blit per tile.
        val tiledClip = run {
            val store = SparseTileStore(w, h, tile)
            for (ty in y0..min(y1, tyCount - 1)) {
                for (tx in x0..min(x1, txCount - 1)) {
                    store.editTile(tx, ty) { it.drawBitmap(dab, null, dst, p) }
                }
            }
            val all = store.copyAll()
            val s = state(all)
            all.recycle()
            s
        }

        // 2: read-modify-write the dab's bounding region (tile-sized scratch).
        val rmw = run {
            val store = SparseTileStore(w, h, tile)
            val scratch = store.readRect(region)
            Canvas(scratch).drawBitmap(
                dab, null,
                RectF(
                    dst.left - region.left, dst.top - region.top,
                    dst.right - region.left, dst.bottom - region.top
                ),
                p
            )
            store.writeRect(region, scratch)
            scratch.recycle()
            val all = store.copyAll()
            val s = state(all)
            all.recycle()
            s
        }

        // 3: canvas-width band, then exact 1:1 composite into the tiles.
        val bandState = run {
            val store = SparseTileStore(w, h, tile)
            val bmp = Bitmap.createBitmap(w, region.height(), Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawBitmap(
                dab, null,
                RectF(dst.left, dst.top - region.top, dst.right, dst.bottom - region.top),
                p
            )
            val scratch = Bitmap.createBitmap(
                region.width(), region.height(), Bitmap.Config.ARGB_8888
            )
            Canvas(scratch).drawBitmap(
                bmp,
                Rect(region.left, 0, region.right, region.height()),
                Rect(0, 0, region.width(), region.height()),
                Paint().apply { blendMode = BlendMode.SRC; isFilterBitmap = false }
            )
            store.writeRect(region, scratch)
            scratch.recycle()
            bmp.recycle()
            val all = store.copyAll()
            val s = state(all)
            all.recycle()
            s
        }

        ref.recycle()
        dab.recycle()
        out.append("dab-tiling tiled-clip=").append(tiledClip)
            .append(" rmw=").append(rmw)
            .append(" band=").append(bandState)
            .append(" (band is the required technique)\n")
        return out.toString()
    }

    /** A soft, pre-rendered dab, like BrushDab generates for the real engine. */
    private fun makeDab(): Bitmap {
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = size / 2f
        p.shader = android.graphics.RadialGradient(
            r, r, r - 1f,
            intArrayOf(
                0xFF3366CC.toInt(),
                0xCC3366CC.toInt(),
                0x553366CC,
                0x003366CC
            ),
            floatArrayOf(0f, 0.45f, 0.8f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        c.drawCircle(r, r, r - 1f, p)
        return bmp
    }

    /**
     * Pure-Skia probe: is a filtered, scaled bitmap blit independent of the
     * destination surface size (stride) and of a clip? Prints strides too.
     */
    private fun diagSurface(): String {
        val dab = makeDab()
        val w = 600
        val h = 600
        val cx = 256f
        val cy = 256f
        val half = 90f
        val dst = RectF(cx - half, cy - half, cx + half, cy + half)
        val fp = Paint().apply { isFilterBitmap = true }

        val big = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(big).drawBitmap(dab, null, dst, fp)

        val bigClip = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c2 = Canvas(bigClip)
        val save = c2.save()
        c2.clipRect(165, 165, 348, 348)
        c2.drawBitmap(dab, null, dst, fp)
        c2.restoreToCount(save)

        val small = Bitmap.createBitmap(183, 183, Bitmap.Config.ARGB_8888)
        Canvas(small).drawBitmap(dab, null, RectF(1f, 1f, 181f, 181f), fp)
        val smallInBig = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(smallInBig).drawBitmap(
            small, 165f, 165f, Paint().apply { blendMode = BlendMode.SRC }
        )

        val out = StringBuilder()
        out.append("dab-raster clip-invariant=").append(big.sameAs(bigClip))
            .append(" stride-invariant=").append(big.sameAs(smallInBig))
            .append(" (strides ").append(big.rowBytes).append('/').append(small.rowBytes)
            .append(")\n")
        big.recycle()
        bigClip.recycle()
        small.recycle()
        smallInBig.recycle()
        dab.recycle()
        return out.toString()
    }

    /**
     * Focused probe: one dab with a colour filter and alpha, drawn directly on
     * a full canvas versus rendered into a canvas-width band and blitted 1:1.
     * Reports the differing pixel count so a stage-split problem is obvious.
     */
    private fun diagPaint(): String {
        val dab = makeDab()
        val w = 600
        val h = 600
        val dst = RectF(166f, 166f, 346f, 346f)
        val cf = android.graphics.PorterDuffColorFilter(
            0xFF3366CC.toInt(), android.graphics.PorterDuff.Mode.SRC_IN
        )
        val dense = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(dense).drawBitmap(
            dab, null, dst,
            Paint(Paint.FILTER_BITMAP_FLAG).apply {
                alpha = 200
                blendMode = BlendMode.SRC_OVER
                colorFilter = cf
            }
        )
        val band = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(band).drawBitmap(
            dab, null, dst,
            Paint(Paint.FILTER_BITMAP_FLAG).apply {
                alpha = 200
                blendMode = BlendMode.SRC
                colorFilter = cf
            }
        )
        val store = SparseTileStore(w, h, 256)
        val blit = Paint().apply {
            alpha = 255
            isFilterBitmap = false
            blendMode = BlendMode.SRC_OVER
        }
        val txCount = (w + 255) / 256
        val tyCount = (h + 255) / 256
        for (ty in 0 until tyCount) {
            for (tx in 0 until txCount) {
                store.editTile(tx, ty) {
                    it.drawBitmap(band, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), blit)
                }
            }
        }
        val got = store.copyAll()
        val note = diffNote(got, dense)
        got.recycle()
        dense.recycle()
        band.recycle()
        dab.recycle()
        return "diag-paint $note\n"
    }

    /** Real-painter probe: the first harness stroke on each store. */
    private fun diagStroke(): String {
        fun run(sparse: Boolean, singleDab: Boolean): Bitmap {
            val oldS = TileStoreConfig.useSparseLayers
            val oldT = TileStoreConfig.tileSize
            TileStoreConfig.useSparseLayers = sparse
            TileStoreConfig.tileSize = 256
            try {
                val engine = DrawingEngine(2048, 2048)
                engine.backgroundColor = BG
                val session = DrawingSession("d", "D", engine, History())
                identityView(session, 2048, 2048)
                session.presets = presets
                session.activePresetId = hard.id
                session.assist = 0f
                session.quickShapeEnabled = false
                session.category = BrushCategory.Brush
                session.color = 0xFFFF0000.toInt()
                if (singleDab) {
                    session.stroke(listOf(P(300f, 300f)))
                } else {
                    session.stroke(
                        listOf(P(300f, 300f), P(500f, 360f), P(700f, 500f), P(900f, 700f))
                    )
                }
                engine.flush()
                return engine.layers[0].tiles.copyAll()
            } finally {
                TileStoreConfig.useSparseLayers = oldS
                TileStoreConfig.tileSize = oldT
            }
        }
        val dSingle = run(false, true)
        val sSingle = run(true, true)
        val dFull = run(false, false)
        val sFull = run(true, false)
        val out = StringBuilder()
        out.append("diag-stroke-1dab ").append(diffNote(sSingle, dSingle)).append('\n')
        out.append("diag-stroke-full ").append(diffNote(sFull, dFull))
        val ra = IntArray(sFull.width)
        val rb = IntArray(dFull.width)
        var shown = 0
        for (y in 0 until sFull.height) {
            sFull.getPixels(ra, 0, sFull.width, 0, y, sFull.width, 1)
            dFull.getPixels(rb, 0, dFull.width, 0, y, dFull.width, 1)
            for (x in 0 until sFull.width) {
                if (ra[x] != rb[x] && shown < 8) {
                    out.append(" (").append(x).append(',').append(y).append(")=")
                        .append(Integer.toHexString(ra[x])).append('/')
                        .append(Integer.toHexString(rb[x]))
                    shown++
                }
            }
        }
        out.append('\n')
        return out.toString()
    }

    private fun fuzzStore(
        w: Int,
        h: Int,
        tile: Int,
        ops: Int,
        every: Int,
        seed: Long,
        defaultPixel: Int = 0,
        solids: Boolean = true,
        evict: Boolean = false
    ): String {
        val rnd = Random(seed)
        val fuzzCache = if (evict) {
            TileCache(File(cacheRoot ?: File("/data/local/tmp"), "tilecache-fuzz-$seed"), 0L)
        } else {
            null
        }
        val store = SparseTileStore(
            w, h, tile, defaultPixel = defaultPixel, solidTiles = solids, cache = fuzzCache
        )
        // Colours shared with a second store, so the two hold the SAME shared
        // solid bitmaps. Writes to [store] must never show up in [twin].
        val palette = intArrayOf(
            rnd.nextInt() or 0xFF000000.toInt(),
            (rnd.nextInt() and 0x00FFFFFF) or 0x7F000000,
            0x80FF0000.toInt()
        )
        val twin = SparseTileStore(w, h, tile, defaultPixel = defaultPixel, solidTiles = solids)
        twin.fill(palette[0])
        twin.fillRect(Rect(0, 0, w / 2, h / 2), palette[1])
        twin.fillRect(Rect(w / 3, h / 3, w, h), palette[2])
        val twinRef = twin.copyAll()
        var solidPeak = 0
        var unshareSeen = 0L
        val ref = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val refCanvas = Canvas(ref)
        if (defaultPixel != 0) refCanvas.drawColor(defaultPixel, BlendMode.SRC)
        val full = Rect(0, 0, w, h)
        val tileCountX = (w + tile - 1) / tile
        val tileCountY = (h + tile - 1) / tile
        // Blending is probed on a top-left window so a large canvas stays fast.
        val probeW = min(w, 512)
        val probeH = min(h, 512)

        val srcPaint = Paint().apply {
            blendMode = BlendMode.SRC
            isFilterBitmap = false
        }
        val blendModes = LayerBlend.entries.map { it.mode }.toTypedArray()

        var comparisons = 0
        var mismatches = 0
        var note = ""
        var currentOp = -1

        fun fail(step: Int, what: String) {
            mismatches++
            if (note.isEmpty()) note = "step=$step op=$currentOp $what"
        }

        fun randomRect(): Rect {
            val x = rnd.nextInt(w)
            val y = rnd.nextInt(h)
            val ww = 1 + rnd.nextInt(w - x)
            val hh = 1 + rnd.nextInt(h - y)
            return Rect(x, y, x + ww, y + hh)
        }

        fun randomPatch(): Bitmap {
            val pw = 1 + rnd.nextInt(min(w, 200).coerceAtLeast(1))
            val ph = 1 + rnd.nextInt(min(h, 200).coerceAtLeast(1))
            val bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            val px = IntArray(pw * ph)
            when (rnd.nextInt(3)) {
                0 -> { /* left fully transparent */ }
                1 -> {
                    val c = rnd.nextInt()
                    px.fill(c)
                }
                else -> for (i in px.indices) px[i] = rnd.nextInt()
            }
            bmp.setPixels(px, 0, pw, 0, 0, pw, ph)
            return bmp
        }

        fun compare(step: Int) {
            store.pruneEmptyTiles(full)
            var transparentTile = false
            store.forEachTileIn(full) { tx, ty, _ ->
                if (store.isTileEmpty(tx, ty)) {
                    transparentTile = true
                    fail(step, "allocated empty tile ($tx,$ty)")
                }
            }
            if (!transparentTile) {
                val bounds = store.nonEmptyBounds()
                if (!bounds.isEmpty && !full.contains(bounds)) {
                    fail(step, "nonEmptyBounds $bounds outside canvas")
                }
            }
            val all = store.copyAll()
            comparisons++
            val ok = all.sameAs(ref)
            if (!ok) fail(step, "copyAll differs: " + diffNote(all, ref))
            all.recycle()
            val staleAll = store.verifyOpaqueFlags(true)
            if (staleAll != 0) fail(step, "opaque cache (all) stale=$staleAll")
            val tw = twin.copyAll()
            if (!tw.sameAs(twinRef)) fail(step, "twin changed: " + diffNote(tw, twinRef))
            tw.recycle()
            if (twin.verifySolids() != 0) fail(step, "twin verifySolids")
        }

        fun refTile(tx: Int, ty: Int): Rect {
            val x0 = tx * tile
            val y0 = ty * tile
            return Rect(x0, y0, min(w, x0 + tile), min(h, y0 + tile))
        }

        fun refIsDefault(r: Rect): Boolean {
            val row = IntArray(r.width())
            for (yy in r.top until r.bottom) {
                ref.getPixels(row, 0, r.width(), r.left, yy, r.width(), 1)
                for (v in row) if (v != defaultPixel) return false
            }
            return true
        }

        fun refCrop(r: Rect): Bitmap {
            val want = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888)
            Canvas(want).drawBitmap(ref, r, Rect(0, 0, r.width(), r.height()), srcPaint)
            return want
        }

        fun randomProbeRect(): Rect {
            val x = rnd.nextInt(probeW)
            val y = rnd.nextInt(probeH)
            return Rect(x, y, x + 1 + rnd.nextInt(probeW - x), y + 1 + rnd.nextInt(probeH - y))
        }

        // A rect snapped to whole tiles half of the time, so fills hit full tiles.
        fun bigRect(): Rect {
            if (rnd.nextBoolean()) return randomRect()
            val tx0 = rnd.nextInt(tileCountX)
            val ty0 = rnd.nextInt(tileCountY)
            val tx1 = tx0 + 1 + rnd.nextInt(tileCountX - tx0)
            val ty1 = ty0 + 1 + rnd.nextInt(tileCountY - ty0)
            return Rect(tx0 * tile, ty0 * tile, min(w, tx1 * tile), min(h, ty1 * tile))
        }

        fun pickColour(): Int = when (rnd.nextInt(4)) {
            0 -> rnd.nextInt()
            else -> palette[rnd.nextInt(palette.size)]
        }

        for (step in 1..ops) {
            val op = rnd.nextInt(20)
            currentOp = op
            when (op) {
                14 -> {
                    // Large fill: whole tiles become shared solids directly.
                    val r = bigRect()
                    val color = pickColour()
                    store.fillRect(r, color)
                    val p = Paint().apply {
                        blendMode = BlendMode.SRC
                        this.color = color
                    }
                    refCanvas.drawRect(RectF(r), p)
                }
                15 -> {
                    // Solid detection (stroke end / op end) over a random area.
                    store.compactSolids(if (rnd.nextBoolean()) full else randomRect())
                }
                16 -> {
                    if (rnd.nextInt(3) == 0) {
                        val color = pickColour()
                        store.fill(color)
                        refCanvas.drawColor(color, BlendMode.SRC)
                    } else {
                        store.clear()
                        refCanvas.drawColor(defaultPixel, BlendMode.SRC)
                    }
                }
                17 -> {
                    // Install the twin's tile: a shared solid is referenced, not
                    // copied, so the two stores now share one bitmap.
                    val tx = rnd.nextInt(tileCountX)
                    val ty = rnd.nextInt(tileCountY)
                    val src = twin.getTile(tx, ty)
                    val tr = refTile(tx, ty)
                    if (src != null && src.width == tr.width() && src.height == tr.height()) {
                        store.putTile(tx, ty, src)
                        refCanvas.drawBitmap(twinRef, tr, tr, srcPaint)
                    }
                }
                18 -> {
                    // Every remaining public read path against the reference.
                    when (rnd.nextInt(8)) {
                        0 -> {
                            val r = randomRect()
                            val got = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888)
                            got.eraseColor(0x12345678)
                            store.readRectInto(r, got)
                            val want = refCrop(r)
                            if (!got.sameAs(want)) fail(step, "readRectInto $r")
                            got.recycle()
                            want.recycle()
                        }
                        1, 2 -> {
                            val r = randomProbeRect()
                            val p = Paint().apply {
                                blendMode = blendModes[rnd.nextInt(blendModes.size)]
                                alpha = rnd.nextInt(256)
                                isFilterBitmap = false
                            }
                            val got = Bitmap.createBitmap(probeW, probeH, Bitmap.Config.ARGB_8888)
                            val gc = Canvas(got)
                            val pre = rnd.nextBoolean()
                            if (pre) {
                                gc.clipRect(r)
                                store.drawIntoRectPreClipped(gc, r, p)
                            } else {
                                store.drawIntoRect(gc, r, p)
                            }
                            val want = Bitmap.createBitmap(probeW, probeH, Bitmap.Config.ARGB_8888)
                            val wc = Canvas(want)
                            wc.clipRect(r)
                            wc.drawBitmap(ref, 0f, 0f, p)
                            if (!got.sameAs(want)) {
                                fail(step, "drawIntoRect pre=$pre $r blend=${p.blendMode}")
                            }
                            got.recycle()
                            want.recycle()
                        }
                        3 -> {
                            val tx = rnd.nextInt(tileCountX)
                            val ty = rnd.nextInt(tileCountY)
                            val tr = refTile(tx, ty)
                            val copy = store.readTileCopy(tx, ty)
                            if (copy == null) {
                                if (!refIsDefault(tr)) fail(step, "readTileCopy($tx,$ty) null")
                            } else {
                                val want = refCrop(tr)
                                if (!copy.isMutable) fail(step, "readTileCopy returned a shared bitmap")
                                if (!copy.sameAs(want)) fail(step, "readTileCopy($tx,$ty)")
                                want.recycle()
                                // History round trip: putting the copy back is a no-op.
                                store.putTile(tx, ty, copy)
                                TilePool.release(copy)
                            }
                        }
                        4 -> {
                            val tx = rnd.nextInt(tileCountX)
                            val ty = rnd.nextInt(tileCountY)
                            if (store.isTileEmpty(tx, ty) != refIsDefault(refTile(tx, ty))) {
                                fail(step, "isTileEmpty($tx,$ty)")
                            }
                        }
                        5 -> {
                            val r = randomRect()
                            if (store.isRectEmpty(r) != refIsDefault(r)) fail(step, "isRectEmpty $r")
                        }
                        6 -> {
                            val tx = rnd.nextInt(tileCountX)
                            val ty = rnd.nextInt(tileCountY)
                            val tr = refTile(tx, ty)
                            val t = store.getTile(tx, ty)
                            if (t == null) {
                                if (!refIsDefault(tr)) fail(step, "getTile($tx,$ty) null")
                            } else {
                                val want = refCrop(tr)
                                if (!t.sameAs(want)) fail(step, "getTile($tx,$ty) pixels")
                                want.recycle()
                            }
                        }
                        else -> {
                            // nonEmptyBounds must cover every non-default pixel.
                            store.pruneEmptyTiles(full)
                            val b = store.nonEmptyBounds()
                            for (ty in 0 until tileCountY) for (tx in 0 until tileCountX) {
                                val tr = refTile(tx, ty)
                                if (!refIsDefault(tr) && !b.contains(tr)) {
                                    fail(step, "nonEmptyBounds $b misses tile ($tx,$ty)")
                                }
                            }
                        }
                    }
                }
                19 -> {
                    // Write INTO a shared solid (stamp, putTileRegion or
                    // withWriteCanvas). The unshare must leave every other user
                    // of the bitmap (other tiles, the twin) untouched.
                    var pick: Pair<Int, Int>? = null
                    store.forEachTileIn(full) { tx, ty, t ->
                        if (pick == null && !t.isMutable && rnd.nextInt(3) == 0) pick = tx to ty
                    }
                    val (tx, ty) = pick ?: (rnd.nextInt(tileCountX) to rnd.nextInt(tileCountY))
                    val tr = refTile(tx, ty)
                    val cx = tr.left + rnd.nextInt(tr.width())
                    val cy = tr.top + rnd.nextInt(tr.height())
                    val clip = Rect(
                        cx, cy,
                        cx + 1 + rnd.nextInt(tr.right - cx),
                        cy + 1 + rnd.nextInt(tr.bottom - cy)
                    )
                    when (rnd.nextInt(3)) {
                        0 -> {
                            val color = pickColour()
                            store.withWriteCanvas(clip) { it.drawColor(color, BlendMode.SRC_OVER) }
                            val save = refCanvas.save()
                            refCanvas.clipRect(clip)
                            refCanvas.drawColor(color, BlendMode.SRC_OVER)
                            refCanvas.restoreToCount(save)
                        }
                        1 -> {
                            val tmp = Bitmap.createBitmap(clip.width(), clip.height(), Bitmap.Config.ARGB_8888)
                            tmp.eraseColor(pickColour())
                            store.putTileRegion(tx, ty, clip, tmp)
                            refCanvas.drawBitmap(tmp, null, RectF(clip), srcPaint)
                            tmp.recycle()
                        }
                        else -> {
                            store.putTileRegion(tx, ty, clip, null)
                            val save = refCanvas.save()
                            refCanvas.clipRect(clip)
                            refCanvas.drawColor(defaultPixel, BlendMode.SRC)
                            refCanvas.restoreToCount(save)
                        }
                    }
                }
                0, 1 -> {
                    val patch = randomPatch()
                    val pw = patch.width
                    val ph = patch.height
                    val x = rnd.nextInt(w - pw + 1)
                    val y = rnd.nextInt(h - ph + 1)
                    val dst = Rect(x, y, x + pw, y + ph)
                    val srcRect = Rect(0, 0, pw, ph)
                    store.writeRect(dst, patch, srcRect)
                    refCanvas.drawBitmap(
                        patch, srcRect, RectF(dst), srcPaint
                    )
                    patch.recycle()
                }
                2 -> {
                    // clearRect resets to the store's default, which is opaque
                    // white for masks, not transparent.
                    val r = randomRect()
                    store.clearRect(r)
                    val save = refCanvas.save()
                    refCanvas.clipRect(r)
                    refCanvas.drawColor(defaultPixel, BlendMode.SRC)
                    refCanvas.restoreToCount(save)
                }
                3 -> {
                    val r = randomRect()
                    val color = if (rnd.nextInt(5) == 0) 0 else rnd.nextInt()
                    store.fillRect(r, color)
                    val p = Paint().apply {
                        blendMode = BlendMode.SRC
                        this.color = color
                    }
                    refCanvas.drawRect(RectF(r), p)
                }
                4 -> {
                    val x = rnd.nextInt(w)
                    val y = rnd.nextInt(h)
                    if (store.getPixel(x, y) != ref.getPixel(x, y)) {
                        fail(step, "getPixel($x,$y)")
                    }
                }
                5, 6 -> {
                    val r = randomRect()
                    val got = store.readRect(r)
                    val want = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888)
                    Canvas(want).drawBitmap(
                        ref, r, Rect(0, 0, r.width(), r.height()), srcPaint
                    )
                    val ok = got.sameAs(want)
                    got.recycle()
                    want.recycle()
                    if (!ok) fail(step, "readRect $r")
                }
                7 -> {
                    val mode = blendModes[rnd.nextInt(blendModes.size)]
                    val p = Paint().apply {
                        blendMode = mode
                        alpha = rnd.nextInt(256)
                        isFilterBitmap = false
                    }
                    val got = Bitmap.createBitmap(probeW, probeH, Bitmap.Config.ARGB_8888)
                    store.drawInto(Canvas(got), p)
                    val want = Bitmap.createBitmap(probeW, probeH, Bitmap.Config.ARGB_8888)
                    Canvas(want).drawBitmap(ref, 0f, 0f, p)
                    val ok = got.sameAs(want)
                    got.recycle()
                    want.recycle()
                    if (!ok) fail(step, "drawInto blend=${p.blendMode} alpha=${p.alpha}")
                }
                8 -> {
                    // A 1:1 (unscaled) blit stamped through every intersecting
                    // tile via editTile. Dabs may start or end outside the
                    // canvas, exactly like real brush stamps.
                    val patch = randomPatch()
                    val pw = patch.width
                    val ph = patch.height
                    val x = rnd.nextInt(w + pw) - pw
                    val y = rnd.nextInt(h + ph) - ph
                    val dst = RectF(x.toFloat(), y.toFloat(), (x + pw).toFloat(), (y + ph).toFloat())
                    val p = Paint().apply {
                        isFilterBitmap = false
                        alpha = 32 + rnd.nextInt(224)
                        blendMode = BlendMode.SRC_OVER
                    }
                    val l = max(0, x)
                    val t = max(0, y)
                    val r = min(w, x + pw)
                    val b = min(h, y + ph)
                    if (r > l && b > t) {
                        for (ty in t / tile..(b - 1) / tile) {
                            for (tx in l / tile..(r - 1) / tile) {
                                store.editTile(tx, ty) { it.drawBitmap(patch, null, dst, p) }
                            }
                        }
                        refCanvas.drawBitmap(patch, null, dst, p)
                    }
                    patch.recycle()
                }
                9 -> {
                    val r = randomRect()
                    val color = if (rnd.nextInt(4) == 0) 0 else rnd.nextInt()
                    store.withWriteCanvas(r) { it.drawColor(color, BlendMode.SRC) }
                    val save = refCanvas.save()
                    refCanvas.clipRect(r)
                    refCanvas.drawColor(color, BlendMode.SRC)
                    refCanvas.restoreToCount(save)
                }
                10 -> {
                    val rw = 1 + rnd.nextInt(min(probeW, 300))
                    val rh = 1 + rnd.nextInt(min(probeH, 300))
                    val sx = rnd.nextInt(probeW - rw + 1)
                    val sy = rnd.nextInt(probeH - rh + 1)
                    val src = Rect(sx, sy, sx + rw, sy + rh)
                    val dw = 1 + rnd.nextInt(min(probeW, 300))
                    val dh = 1 + rnd.nextInt(min(probeH, 300))
                    val dx = rnd.nextInt(probeW - dw + 1).toFloat()
                    val dy = rnd.nextInt(probeH - dh + 1).toFloat()
                    val dst = RectF(dx, dy, dx + dw, dy + dh)
                    val p = Paint().apply {
                        alpha = rnd.nextInt(256)
                        blendMode = blendModes[rnd.nextInt(blendModes.size)]
                        isFilterBitmap = false
                    }
                    val got = Bitmap.createBitmap(probeW, probeH, Bitmap.Config.ARGB_8888)
                    store.drawRect(Canvas(got), src, dst, p)
                    val want = Bitmap.createBitmap(probeW, probeH, Bitmap.Config.ARGB_8888)
                    Canvas(want).drawBitmap(ref, src, dst, p)
                    val ok = got.sameAs(want)
                    got.recycle()
                    want.recycle()
                    if (!ok) fail(step, "drawRect blend=${p.blendMode}")
                }
                11 -> {
                    // One tile's cropped region, exactly how sparse history
                    // restores an action (null means "reset to default").
                    val tx = rnd.nextInt(tileCountX)
                    val ty = rnd.nextInt(tileCountY)
                    val tx0 = tx * tile
                    val ty0 = ty * tile
                    val tw = min(tile, w - tx0)
                    val th = min(tile, h - ty0)
                    val cx = tx0 + rnd.nextInt(tw)
                    val cy = ty0 + rnd.nextInt(th)
                    val cw = 1 + rnd.nextInt(tx0 + tw - cx)
                    val ch = 1 + rnd.nextInt(ty0 + th - cy)
                    val clip = Rect(cx, cy, cx + cw, cy + ch)
                    if (rnd.nextInt(4) == 0) {
                        store.putTileRegion(tx, ty, clip, null)
                        val save = refCanvas.save()
                        refCanvas.clipRect(clip)
                        refCanvas.drawColor(defaultPixel, BlendMode.SRC)
                        refCanvas.restoreToCount(save)
                    } else {
                        val tmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
                        val px = IntArray(cw * ch)
                        for (i in px.indices) px[i] = rnd.nextInt()
                        tmp.setPixels(px, 0, cw, 0, 0, cw, ch)
                        store.putTileRegion(tx, ty, clip, tmp)
                        refCanvas.drawBitmap(tmp, null, RectF(clip), srcPaint)
                        tmp.recycle()
                    }
                }
                12 -> {
                    // Probe the per-tile opacity cache and compare it with the
                    // reference pixels. A stale flag would make the compositor
                    // cull a layer it should have drawn, so this is checked on
                    // every step as well.
                    val tx = rnd.nextInt(tileCountX)
                    val ty = rnd.nextInt(tileCountY)
                    val got = store.isTileOpaque(tx, ty)
                    val x0 = tx * tile
                    val y0 = ty * tile
                    val tw = min(tile, w - x0)
                    val th = min(tile, h - y0)
                    var want = true
                    val row = IntArray(tw)
                    var yy = 0
                    while (yy < th && want) {
                        ref.getPixels(row, 0, tw, x0, y0 + yy, tw, 1)
                        var xx = 0
                        while (xx < tw) {
                            if ((row[xx] ushr 24) != 255) {
                                want = false
                                break
                            }
                            xx++
                        }
                        yy++
                    }
                    if (got != want) {
                        fail(step, "isTileOpaque($tx,$ty) got=$got want=$want")
                    }
                }
                else -> {
                    store.pruneEmptyTiles(randomRect())
                }
            }
            // Any op must keep the opaque cache consistent with the pixels.
            val stale = store.verifyOpaqueFlags(false)
            if (stale != 0) fail(step, "opaque cache stale=$stale")
            if (fuzzCache != null && rnd.nextInt(3) == 0) {
                // Forced eviction between operations: a random budget, all, or
                // one random tile. Reads afterwards must load them back exactly.
                when (rnd.nextInt(3)) {
                    0 -> fuzzCache.evictTo(rnd.nextInt(4).toLong() * tile * tile * 4L)
                    1 -> fuzzCache.evictAll()
                    else -> store.evictTile(
                        (rnd.nextInt(tileCountX).toLong() shl 32) or rnd.nextInt(tileCountY).toLong()
                    )
                }
            }
            if (store.lostTiles != 0) fail(step, "lost tiles=${store.lostTiles}")
            val badSolids = store.verifySolids()
            if (badSolids != 0) fail(step, "verifySolids=$badSolids")
            if (!solids && store.solidTileCount() != 0) fail(step, "solids while disabled")
            solidPeak = max(solidPeak, store.solidTileCount())
            unshareSeen = store.unshares
            if (step % every == 0) compare(step)
        }
        if (ops % every != 0) compare(ops)

        // clear() must drop every tile and release all bytes.
        store.clear()
        var tilesLeft = 0
        store.forEachTileIn(full) { _, _, _ -> tilesLeft++ }
        if (tilesLeft != 0) fail(ops, "clear left $tilesLeft tiles")
        if (store.residentBytes() != 0L) {
            fail(ops, "clear left ${store.residentBytes()} bytes")
        }
        if (!store.nonEmptyBounds().isEmpty) fail(ops, "clear left non-empty bounds")

        ref.recycle()
        twinRef.recycle()
        twin.clear()
        val evictNote = if (fuzzCache != null) {
            " evict=${fuzzCache.evictions} loads=${fuzzCache.loads} written=${fuzzCache.dirtyWrites}"
        } else {
            ""
        }
        fuzzCache?.close()
        val verdict = if (mismatches == 0) "PASS" else "FAIL"
        val detail = if (note.isEmpty()) "" else " [$note]"
        return "ops=$ops comparisons=$comparisons mismatches=$mismatches" +
            " solidPeak=$solidPeak unshares=$unshareSeen$evictNote $verdict$detail"
    }

    // --------------------------------------------------------------- benchmark

    /**
     * Resident cost by category for a large document, sparse vs dense. The
     * dense 20-layer case cannot be allocated at all (1.28 GB) and reports FAIL
     * so the comparison is explicit.
     */
    private fun scoreMemory(): String {
        fun build(size: Int, sparse: Boolean, layers: Int): String {
            val oldS = TileStoreConfig.useSparseLayers
            val oldT = TileStoreConfig.tileSize
            TileStoreConfig.useSparseLayers = sparse
            TileStoreConfig.tileSize = 256
            val lbl = if (sparse) "sparse" else "dense"
            return try {
                Runtime.getRuntime().gc()
                val engine = DrawingEngine(size, size)
                engine.backgroundColor = BG
                val session = DrawingSession("mem", "Mem", engine, History())
                identityView(session, size, size)
                session.presets = presets
                session.activePresetId = soft.id
                session.assist = 0f
                session.quickShapeEnabled = false
                session.category = BrushCategory.Brush
                for (i in 1 until layers) engine.newLayer(name = "L$i")
                for (i in 0 until layers) {
                    engine.selectLayer(engine.layers[i].id)
                    session.color = 0xFF000000.toInt() or ((i + 1) * 0x0A0A0A)
                    val y = 100f + i * 180f
                    session.stroke(listOf(P(100f, y), P(700f, y + 40f), P(1400f, y)))
                }
                engine.flush()
                engine.selectLayer(engine.layers[0].id)
                session.stroke(listOf(P(200f, 200f), P(600f, 600f)))
                engine.flush()
                "memory ${size}x$size $lbl x$layers: ${engine.memoryReport()}"
            } catch (t: Throwable) {
                "memory ${size}x$size $lbl x$layers: FAIL ${t.javaClass.simpleName}"
            } finally {
                TileStoreConfig.useSparseLayers = oldS
                TileStoreConfig.tileSize = oldT
                Runtime.getRuntime().gc()
            }
        }
        val out = StringBuilder()
        out.append(build(4096, true, 20)).append('\n')
        out.append(build(4096, false, 1)).append('\n')
        out.append(build(4096, false, 20)).append('\n')
        out.append(build(2048, true, 20))
        return out.toString()
    }

    /**
     * Stage 3b-1a memory table: layers with large flat regions, solid tiles on
     * vs the materialised equivalent (every tile a private bitmap). Layers are
     * built through the factory, flat regions through fillRect / fill (the paths
     * that assign solids directly) and a brush stroke is painted over each so a
     * few solids are unshared again. The "materialised" figure is computed from
     * the tile count, because 20 x 4096^2 materialised does not fit the heap.
     */
    private fun scoreSolidMemory(): String {
        val out = StringBuilder()
        val oldS = TileStoreConfig.useSparseLayers
        val oldT = TileStoreConfig.tileSize
        val oldSolid = TileStoreConfig.solidTiles
        fun tileCount(st: com.yume.app.engine.TileStore): Int {
            var n = 0
            st.forEachTileIn(Rect(0, 0, st.width, st.height)) { _, _, _ -> n++ }
            return n
        }
        fun mb(b: Long) = "%.1f".format(b / 1048576.0)
        try {
            TileStoreConfig.useSparseLayers = true
            TileStoreConfig.tileSize = 256
            TileStoreConfig.solidTiles = true
            for (flat in listOf(50, 75, 100)) {
                Runtime.getRuntime().gc()
                SolidTiles.resetCounters()
                val size = 4096
                val engine = DrawingEngine(size, size)
                engine.backgroundColor = BG
                val session = DrawingSession("solidmem", "SolidMem", engine, History())
                identityView(session, size, size)
                session.presets = presets
                session.activePresetId = hard.id
                session.assist = 0f
                session.quickShapeEnabled = false
                session.category = BrushCategory.Brush
                val layers = 20
                for (i in 1 until layers) engine.newLayer(name = "L$i")
                for (i in 0 until layers) {
                    val layer = engine.layers[i]
                    val colour = 0xFF000000.toInt() or ((i * 37 + 11) * 0x010203 and 0xFFFFFF)
                    if (flat == 100) {
                        layer.tiles.fill(colour)
                    } else {
                        // Flat band of the given share of the canvas, offset per
                        // layer, not tile aligned (edge tiles stay private).
                        val hgt = size * flat / 100
                        val top = (i * 97) % (size - hgt + 1)
                        layer.tiles.fillRect(Rect(13, top, size - 7, top + hgt), colour)
                    }
                    engine.selectLayer(layer.id)
                    session.color = 0xFF000000.toInt() or (i * 0x0B0B0B and 0xFFFFFF)
                    val y = 300f + i * 170f
                    session.stroke(listOf(P(100f, y), P(1800f, y + 60f), P(3600f, y)))
                }
                engine.flush()
                var privateBytes = 0L
                var tiles = 0
                var solid = 0
                for (l in engine.layers) {
                    privateBytes += l.tiles.residentBytes()
                    tiles += tileCount(l.tiles)
                    solid += l.tiles.solidTileCount()
                }
                val (shared, sharedBytes) = SolidTiles.stats()
                val materialised = tiles.toLong() * 256L * 256L * 4L
                out.append("solid-mem 4096x4096 x$layers flat=$flat% tiles=$tiles solid=$solid")
                    .append(" private=").append(mb(privateBytes)).append("MB")
                    .append(" sharedSolids=").append(shared).append('/').append(mb(sharedBytes)).append("MB")
                    .append(" total=").append(mb(privateBytes + sharedBytes)).append("MB")
                    .append(" materialised=").append(mb(materialised)).append("MB")
                    .append(" created=").append(SolidTiles.created)
                    .append(" reused=").append(SolidTiles.reused)
                    .append('\n')
                engine.layers.forEach { it.tiles.clear() }
            }
            // One fully filled 8192^2 layer. Store level: the UI memory guard
            // still refuses 8192^2 documents, the harness only measures.
            Runtime.getRuntime().gc()
            SolidTiles.resetCounters()
            val big = com.yume.app.engine.TileStoreFactory.blank(8192, 8192)
            val t0 = System.nanoTime()
            big.fill(0xFF336699.toInt())
            val fillMs = (System.nanoTime() - t0) / 1e6
            val (shared, sharedBytes) = SolidTiles.stats()
            out.append("solid-mem 8192x8192 x1 flat=100% tiles=").append(tileCount(big))
                .append(" solid=").append(big.solidTileCount())
                .append(" private=").append(mb(big.residentBytes())).append("MB")
                .append(" sharedSolids=").append(shared).append('/').append(mb(sharedBytes)).append("MB")
                .append(" materialised=").append(mb(8192L * 8192L * 4L)).append("MB")
                .append(" fill=").append("%.2f".format(fillMs)).append("ms")
                .append(" verifySolids=").append(big.verifySolids())
                .append('\n')
            big.clear()
        } catch (t: Throwable) {
            out.append("solid-mem FAIL ").append(t).append('\n')
        } finally {
            TileStoreConfig.useSparseLayers = oldS
            TileStoreConfig.tileSize = oldT
            TileStoreConfig.solidTiles = oldSolid
            Runtime.getRuntime().gc()
        }
        return out.toString()
    }


    // ============================================================ Stage 3b-1c

    /** Set while a golden run must evict every unpinned tile at each phase. */
    @Volatile private var evictAllPerPhase = false
    /** Lost (unreadable) tiles seen at golden phases; must stay 0. */
    @Volatile private var goldenLost = 0
    private var cacheRoot: File? = null

    private fun goldenSparse(w: Int, h: Int, tile: Int): GoldenRun {
        val oldS = TileStoreConfig.useSparseLayers
        val oldT = TileStoreConfig.tileSize
        TileStoreConfig.useSparseLayers = true
        TileStoreConfig.tileSize = tile
        return try {
            runGolden(w, h)
        } finally {
            TileStoreConfig.useSparseLayers = oldS
            TileStoreConfig.tileSize = oldT
            Runtime.getRuntime().gc()
        }
    }

    /**
     * Every golden phase on sparse stores under forced eviction, compared line by
     * line with the same run without a cache. Modes: a 1 MB and a 4 MB budget
     * enforced after every operation, and "evict every unpinned tile" before
     * each phase is hashed (so every hash reads tiles back from the spill file).
     */
    private fun scoreGoldenEvict(app: YumeApp): String {
        val out = StringBuilder()
        data class Mode(val label: String, val budget: Long, val everyOp: Boolean, val all: Boolean)
        val modes = listOf(
            Mode("budget=1MB/op", 1L shl 20, true, false),
            Mode("budget=4MB/op", 4L shl 20, true, false),
            Mode("evictAll/phase", Long.MAX_VALUE / 4, false, true)
        )
        for ((w, h) in listOf(2048 to 2048, 2000 to 1333)) {
            for (tile in listOf(64, 256)) {
                val base = goldenSparse(w, h, tile).phases
                for (m in modes) {
                    val cache = TileCache.open(app.cacheDir, "harness-golden", m.budget)
                    cache.evictEveryOp = m.everyOp
                    evictAllPerPhase = m.all
                    goldenLost = 0
                    val run = try {
                        goldenSparse(w, h, tile).phases
                    } catch (t: Throwable) {
                        Log.e(TAG, "golden evict failed", t)
                        emptyList()
                    } finally {
                        evictAllPerPhase = false
                    }
                    val stats = cache.stats()
                    cache.close()
                    var bad = 0
                    var first = ""
                    for (i in 0 until min(base.size, run.size)) {
                        if (base[i] != run[i]) {
                            bad++
                            if (first.isEmpty()) first = " first=" + run[i].substringBefore(' ')
                        }
                    }
                    bad += abs(base.size - run.size)
                    val verdict = if (bad == 0 && goldenLost == 0 && run.isNotEmpty()) "MATCH" else "MISMATCH"
                    out.append("golden-evict ${w}x$h tile=$tile ${m.label}: $verdict")
                        .append(" (${run.size} phases, $bad bad, lost=$goldenLost)$first | $stats\n")
                    Runtime.getRuntime().gc()
                }
            }
        }
        return out.toString()
    }

    /** Store fuzz with random forced evictions interleaved (all shapes, solids on). */
    private fun scoreFuzzEvict(): String {
        val out = StringBuilder()
        val white = 0xFFFFFFFF.toInt()
        data class C(val w: Int, val h: Int, val tile: Int, val ops: Int, val every: Int, val seed: Long, val def: Int = 0)
        val combos = listOf(
            C(2000, 1333, 256, 60, 1, 7013L),
            C(37, 53, 16, 300, 1, 7011L),
            C(1001, 777, 16, 300, 1, 7021L),
            C(1001, 777, 64, 400, 1, 7022L),
            C(1001, 777, 256, 400, 1, 7023L),
            C(2048, 2048, 64, 1000, 100, 7031L),
            C(2048, 2048, 256, 1000, 100, 7041L),
            C(1000, 700, 64, 600, 50, 7051L, white),
            C(301, 999, 64, 300, 1, 7061L, white)
        )
        for (c in combos) {
            val res = try {
                fuzzStore(c.w, c.h, c.tile, c.ops, c.every, c.seed, c.def, true, evict = true)
            } catch (t: Throwable) {
                "FAIL ${t.javaClass.simpleName}: ${t.message}"
            }
            out.append("evict-fuzz ").append(c.w).append('x').append(c.h)
                .append(" tile=").append(c.tile)
                .append(if (c.def != 0) " mask" else "")
                .append(' ').append(res).append('\n')
            Runtime.getRuntime().gc()
        }
        return out.toString()
    }

    private fun noiseTile(seed: Int, size: Int, opaque: Boolean = false): Bitmap {
        val rnd = Random(seed.toLong())
        val px = IntArray(size * size)
        for (i in px.indices) {
            val a = if (opaque) 255 else 128 + rnd.nextInt(128)
            px[i] = (a shl 24) or (rnd.nextInt() and 0x00FFFFFF)
        }
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        b.setPixels(px, 0, size, 0, 0, size, size)
        return b
    }

    /**
     * Failure injection: a failing writer, low disk space, a corrupt and a
     * truncated record, compaction, and a save that must refuse (keeping the
     * previous PNG) when a cached tile is unreadable.
     */
    private fun scoreCacheFailures(app: YumeApp): String {
        val out = StringBuilder()
        val root = File(app.cacheDir, "tilecache-harness")
        root.deleteRecursively()
        fun painted(cache: TileCache, seed: Int): SparseTileStore {
            val st = SparseTileStore(1000, 700, 64, solidTiles = true, cache = cache)
            val n = noiseTile(seed, 64)
            val rnd = Random(seed.toLong())
            for (ty in 0 until 11) for (tx in 0 until 16) {
                if (rnd.nextInt(3) == 0) continue
                val r = Rect(tx * 64, ty * 64, min(1000, tx * 64 + 64), min(700, ty * 64 + 64))
                st.writeRect(r, n, Rect(0, 0, r.width(), r.height()))
            }
            return st
        }
        fun same(a: Bitmap, b: Bitmap) = a.sameAs(b).also { a.recycle() }

        // 1. Failing writer: nothing may be evicted, nothing may be lost.
        run {
            val cache = TileCache(File(root, "w"), 0L)
            val st = painted(cache, 1)
            val before = st.copyAll()
            st.spillFile!!.failWrites = true
            val n = cache.evictAll()
            val ok1 = n == 0 && cache.writeFailures > 0 && cache.lastError != null &&
                same(st.copyAll(), before)
            st.spillFile!!.failWrites = false
            val n2 = cache.evictAll()
            val ok2 = n2 > 0 && same(st.copyAll(), before) && st.lostTiles == 0
            out.append("cache-fail writer: ${if (ok1 && ok2) "PASS" else "FAIL"}")
                .append(" refused=$n writeFail=${cache.writeFailures} later=$n2 msg=\"${cache.lastError}\"\n")
            before.recycle()
            cache.close()
        }
        // 2. Low disk space: refuse to spill, keep tiles, report it.
        run {
            val cache = TileCache(File(root, "d"), 0L)
            val st = painted(cache, 2)
            val before = st.copyAll()
            cache.minFreeDiskBytes = Long.MAX_VALUE / 4
            val n = cache.evictAll()
            val ok = n == 0 && same(st.copyAll(), before) && cache.lastError?.contains("storage") == true
            out.append("cache-fail lowdisk: ${if (ok) "PASS" else "FAIL"} refused=$n msg=\"${cache.lastError}\"\n")
            before.recycle()
            cache.close()
        }
        // 3/4. Corrupt and truncated records: CRC / short read detected, no
        // crash, the tile is reported lost and every other tile still reads.
        for (kind in listOf("corrupt", "truncate")) {
            val cache = TileCache(File(root, kind), 0L)
            val st = painted(cache, 3)
            val before = st.copyAll()
            cache.evictAll()
            // The record with the highest offset: truncating then cuts only it.
            var victim = -1L
            var best = -1L
            for (ty in 0 until 11) for (tx in 0 until 16) {
                val k = (tx.toLong() shl 32) or ty.toLong()
                val rec = st.spillFile!!.lookup(k) ?: continue
                if (rec.offset > best) { best = rec.offset; victim = k }
            }
            if (kind == "corrupt") st.spillFile!!.corruptForTest(victim) else st.spillFile!!.truncateForTest(victim)
            val vx = (victim ushr 32).toInt()
            val vy = victim.toInt()
            val crashed = runCatching { st.getTile(vx, vy) }.isFailure
            val lost = st.lostTiles
            // Every other tile: compare against the reference outside the victim.
            val after = st.copyAll()
            val c = Canvas(after)
            val p = Paint().apply { blendMode = BlendMode.SRC }
            val vr = Rect(vx * 64, vy * 64, min(1000, vx * 64 + 64), min(700, vy * 64 + 64))
            c.drawBitmap(before, vr, vr, p)
            val othersOk = after.sameAs(before)
            after.recycle()
            val ok = !crashed && lost == 1 && othersOk && cache.readFailures >= 1
            out.append("cache-fail $kind: ${if (ok) "PASS" else "FAIL"} lost=$lost readFail=${cache.readFailures}")
                .append(" othersIntact=$othersOk msg=\"${cache.lastError}\"\n")
            before.recycle()
            cache.close()
        }
        // 5. Compaction: rewrite tiles until garbage passes half the log.
        run {
            val cache = TileCache(File(root, "c"), 0L)
            val st = SparseTileStore(1000, 700, 64, solidTiles = true, cache = cache)
            var ok = true
            for (round in 0 until 6) {
                val n = noiseTile(100 + round, 64)
                for (ty in 0 until 11) for (tx in 0 until 16) {
                    val r = Rect(tx * 64, ty * 64, min(1000, tx * 64 + 64), min(700, ty * 64 + 64))
                    st.writeRect(r, n, Rect(0, 0, r.width(), r.height()))
                }
                val want = st.copyAll()
                cache.evictAll()
                ok = ok && same(st.copyAll(), want)
                want.recycle()
            }
            val f = st.spillFile!!
            ok = ok && f.compactions > 0 && st.lostTiles == 0
            out.append("cache-compaction: ${if (ok) "PASS" else "FAIL"} compactions=${f.compactions}")
                .append(" file=${f.fileBytes / 1024}KB live=${f.liveBytes / 1024}KB written=${f.bytesWritten / 1024}KB\n")
            cache.close()
        }
        // 6. Save refusal: a document whose layer has an unreadable cached tile
        // keeps its previous PNG and reports a message.
        run {
            val ps = app.projectStore
            val oldS = TileStoreConfig.useSparseLayers
            val oldT = TileStoreConfig.tileSize
            TileStoreConfig.useSparseLayers = true
            TileStoreConfig.tileSize = 64
            val project = ps.createProject("harness-cache-refuse", 1000, 700, BG)
            val cache = TileCache.open(app.cacheDir, project.id, Long.MAX_VALUE / 4)
            try {
                val engine = DrawingEngine(1000, 700)
                engine.backgroundColor = BG
                val layer = engine.newLayer("L")
                val n = noiseTile(9, 64)
                for (ty in 0 until 11) for (tx in 0 until 16) {
                    val r = Rect(tx * 64, ty * 64, min(1000, tx * 64 + 64), min(700, ty * 64 + 64))
                    layer.tiles.writeRect(r, n, Rect(0, 0, r.width(), r.height()))
                }
                layer.touch()
                val session = DrawingSession("refuse", "R", engine, History())
                ps.saveProject(project.id, session.snapshotNow())
                val file = savedLayerFile(ps.projectDir(project.id), "layer_0")
                val crcA = crcBytes(file.readBytes())
                layer.tiles.fillRect(Rect(10, 10, 300, 200), 0xFF00FF00.toInt())
                layer.touch()
                cache.evictAll()
                val st = layer.tiles as SparseTileStore
                // A tile with a spill record (fully filled tiles became solids).
                var victim = -1L
                for (ty in 0 until 11) for (tx in 0 until 16) {
                    val k = (tx.toLong() shl 32) or ty.toLong()
                    if (victim < 0 && st.spillFile!!.has(k)) victim = k
                }
                st.spillFile!!.corruptForTest(victim)
                ps.saveWarning = null
                ps.saveProject(project.id, session.snapshotNow())
                val crcB = crcBytes(file.readBytes())
                val warned = ps.saveWarning
                val ok = crcA == crcB && warned != null && st.lostTiles > 0
                out.append("cache-fail save-refuse: ${if (ok) "PASS" else "FAIL"} keptPrevious=${crcA == crcB}")
                    .append(" lost=${st.lostTiles} msg=\"$warned\"\n")
                ps.saveWarning = null
            } catch (t: Throwable) {
                out.append("cache-fail save-refuse: FAIL ${t.javaClass.simpleName}: ${t.message}\n")
            } finally {
                cache.close()
                runCatching { ps.deleteProject(project.id) }
                TileStoreConfig.useSparseLayers = oldS
                TileStoreConfig.tileSize = oldT
            }
        }
        root.deleteRecursively()
        return out.toString()
    }

    /**
     * Banded PNG (Stage 3c) against the platform codec, on opaque, translucent
     * and mask-like content at sizes that are and are not tile multiples:
     *  - legacy file (Bitmap.compress, what old documents contain) read by the
     *    banded decoder into a sparse store == BitmapFactory decode;
     *  - banded file read by BitmapFactory == the same file read banded;
     *  - banded file read back == the source pixels (exact for opaque content;
     *    translucent pixels go through unpremultiply, reported as a diff).
     */
    private fun scorePngParity(app: YumeApp): String {
        val out = StringBuilder()
        val dir = File(app.cacheDir, "png-parity")
        dir.deleteRecursively()
        dir.mkdirs()
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        fun content(kind: String, w: Int, h: Int): Bitmap {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val rnd = Random(w * 31L + h + kind.length)
            when (kind) {
                "opaque" -> {
                    c.drawColor(0xFF203040.toInt())
                    val p = Paint()
                    for (i in 0 until 60) {
                        p.color = rnd.nextInt() or 0xFF000000.toInt()
                        c.drawCircle(rnd.nextInt(w).toFloat(), rnd.nextInt(h).toFloat(), 8f + rnd.nextInt(120), p)
                    }
                }
                "translucent" -> {
                    val p = Paint(Paint.ANTI_ALIAS_FLAG)
                    for (i in 0 until 80) {
                        p.color = rnd.nextInt()
                        c.drawCircle(rnd.nextInt(w).toFloat(), rnd.nextInt(h).toFloat(), 8f + rnd.nextInt(120), p)
                    }
                }
                else -> { // mask: white with black/grey shapes
                    c.drawColor(0xFFFFFFFF.toInt())
                    val p = Paint(Paint.ANTI_ALIAS_FLAG)
                    for (i in 0 until 30) {
                        val g = rnd.nextInt(256)
                        p.color = 0xFF000000.toInt() or (g * 0x010101)
                        c.drawRect(
                            rnd.nextInt(w).toFloat(), rnd.nextInt(h).toFloat(),
                            rnd.nextInt(w).toFloat(), rnd.nextInt(h).toFloat(), p
                        )
                    }
                }
            }
            return b
        }
        fun bandedRead(file: File, w: Int, h: Int, def: Int): Bitmap? {
            val st = SparseTileStore(w, h, 256, null, file, def, solidTiles = true)
            st.ensureResident()
            if (!st.isResident) return null
            return st.copyAll()
        }
        var fails = 0
        for ((w, h) in listOf(1000 to 700, 1024 to 1024, 301 to 999)) {
            for (kind in listOf("opaque", "translucent", "mask")) {
                val def = if (kind == "mask") 0xFFFFFFFF.toInt() else 0
                val src = content(kind, w, h)
                val legacy = File(dir, "legacy-$kind-$w.png")
                java.io.FileOutputStream(legacy).use { src.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val banded = File(dir, "banded-$kind-$w.png")
                java.io.BufferedOutputStream(java.io.FileOutputStream(banded)).use { o ->
                    com.yume.app.engine.BandedPng.encode(o, w, h, 256) { y0, rows, px ->
                        src.getPixels(px, 0, w, 0, y0, w, rows)
                    }
                }
                val legacyPlatform = BitmapFactory.decodeFile(legacy.path, opts)
                val legacyBanded = bandedRead(legacy, w, h, def)
                val bandedPlatform = BitmapFactory.decodeFile(banded.path, opts)
                val bandedBanded = bandedRead(banded, w, h, def)
                val a = legacyBanded != null && legacyBanded.sameAs(legacyPlatform)
                val b = bandedBanded != null && bandedPlatform != null && bandedBanded.sameAs(bandedPlatform)
                val roundTrip = if (bandedBanded == null) "decode-failed"
                else if (bandedBanded.sameAs(src)) "exact" else diffNote(bandedBanded, src)
                val legacyVsBanded = if (bandedPlatform != null && legacyPlatform != null) {
                    if (bandedPlatform.sameAs(legacyPlatform)) "same" else diffNote(bandedPlatform, legacyPlatform)
                } else "n/a"
                val ok = a && b && (kind == "translucent" || roundTrip == "exact")
                if (!ok) fails++
                out.append("png-parity ${w}x$h $kind: ${if (ok) "PASS" else "FAIL"}")
                    .append(" legacy:banded==platform=$a new:banded==platform=$b")
                    .append(" roundTrip=$roundTrip newVsLegacyPixels=$legacyVsBanded")
                    .append(" bytes legacy=${legacy.length() / 1024}KB banded=${banded.length() / 1024}KB\n")
                listOfNotNull(src, legacyPlatform, legacyBanded, bandedPlatform, bandedBanded).forEach { it.recycle() }
            }
        }
        // Speed at 4096^2 opaque content: banded encode vs Bitmap.compress.
        run {
            val src = content("opaque", 4096, 4096)
            val f1 = File(dir, "speed-legacy.png")
            val t0 = System.nanoTime()
            java.io.FileOutputStream(f1).use { src.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val t1 = System.nanoTime()
            val f2 = File(dir, "speed-banded.png")
            java.io.BufferedOutputStream(java.io.FileOutputStream(f2), 1 shl 16).use { o ->
                com.yume.app.engine.BandedPng.encode(o, 4096, 4096, 256) { y0, rows, px ->
                    src.getPixels(px, 0, 4096, 0, y0, 4096, rows)
                }
            }
            val t2 = System.nanoTime()
            val st = SparseTileStore(4096, 4096, 256, null, f2, 0, solidTiles = true)
            st.ensureResident()
            val t3 = System.nanoTime()
            val tPlat = System.nanoTime()
            BitmapFactory.decodeFile(f2.path, opts)?.recycle()
            val t4 = System.nanoTime()
            out.append("png-speed 4096x4096: encode legacy=${(t1 - t0) / 1_000_000}ms ${f1.length() shr 10}KB")
                .append(" banded=${(t2 - t1) / 1_000_000}ms ${f2.length() shr 10}KB;")
                .append(" decode banded->tiles=${(t3 - t2) / 1_000_000}ms platform=${(t4 - tPlat) / 1_000_000}ms\n")
            src.recycle()
            st.clear()
        }
        out.append("png-parity verdict=${if (fails == 0) "PASS" else "FAIL"}\n")
        dir.deleteRecursively()
        return out.toString()
    }


    /**
     * Crash recovery (Stage 3c): full save, then two journal autosaves covering
     * a stroke, a fill, a new layer, a deletion, a new mask and a reorder, then
     * edits that are NOT autosaved. Reopening must give exactly the state at the
     * second autosave. Also: a torn log tail is ignored, a corrupt record is
     * reported without a crash, a later full save retires the journal, and a
     * journal from before a full save (stale saveSeq) is ignored.
     */
    private fun scoreJournal(app: YumeApp): String {
        val out = StringBuilder()
        val ps = app.projectStore
        val oldS = TileStoreConfig.useSparseLayers
        val oldT = TileStoreConfig.tileSize
        TileStoreConfig.useSparseLayers = true
        TileStoreConfig.tileSize = 256
        val w = 1500
        val h = 1000
        val project = ps.createProject("harness-journal", w, h, BG)
        var cache: TileCache? = null
        fun state(engine: DrawingEngine): String {
            engine.compositeAll()
            return engine.layers.joinToString(" ") { it.name + "=" + hashLayer(it) } +
                " C=" + compositeHash(engine)
        }
        fun rebuild(): Pair<DrawingEngine, String> {
            cache?.close()
            cache = TileCache.open(app.cacheDir, project.id, 64L shl 20)
            val loaded = ps.loadProject(project.id) ?: error("reload failed")
            val e = DrawingEngine(w, h)
            e.backgroundColor = loaded.background
            for (d in loaded.layers) {
                val l = e.newLayer(name = d.name, bitmap = d.bitmap, backingFile = d.file)
                l.adoptMask(d.mask, d.maskFile)
                l.opacity = d.opacity
                l.blend = d.blend
                l.visible = d.visible
                l.maskEnabled = d.maskEnabled
            }
            ps.adoptBases(project.id, e.layers.map { it.id }, loaded.layers)
            var note = "none"
            loaded.recovery?.let { r ->
                note = runCatching { "replayed=" + r.apply(e) }.getOrElse { "error=" + it.message }
            }
            return e to note
        }
        try {
            cache = TileCache.open(app.cacheDir, project.id, 64L shl 20)
            val engine = DrawingEngine(w, h)
            engine.backgroundColor = BG
            val session = DrawingSession("journal", "J", engine, History())
            identityView(session, w, h)
            session.presets = presets
            session.activePresetId = hard.id
            session.assist = 0f
            session.quickShapeEnabled = false
            session.category = BrushCategory.Brush
            val names = listOf("A", "B", "C")
            for (n in names) {
                val l = engine.newLayer(n)
                engine.selectLayer(l.id)
                session.color = 0xFF000000.toInt() or (n.hashCode() * 0x3F5A1 and 0xFFFFFF)
                session.stroke(listOf(P(50f, 100f + n[0].code), P(1400f, 900f), P(700f, 80f)))
            }
            ps.saveProject(project.id, session.snapshotNow())
            ps.adoptBases(project.id, emptyList(), emptyList())
            // saveProject recorded the bases itself; re-derive them like the editor.
            ps.saveProject(project.id, session.snapshotNow())
            fun autosave(): String {
                val batch = session.collectJournal(
                    isNew = { id, m -> ps.baseOf(project.id, id, m) == null },
                    maxBytes = 96L shl 20
                ) ?: return "collect-null"
                ps.writeJournal(project.id, batch)
                return "records=${batch.changes.size}"
            }
            // Autosave 1: stroke, fill, a new layer, delete, mask, reorder.
            engine.selectLayer(engine.layers[0].id)
            session.color = 0xFF33AA55.toInt()
            session.stroke(listOf(P(100f, 500f), P(1300f, 520f)))
            engine.fillLayer(engine.layers[1].id, 0x8033CCFF.toInt())
            val d = engine.newLayer("D")
            engine.selectLayer(d.id)
            session.color = 0xFFAA2244.toInt()
            session.stroke(listOf(P(300f, 300f), P(1200f, 800f)))
            engine.deleteLayer(engine.layers.first { it.name == "C" }.id)
            val a = engine.layers.first { it.name == "A" }
            engine.addMask(a)
            a.maskTiles!!.fillRect(Rect(200, 200, 900, 700), 0xFF000000.toInt())
            engine.moveLayer(0, engine.layers.size - 1)
            val j1 = autosave()
            // Autosave 2: more paint, a cleared region.
            engine.selectLayer(d.id)
            session.color = 0xFF2266EE.toInt()
            session.stroke(listOf(P(1400f, 100f), P(100f, 900f)))
            engine.layers.first { it.name == "B" }.tiles.clearRect(Rect(0, 0, 600, 400))
            val j2 = autosave()
            val expected = state(engine)
            // Not autosaved: must not come back after the "crash".
            session.color = 0xFF000000.toInt()
            session.stroke(listOf(P(10f, 10f), P(1490f, 990f)))
            engine.layers.first { it.name == "D" }.tiles.fillRect(Rect(0, 0, 300, 300), 0xFFFFFF00.toInt())
            val unsaved = state(engine)

            // Crash: nothing else is written. Reopen.
            val (r1, n1) = rebuild()
            val got1 = state(r1)
            val ok1 = got1 == expected && got1 != unsaved
            out.append("journal recover: ${if (ok1) "PASS" else "FAIL"} $j1 $j2 $n1\n")
            if (!ok1) out.append("  expected: $expected\n  got:      $got1\n")

            // Torn tail: bytes after the committed length are ignored.
            val log = File(File(ps.projectDir(project.id), "journal"), "tiles.log")
            log.appendBytes(ByteArray(777) { (it * 37).toByte() })
            val (r2, n2) = rebuild()
            val ok2 = state(r2) == expected
            out.append("journal torn-tail: ${if (ok2) "PASS" else "FAIL"} $n2\n")

            // Corrupt record inside the committed length: reported, no crash.
            val bytes = log.readBytes()
            val mid = bytes.size / 2
            bytes[mid] = (bytes[mid].toInt() xor 0x55).toByte()
            val saved = log.readBytes()
            log.writeBytes(bytes)
            val crashed = runCatching { rebuild() }
            val ok3 = crashed.isSuccess && crashed.getOrNull()!!.second.startsWith("error=")
            out.append("journal corrupt: ${if (ok3) "PASS" else "FAIL"} ${crashed.getOrNull()?.second ?: crashed.exceptionOrNull()}\n")
            log.writeBytes(saved)

            // Stale journal: a full save happened after it (saveSeq moved on).
            val (r4, _) = rebuild()
            val s4 = DrawingSession("journal2", "J2", r4, History())
            val stateBefore = state(r4)
            val journalState = File(File(ps.projectDir(project.id), "journal"), "state.json").readText()
            ps.saveProject(project.id, s4.snapshotNow())
            val retired = !File(ps.projectDir(project.id), "journal").exists()
            File(File(ps.projectDir(project.id), "journal").apply { mkdirs() }, "state.json").writeText(journalState)
            log.writeBytes(saved)
            val (r5, n5) = rebuild()
            val ok5 = retired && n5 == "none" && state(r5) == stateBefore
            out.append("journal full-save+stale: ${if (ok5) "PASS" else "FAIL"} retired=$retired recovery=$n5\n")
        } catch (t: Throwable) {
            Log.e(TAG, "journal test failed", t)
            out.append("journal: FAIL ${t.javaClass.simpleName}: ${t.message}\n")
        } finally {
            cache?.close()
            runCatching { ps.deleteProject(project.id) }
            TileStoreConfig.useSparseLayers = oldS
            TileStoreConfig.tileSize = oldT
        }
        return out.toString()
    }


    /**
     * History disk tier (Stage 3c): 40 overlapping strokes with a 4 MB
     * in-memory history cap and a spill file. Undo all the way back and redo
     * all the way forward must reproduce the hash recorded after every step,
     * exactly like an unlimited in-memory history.
     */
    private fun scoreHistorySpill(app: YumeApp): String {
        val oldS = TileStoreConfig.useSparseLayers
        val oldT = TileStoreConfig.tileSize
        TileStoreConfig.useSparseLayers = true
        TileStoreConfig.tileSize = 256
        val file = File(app.cacheDir, "history-spill-test.spill")
        return try {
            val size = 2048
            val history = History(maxBytes = 4L shl 20)
            history.spill = com.yume.app.engine.HistorySpill(file)
            val engine = DrawingEngine(size, size)
            engine.backgroundColor = BG
            val session = DrawingSession("hist", "H", engine, History().let { history })
            identityView(session, size, size)
            session.presets = presets
            session.activePresetId = soft.id
            session.assist = 0f
            session.quickShapeEnabled = false
            session.category = BrushCategory.Brush
            val l = engine.newLayer("L")
            engine.selectLayer(l.id)
            val hashes = ArrayList<String>()
            hashes.add(hashLayer(l))
            val steps = 40
            for (i in 0 until steps) {
                session.color = 0xFF000000.toInt() or ((i * 0x2F3B17) and 0xFFFFFF)
                val y = 100f + (i % 20) * 90f
                session.stroke(listOf(P(80f + i * 7f, y), P(1900f - i * 11f, y + 300f), P(1000f, 1900f - i * 13f)))
                engine.flush()
                hashes.add(hashLayer(l))
            }
            var bad = 0
            var firstBad = -1
            for (i in steps downTo 1) {
                if (!history.undo(engine)) { bad++; if (firstBad < 0) firstBad = i; break }
                if (hashLayer(l) != hashes[i - 1]) { bad++; if (firstBad < 0) firstBad = i }
            }
            for (i in 1..steps) {
                if (!history.redo(engine)) { bad++; break }
                if (hashLayer(l) != hashes[i]) { bad++; if (firstBad < 0) firstBad = 100 + i }
            }
            val s = history.spill!!
            val verdict = if (bad == 0 && history.spilledActions > 0) "PASS" else "FAIL"
            "history-spill 2048x2048 steps=$steps cap=4MB: $verdict bad=$bad firstBad=$firstBad" +
                " spilledActions=${history.spilledActions} loads=${history.loadedActions}" +
                " diskWrites=${s.writes} diskReads=${s.reads} disk=${s.diskBytes / 1024}KB" +
                " error=${history.lastError ?: "none"}"
        } catch (t: Throwable) {
            Log.e(TAG, "history spill failed", t)
            "history-spill: FAIL ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            file.delete()
            TileStoreConfig.useSparseLayers = oldS
            TileStoreConfig.tileSize = oldT
        }
    }


    /** CRC of a store's pixels read band by band (no canvas-sized bitmap). */
    private fun bandHash(store: com.yume.app.engine.TileStore): String {
        val w = store.width
        val h = store.height
        val rows = 256
        val band = Bitmap.createBitmap(w, min(rows, h), Bitmap.Config.ARGB_8888)
        val px = IntArray(w)
        val crc = java.util.zip.CRC32()
        val bytes = ByteArray(w * 4)
        var y = 0
        while (y < h) {
            val n = min(rows, h - y)
            store.readRectInto(Rect(0, y, w, y + n), band)
            for (r in 0 until n) {
                band.getPixels(px, 0, w, 0, r, w, 1)
                var o = 0
                for (c in px) {
                    bytes[o] = (c ushr 24).toByte(); bytes[o + 1] = (c ushr 16).toByte()
                    bytes[o + 2] = (c ushr 8).toByte(); bytes[o + 3] = c.toByte()
                    o += 4
                }
                crc.update(bytes, 0, w * 4)
            }
            y += n
        }
        band.recycle()
        return "%08x".format(crc.value)
    }

    /**
     * Stage 3c acceptance on the device: an 8192^2 document with 30 painted
     * layers through paint, transform, blur, merge, undo/redo, save, close and
     * reopen. The allocation counter runs for the whole sequence and must stay
     * empty; the flattened hash before saving must match after reopening.
     */
    private fun scoreAcceptance8k(app: YumeApp): String {
        val out = StringBuilder()
        val size = 8192
        val layers = 30
        val ps = app.projectStore
        val oldS = TileStoreConfig.useSparseLayers
        val oldT = TileStoreConfig.tileSize
        TileStoreConfig.useSparseLayers = true
        TileStoreConfig.tileSize = 256
        val am = app.getSystemService(android.app.ActivityManager::class.java)
        val budget = TileCache.defaultBudget(am)
        var cache: TileCache? = null
        val rt = Runtime.getRuntime()
        var javaPeak = 0L
        var nativePeak = 0L
        fun sample() {
            javaPeak = max(javaPeak, rt.totalMemory() - rt.freeMemory())
            nativePeak = max(nativePeak, android.os.Debug.getNativeHeapAllocatedSize())
        }
        fun ms(t0: Long) = "%.0f".format((System.nanoTime() - t0) / 1e6)
        AllocTracker.reset(size, size)
        AllocTracker.enabled = true
        val fits = MemoryBudget.fitsDocument(size, size, 1)
        val project = ps.createProject("harness-8k", size, size, BG)
        try {
            cache = TileCache.open(app.cacheDir, project.id, budget)
            val c0 = cache!!
            val engine = DrawingEngine(size, size)
            engine.backgroundColor = BG
            val session = DrawingSession("8k", "8K", engine, History())
            session.history.spill = com.yume.app.engine.HistorySpill(File(c0.dir, "history.spill"))
            identityView(session, size, size)
            session.presets = presets
            session.activePresetId = hard.id
            session.assist = 0f
            session.quickShapeEnabled = false
            session.category = BrushCategory.Brush
            val noise = (0 until 4).map { noiseTile(800 + it, 256, opaque = true) }
            var t0 = System.nanoTime()
            var noiseTiles = 0
            for (i in 0 until layers) {
                val l = engine.newLayer("L$i")
                l.opacity = if (i % 4 == 0) 0.85f else 1f
                l.blend = if (i % 5 == 3) LayerBlend.Multiply else LayerBlend.Normal
                // A flat region (shared solids), a few noise tiles, three strokes.
                val top = (i * 211) % (size / 2)
                l.tiles.fillRect(Rect(i * 64, top, size - i * 32, top + size / 3), 0xFF000000.toInt() or (i * 0x070B0D and 0xFFFFFF))
                val rnd = Random(9000L + i)
                for (k in 0 until 80) {
                    val tx = rnd.nextInt(32)
                    val ty = rnd.nextInt(32)
                    l.tiles.writeRect(Rect(tx * 256, ty * 256, tx * 256 + 256, ty * 256 + 256), noise[k % 4], Rect(0, 0, 256, 256))
                    noiseTiles++
                }
                engine.selectLayer(l.id)
                session.color = 0xFF000000.toInt() or ((i * 0x135791) and 0xFFFFFF)
                for (s in 0 until 3) {
                    val y = 400f + ((i * 3 + s) * 251) % (size - 800)
                    session.stroke(listOf(P(200f, y), P(size * 0.5f, y + 900f), P(size - 200f, y)))
                }
                l.touch()
                c0.safePoint()
                sample()
            }
            engine.markDirtyAll()
            engine.flush()
            c0.safePoint()
            sample()
            out.append("accept-8k create ${size}x$size x$layers noiseTiles=$noiseTiles budget=${budget shr 20}MB")
                .append(" guardAllows=$fits time=${ms(t0)}ms\n")

            // Painting frames on the top layer (renderer-style frames).
            engine.selectLayer(engine.layers.last().id)
            c0.resetCounters()
            fun frame(block: () -> Unit): Long {
                val f0 = System.nanoTime()
                c0.adoptPrefetched()
                block()
                c0.blockingLoads = false
                try { engine.flush() } finally { c0.blockingLoads = true }
                c0.evictIfNeeded()
                sample()
                return System.nanoTime() - f0
            }
            val paint = ArrayList<Long>()
            session.pushInput(InputEvent.Down(0, 3000f, 3000f, 1f, 0f, true, false))
            session.runInput()
            val steps = 960
            var i = 1
            while (i <= steps) {
                val end = min(i + 8, steps + 1)
                paint.add(frame {
                    while (i < end) {
                        val t = i / steps.toFloat()
                        // A hand-speed stroke: ~2000 px over 120 frames (1 s).
                        session.pushInput(
                            InputEvent.Move(
                                0, floatArrayOf(3000f + t * 2000f),
                                floatArrayOf(3000f + sin(t * 6f) * 600f), floatArrayOf(1f), 0f
                            )
                        )
                        session.runInput()
                        i++
                    }
                })
            }
            session.pushInput(InputEvent.Up(0))
            session.runInput()
            run { var g = 0; while (engine.hasDirty() && g++ < 20000) frame { } }
            out.append(frameLine("accept-8k painting", paint))
                .append(" syncRender=${c0.syncLoadsRender} deferred=${c0.deferredTiles} evict=${c0.evictions}\n")

            // One-shot operations.
            fun op(name: String, block: () -> Unit) {
                val s0 = System.nanoTime()
                block()
                run { var g = 0; while (engine.hasDirty() && g++ < 20000) frame { } }
                c0.safePoint()
                sample()
                out.append("accept-8k op $name: ${ms(s0)}ms\n")
            }
            op("transform") {
                session.chooseTool(Tool.Transform)
                session.runCommands()
                session.pushInput(InputEvent.Gesture(size * 0.5f, size * 0.5f, 1f, 37f, -21f, 0f))
                session.runInput()
                session.commitTransform()
                session.runCommands()
                session.chooseTool(Tool.Brush)
                session.runCommands()
            }
            op("blur") {
                session.previewAdjustment(AdjustmentKind.Blur, 0.3f)
                session.consumePendingPreview()
                session.commitAdjustment()
                session.runCommands()
            }
            op("merge") { session.mergeDown(); session.runCommands() }
            op("undo x3") { repeat(3) { session.undo(); session.runCommands() } }
            op("redo x3") { repeat(3) { session.redo(); session.runCommands() } }

            engine.compositeAll()
            c0.safePoint()
            val hashBefore = bandHash(engine.compositeTiles!!)
            t0 = System.nanoTime()
            ps.saveProject(project.id, session.snapshotNow())
            val saveMs = ms(t0)
            val complete = ps.lastSaveComplete
            sample()
            val stats = c0.stats()
            val acc = c0.accounting().entries.joinToString(" ") { "${it.key}=${it.value shr 20}MB" }
            val peak = c0.peakResident
            val hist = session.history
            val histNote = "historySpilled=${hist.spilledActions} historyDisk=${(hist.spill?.diskBytes ?: 0) shr 20}MB"
            hist.spill?.close()
            c0.close()
            cache = null
            noise.forEach { it.recycle() }
            Runtime.getRuntime().gc()
            out.append("accept-8k save: ${saveMs}ms complete=$complete warning=${ps.saveWarning ?: "none"} | $stats\n")
            out.append("accept-8k accounting at close: $acc peakResident=${peak shr 20}MB $histNote\n")
            ps.saveWarning = null

            t0 = System.nanoTime()
            val c1 = TileCache.open(app.cacheDir, project.id, budget)
            cache = c1
            val loaded = ps.loadProject(project.id) ?: error("reload failed")
            val re = DrawingEngine(size, size)
            re.backgroundColor = loaded.background
            for (d in loaded.layers) {
                val l = re.newLayer(name = d.name, bitmap = d.bitmap, backingFile = d.file)
                l.adoptMask(d.mask, d.maskFile)
                l.opacity = d.opacity
                l.blend = d.blend
                l.visible = d.visible
            }
            re.compositeAll()
            c1.safePoint()
            sample()
            val openMs = ms(t0)
            val hashAfter = bandHash(re.compositeTiles!!)
            AllocTracker.enabled = false
            val alloc = AllocTracker.table()
            val allocOk = AllocTracker.sites().isEmpty()
            val verdict = if (hashAfter == hashBefore && allocOk && complete) "PASS" else "FAIL"
            out.append("accept-8k reopen: $verdict before=$hashBefore after=$hashAfter open=${openMs}ms")
                .append(" alloc=[${alloc.ifEmpty { "none" }}] | ${c1.stats()}\n")
            out.append("accept-8k memory: javaHeapPeak=${javaPeak shr 20}MB nativeHeapPeak=${nativePeak shr 20}MB")
                .append(" largeHeap=${(am?.largeMemoryClass ?: 0)}MB\n")
        } catch (t: Throwable) {
            Log.e(TAG, "8k acceptance failed", t)
            out.append("accept-8k: FAIL ${t.javaClass.simpleName}: ${t.message}\n")
        } finally {
            AllocTracker.enabled = false
            cache?.close()
            runCatching { ps.deleteProject(project.id) }
            TileStoreConfig.useSparseLayers = oldS
            TileStoreConfig.tileSize = oldT
            Runtime.getRuntime().gc()
        }
        return out.toString()
    }


    /**
     * Two-finger pan over every tool: one finger lands and drags, a second
     * finger turns it into a pan (the view's CancelStroke), then the fingers
     * pan and pinch a little. Nothing in the document may change: layers,
     * selection, guides, symmetry, transform, history, pending text.
     */
    private fun scorePanCancel(): String {
        val out = StringBuilder()
        var bad = 0
        fun scenario(name: String, setup: (DrawingSession, DrawingEngine) -> Unit) {
            val size = 512
            val engine = DrawingEngine(size, size)
            engine.backgroundColor = 0xFFFFFFFF.toInt()
            val session = DrawingSession("pc", "PC", engine, History())
            identityView(session, size, size)
            session.presets = presets
            session.assist = 0f
            session.quickShapeEnabled = false
            val l = engine.newLayer("Ink")
            engine.selectLayer(l.id)
            pick(session, hard, BrushCategory.Brush)
            session.runCommands()
            session.color = 0xFF2040C0.toInt()
            session.stroke(listOf(P(60f, 60f), P(450f, 300f), P(120f, 460f)))
            setup(session, engine)
            session.runCommands()
            session.runInput()
            fun state(): String {
                val sel = engine.selection?.bounds?.toShortString() ?: "none"
                val tv = session.transformValues()?.joinToString(",") ?: "none"
                return engine.layers.joinToString("|") { hashLayer(it) } +
                    " sel=$sel guides=${engine.guides.toJson()} sym=${engine.symmetryXPos},${engine.symmetryYPos}" +
                    " t=$tv hist=${session.history.totalCount} text=${session.textRequest.value?.joinToString()}"
            }
            val before = state()
            session.pushInput(InputEvent.Down(0, 200f, 200f, 1f, 0f, false, false))
            session.runInput()
            for (k in 1..6) {
                session.pushInput(InputEvent.Move(0, floatArrayOf(200f + k * 9f), floatArrayOf(200f + k * 4f), floatArrayOf(1f), 0f))
                session.runInput()
            }
            session.tickFrame()
            session.pushInput(InputEvent.CancelStroke)
            session.pushInput(InputEvent.Gesture(256f, 256f, 1.01f, 0.6f, 30f, 5f, first = true))
            session.pushInput(InputEvent.Gesture(256f, 256f, 1f, 0f, 40f, 0f))
            session.runInput()
            session.runCommands()
            engine.flush()
            val after = state()
            val ok = before == after
            if (!ok) bad++
            out.append("pan-cancel $name: ${if (ok) "ok" else "CHANGED\n    before=$before\n    after =$after"}\n")
        }
        scenario("brush") { _, _ -> }
        scenario("eraser") { s, _ -> pick(s, eraser, BrushCategory.Eraser) }
        scenario("smudge") { s, _ -> pick(s, smudge, BrushCategory.Smudge) }
        scenario("fill") { s, _ -> s.chooseTool(Tool.Fill) }
        scenario("gradient") { s, _ -> s.chooseTool(Tool.Gradient) }
        scenario("shape") { s, _ -> s.chooseTool(Tool.Shape) }
        for (m in com.yume.app.engine.LiquifyMode.entries) {
            scenario("liquify-${m.name}") { s, _ -> s.chooseTool(Tool.Liquify); s.setLiquifyMode(m) }
        }
        scenario("text") { s, _ -> s.chooseTool(Tool.Text) }
        for (shape in SelectionShape.entries) {
            scenario("selection-${shape.name}") { s, _ -> s.chooseTool(Tool.Selection); s.chooseSelectionShape(shape) }
        }
        scenario("selection-move") { s, e ->
            e.applySelection(android.graphics.Region(150, 150, 300, 300), SelectionOp.Replace)
            s.chooseTool(Tool.Selection)
            s.chooseSelectionShape(SelectionShape.Rectangle)
        }
        scenario("selection-auto-move") { s, e ->
            e.applySelection(android.graphics.Region(150, 150, 300, 300), SelectionOp.Replace)
            s.chooseTool(Tool.Selection)
            s.chooseSelectionShape(SelectionShape.Automatic)
        }
        scenario("transform") { s, _ -> s.chooseTool(Tool.Transform) }
        scenario("symmetry-line") { s, e ->
            e.symmetryX = true
            e.symmetryXPos = 200f
        }
        scenario("guide-handle") { s, e ->
            s.setGuideMode(com.yume.app.engine.GuideMode.Perspective)
            s.setVanishingPoints(1)
            e.guides.vpX[0] = 200f
            e.guides.vpY[0] = 200f
            s.setGuideEditing(true)
        }
        out.insert(0, "pan-cancel: ${if (bad == 0) "PASS" else "FAIL ($bad changed)"}\n")
        return out.toString()
    }

    /**
     * Big-canvas compositing timings: three filled layers at different
     * opacities plus a few strokes. Opening (every tile dirty, renderer-style
     * frames with the frame budget), an opacity change, the mip, and undo /
     * redo of a whole-canvas fill, each until the screen has settled.
     */
    private fun scoreCompositeBig(app: YumeApp, size: Int): String {
        val out = StringBuilder()
        val ps = app.projectStore
        val oldS = TileStoreConfig.useSparseLayers
        val oldT = TileStoreConfig.tileSize
        TileStoreConfig.useSparseLayers = true
        TileStoreConfig.tileSize = 256
        val am = app.getSystemService(android.app.ActivityManager::class.java)
        val budget = TileCache.defaultBudget(am)
        var cache: TileCache? = null
        val project = ps.createProject("harness-comp", size, size, BG)
        val tag = "comp-${size / 1024}k"
        fun ms(t0: Long) = "%.0f".format((System.nanoTime() - t0) / 1e6)
        try {
            cache = TileCache.open(app.cacheDir, project.id, budget)
            val c0 = cache!!
            val engine = DrawingEngine(size, size)
            engine.backgroundColor = BG
            val session = DrawingSession("cb", "CB", engine, History())
            session.history.spill = com.yume.app.engine.HistorySpill(File(c0.dir, "history.spill"))
            identityView(session, size, size)
            session.presets = presets
            session.activePresetId = hard.id
            session.assist = 0f
            session.quickShapeEnabled = false
            session.category = BrushCategory.Brush
            var t0 = System.nanoTime()
            val a = engine.newLayer("A")
            a.tiles.fillRect(Rect(0, 0, size, size), 0xFFD04030.toInt())
            val b = engine.newLayer("B")
            b.tiles.fillRect(Rect(0, 0, size, size), 0xFF3060C0.toInt())
            b.opacity = 0.6f
            val c = engine.newLayer("C")
            c.tiles.fillRect(Rect(0, size / 3, size, size), 0xFF40B060.toInt())
            c.opacity = 0.4f
            engine.selectLayer(c.id)
            session.color = 0xFF101010.toInt()
            for (k in 0 until 4) {
                val y = size * (0.2f + 0.2f * k)
                session.stroke(listOf(P(size * 0.1f, y), P(size * 0.5f, y + size * 0.05f), P(size * 0.9f, y)))
            }
            listOf(a, b, c).forEach { it.touch() }
            out.append("$tag setup ${size}x$size: ${ms(t0)}ms\n")
            fun settle(): String {
                val f0 = System.nanoTime()
                var frames = 0
                var worst = 0L
                while (engine.hasDirty() && frames < 200000) {
                    val s0 = System.nanoTime()
                    c0.adoptPrefetched()
                    c0.blockingLoads = false
                    try { engine.flush() } finally { c0.blockingLoads = true }
                    c0.evictIfNeeded()
                    frames++
                    worst = max(worst, System.nanoTime() - s0)
                }
                return "${ms(f0)}ms frames=$frames worstFrame=${"%.1f".format(worst / 1e6)}ms"
            }
            fun mip(): String {
                val m0 = System.nanoTime()
                engine.enableMip()
                var g = 0
                while (engine.hasMipDirty && g++ < 100000) engine.updateMip()
                return "${ms(m0)}ms"
            }
            com.yume.app.engine.CompositeProfile.flatTiles = 0
            com.yume.app.engine.CompositeProfile.enabled = true
            engine.markDirtyAll()
            out.append("$tag open: ${settle()} flatTiles=${com.yume.app.engine.CompositeProfile.flatTiles} mip=${mip()}\n")
            b.opacity = 0.45f
            engine.markDirtyAll()
            out.append("$tag opacity change: ${settle()} mip=${mip()}\n")
            val f = engine.newLayer("F")
            engine.selectLayer(f.id)
            session.chooseTool(Tool.Fill)
            session.runCommands()
            session.fillLayerOnly = true
            session.color = 0xFFEEBB22.toInt()
            t0 = System.nanoTime()
            session.fillAt(10f, 10f)
            session.runCommands()
            out.append("$tag fill: ${ms(t0)}ms + ${settle()} mip=${mip()}\n")
            t0 = System.nanoTime()
            session.undo(); session.runCommands()
            out.append("$tag undo: ${ms(t0)}ms + ${settle()} mip=${mip()}\n")
            t0 = System.nanoTime()
            session.redo(); session.runCommands()
            out.append("$tag redo: ${ms(t0)}ms + ${settle()} mip=${mip()}\n")
            com.yume.app.engine.CompositeProfile.enabled = false
            out.append("$tag memory: nativeHeap=${android.os.Debug.getNativeHeapAllocatedSize() shr 20}MB | ${c0.stats()}\n")
            session.history.spill?.close()

            // Save, then open from disk the way the editor does: lazy layers,
            // renderer-style frames until the screen has settled.
            engine.selectLayer(f.id)
            engine.deleteLayer(f.id)
            engine.compositeAll()
            val hashBefore = bandHash(engine.compositeTiles!!)
            t0 = System.nanoTime()
            ps.saveProject(project.id, session.snapshotNow())
            out.append("$tag save: ${ms(t0)}ms complete=${ps.lastSaveComplete}\n")
            c0.close()
            cache = null
            Runtime.getRuntime().gc()
            t0 = System.nanoTime()
            val c1 = TileCache.open(app.cacheDir, project.id, budget)
            cache = c1
            val loaded = ps.loadProject(project.id) ?: error("reload failed")
            val re = DrawingEngine(size, size)
            re.backgroundColor = loaded.background
            for (d in loaded.layers) {
                val l = re.newLayer(name = d.name, bitmap = d.bitmap, backingFile = d.file)
                l.adoptMask(d.mask, d.maskFile)
                l.opacity = d.opacity
                l.blend = d.blend
                l.visible = d.visible
            }
            val loadMs = ms(t0)
            val r0 = System.nanoTime()
            var frames = 0
            re.markDirtyAll()
            while (re.hasDirty() && frames < 200000) {
                c1.adoptPrefetched()
                c1.blockingLoads = false
                try { re.flush() } finally { c1.blockingLoads = true }
                c1.evictIfNeeded()
                frames++
            }
            re.compositeAll()
            val hashAfter = bandHash(re.compositeTiles!!)
            out.append("$tag reopen ${if (hashAfter == hashBefore) "MATCH" else "MISMATCH before=$hashBefore after=$hashAfter"}: manifest ${loadMs}ms, composite settled ${ms(r0)}ms frames=$frames total ${ms(t0)}ms | ${c1.stats()}\n")
        } catch (t: Throwable) {
            Log.e(TAG, "comp failed", t)
            out.append("$tag: FAIL ${t.javaClass.simpleName}: ${t.message}\n")
        } finally {
            com.yume.app.engine.CompositeProfile.enabled = false
            cache?.close()
            runCatching { ps.deleteProject(project.id) }
            TileStoreConfig.useSparseLayers = oldS
            TileStoreConfig.tileSize = oldT
            Runtime.getRuntime().gc()
        }
        return out.toString()
    }

    /**
     * Cancel: a long fill and a long blur on an 8K layer are cancelled from
     * another thread part-way through. The layer and history must come back
     * exactly as they were, and the next operation must run normally.
     */
    private fun scoreCancel(app: YumeApp): String {
        val out = StringBuilder()
        val size = 8192
        val oldS = TileStoreConfig.useSparseLayers
        val oldT = TileStoreConfig.tileSize
        TileStoreConfig.useSparseLayers = true
        TileStoreConfig.tileSize = 256
        var cache: TileCache? = null
        val ps = app.projectStore
        val project = ps.createProject("harness-cancel", 64, 64, BG)
        try {
            val am = app.getSystemService(android.app.ActivityManager::class.java)
            cache = TileCache.open(app.cacheDir, project.id, TileCache.defaultBudget(am))
            val engine = DrawingEngine(size, size)
            engine.backgroundColor = BG
            val session = DrawingSession("cx", "CX", engine, History())
            identityView(session, size, size)
            session.presets = presets
            session.activePresetId = hard.id
            session.assist = 0f
            session.quickShapeEnabled = false
            session.category = BrushCategory.Brush
            val l = engine.newLayer("Ink")
            engine.selectLayer(l.id)
            val noise = noiseTile(77, 256, opaque = true)
            for (ty in 0 until 32 step 3) for (tx in 0 until 32 step 2) {
                l.tiles.writeRect(Rect(tx * 256, ty * 256, tx * 256 + 256, ty * 256 + 256), noise, Rect(0, 0, 256, 256))
            }
            l.touch()
            engine.markDirtyAll()
            engine.flush()
            fun state() = hashLayer(l) + " hist=" + session.history.totalCount
            // Like the UI: Cancel exists only once a cancellable step runs.
            fun cancelAfter(ms: Long) = Thread {
                var waited = 0
                while (!com.yume.app.engine.OpCancel.armed && waited < 120_000) { Thread.sleep(5); waited += 5 }
                Thread.sleep(ms)
                com.yume.app.engine.OpCancel.requested = true
            }.apply { start() }
            fun check(name: String, delayMs: Long, op: () -> Unit) {
                val before = state()
                val t = cancelAfter(delayMs)
                val t0 = System.nanoTime()
                op()
                val ms = (System.nanoTime() - t0) / 1_000_000
                t.join()
                val after = state()
                out.append("cancel $name after ${delayMs}ms: ${if (before == after) "RESTORED" else "CHANGED"} in ${ms}ms\n")
            }
            session.chooseTool(Tool.Fill)
            session.runCommands()
            session.fillLayerOnly = false
            session.color = 0xFF3366CC.toInt()
            check("fill 8K", 250) { session.fillAt(4000f, 100f); session.runCommands() }
            session.chooseTool(Tool.Brush)
            session.runCommands()
            check("blur 8K", 300) {
                session.previewAdjustment(com.yume.app.engine.AdjustmentKind.Blur, 0.5f)
                session.consumePendingPreview()
                session.commitAdjustment()
                session.runCommands()
            }
            // Not cancelled: a normal fill afterwards still lands.
            val before = state()
            session.chooseTool(Tool.Fill)
            session.runCommands()
            session.fillAt(4000f, 100f)
            session.runCommands()
            out.append("cancel then normal fill: ${if (state() != before) "APPLIED" else "MISSING"}\n")
            noise.recycle()
        } catch (t: Throwable) {
            Log.e(TAG, "cancel test failed", t)
            out.append("cancel: FAIL ${t.javaClass.simpleName}: ${t.message}\n")
        } finally {
            cache?.close()
            runCatching { ps.deleteProject(project.id) }
            TileStoreConfig.useSparseLayers = oldS
            TileStoreConfig.tileSize = oldT
        }
        return out.toString()
    }

    /**
     * Autosave after a whole-canvas fill at 16K: the history snapshot taken
     * for saving and the history files. A fill is 4096 tile changes; flat ones
     * must not be copied (they were 1 GB of copies, an out-of-memory crash).
     */
    /**
     * Undo history across a reopen, as the editor does it: strokes (some
     * spilled to the disk tier, one big enough to be streamed), a layer
     * opacity change, a save, then a fresh session restored from disk that
     * must undo to the blank start and redo to the final picture.
     */
    /**
     * Where painting stalls after big strokes at 8K: the time the render
     * thread spends ending each stroke (history capture, streaming to disk)
     * and in each autosave's history snapshot, which runs on that thread.
     */
    private fun scoreFreeze8k(app: YumeApp): String {
        val out = StringBuilder()
        val size = 8192
        val oldS = TileStoreConfig.useSparseLayers
        TileStoreConfig.useSparseLayers = true
        var cache: TileCache? = null
        val ps = app.projectStore
        val project = ps.createProject("harness-freeze8k", size, size, BG)
        fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000
        try {
            val am = app.getSystemService(android.app.ActivityManager::class.java)
            cache = TileCache.open(app.cacheDir, project.id, TileCache.defaultBudget(am))
            val engine = DrawingEngine(size, size)
            engine.backgroundColor = BG
            val session = DrawingSession("fz", "FZ", engine, History())
            session.history.spill = com.yume.app.engine.HistorySpill(File(cache!!.dir, "history.spill"))
            identityView(session, size, size)
            session.presets = presets
            session.activePresetId = hard.id
            session.assist = 0f
            session.quickShapeEnabled = false
            session.category = BrushCategory.Brush
            val l = engine.newLayer("Ink")
            engine.selectLayer(l.id)
            engine.compositeAll()
            session.setBrushSize(900f)
            session.runCommands()
            for (k in 0 until 6) {
                session.color = (0xFF000000.toInt() or (k * 0x2A3B4C))
                val y = 700f + k * 1250f
                val pts = (0..40).map { i -> P(300f + i * 190f, y + kotlin.math.sin(i * 0.4f) * 500f) }
                val t0 = System.nanoTime()
                session.pushInput(InputEvent.Down(0, pts[0].x, pts[0].y, 1f, 0f, true, false))
                session.runInput()
                for (i in 1 until pts.size) {
                    session.pushInput(InputEvent.Move(0, floatArrayOf(pts[i].x), floatArrayOf(pts[i].y), floatArrayOf(1f), 0f))
                    session.runInput()
                }
                val paintMs = ms(t0)
                val spillBefore = session.history.spilledActions
                val tu = System.nanoTime()
                session.pushInput(InputEvent.Up(0))
                session.runInput()
                session.runCommands()
                val endMs = ms(tu)
                val strokeMs = "paint $paintMs + end $endMs (spilled ${session.history.spilledActions - spillBefore} step now)"
                val t1 = System.nanoTime()
                val h = session.historyForDiskNow(ps.savedHistoryIds(project.id))
                val snapMs = ms(t1)
                val t2 = System.nanoTime()
                ps.saveHistory(project.id, h, ps.historyStamp(project.id))
                h.entries.forEach { e -> e.tiles?.forEach { t -> t.before?.recycle(); t.after?.recycle() } }
                val saveMs = ms(t2)
                out.append("freeze8k stroke $k: paint+end ${strokeMs} ms, history snapshot (render thread) ${snapMs} ms, " +
                    "history write ${saveMs} ms, spilled ${session.history.spilledActions}\n")
            }
            session.history.spill?.close()
        } catch (t: Throwable) {
            Log.e(TAG, "freeze8k failed", t)
            out.append("freeze8k: FAIL ${t.javaClass.simpleName}: ${t.message}\n")
        } finally {
            cache?.close()
            runCatching { ps.deleteProject(project.id) }
            TileStoreConfig.useSparseLayers = oldS
        }
        return out.toString()
    }

    private fun scoreHistPersist(app: YumeApp): String {
        val out = StringBuilder()
        val size = 6144
        val oldS = TileStoreConfig.useSparseLayers
        TileStoreConfig.useSparseLayers = true
        var cache: TileCache? = null
        val ps = app.projectStore
        val project = ps.createProject("harness-histpersist", size, size, BG)
        try {
            val am = app.getSystemService(android.app.ActivityManager::class.java)
            cache = TileCache.open(app.cacheDir, project.id, TileCache.defaultBudget(am))
            val engine = DrawingEngine(size, size)
            engine.backgroundColor = BG
            val session = DrawingSession("hp", "HP", engine, History())
            session.history.spill = com.yume.app.engine.HistorySpill(File(cache!!.dir, "history.spill"))
            identityView(session, size, size)
            session.presets = presets
            session.activePresetId = hard.id
            session.assist = 0f
            session.quickShapeEnabled = false
            session.category = BrushCategory.Brush
            val l = engine.newLayer("Ink")
            engine.selectLayer(l.id)
            engine.compositeAll()
            fun hashNow(e: DrawingEngine): String { e.compositeAll(); return e.flatten().let { b -> val h = hashBitmap(b); b.recycle(); h } }
            // The picture after each step: states[k] = after k steps.
            val states = ArrayList<String>()
            states.add(hashNow(engine))
            session.setBrushSize(900f)
            session.runCommands()
            for (k in 0 until 8) {
                session.color = (0xFF000000.toInt() or (k * 0x1F3A57))
                val y = 500f + k * 650f
                session.stroke(listOf(P(200f, y), P(3000f, y + 200f), P(5900f, y - 100f)))
                session.runCommands()
                states.add(hashNow(engine))
            }
            val final = states.last()
            val steps = session.history.totalCount
            out.append("histpersist: $steps steps, spilled ${session.history.spilledActions}\n")
            // Save as the editor does: the pixels, then the history; again
            // until every step is on disk (each save copies a bounded amount,
            // the steps already written are reused).
            var hist: com.yume.app.engine.PersistedHistory? = null
            var rounds = 0
            do {
                val snapDoc = session.snapshotNow()
                hist = session.historyForDiskNow(ps.savedHistoryIds(project.id))
                ps.saveProject(project.id, snapDoc)
                ps.saveHistory(project.id, hist!!, ps.historyStamp(project.id))
                hist!!.entries.forEach { e -> e.tiles?.forEach { t -> t.before?.recycle(); t.after?.recycle() } }
                rounds++
            } while (hist!!.entries.size < steps && rounds < 6)
            out.append("histpersist: saved ${hist!!.entries.size} of $steps steps in $rounds saves (applied ${hist!!.appliedCount})\n")
            session.history.spill?.close()

            // Reopen.
            val loaded = ps.loadProject(project.id)!!
            val e2 = DrawingEngine(size, size)
            e2.backgroundColor = loaded.background
            for (d in loaded.layers) {
                val ly = e2.newLayer(name = d.name, bitmap = d.bitmap, backingFile = d.file)
                ly.adoptMask(d.mask, d.maskFile)
                ly.opacity = d.opacity
                ly.blend = d.blend
                ly.visible = d.visible
                ly.maskEnabled = d.maskEnabled
            }
            // Layer ids are new in this engine: map by position, as the file does.
            val back = ps.loadHistory(project.id, ps.historyStamp(project.id))
            if (back == null) {
                out.append("histpersist: reload FAIL (no history)\n")
            } else {
                val s2 = DrawingSession("hp2", "HP2", e2, History())
                identityView(s2, size, size)
                val ids = HashMap<Long, Long>()
                loaded.layers.forEachIndexed { i, d -> if (d.id != 0L) ids[d.id] = e2.layers[i].id }
                s2.restoreHistory(back, emptyMap(), ids)
                s2.runCommands()
                val reopened = hashNow(e2)
                // Undo one step at a time: each must match the picture painted then.
                var matches = 0
                var undos = 0
                while (s2.history.canUndo && undos < 100) {
                    s2.undo(); s2.runCommands(); undos++
                    if (hashNow(e2) == states.getOrNull(steps - undos)) matches++
                }
                var guard = 0
                while (s2.history.canRedo && guard++ < 100) { s2.redo(); s2.runCommands() }
                val redone = hashNow(e2)
                out.append("histpersist: reload ${back.entries.size} steps; reopened ${if (reopened == final) "= final" else "!= final"}, " +
                    "$matches/$undos undo states match, redo all ${if (redone == final) "= final" else "!= final"} " +
                    (if (back.entries.size == steps && reopened == final && matches == undos && undos == steps && redone == final) "PASS" else "FAIL") + "\n")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "histpersist failed", t)
            out.append("histpersist: FAIL ${t.javaClass.simpleName}: ${t.message}\n")
        } finally {
            cache?.close()
            runCatching { ps.deleteProject(project.id) }
            TileStoreConfig.useSparseLayers = oldS
        }
        return out.toString()
    }

    private fun scoreHistory16k(app: YumeApp): String {
        val out = StringBuilder()
        val size = 16384
        val oldS = TileStoreConfig.useSparseLayers
        val oldT = TileStoreConfig.tileSize
        TileStoreConfig.useSparseLayers = true
        TileStoreConfig.tileSize = 256
        var cache: TileCache? = null
        val ps = app.projectStore
        val project = ps.createProject("harness-hist16k", size, size, BG)
        fun ms(t0: Long) = "%.0f".format((System.nanoTime() - t0) / 1e6)
        try {
            val am = app.getSystemService(android.app.ActivityManager::class.java)
            cache = TileCache.open(app.cacheDir, project.id, TileCache.defaultBudget(am))
            val engine = DrawingEngine(size, size)
            engine.backgroundColor = BG
            val session = DrawingSession("h16", "H16", engine, History())
            session.history.spill = com.yume.app.engine.HistorySpill(File(cache!!.dir, "history.spill"))
            identityView(session, size, size)
            session.presets = presets
            session.activePresetId = hard.id
            session.assist = 0f
            session.quickShapeEnabled = false
            session.category = BrushCategory.Brush
            val l = engine.newLayer("Ink")
            engine.selectLayer(l.id)
            session.color = 0xFF202020.toInt()
            session.stroke(listOf(P(1000f, 1000f), P(9000f, 6000f), P(15000f, 2000f)))
            session.chooseTool(Tool.Fill)
            session.runCommands()
            session.fillLayerOnly = false
            session.color = 0xFF3366CC.toInt()
            var t0 = System.nanoTime()
            session.fillAt(100f, 15000f)
            session.runCommands()
            out.append("hist16k fill: ${ms(t0)}ms changes=${session.history.totalCount}\n")
            val heap0 = android.os.Debug.getNativeHeapAllocatedSize() shr 20
            // The snapshot runs on the session's own queue: drive it from here.
            var snap: com.yume.app.engine.PersistedHistory? = null
            t0 = System.nanoTime()
            val th = Thread { snap = session.historyForDiskBlocking() }.apply { start() }
            while (th.isAlive) { session.runCommands(); Thread.sleep(2) }
            val heap1 = android.os.Debug.getNativeHeapAllocatedSize() shr 20
            val tiles = snap?.entries?.sumOf { it.tiles?.size ?: 0 } ?: 0
            val solids = snap?.entries?.sumOf { e -> e.tiles?.count { it.afterSolid >= 0L } ?: 0 } ?: 0
            out.append("hist16k snapshot: ${ms(t0)}ms tiles=$tiles flatAsValues=$solids nativeHeap ${heap0}->${heap1}MB\n")
            t0 = System.nanoTime()
            snap?.let { ps.saveHistory(project.id, it) }
            val files = File(ps.projectDir(project.id), "history").listFiles()?.size ?: 0
            out.append("hist16k save: ${ms(t0)}ms files=$files\n")
            val back = ps.loadHistory(project.id)
            val backTiles = back?.entries?.sumOf { it.tiles?.size ?: 0 } ?: 0
            val backSolids = back?.entries?.sumOf { e -> e.tiles?.count { it.afterSolid >= 0L } ?: 0 } ?: 0
            out.append("hist16k reload: entries=${back?.entries?.size} tiles=$backTiles flat=$backSolids " +
                (if (backTiles == tiles && backSolids == solids) "MATCH" else "MISMATCH") + "\n")
            session.history.spill?.close()
        } catch (t: Throwable) {
            Log.e(TAG, "hist16k failed", t)
            out.append("hist16k: FAIL ${t.javaClass.simpleName}: ${t.message}\n")
        } finally {
            cache?.close()
            runCatching { ps.deleteProject(project.id) }
            TileStoreConfig.useSparseLayers = oldS
            TileStoreConfig.tileSize = oldT
        }
        return out.toString()
    }

    /**
     * Read-only: loads the user's 8K image project (never saved back, own
     * tile cache) and builds the motion-blur preview on its top Image layer
     * at 19 px / -5 degrees, saving the preview and the layer at preview size.
     */
    private fun scoreMotionUser(app: YumeApp): String {
        val out = StringBuilder()
        val id = "20dc8b68-315e-4a63-b5da-5e78c4e63a41"
        val ps = app.projectStore
        var cache: TileCache? = null
        try {
            val am = app.getSystemService(android.app.ActivityManager::class.java)
            cache = TileCache.open(app.cacheDir, "harness-motionuser", TileCache.defaultBudget(am))
            val loaded = ps.loadProject(id) ?: return "motionuser: load failed\n"
            val engine = DrawingEngine(loaded.width, loaded.height)
            engine.backgroundColor = loaded.background
            for (dl in loaded.layers) {
                val l = engine.newLayer(name = dl.name, bitmap = dl.bitmap, backingFile = dl.file)
                l.adoptMask(dl.mask, dl.maskFile)
                l.opacity = dl.opacity
                l.blend = dl.blend
                l.visible = dl.visible
            }
            val session = DrawingSession("mu", "MU", engine, History())
            identityView(session, loaded.width, loaded.height)
            val target = engine.layers.last { it.name == "Image" }
            engine.selectLayer(target.id)
            out.append("motionuser: ${loaded.width}x${loaded.height} layers=${engine.layers.size} target=${engine.layers.indexOf(target)} sparse=${target.tiles.isSparse} tileSize=${target.tiles.tileSize}\n")
            session.chooseMotionAngle(-5f)
            session.previewAdjustment(com.yume.app.engine.AdjustmentKind.MotionBlur, 0.2f)
            session.runCommands()
            session.consumePendingPreview()
            val proxy = engine.preview?.proxy
            if (proxy == null) return out.append("motionuser: NO PREVIEW\n").toString()
            java.io.FileOutputStream(File(app.filesDir, "mu_gpu.png")).use { proxy.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val small = Bitmap.createBitmap(proxy.width, proxy.height, Bitmap.Config.ARGB_8888)
            val sc = android.graphics.Canvas(small)
            sc.scale(proxy.width.toFloat() / loaded.width, proxy.height.toFloat() / loaded.height)
            target.tiles.drawInto(sc, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            java.io.FileOutputStream(File(app.filesDir, "mu_layer.png")).use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // What the screen shows: the composite (full resolution, drawn
            // scaled) and the mip, both with the preview in place.
            // Frames the way the renderer runs them: non-blocking loads, the
            // frame budget, eviction between frames.
            run {
                val c0 = cache!!
                engine.priorityRect.set(0, 0, loaded.width, loaded.height)
                var frames = 0
                while (engine.hasDirty() && frames < 100000) {
                    c0.adoptPrefetched()
                    c0.blockingLoads = false
                    try { engine.flush() } finally { c0.blockingLoads = true }
                    c0.evictIfNeeded()
                    frames++
                }
                out.append("motionuser: settled in $frames frames, cache=${c0.residentBytes() shr 20}MB\n")
            }
            val view = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
            run {
                val vc = android.graphics.Canvas(view)
                vc.scale(1024f / loaded.width, 1024f / loaded.height)
                engine.drawComposite(vc, Rect(0, 0, loaded.width, loaded.height))
            }
            java.io.FileOutputStream(File(app.filesDir, "mu_composite.png")).use { view.compress(Bitmap.CompressFormat.PNG, 100, it) }
            engine.enableMip()
            var g = 0
            while (engine.hasMipDirty && g++ < 100000) engine.updateMip()
            run {
                val vc = android.graphics.Canvas(view)
                vc.drawColor(0, android.graphics.BlendMode.CLEAR)
                vc.scale(1024f / loaded.width, 1024f / loaded.height)
                engine.drawMip(vc)
            }
            java.io.FileOutputStream(File(app.filesDir, "mu_mip.png")).use { view.compress(Bitmap.CompressFormat.PNG, 100, it) }
            out.append("motionuser: proxy ${proxy.width}x${proxy.height} config=${proxy.config}\n")
            session.cancelAdjustment()
            session.runCommands()
        } catch (t: Throwable) {
            Log.e(TAG, "motionuser failed", t)
            out.append("motionuser: FAIL ${t.javaClass.simpleName}: ${t.message}\n")
        } finally {
            cache?.close()
        }
        return out.toString()
    }

    /** A dense, slightly wobbly pen path from (x0,y0) to (x1,y1) with a pressure arc. */
    private fun handLine(
        x0: Float, y0: Float, x1: Float, y1: Float, seed: Int,
        wobble: Float = 6f, pMin: Float = 0.35f, pMax: Float = 1f, step: Float = 7f
    ): List<P> {
        val rnd = java.util.Random(seed.toLong())
        val len = kotlin.math.hypot(x1 - x0, y1 - y0)
        val n = max(2, (len / step).toInt())
        val nx = -(y1 - y0) / max(1f, len)
        val ny = (x1 - x0) / max(1f, len)
        val ph = rnd.nextFloat() * 6.28f
        val fr = 1.5f + rnd.nextFloat() * 2f
        val out = ArrayList<P>(n + 1)
        for (i in 0..n) {
            val t = i / n.toFloat()
            val w = (kotlin.math.sin(t * fr * 6.28f + ph) * 0.6f + (rnd.nextFloat() - 0.5f) * 0.5f) * wobble
            val pr = pMin + (pMax - pMin) * kotlin.math.sin(t * Math.PI).toFloat().coerceIn(0f, 1f)
            out.add(P(x0 + (x1 - x0) * t + nx * w, y0 + (y1 - y0) * t + ny * w, pr.coerceIn(0.05f, 1f)))
        }
        return out
    }

    /**
     * The new brush engines side by side, saved as files/bleedtest.png: colour
     * blocks blended by the wet-bleed blender, bleeding watercolour, wet
     * edges, glazing, splatter, foliage and jittered acrylic. Also checks that
     * the dense and the sparse store give the same pixels.
     */
    private fun scoreBleedTest(app: YumeApp): String {
        val out = StringBuilder()
        val w = 1600
        val h = 1100
        val oldS = TileStoreConfig.useSparseLayers
        val hashes = ArrayList<Int>()
        try {
            for (sparse in listOf(false, true)) {
                TileStoreConfig.useSparseLayers = sparse
                val engine = DrawingEngine(w, h)
                engine.backgroundColor = 0xFFF6F1E7.toInt()
                val session = DrawingSession("bt", "BT", engine, History())
                identityView(session, w, h)
                session.presets = com.yume.app.engine.BrushLibrary.defaults
                session.assist = 0f
                session.quickShapeEnabled = false
                fun use(id: String, size: Float? = null) {
                    val p = session.presets.first { it.id == id }
                    pick(session, p, p.category)
                    if (size != null) session.setBrushSize(size)
                    session.runCommands()
                }
                var seed = 1
                fun line(x0: Float, y0: Float, x1: Float, y1: Float, wob: Float = 6f) =
                    session.stroke(handLine(x0, y0, x1, y1, seed++, wob))
                // 1. Three acrylic blocks, then the wet-bleed blender across them.
                val cols = intArrayOf(0xFFD8452E.toInt(), 0xFFF2C230.toInt(), 0xFF2F6FB5.toInt())
                use("acr-thick", 70f)
                for (k in 0 until 3) {
                    session.color = cols[k]
                    for (r in 0 until 5) line(80f + k * 230f, 90f + r * 52f, 300f + k * 230f, 90f + r * 52f, 3f)
                }
                use("wc-wet-bleed", 90f)
                for (r in 0 until 3) line(120f, 130f + r * 80f, 740f, 150f + r * 80f, 14f)
                for (k in 0 until 2) line(310f + k * 230f, 80f, 300f + k * 230f, 330f, 10f)
                // 2. Bleeding watercolour, overlapping washes.
                use("wc-bleed-paint", 80f)
                session.color = 0xFFB03A6B.toInt()
                line(880f, 110f, 1500f, 130f, 12f)
                line(880f, 180f, 1500f, 200f, 12f)
                session.color = 0xFF2E7D8C.toInt()
                line(900f, 250f, 1500f, 180f, 12f)
                line(900f, 320f, 1480f, 300f, 12f)
                // 3. Wet-edge wash and glaze.
                use("wc-wet-edge", 90f)
                session.color = 0xFF3F5FA8.toInt()
                line(90f, 470f, 720f, 480f, 10f)
                line(90f, 560f, 720f, 550f, 10f)
                use("wc-glaze", 110f)
                session.color = 0xFFE08A2B.toInt()
                line(200f, 430f, 620f, 620f, 10f)
                use("wc-round", 22f)
                session.color = 0xFF27304A.toInt()
                line(110f, 660f, 700f, 640f, 5f)
                // 4. Splatter, foliage, grass, colour-variance acrylic.
                use("wc-splatter")
                session.color = 0xFF8A2E5C.toInt()
                line(900f, 450f, 1480f, 470f, 8f)
                use("foliage")
                session.color = 0xFF3E8A3A.toInt()
                line(900f, 600f, 1480f, 580f, 20f)
                use("grass")
                session.color = 0xFF4E9A44.toInt()
                line(900f, 720f, 1480f, 720f, 6f)
                use("acr-variance", 46f)
                session.color = 0xFFE06A3A.toInt()
                for (r in 0 until 4) line(100f, 780f + r * 40f, 720f, 790f + r * 40f, 5f)
                use("cloud")
                session.color = 0xFFFFFFFF.toInt()
                line(900f, 860f, 1480f, 840f, 14f)
                use("wc-wet-bleed-soft", 130f)
                line(120f, 820f, 700f, 900f, 12f)
                session.runCommands()
                engine.compositeAll()
                val flat = engine.flatten()
                val px = IntArray(w * h)
                flat.getPixels(px, 0, w, 0, 0, w, h)
                hashes.add(px.contentHashCode())
                if (sparse) {
                    java.io.FileOutputStream(File(app.filesDir, "bleedtest.png")).use {
                        flat.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                flat.recycle()
            }
            out.append("bleedtest: dense=${"%08x".format(hashes[0])} sparse=${"%08x".format(hashes[1])} " +
                (if (hashes[0] == hashes[1]) "MATCH" else "MISMATCH") + "\n")
        } catch (t: Throwable) {
            Log.e(TAG, "bleedtest failed", t)
            out.append("bleedtest: FAIL ${t.javaClass.simpleName}: ${t.message}\n")
        } finally {
            TileStoreConfig.useSparseLayers = oldS
        }
        return out.toString()
    }

    /**
     * Stroke opacity and the wet-bleed blender over time.
     * 1. A 20% black stroke scribbled back and forth 30 times over white must
     *    stay near 20% grey (it used to pile up to black), dense = sparse.
     * 2. The wet-bleed blender scrubbed 40 times over red / yellow / blue
     *    stripes mixes them but must not darken the area (it went to black).
     */
    /**
     * Smudge cost on a painted 4K layer: every smudge preset at three sizes,
     * one stroke of 30 input events 40 px apart (a brisk hand at 60 Hz). Per
     * event = per frame of drawing: the worst one is the stutter.
     */
    /**
     * Pen lift: fast strokes whose last samples lose pressure while the pen
     * still moves (as when lifting off), with heavy smoothing so the drawn
     * stroke lags. The end must taper, not end in a thin stub or a dot.
     * Writes files/liftend.png and reports the width just before the lift
     * point against the width at mid stroke.
     */
    /**
     * Imports every file in files/importtest/ with the real importer and
     * reports what came in; writes files/importtest.png, a sheet of every
     * imported tip (and texture, right of it) with a test stroke under it.
     */
    /**
     * The hover cursor against a real stroke at the lowest pressure: the
     * live dab size mid-stroke, and the stroke's built-up opacity at its
     * centre line (round tips), for a spread of library presets.
     */
    private fun scoreCursorDab(): String {
        val out = StringBuilder()
        val w = 1600
        val h = 400
        val oldS = TileStoreConfig.useSparseLayers
        TileStoreConfig.useSparseLayers = true
        try {
            val lib = com.yume.app.engine.BrushLibrary.defaults
            val picks = lib.filter { it.category == BrushCategory.Brush && it.sizeJitter == 0f && !it.bleeds && it.wetMix < 0.01f }
                .let { l -> l.filterIndexed { i, _ -> i % 4 == 0 } }.take(14)
            var worstSize = 0f
            var worstOpacity = 0f
            for (p0 in picks) {
                val p = p0.copy(size = 60f, taperIn = 0f)
                val engine = DrawingEngine(w, h)
                engine.backgroundColor = 0x00FFFFFF
                val session = DrawingSession("cd", "CD", engine, History())
                identityView(session, w, h)
                session.presets = listOf(p)
                session.activePresetId = p.id
                session.category = BrushCategory.Brush
                session.tool = Tool.Brush
                session.assist = 0f
                session.quickShapeEnabled = false
                val l = engine.newLayer("S")
                engine.selectLayer(l.id)
                session.color = 0xFF000000.toInt()
                val predicted = com.yume.app.engine.StrokePainter.cursorDab(p, 0f, 0f, 0f)
                session.pushInput(InputEvent.Down(0, 100f, 200f, 0.02f, 0f, true, false))
                session.runInput()
                var live = 0f
                for (i in 1..40) {
                    session.pushInput(InputEvent.Move(0, floatArrayOf(100f + i * 30f), floatArrayOf(200f), floatArrayOf(0.02f), 0f))
                    session.runInput()
                    if (i == 25) live = session.liveCursor()?.get(2) ?: 0f
                }
                session.pushInput(InputEvent.Up(0))
                session.runInput()
                session.runCommands()
                val centre = (l.tiles.getPixel(700, 200) ushr 24) / 255f
                // Speed-dependent brushes: the cursor is the pen at rest (as a
                // stroke starts); this test stroke moves fast.
                val err = if (p.velocitySize != 0f) 0f else if (live > 0f) kotlin.math.abs(predicted.size - live) / live else 1f
                worstSize = max(worstSize, err)
                // What the cursor shows at its centre: alpha x the dab's centre coverage.
                val dab = com.yume.app.engine.BrushDab.dab(p)
                val c0 = (dab.getPixel(dab.width / 2, dab.height / 2) ushr 24) / 255f
                val shown = predicted.alpha * c0
                val oe = kotlin.math.abs(shown - centre)
                if (p.shapeId.isEmpty() && p.grain <= 0.05f) worstOpacity = max(worstOpacity, oe)
                out.append("cursordab ${p.id}: size cursor ${"%.2f".format(predicted.size)} stroke ${"%.2f".format(live)}" +
                    (if (p.velocitySize != 0f) " (speed-dependent: cursor is the pen at rest)" else "") + " | " +
                    "opacity cursor centre ${"%.2f".format(shown)} stroke centre ${"%.2f".format(centre)}" +
                    (if (p.shapeId.isNotEmpty() || p.grain > 0.05f) " (shaped / grained: centre is texture)" else "") + "\n")
            }
            out.append("cursordab: worst size error ${"%.2f".format(worstSize * 100)}%, worst centre opacity error (plain round tips) " +
                "${"%.3f".format(worstOpacity)} ${if (worstSize < 0.01f && worstOpacity < 0.04f) "PASS" else "FAIL"}\n")
        } catch (t: Throwable) {
            Log.e(TAG, "cursordab failed", t)
            out.append("cursordab: FAIL ${t.javaClass.simpleName}: ${t.message}\n")
        } finally {
            TileStoreConfig.useSparseLayers = oldS
        }
        return out.toString()
    }

    private fun scoreImportTest(app: YumeApp): String {
        val out = StringBuilder()
        val dir = File(app.filesDir, "importtest")
        val files = dir.listFiles()?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return "importtest: no files in ${dir.absolutePath}\n"
        val all = ArrayList<com.yume.app.engine.BrushPreset>()
        for (f in files) {
            val t0 = System.nanoTime()
            val r = com.yume.app.engine.ForeignBrushes.import(f.readBytes(), f.name, app.cacheDir)
            val ms = (System.nanoTime() - t0) / 1e6
            out.append("importtest ${f.name}: ${r.presets.size} brush(es) in ${"%.0f".format(ms)} ms. ${r.note}\n")
            for (p in r.presets) {
                out.append("    ${p.name}: size ${p.size.toInt()} spacing ${"%.2f".format(p.spacing)} opacity ${"%.2f".format(p.opacity)} " +
                    "tip=${if (p.shapeId.isNotEmpty()) "yes" else "round"} texture=${if (p.grainId.isNotEmpty()) "yes ${"%.2f".format(p.grain)}" else "no"} " +
                    "rot ${p.rotation.toInt()} mode ${p.rotationMode}\n")
            }
            all.addAll(r.presets)
        }
        // Sheet: tip | texture | stroke.
        val cell = 200
        val rows = all.size
        val sheet = Bitmap.createBitmap(cell * 4, max(1, rows) * cell, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(sheet)
        c.drawColor(0xFF303030.toInt())
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        for ((k, p) in all.withIndex()) {
            val y = k * cell
            p.shapeId.takeIf { it.isNotEmpty() }?.let { com.yume.app.engine.BrushAssets.shape(it) }?.let {
                c.drawRect(4f, y + 4f, cell - 4f, y + cell - 4f, android.graphics.Paint().apply { color = 0xFFFFFFFF.toInt() })
                val tint = android.graphics.Paint(paint).apply { colorFilter = android.graphics.PorterDuffColorFilter(0xFF000000.toInt(), android.graphics.PorterDuff.Mode.SRC_IN) }
                c.drawBitmap(it, null, android.graphics.RectF(4f, y + 4f, cell - 4f, y + cell - 4f), tint)
            }
            p.grainId.takeIf { it.isNotEmpty() }?.let { com.yume.app.engine.BrushAssets.grain(it) }?.let {
                c.drawBitmap(it, null, android.graphics.RectF(cell + 4f, y + 4f, 2f * cell - 4f, y + cell - 4f), paint)
            }
            // A stroke with the brush, scaled to fit the cell.
            val w = cell * 2
            val engine = DrawingEngine(w, cell)
            engine.backgroundColor = 0xFFFFFFFF.toInt()
            val session = DrawingSession("it", "IT", engine, History())
            identityView(session, w, cell)
            session.presets = listOf(p.copy(size = 60f))
            session.activePresetId = p.id
            session.category = p.category
            session.tool = Tool.Brush
            session.assist = 0f
            session.quickShapeEnabled = false
            val l = engine.newLayer("S")
            engine.selectLayer(l.id)
            session.color = 0xFF202040.toInt()
            session.stroke((0..20).map { P(30f + it * 17f, cell / 2f + 40f * kotlin.math.sin(it / 3f), 0.4f + 0.6f * kotlin.math.sin(it / 20f * 3.14f)) })
            session.runCommands()
            engine.compositeAll()
            val flat = engine.flatten()
            c.drawBitmap(flat, 2f * cell, y.toFloat(), null)
            flat.recycle()
        }
        java.io.FileOutputStream(File(app.filesDir, "importtest.png")).use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        sheet.recycle()
        return out.toString()
    }

    private fun scoreLiftEnd(app: YumeApp): String {
        val out = StringBuilder()
        val w = 2400
        val h = 1200
        val oldS = TileStoreConfig.useSparseLayers
        TileStoreConfig.useSparseLayers = true
        try {
            val engine = DrawingEngine(w, h)
            engine.backgroundColor = 0xFFFFFFFF.toInt()
            val session = DrawingSession("le", "LE", engine, History())
            identityView(session, w, h)
            session.presets = com.yume.app.engine.BrushLibrary.defaults
            session.assist = 0.5f
            session.quickShapeEnabled = false
            val l = engine.newLayer("Ink")
            engine.selectLayer(l.id)
            val cases = listOf("studio-pen" to 120f, "marker" to 200f, "acr-round" to 300f)
            for ((k, c) in cases.withIndex()) {
                val (id, size) = c
                val p = session.presets.first { it.id == id }
                pick(session, p, p.category)
                session.runCommands()
                session.setBrushSize(size)
                session.updatePreset { it.copy(smoothing = 0.8f) }
                session.runCommands()
                session.color = 0xFF5540F0.toInt()
                val y = 250f + k * 350f
                val pts = ArrayList<P>()
                for (i in 0 until 26) pts.add(P(200f + i * 60f, y, 1f))
                // Lifting: still moving, pressure falling to nothing.
                val fall = floatArrayOf(0.7f, 0.4f, 0.15f, 0.03f)
                for ((j, pr) in fall.withIndex()) pts.add(P(200f + (26 + j) * 60f, y, pr))
                session.stroke(pts)
                session.runCommands()
                // Width of the ink across the stroke at x: rows with paint.
                fun widthAt(x: Int): Int {
                    var n = 0
                    for (yy in (y - 250f).toInt()..(y + 250f).toInt()) {
                        if (yy < 0 || yy >= h) continue
                        if ((l.tiles.getPixel(x, yy) ushr 24) > 128) n++
                    }
                    return n
                }
                var lastInk = 0
                for (x in 0 until w) if (widthAt(x) > 0) lastInk = x
                val mid = widthAt(900)
                val profile = (1..8).map { widthAt(lastInk - it * 25) }.reversed()
                out.append("liftend $id ${size.toInt()} px: mid width $mid, last ink x=$lastInk, width toward the end ${profile.joinToString(" ")} (every 25 px)\n")
            }
            engine.compositeAll()
            val flat = engine.flatten()
            java.io.FileOutputStream(File(app.filesDir, "liftend.png")).use { flat.compress(Bitmap.CompressFormat.PNG, 100, it) }
            flat.recycle()
        } catch (t: Throwable) {
            Log.e(TAG, "liftend failed", t)
            out.append("liftend: FAIL ${t.javaClass.simpleName}: ${t.message}\n")
        } finally {
            TileStoreConfig.useSparseLayers = oldS
        }
        return out.toString()
    }

    /**
     * A big brush swept fast across a 4K canvas, as a finger does: per input
     * batch cost and the cost at lift, for a few brushes at 300 px (Studio Pen
     * also at spacing 0, as users set it).
     */
    private fun scoreBigStroke(size: Int = 4096, brushPx: Float = 300f, fast: Boolean = false): String {
        val out = StringBuilder()
        val oldS = TileStoreConfig.useSparseLayers
        TileStoreConfig.useSparseLayers = true
        try {
            val engine = DrawingEngine(size, size)
            engine.backgroundColor = 0xFFFFFFFF.toInt()
            val session = DrawingSession("bs", "BS", engine, History())
            identityView(session, size, size)
            session.presets = com.yume.app.engine.BrushLibrary.defaults.map {
                if (it.id == "studio-pen") it.copy(spacing = 0f) else it
            } + com.yume.app.engine.BrushLibrary.defaults.first { it.id == "studio-pen" }.copy(id = "studio-pen-5", name = "sp5")
            session.assist = 0.3f
            session.quickShapeEnabled = false
            val l = engine.newLayer("Paint")
            engine.selectLayer(l.id)
            engine.compositeAll()
            val cases = listOf("studio-pen", "studio-pen-5", "soft-brush", "pencil-6b", "airbrush")
            for ((ci, id) in cases.withIndex()) {
                val p = session.presets.first { it.id == id }
                pick(session, p, p.category)
                session.runCommands()
                session.setBrushSize(brushPx)
                session.runCommands()
                val y = 400f + ci * (size / 6f)
                var worst = 0.0
                var total = 0.0
                val n = 30
                var paintNs = 0L
                var flushNs = 0L
                session.fastDabsForTest = fast
                session.pushInput(InputEvent.Down(0, 100f, y, 0.6f, 0f, false, false))
                session.runInput()
                for (i in 1..n) {
                    val t0 = System.nanoTime()
                    // ~130 px per event batch: a quick finger across the screen.
                    val xs = FloatArray(4) { k -> 100f + (i - 1) * 130f + (k + 1) * 32.5f }
                    val ys = FloatArray(4) { k -> y + kotlin.math.sin((i * 4 + k) * 0.12f) * 120f }
                    session.pushInput(InputEvent.Move(0, xs, ys, FloatArray(4) { 0.6f }, 0f))
                    val tp = System.nanoTime()
                    session.runInput()
                    paintNs += System.nanoTime() - tp
                    val tf = System.nanoTime()
                    engine.flush()
                    flushNs += System.nanoTime() - tf
                    val ms = (System.nanoTime() - t0) / 1e6
                    worst = max(worst, ms)
                    total += ms
                }
                val e0 = System.nanoTime()
                session.pushInput(InputEvent.Up(0))
                session.runInput()
                session.runCommands()
                engine.flush()
                val endMs = (System.nanoTime() - e0) / 1e6
                val pz = session.painterForDebug(); out.append("  tips hit ${pz.tipHits} miss ${pz.tipMisses} skip ${pz.tipSkips}\n"); pz.tipHits = 0; pz.tipMisses = 0; pz.tipSkips = 0
                out.append("bigstroke${if (fast) " FAST" else ""} $id ${brushPx.toInt()}px at $size spacing ${p.spacing}: per batch avg ${"%.1f".format(total / n)} ms worst ${"%.1f".format(worst)} ms, lift ${"%.0f".format(endMs)} ms (paint ${paintNs / 1_000_000 / n} + composite ${flushNs / 1_000_000 / n} ms per batch)\n")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "bigstroke failed", t)
            out.append("bigstroke: FAIL ${t.javaClass.simpleName}: ${t.message}\n")
        } finally {
            TileStoreConfig.useSparseLayers = oldS
        }
        return out.toString()
    }

    private fun scoreSmudgePerf(): String {
        val out = StringBuilder()
        val size = 4096
        val oldS = TileStoreConfig.useSparseLayers
        TileStoreConfig.useSparseLayers = true
        try {
            val engine = DrawingEngine(size, size)
            engine.backgroundColor = 0xFFFFFFFF.toInt()
            val session = DrawingSession("sp", "SP", engine, History())
            identityView(session, size, size)
            session.presets = com.yume.app.engine.BrushLibrary.defaults
            session.assist = 0f
            session.quickShapeEnabled = false
            val l = engine.newLayer("Paint")
            engine.selectLayer(l.id)
            val noise = (0 until 4).map { noiseTile(900 + it, 256, opaque = true) }
            for (ty in 0 until 16) for (tx in 0 until 16) {
                l.tiles.writeRect(Rect(tx * 256, ty * 256, tx * 256 + 256, ty * 256 + 256), noise[(tx + ty * 3) % 4], Rect(0, 0, 256, 256))
            }
            l.touch()
            engine.compositeAll()
            val ids = session.presets.filter { it.category == BrushCategory.Smudge }.map { it.id }
            for (id in ids) {
                for (bs in floatArrayOf(40f, 120f, 300f)) {
                    val p = session.presets.first { it.id == id }
                    pick(session, p, p.category)
                    session.runCommands()
                    session.setBrushSize(bs)
                    session.runCommands()
                    val y = 300f + (ids.indexOf(id) * 3 + (if (bs < 50f) 0 else if (bs < 200f) 1 else 2)) * 170f
                    var worst = 0.0
                    var total = 0.0
                    val wb = com.yume.app.engine.WetBleed
                    wb.tPremul = 0; wb.tBlur = 0; wb.tMain = 0
                    val pp = session.painterForDebug()
                    pp.bleedRead = 0; pp.bleedWrite = 0; pp.bleedDabs = 0
                    session.pushInput(InputEvent.Down(0, 200f, y, 1f, 0f, true, false))
                    session.runInput()
                    for (i in 1..30) {
                        val t0 = System.nanoTime()
                        session.pushInput(InputEvent.Move(0, floatArrayOf(200f + i * 40f), floatArrayOf(y + (i % 2) * 6f), floatArrayOf(1f), 0f))
                        session.runInput()
                        engine.flush()
                        val ms = (System.nanoTime() - t0) / 1e6
                        worst = max(worst, ms)
                        total += ms
                    }
                    val e0 = System.nanoTime()
                    session.pushInput(InputEvent.Up(0))
                    session.runInput()
                    session.runCommands()
                    val endMs = (System.nanoTime() - e0) / 1e6
                    out.append("smudgeperf $id size ${bs.toInt()}: per frame avg ${"%.1f".format(total / 30)} ms worst ${"%.1f".format(worst)} ms, stroke end ${"%.0f".format(endMs)} ms")
                    if (pp.bleedDabs > 0) {
                        val d = pp.bleedDabs * 1e6
                        out.append(" | ${pp.bleedDabs} bleed dabs, per dab: read ${"%.2f".format(pp.bleedRead / d)} premul ${"%.2f".format(wb.tPremul / d)} " +
                            "blur ${"%.2f".format(wb.tBlur / d)} main ${"%.2f".format(wb.tMain / d)} write ${"%.2f".format(pp.bleedWrite / d)} ms")
                    }
                    out.append("\n")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "smudgeperf failed", t)
            out.append("smudgeperf: FAIL ${t.javaClass.simpleName}: ${t.message}\n")
        } finally {
            TileStoreConfig.useSparseLayers = oldS
        }
        return out.toString()
    }

    /**
     * Overlay layers: a coloured base and an Overlay layer of black / grey /
     * white bands over it; the flattened document and the on-screen composite
     * are compared with the overlay formula, dense and sparse, then a stroke
     * painted on the overlay layer.
     */
    private fun scoreOverlayTest(): String {
        val out = StringBuilder()
        val w = 768
        val h = 512
        val oldS = TileStoreConfig.useSparseLayers
        fun ov(sv: Int, dv: Int): Int {
            val a = sv / 255f
            val b = dv / 255f
            val r = if (b <= 0.5f) 2f * a * b else 1f - 2f * (1f - a) * (1f - b)
            return (r * 255f + 0.5f).toInt()
        }
        try {
            for (sparse in listOf(false, true)) {
                TileStoreConfig.useSparseLayers = sparse
                val engine = DrawingEngine(w, h)
                engine.backgroundColor = 0xFFFFFFFF.toInt()
                val session = DrawingSession("ov", "OV", engine, History())
                identityView(session, w, h)
                session.presets = com.yume.app.engine.BrushLibrary.defaults
                session.assist = 0f
                session.quickShapeEnabled = false
                val base = engine.newLayer("Base")
                val baseColor = 0xFF3C82C8.toInt()
                val bb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(baseColor) }
                base.tiles.writeRect(Rect(0, 0, w, h), bb, Rect(0, 0, w, h))
                base.touch()
                val top = engine.newLayer("Overlay")
                val tb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val tc = android.graphics.Canvas(tb)
                val band = Paint()
                band.color = 0xFF000000.toInt(); tc.drawRect(0f, 0f, 256f, h.toFloat(), band)
                band.color = 0xFF808080.toInt(); tc.drawRect(256f, 0f, 512f, h.toFloat(), band)
                band.color = 0xFFFFFFFF.toInt(); tc.drawRect(512f, 0f, w.toFloat(), h.toFloat(), band)
                top.tiles.writeRect(Rect(0, 0, w, h), tb, Rect(0, 0, w, h))
                top.touch()
                session.setLayerBlend(top.id, com.yume.app.engine.LayerBlend.Overlay)
                session.runCommands()
                engine.selectLayer(top.id)
                engine.compositeAll()
                engine.flush()
                fun expect(src: Int): Int {
                    val r = ov((src shr 16) and 0xFF, (baseColor shr 16) and 0xFF)
                    val g = ov((src shr 8) and 0xFF, (baseColor shr 8) and 0xFF)
                    val b = ov(src and 0xFF, baseColor and 0xFF)
                    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                fun hex(c: Int) = "%08X".format(c)
                val flat = engine.flatten()
                val screen = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val sc = android.graphics.Canvas(screen)
                if (engine.compositeIsSparse) engine.drawComposite(sc, Rect(0, 0, w, h))
                else sc.drawBitmap(engine.composite, 0f, 0f, null)
                for ((x, src) in listOf(128 to 0xFF000000.toInt(), 384 to 0xFF808080.toInt(), 640 to 0xFFFFFFFF.toInt())) {
                    val f = flat.getPixel(x, 200)
                    val v = screen.getPixel(x, 200)
                    val e = expect(src)
                    fun near(a: Int, b: Int) = (0..2).all { k -> kotlin.math.abs(((a shr (k * 8)) and 0xFF) - ((b shr (k * 8)) and 0xFF)) <= 3 }
                    out.append("overlaytest ${if (sparse) "sparse" else "dense"} band ${hex(src)}: flat ${hex(f)} screen ${hex(v)} expected ${hex(e)} ${if (near(f, e) && near(v, e)) "PASS" else "FAIL"}\n")
                }
                // A stroke painted on the overlay layer, white over the black band.
                val p = session.presets.first { it.id == "technical-pen" }
                pick(session, p, p.category)
                session.setBrushSize(30f)
                session.runCommands()
                session.color = 0xFFFFFFFF.toInt()
                session.stroke(listOf(P(40f, 300f), P(220f, 300f)))
                session.runCommands()
                engine.compositeAll()
                engine.flush()
                sc.drawColor(0, android.graphics.BlendMode.CLEAR)
                if (engine.compositeIsSparse) engine.drawComposite(sc, Rect(0, 0, w, h))
                else sc.drawBitmap(engine.composite, 0f, 0f, null)
                val flat2 = engine.flatten()
                val e2 = expect(0xFFFFFFFF.toInt())
                out.append("overlaytest ${if (sparse) "sparse" else "dense"} stroke: flat ${hex(flat2.getPixel(130, 300))} screen ${hex(screen.getPixel(130, 300))} expected ${hex(e2)}\n")
                // Clipped to a base that covers only the left half: Overlay
                // must change only the overlay's own pixels, and nothing where
                // the base is empty.
                val half = engine.newLayer("Half")
                val hb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(hb).drawRect(0f, 0f, 384f, h.toFloat(), Paint().apply { color = baseColor })
                half.tiles.writeRect(Rect(0, 0, w, h), hb, Rect(0, 0, w, h))
                half.touch()
                base.visible = false
                top.visible = false
                val clip = engine.newLayer("Clipped overlay")
                val cb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(cb).drawRect(0f, 0f, 200f, h.toFloat(), Paint().apply { color = 0xFFFFFFFF.toInt() })
                android.graphics.Canvas(cb).drawRect(500f, 0f, 700f, h.toFloat(), Paint().apply { color = 0xFFFFFFFF.toInt() })
                clip.tiles.writeRect(Rect(0, 0, w, h), cb, Rect(0, 0, w, h))
                clip.touch()
                clip.clipping = true
                session.setLayerBlend(clip.id, com.yume.app.engine.LayerBlend.Overlay)
                session.runCommands()
                engine.markDirtyAll()
                engine.compositeAll()
                engine.flush()
                val flat3 = engine.flatten()
                val inOv = flat3.getPixel(100, 100)
                val baseOnly = flat3.getPixel(300, 100)
                val outside = flat3.getPixel(600, 100)
                val ok = (0..2).all { k -> kotlin.math.abs(((inOv shr (k * 8)) and 0xFF) - ((e2 shr (k * 8)) and 0xFF)) <= 3 }
                out.append("overlaytest ${if (sparse) "sparse" else "dense"} clipped: overlay ${hex(inOv)} (expected ${hex(e2)}) base ${hex(baseOnly)} (expected ${hex(baseColor)}) outside ${hex(outside)} (expected FFFFFFFF) " +
                    (if (ok && baseOnly == baseColor && outside == -1) "PASS" else "FAIL") + "\n")
                flat3.recycle()
                flat.recycle(); flat2.recycle(); screen.recycle()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "overlaytest failed", t)
            out.append("overlaytest: FAIL ${t.javaClass.simpleName}: ${t.message}\n")
        } finally {
            TileStoreConfig.useSparseLayers = oldS
        }
        return out.toString()
    }

    private fun scoreOpacityTest(): String {
        val out = StringBuilder()
        val w = 900
        val h = 700
        val oldS = TileStoreConfig.useSparseLayers
        val hashes = ArrayList<Int>()
        fun lum(c: Int) = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        try {
            for (sparse in listOf(false, true)) {
                TileStoreConfig.useSparseLayers = sparse
                val engine = DrawingEngine(w, h)
                engine.backgroundColor = 0xFFFFFFFF.toInt()
                val session = DrawingSession("op", "OP", engine, History())
                identityView(session, w, h)
                session.presets = com.yume.app.engine.BrushLibrary.defaults
                session.assist = 0f
                session.quickShapeEnabled = false
                val l = engine.newLayer("Paint")
                engine.selectLayer(l.id)
                var seed = 3
                fun use(id: String, size: Float) {
                    val p = session.presets.first { it.id == id }
                    pick(session, p, p.category)
                    session.setBrushSize(size)
                    session.runCommands()
                }
                // 1. Opacity cap.
                use("acr-round", 40f)
                session.setBrushOpacity(0.2f)
                session.runCommands()
                session.color = 0xFF000000.toInt()
                val zig = ArrayList<P>()
                for (k in 0 until 30) {
                    zig.add(P(60f + (k % 2) * 300f, 60f + k * 2f))
                }
                session.stroke(zig)
                session.runCommands()
                engine.compositeAll()
                var flat = engine.flatten()
                var px = IntArray(w * h)
                flat.getPixels(px, 0, w, 0, 0, w, h)
                var darkest = 255
                for (y in 40 until 160) for (x in 40 until 380) darkest = min(darkest, lum(px[y * w + x]))
                hashes.add(px.contentHashCode())
                flat.recycle()
                out.append("opacitytest ${if (sparse) "sparse" else "dense"}: 20% black, 30 passes -> darkest ${darkest}/255 " +
                    "(${"%.0f".format((255 - darkest) / 2.55)}% dark) ${if (darkest >= 196) "PASS" else "FAIL"}\n")
                // 2. Wet bleed does not darken.
                session.setBrushOpacity(1f)
                use("acr-thick", 40f)
                val cols = intArrayOf(0xFFD8452E.toInt(), 0xFFF2C230.toInt(), 0xFF2F6FB5.toInt())
                for (r in 0 until 9) {
                    session.color = cols[r % 3]
                    session.stroke(handLine(420f, 260f + r * 40f, 860f, 260f + r * 40f, seed++, 2f))
                }
                session.runCommands()
                fun meanLum(): Double {
                    engine.compositeAll()
                    val f = engine.flatten()
                    val q = IntArray(w * h)
                    f.getPixels(q, 0, w, 0, 0, w, h)
                    f.recycle()
                    var sum = 0L
                    var n = 0
                    for (y in 300 until 560) for (x in 480 until 800) { sum += lum(q[y * w + x]); n++ }
                    return sum.toDouble() / n
                }
                val before = meanLum()
                use("wc-wet-bleed", 90f)
                for (k in 0 until 40) {
                    val y = 290f + (k % 8) * 35f
                    session.stroke(handLine(440f, y, 840f, y + 20f, seed++, 6f))
                }
                session.runCommands()
                val after = meanLum()
                out.append("opacitytest ${if (sparse) "sparse" else "dense"}: wet bleed x40 mean luminance ${"%.1f".format(before)} -> ${"%.1f".format(after)} " +
                    "${if (after >= before - 6.0) "PASS" else "FAIL (darkens)"}\n")
            }
            out.append("opacitytest: dense=${"%08x".format(hashes[0])} sparse=${"%08x".format(hashes[1])} " +
                (if (hashes[0] == hashes[1]) "MATCH" else "MISMATCH") + "\n")
        } catch (t: Throwable) {
            Log.e(TAG, "opacitytest failed", t)
            out.append("opacitytest: FAIL ${t.javaClass.simpleName}: ${t.message}\n")
        } finally {
            TileStoreConfig.useSparseLayers = oldS
        }
        return out.toString()
    }

    /** Saves an empty 16K canvas (one empty layer), kept in the gallery for UI checks. */
    private fun makeEmpty16k(app: YumeApp): String {
        val size = 16384
        val oldS = TileStoreConfig.useSparseLayers
        TileStoreConfig.useSparseLayers = true
        val ps = app.projectStore
        val project = ps.createProject("test-16k-empty (delete me)", size, size, BG)
        var cache: TileCache? = null
        try {
            val am = app.getSystemService(android.app.ActivityManager::class.java)
            cache = TileCache.open(app.cacheDir, project.id, TileCache.defaultBudget(am))
            val engine = DrawingEngine(size, size)
            engine.backgroundColor = BG
            val session = DrawingSession("e16", "E16", engine, History())
            identityView(session, size, size)
            engine.ensureLayer()
            session.runCommands()
            ps.saveProject(project.id, session.snapshotNow())
            return "make16kempty: saved ${project.id} layers=${engine.layers.size}\n"
        } finally {
            cache?.close()
            TileStoreConfig.useSparseLayers = oldS
        }
    }

    /** Saves an 8K canvas with strokes, kept in the gallery for UI checks. */
    private fun makeTest8k(app: YumeApp): String {
        val size = 8192
        val oldS = TileStoreConfig.useSparseLayers
        TileStoreConfig.useSparseLayers = true
        val ps = app.projectStore
        val project = ps.createProject("test-8k-motion (delete me)", size, size, BG)
        var cache: TileCache? = null
        try {
            val am = app.getSystemService(android.app.ActivityManager::class.java)
            cache = TileCache.open(app.cacheDir, project.id, TileCache.defaultBudget(am))
            val engine = DrawingEngine(size, size)
            engine.backgroundColor = BG
            val session = DrawingSession("t8", "T8", engine, History())
            identityView(session, size, size)
            session.presets = presets
            session.activePresetId = hard.id
            session.assist = 0f
            session.quickShapeEnabled = false
            session.category = BrushCategory.Brush
            val l = engine.layers.first()
            engine.selectLayer(l.id)
            session.color = 0xFF202020.toInt()
            for (k in 0 until 6) {
                val y = 1000f + k * 1200f
                session.stroke(listOf(P(800f, y), P(4000f, y + 700f), P(7400f, y)))
            }
            session.color = 0xFFCC3344.toInt()
            for (k in 0 until 6) {
                val x = 1000f + k * 1200f
                session.stroke(listOf(P(x, 600f), P(x + 500f, 4000f), P(x, 7600f)))
            }
            session.runCommands()
            ps.saveProject(project.id, session.snapshotNow())
            return "make8k: saved ${project.id}\n"
        } finally {
            cache?.close()
            TileStoreConfig.useSparseLayers = oldS
        }
    }

    /**
     * The live GPU preview of sharpen / motion blur / blur at 8K against the
     * CPU bake, scaled to the preview's size: mean difference, and PNGs of
     * both in files/ for a look.
     */
    private fun scoreMotion8k(app: YumeApp): String {
        val out = StringBuilder()
        val size = 8192
        val oldS = TileStoreConfig.useSparseLayers
        val oldT = TileStoreConfig.tileSize
        TileStoreConfig.useSparseLayers = true
        TileStoreConfig.tileSize = 256
        var cache: TileCache? = null
        val ps = app.projectStore
        val project = ps.createProject("harness-motion8k", size, size, BG)
        try {
            val am = app.getSystemService(android.app.ActivityManager::class.java)
            cache = TileCache.open(app.cacheDir, project.id, TileCache.defaultBudget(am))
            val engine = DrawingEngine(size, size)
            engine.backgroundColor = BG
            val session = DrawingSession("m8", "M8", engine, History())
            identityView(session, size, size)
            session.presets = presets
            session.activePresetId = hard.id
            session.assist = 0f
            session.quickShapeEnabled = false
            session.category = BrushCategory.Brush
            // A picture imported the way the editor does (one big draw).
            val pic = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888)
            run {
                val pc = android.graphics.Canvas(pic)
                val pp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                pp.shader = android.graphics.LinearGradient(0f, 0f, 1600f, 1000f, 0xFF88AACC.toInt(), 0xFF223344.toInt(), android.graphics.Shader.TileMode.CLAMP)
                pc.drawRect(0f, 0f, 1600f, 1000f, pp)
                pp.shader = null
                val rnd = java.util.Random(3)
                for (i in 0 until 60) {
                    pp.color = rnd.nextInt() or 0xFF000000.toInt()
                    pc.drawCircle(rnd.nextFloat() * 1600f, rnd.nextFloat() * 1000f, 20f + rnd.nextFloat() * 90f, pp)
                }
            }
            session.addImageLayer(pic, "Image")
            session.runCommands()
            val l = engine.activeLayer!!
            for ((kind, angle) in listOf(
                com.yume.app.engine.AdjustmentKind.MotionBlur to -5f,
                com.yume.app.engine.AdjustmentKind.MotionBlur to 0f,
                com.yume.app.engine.AdjustmentKind.MotionBlur to 45f,
                com.yume.app.engine.AdjustmentKind.Sharpen to 0f
            )) {
                session.chooseMotionAngle(angle)
                session.previewAdjustment(kind, 0.2f)
                session.runCommands()
                session.consumePendingPreview()
                val proxy = engine.preview?.proxy
                if (proxy == null) {
                    out.append("motion8k ${kind.label} $angle: NO PREVIEW\n")
                    session.cancelAdjustment(); session.runCommands()
                    continue
                }
                val pv = proxy.copy(Bitmap.Config.ARGB_8888, false)
                session.commitAdjustment()
                session.runCommands()
                val small = Bitmap.createBitmap(pv.width, pv.height, Bitmap.Config.ARGB_8888)
                val sc = android.graphics.Canvas(small)
                sc.scale(pv.width.toFloat() / size, pv.height.toFloat() / size)
                l.tiles.drawInto(sc, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                var sum = 0L
                var cnt = 0L
                var opaque = 0L
                val ra = IntArray(pv.width)
                val rb = IntArray(pv.width)
                for (y in 0 until pv.height step 3) {
                    pv.getPixels(ra, 0, pv.width, 0, y, pv.width, 1)
                    small.getPixels(rb, 0, pv.width, 0, y, pv.width, 1)
                    for (x in 0 until pv.width) {
                        if ((ra[x] ushr 24) > 0) opaque++
                        for (sh in intArrayOf(0, 8, 16, 24)) sum += kotlin.math.abs(((ra[x] ushr sh) and 255) - ((rb[x] ushr sh) and 255))
                        cnt += 4
                    }
                }
                val tag = "${kind.name.lowercase()}_${angle.toInt()}"
                java.io.FileOutputStream(File(app.filesDir, "m8_${tag}_gpu.png")).use { pv.compress(Bitmap.CompressFormat.PNG, 100, it) }
                java.io.FileOutputStream(File(app.filesDir, "m8_${tag}_cpu.png")).use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
                out.append("motion8k ${kind.label} $angle: preview ${pv.width}x${pv.height} meanDiff=${"%.2f".format(sum.toDouble() / cnt)}/255 coveredPx=$opaque\n")
                pv.recycle()
                small.recycle()
                session.undo()
                session.runCommands()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "motion8k failed", t)
            out.append("motion8k: FAIL ${t.javaClass.simpleName}: ${t.message}\n")
        } finally {
            cache?.close()
            runCatching { ps.deleteProject(project.id) }
            TileStoreConfig.useSparseLayers = oldS
            TileStoreConfig.tileSize = oldT
        }
        return out.toString()
    }

    /**
     * GPU filters against the CPU ones on a 4096 x 4096 painted proxy (the
     * blur preview's size for a 16K canvas): time and mean difference.
     */
    private fun scoreGpu(): String {
        val out = StringBuilder()
        if (android.os.Build.VERSION.SDK_INT < 33) return "gpu: needs Android 13\n"
        val n = 4096
        val src = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(src)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val rnd = java.util.Random(7)
        for (i in 0 until 400) {
            p.color = rnd.nextInt() or 0xFF000000.toInt()
            c.drawCircle(rnd.nextFloat() * n, rnd.nextFloat() * n, 20f + rnd.nextFloat() * 200f, p)
        }
        fun diff(a: Bitmap, b: Bitmap): Double {
            val w = a.width
            val pa = IntArray(w)
            val pb = IntArray(w)
            var sum = 0L
            var cnt = 0L
            var y = 0
            while (y < a.height) {
                a.getPixels(pa, 0, w, 0, y, w, 1)
                b.getPixels(pb, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    for (sh in intArrayOf(0, 8, 16, 24)) sum += kotlin.math.abs(((pa[x] ushr sh) and 255) - ((pb[x] ushr sh) and 255))
                    cnt += 4
                }
                y += 7
            }
            return sum.toDouble() / cnt
        }
        for (radius in intArrayOf(2, 6)) {
            val cpu = src.copy(Bitmap.Config.ARGB_8888, true)
            var t0 = System.nanoTime()
            com.yume.app.engine.Adjustments.blur(cpu, radius)
            val cpuMs = (System.nanoTime() - t0) / 1e6
            com.yume.app.engine.GpuFilter.apply(src, com.yume.app.engine.GpuFilter.blurEffect(radius, 1f))?.recycle()
            t0 = System.nanoTime()
            val gpu = com.yume.app.engine.GpuFilter.apply(src, com.yume.app.engine.GpuFilter.blurEffect(radius, 1f))
            val gpuMs = (System.nanoTime() - t0) / 1e6
            out.append("gpu blur r=$radius 4096^2: cpu ${"%.0f".format(cpuMs)}ms gpu ${"%.0f".format(gpuMs)}ms " +
                (if (gpu == null) "GPU UNAVAILABLE" else "meanDiff=${"%.2f".format(diff(cpu, gpu))}/255") + "\n")
            cpu.recycle()
            gpu?.recycle()
        }
        var t0 = System.nanoTime()
        val sh = com.yume.app.engine.GpuFilter.apply(src, com.yume.app.engine.GpuFilter.sharpenEffect(1.5f))
        out.append("gpu sharpen 4096^2: ${"%.0f".format((System.nanoTime() - t0) / 1e6)}ms ${if (sh == null) "UNAVAILABLE" else "ok"}\n")
        sh?.recycle()
        t0 = System.nanoTime()
        val mo = com.yume.app.engine.GpuFilter.apply(src, com.yume.app.engine.GpuFilter.motionEffect(12f, 30f))
        out.append("gpu motion 4096^2: ${"%.0f".format((System.nanoTime() - t0) / 1e6)}ms ${if (mo == null) "UNAVAILABLE" else "ok"}\n")
        mo?.recycle()
        com.yume.app.engine.GpuFilter.release()
        src.recycle()
        return out.toString()
    }

    /**
     * 16K with several layers, the way the editor uses it: fills, strokes, a
     * large textured area, new layers, blur, undo / redo, the autosave journal,
     * the history snapshot, a full save and a reopen. Java and native heap
     * peaks are sampled throughout, and every step reports its renderer-style
     * frames until the screen settles (worst frame = the hitch).
     */
    /**
     * The fast motion blur (parallel chunks, row / column runs, flat chunks
     * skipped) against the one-sample-at-a-time reference: identical bytes at
     * many angles and radii, with flat, empty and translucent areas and
     * canvas edges. Then the time of both on a 4096 px noise image.
     */
    private fun scoreMotionTest(): String {
        val sb = StringBuilder()
        val w = 700
        val h = 520
        val px = IntArray(w * h)
        val rnd = java.util.Random(7)
        for (y in 0 until h) for (x in 0 until w) {
            px[y * w + x] = when {
                x < 260 && y < 300 -> 0xFF2040A0.toInt()
                x > 450 && y > 300 -> 0
                else -> {
                    val a = if (rnd.nextInt(4) == 0) rnd.nextInt(256) else 255
                    (a shl 24) or rnd.nextInt(0x1000000)
                }
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        val base = IntArray(w * h)
        bmp.getPixels(base, 0, w, 0, 0, w, h)
        var cases = 0
        var bad = 0
        for (radius in intArrayOf(1, 3, 17, 48)) {
            for (angle in floatArrayOf(0f, 3f, 12f, 22.5f, 30f, 37f, 45f, 53f, 60f, 75f, 90f, 101f, 135f, 180f, 200f, 270f, 315f, 333f)) {
                for (bounds in listOf(Rect(0, 0, w, h), Rect(37, 11, 611, 499))) {
                    val a = base.copyOf()
                    val b = base.copyOf()
                    Kernels.motionBlur(w, h, bounds, radius, angle, Kernels.bitmapSource(bmp)) { r, o ->
                        o.getPixels(a, r.top * w + r.left, w, 0, 0, r.width(), r.height())
                    }
                    Kernels.motionBlurReference(w, h, bounds, radius, angle, Kernels.bitmapSource(bmp)) { r, o ->
                        o.getPixels(b, r.top * w + r.left, w, 0, 0, r.width(), r.height())
                    }
                    cases++
                    if (!a.contentEquals(b)) {
                        bad++
                        var first = -1
                        for (i in a.indices) if (a[i] != b[i]) { first = i; break }
                        sb.append("motiontest MISMATCH r=$radius angle=$angle bounds=$bounds at ${first % w},${first / w}: ${"%08x".format(a[first])} vs ${"%08x".format(b[first])}\n")
                    }
                }
            }
        }
        sb.append("motiontest: $cases cases, ${if (bad == 0) "all MATCH" else "$bad MISMATCH"}\n")
        bmp.recycle()
        val big = 4096
        val nb = Bitmap.createBitmap(big, big, Bitmap.Config.ARGB_8888)
        val row = IntArray(big)
        for (y in 0 until big) {
            for (x in 0 until big) row[x] = (0xFF shl 24) or rnd.nextInt(0x1000000)
            nb.setPixels(row, 0, big, 0, y, big, 1)
        }
        for (angle in floatArrayOf(0f, 20f, 45f, 90f)) {
            var t0 = System.nanoTime()
            Kernels.motionBlur(big, big, Rect(0, 0, big, big), 48, angle, Kernels.bitmapSource(nb)) { _, _ -> }
            val fast = (System.nanoTime() - t0) / 1e6
            var ref = -1.0
            if (angle == 0f || angle == 45f) {
                t0 = System.nanoTime()
                Kernels.motionBlurReference(big, big, Rect(0, 0, big, big), 48, angle, Kernels.bitmapSource(nb)) { _, _ -> }
                ref = (System.nanoTime() - t0) / 1e6
            }
            sb.append("motiontest 4096^2 r=48 angle=$angle: fast ${"%.0f".format(fast)} ms" +
                (if (ref >= 0) " reference ${"%.0f".format(ref)} ms" else "") + "\n")
        }
        nb.recycle()
        return sb.toString()
    }

    @Volatile private var profPhase = ""

    /**
     * How long a stroke / an undo takes to show in the zoomed-out view of an
     * empty 16K canvas, with frames run like the renderer's (non-blocking
     * composite, 6 ms of mip per frame, eviction at the safe point).
     */
    private fun scoreMipStale(app: YumeApp, tightMb: Int = 0, painted: Boolean = false): String {
        val out = StringBuilder()
        val size = 16384
        val oldS = TileStoreConfig.useSparseLayers
        val oldT = TileStoreConfig.tileSize
        TileStoreConfig.useSparseLayers = true
        TileStoreConfig.tileSize = 256
        val ps = app.projectStore
        val project = ps.createProject("harness-mipstale", size, size, BG)
        var cache: TileCache? = null
        fun line(t: String) { Log.i(TAG, t); out.append(t).append('\n') }
        try {
            val am = app.getSystemService(android.app.ActivityManager::class.java)
            cache = TileCache.open(app.cacheDir, project.id, TileCache.defaultBudget(am))
            val c0 = cache!!
            if (tightMb > 0) {
                c0.budgetBytes = tightMb * 1024L * 1024L
                line("mipstale: tile cache squeezed to $tightMb MB (memory pressure)")
            }
            val engine = DrawingEngine(size, size)
            engine.backgroundColor = BG
            val session = DrawingSession("ms", "MS", engine, History())
            session.history.spill = com.yume.app.engine.HistorySpill(File(c0.dir, "history.spill"))
            identityView(session, size, size)
            session.presets = presets
            session.activePresetId = hard.id
            session.assist = 0f
            session.quickShapeEnabled = false
            session.category = BrushCategory.Brush
            if (painted) {
                // A painting underneath: a whole 16K layer of texture, so every
                // composite tile is real work and most of them live on disk.
                val under = engine.newLayer("Painting")
                val noise = (0 until 4).map { noiseTile(700 + it, 256, opaque = true) }
                val t0 = System.nanoTime()
                for (ty in 0 until 64) for (tx in 0 until 64) {
                    under.tiles.writeRect(Rect(tx * 256, ty * 256, tx * 256 + 256, ty * 256 + 256), noise[(tx * 3 + ty) % 4], Rect(0, 0, 256, 256))
                    if ((tx + ty * 64) % 64 == 0) c0.safePoint()
                }
                under.touch()
                c0.safePoint()
                line("mipstale: painted 4096 tiles in ${"%.0f".format((System.nanoTime() - t0) / 1e6)} ms")
            }
            val l = engine.newLayer("Layer 1")
            engine.selectLayer(l.id)
            session.chooseTool(Tool.Brush)
            session.runCommands()
            session.color = 0xFF101010.toInt()
            session.setBrushSize(40f)
            session.runCommands()
            var zoomedOut = true
            var mipMs = 0.0
            var worstMs = 0.0
            fun frame() {
                val f0 = System.nanoTime()
                c0.frameCompositeBudgetNs = 12_000_000L
                session.runCommands()
                session.runInput()
                session.tickFrame()
                c0.adoptPrefetched()
                c0.blockingLoads = false
                try { engine.flush() } finally { c0.blockingLoads = true }
                if (zoomedOut) {
                    engine.priorityRect.set(0, 0, size, size)
                    engine.enableMip()
                    val m0 = System.nanoTime()
                    if (engine.hasMipDirty) engine.updateMip(6_000_000L)
                    mipMs += (System.nanoTime() - m0) / 1e6
                } else {
                    engine.priorityRect.set(7000, 7000, 9000, 9000)
                }
                c0.evictIfNeeded()
                worstMs = max(worstMs, (System.nanoTime() - f0) / 1e6)
            }
            // The canvas as opened: everything composited, mip built.
            val o0 = System.nanoTime()
            engine.compositeAll()
            engine.enableMip()
            engine.updateMip()
            c0.safePoint()
            line("mipstale: open (composite + mip) ${"%.0f".format((System.nanoTime() - o0) / 1e6)} ms")
            for (i in 0 until 5) frame()
            fun mipAt(x: Int, y: Int): Int = engine.mipTiles!!.getPixel(x / engine.mipScale, y / engine.mipScale)
            fun compAt(x: Int, y: Int): Int = engine.compositeTiles!!.getPixel(x, y)
            fun waitFor(name: String, x: Int, y: Int, want: (Int) -> Boolean) {
                val t0 = System.nanoTime()
                var frames = 0
                mipMs = 0.0
                worstMs = 0.0
                var compFrames = -1
                while (frames < 4000) {
                    frame()
                    frames++
                    if (compFrames < 0 && (painted || want(compAt(x, y)))) {
                        if (!painted || !engine.hasDirty()) compFrames = frames
                    }
                    // Caught up: nothing left to composite or downscale (and,
                    // on the plain canvas, the probe shows the right thing).
                    if (!engine.hasDirty() && !engine.hasMipDirty && (painted || want(mipAt(x, y)))) break
                }
                line("mipstale $name: composite after $compFrames frames, zoomed-out view after $frames frames " +
                    "${"%.0f".format((System.nanoTime() - t0) / 1e6)} ms (mip ${"%.0f".format(mipMs)} ms, worst frame ${"%.1f".format(worstMs)} ms) " +
                    "mipDirty=${engine.hasMipDirty} pending=${engine.deferredRect()} " + if (frames >= 4000) "STALE" else "OK")
            }
            val ink = { c: Int -> ((c shr 16) and 0xFF) < 128 }
            val paper = { c: Int -> ((c shr 16) and 0xFF) > 200 }
            // 1. Zoomed out the whole time.
            val s0 = System.nanoTime()
            session.stroke(listOf(P(6000f, 6000f), P(8000f, 8200f), P(10000f, 8000f)))
            line("mipstale: stroke + end ${"%.0f".format((System.nanoTime() - s0) / 1e6)} ms")
            waitFor("stroke while zoomed out", 8000, 8200, ink)
            // 2. Undo it.
            session.undo()
            run {
                val u0 = System.nanoTime()
                session.runCommands()
                line("mipstale: undo itself ${"%.0f".format((System.nanoTime() - u0) / 1e6)} ms; then ${engine.mipDebug()} " +
                    "history top=${session.history.peekRedo()?.javaClass?.simpleName}")
            }
            waitFor("undo while zoomed out", 8000, 8200, paper)
            // 3. Stroke while zoomed in, then zoom out.
            zoomedOut = false
            session.stroke(listOf(P(7200f, 7200f), P(8000f, 8000f), P(8800f, 7600f)))
            for (i in 0 until 30) frame()
            zoomedOut = true
            waitFor("stroke zoomed in, then zoom out", 8000, 8000, ink)
            // 4. Strokes far apart, then undo both.
            session.stroke(listOf(P(1000f, 1000f), P(2000f, 1600f)))
            session.stroke(listOf(P(15000f, 15000f), P(15800f, 15600f)))
            waitFor("second far stroke", 15400, 15300, ink)
            session.undo()
            session.undo()
            waitFor("undo two far strokes", 15400, 15300, paper)
        } catch (t: Throwable) {
            Log.e(TAG, "mipstale failed", t)
            out.append("mipstale: FAIL ${t.javaClass.simpleName}: ${t.message}\n")
        } finally {
            cache?.close()
            runCatching { ps.deleteProject(project.id) }
            TileStoreConfig.useSparseLayers = oldS
            TileStoreConfig.tileSize = oldT
        }
        return out.toString()
    }

    private fun scoreStress16k(app: YumeApp, quick: Boolean = false, motion: Boolean = false): String {
        val sb = StringBuilder()
        val size = 16384
        val oldS = TileStoreConfig.useSparseLayers
        val oldT = TileStoreConfig.tileSize
        TileStoreConfig.useSparseLayers = true
        TileStoreConfig.tileSize = 256
        var cache: TileCache? = null
        val ps = app.projectStore
        val project = ps.createProject("harness-stress16k", size, size, BG)
        val rt = Runtime.getRuntime()
        val sampling = java.util.concurrent.atomic.AtomicBoolean(true)
        var javaPeak = 0L
        var nativePeak = 0L
        val dumped = java.util.concurrent.atomic.AtomicBoolean(false)
        val sampler = Thread {
            while (sampling.get()) {
                javaPeak = max(javaPeak, rt.totalMemory() - rt.freeMemory())
                val nh = android.os.Debug.getNativeHeapAllocatedSize()
                nativePeak = max(nativePeak, nh)
                // Who is allocating: every thread's stack, once, past 1.5 GB.
                if (nh > 1536L * 1024L * 1024L && !dumped.getAndSet(true)) {
                    for ((t, st) in Thread.getAllStackTraces()) {
                        if (st.isEmpty()) continue
                        Log.w(TAG, "spike ${nh shr 20}MB thread ${t.name}:\n" + st.take(18).joinToString("\n") { "    at $it" })
                    }
                }
                Thread.sleep(5)
            }
        }.apply { isDaemon = true; start() }
        fun ms(t0: Long) = "%.0f".format((System.nanoTime() - t0) / 1e6)
        fun mem() = "java=${(rt.totalMemory() - rt.freeMemory()) shr 20}MB (peak ${javaPeak shr 20}) native=${android.os.Debug.getNativeHeapAllocatedSize() shr 20}MB (peak ${nativePeak shr 20})"
        // Every line is logged as it happens, so a long run can be followed.
        val out = object {
            fun append(line: String): StringBuilder {
                Log.i(TAG, line.trimEnd())
                return sb.append(line)
            }
            override fun toString() = sb.toString()
        }
        try {
            val am = app.getSystemService(android.app.ActivityManager::class.java)
            cache = TileCache.open(app.cacheDir, project.id, TileCache.defaultBudget(am))
            val c0 = cache!!
            val engine = DrawingEngine(size, size)
            engine.backgroundColor = BG
            val session = DrawingSession("s16", "S16", engine, History())
            session.history.spill = com.yume.app.engine.HistorySpill(File(c0.dir, "history.spill"))
            identityView(session, size, size)
            session.presets = presets
            session.activePresetId = hard.id
            session.assist = 0f
            session.quickShapeEnabled = false
            session.category = BrushCategory.Brush
            fun settle(): String {
                var frames = 0
                var worst = 0L
                var wFlush = 0L
                var wMip = 0L
                var wEvict = 0L
                val f0 = System.nanoTime()
                while (engine.hasDirty() && frames < 100000) {
                    val s0 = System.nanoTime()
                    c0.adoptPrefetched()
                    c0.blockingLoads = false
                    profPhase = "flush"
                    try { engine.flush() } finally { c0.blockingLoads = true }
                    val s1 = System.nanoTime()
                    engine.enableMip()
                    profPhase = "mip"
                    if (engine.hasMipDirty) engine.updateMip(6_000_000L)
                    val s2 = System.nanoTime()
                    profPhase = "evict"
                    c0.evictIfNeeded()
                    profPhase = ""
                    val s3 = System.nanoTime()
                    frames++
                    worst = max(worst, s3 - s0)
                    wFlush = max(wFlush, s1 - s0)
                    wMip = max(wMip, s2 - s1)
                    wEvict = max(wEvict, s3 - s2)
                }
                return "settle ${ms(f0)}ms frames=$frames worst=${"%.1f".format(worst / 1e6)}ms " +
                    "(flush ${"%.0f".format(wFlush / 1e6)} mip ${"%.0f".format(wMip / 1e6)} evict ${"%.0f".format(wEvict / 1e6)})"
            }
            fun step(name: String, block: () -> Unit) {
                // Motion runs: sample where this thread spends its time.
                val me = Thread.currentThread()
                val samples = HashMap<String, Int>()
                val sampling2 = java.util.concurrent.atomic.AtomicBoolean(motion)
                var phase = "work"
                val prof = if (!motion) null else Thread {
                    while (sampling2.get()) {
                        val st = me.stackTrace
                        val key = phase + "/" + profPhase + ": " + st.filter { it.className.startsWith("com.yume") }
                            .take(6).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                        synchronized(samples) { samples[key] = (samples[key] ?: 0) + 1 }
                        Thread.sleep(100)
                    }
                }.apply { isDaemon = true; start() }
                val t0 = System.nanoTime()
                block()
                session.runCommands()
                val work = ms(t0)
                val atWork = "${mem()} cache=${c0.residentBytes() shr 20}MB capture=${com.yume.app.engine.TileCapture.liveBytes shr 20}MB pool=${com.yume.app.engine.TilePool.heldBytes shr 20}MB"
                phase = "settle"
                val st = settle()
                sampling2.set(false)
                prof?.join()
                out.append("stress16k $name: ${work}ms [after work: $atWork] + $st | ${mem()} cache=${c0.residentBytes() shr 20}MB\n")
                if (prof != null) {
                    for ((k, v) in samples.entries.sortedByDescending { it.value }.take(14)) {
                        out.append("    ${v / 10.0}s  $k\n")
                    }
                }
            }
            fun journal(name: String) {
                val t0 = System.nanoTime()
                val batch = session.collectJournal({ _, _ -> false }, 96L * 1024L * 1024L)
                out.append("stress16k journal after $name: ${ms(t0)}ms ${if (batch == null) "too big -> full save" else "${batch.changes.size} records"} | ${mem()}\n")
            }
            val base = engine.layers.first()
            engine.selectLayer(base.id)
            session.chooseTool(Tool.Fill)
            session.runCommands()
            session.fillLayerOnly = false
            session.color = 0xFF3366CC.toInt()
            step("fill base") { session.fillAt(100f, 100f) }
            journal("fill")
            step("new layer") { engine.newLayer("Strokes").also { engine.selectLayer(it.id) } }
            journal("new layer")
            session.chooseTool(Tool.Brush)
            session.runCommands()
            session.color = 0xFF101010.toInt()
            step("10 strokes") {
                for (k in 0 until 10) {
                    val y = 800f + k * 1500f
                    session.stroke(listOf(P(300f, y), P(8000f, y + 900f), P(16000f, y)))
                }
            }
            journal("strokes")
            val tex = engine.newLayer("Texture")
            engine.selectLayer(tex.id)
            val noise = (0 until 4).map { noiseTile(500 + it, 256, opaque = true) }
            step("textured quarter (1024 tiles)") {
                for (ty in 0 until 32) for (tx in 0 until 32) {
                    tex.tiles.writeRect(Rect(tx * 256, ty * 256, tx * 256 + 256, ty * 256 + 256), noise[(tx + ty) % 4], Rect(0, 0, 256, 256))
                    if ((tx + ty * 32) % 64 == 0) c0.safePoint()
                }
                tex.touch()
                engine.markDirty(Rect(0, 0, 8192, 8192))
            }
            journal("texture")
            if (motion) {
                // Texture over the rest of the canvas too: a whole 16K layer.
                step("texture rest (3072 tiles)") {
                    for (ty in 0 until 64) for (tx in 0 until 64) {
                        if (tx < 32 && ty < 32) continue
                        tex.tiles.writeRect(Rect(tx * 256, ty * 256, tx * 256 + 256, ty * 256 + 256), noise[(tx * 3 + ty) % 4], Rect(0, 0, 256, 256))
                        if ((tx + ty * 64) % 64 == 0) c0.safePoint()
                    }
                    tex.touch()
                    engine.markDirtyAll()
                }
                for (angle in floatArrayOf(0f, 45f)) {
                    session.chooseMotionAngle(angle)
                    session.runCommands()
                    engine.compositeAll()
                    val h0 = bandHash(engine.compositeTiles!!)
                    step("motion blur r48 ${angle}deg") {
                        session.previewAdjustment(com.yume.app.engine.AdjustmentKind.MotionBlur, 1f)
                        session.consumePendingPreview()
                        session.commitAdjustment()
                    }
                    step("undo motion") { session.undo() }
                    engine.compositeAll()
                    val h1 = bandHash(engine.compositeTiles!!)
                    out.append("stress16k undo motion restores: ${if (h0 == h1) "MATCH" else "MISMATCH"}\n")
                }
            }
            if (!quick) {
            engine.compositeAll()
            val hashPreBlur = bandHash(engine.compositeTiles!!)
            step("blur texture layer") {
                session.previewAdjustment(com.yume.app.engine.AdjustmentKind.Blur, 0.2f)
                session.consumePendingPreview()
                session.commitAdjustment()
            }
            step("undo blur") { session.undo() }
            engine.compositeAll()
            val hashUndone = bandHash(engine.compositeTiles!!)
            out.append("stress16k undo blur restores: ${if (hashUndone == hashPreBlur) "MATCH" else "MISMATCH"} streamed=${session.history.spill?.diskBytes?.shr(20)}MB on disk\n")
            step("redo blur") { session.redo() }
            }
            val d = engine.newLayer("Fill 2")
            engine.selectLayer(d.id)
            session.chooseTool(Tool.Fill)
            session.runCommands()
            session.color = 0x80FF8800.toInt()
            step("translucent fill on new layer") { session.fillAt(16000f, 16000f) }
            step("opacity change") { d.opacity = 0.5f; engine.markDirtyAll() }
            journal("all")
            var snap: com.yume.app.engine.PersistedHistory? = null
            var t0 = System.nanoTime()
            val th = Thread { snap = session.historyForDiskBlocking() }.apply { start() }
            while (th.isAlive) { session.runCommands(); Thread.sleep(2) }
            out.append("stress16k history snapshot: ${ms(t0)}ms entries=${snap?.entries?.size} | ${mem()}\n")
            t0 = System.nanoTime()
            snap?.let { ps.saveHistory(project.id, it) }
            out.append("stress16k history save: ${ms(t0)}ms | ${mem()}\n")
            engine.compositeAll()
            val hashBefore = bandHash(engine.compositeTiles!!)
            t0 = System.nanoTime()
            ps.saveProject(project.id, session.snapshotNow())
            out.append("stress16k save: ${ms(t0)}ms complete=${ps.lastSaveComplete} | ${mem()}\n")
            session.history.spill?.close()
            noise.forEach { it.recycle() }
            c0.close()
            cache = null
            rt.gc()
            t0 = System.nanoTime()
            val c1 = TileCache.open(app.cacheDir, project.id, TileCache.defaultBudget(am))
            cache = c1
            val loaded = ps.loadProject(project.id) ?: error("reload failed")
            val re = DrawingEngine(size, size)
            re.backgroundColor = loaded.background
            for (dl in loaded.layers) {
                val l = re.newLayer(name = dl.name, bitmap = dl.bitmap, backingFile = dl.file)
                l.adoptMask(dl.mask, dl.maskFile)
                l.opacity = dl.opacity
                l.blend = dl.blend
                l.visible = dl.visible
            }
            re.compositeAll()
            val hashAfter = bandHash(re.compositeTiles!!)
            out.append("stress16k reopen: ${ms(t0)}ms ${if (hashAfter == hashBefore) "MATCH" else "MISMATCH"} | ${mem()}\n")
        } catch (t: Throwable) {
            Log.e(TAG, "stress16k failed", t)
            out.append("stress16k: FAIL ${t.javaClass.simpleName}: ${t.message} | ${mem()}\n")
        } finally {
            sampling.set(false)
            cache?.close()
            runCatching { ps.deleteProject(project.id) }
            TileStoreConfig.useSparseLayers = oldS
            TileStoreConfig.tileSize = oldT
            rt.gc()
        }
        out.append("stress16k peaks: java=${javaPeak shr 20}MB native=${nativePeak shr 20}MB (Java limit ${rt.maxMemory() shr 20}MB)\n")
        return out.toString()
    }

    /**
     * Eyedropper after a held pen: a white soft stroke starts on black, the
     * hold cancels it and picks. The pick must see black (the cancelled dab
     * is gone), on the dense and the sparse store.
     */
    private fun scorePickFresh(): String {
        val res = StringBuilder("pick-fresh:")
        var ok = true
        for (sparse in listOf(false, true)) {
            val oldS = TileStoreConfig.useSparseLayers
            TileStoreConfig.useSparseLayers = sparse
            try {
                val size = 512
                val engine = DrawingEngine(size, size)
                engine.backgroundColor = 0xFFFFFFFF.toInt()
                val session = DrawingSession("pf", "PF", engine, History())
                identityView(session, size, size)
                session.presets = presets
                val l = engine.newLayer("Ink")
                engine.selectLayer(l.id)
                l.tiles.fillRect(Rect(0, 0, size, size), 0xFF000000.toInt())
                l.touch()
                engine.markDirtyAll()
                engine.flush()
                pick(session, soft, BrushCategory.Brush)
                session.runCommands()
                session.color = 0xFFFFFFFF.toInt()
                session.pushInput(InputEvent.Down(0, 256f, 256f, 0.6f, 0f, true, false))
                session.runInput()
                session.pushInput(InputEvent.Move(0, floatArrayOf(257f), floatArrayOf(256f), floatArrayOf(0.6f), 0f))
                session.runInput()
                // The frame that would have shown the dab, then the hold fires.
                engine.flush()
                session.pushInput(InputEvent.Move(0, floatArrayOf(257.5f), floatArrayOf(256f), floatArrayOf(0.6f), 0f))
                session.runInput()
                session.pushInput(InputEvent.CancelStroke)
                session.pushInput(InputEvent.PickStart(256f, 256f))
                session.runInput()
                val got = session.color
                val pass = got == 0xFF000000.toInt()
                if (!pass) ok = false
                res.append(" ${if (sparse) "sparse" else "dense"}=${"%08x".format(got)}")
            } finally {
                TileStoreConfig.useSparseLayers = oldS
            }
        }
        res.insert(0, if (ok) "PASS " else "FAIL ")
        return res.toString()
    }

    /**
     * Paint bucket and auto-select timings on an 8K, 4-layer document: a
     * whole-canvas fill from the composite, a layer-only refill, an enclosed
     * fill, a wand tap on the blank area, and undo / redo of the big fill.
     */
    private fun scoreFill8k(app: YumeApp): String {
        val out = StringBuilder()
        val size = 8192
        val ps = app.projectStore
        val oldS = TileStoreConfig.useSparseLayers
        val oldT = TileStoreConfig.tileSize
        TileStoreConfig.useSparseLayers = true
        TileStoreConfig.tileSize = 256
        val am = app.getSystemService(android.app.ActivityManager::class.java)
        val budget = TileCache.defaultBudget(am)
        var cache: TileCache? = null
        val project = ps.createProject("harness-fill8k", size, size, BG)
        fun ms(t0: Long) = "%.0f".format((System.nanoTime() - t0) / 1e6)
        try {
            cache = TileCache.open(app.cacheDir, project.id, budget)
            val c0 = cache!!
            val engine = DrawingEngine(size, size)
            engine.backgroundColor = BG
            val session = DrawingSession("f8k", "F8K", engine, History())
            session.history.spill = com.yume.app.engine.HistorySpill(File(c0.dir, "history.spill"))
            identityView(session, size, size)
            session.presets = presets
            session.activePresetId = hard.id
            session.assist = 0f
            session.quickShapeEnabled = false
            session.category = BrushCategory.Brush
            for (i in 0 until 3) {
                val l = engine.newLayer("L$i")
                engine.selectLayer(l.id)
                session.color = 0xFF000000.toInt() or ((i * 0x135791) and 0xFFFFFF)
                for (s in 0 until 3) {
                    val y = 600f + ((i * 3 + s) * 811) % (size - 1200)
                    session.stroke(listOf(P(300f, y), P(size * 0.5f, y + 700f), P(size - 300f, y)))
                }
            }
            // An enclosed box on the line layer.
            session.color = 0xFF101010.toInt()
            session.stroke(listOf(P(1000f, 1000f), P(3000f, 1000f), P(3000f, 3000f), P(1000f, 3000f), P(1000f, 1000f)))
            val top = engine.newLayer("Fill")
            engine.selectLayer(top.id)
            engine.markDirtyAll()
            engine.flush()
            c0.safePoint()
            fun settle() {
                var g = 0
                while (engine.hasDirty() && g++ < 20000) {
                    c0.adoptPrefetched()
                    c0.blockingLoads = false
                    try { engine.flush() } finally { c0.blockingLoads = true }
                    c0.evictIfNeeded()
                }
                c0.safePoint()
            }
            fun op(name: String, block: () -> Unit) {
                val t0 = System.nanoTime()
                block()
                session.runCommands()
                val work = ms(t0)
                settle()
                out.append("fill-8k $name: ${work}ms (+composite ${ms(t0)}ms total) ${session.lastFillProfile}\n")
                session.lastFillProfile = ""
            }
            session.chooseTool(Tool.Fill)
            session.runCommands()
            session.fillExpand = 1
            session.fillTolerance = 30
            session.fillLayerOnly = false
            session.color = 0xFF3366CC.toInt()
            op("whole canvas (composite)") { session.fillAt(50f, 50f) }
            session.fillLayerOnly = true
            session.color = 0xFF55AA33.toInt()
            op("whole canvas refill (layer)") { session.fillAt(50f, 50f) }
            op("undo") { session.undo() }
            op("redo") { session.redo() }
            session.fillLayerOnly = false
            session.color = 0xFFEEBB22.toInt()
            op("enclosed 2000px box") { session.fillAt(2000f, 2000f) }
            session.chooseTool(Tool.Selection)
            session.runCommands()
            session.selectionShape = SelectionShape.Automatic
            op("wand blank area") {
                session.pushInput(InputEvent.Down(0, 100f, 8000f, 1f, 0f, true, false))
                session.pushInput(InputEvent.Up(0))
                session.runInput()
            }
            out.append("fill-8k memory: nativeHeap=${android.os.Debug.getNativeHeapAllocatedSize() shr 20}MB | ${c0.stats()}\n")
            session.history.spill?.close()
        } catch (t: Throwable) {
            Log.e(TAG, "fill8k failed", t)
            out.append("fill-8k: FAIL ${t.javaClass.simpleName}: ${t.message}\n")
        } finally {
            cache?.close()
            runCatching { ps.deleteProject(project.id) }
            TileStoreConfig.useSparseLayers = oldS
            TileStoreConfig.tileSize = oldT
            Runtime.getRuntime().gc()
        }
        return out.toString()
    }

    // ============================================================ Stage 4

    /**
     * Feature phases (Stage 4.1+), run on the dense oracle and on sparse
     * stores at tile 64 and 256 on both golden canvases. Every phase must hash
     * identically on all three stores; the dense lines are new oracle entries
     * (the 00..58 golden phases are not touched).
     */
    private fun runFeatures(width: Int, height: Int): List<String> {
        val sx = width / 2048f
        val sy = height / 2048f
        fun pts(vararg p: P) = p.map { P(it.x * sx, it.y * sy, it.pressure) }
        val engine = DrawingEngine(width, height)
        engine.backgroundColor = BG
        val session = DrawingSession("features", "F", engine, History())
        identityView(session, width, height)
        session.presets = presets
        session.activePresetId = hard.id
        session.assist = 0f
        session.quickShapeEnabled = false
        session.category = BrushCategory.Brush
        val phases = ArrayList<String>()
        fun phase(name: String) {
            session.runCommands()
            engine.flush()
            val sb = StringBuilder("feature=").append(name)
            engine.layers.forEachIndexed { i, l -> sb.append(" L").append(i).append('=').append(hashLayer(l)) }
            sb.append(" C=").append(compositeHash(engine))
            phases.add(sb.toString())
        }
        // Base: closed outline shapes on two layers so fills have regions.
        val base = engine.newLayer("Base")
        engine.selectLayer(base.id)
        session.color = 0xFF202020.toInt()
        session.stroke(pts(P(300f, 300f), P(1700f, 300f), P(1700f, 1700f), P(300f, 1700f), P(300f, 300f)))
        session.stroke(pts(P(300f, 1000f), P(1700f, 1000f)))
        session.stroke(pts(P(1000f, 300f), P(1000f, 1700f)))
        val top = engine.newLayer("Top")
        engine.selectLayer(top.id)
        session.color = 0xFFCC3344.toInt()
        session.activePresetId = soft.id
        session.stroke(pts(P(400f, 1300f), P(900f, 1600f)))
        session.activePresetId = hard.id
        phase("59-fill-base")

        session.chooseTool(Tool.Fill)
        session.fillLayerOnly = false
        session.fillExpand = 1
        session.fillTolerance = 30
        session.color = 0xFF3366CC.toInt()
        session.fillAt(600f * sx, 600f * sy)
        phase("60-fill-composite")

        engine.selectLayer(base.id)
        session.fillLayerOnly = true
        session.color = 0xFF55AA33.toInt()
        session.fillAt(1400f * sx, 1400f * sy)
        phase("61-fill-layer-only")

        session.fillLayerOnly = false
        session.fillExpand = 0
        session.fillTolerance = 80
        session.color = 0x8022AAEE.toInt()
        session.fillAt(50f * sx, 50f * sy)
        phase("62-fill-translucent-outside")

        engine.applySelection(
            android.graphics.Region(
                (1100 * sx).toInt(), (350 * sy).toInt(), (1650 * sx).toInt(), (950 * sy).toInt()
            ),
            SelectionOp.Replace
        )
        session.fillSelection = true
        session.color = 0xFFEEBB22.toInt()
        session.fillAt(1300f * sx, 600f * sy)
        phase("63-fill-selection")
        session.fillSelection = false
        session.color = 0xFF8844CC.toInt()
        session.fillAt(1300f * sx, 600f * sy)
        phase("64-fill-clipped-to-selection")
        engine.setSelection(null)

        session.toggleAlphaLock(top.id)
        engine.selectLayer(top.id)
        session.fillLayerOnly = true
        session.fillTolerance = 30
        session.color = 0xFF11CC88.toInt()
        session.fillAt(600f * sx, 1400f * sy)
        phase("65-fill-alpha-lock")
        session.toggleAlphaLock(top.id)
        session.runCommands()

        engine.addMask(base)
        engine.selectLayer(base.id)
        base.editMask = true
        session.fillLayerOnly = false
        session.fillAt(1400f * sx, 600f * sy)
        phase("66-fill-mask")
        base.editMask = false

        engine.selectLayer(top.id)
        session.color = 0xFFFF7700.toInt()
        session.colorDropAt(1400f * sx, 1400f * sy)
        phase("67-colordrop")

        session.undo()
        session.undo()
        phase("68-fill-undo2")
        session.redo()
        phase("69-fill-redo1")

        // Gradients (Stage 4.1): linear opaque-to-opaque, radial fade to
        // transparent, inside a selection, under alpha lock, on a mask, undo.
        session.chooseTool(Tool.Gradient)
        val g = engine.newLayer("Gradient")
        // Semi-transparent so later edits to the layers below still show in C.
        g.opacity = 0.6f
        engine.selectLayer(g.id)
        session.color = 0xFF2040C0.toInt()
        session.gradientKind = com.yume.app.engine.Gradients.Kind.Linear
        session.gradientSecond = 0xFFF0C030.toInt()
        session.applyGradient(100f * sx, 200f * sy, 1900f * sx, 1700f * sy)
        phase("70-gradient-linear")
        session.color = 0xFFCC2266.toInt()
        session.gradientKind = com.yume.app.engine.Gradients.Kind.Radial
        session.gradientSecond = 0x00CC2266
        session.applyGradient(1024f * sx, 1024f * sy, 1600f * sx, 1300f * sy)
        phase("71-gradient-radial-fade")
        engine.applySelection(
            android.graphics.Region((200 * sx).toInt(), (1200 * sy).toInt(), (900 * sx).toInt(), (1900 * sy).toInt()),
            SelectionOp.Replace
        )
        session.color = 0xFF10A050.toInt()
        session.gradientKind = com.yume.app.engine.Gradients.Kind.Linear
        session.gradientSecond = 0x8010A0F0.toInt()
        session.applyGradient(200f * sx, 1200f * sy, 900f * sx, 1900f * sy)
        phase("72-gradient-selection")
        engine.setSelection(null)
        session.toggleAlphaLock(top.id)
        engine.selectLayer(top.id)
        session.color = 0xFF000000.toInt()
        session.gradientSecond = 0xFFFFFFFF.toInt()
        session.applyGradient(0f, 0f, width.toFloat(), 0f)
        phase("73-gradient-alpha-lock")
        session.toggleAlphaLock(top.id)
        engine.selectLayer(base.id)
        base.editMask = true
        session.color = 0xFF000000.toInt()
        session.gradientSecond = 0x00000000
        session.applyGradient(0f, height * 0.5f, 0f, height.toFloat())
        phase("74-gradient-mask")
        base.editMask = false
        session.undo()
        phase("75-gradient-undo")
        session.chooseTool(Tool.Brush)

        // Sharpen and motion blur (Stage 4.1), exact per tile.
        engine.selectLayer(top.id)
        fun bake(kind: AdjustmentKind, amount: Float) {
            session.previewAdjustment(kind, amount)
            session.consumePendingPreview()
            session.commitAdjustment()
            session.runCommands()
        }
        bake(AdjustmentKind.Sharpen, 0.7f)
        phase("76-sharpen")
        session.motionAngle = 0f
        bake(AdjustmentKind.MotionBlur, 0.4f)
        phase("77-motion-blur-h")
        engine.selectLayer(base.id)
        session.motionAngle = 33f
        bake(AdjustmentKind.MotionBlur, 0.25f)
        phase("78-motion-blur-33deg")
        session.undo()
        phase("79-motion-blur-undo")

        // Tone adjustments (curves, levels, colour balance, gradient map):
        // one lookup table applied per tile, exact across stores.
        fun bakeTone(kind: AdjustmentKind, lut: com.yume.app.engine.ToneLut) {
            session.previewTone(kind, lut)
            session.consumePendingPreview()
            session.commitAdjustment()
            session.runCommands()
        }
        val ident = listOf(0f to 0f, 1f to 1f)
        engine.selectLayer(top.id)
        bakeTone(
            AdjustmentKind.Curves,
            com.yume.app.engine.ToneLut.curves(
                listOf(0f to 0f, 0.3f to 0.15f, 0.7f to 0.85f, 1f to 1f),
                ident, listOf(0f to 0.1f, 1f to 0.9f), ident
            )
        )
        phase("80-curves")
        bakeTone(AdjustmentKind.Levels, com.yume.app.engine.ToneLut.levels(0.1f, 0.85f, 1.4f, 0.05f, 0.95f))
        phase("81-levels")
        engine.selectLayer(base.id)
        bakeTone(
            AdjustmentKind.ColorBalance,
            com.yume.app.engine.ToneLut.colorBalance(floatArrayOf(0.3f, 0f, -0.2f, 0f, 0.25f, 0f, -0.2f, 0f, 0.3f))
        )
        phase("82-color-balance")
        bakeTone(
            AdjustmentKind.GradientMap,
            com.yume.app.engine.ToneLut.gradientMap(com.yume.app.engine.GradientMaps.presets[2].second)
        )
        phase("83-gradient-map")
        session.undo()
        phase("84-tone-undo")

        // Brush engine (stamps, grain textures, rotation, wet mix): all in the
        // dab-scratch resample pass, so every store must stamp identically.
        session.chooseTool(Tool.Brush)
        val oldRoot = com.yume.app.engine.BrushAssets.root
        com.yume.app.engine.BrushAssets.root = java.io.File(cacheRoot, "harness-brush").apply { mkdirs() }
        try {
            val tri = android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(tri).drawPath(android.graphics.Path().apply {
                moveTo(0f, 48f); lineTo(32f, 0f); lineTo(64f, 48f); close()
            }, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() })
            val noise = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
            val npx = IntArray(32 * 32) { i -> if ((i * 7919) % 5 < 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
            noise.setPixels(npx, 0, 32, 0, 0, 32, 32)
            val shapeId = com.yume.app.engine.BrushAssets.importShape(tri) ?: ""
            val grainId = com.yume.app.engine.BrushAssets.importGrain(noise) ?: ""
            val rotated = hard.copy(id = "h-rot", size = 44f, spacing = 0.18f, shapeId = shapeId, rotationMode = 1, rotation = 15f)
            val grained = soft.copy(id = "h-grainy", size = 60f, grain = 0.7f, grainId = grainId)
            val wet = hard.copy(id = "h-wet", size = 40f, wetMix = 0.6f)
            session.presets = presets + listOf(rotated, grained, wet)
            engine.selectLayer(top.id)
            session.color = 0xFF3355AA.toInt()
            session.activePresetId = rotated.id
            session.stroke(pts(P(250f, 400f), P(800f, 600f), P(1300f, 450f), P(1800f, 900f)))
            phase("85-shape-rotated")
            session.activePresetId = grained.id
            session.stroke(pts(P(300f, 1500f), P(1700f, 1300f)))
            phase("86-grain-texture")
            engine.selectLayer(base.id)
            session.color = 0xFFEEDD22.toInt()
            session.activePresetId = wet.id
            session.stroke(pts(P(200f, 1000f), P(1850f, 1000f)))
            phase("87-wet-mix")
            session.undo()
            phase("88-brush-undo")
            session.activePresetId = hard.id
        } finally {
            com.yume.app.engine.BrushAssets.root?.deleteRecursively()
            com.yume.app.engine.BrushAssets.root = oldRoot
        }
        return phases
    }

    private fun scoreFeatures(): String {
        val out = StringBuilder()
        for ((w, h) in listOf(2048 to 2048, 2000 to 1333)) {
            fun run(sparse: Boolean, tile: Int): List<String> {
                val oldS = TileStoreConfig.useSparseLayers
                val oldT = TileStoreConfig.tileSize
                TileStoreConfig.useSparseLayers = sparse
                TileStoreConfig.tileSize = tile
                return try {
                    runFeatures(w, h)
                } catch (t: Throwable) {
                    Log.e(TAG, "features failed", t)
                    listOf("FAILED ${t.javaClass.simpleName}: ${t.message}")
                } finally {
                    TileStoreConfig.useSparseLayers = oldS
                    TileStoreConfig.tileSize = oldT
                    Runtime.getRuntime().gc()
                }
            }
            val dense = run(false, 256)
            out.append("# features dense ${w}x$h\n")
            dense.forEach { out.append(it).append('\n') }
            for (tile in listOf(256, 64)) {
                val sp = run(true, tile)
                var bad = 0
                var first = ""
                for (i in 0 until max(dense.size, sp.size)) {
                    if (dense.getOrNull(i) != sp.getOrNull(i)) {
                        bad++
                        if (first.isEmpty()) first = "\n  dense:  ${dense.getOrNull(i)}\n  sparse: ${sp.getOrNull(i)}"
                    }
                }
                out.append("features sparse$tile-vs-dense ${w}x$h: ")
                    .append(if (bad == 0) "MATCH" else "MISMATCH")
                    .append(" (${dense.size} phases, $bad bad)").append(first).append('\n')
            }
        }
        return out.toString()
    }


    /**
     * Canvas crop / resize as a new document (Stage 4.1): dense vs sparse
     * (tile 64 / 256) must write identical layers; a crop must equal the source
     * region exactly; scaled copies must be deterministic across stores.
     */
    private fun scoreCanvasCopy(app: YumeApp): String {
        val out = StringBuilder()
        val ps = app.projectStore
        val w = 2000
        val h = 1333
        data class Spec(val label: String, val nw: Int, val nh: Int, val scale: Boolean, val ox: Int, val oy: Int)
        val specs = listOf(
            Spec("crop-center", 1100, 700, false, (w - 1100) / 2, (h - 700) / 2),
            Spec("extend", 2400, 1500, false, -200, -83),
            Spec("scale-down", 1000, 667, true, 0, 0),
            Spec("scale-up", 2600, 1733, true, 0, 0)
        )
        val results = HashMap<String, MutableList<String>>()
        var cropExact = true
        for ((sparse, tile) in listOf(false to 256, true to 256, true to 64)) {
            val oldS = TileStoreConfig.useSparseLayers
            val oldT = TileStoreConfig.tileSize
            TileStoreConfig.useSparseLayers = sparse
            TileStoreConfig.tileSize = tile
            try {
                val engine = DrawingEngine(w, h)
                engine.backgroundColor = BG
                val session = DrawingSession("copy", "Copy", engine, History())
                identityView(session, w, h)
                session.presets = presets
                session.activePresetId = soft.id
                session.assist = 0f
                session.quickShapeEnabled = false
                session.category = BrushCategory.Brush
                val a = engine.newLayer("A")
                engine.selectLayer(a.id)
                session.color = 0xFF2255CC.toInt()
                session.stroke(listOf(P(100f, 100f), P(1900f, 1200f), P(300f, 1250f)))
                val b = engine.newLayer("B")
                engine.selectLayer(b.id)
                session.color = 0x99DD4411.toInt()
                session.stroke(listOf(P(1800f, 150f), P(200f, 1100f)))
                engine.addMask(b)
                b.maskTiles!!.fillRect(Rect(600, 300, 1300, 900), 0xFF000000.toInt())
                val snap = session.snapshotNow()
                for (sp in specs) {
                    val id = ps.createCanvasCopy(snap, "copy-${sp.label}", sp.nw, sp.nh, sp.scale, sp.ox, sp.oy)
                    if (id == null) {
                        results.getOrPut(sp.label) { ArrayList() }.add("FAILED")
                        continue
                    }
                    val loaded = ps.loadProject(id)
                    val e2 = DrawingEngine(sp.nw, sp.nh)
                    loaded?.layers?.forEach { d ->
                        val l = e2.newLayer(name = d.name, bitmap = d.bitmap, backingFile = d.file)
                        l.adoptMask(d.mask, d.maskFile)
                    }
                    val line = e2.layers.joinToString(" ") { it.name + "=" + hashLayer(it) }
                    results.getOrPut(sp.label) { ArrayList() }.add(line)
                    if (sp.label == "crop-center" && !sparse) {
                        // Exactness: the copy's layer A equals the source region.
                        val want = a.tiles.readRect(Rect(sp.ox, sp.oy, sp.ox + sp.nw, sp.oy + sp.nh))
                        val got = e2.layers.first { it.name == "A" }.tiles.copyAll()
                        if (!want.sameAs(got)) cropExact = false
                        want.recycle()
                        got.recycle()
                    }
                    runCatching { ps.deleteProject(id) }
                }
            } finally {
                TileStoreConfig.useSparseLayers = oldS
                TileStoreConfig.tileSize = oldT
                Runtime.getRuntime().gc()
            }
        }
        var bad = 0
        for (sp in specs) {
            val r = results[sp.label] ?: continue
            val same = r.size == 3 && r.all { it == r[0] } && !r[0].startsWith("FAILED")
            if (!same) bad++
            out.append("canvas-copy ${sp.label} ${sp.nw}x${sp.nh}: ${if (same) "MATCH" else "MISMATCH"} ${r.firstOrNull()}\n")
        }
        out.append("canvas-copy crop exact=$cropExact verdict=${if (bad == 0 && cropExact) "PASS" else "FAIL"}\n")
        return out.toString()
    }

    private fun pct(sorted: LongArray, q: Double): String {
        if (sorted.isEmpty()) return "-"
        val i = ((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)
        return "%.2f".format(sorted[i] / 1e6)
    }

    private fun frameLine(label: String, times: List<Long>): String {
        val a = times.toLongArray().also { it.sort() }
        return "$label: frames=${a.size} p50=${pct(a, 0.5)} p95=${pct(a, 0.95)}" +
            " p99=${pct(a, 0.99)} max=${pct(a, 1.0)}ms"
    }

    /**
     * 4096^2 with 30 substantially painted layers (~50% of tiles with content)
     * under the default budget: create, paint, save, close, reopen and compare
     * the flattened composite hash; frame times while painting, while panning
     * and while recompositing over cold (evicted) tiles.
     */
    private fun scoreCacheBig(app: YumeApp, quick: Boolean = false): String {
        val out = StringBuilder()
        val size = 4096
        val layers = 30
        val ps = app.projectStore
        val oldS = TileStoreConfig.useSparseLayers
        val oldT = TileStoreConfig.tileSize
        TileStoreConfig.useSparseLayers = true
        TileStoreConfig.tileSize = 256
        val budget = TileCache.defaultBudget(app.getSystemService(android.app.ActivityManager::class.java))
        val project = ps.createProject("harness-cache-big", size, size, BG)
        var cache: TileCache? = null
        val rt = Runtime.getRuntime()
        var heapPeak = 0L
        fun sampleHeap() {
            val used = rt.totalMemory() - rt.freeMemory()
            if (used > heapPeak) heapPeak = used
        }
        try {
            cache = TileCache.open(app.cacheDir, project.id, budget)
            val c0 = cache
            val engine = DrawingEngine(size, size)
            engine.backgroundColor = BG
            // Opaque content: a PNG round trip is then lossless, so the reopen
            // hash compares storage, not premultiply rounding.
            val noise = (0 until 8).map { noiseTile(500 + it, 256, opaque = true) }
            val blends = listOf(LayerBlend.Normal, LayerBlend.Multiply, LayerBlend.Screen, LayerBlend.Normal)
            val tCreate = System.nanoTime()
            var contentTiles = 0
            for (i in 0 until layers) {
                val layer = engine.newLayer("L$i")
                layer.blend = blends[i % blends.size]
                layer.opacity = if (i % 3 == 0) 0.8f else 1f
                val rnd = Random(1000L + i)
                for (ty in 0 until 16) for (tx in 0 until 16) {
                    if (!rnd.nextBoolean()) continue
                    val r = Rect(tx * 256, ty * 256, tx * 256 + 256, ty * 256 + 256)
                    layer.tiles.writeRect(r, noise[(i + tx * 3 + ty) % noise.size], Rect(0, 0, 256, 256))
                    contentTiles++
                }
                layer.touch()
                c0.safePoint()
                sampleHeap()
            }
            engine.markDirtyAll()
            engine.flush()
            c0.safePoint()
            val createMs = (System.nanoTime() - tCreate) / 1e6
            out.append("cache-big ${size}x$size x$layers contentTiles=$contentTiles")
                .append(" (${contentTiles * 256L / 1024}MB of layer pixels) budget=${budget shr 20}MB")
                .append(" create+composite=${"%.0f".format(createMs)}ms\n")
            out.append("cache-big accounting after create: ")
                .append(c0.accounting().entries.joinToString(" ") { "${it.key}=${it.value shr 20}MB" })
                .append('\n')

            // Painting on the top layer, frames composited as the renderer does
            // (non-blocking for the other layers, safe point after each frame).
            val session = DrawingSession("big", "Big", engine, History())
            identityView(session, size, size)
            session.presets = presets
            session.activePresetId = hard.id
            session.assist = 0f
            session.quickShapeEnabled = false
            session.category = BrushCategory.Brush
            session.color = 0xFF3355AA.toInt()
            engine.selectLayer(engine.layers.last().id)
            c0.resetCounters()
            fun frame(block: () -> Unit): Long {
                val t0 = System.nanoTime()
                c0.adoptPrefetched()
                block()
                c0.blockingLoads = false
                try {
                    engine.flush()
                } finally {
                    c0.blockingLoads = true
                }
                c0.evictIfNeeded()
                sampleHeap()
                return System.nanoTime() - t0
            }
            val paintTimes = ArrayList<Long>()
            var strokeCachePeak = 0L
            session.pushInput(InputEvent.Down(0, 100f, size * 0.5f, 1f, 0f, true, false))
            session.runInput()
            val steps = 1200
            var i = 1
            while (i <= steps) {
                val end = min(i + 8, steps + 1)
                paintTimes.add(frame {
                    while (i < end) {
                        val t = i / steps.toFloat()
                        session.pushInput(
                            InputEvent.Move(
                                0, floatArrayOf(100f + t * (size - 200f)),
                                floatArrayOf(size * 0.5f + sin(t * 12f) * size * 0.3f),
                                floatArrayOf(1f), 0f
                            )
                        )
                        session.runInput()
                        i++
                    }
                    strokeCachePeak = max(strokeCachePeak, engine.strokeCacheBytes())
                })
            }
            session.pushInput(InputEvent.Up(0))
            session.runInput()
            out.append(frameLine("cache-big painting", paintTimes))
                .append(" syncLoads=${c0.syncLoads} syncRender=${c0.syncLoadsRender}")
                .append(" deferred=${c0.deferredTiles}(time=${c0.deferredTime}) evict=${c0.evictions}")
                .append(" strokeCachePeak=${strokeCachePeak shr 20}MB\n")

            // Cold recomposite: evict everything, then hide the top layer so the
            // whole composite must be rebuilt from cold tiles. Frames do not
            // block on disk; count frames until the composite is complete.
            run { var g = 0; while (engine.hasDirty() && g++ < 20000) frame { } }
            c0.evictAll()
            c0.resetCounters()
            val coldTimes = ArrayList<Long>()
            engine.layers.last().visible = false
            engine.markDirtyAll()
            val tCold = System.nanoTime()
            var guard = 0
            while (engine.hasDirty() && guard < 20000) {
                coldTimes.add(frame { })
                if (c0.pendingPrefetches() > 0) Thread.sleep(1)
                guard++
            }
            val coldMs = (System.nanoTime() - tCold) / 1e6
            val coldComplete = !engine.hasDirty()
            out.append(frameLine("cache-big cold-recomposite", coldTimes))
                .append(" done=$coldComplete in ${"%.0f".format(coldMs)}ms prefetch=${c0.prefetchLoads}")
                .append(" syncRender=${c0.syncLoadsRender} deferred=${c0.deferredTiles}\n")
            engine.layers.last().visible = true
            engine.markDirtyAll()
            run { var g = 0; while (engine.hasDirty() && g++ < 20000) frame { } }

            // Panning at 100% over the whole document: what the renderer does per
            // frame (viewport prefetch + composite blit) into a tablet-sized surface.
            c0.evictAll()
            c0.resetCounters()
            val surface = Bitmap.createBitmap(2560, 1600, Bitmap.Config.ARGB_8888)
            val sc = Canvas(surface)
            val panTimes = ArrayList<Long>()
            for (f in 0 until 240) {
                val x = ((f * 37) % (size - 2560)).coerceAtLeast(0)
                val y = ((f * 23) % (size - 1600)).coerceAtLeast(0)
                val vp = Rect(x, y, x + 2560, y + 1600)
                panTimes.add(frame {
                    sc.save()
                    sc.translate(-x.toFloat(), -y.toFloat())
                    engine.drawComposite(sc, vp)
                    sc.restore()
                    val m = Rect(max(0, x - 256), max(0, y - 256), min(size, x + 2816), min(size, y + 1856))
                    (engine.activeLayer?.tiles as? SparseTileStore)?.prefetchIn(m)
                })
            }
            surface.recycle()
            out.append(frameLine("cache-big panning", panTimes))
                .append(" prefetch=${c0.prefetchLoads} syncRender=${c0.syncLoadsRender}\n")

            if (quick) {
                out.append("cache-big quick: ${c0.stats()}\n")
                noise.forEach { it.recycle() }
                return out.toString()
            }
            // Save, close, reopen. The reference hash is taken after every edit.
            run { var g = 0; while (engine.hasDirty() && g++ < 20000) frame { } }
            engine.compositeAll()
            val hashBefore = compositeHash(engine)
            val tSave = System.nanoTime()
            ps.saveProject(project.id, session.snapshotNow())
            val saveMs = (System.nanoTime() - tSave) / 1e6
            val warn = ps.saveWarning
            ps.saveWarning = null
            sampleHeap()
            val closeStats = c0.stats()
            val closeAcc = c0.accounting().entries.joinToString(" ") { "${it.key}=${it.value shr 20}MB" }
            val peak = c0.peakResident
            c0.close()
            cache = null
            noise.forEach { it.recycle() }
            out.append("cache-big save=${"%.0f".format(saveMs)}ms warning=${warn ?: "none"} | $closeStats\n")
            out.append("cache-big accounting at close: $closeAcc peakResident=${peak shr 20}MB\n")
            Runtime.getRuntime().gc()

            val tOpen = System.nanoTime()
            val c1 = TileCache.open(app.cacheDir, project.id, budget)
            cache = c1
            val loaded = ps.loadProject(project.id) ?: error("reload failed")
            val rebuilt = DrawingEngine(size, size)
            rebuilt.backgroundColor = loaded.background
            for (d in loaded.layers) {
                val l = rebuilt.newLayer(name = d.name, bitmap = d.bitmap, backingFile = d.file)
                l.adoptMask(d.mask, d.maskFile)
                l.opacity = d.opacity
                l.blend = d.blend
                l.visible = d.visible
            }
            rebuilt.compositeAll()
            c1.safePoint()
            sampleHeap()
            val openMs = (System.nanoTime() - tOpen) / 1e6
            val hashAfter = compositeHash(rebuilt)
            val verdict = if (hashAfter == hashBefore) "PASS" else "FAIL"
            out.append("cache-big reopen: $verdict before=$hashBefore after=$hashAfter")
                .append(" open+composite=${"%.0f".format(openMs)}ms heapPeak=${heapPeak shr 20}MB | ${c1.stats()}\n")
        } catch (t: Throwable) {
            Log.e(TAG, "cache-big failed", t)
            out.append("cache-big: FAIL ${t.javaClass.simpleName}: ${t.message}\n")
        } finally {
            cache?.close()
            runCatching { ps.deleteProject(project.id) }
            TileStoreConfig.useSparseLayers = oldS
            TileStoreConfig.tileSize = oldT
            Runtime.getRuntime().gc()
        }
        return out.toString()
    }

    private fun scoreBench(sparse: Boolean = false): String {
        val oldS = TileStoreConfig.useSparseLayers
        val oldT = TileStoreConfig.tileSize
        TileStoreConfig.useSparseLayers = sparse
        try {
            val engine = DrawingEngine(W, H)
            engine.backgroundColor = BG
            val session = DrawingSession("bench", "Bench", engine, History())
            identityView(session, W, H)
            session.presets = presets
            session.activePresetId = hard.id
            session.assist = 0f
            session.category = BrushCategory.Brush

            val points = ArrayList<P>(2001)
            for (i in 0..2000) {
                val t = i / 2000f
                val x = 100f + t * (W - 200f)
                val y = 500f + sin(t * 18f) * 400f
                points.add(P(x, y, 1f))
            }
            session.stroke(listOf(P(100f, 100f)))

            StrokePainter.resetStats()
            val t0 = System.nanoTime()
            session.stroke(points)
            val paintMs = (System.nanoTime() - t0) / 1e6

            val t1 = System.nanoTime()
            engine.markDirtyAll()
            engine.flush()
            val flushMs = (System.nanoTime() - t1) / 1e6

            val label = if (!sparse) "bench-single" else "bench-sparse"
            val stats = if (sparse) {
                " dabs=${StrokePainter.statDabs} draws=${StrokePainter.statWriteDraws}" +
                    " writePx=${StrokePainter.statWritePx}"
            } else {
                ""
            }
            return "$label: stroke=%.2f ms for 2000 move events, full-flush=%.2f ms$stats"
                .format(paintMs, flushMs)
        } finally {
            TileStoreConfig.useSparseLayers = oldS
            TileStoreConfig.tileSize = oldT
            Runtime.getRuntime().gc()
        }
    }

    // ------------------------------------------------------------------ hashing

    private fun diffNote(a: Bitmap, b: Bitmap): String {
        if (a.width != b.width || a.height != b.height) {
            return "size ${a.width}x${a.height} vs ${b.width}x${b.height}"
        }
        val ra = IntArray(a.width)
        val rb = IntArray(b.width)
        var count = 0
        var l = Int.MAX_VALUE
        var t = Int.MAX_VALUE
        var r = -1
        var bo = -1
        for (y in 0 until a.height) {
            a.getPixels(ra, 0, a.width, 0, y, a.width, 1)
            b.getPixels(rb, 0, b.width, 0, y, b.width, 1)
            for (x in 0 until a.width) {
                if (ra[x] != rb[x]) {
                    count++
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    if (y > bo) bo = y
                }
            }
        }
        return if (count == 0) "identical" else "diff=$count bbox=($l,$t-${r + 1},${bo + 1})"
    }

    private fun hashLayer(layer: Layer): String {
        val shared = layer.tiles.sharedBitmap()
        val pixels = if (shared != null) {
            hashBitmap(shared)
        } else {
            val bmp = layer.tiles.copyAll()
            val h = hashBitmap(bmp)
            bmp.recycle()
            h
        }
        val maskStore = layer.maskTiles
        val mask = maskStore?.let { store ->
            val ms = store.sharedBitmap()
            if (ms != null) {
                hashBitmap(ms)
            } else {
                val bmp = store.copyAll()
                val h = hashBitmap(bmp)
                bmp.recycle()
                h
            }
        }
        return if (mask != null) "$pixels/$mask" else pixels
    }

    /** Composite hash, materialising (and releasing) a tiled composite. */
    private fun compositeHash(engine: DrawingEngine): String {
        val bmp = engine.compositeBitmap()
        val h = hashBitmap(bmp)
        if (engine.compositeIsSparse) bmp.recycle()
        return h
    }

    private fun hashBitmap(bitmap: Bitmap): String {
        val crc = CRC32()
        val row = IntArray(bitmap.width)
        val buffer = ByteBuffer.allocate(bitmap.width * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            buffer.clear()
            for (p in row) buffer.putInt(p)
            crc.update(buffer.array(), 0, buffer.position())
        }
        return crc.value.toString(16).padStart(8, '0')
    }

    // ------------------------------------------------------- golden run types

    private data class GoldenRun(
        val phases: List<String>,
        val diffs: List<PhaseDiff>,
        val classes: List<String>,
        val opaqueBad: Int = 0,
        val opaqueNotes: List<String> = emptyList()
    )

    private class PhaseDiff(
        val name: String,
        val maxChannel: Int,
        val diffPixels: Long,
        val samples: List<String>
    )

    private class DiffStat(
        val maxChannel: Int,
        val diffPixels: Long,
        val samples: List<String>
    )

    /**
     * Reference pixels for the tolerance comparison, losslessly deflated so the
     * strip-vs-dense diff is not polluted by a PNG round trip.
     */
    private fun writeRef(file: File, bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        DataOutputStream(
            BufferedOutputStream(
                DeflaterOutputStream(FileOutputStream(file), Deflater(Deflater.BEST_SPEED))
            )
        ).use { out ->
            out.writeInt(w)
            out.writeInt(h)
            for (v in px) out.writeInt(v)
        }
    }

    private fun readRef(file: File): IntArray? = runCatching {
        DataInputStream(BufferedInputStream(InflaterInputStream(FileInputStream(file)))).use { input ->
            val w = input.readInt()
            val h = input.readInt()
            val px = IntArray(w * h)
            for (i in px.indices) px[i] = input.readInt()
            px
        }
    }.getOrNull()

    /**
     * Compares the current dense composite pixels against the recorded v1
     * ("band") reference, per phase: max channel difference, differing pixel
     * count and an amplified PNG (difference x 32). Never writes over v1.
     */
    private fun v1v2Report(
        v1Dir: File,
        v2Dir: File,
        phases: List<String>,
        w: Int,
        h: Int,
        app: YumeApp
    ): String {
        val outDir = File(app.filesDir, "diffs_v2").apply {
            deleteRecursively()
            mkdirs()
        }
        val sb = StringBuilder()
        var worstCh = 0
        var worstPx = 0L
        var worstName = ""
        val total = w.toLong() * h.toLong()
        for (i in phases.indices) {
            val a = readRef(File(v1Dir, "p${i}_c.ref")) ?: continue
            val b = readRef(File(v2Dir, "p${i}_c.ref")) ?: continue
            if (a.size != b.size) continue
            val name = phases[i].removePrefix("phase=").substringBefore(' ')
            var maxCh = 0
            var px = 0L
            val hist = IntArray(6)
            val big = ArrayList<Int>()
            val amp = IntArray(a.size)
            for (k in a.indices) {
                val x = a[k]
                val y = b[k]
                if (x == y) continue
                px++
                var d = abs(((x ushr 24) and 0xFF) - ((y ushr 24) and 0xFF))
                var e = abs(((x ushr 16) and 0xFF) - ((y ushr 16) and 0xFF))
                if (e > d) d = e
                e = abs(((x ushr 8) and 0xFF) - ((y ushr 8) and 0xFF))
                if (e > d) d = e
                e = abs((x and 0xFF) - (y and 0xFF))
                if (e > d) d = e
                if (d > maxCh) maxCh = d
                when {
                    d <= 1 -> hist[0]++
                    d == 2 -> hist[1]++
                    d <= 4 -> hist[2]++
                    d <= 8 -> hist[3]++
                    d <= 16 -> hist[4]++
                    else -> {
                        hist[5]++
                        big.add(k)
                    }
                }
                val v = (d * 32).coerceAtMost(255)
                amp[k] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
            }

            // Where do the large differences sit? "Near an edge of the dragged
            // content" means the composite's colour range inside a 7x7 box around
            // the pixel exceeds 8 in some channel (alpha included). A range test
            // catches both a hard outline and the wide soft ramp of an airbrush,
            // which a single-pixel step test does not. Count the >16 pixels with
            // no such variation around them: those would be interior noise.
            var offEdge = 0
            if (big.isNotEmpty()) {
                for (k in big) {
                    val yy = k / w
                    val xx = k % w
                    val x0 = max(0, xx - 3)
                    val x1 = min(w - 1, xx + 3)
                    val y0 = max(0, yy - 3)
                    val y1 = min(h - 1, yy + 3)
                    var aMin = 255
                    var aMax = 0
                    var rMin = 255
                    var rMax = 0
                    var gMin = 255
                    var gMax = 0
                    var bMin = 255
                    var bMax = 0
                    for (ry in y0..y1) {
                        val base = ry * w
                        for (rx in x0..x1) {
                            val p = b[base + rx]
                            val aa = (p ushr 24) and 0xFF
                            val rr = (p ushr 16) and 0xFF
                            val gg = (p ushr 8) and 0xFF
                            val bb = p and 0xFF
                            if (aa < aMin) aMin = aa
                            if (aa > aMax) aMax = aa
                            if (rr < rMin) rMin = rr
                            if (rr > rMax) rMax = rr
                            if (gg < gMin) gMin = gg
                            if (gg > gMax) gMax = gg
                            if (bb < bMin) bMin = bb
                            if (bb > bMax) bMax = bb
                        }
                    }
                    val range = maxOf(aMax - aMin, rMax - rMin, gMax - gMin, bMax - bMin)
                    if (range <= 8) offEdge++
                }
            }

            if (px > 0L) {
                sb.append("v1v2 ").append(name)
                    .append(": maxch=").append(maxCh)
                    .append(" px=").append(px)
                    .append(" (").append("%.4f".format(px * 100.0 / total))
                    .append("%) hist[1|2|3-4|5-8|9-16|>16]=")
                    .append(hist.joinToString("|"))
                    .append(" hi16=").append(big.size)
                    .append(" offEdge=").append(offEdge)
                    .append('\n')
                runCatching {
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.setPixels(amp, 0, w, 0, 0, w, h)
                    FileOutputStream(File(outDir, "p$i-$name.png")).use {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bmp.recycle()
                }
            }
            if (maxCh > worstCh || (maxCh == worstCh && px > worstPx)) {
                worstCh = maxCh
                worstPx = px
                worstName = name
            }
        }
        sb.append("v1v2 worst: maxch=").append(worstCh)
            .append(" px=").append(worstPx)
            .append(" phase=").append(worstName).append('\n')
        return sb.toString()
    }

    /** Directory that receives the amplified diff PNGs (null = don't write). */
    private var diffOutDir: File? = null

    /**
     * Counts the differing pixels that sit within 2 px of a tile border versus in
     * the tile interior, and writes an amplified diff PNG (red = near a border,
     * yellow = interior, black = identical) named `p<phase>-t<tile>.png`.
     */
    private fun classifyDiff(
        bmp: Bitmap,
        ref: IntArray,
        dir: File,
        index: Int,
        tile: Int
    ): String {
        val w = bmp.width
        val h = bmp.height
        if (ref.size != w * h) return "n/a"
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var near = 0
        var interior = 0
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            val my = y % tile
            val dy = min(my, tile - my)
            for (x in 0 until w) {
                if (px[row + x] == ref[row + x]) continue
                val mx = x % tile
                val dx = min(mx, tile - mx)
                if (dx <= 2 || dy <= 2) {
                    near++
                    out[row + x] = 0xFFFF3030.toInt()
                } else {
                    interior++
                    out[row + x] = 0xFFFFFF40.toInt()
                }
            }
        }
        runCatching {
            val dbmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            dbmp.setPixels(out, 0, w, 0, 0, w, h)
            FileOutputStream(File(dir, "p$index-t$tile.png")).use {
                dbmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            dbmp.recycle()
        }
        return "near=$near interior=$interior"
    }

    private fun diffStats(bmp: Bitmap, ref: IntArray): DiffStat {
        val w = bmp.width
        val h = bmp.height
        if (ref.size != w * h) return DiffStat(255, Long.MAX_VALUE / 2, emptyList())
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var maxCh = 0
        var diff = 0L
        val samples = ArrayList<String>(6)
        for (i in px.indices) {
            val a = px[i]
            val b = ref[i]
            if (a != b) {
                diff++
                var d = abs(((a ushr 24) and 0xFF) - ((b ushr 24) and 0xFF))
                var e = abs(((a ushr 16) and 0xFF) - ((b ushr 16) and 0xFF))
                if (e > d) d = e
                e = abs(((a ushr 8) and 0xFF) - ((b ushr 8) and 0xFF))
                if (e > d) d = e
                e = abs((a and 0xFF) - (b and 0xFF))
                if (e > d) d = e
                if (d > maxCh) maxCh = d
                if (samples.size < 6) {
                    samples.add(
                        "(${i % w},${i / w})=${Integer.toHexString(a)}/${Integer.toHexString(b)}"
                    )
                }
            }
        }
        return DiffStat(maxCh, diff, samples)
    }

    /** The saved file for [base] ("layer_0"): tile file or PNG, whichever exists. */
    private fun savedLayerFile(dir: File, base: String): File {
        val t = File(dir, "$base.${com.yume.app.engine.TileFile.EXT}")
        return if (t.exists()) t else File(dir, "$base.png")
    }

    /** CRC of the pixels saved for [base], decoded from either format; null if none. */
    private fun savedPixelsHash(dir: File, base: String): String? {
        val f = savedLayerFile(dir, base)
        if (!f.exists()) return null
        val bmp = if (com.yume.app.engine.TileFile.isTileFile(f)) com.yume.app.engine.TileFile.decodeFull(f)
        else android.graphics.BitmapFactory.decodeFile(f.absolutePath, android.graphics.BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
        bmp ?: return "undecodable"
        return hashBitmap(bmp).also { bmp.recycle() }
    }

    private fun crcBytes(bytes: ByteArray): String {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value.toString(16).padStart(8, '0')
    }

    // ---------------------------------------------------- sparse persistence

    private class PersistResult(
        /** Per-layer CRC of pixels plus `/mask` CRC, in document order. */
        val layerHashes: List<String>,
        /** CRC of each saved layer_N.png and mask_N.png file's bytes. */
        val pngHashes: List<String>,
        /** Reloaded through the real load path and identical to the source. */
        val reloadOk: Boolean,
        /** The hidden layer came back without its pixels decoded. */
        val lazyHidden: Boolean
    )

    /**
     * Save/reload round trip for sparse layers.
     *
     * Builds the same document on dense and sparse stores (a mask with the
     * default-white semantics, a group, a hidden layer, a reorder, a delete and
     * a layer released to disk), saves it through ProjectStore, reloads it
     * through the real load path and compares layer, mask and PNG bytes.
     */
    private fun scoreSparsePersistence(app: YumeApp): String {
        val store = app.projectStore
        val notes = StringBuilder()
        var failures = 0
        var checks = 0
        for ((w, h) in listOf(2000 to 1333, 1500 to 1000)) {
            for (tile in intArrayOf(64, 256)) {
                val dense = persistOne(store, w, h, false, tile)
                val sparse = persistOne(store, w, h, true, tile)
                if (dense == null || sparse == null) {
                    failures++
                    notes.append("FAIL ${w}x$h tile=$tile (exception)\n")
                    continue
                }
                checks++
                if (dense.layerHashes != sparse.layerHashes) {
                    failures++
                    notes.append("FAIL ${w}x$h tile=$tile layer/mask hashes dense!=sparse\n")
                }
                if (dense.pngHashes != sparse.pngHashes) {
                    failures++
                    notes.append("FAIL ${w}x$h tile=$tile PNG bytes dense!=sparse\n")
                }
                if (!dense.reloadOk) {
                    failures++
                    notes.append("FAIL ${w}x$h tile=$tile dense reload differs\n")
                }
                if (!sparse.reloadOk) {
                    failures++
                    notes.append("FAIL ${w}x$h tile=$tile sparse reload differs\n")
                }
                if (!sparse.lazyHidden) {
                    failures++
                    notes.append("FAIL ${w}x$h tile=$tile hidden layer not lazy\n")
                }
            }
        }
        val verdict = if (failures == 0) "PASS" else "FAIL"
        return "sparse-persist: $verdict ($checks cases, $failures failures)\n$notes".trimEnd()
    }

    private fun persistOne(
        store: ProjectStore,
        w: Int,
        h: Int,
        sparse: Boolean,
        tile: Int
    ): PersistResult? {
        val oldS = TileStoreConfig.useSparseLayers
        val oldT = TileStoreConfig.tileSize
        TileStoreConfig.useSparseLayers = sparse
        TileStoreConfig.tileSize = tile
        val project = store.createProject("harness-persist-$sparse-$tile-$w", w, h, BG)
        return try {
            // Opaque pixels only: a PNG round trip is then lossless, so the
            // reload comparison cannot be polluted by premultiply rounding.
            fun pattern(seed: Int): Bitmap {
                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(b)
                c.drawColor(0xFF000000.toInt() or ((seed * 0x1F1F1F) and 0xFFFFFF))
                val p = Paint()
                p.color = 0xFFFFFFFF.toInt()
                c.drawRect(0f, 0f, w * 0.37f, h * 0.23f, p)
                p.color = 0xFF33CC66.toInt()
                c.drawRect(w * 0.5f, h * 0.5f, w * 0.5f + 64f, h * 0.5f + 256f, p)
                p.color = 0xFF2222AA.toInt()
                c.drawRect(7f, h - 130f, w - 9f, h - 7f, p)
                return b
            }

            val engine = DrawingEngine(w, h)
            engine.backgroundColor = BG
            val base = engine.newLayer("Base", bitmap = pattern(1))
            val masked = engine.newLayer("Masked", bitmap = pattern(2))
            val grouped = engine.newLayer("Grouped", bitmap = pattern(3))
            val hidden = engine.newLayer("Hidden", bitmap = pattern(4))
            val deleted = engine.newLayer("Deleted", bitmap = pattern(5))

            // Mask (default opaque white) with a black rectangle crossing borders.
            engine.addMask(masked)
            masked.maskTiles!!.fillRect(
                Rect(w / 4, h / 4, w / 2 + 7, h / 2 + 3),
                0xFF000000.toInt()
            )

            val group = engine.newGroup("G")
            group.opacity = 0.8f
            group.blend = LayerBlend.Multiply
            grouped.groupId = group.id
            grouped.opacity = 0.6f
            grouped.blend = LayerBlend.Screen

            hidden.visible = false

            // Reorder and delete before saving.
            engine.moveLayer(0, engine.layers.size - 1)
            engine.deleteLayer(deleted.id)

            val session = DrawingSession("persist", "P", engine, History())
            val expected = engine.layers.map { hashLayer(it) }

            // Release a layer to disk so the save must copy its backing file.
            val bytes = base.tiles.copyAll()
            val cache = store.writeLayerCache(project.id, base.id, bytes) ?: return null
            bytes.recycle()
            base.tiles.releaseToDisk(cache)

            store.saveProject(project.id, session.snapshotNow())

            val dir = store.projectDir(project.id)
            val pngHashes = ArrayList<String>()
            for (i in engine.layers.indices) {
                // Saved pixels, whatever the file format (sparse layers are
                // tile files now, dense ones PNG): decoded and hashed.
                pngHashes.add(savedPixelsHash(dir, "layer_$i") ?: "missing")
                savedPixelsHash(dir, "mask_$i")?.let { pngHashes.add(it) }
            }

            // Real load path: decode the project, rebuild layers as the editor does.
            val loaded = store.loadProject(project.id) ?: return null
            val rebuilt = DrawingEngine(w, h)
            for (d in loaded.layers) {
                val layer = rebuilt.newLayer(name = d.name, bitmap = d.bitmap, backingFile = d.file)
                layer.adoptMask(d.mask, d.maskFile)
                layer.opacity = d.opacity
                layer.blend = d.blend
                layer.visible = d.visible
                layer.locked = d.locked
                layer.clipping = d.clipping
                layer.maskEnabled = d.maskEnabled
                layer.groupId = d.groupId
            }
            val got = rebuilt.layers.map { hashLayer(it) }
            val hiddenData = loaded.layers.first { it.name == "Hidden" }
            PersistResult(
                layerHashes = expected,
                pngHashes = pngHashes,
                reloadOk = got == expected,
                lazyHidden = hiddenData.bitmap == null && hiddenData.file != null
            )
        } catch (t: Throwable) {
            Log.e(TAG, "persistence round trip failed", t)
            null
        } finally {
            runCatching { store.deleteProject(project.id) }
            TileStoreConfig.useSparseLayers = oldS
            TileStoreConfig.tileSize = oldT
            Runtime.getRuntime().gc()
        }
    }

    // ------------------------------------------------------------- composite

    /**
     * A composite rebuilt from only the dirty tiles must equal a cache-free
     * full recomposite, for every kind of edit the app performs between frames.
     */
    private fun scoreComposite(): String {
        val oldS = TileStoreConfig.useSparseLayers
        val oldT = TileStoreConfig.tileSize
        val notes = ArrayList<String>()
        var failures = 0
        var checks = 0
        try {
            for (tile in intArrayOf(256, 64)) {
                for ((w, h) in listOf(1000 to 700, 720 to 720)) {
                    TileStoreConfig.useSparseLayers = true
                    TileStoreConfig.tileSize = tile
                    val engine = DrawingEngine(w, h)
                    engine.backgroundColor = BG
                    val session = DrawingSession("comp", "C", engine, History())
                    identityView(session, w, h)
                    session.presets = presets
                    session.activePresetId = hard.id
                    session.assist = 0f
                    session.quickShapeEnabled = false
                    session.category = BrushCategory.Brush
                    session.color = 0xFF2266CC.toInt()

                    val a = engine.newLayer("A")
                    val b = engine.newLayer("B")
                    val c = engine.newLayer("C")
                    a.blend = LayerBlend.Multiply
                    b.opacity = 0.6f
                    c.blend = LayerBlend.Screen

                    fun verify(label: String) {
                        engine.flush()
                        val incremental = compositeHash(engine)
                        engine.compositeAll()
                        val fresh = compositeHash(engine)
                        checks++
                        if (incremental != fresh) {
                            failures++
                            notes.add("FAIL ${w}x$h tile=$tile $label")
                        }
                        val badOpaque = engine.verifyOpaqueFlags(true)
                        if (badOpaque != 0) {
                            failures++
                            notes.add("OPAQUE FAIL ${w}x$h tile=$tile $label stale=$badOpaque")
                        }
                    }

                    verify("empty")

                    // A partial edit: painting only dirties the stroke bounds.
                    engine.selectLayer(a.id)
                    session.stroke(listOf(P(50f, 50f), P(w * 0.8f, h * 0.5f)))
                    verify("stroke")

                    // Editing a layer that is not active.
                    engine.selectLayer(b.id)
                    session.stroke(listOf(P(30f, h * 0.7f), P(w * 0.6f, 60f)))
                    engine.selectLayer(a.id)
                    verify("stroke-other")

                    // Structural and property changes.
                    b.visible = false
                    engine.markDirtyAll()
                    verify("hidden")
                    b.visible = true
                    b.opacity = 0.35f
                    engine.markDirtyAll()
                    verify("opacity")
                    c.blend = LayerBlend.ColorDodge
                    engine.markDirtyAll()
                    verify("blend")
                    engine.moveLayer(0, 2)
                    engine.markDirtyAll()
                    verify("order")

                    // Mask, clip and group on the composite.
                    engine.addMask(a)
                    a.maskTiles!!.fillRect(Rect(40, 40, w / 2, h - 30), 0xFF000000.toInt())
                    engine.markDirtyAll()
                    verify("mask")
                    val g = engine.newGroup("G")
                    g.opacity = 0.5f
                    b.groupId = g.id
                    c.groupId = g.id
                    engine.markDirtyAll()
                    verify("group")
                    engine.selectLayer(c.id)
                    session.stroke(listOf(P(20f, h * 0.4f), P(w - 20f, h * 0.4f)))
                    verify("group-stroke")

                    // Solo and background round trip.
                    engine.setSoloLayer(b.id)
                    verify("solo")
                    engine.setSoloLayer(null)
                    verify("unsolo")

                    // Undo/redo across all of it.
                    repeat(3) {
                        session.undo()
                        session.runCommands()
                    }
                    verify("undo3")
                    session.redo()
                    session.runCommands()
                    verify("redo1")
                }
            }
        } finally {
            TileStoreConfig.useSparseLayers = oldS
            TileStoreConfig.tileSize = oldT
            Runtime.getRuntime().gc()
        }
        val verdict = if (failures == 0) "PASS" else "FAIL"
        val sb = StringBuilder("composite-invalidate: $verdict ($checks checks, $failures bad)")
        for (n in notes.take(6)) sb.append('\n').append(n)
        return sb.toString()
    }

    // -------------------------------------------------------------------- mip

    /**
     * The stroke-scoped below cache must be invisible: mid-stroke the composite
     * has to equal a full recomposite computed with the cache turned off, on a
     * stack that includes a group and mixed blend modes.
     */
    private fun scoreStrokeCache(): String =
        (0..2).joinToString("\n") { strokeCacheCase(it) }

    /**
     * [mode] 0: the active layer is on top (below cache only).
     * [mode] 1: three plain Normal layers above it (below and above caches).
     * [mode] 2: a Multiply layer above it, so the above cache must stand down.
     */
    private fun strokeCacheCase(mode: Int): String {
        val oldS = TileStoreConfig.useSparseLayers
        val oldT = TileStoreConfig.tileSize
        TileStoreConfig.useSparseLayers = true
        TileStoreConfig.tileSize = 256
        val w = 900
        val h = 700
        return try {
            val engine = DrawingEngine(w, h)
            engine.backgroundColor = BG
            val session = DrawingSession("sc", "S", engine, History())
            identityView(session, w, h)
            session.presets = presets
            session.activePresetId = soft.id
            session.assist = 0f
            session.quickShapeEnabled = false
            session.category = BrushCategory.Brush
            session.color = 0xFF3355AA.toInt()
            for (i in 1..5) {
                val l = engine.newLayer("L$i")
                l.blend = if (i % 2 == 0) LayerBlend.Multiply else LayerBlend.Normal
                l.tiles.fillRect(
                    Rect(0, 0, w, h * i / 6), 0xFF000000.toInt() or (i * 0x101010)
                )
            }
            val g = engine.newGroup("G")
            g.opacity = 0.8f
            g.blend = LayerBlend.Multiply
            engine.layers[1].groupId = g.id
            engine.layers[1].opacity = 1f
            engine.layers[2].groupId = g.id
            engine.layers[2].opacity = 1f
            engine.newLayer("Top")
            val activeLayer = engine.layers[engine.layers.size - 1]
            engine.selectLayer(activeLayer.id)
            if (mode >= 1) {
                val a1 = engine.newLayer("A1")
                val a2 = engine.newLayer("A2")
                val a3 = engine.newLayer("A3")
                if (mode == 2) a2.blend = LayerBlend.Multiply
                a1.tiles.fillRect(Rect(0, 0, w, h / 3), 0x80FFFFFF.toInt())
                a2.tiles.fillRect(Rect(0, h / 3, w, h * 2 / 3), 0x8000FF00.toInt())
                a3.tiles.fillRect(Rect(0, h * 2 / 3, w, h), 0x800000FF.toInt())
                engine.selectLayer(activeLayer.id)
            }
            engine.markDirtyAll()
            engine.compositeAll()

            // Manual stroke so the mid-stroke composite can be hashed while the
            // cache is live.
            session.pushInput(InputEvent.Down(0, 100f, 100f, 1f, 0f, true, false))
            session.runInput()
            var i = 0
            while (i < 60) {
                session.pushInput(
                    InputEvent.Move(
                        0, floatArrayOf(100f + i * 10f), floatArrayOf(100f + i * 7f),
                        floatArrayOf(1f), 0f
                    )
                )
                session.runInput()
                i++
            }
            engine.flush()
            val active = engine.strokeCacheActive
            val cached = compositeHash(engine)
            // Cache-free recomposite of exactly the same layer state.
            engine.endStrokeCache()
            engine.compositeAll()
            val fresh = compositeHash(engine)
            session.pushInput(InputEvent.Up(0))
            session.runInput()
            val ok = cached == fresh
            val badOpaque = engine.verifyOpaqueFlags(true)
            "stroke-cache[$mode]: ${if (ok && badOpaque == 0) "PASS" else "FAIL"} " +
                "active=$active opaqueStale=$badOpaque " +
                "cached=$cached fresh=$fresh bytes=${engine.strokeCacheBytes()}"
        } catch (t: Throwable) {
            "stroke-cache: FAIL ${t.javaClass.simpleName}"
        } finally {
            TileStoreConfig.useSparseLayers = oldS
            TileStoreConfig.tileSize = oldT
            Runtime.getRuntime().gc()
        }
    }

    /**
     * The mip must be a downscale of the composite, and a partial dirty update
     * must produce the same mip as a full rebuild.
     */
    private fun scoreMip(): String {
        val oldS = TileStoreConfig.useSparseLayers
        val oldT = TileStoreConfig.tileSize
        TileStoreConfig.useSparseLayers = true
        TileStoreConfig.tileSize = 256
        return try {
            val sb = StringBuilder("mip:")
            for ((w, h) in listOf(2048 to 2048, 1000 to 700)) {
                val engine = DrawingEngine(w, h)
                engine.backgroundColor = BG
                val session = DrawingSession("mip", "M", engine, History())
                identityView(session, w, h)
                session.presets = presets
                session.activePresetId = soft.id
                session.assist = 0f
                session.quickShapeEnabled = false
                session.category = BrushCategory.Brush
                val l0 = engine.newLayer("A")
                val l1 = engine.newLayer("B")
                session.color = 0xFF3366CC.toInt()
                session.stroke(listOf(P(40f, 40f), P(w * 0.9f, h * 0.3f)))
                engine.selectLayer(l1.id)
                session.color = 0xFFCC6633.toInt()
                session.stroke(listOf(P(w * 0.1f, h * 0.8f), P(w * 0.7f, h * 0.1f)))
                engine.flush()

                engine.enableMip()
                engine.updateMip()

                // Partial update, then compare with a full rebuild.
                engine.selectLayer(l0.id)
                session.color = 0xFF22AA55.toInt()
                session.stroke(listOf(P(60f, h * 0.5f), P(w * 0.5f, h * 0.9f)))
                engine.flush()
                engine.updateMip()
                val partial = engine.mipBitmap()
                val partialHash = if (partial != null) hashBitmap(partial) else "null"
                partial?.recycle()

                engine.markDirtyAll()
                engine.flush()
                engine.updateMip()
                val full = engine.mipBitmap()
                val fullHash = if (full != null) hashBitmap(full) else "null"

                // Reference downscale of the authoritative composite.
                var maxCh = 255
                var diffPx = 0L
                if (full != null) {
                    val comp = engine.compositeBitmap()
                    val ref = Bitmap.createScaledBitmap(
                        comp, engine.mipWidth, engine.mipHeight, true
                    )
                    val px = IntArray(ref.width * ref.height)
                    ref.getPixels(px, 0, ref.width, 0, 0, ref.width, ref.height)
                    val st = diffStats(full, px)
                    maxCh = st.maxChannel
                    diffPx = st.diffPixels
                    ref.recycle()
                    if (engine.compositeIsSparse) comp.recycle()
                    full.recycle()
                }
                sb.append(" ${w}x$h partial-vs-full=")
                    .append(if (partialHash == fullHash) "MATCH" else "DIFF")
                    .append(" vs-downscale maxch=").append(maxCh)
                    .append(" px=").append(diffPx)
            }
            sb.toString()
        } finally {
            TileStoreConfig.useSparseLayers = oldS
            TileStoreConfig.tileSize = oldT
            Runtime.getRuntime().gc()
        }
    }

    // ------------------------------------------------ painted-stack frames

    /** ART GC counters (count, milliseconds) for the painted-stack evidence. */
    private fun gcStats(): Pair<Long, Long> {
        val count = runCatching { android.os.Debug.getRuntimeStat("art.gc.gc-count") }
            .getOrNull()?.toLongOrNull() ?: 0L
        val time = runCatching { android.os.Debug.getRuntimeStat("art.gc.gc-time") }
            .getOrNull()?.toLongOrNull() ?: 0L
        return count to time
    }

    /**
     * Frame time while painting on top of a heavily painted stack.
     *
     * Each layer covers [fill] of the canvas with real tile content, and the
     * stack mixes Normal, Multiply and Overlay, holds a group with a clipping
     * layer and a masked layer. That is the compositing worst case: every dirty
     * tile has to blend every layer. A frame is 8 input events plus the
     * dirty-region composite, as in [scoreFrames]; the gate is p99 < 6 ms at
     * 2048². 4096² is reported against the 8.33 ms (120 fps) frame budget but
     * does not fail the harness, because the device is the final judge.
     *
     * Cases that cannot fit in the heap return FAIL with the reason instead of
     * crashing, so the numbers stay honest on a 512 MB device.
     */
    private fun scoreFramesPainted(): String {
        val out = StringBuilder()
        fun run(
            size: Int,
            sparse: Boolean,
            layers: Int,
            fill: Float,
            profile: Boolean = false
        ): String {
            val tag = "paint ${size}x$size ${if (sparse) "sparse" else "dense"} " +
                "x$layers fill=${(fill * 100).toInt()}%" + (if (profile) " [profiled]" else "")
            val oldS = TileStoreConfig.useSparseLayers
            val oldT = TileStoreConfig.tileSize
            TileStoreConfig.useSparseLayers = sparse
            TileStoreConfig.tileSize = 256
            return try {
                Runtime.getRuntime().gc()
                val engine = DrawingEngine(size, size)
                engine.backgroundColor = BG
                val session = DrawingSession("paint", "P", engine, History())
                identityView(session, size, size)
                session.presets = presets
                session.activePresetId = hard.id
                session.assist = 0f
                session.quickShapeEnabled = false
                session.category = BrushCategory.Brush
                session.color = 0xFF3355AA.toInt()

                for (i in 1 until layers) engine.newLayer(name = "L$i")
                val span = (size * fill).toInt()
                for (i in 0 until layers) {
                    val l = engine.layers[i]
                    val shade = 0xFF000000.toInt() or ((i + 1) * 0x0B0B0B)
                    // Two offset blocks so tile borders fall inside the paint.
                    l.tiles.fillRect(Rect(0, 0, span, span), shade)
                    l.tiles.fillRect(
                        Rect(size - span, size - span, size, size), shade or 0x202020
                    )
                    l.blend = when (i % 3) {
                        1 -> LayerBlend.Multiply
                        2 -> LayerBlend.Overlay
                        else -> LayerBlend.Normal
                    }
                }
                // A group, a clipping layer and a masked layer.
                if (layers >= 4) {
                    val g = engine.newGroup("G")
                    g.opacity = 0.85f
                    g.blend = LayerBlend.Multiply
                    engine.layers[1].groupId = g.id
                    engine.layers[1].opacity = 1f
                    engine.layers[2].groupId = g.id
                    engine.layers[2].opacity = 1f
                    engine.layers[3].clipping = true
                    engine.addMask(engine.layers[0])
                    engine.layers[0].maskTiles!!.fillRect(
                        Rect(0, 0, size / 2, size), 0xFF000000.toInt()
                    )
                }
                engine.markDirtyAll()
                engine.compositeAll()
                engine.selectLayer(engine.layers[layers - 1].id)

                val steps = 1200
                session.pushInput(
                    InputEvent.Down(0, 100f, size * 0.5f, 1f, 0f, true, false)
                )
                session.runInput()
                val gc0 = gcStats()
                // Measure this stroke from a cold tile pool so the counts are honest.
                TilePool.reset()
                TilePool.resetCounters()
                FrameLogger.reset()
                FrameLogger.enabled = true
                FrameLogger.strokeActive = true
                if (profile) {
                    CompositeProfile.reset()
                    CompositeProfile.enabled = true
                }
                var i = 1
                // Per-frame deltas of the profile counters, kept so the worst 1% of
                // frames can be described individually instead of in aggregate.
                val frameCount = (steps + 7) / 8 + 1
                val fTotal = LongArray(frameCount)
                val fComp = LongArray(frameCount)
                val fPaint = LongArray(frameCount)
                val fBlit = LongArray(frameCount)
                val fOpa = LongArray(frameCount)
                val fTiles = LongArray(frameCount)
                val fAlloc = LongArray(frameCount)
                val fCapt = LongArray(frameCount)
                val fDabs = LongArray(frameCount)
                val fTilePx = LongArray(frameCount)
                val fDirtyPx = LongArray(frameCount)
                var pComp = CompositeProfile.compositeNanos
                var pPaint = CompositeProfile.paintNanos
                var pBlit = CompositeProfile.blitNanos
                var pOpa = CompositeProfile.opacityNanos
                var pTiles = CompositeProfile.tileVisits
                var pAlloc = CompositeProfile.tilesAllocated
                var pCapt = CompositeProfile.captures
                var pDabs = CompositeProfile.paintDabs
                var pTilePx = CompositeProfile.compositePx
                var pDirtyPx = CompositeProfile.compositeDirtyPx
                var fc = 0
                while (i <= steps) {
                    val t0 = System.nanoTime()
                    val end = min(i + 8, steps + 1)
                    while (i < end) {
                        val t = i / steps.toFloat()
                        val x = 100f + t * (size - 200f)
                        val y = size * 0.5f + sin(t * 16f) * size * 0.18f
                        session.pushInput(
                            InputEvent.Move(
                                0, floatArrayOf(x), floatArrayOf(y), floatArrayOf(1f), 0f
                            )
                        )
                        session.runInput()
                        i++
                    }
                    engine.flush()
                    val dt = System.nanoTime() - t0
                    FrameLogger.record(dt)
                    if (profile && fc < frameCount) {
                        fTotal[fc] = dt
                        fComp[fc] = CompositeProfile.compositeNanos - pComp
                        fPaint[fc] = CompositeProfile.paintNanos - pPaint
                        fBlit[fc] = CompositeProfile.blitNanos - pBlit
                        fOpa[fc] = CompositeProfile.opacityNanos - pOpa
                        fTiles[fc] = CompositeProfile.tileVisits - pTiles
                        fAlloc[fc] = CompositeProfile.tilesAllocated - pAlloc
                        fCapt[fc] = CompositeProfile.captures - pCapt
                        fDabs[fc] = CompositeProfile.paintDabs - pDabs
                        fTilePx[fc] = CompositeProfile.compositePx - pTilePx
                        fDirtyPx[fc] = CompositeProfile.compositeDirtyPx - pDirtyPx
                        pComp = CompositeProfile.compositeNanos
                        pPaint = CompositeProfile.paintNanos
                        pBlit = CompositeProfile.blitNanos
                        pOpa = CompositeProfile.opacityNanos
                        pTiles = CompositeProfile.tileVisits
                        pAlloc = CompositeProfile.tilesAllocated
                        pCapt = CompositeProfile.captures
                        pDabs = CompositeProfile.paintDabs
                        pTilePx = CompositeProfile.compositePx
                        pDirtyPx = CompositeProfile.compositeDirtyPx
                        fc++
                    }
                }
                session.pushInput(InputEvent.Up(0))
                session.runInput()
                FrameLogger.strokeActive = false
                FrameLogger.enabled = false
                if (profile) CompositeProfile.enabled = false
                val gc1 = gcStats()
                val p99 = FrameLogger.percentileMillis(0.99)
                val mb = (engine.memoryReport())
                val layerMb = engine.layers.sumOf { it.residentBytes() } / (1024L * 1024L)
                // 2048² is a hard gate at p99 < 6 ms; 4096² is reported against
                // the 8.33 ms frame budget (the device is the final judge).
                val limit = if (size <= 2048) 6.0 else 8.33
                val tail = if (profile && fc > 0) {
                    fun ms(v: Long): String = "%.2f".format(v / 1e6)
                    fun frame(f: Int): String =
                        "f=$f total=${ms(fTotal[f])} comp=${ms(fComp[f])}" +
                            " paint=${ms(fPaint[f])} blit=${ms(fBlit[f])}" +
                            " opa=${ms(fOpa[f])} tiles=${fTiles[f]}" +
                            " alloc=${fAlloc[f]} cap=${fCapt[f]} dabs=${fDabs[f]}" +
                            " tilePx=${fTilePx[f]} dirtyPx=${fDirtyPx[f]}"
                    val byTotal = (0 until fc).sortedBy { fTotal[it] }
                    val nWorst = max(1, (fc + 99) / 100)
                    val sb = StringBuilder()
                    sb.append(" frameTail(median: ").append(frame(byTotal[fc / 2])).append(')')
                    for (j in 1..nWorst) {
                        val f = byTotal[fc - j]
                        sb.append(" slow$j[").append(frame(f)).append(']')
                    }
                    sb.toString()
                } else {
                    ""
                }
                "$tag: ${FrameLogger.summary()}" +
                    " gate=${if (p99 < limit) "PASS" else "FAIL"} (limit=$limit)" +
                    " resLayer=${layerMb}MB" +
                    " gc=+${gc1.first - gc0.first}/${gc1.second - gc0.second}ms [$mb]" +
                    (if (profile) " ${CompositeProfile.summary()}" else "") + tail +
                    (if (profile) " ${engine.poolReport()}" else "") +
                    (if (profile) "" else " ${TilePool.stats()}")
            } catch (t: Throwable) {
                "$tag: FAIL ${t.javaClass.simpleName} (needs more than the heap has)"
            } finally {
                TileStoreConfig.useSparseLayers = oldS
                TileStoreConfig.tileSize = oldT
                Runtime.getRuntime().gc()
            }
        }
        // Ladder from comfortable to impossible, so the report shows exactly
        // where the device runs out of heap.
        out.append(run(2048, false, 10, 0.8f)).append('\n')
        out.append(run(2048, true, 10, 0.8f)).append('\n')
        out.append(run(2048, false, 30, 0.6f)).append('\n')
        out.append(run(2048, true, 30, 0.6f)).append('\n')
        out.append(run(4096, false, 10, 0.5f)).append('\n')
        out.append(run(4096, true, 10, 0.5f)).append('\n')
        out.append(run(4096, true, 10, 0.5f, profile = true)).append('\n')
        out.append(run(4096, true, 30, 0.3f)).append('\n')
        out.append(run(4096, true, 30, 0.3f, profile = true)).append('\n')
        out.append(run(4096, true, 30, 0.15f))
        return out.toString()
    }

    /**
     * First-stroke latency: the very first dab used to allocate the stamping
     * band (8/64/256 MB) inside the gesture. Compares the first input batch with
     * and without the warmup that pre-allocates the band and tile scratch.
     */
    private fun scoreFirstStroke(): String {
        fun run(size: Int, warmup: Boolean): String {
            val oldS = TileStoreConfig.useSparseLayers
            val oldT = TileStoreConfig.tileSize
            TileStoreConfig.useSparseLayers = true
            TileStoreConfig.tileSize = 256
            return try {
                Runtime.getRuntime().gc()
                val engine = DrawingEngine(size, size)
                engine.backgroundColor = BG
                val session = DrawingSession("first", "F", engine, History())
                identityView(session, size, size)
                session.presets = presets
                session.activePresetId = hard.id
                session.assist = 0f
                session.quickShapeEnabled = false
                session.category = BrushCategory.Brush
                session.color = 0xFF3355AA.toInt()
                if (warmup) {
                    engine.reserveScratch()
                    // painter.preallocate() is reached through the same command
                    // the editor posts after loading a document.
                    session.warmup()
                    session.runCommands()
                }
                val t0 = System.nanoTime()
                session.pushInput(InputEvent.Down(0, 200f, size * 0.5f, 1f, 0f, true, false))
                session.runInput()
                val first = (System.nanoTime() - t0) / 1e6
                var steady = 0.0
                for (i in 0..40) {
                    val s = System.nanoTime()
                    session.pushInput(
                        InputEvent.Move(
                            0, floatArrayOf(200f + i * 20f),
                            floatArrayOf(size * 0.5f + i * 6f), floatArrayOf(1f), 0f
                        )
                    )
                    session.runInput()
                    engine.flush()
                    if (i > 0) steady = maxOf(steady, (System.nanoTime() - s) / 1e6)
                }
                session.pushInput(InputEvent.Up(0))
                session.runInput()
                "first-stroke ${size}x$size warmup=$warmup: first=%.1fms steady-max=%.1fms"
                    .format(first, steady)
            } catch (t: Throwable) {
                "first-stroke ${size}x$size warmup=$warmup: FAIL ${t.javaClass.simpleName}"
            } finally {
                TileStoreConfig.useSparseLayers = oldS
                TileStoreConfig.tileSize = oldT
                Runtime.getRuntime().gc()
            }
        }
        return listOf(
            run(4096, false), run(4096, true),
            run(2048, false), run(2048, true)
        ).joinToString("\n")
    }

    // ------------------------------------------------------- wand equivalence

    /**
     * The previous whole-image wand, kept here as the reference the streaming
     * algorithm is measured against. The app no longer contains it: both stores
     * select through `WandGrid`, so there is exactly one algorithm in the build.
     */
    private var legacyPixels: IntArray? = null
    private var legacyMask: BooleanArray? = null
    private var legacyStack = IntArray(4096)
    private var legacyScaled: Bitmap? = null
    private val legacyPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private fun similarColour(a: Int, b: Int, tolerance: Int): Boolean {
        if (abs(Color.alpha(a) - Color.alpha(b)) > tolerance) return false
        return abs(Color.red(a) - Color.red(b)) <= tolerance &&
            abs(Color.green(a) - Color.green(b)) <= tolerance &&
            abs(Color.blue(a) - Color.blue(b)) <= tolerance
    }

    private fun legacyMagicWand(bitmap: Bitmap, seedX: Int, seedY: Int, tolerance: Int): Region {
        val w0 = bitmap.width
        val h0 = bitmap.height
        if (seedX !in 0 until w0 || seedY !in 0 until h0) return Region()
        val step = if (w0.toLong() * h0 > 1_000_000L) 2 else 1
        val w = maxOf(1, w0 / step)
        val h = maxOf(1, h0 / step)
        val n = w * h
        val pixels = legacyPixels?.takeIf { it.size == n }
            ?: IntArray(n).also { legacyPixels = it }
        if (step == 1) {
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        } else {
            val scaled = legacyScaled?.takeIf { it.width == w && it.height == h }
                ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { legacyScaled = it }
            val canvas = Canvas(scaled)
            canvas.drawColor(0, BlendMode.SRC)
            canvas.drawBitmap(bitmap, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), legacyPaint)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        }
        val mask = legacyMask?.takeIf { it.size == n }
            ?: BooleanArray(n).also { legacyMask = it }
        java.util.Arrays.fill(mask, false)
        val seedXi = (seedX / step).coerceIn(0, w - 1)
        val seedYi = (seedY / step).coerceIn(0, h - 1)
        val target = pixels[seedYi * w + seedXi]
        var stack = legacyStack
        var top = 0
        stack[top++] = seedYi * w + seedXi
        while (top > 0) {
            val seed = stack[--top]
            if (mask[seed]) continue
            val sy = seed / w
            var sx = seed % w
            while (sx > 0 && !mask[sy * w + sx - 1] &&
                similarColour(pixels[sy * w + sx - 1], target, tolerance)
            ) {
                sx--
            }
            var upPending = false
            var downPending = false
            var x = sx
            while (x < w && !mask[sy * w + x] &&
                similarColour(pixels[sy * w + x], target, tolerance)
            ) {
                mask[sy * w + x] = true
                if (sy > 0) {
                    val up = (sy - 1) * w + x
                    if (!mask[up] && similarColour(pixels[up], target, tolerance)) {
                        if (!upPending) {
                            if (top == stack.size) {
                                stack = stack.copyOf(stack.size * 2)
                                legacyStack = stack
                            }
                            stack[top++] = up
                            upPending = true
                        }
                    } else {
                        upPending = false
                    }
                }
                if (sy < h - 1) {
                    val down = (sy + 1) * w + x
                    if (!mask[down] && similarColour(pixels[down], target, tolerance)) {
                        if (!downPending) {
                            if (top == stack.size) {
                                stack = stack.copyOf(stack.size * 2)
                                legacyStack = stack
                            }
                            stack[top++] = down
                            downPending = true
                        }
                    } else {
                        downPending = false
                    }
                }
                x++
            }
        }
        val region = Region()
        val path = Path()
        val open = HashMap<Long, IntArray>()
        val touched = HashSet<Long>()
        for (y in 0 until h) {
            touched.clear()
            var x = 0
            val row = y * w
            while (x < w) {
                if (mask[row + x]) {
                    val start = x
                    while (x < w && mask[row + x]) x++
                    val key = (start.toLong() shl 32) or (x.toLong() and 0xFFFFFFFFL)
                    if (open.containsKey(key)) {
                        touched.add(key)
                    } else {
                        open[key] = intArrayOf(start, x, y)
                        touched.add(key)
                    }
                } else {
                    x++
                }
            }
            if (open.isNotEmpty()) {
                val iterator = open.keys.iterator()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    if (key !in touched) {
                        val run = open[key] ?: continue
                        path.addRect(
                            (run[0] * step).toFloat(), (run[2] * step).toFloat(),
                            minOf(w0, run[1] * step).toFloat(),
                            minOf(h0, y * step).toFloat(),
                            Path.Direction.CW
                        )
                        iterator.remove()
                    }
                }
            }
        }
        for (run in open.values) {
            path.addRect(
                (run[0] * step).toFloat(), (run[2] * step).toFloat(),
                minOf(w0, run[1] * step).toFloat(), minOf(h0, h * step).toFloat(),
                Path.Direction.CW
            )
        }
        region.setPath(path, Region(0, 0, w0, h0))
        return region
    }

    /** Pixels covered by a region. */
    private fun regionArea(r: Region): Long {
        val it = RegionIterator(r)
        val rect = Rect()
        var area = 0L
        while (it.next(rect)) area += rect.width().toLong() * rect.height().toLong()
        return area
    }

    /** Stable signature of a selection: area, bounds and a CRC of its spans. */
    private fun regionSig(r: Region): String {
        val crc = CRC32()
        val it = RegionIterator(r)
        val rect = Rect()
        var area = 0L
        val buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        while (it.next(rect)) {
            area += rect.width().toLong() * rect.height().toLong()
            buf.clear()
            buf.putInt(rect.left)
            buf.putInt(rect.top)
            buf.putInt(rect.right)
            buf.putInt(rect.bottom)
            crc.update(buf.array(), 0, 16)
        }
        val b = r.bounds
        return "${area}:${b.left},${b.top},${b.right},${b.bottom}:${crc.value.toString(16)}"
    }

    /**
     * The streaming wand must select exactly what the materialising wand did:
     * the same pixels, on both canvases, at both tile sizes, for seeds on paint,
     * on blank canvas and on tile borders.
     */
    private fun scoreWand(): String {
        val oldS = TileStoreConfig.useSparseLayers
        val oldT = TileStoreConfig.tileSize
        val notes = ArrayList<String>()
        var checks = 0
        var bad = 0
        try {
            for ((w, h) in listOf(2048 to 2048, 2000 to 1333)) {
                for (tile in intArrayOf(256, 64)) {
                    TileStoreConfig.useSparseLayers = true
                    TileStoreConfig.tileSize = tile
                    val engine = DrawingEngine(w, h)
                    engine.backgroundColor = BG
                    val session = DrawingSession("wand", "W", engine, History())
                    identityView(session, w, h)
                    session.presets = presets
                    session.activePresetId = hard.id
                    session.assist = 0f
                    session.quickShapeEnabled = false
                    session.category = BrushCategory.Brush
                    // Content that straddles tile borders, plus a flat block.
                    val l0 = engine.newLayer("W0")
                    l0.tiles.fillRect(Rect(0, 0, w / 2, h / 3), 0xFFCC3322.toInt())
                    engine.selectLayer(l0.id)
                    session.color = 0xFF2244CC.toInt()
                    session.stroke(
                        listOf(
                            P(tile * 1.0f, tile * 1.0f),
                            P(tile * 2.2f, tile * 1.6f),
                            P(tile * 3.4f, tile * 2.4f)
                        )
                    )
                    engine.compositeAll()
                    val expected = engine.compositeBitmap()
                    val step = if (w.toLong() * h > 1_000_000L) 2 else 1
                    val seeds = listOf(
                        intArrayOf(w / 2, h / 2),
                        intArrayOf(4, 4),
                        intArrayOf(tile, tile),
                        intArrayOf(min(tile * 2, w - 2), min(tile * 3, h - 2)),
                        intArrayOf(w - 3, h - 3),
                        intArrayOf(w / 2, tile)
                    )
                    for (s in seeds) {
                        for (tol in intArrayOf(0, 30, 120)) {
                            val a = legacyMagicWand(expected, s[0], s[1], tol)
                            val grid = engine.compositeGrid(step)
                            val b = try {
                                SelectionUtil.magicWandGrid(grid, s[0], s[1], tol)
                            } finally {
                                grid.release()
                            }
                            val diff = Region(a)
                            diff.op(b, Region.Op.XOR)
                            val delta = regionArea(diff)
                            checks++
                            if (delta != 0L) {
                                bad++
                                notes.add(
                                    "delta ${w}x$h tile=$tile seed=(${s[0]},${s[1]}) " +
                                        "tol=$tol px=$delta legacy=${regionArea(a)} " +
                                        "new=${regionArea(b)}"
                                )
                            }
                        }
                    }
                    expected.recycle()
                }
            }
        } finally {
            TileStoreConfig.useSparseLayers = oldS
            TileStoreConfig.tileSize = oldT
            Runtime.getRuntime().gc()
        }
        val verdict = if (bad == 0) "PASS" else "FAIL"
        val sb = StringBuilder("wand-legacy-delta: $verdict ($checks cases, $bad changed)")
        for (n in notes) sb.append('\n').append(n)
        return sb.toString()
    }

    /** The same wand document built on any store, for the parity test. */
    private fun buildWandDoc(w: Int, h: Int, sparse: Boolean, tile: Int): DrawingEngine {
        val oldS = TileStoreConfig.useSparseLayers
        val oldT = TileStoreConfig.tileSize
        TileStoreConfig.useSparseLayers = sparse
        TileStoreConfig.tileSize = tile
        try {
            val engine = DrawingEngine(w, h)
            engine.backgroundColor = BG
            val session = DrawingSession("wpar", "WP", engine, History())
            identityView(session, w, h)
            session.presets = presets
            session.activePresetId = hard.id
            session.assist = 0f
            session.quickShapeEnabled = false
            session.category = BrushCategory.Brush
            // Flat block, a band across the middle, a stroke over tile borders.
            val l0 = engine.newLayer("W0")
            l0.tiles.fillRect(Rect(0, 0, w / 2, h / 3), 0xFFCC3322.toInt())
            val l1 = engine.newLayer("W1")
            l1.tiles.fillRect(Rect(0, h / 2, w, h / 2 + 40), 0xFF2244CC.toInt())
            engine.selectLayer(l0.id)
            session.color = 0xFF33AA55.toInt()
            session.stroke(
                listOf(
                    P(256f, 256f), P(512f, 400f), P(768f, 300f), P(w * 0.85f, h * 0.4f)
                )
            )
            engine.compositeAll()
            return engine
        } finally {
            TileStoreConfig.useSparseLayers = oldS
            TileStoreConfig.tileSize = oldT
        }
    }

    /**
     * One wand algorithm, one answer: the dense store and sparse stores at tile
     * 256 and 64 must return exactly the same selection on both canvas shapes,
     * for seeds on paint, on blank canvas and on tile borders.
     */
    private fun scoreWandStores(): String {
        val notes = ArrayList<String>()
        val oracle = ArrayList<String>()
        var checks = 0
        var bad = 0
        val names = listOf("dense", "sparse256", "sparse64")
        try {
            for ((w, h) in listOf(2048 to 2048, 2000 to 1333)) {
                val engines = ArrayList<DrawingEngine>()
                engines.add(buildWandDoc(w, h, false, 256))
                engines.add(buildWandDoc(w, h, true, 256))
                engines.add(buildWandDoc(w, h, true, 64))
                val step = if (w.toLong() * h > 1_000_000L) 2 else 1
                val seeds = listOf(
                    intArrayOf(w / 2, h / 2),
                    intArrayOf(4, 4),
                    intArrayOf(256, 256),
                    intArrayOf(w / 2, 256),
                    intArrayOf(w - 3, h - 3),
                    intArrayOf(512, min(h - 2, 768))
                )
                for (s in seeds) {
                    for (tol in intArrayOf(0, 30, 120)) {
                        var sig: String? = null
                        var label = ""
                        for ((i, e) in engines.withIndex()) {
                            val g = e.compositeGrid(step)
                            val r = try {
                                SelectionUtil.magicWandGrid(g, s[0], s[1], tol)
                            } finally {
                                g.release()
                            }
                            val sg = regionSig(r)
                            if (sig == null) {
                                sig = sg
                                label = names[i]
                            } else if (sg != sig) {
                                bad++
                                if (notes.size < 8) {
                                    notes.add(
                                        "MISMATCH ${w}x$h seed=(${s[0]},${s[1]}) tol=$tol " +
                                            "$label=$sig ${names[i]}=$sg"
                                    )
                                }
                            }
                        }
                        checks++
                        oracle.add(
                            "wand ${w}x$h seed=(${s[0]},${s[1]}) tol=$tol sig=$sig"
                        )
                    }
                }
                engines.clear()
                Runtime.getRuntime().gc()
            }
        } finally {
            Runtime.getRuntime().gc()
        }
        val verdict = if (bad == 0) "PASS" else "FAIL"
        val sb = StringBuilder(
            "wand-stores: $verdict ($checks cases, $bad cross-store mismatches)"
        )
        for (n in notes) sb.append('\n').append(n)
        for (o in oracle) sb.append('\n').append("wand-oracle ").append(o.removePrefix("wand "))
        return sb.toString()
    }

    // -------------------------------------------------- allocation per op

    /**
     * Runs every one-shot operation on a sparse document with the allocation
     * counter on. In sparse mode only the stamping band and the single PNG
     * encode surface may be canvas sized; anything else is a regression.
     */
    private fun scoreOpsAlloc(app: YumeApp): String {
        val oldS = TileStoreConfig.useSparseLayers
        val oldT = TileStoreConfig.tileSize
        TileStoreConfig.useSparseLayers = true
        TileStoreConfig.tileSize = 256
        val size = 1024
        val out = StringBuilder("alloc-per-op:")
        var failures = 0
        try {
            val engine = DrawingEngine(size, size)
            engine.backgroundColor = BG
            val session = DrawingSession("alloc", "A", engine, History())
            identityView(session, size, size)
            session.presets = presets
            session.activePresetId = hard.id
            session.assist = 0f
            session.quickShapeEnabled = false
            session.category = BrushCategory.Brush
            engine.reserveScratch()
            val l0 = engine.newLayer("A0")
            l0.tiles.fillRect(Rect(0, 0, size / 2, size / 2), 0xFFAA3355.toInt())
            engine.selectLayer(l0.id)
            session.color = 0xFF2244CC.toInt()
            session.stroke(listOf(P(64f, 300f), P(size * 0.7f, size * 0.6f)))
            engine.newLayer("A1")
            engine.selectLayer(l0.id)
            engine.compositeAll()

            fun op(name: String, block: () -> Unit) {
                AllocTracker.reset(size, size)
                AllocTracker.enabled = true
                try {
                    block()
                } catch (t: Throwable) {
                    out.append("\n  $name threw ${t.javaClass.simpleName}")
                } finally {
                    AllocTracker.enabled = false
                }
                val table = AllocTracker.table()
                val bad = AllocTracker.sites().keys.any { it !in AllocTracker.allowedSites }
                if (bad) failures++
                out.append("\n  ").append(name).append(": [").append(table).append("] ")
                    .append(if (bad) "FAIL" else "ok")
            }

            op("transform-move") {
                session.chooseTool(Tool.Transform)
                session.runCommands()
                session.pushInput(InputEvent.Gesture(size * 0.5f, size * 0.5f, 1.2f, 7f, 0f, 0f))
                session.runInput()
                session.commitTransform()
                session.runCommands()
                session.chooseTool(Tool.Brush)
                session.runCommands()
            }
            op("adjust-blur") {
                session.previewAdjustment(AdjustmentKind.Blur, 0.4f)
                session.consumePendingPreview()
                session.commitAdjustment()
                session.runCommands()
            }
            op("adjust-hue") {
                session.previewAdjustment(AdjustmentKind.Hue, 30f)
                session.consumePendingPreview()
                session.commitAdjustment()
                session.runCommands()
            }
            op("invert") { session.invertColors(); session.runCommands() }
            op("grayscale") { session.grayscale(); session.runCommands() }
            op("merge") {
                engine.newLayer("A2")
                engine.selectLayer(engine.layers[engine.layers.size - 1].id)
                session.mergeDown()
                session.runCommands()
            }
            op("duplicate") { session.duplicateLayer(); session.runCommands() }
            op("fill") { session.fillLayer(0xFF8899AA.toInt()); session.runCommands() }
            op("clear") { session.clearLayer(); session.runCommands() }
            op("wand") {
                val grid = engine.compositeGrid(1)
                val region = try {
                    SelectionUtil.magicWandGrid(grid, size / 2, size / 2, 30)
                } finally {
                    grid.release()
                }
                engine.applySelection(region, SelectionOp.Replace)
                engine.setSelection(null)
            }
            op("undo") { session.undo(); session.runCommands() }
            op("redo") { session.redo(); session.runCommands() }
            op("export") {
                val sink = java.io.ByteArrayOutputStream()
                if (!session.exportBandedTo(sink)) throw IllegalStateException("export failed")
            }
            // Save and lazy load through the real ProjectStore paths.
            val ps = app.projectStore
            val project = ps.createProject("harness-alloc", size, size, BG)
            try {
                op("save") { ps.saveProject(project.id, session.snapshotNow()) }
                op("load") {
                    val loaded = ps.loadProject(project.id) ?: error("reload failed")
                    val rebuilt = DrawingEngine(size, size)
                    for (d in loaded.layers) {
                        val l = rebuilt.newLayer(name = d.name, bitmap = d.bitmap, backingFile = d.file)
                        l.adoptMask(d.mask, d.maskFile)
                    }
                    rebuilt.compositeAll()
                }
            } finally {
                runCatching { ps.deleteProject(project.id) }
            }
        } finally {
            TileStoreConfig.useSparseLayers = oldS
            TileStoreConfig.tileSize = oldT
            Runtime.getRuntime().gc()
        }
        out.append("\n  verdict=").append(if (failures == 0) "PASS" else "FAIL")
        return out.toString()
    }

    /**
     * Flatten (the PNG encode surface) must be identical on both stores, so an
     * exported file cannot depend on how the layers are stored.
     */
    private fun scoreExportParity(): String {
        val oldS = TileStoreConfig.useSparseLayers
        val oldT = TileStoreConfig.tileSize
        val w = 1000
        val h = 700
        fun build(sparse: Boolean, tile: Int): String {
            TileStoreConfig.useSparseLayers = sparse
            TileStoreConfig.tileSize = tile
            val engine = DrawingEngine(w, h)
            engine.backgroundColor = BG
            val session = DrawingSession("exp", "E", engine, History())
            identityView(session, w, h)
            session.presets = presets
            session.activePresetId = hard.id
            session.assist = 0f
            session.quickShapeEnabled = false
            session.category = BrushCategory.Brush
            session.color = 0xFF3366AA.toInt()
            val a = engine.newLayer("E0")
            a.blend = LayerBlend.Multiply
            session.stroke(listOf(P(30f, 40f), P(w * 0.8f, h * 0.7f)))
            engine.newLayer("E1")
            session.color = 0xFFCC8844.toInt()
            session.stroke(listOf(P(w * 0.2f, h * 0.9f), P(w * 0.9f, h * 0.2f)))
            engine.addMask(a)
            a.maskTiles!!.fillRect(Rect(0, 0, w / 2, h), 0xFF000000.toInt())
            engine.compositeAll()
            val flat = engine.flatten()
            val hash = hashBitmap(flat)
            flat.recycle()
            return hash
        }
        return try {
            val dense = build(false, 256)
            val s256 = build(true, 256)
            val s64 = build(true, 64)
            val ok = dense == s256 && dense == s64
            "export-parity ${w}x$h: ${if (ok) "PASS" else "FAIL"} dense=$dense tile256=$s256 tile64=$s64"
        } finally {
            TileStoreConfig.useSparseLayers = oldS
            TileStoreConfig.tileSize = oldT
            Runtime.getRuntime().gc()
        }
    }

    // ---------------------------------------------------------- frame logger

    /**
     * Measures frame time while painting, using the same FrameLogger the render
     * thread feeds on device. A frame is one batch of input events plus the
     * dirty-region composite, which is what costs time while a stroke is live.
     */
    private fun scoreFrames(): String {
        val out = StringBuilder()
        fun run(size: Int, sparse: Boolean, layers: Int): String {
            val tag = "${size}x$size ${if (sparse) "sparse" else "dense"} layers=$layers"
            val oldS = TileStoreConfig.useSparseLayers
            val oldT = TileStoreConfig.tileSize
            TileStoreConfig.useSparseLayers = sparse
            TileStoreConfig.tileSize = 256
            return try {
                Runtime.getRuntime().gc()
                AllocTracker.reset(size, size)
                AllocTracker.enabled = true
                val engine = DrawingEngine(size, size)
                engine.backgroundColor = BG
                val session = DrawingSession("frames", "F", engine, History())
                identityView(session, size, size)
                session.presets = presets
                session.activePresetId = hard.id
                session.assist = 0f
                session.quickShapeEnabled = false
                session.category = BrushCategory.Brush
                session.color = 0xFF3355AA.toInt()
                for (i in 1 until layers) engine.newLayer(name = "L$i")
                for (i in 0 until layers - 1) {
                    engine.selectLayer(engine.layers[i].id)
                    session.stroke(
                        listOf(
                            P(80f, 120f + i * 170f),
                            P(size * 0.45f, 160f + i * 170f)
                        )
                    )
                }
                engine.selectLayer(engine.layers[layers - 1].id)

                val steps = 2000
                session.pushInput(
                    InputEvent.Down(0, 100f, size * 0.5f, 1f, 0f, true, false)
                )
                session.runInput()
                FrameLogger.reset()
                FrameLogger.enabled = true
                FrameLogger.strokeActive = true
                var i = 1
                while (i <= steps) {
                    val t0 = System.nanoTime()
                    val end = min(i + 8, steps + 1)
                    while (i < end) {
                        val t = i / steps.toFloat()
                        val x = 100f + t * (size - 200f)
                        val y = size * 0.5f + sin(t * 16f) * size * 0.18f
                        session.pushInput(
                            InputEvent.Move(
                                0, floatArrayOf(x), floatArrayOf(y),
                                floatArrayOf(1f), 0f
                            )
                        )
                        session.runInput()
                        i++
                    }
                    engine.flush()
                    FrameLogger.record(System.nanoTime() - t0)
                }
                session.pushInput(InputEvent.Up(0))
                session.runInput()
                FrameLogger.strokeActive = false
                FrameLogger.enabled = false
                AllocTracker.enabled = false
                val p99 = FrameLogger.percentileMillis(0.99)
                val limit = if (size <= 2048) 6.0 else 8.33
                val gate = p99 < limit
                val alloc = AllocTracker.table()
                // Allocation gate: a sparse document may not allocate a
                // canvas-sized bitmap outside the allowed sites (the PNG encoder).
                val allocGate = if (sparse) {
                    AllocTracker.sites().keys.all { it in AllocTracker.allowedSites }
                } else {
                    true
                }
                "frames $tag: ${FrameLogger.summary()}" +
                    " gate=${if (gate) "PASS" else "FAIL"} (limit=$limit)" +
                    " allocGate=${if (allocGate) "PASS" else "FAIL"} alloc=[$alloc]"
            } catch (t: Throwable) {
                "frames $tag: FAIL ${t.javaClass.simpleName}"
            } finally {
                TileStoreConfig.useSparseLayers = oldS
                TileStoreConfig.tileSize = oldT
                Runtime.getRuntime().gc()
            }
        }
        out.append(run(2048, false, 1)).append('\n')
        out.append(run(2048, false, 20)).append('\n')
        out.append(run(2048, true, 1)).append('\n')
        out.append(run(2048, true, 20)).append('\n')
        out.append(run(4096, false, 1)).append('\n')
        out.append(run(4096, true, 1)).append('\n')
        out.append(run(4096, true, 20))
        return out.toString()
    }

    private fun line(text: String): String = "$text\n"

    /**
     * QuickShape recognition on synthetic hand-drawn strokes: each case is a
     * known shape with wobble, uneven speed and an imperfect closure, and must
     * come back as the expected kind (or as nothing, for a scribble).
     */
    private fun scoreShapes(): String {
        val sb = StringBuilder()
        val rnd = java.util.Random(7)
        fun noisy(xs: List<Float>, ys: List<Float>, amp: Float): Pair<FloatArray, FloatArray> {
            val nx = FloatArray(xs.size) { xs[it] + (rnd.nextGaussian() * amp).toFloat() }
            val ny = FloatArray(ys.size) { ys[it] + (rnd.nextGaussian() * amp).toFloat() }
            return nx to ny
        }
        fun polyline(px: FloatArray, py: FloatArray, per: Int): Pair<List<Float>, List<Float>> {
            val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
            for (k in 0 until px.size - 1) for (t in 0 until per) {
                val f = t / per.toFloat()
                xs.add(px[k] + (px[k + 1] - px[k]) * f); ys.add(py[k] + (py[k + 1] - py[k]) * f)
            }
            xs.add(px.last()); ys.add(py.last())
            return xs to ys
        }
        fun ellipsePts(cx: Float, cy: Float, a: Float, b: Float, rot: Float, sweep: Double, n: Int): Pair<List<Float>, List<Float>> {
            val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
            for (k in 0..n) {
                // Uneven speed: fast through the middle of each quarter.
                val u = k / n.toDouble()
                val t = sweep * (u + 0.04 * kotlin.math.sin(8 * Math.PI * u))
                val ex = a * kotlin.math.cos(t).toFloat(); val ey = b * kotlin.math.sin(t).toFloat()
                xs.add(cx + ex * kotlin.math.cos(rot) - ey * kotlin.math.sin(rot))
                ys.add(cy + ex * kotlin.math.sin(rot) + ey * kotlin.math.cos(rot))
            }
            return xs to ys
        }
        data class Case(val name: String, val want: String?, val pts: Pair<FloatArray, FloatArray>)
        val cases = ArrayList<Case>()
        run {
            val (x, y) = polyline(floatArrayOf(100f, 900f), floatArrayOf(200f, 520f), 80)
            cases.add(Case("line", "line", noisy(x, y, 2.5f)))
        }
        run {
            val (x, y) = ellipsePts(500f, 500f, 300f, 300f, 0f, 2 * Math.PI * 1.03, 160)
            cases.add(Case("circle", "circle", noisy(x, y, 4f)))
        }
        run {
            val (x, y) = ellipsePts(500f, 500f, 380f, 190f, 0.4f, 2 * Math.PI * 0.97, 160)
            cases.add(Case("ellipse", "ellipse", noisy(x, y, 4f)))
        }
        run {
            val (x, y) = polyline(floatArrayOf(200f, 800f, 800f, 200f, 205f), floatArrayOf(200f, 210f, 600f, 590f, 215f), 40)
            cases.add(Case("rectangle", "rectangle", noisy(x, y, 3f)))
        }
        run {
            val (x, y) = polyline(floatArrayOf(500f, 820f, 180f, 495f), floatArrayOf(150f, 700f, 690f, 160f), 50)
            cases.add(Case("triangle", "triangle", noisy(x, y, 3f)))
        }
        run {
            val (x, y) = polyline(floatArrayOf(100f, 400f, 700f), floatArrayOf(600f, 150f, 600f), 50)
            cases.add(Case("polyline", "polyline", noisy(x, y, 2.5f)))
        }
        run {
            val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
            var px = 500f; var py = 500f
            for (k in 0 until 200) {
                px += (rnd.nextFloat() - 0.5f) * 60f; py += (rnd.nextFloat() - 0.5f) * 60f
                xs.add(px); ys.add(py)
            }
            cases.add(Case("scribble", null, xs.toFloatArray() to ys.toFloatArray()))
        }
        var bad = 0
        for (c in cases) {
            val got = com.yume.app.engine.ShapeGeometry.recognize(c.pts.first, c.pts.second, c.pts.first.size)?.kind
            val ok = got == c.want
            if (!ok) bad++
            sb.append("shape ${c.name}: want=${c.want} got=$got ${if (ok) "ok" else "BAD"}\n")
        }
        sb.append("shapes: ${if (bad == 0) "PASS" else "FAIL"} (${cases.size} cases, $bad bad)\n")

        // .yumebrush round trip: settings and embedded assets survive.
        val oldRoot = com.yume.app.engine.BrushAssets.root
        com.yume.app.engine.BrushAssets.root = java.io.File(cacheRoot, "harness-brushfile").apply { mkdirs() }
        try {
            val img = android.graphics.Bitmap.createBitmap(40, 40, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(img).drawCircle(20f, 20f, 14f, android.graphics.Paint().apply { color = 0xFF000000.toInt() })
            val sid = com.yume.app.engine.BrushAssets.importShape(img) ?: ""
            val src = hard.copy(id = "rt", name = "Round trip", shapeId = sid, rotationMode = 2, rotation = 30f, wetMix = 0.4f, builtIn = false)
            val text = com.yume.app.engine.BrushPresetFile.export(listOf(src))
            java.io.File(com.yume.app.engine.BrushAssets.root, "brush_shapes").deleteRecursively()
            val back = com.yume.app.engine.BrushPresetFile.import(text)
            val got = back.firstOrNull()
            val same = got != null && got.copy(id = src.id, builtIn = src.builtIn) == src
            val asset = got != null && com.yume.app.engine.BrushAssets.shape(got.shapeId) != null
            sb.append("brush-file: ${if (same && asset) "PASS" else "FAIL"} settings=$same asset=$asset\n")
        } finally {
            com.yume.app.engine.BrushAssets.root?.deleteRecursively()
            com.yume.app.engine.BrushAssets.root = oldRoot
        }
        return sb.toString()
    }

    /** Backup zip round trip: every file comes back byte for byte. */
    private fun scoreBackup(app: YumeApp): String {
        val ps = app.projectStore
        val project = ps.createProject("harness-backup", 300, 200, BG)
        var restored: String? = null
        return try {
            val dir = ps.projectDir(project.id)
            java.io.File(dir, "extra.bin").writeBytes(ByteArray(5000) { (it * 31).toByte() })
            val zip = java.io.File(cacheRoot, "harness-backup.zip")
            val ok1 = zip.outputStream().use { ps.backupProject(project.id, it) }
            restored = zip.inputStream().use { ps.restoreProject(it) }
            val back = restored?.let { ps.projectDir(it) }
            var same = back != null
            if (back != null) {
                dir.walkTopDown().filter { it.isFile && it.name != "manifest.json" && !it.path.contains("journal") }.forEach { f ->
                    val g = java.io.File(back, f.relativeTo(dir).path)
                    if (!g.exists() || !g.readBytes().contentEquals(f.readBytes())) same = false
                }
            }
            // A zip that tries to escape its folder is refused.
            val evil = java.io.File(cacheRoot, "harness-evil.zip")
            java.util.zip.ZipOutputStream(evil.outputStream()).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry("yume-backup.json")); z.write("{}".toByteArray()); z.closeEntry()
                z.putNextEntry(java.util.zip.ZipEntry("project/../../escape.txt")); z.write("x".toByteArray()); z.closeEntry()
            }
            val evilId = evil.inputStream().use { ps.restoreProject(it) }
            zip.delete(); evil.delete()
            val escaped = java.io.File(ps.projectDir("x").parentFile?.parentFile, "escape.txt").exists()
            "backup: ${if (ok1 && same && evilId == null && !escaped) "PASS" else "FAIL"} backup=$ok1 sameFiles=$same evilRefused=${evilId == null} escaped=$escaped\n"
        } finally {
            runCatching { ps.deleteProject(project.id) }
            restored?.let { runCatching { ps.deleteProject(it) } }
        }
    }
}
