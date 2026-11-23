package com.yume.app.engine

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** What the Effects panel can add as a new layer, with a live preview first. */
enum class InsertKind(val label: String) {
    FocusLines("Focus lines"),
    SpeedLines("Speed lines"),
    Frames("Comic frames"),
    Material("Material")
}

/**
 * Manga lines. [thickness] is the widest end in document px; [centerX] /
 * [centerY] are where focus lines point (document px, NaN: the selection's
 * centre, else the canvas's).
 */
data class MangaParams(
    val speed: Boolean = false,
    val count: Int = 160,
    val thickness: Float = 8f,
    val clear: Float = 0.45f,
    val variation: Float = 0.6f,
    val angle: Float = 0f,
    val seed: Long = 1L,
    val centerX: Float = Float.NaN,
    val centerY: Float = Float.NaN
)

/** A material from the library; [size] is its period in document px; [ink] 0 colour, 1 black, 2 white. */
data class MaterialParams(
    val kind: MaterialKind = MaterialKind.Dots,
    val size: Float = 8f,
    val angle: Float = 45f,
    val density: Float = 0.3f,
    val ink: Int = 1,
    val seed: Int = 1
)

/** One comic panel: a block of grid cells. */
data class FramePanel(val col: Int, val row: Int, val w: Int, val h: Int) {
    fun overlaps(o: FramePanel): Boolean =
        col < o.col + o.w && o.col < col + w && row < o.row + o.h && o.row < row + h

    fun contains(c: Int, r: Int): Boolean = c in col until col + w && r in row until row + h
}

/**
 * A comic page: a [cols] x [rows] grid inside a [margin] (share of the
 * canvas's shorter side) with [gutter] between cells; each panel covers a
 * block of cells (gutters inside it included), like items in a grid
 * inventory. [border] is the panel outline in document px.
 */
data class FramesLayout(
    val cols: Int = 3,
    val rows: Int = 4,
    val gutter: Float = 0.025f,
    val margin: Float = 0.05f,
    val border: Float = 6f,
    val panels: List<FramePanel> = emptyList(),
    val selected: Int = -1
) {
    fun valid(p: FramePanel, ignore: Int = -1): Boolean {
        if (p.col < 0 || p.row < 0 || p.w < 1 || p.h < 1 || p.col + p.w > cols || p.row + p.h > rows) return false
        return panels.withIndex().none { (i, o) -> i != ignore && o.overlaps(p) }
    }

    /** The same panels on a [c] x [r] grid: scaled to it, overlapping ones dropped. */
    fun regrid(c: Int, r: Int): FramesLayout {
        if (c == cols && r == rows) return this
        val out = ArrayList<FramePanel>()
        for (p in panels) {
            val col = (p.col * c + cols / 2) / cols
            val row = (p.row * r + rows / 2) / rows
            val col2 = max(col + 1, ((p.col + p.w) * c + cols / 2) / cols)
            val row2 = max(row + 1, ((p.row + p.h) * r + rows / 2) / rows)
            val q = FramePanel(col.coerceIn(0, c - 1), row.coerceIn(0, r - 1), 0, 0).let {
                it.copy(w = (min(col2, c) - it.col).coerceAtLeast(1), h = (min(row2, r) - it.row).coerceAtLeast(1))
            }
            if (out.none { it.overlaps(q) }) out.add(q)
        }
        return copy(cols = c, rows = r, panels = out, selected = -1)
    }

    /** Every empty cell becomes a 1 x 1 panel. */
    fun fillEmpty(): FramesLayout {
        val out = ArrayList(panels)
        for (r in 0 until rows) for (c in 0 until cols) {
            if (out.none { it.contains(c, r) }) out.add(FramePanel(c, r, 1, 1))
        }
        return copy(panels = out)
    }

    // ----------------------------------------------------- geometry (doc px)

    class Geometry(val left: Float, val top: Float, val cellW: Float, val cellH: Float, val gutter: Float)

    fun geometry(width: Int, height: Int): Geometry {
        val unit = min(width, height).toFloat()
        val m = unit * margin.coerceIn(0f, 0.3f)
        val g = unit * gutter.coerceIn(0f, 0.2f)
        val cw = (width - 2f * m - (cols - 1) * g) / cols
        val ch = (height - 2f * m - (rows - 1) * g) / rows
        return Geometry(m, m, cw, ch, g)
    }

    fun rectOf(p: FramePanel, geo: Geometry): RectF {
        val l = geo.left + p.col * (geo.cellW + geo.gutter)
        val t = geo.top + p.row * (geo.cellH + geo.gutter)
        return RectF(l, t, l + p.w * geo.cellW + (p.w - 1) * geo.gutter, t + p.h * geo.cellH + (p.h - 1) * geo.gutter)
    }

    /** The grid cell nearest to a document point (column, row). */
    fun cellAt(x: Float, y: Float, geo: Geometry): IntArray {
        val c = ((x - geo.left + geo.gutter / 2f) / (geo.cellW + geo.gutter)).toInt().coerceIn(0, cols - 1)
        val r = ((y - geo.top + geo.gutter / 2f) / (geo.cellH + geo.gutter)).toInt().coerceIn(0, rows - 1)
        return intArrayOf(if (x < geo.left) 0 else c, if (y < geo.top) 0 else r)
    }

    fun fits(width: Int, height: Int): Boolean {
        val geo = geometry(width, height)
        return geo.cellW >= 4f && geo.cellH >= 4f
    }

    companion object {
        /** Ready-made pages (name, layout). */
        val presets: List<Pair<String, FramesLayout>> = listOf(
            "Manga page" to FramesLayout(
                3, 4, panels = listOf(
                    FramePanel(0, 0, 3, 1), FramePanel(0, 1, 2, 1), FramePanel(2, 1, 1, 2),
                    FramePanel(0, 2, 2, 1), FramePanel(0, 3, 1, 1), FramePanel(1, 3, 2, 1)
                )
            ),
            "Splash + 3" to FramesLayout(
                3, 3, panels = listOf(
                    FramePanel(0, 0, 3, 2), FramePanel(0, 2, 1, 1), FramePanel(1, 2, 1, 1), FramePanel(2, 2, 1, 1)
                )
            ),
            "Four strips" to FramesLayout(1, 4).fillEmpty(),
            "2 × 3 grid" to FramesLayout(2, 3).fillEmpty(),
            "Tall + stack" to FramesLayout(
                2, 3, panels = listOf(FramePanel(0, 0, 1, 3), FramePanel(1, 0, 1, 1), FramePanel(1, 1, 1, 1), FramePanel(1, 2, 1, 1))
            ),
            "Empty grid" to FramesLayout(4, 6)
        )
    }
}

