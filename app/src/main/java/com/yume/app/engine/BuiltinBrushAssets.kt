package com.yume.app.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Stamp shapes and grain textures the built-in brushes use, drawn here once
 * (deterministically) and stored like imported assets, under fixed ids.
 */
object BuiltinBrushAssets {
    private const val VERSION = 1

    fun ensure(root: File) {
        val marker = File(root, "brush_builtin_v$VERSION")
        if (marker.exists()) return
        shapes().forEach { (id, bmp) -> BrushAssets.put("shapes", id, BrushAssets.pngBytes(bmp)); bmp.recycle() }
        grains().forEach { (id, bmp) -> BrushAssets.put("grains", id, BrushAssets.pngBytes(bmp)); bmp.recycle() }
        runCatching { marker.writeText("ok") }
    }

    private const val R = BrushAssets.RES

    private fun canvasOf(): Pair<Bitmap, Canvas> {
        val b = Bitmap.createBitmap(R, R, Bitmap.Config.ARGB_8888)
        return b to Canvas(b)
    }

    private fun white(alpha: Int = 255) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x00FFFFFF or (alpha shl 24)
    }

    private fun shapes(): List<Pair<String, Bitmap>> {
        val out = ArrayList<Pair<String, Bitmap>>()
        // Flat nib: a thin ellipse.
        canvasOf().let { (b, c) ->
            c.drawOval(RectF(R * 0.04f, R * 0.41f, R * 0.96f, R * 0.59f), white())
            out.add("yume-flat" to b)
        }
        // Chalk: a rough disc with bites taken out of it.
        canvasOf().let { (b, c) ->
            val rnd = Random(11)
            val p = Path()
            for (i in 0..72) {
                val a = i / 72.0 * 2 * PI
                val r = R * (0.36f + rnd.nextFloat() * 0.1f)
                val x = R / 2f + (cos(a) * r).toFloat()
                val y = R / 2f + (sin(a) * r).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            c.drawPath(p, white())
            val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = android.graphics.BlendMode.CLEAR }
            repeat(70) {
                val a = rnd.nextDouble() * 2 * PI
                val d = sqrt(rnd.nextDouble()) * R * 0.42
                c.drawCircle(R / 2f + (cos(a) * d).toFloat(), R / 2f + (sin(a) * d).toFloat(), R * (0.01f + rnd.nextFloat() * 0.03f), hole)
            }
            out.add("yume-chalk" to b)
        }
        // Splatter: drops of many sizes, denser in the middle.
        canvasOf().let { (b, c) ->
            val rnd = Random(23)
            repeat(46) {
                val a = rnd.nextDouble() * 2 * PI
                val d = rnd.nextDouble().let { it * it } * R * 0.44
                val r = R * (0.008f + rnd.nextFloat().let { it * it * it } * 0.09f)
                c.drawCircle(R / 2f + (cos(a) * d).toFloat(), R / 2f + (sin(a) * d).toFloat(), r, white())
            }
            out.add("yume-splatter" to b)
        }
        // Bristle: a row of soft hair tips across the stamp.
        canvasOf().let { (b, c) ->
            val rnd = Random(37)
            val n = 18
            for (i in 0 until n) {
                val x = R * (0.06f + 0.88f * i / (n - 1)) + (rnd.nextFloat() - 0.5f) * 6f
                val y = R / 2f + (rnd.nextFloat() - 0.5f) * R * 0.14f
                val r = R * (0.02f + rnd.nextFloat() * 0.025f)
                c.drawCircle(x, y, r, white(150 + rnd.nextInt(105)))
            }
            out.add("yume-bristle" to b)
        }
        // Hatch: three parallel strokes.
        canvasOf().let { (b, c) ->
            for (k in 0 until 3) {
                val y = R * (0.3f + k * 0.2f)
                c.drawRoundRect(RectF(R * 0.08f, y - R * 0.025f, R * 0.92f, y + R * 0.025f), R * 0.03f, R * 0.03f, white())
            }
            out.add("yume-hatch" to b)
        }
        // Foliage: a cluster of small leaves.
        canvasOf().let { (b, c) ->
            val rnd = Random(53)
            repeat(16) {
                val a = rnd.nextDouble() * 2 * PI
                val d = rnd.nextDouble() * R * 0.3
                val cx = R / 2f + (cos(a) * d).toFloat()
                val cy = R / 2f + (sin(a) * d).toFloat()
                val len = R * (0.08f + rnd.nextFloat() * 0.08f)
                c.save()
                c.rotate(rnd.nextFloat() * 360f, cx, cy)
                c.drawOval(RectF(cx - len, cy - len * 0.38f, cx + len, cy + len * 0.38f), white(180 + rnd.nextInt(75)))
                c.restore()
            }
            out.add("yume-foliage" to b)
        }
        // Star: four-pointed sparkle.
        canvasOf().let { (b, c) ->
            val p = Path()
            val cx = R / 2f
            for (i in 0 until 8) {
                val a = i / 8.0 * 2 * PI - PI / 2
                val r = if (i % 2 == 0) R * 0.46f else R * 0.09f
                val x = cx + (cos(a) * r).toFloat()
                val y = cx + (sin(a) * r).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            c.drawPath(p, white())
            out.add("yume-star" to b)
        }
        return out
    }

    private fun grains(): List<Pair<String, Bitmap>> {
        val out = ArrayList<Pair<String, Bitmap>>()
        // Paper: smooth value noise at two scales.
        out.add("yume-paper" to noiseBitmap(7, floatArrayOf(4f, 16f, 48f), floatArrayOf(0.5f, 0.35f, 0.15f)))
        // Sponge: big soft holes.
        out.add("yume-sponge" to noiseBitmap(19, floatArrayOf(10f, 26f), floatArrayOf(0.6f, 0.4f), contrast = 2.2f))
        // Canvas: a woven pattern with a little noise.
        val px = IntArray(R * R)
        val rnd = Random(29)
        for (y in 0 until R) for (x in 0 until R) {
            val wx = sin(x * 2 * PI / 8).toFloat()
            val wy = sin(y * 2 * PI / 8).toFloat()
            val weave = if (((x / 8) + (y / 8)) % 2 == 0) wx * 0.5f + 0.5f else wy * 0.5f + 0.5f
            val v = (0.25f + 0.65f * weave + (rnd.nextFloat() - 0.5f) * 0.15f).coerceIn(0f, 1f)
            px[y * R + x] = ((v * 255).toInt() shl 24) or 0x00FFFFFF
        }
        out.add("yume-canvas" to Bitmap.createBitmap(px, R, R, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true))
        return out
    }

    /** Tileable value noise: a sum of [scales] (cells per side) with [weights]. */
    private fun noiseBitmap(seed: Int, scales: FloatArray, weights: FloatArray, contrast: Float = 1.4f): Bitmap {
        val acc = FloatArray(R * R)
        val rnd = Random(seed)
        for ((si, cells) in scales.withIndex()) {
            val n = cells.toInt()
            val grid = FloatArray(n * n) { rnd.nextFloat() }
            for (y in 0 until R) for (x in 0 until R) {
                val gx = x.toFloat() / R * n
                val gy = y.toFloat() / R * n
                val x0 = gx.toInt() % n
                val y0 = gy.toInt() % n
                val x1 = (x0 + 1) % n
                val y1 = (y0 + 1) % n
                val fx = (gx - gx.toInt()).let { it * it * (3 - 2 * it) }
                val fy = (gy - gy.toInt()).let { it * it * (3 - 2 * it) }
                val top = grid[y0 * n + x0] * (1 - fx) + grid[y0 * n + x1] * fx
                val bot = grid[y1 * n + x0] * (1 - fx) + grid[y1 * n + x1] * fx
                acc[y * R + x] += (top * (1 - fy) + bot * fy) * weights[si]
            }
        }
        val px = IntArray(R * R)
        for (i in px.indices) {
            val v = ((acc[i] - 0.5f) * contrast + 0.5f).coerceIn(0f, 1f)
            px[i] = ((v * 255).toInt() shl 24) or 0x00FFFFFF
        }
        return Bitmap.createBitmap(px, R, R, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
    }

    @Suppress("unused")
    private fun clamp(v: Float) = max(0f, min(1f, v))
}
