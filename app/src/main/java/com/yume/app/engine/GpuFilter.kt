package com.yume.app.engine

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.util.Log
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The first piece of the GPU engine: runs image filters on the GPU through an
 * offscreen [HardwareRenderer] and reads the result back into a normal bitmap.
 *
 * Used for the live previews of the adjustments (blur, sharpen, motion blur),
 * which were CPU work on every slider move. The applied result is still baked
 * by the CPU kernels, which are exact and match the golden hashes; the GPU
 * only has to look right.
 *
 * Needs Android 13 (AGSL shaders). Any failure turns the GPU off for the rest
 * of the run and callers fall back to the CPU, so a driver problem never
 * breaks an edit.
 */
object GpuFilter {
    private const val TAG = "YumeGpu"

    @Volatile private var broken = false

    val available: Boolean get() = !broken && Build.VERSION.SDK_INT >= 33

    /** Kept between calls: setting a renderer up costs more than a filter. */
    private var renderer: HardwareRenderer? = null
    private var reader: ImageReader? = null
    private var rw = 0
    private var rh = 0
    private val node = RenderNode("yume-gpu-filter")

    /**
     * [source] filtered by [effect], as a new software bitmap the size of
     * [source], or null when the GPU is not usable (the caller does the CPU
     * version). [source] is not changed.
     */
    @Synchronized
    @androidx.annotation.RequiresApi(33)
    fun apply(source: Bitmap, effect: RenderEffect): Bitmap? {
        if (!available) return null
        val w = source.width
        val h = source.height
        return try {
            ensureTarget(w, h)
            node.setPosition(0, 0, w, h)
            node.setRenderEffect(effect)
            val c = node.beginRecording(w, h)
            try {
                c.drawBitmap(source, 0f, 0f, null)
            } finally {
                node.endRecording()
            }
            val r = renderer!!
            r.setContentRoot(node)
            val sync = r.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            if (sync != HardwareRenderer.SYNC_OK && sync != HardwareRenderer.SYNC_FRAME_DROPPED) {
                throw IllegalStateException("syncAndDraw $sync")
            }
            val image = reader!!.acquireLatestImage() ?: throw IllegalStateException("no image")
            image.use {
                val hb = it.hardwareBuffer ?: throw IllegalStateException("no buffer")
                hb.use { buf ->
                    val hw = Bitmap.wrapHardwareBuffer(buf, ColorSpace.get(ColorSpace.Named.SRGB))
                        ?: throw IllegalStateException("wrap failed")
                    val out = hw.copy(Bitmap.Config.ARGB_8888, true)
                    hw.recycle()
                    out
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "GPU filter failed, using the CPU from now on", t)
            broken = true
            release()
            null
        }
    }

    @androidx.annotation.RequiresApi(33)
    private fun ensureTarget(w: Int, h: Int) {
        if (renderer != null && rw == w && rh == h) return
        release()
        val rd = ImageReader.newInstance(
            w, h, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        val r = HardwareRenderer()
        r.isOpaque = false
        r.setSurface(rd.surface)
        reader = rd
        renderer = r
        rw = w
        rh = h
    }

    /** Frees the offscreen target (the next filter makes a new one). */
    @Synchronized
    fun release() {
        runCatching { renderer?.destroy() }
        runCatching { reader?.close() }
        renderer = null
        reader = null
        rw = 0
        rh = 0
    }

    // ------------------------------------------------------------ effects

    /**
     * Gaussian matching [Adjustments.blur]'s three box passes of [radius] px:
     * their sigma is sqrt(r (r + 1)). [scale] is proxy px per canvas px.
     */
    @androidx.annotation.RequiresApi(33)
    fun blurEffect(radius: Int, scale: Float): RenderEffect {
        val sigma = sqrt(radius.toFloat() * (radius + 1)) * scale
        // Skia turns a blur "radius" into sigma = 0.57735 r + 0.5.
        val r = ((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.01f)
        return RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL)
    }

    private const val SHARPEN = """
        uniform shader content;
        uniform float strength;
        half4 main(float2 p) {
            half4 c = content.eval(p);
            half3 b = half3(0);
            // The same 1-4-6-4-1 kernel as Kernels.sharpen.
            for (int j = -2; j <= 2; j++) {
                float wy = j == 0 ? 6.0 : (j == 1 || j == -1 ? 4.0 : 1.0);
                for (int i = -2; i <= 2; i++) {
                    float wx = i == 0 ? 6.0 : (i == 1 || i == -1 ? 4.0 : 1.0);
                    b += content.eval(p + float2(i, j)).rgb * half(wx * wy / 256.0);
                }
            }
            half3 r = c.rgb + (c.rgb - b) * half(strength);
            return half4(clamp(r, half3(0), half3(c.a)), c.a);
        }
    """

    /** Unsharp mask like [Kernels.sharpen] at [strength] (0..2). */
    @androidx.annotation.RequiresApi(33)
    fun sharpenEffect(strength: Float): RenderEffect {
        val s = RuntimeShader(SHARPEN)
        s.setFloatUniform("strength", strength)
        return RenderEffect.createRuntimeShaderEffect(s, "content")
    }

    private const val NOISE = """
        uniform shader content;
        uniform float strength;
        half4 main(float2 p) {
            half4 c = content.eval(p);
            if (c.a <= 0.0) { return c; }
            float2 q = floor(p);
            float n = fract(sin(dot(q, float2(12.9898, 78.233))) * 43758.5453);
            half d = half((n * 2.0 - 1.0) * strength);
            half3 rgb = clamp(c.rgb / c.a + half3(d), half3(0), half3(1));
            return half4(rgb * c.a, c.a);
        }
    """

    /** Film grain of up to +-[strength] (0..1) per pixel, like the noise bake. */
    @androidx.annotation.RequiresApi(33)
    fun noiseEffect(strength: Float): RenderEffect {
        val s = RuntimeShader(NOISE)
        s.setFloatUniform("strength", strength)
        return RenderEffect.createRuntimeShaderEffect(s, "content")
    }

    private const val MOTION = """
        uniform shader content;
        uniform float2 dir;
        uniform float radius;
        half4 main(float2 p) {
            half4 sum = half4(0);
            float n = 0.0;
            for (int i = -64; i <= 64; i++) {
                float fi = float(i);
                if (abs(fi) <= radius) {
                    sum += content.eval(p + dir * fi);
                    n += 1.0;
                }
            }
            return sum / half(n);
        }
    """

    /** Averages along a line like [Kernels.motionBlur]: [radius] proxy px at [angleDeg]. */
    @androidx.annotation.RequiresApi(33)
    fun motionEffect(radius: Float, angleDeg: Float): RenderEffect {
        val s = RuntimeShader(MOTION)
        val a = Math.toRadians(angleDeg.toDouble())
        // Past 64 taps the step grows instead (a preview, so close is fine).
        val taps = radius.coerceAtMost(64f)
        val stride = if (taps > 0f) radius / taps else 1f
        s.setFloatUniform("dir", (cos(a) * stride).toFloat(), (sin(a) * stride).toFloat())
        s.setFloatUniform("radius", taps)
        return RenderEffect.createRuntimeShaderEffect(s, "content")
    }
}