/**
 * Something about to become a new layer: drawn at any scale for the live
 * preview, and at full size when applied. Immutable, so a preview can be
 * drawn on a background thread.
 */
abstract class InsertArt(val label: String, val width: Int, val height: Int) {
    /**
     * Draws the document region whose top-left is ([left], [top]) into
     * [out] at [scale] out px per document px (out is cleared first).
     */
    abstract fun render(out: Bitmap, left: Float, top: Float, scale: Float)

    /** Paints the full-size pixels into [layer] (new and empty). May throw [OperationCancelled]. */
    abstract fun bake(layer: Layer)

    companion object {
        private val THREADS = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val pool by lazy {
            Executors.newFixedThreadPool(THREADS) { r -> Thread(r, "yume-insert").apply { isDaemon = true } }
        }

        /** Runs [band] (first row, end row) over [rows] rows split across the pool. */
        fun parallelRows(rows: Int, band: (Int, Int) -> Unit) {
            val n = min(THREADS, max(1, rows / 16))
            if (n <= 1) {
                band(0, rows); return
            }
            val step = (rows + n - 1) / n
            val jobs = ArrayList<Future<*>>()
            var y = 0
            while (y < rows) {
                val a = y
                val b = min(rows, y + step)
                jobs.add(pool.submit { band(a, b) })
                y = b
            }
            for (j in jobs) j.get()
        }
    }
}

/** Art drawn with a Canvas in document coordinates (manga lines, frames). */
abstract class VectorArt(label: String, width: Int, height: Int) : InsertArt(label, width, height) {
    abstract fun draw(c: Canvas)

    override fun render(out: Bitmap, left: Float, top: Float, scale: Float) {
        val c = Canvas(out)
        c.drawColor(0, BlendMode.CLEAR)
        c.scale(scale, scale)
        c.translate(-left, -top)
        draw(c)
    }

    override fun bake(layer: Layer) {
        layer.tiles.withWriteCanvas(Rect(0, 0, width, height)) { draw(it) }
    }
}

