package com.yume.app.engine

/**
 * Cooperative cancel for long render-thread operations (fill, auto-select,
 * adjustments, merge…). The UI sets [requested]; the operation's tile loops
 * call [check], which throws [OperationCancelled]; the operation then puts
 * every tile it already changed back, so a cancel leaves no trace.
 */
object OpCancel {
    /** An operation that can be cancelled is running now. */
    @Volatile var armed: Boolean = false
        private set

    /** The user asked to stop the running operation. */
    @Volatile var requested: Boolean = false

    /**
     * How far the running operation has got, 0..1 (-1 = unknown). Long
     * kernels report it so the working card can show a real countdown.
     */
    @Volatile var progress: Float = -1f

    fun check() {
        if (requested && armed) throw OperationCancelled()
    }

    private var depth = 0

    /** Nested operations (a fill that first commits an adjustment) share one arm. */
    internal fun arm() {
        if (depth++ == 0) {
            requested = false
            progress = -1f
        }
        armed = true
    }

    internal fun disarm() {
        if (--depth <= 0) {
            depth = 0
            armed = false
            requested = false
            progress = -1f
        }
    }
}

class OperationCancelled : RuntimeException("cancelled by the user")
