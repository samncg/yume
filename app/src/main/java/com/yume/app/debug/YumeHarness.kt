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
import com.yume.app.engine.LayerBlend as LB
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
                } else if (only == "accept8k") {
                    out.append(scoreAcceptance8k(app))
                } else if (only == "journal") {
                    out.append(scoreJournal(app))
                    out.append(line(scoreHistorySpill(app)))
                } else if (only == "png") {
                    out.append(scorePngParity(app))
                    out.append(line(scoreOpsAlloc(app)))
                    out.append(line(scoreSparsePersistence(app)))
                    out.append(line(scoreExportParity()))
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
                val file = File(ps.projectDir(project.id), "layer_0.png")
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
                val layerFile = File(dir, "layer_$i.png")
                pngHashes.add(if (layerFile.exists()) crcBytes(layerFile.readBytes()) else "missing")
                val maskFile = File(dir, "mask_$i.png")
                if (maskFile.exists()) pngHashes.add(crcBytes(maskFile.readBytes()))
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
}