class MangaArt(
    width: Int, height: Int,
    private val path: Path,
    private val keepOut: Path?,
    color: Int,
    label: String
) : VectorArt(label, width, height) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color or 0xFF000000.toInt() }

    override fun draw(c: Canvas) {
        val save = c.save()
        if (keepOut != null) c.clipOutPath(keepOut)
        c.drawPath(path, paint)
        c.restoreToCount(save)
    }

    companion object {
        /** Where focus lines point by default: the selection's centre, else the canvas's. */
        fun defaultCenter(width: Int, height: Int, selection: android.graphics.Region?): FloatArray {
            val sel = selection?.takeIf { !it.isEmpty }?.bounds
            return floatArrayOf(sel?.exactCenterX() ?: (width / 2f), sel?.exactCenterY() ?: (height / 2f))
        }

        /**
         * Focus lines (wedges pointing at the centre) or speed lines (thin
         * lenses at [MangaParams.angle]). Focus lines leave the selection's
         * bounds clear when there is one, else an ellipse [MangaParams.clear]
         * of the canvas across; they never enter the selection's shape.
         */
        fun build(p: MangaParams, width: Int, height: Int, selection: android.graphics.Region?, color: Int): MangaArt {
            val w = width.toFloat()
            val h = height.toFloat()
            val rnd = java.util.Random(p.seed)
            val v = p.variation.coerceIn(0f, 1f)
            val count = p.count.coerceIn(1, 2000)
            val path = Path()
            val sel = selection?.takeIf { !it.isEmpty }
            if (!p.speed) {
                val def = defaultCenter(width, height, sel)
                val cx = if (p.centerX.isNaN()) def[0] else p.centerX
                val cy = if (p.centerY.isNaN()) def[1] else p.centerY
                val sb = sel?.bounds
                val rx = sb?.let { it.width() / 2f } ?: (w / 2f * p.clear.coerceIn(0f, 0.95f))
                val ry = sb?.let { it.height() / 2f } ?: (h / 2f * p.clear.coerceIn(0f, 0.95f))
                // Far enough to leave the canvas from any centre.
                val far = hypot(w, h) * 2f
                for (i in 0 until count) {
                    val a = (2.0 * Math.PI * (i + rnd.nextFloat() * 0.85f) / count).toFloat()
                    val ca = kotlin.math.cos(a)
                    val sa = kotlin.math.sin(a)
                    // Starts just outside the clear area, some further out.
                    val start = 1f + v * rnd.nextFloat() * 0.9f
                    val sx = cx + ca * rx * start
                    val sy = cy + sa * ry * start
                    val ex = cx + ca * far
                    val ey = cy + sa * far
                    val half = p.thickness * (1f - v * 0.7f + v * 0.7f * rnd.nextFloat() * 2f).coerceAtLeast(0.15f) / 2f
                    val px = -sa
                    val py = ca
                    path.moveTo(sx, sy)
                    path.lineTo(ex + px * half, ey + py * half)
                    path.lineTo(ex - px * half, ey - py * half)
                    path.close()
                }
            } else {
                val rad = Math.toRadians(p.angle.toDouble())
                val dx = kotlin.math.cos(rad).toFloat()
                val dy = kotlin.math.sin(rad).toFloat()
                val px = -dy
                val py = dx
                val span = abs(w * px) + abs(h * py)
                val len = abs(w * dx) + abs(h * dy)
                val band = span * p.clear.coerceIn(0f, 0.9f) / 2f
                var made = 0
                var tries = 0
                while (made < count && tries < count * 20) {
                    tries++
                    val t = (rnd.nextFloat() - 0.5f) * span
                    if (abs(t) < band) continue
                    val along = (rnd.nextFloat() - 0.5f) * len * 0.8f
                    val l = len * (0.9f - v * 0.6f * rnd.nextFloat()) / 2f
                    val half = p.thickness * (1f - v * 0.7f + v * 0.7f * rnd.nextFloat() * 2f).coerceAtLeast(0.15f) / 2f
                    val mx = w / 2f + px * t + dx * along
                    val my = h / 2f + py * t + dy * along
                    path.moveTo(mx - dx * l, my - dy * l)
                    path.lineTo(mx + px * half, my + py * half)
                    path.lineTo(mx + dx * l, my + dy * l)
                    path.lineTo(mx - px * half, my - py * half)
                    path.close()
                    made++
                }
            }
            val keepOut = if (!p.speed && sel != null) SelectionUtil.regionFillPath(sel) else null
            return MangaArt(width, height, path, keepOut, color, if (p.speed) "Speed lines" else "Focus lines")
        }
    }
}

