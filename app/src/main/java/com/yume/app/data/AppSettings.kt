package com.yume.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * App-wide preferences (Settings page). Every value is snapshot state, so the
 * UI recomposes when it changes, and plain reads from the input and render
 * code are cheap. Writes are persisted immediately.
 */
object AppSettings {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        val p = context.getSharedPreferences("yume_settings", Context.MODE_PRIVATE)
        prefs = p
        theme = p.getString("theme", theme) ?: theme
        accent = p.getString("accent", accent) ?: accent
        motionFiltering = p.getFloat("motionFiltering", motionFiltering)
        prediction = p.getBoolean("prediction", prediction)
        pressureCurve = p.getFloat("pressureCurve", pressureCurve)
        quickShapeHoldMs = p.getInt("quickShapeHoldMs", quickShapeHoldMs)
        eyedropperHoldMs = p.getInt("eyedropperHoldMs", eyedropperHoldMs)
        twoFingerUndo = p.getBoolean("twoFingerUndo", twoFingerUndo)
        threeFingerRedo = p.getBoolean("threeFingerRedo", threeFingerRedo)
        threeFingerScrub = p.getBoolean("threeFingerScrub", threeFingerScrub)
        fourFingerUi = p.getBoolean("fourFingerUi", fourFingerUi)
        holdToRepeatUndo = p.getBoolean("holdToRepeatUndo", holdToRepeatUndo)
        railOnRight = p.getBoolean("railOnRight", railOnRight)
        uiScale = p.getFloat("uiScale", uiScale)
    }

    private fun put(block: SharedPreferences.Editor.() -> Unit) {
        prefs?.edit()?.apply(block)?.apply()
    }

    // ------------------------------------------------------------ look

    /** Background family: see [com.yume.app.ui.YumeThemes]. */
    var theme by mutableStateOf("graphite")
        private set
    fun setThemeId(v: String) { theme = v; put { putString("theme", v) } }

    /** Accent colour: see [com.yume.app.ui.YumeThemes]. */
    var accent by mutableStateOf("amber")
        private set
    fun setAccentId(v: String) { accent = v; put { putString("accent", v) } }

    /** Size of the editor's toolbars and panels (0.85 .. 1.15). */
    var uiScale by mutableFloatStateOf(1f)
        private set
    fun setUiScaleValue(v: Float) { uiScale = v.coerceIn(0.85f, 1.15f); put { putFloat("uiScale", uiScale) } }

    /** The size / opacity rail sits on the right edge (left-handed use). */
    var railOnRight by mutableStateOf(false)
        private set
    fun setRailOnRightValue(v: Boolean) { railOnRight = v; put { putBoolean("railOnRight", v) } }

    // ------------------------------------------------------------ drawing

    /**
     * Motion filtering, 0 (off) .. 1 (strongest): an adaptive low-pass filter
     * on the pen path that removes hand tremor while slow, and stays out of
     * the way of fast, deliberate strokes.
     */
    var motionFiltering by mutableFloatStateOf(0.35f)
        private set
    fun setMotionFilteringValue(v: Float) { motionFiltering = v.coerceIn(0f, 1f); put { putFloat("motionFiltering", motionFiltering) } }

    /** Draws the stroke a few milliseconds ahead of the pen while it moves. */
    var prediction by mutableStateOf(true)
        private set
    fun setPredictionValue(v: Boolean) { prediction = v; put { putBoolean("prediction", v) } }

    /** Pressure response: output = pressure ^ curve (1 = linear, < 1 = lighter touch). */
    var pressureCurve by mutableFloatStateOf(1f)
        private set
    fun setPressureCurveValue(v: Float) { pressureCurve = v.coerceIn(0.4f, 2.5f); put { putFloat("pressureCurve", pressureCurve) } }

    /** How long the pen rests before QuickShape snaps. */
    var quickShapeHoldMs by mutableIntStateOf(450)
        private set
    fun setQuickShapeHold(v: Int) { quickShapeHoldMs = v.coerceIn(250, 1200); put { putInt("quickShapeHoldMs", quickShapeHoldMs) } }

    /** How long a held stroke takes to become the eyedropper. */
    var eyedropperHoldMs by mutableIntStateOf(750)
        private set
    fun setEyedropperHold(v: Int) { eyedropperHoldMs = v.coerceIn(300, 1500); put { putInt("eyedropperHoldMs", eyedropperHoldMs) } }

    // ------------------------------------------------------------ gestures

    var twoFingerUndo by mutableStateOf(true)
        private set
    fun updateTwoFingerUndo(v: Boolean) { twoFingerUndo = v; put { putBoolean("twoFingerUndo", v) } }

    var threeFingerRedo by mutableStateOf(true)
        private set
    fun updateThreeFingerRedo(v: Boolean) { threeFingerRedo = v; put { putBoolean("threeFingerRedo", v) } }

    /** Three fingers dragged sideways scrub through history (left undo, right redo). */
    var threeFingerScrub by mutableStateOf(true)
        private set
    fun updateThreeFingerScrub(v: Boolean) { threeFingerScrub = v; put { putBoolean("threeFingerScrub", v) } }

    var fourFingerUi by mutableStateOf(true)
        private set
    fun updateFourFingerUi(v: Boolean) { fourFingerUi = v; put { putBoolean("fourFingerUi", v) } }

    /** Holding the undo / redo buttons keeps stepping, faster the longer they are held. */
    var holdToRepeatUndo by mutableStateOf(true)
        private set
    fun updateHoldToRepeatUndo(v: Boolean) { holdToRepeatUndo = v; put { putBoolean("holdToRepeatUndo", v) } }
}
