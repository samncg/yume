package com.yume.app.engine

/**
 * Debug-only frame-time logger.
 *
 * Records frame times while a stroke is active and reports a rolling fps plus
 * p50/p95/p99/max frame time. Everything is off unless [enabled] is set, and
 * the sample buffer is a fixed-size ring, so recording never allocates.
 *
 * On device the render loop feeds it the start-to-start frame interval (which
 * includes the idle wait, i.e. the real refresh cadence). The harness feeds it
 * the measured work of a simulated frame (paint + composite + blit), so dense
 * and sparse builds can be compared without a display.
 */
object FrameLogger {

    /** Master switch. Off by default; nothing is measured while it is false. */
    @Volatile var enabled: Boolean = false

    /** Frames are only recorded while a stroke is in flight. */
    @Volatile var strokeActive: Boolean = false

    private const val CAPACITY = 2048
    private val samples = LongArray(CAPACITY)
    private var count = 0
    private var head = 0

    fun reset() {
        count = 0
        head = 0
    }

    /** Records one frame that took [nanos]. No-op when disabled or idle. */
    fun record(nanos: Long) {
        if (!enabled || !strokeActive) return
        if (nanos <= 0L) return
        samples[head] = nanos
        head = (head + 1) % CAPACITY
        if (count < CAPACITY) count++
    }

    /** Records a frame measured from [thenNanos]. */
    fun recordSince(thenNanos: Long) {
        if (!enabled || !strokeActive) return
        record(System.nanoTime() - thenNanos)
    }

    val sampleCount: Int get() = count

    /** A percentile of the recorded window, in milliseconds. */
    @Synchronized
    fun percentileMillis(q: Double): Double {
        if (count == 0) return 0.0
        val copy = LongArray(count)
        for (i in 0 until count) copy[i] = samples[i]
        copy.sort()
        val i = ((count - 1) * q).toInt().coerceIn(0, count - 1)
        return copy[i] / 1_000_000.0
    }

    @Synchronized
    fun maxMillis(): Double {
        if (count == 0) return 0.0
        var m = 0L
        for (i in 0 until count) if (samples[i] > m) m = samples[i]
        return m / 1_000_000.0
    }

    /** One-line summary; empty sample window reports `frames=0`. */
    fun summary(): String {
        if (count == 0) return "frames=0"
        val copy = LongArray(count)
        for (i in 0 until count) copy[i] = samples[i]
        copy.sort()
        var sum = 0L
        for (v in copy) sum += v
        val mean = sum.toDouble() / count
        fun p(q: Double): Double {
            val i = ((count - 1) * q).toInt().coerceIn(0, count - 1)
            return copy[i] / 1_000_000.0
        }
        val fps = if (mean > 0.0) 1_000_000_000.0 / mean else 0.0
        return "frames=$count fps=%.1f p50=%.2fms p95=%.2fms p99=%.2fms max=%.2fms"
            .format(java.util.Locale.US, fps, p(0.50), p(0.95), p(0.99), p(1.0))
    }
}