/** White page, clear panels, black borders. */
class FramesArt(width: Int, height: Int, private val panels: List<RectF>, private val border: Float) :
    VectorArt("Frames", width, height) {
    private val white = Paint().apply { color = 0xFFFFFFFF.toInt() }
    private val clear = Paint().apply { blendMode = BlendMode.CLEAR }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = border.coerceAtLeast(0.5f)
        color = 0xFF000000.toInt()
        strokeJoin = Paint.Join.MITER
    }

    override fun draw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), white)
        for (p in panels) c.drawRect(p, clear)
        for (p in panels) c.drawRect(p, line)
    }

    companion object {
        fun build(layout: FramesLayout, width: Int, height: Int): FramesArt {
            val geo = layout.geometry(width, height)
            return FramesArt(width, height, layout.panels.map { layout.rectOf(it, geo) }, layout.border)
        }
    }
}

/**
 * A material over [area] (the selection's bounds, or the canvas), kept
 * inside [selection] when there is one. Full size it is exact per pixel;
 * smaller previews average a few samples per pixel so fine tones read as
 * their grey instead of moiré.
 */
class MaterialArt(
    width: Int, height: Int,
    private val material: Material,
    private val area: Rect,
    private val color: Int,
    private val selection: android.graphics.Region?
) : InsertArt(material.kind.label, width, height) {

    private val keep: Path? = selection?.let { SelectionUtil.regionFillPath(it) }

    override fun render(out: Bitmap, left: Float, top: Float, scale: Float) {
        val w = out.width
        val h = out.height
        val px = IntArray(w * h)
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val exact = scale >= 0.999f
        parallelRows(h) { y0, y1 ->
            for (y in y0 until y1) {
                for (x in 0 until w) {
                    val cov: Float
                    if (exact) {
                        val dx = (left + x / scale).toInt()
                        val dy = (top + y / scale).toInt()
                        cov = if (dx < area.left || dy < area.top || dx >= area.right || dy >= area.bottom) 0f
                        else material.coverage(dx, dy)
                    } else {
                        var sum = 0f
                        for (k in 0 until 4) {
                            val sx = left + (x + 0.25f + 0.5f * (k and 1)) / scale
                            val sy = top + (y + 0.25f + 0.5f * (k shr 1)) / scale
                            if (sx < area.left || sy < area.top || sx >= area.right || sy >= area.bottom) continue
                            sum += material.coverageAt(sx, sy)
                        }
                        cov = sum / 4f
                    }
                    val a = (cov.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                    px[y * w + x] = if (a == 0) 0 else (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        val k = keep ?: return
        val c = Canvas(out)
        c.scale(scale, scale)
        c.translate(-left, -top)
        c.clipOutPath(k)
        c.drawColor(0, BlendMode.CLEAR)
    }

    override fun bake(layer: Layer) {
        Kernels.generate(width, height, area, { chunk, out -> material.fill(chunk, color, out) }) { r, bmp ->
            layer.tiles.writeRect(r, bmp, Rect(0, 0, r.width(), r.height()))
        }
        // Only inside the selection's real shape, not just its bounds.
        val k = keep ?: return
        layer.tiles.withWriteCanvas(area) { c ->
            val save = c.save()
            c.clipOutPath(k)
            c.drawColor(0, BlendMode.CLEAR)
            c.restoreToCount(save)
        }
    }

    companion object {
        fun build(p: MaterialParams, width: Int, height: Int, selection: android.graphics.Region?, currentColor: Int): MaterialArt {
            val full = Rect(0, 0, width, height)
            val sel = selection?.takeIf { !it.isEmpty }
            val area = sel?.bounds?.let { Rect(it) }?.takeIf { it.intersect(full) } ?: full
            val color = when (p.ink) {
                1 -> 0xFF000000.toInt()
                2 -> 0xFFFFFFFF.toInt()
                else -> currentColor or 0xFF000000.toInt()
            }
            return MaterialArt(width, height, Material(p.kind, p.size, p.angle, p.density, area, p.seed), area, color, sel)
        }
    }
}

/** The live preview drawn above layer [aboveLayerId]: a whole-canvas [overview] plus a sharper [patch] where the view is. */
class InsertImage(
    val aboveLayerId: Long,
    val overview: Bitmap,
    val patch: Bitmap?,
    val patchRect: RectF?
)
