package com.yume.app.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Model downloads in the background, shared by every screen: the panel that
 * started one can close and the download carries on. One at a time per model.
 */
object AiDownloads {
    data class Status(
        val running: Boolean = false,
        val done: Long = 0L,
        val total: Long = 0L,
        val error: String? = null,
        /** Bumped when a model finished installing (screens re-check readiness). */
        val installed: Int = 0
    ) {
        val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    private val _status = MutableStateFlow<Map<AiModel, Status>>(emptyMap())
    val status: StateFlow<Map<AiModel, Status>> = _status

    @Volatile private var store: ModelStore? = null
    private val cancelled = HashSet<AiModel>()

    fun init(s: ModelStore) {
        store = s
    }

    fun storeOrNull(): ModelStore? = store

    fun isReady(model: AiModel): Boolean = store?.isReady(model) == true

    private fun set(model: AiModel, f: (Status) -> Status) {
        _status.update { m -> m + (model to f(m[model] ?: Status())) }
    }

    fun start(model: AiModel) {
        val s = store ?: return
        if (s.isReady(model)) return
        synchronized(this) {
            if (_status.value[model]?.running == true) return
            cancelled.remove(model)
        }
        set(model) { it.copy(running = true, error = null, done = 0L, total = model.bytes) }
        Thread({
            try {
                s.download(model, cancelled = { synchronized(this) { model in cancelled } }) { done, total ->
                    set(model) { it.copy(done = done, total = total) }
                }
                set(model) { it.copy(running = false, done = model.bytes, installed = it.installed + 1) }
            } catch (c: ModelStore.Cancelled) {
                set(model) { it.copy(running = false, error = null) }
            } catch (t: Throwable) {
                val msg = when (t) {
                    is java.net.UnknownHostException -> "No internet connection"
                    is java.net.SocketTimeoutException -> "The connection timed out"
                    else -> t.message ?: t.javaClass.simpleName
                }
                set(model) { it.copy(running = false, error = msg) }
            }
        }, "yume-model-${model.name}").apply { isDaemon = true }.start()
    }

    fun cancel(model: AiModel) {
        synchronized(this) { cancelled.add(model) }
    }

    fun delete(model: AiModel) {
        cancel(model)
        store?.delete(model)
        set(model) { Status(installed = it.installed + 1) }
    }
}
