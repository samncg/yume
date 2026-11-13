package com.yume.app.input

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Squeeze and double-tap on a smart pen (Xiaomi Focus Pen Pro and similar).
 *
 * There is no public API for these gestures. The pen pairs as a Bluetooth
 * keyboard ("Xiaomi Focus Pen Pro Keyboard") and mouse. Measured on a Xiaomi
 * pad (HyperOS, Nov 2026): pinch sends raw F19, which the pen's key map turns
 * into KEYCODE_BUTTON_7; double-press sends KP_ENTER -> KEYCODE_BUTTON_8. The
 * codes do not change with the HyperOS gesture setting.
 *
 * HyperOS only passes these keys to apps on its stylus whitelist
 * (MiuiStylusShortcutManager, mAppWhiteSet); everywhere else it consumes them
 * for its own shortcuts (pinch opens the Quick App Panel). Yume is not on that
 * list, so on stock HyperOS nothing arrives here yet. The handling stays so it
 * works the day the keys are delivered, and for other pens that send keys;
 * Teach covers pens whose keys differ.
 */
object PenGestures {

    enum class Gesture(val label: String) { Squeeze("Pinch"), DoubleTap("Double-press") }

    enum class Action(val label: String) {
        Menu("Menu"), Eraser("Eraser"), Undo("Undo"), Off("Off")
    }

    const val KEY_UNSET = -1

    /** Mouse-button presses from the pen are stored as this base plus the button bit. */
    private const val BUTTON_BASE = 100_000

    private const val TAG = "YumePen"
    private const val PREFS = "pen_gestures"

    private val DEFAULT_SQUEEZE = setOf(KeyEvent.KEYCODE_BUTTON_7, KeyEvent.KEYCODE_F19)
    private val DEFAULT_DOUBLE_TAP = setOf(
        KeyEvent.KEYCODE_BUTTON_8, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL
    )

    /** Keys a pen gesture must never take over. */
    private val SYSTEM_KEYS = setOf(
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_SLEEP,
        KeyEvent.KEYCODE_ESCAPE
    )

    private var prefs: SharedPreferences? = null

    var squeezeAction by mutableStateOf(Action.Menu)
        private set
    var doubleTapAction by mutableStateOf(Action.Eraser)
        private set
    var squeezeKey by mutableIntStateOf(KEY_UNSET)
        private set
    var doubleTapKey by mutableIntStateOf(KEY_UNSET)
        private set

    /** The gesture waiting to be taught, or null. */
    var learning by mutableStateOf<Gesture?>(null)

    /** The last pen key that is not mapped to a gesture (settings hint). */
    var lastUnknownKey by mutableIntStateOf(KEY_UNSET)
        private set

    /** Where the pen last hovered or touched, in canvas-view px (-1 when unknown). */
    @Volatile var lastX = -1f
    @Volatile var lastY = -1f

    /** The open editor's handler; null outside the editor. */
    var listener: ((Gesture, Action) -> Unit)? = null

    /** Opens the shortcut ring at canvas-view px (two-finger hold); null outside the editor. */
    var onMenuAt: ((Float, Float) -> Unit)? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        squeezeAction = actionOf(p.getString("squeeze_action", null), Action.Menu)
        doubleTapAction = actionOf(p.getString("double_tap_action", null), Action.Eraser)
        squeezeKey = p.getInt("squeeze_key", KEY_UNSET)
        doubleTapKey = p.getInt("double_tap_key", KEY_UNSET)
    }

    private fun actionOf(name: String?, fallback: Action): Action =
        Action.entries.firstOrNull { it.name == name } ?: fallback

    fun actionFor(g: Gesture): Action = if (g == Gesture.Squeeze) squeezeAction else doubleTapAction

    fun keyFor(g: Gesture): Int = if (g == Gesture.Squeeze) squeezeKey else doubleTapKey

    fun setAction(g: Gesture, a: Action) {
        if (g == Gesture.Squeeze) squeezeAction = a else doubleTapAction = a
        prefs?.edit()?.putString(if (g == Gesture.Squeeze) "squeeze_action" else "double_tap_action", a.name)?.apply()
    }

    private fun setKey(g: Gesture, code: Int) {
        // One key, one gesture: teaching a key moves it off the other gesture.
        if (g == Gesture.Squeeze) {
            squeezeKey = code
            if (doubleTapKey == code) doubleTapKey = KEY_UNSET
        } else {
            doubleTapKey = code
            if (squeezeKey == code) squeezeKey = KEY_UNSET
        }
        prefs?.edit()
            ?.putInt("squeeze_key", squeezeKey)
            ?.putInt("double_tap_key", doubleTapKey)
            ?.apply()
    }

    fun resetKeys() {
        setKey(Gesture.Squeeze, KEY_UNSET)
        setKey(Gesture.DoubleTap, KEY_UNSET)
        lastUnknownKey = KEY_UNSET
    }

    fun keyLabel(code: Int): String = when {
        code == KEY_UNSET -> "default keys"
        code >= BUTTON_BASE -> "pen button ${code - BUTTON_BASE}"
        else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
    }

    private fun isPenDevice(device: InputDevice?): Boolean {
        val name = device?.name?.lowercase() ?: return false
        if (device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) return false
        return "pen" in name || "stylus" in name
    }

    private fun resolve(code: Int): Gesture? = when {
        code == squeezeKey -> Gesture.Squeeze
        code == doubleTapKey -> Gesture.DoubleTap
        squeezeKey == KEY_UNSET && code in DEFAULT_SQUEEZE -> Gesture.Squeeze
        doubleTapKey == KEY_UNSET && code in DEFAULT_DOUBLE_TAP -> Gesture.DoubleTap
        else -> null
    }

    /** Returns true when the key was a pen gesture (or pen noise) and is consumed. */
    fun handleKey(event: KeyEvent, haptic: () -> Unit): Boolean {
        val code = event.keyCode
        if (code in SYSTEM_KEYS) return false
        val fromPen = isPenDevice(event.device)
        if (com.yume.app.BuildConfig.DEBUG) Log.i(TAG, "key ${KeyEvent.keyCodeToString(code)} ($code) action=${event.action} repeat=${event.repeatCount} scan=${event.scanCode} meta=${event.metaState} device=${event.device?.name} id=${event.deviceId} source=0x${Integer.toHexString(event.source)}")
        return dispatch(code, fromPen, event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0, haptic)
    }

    /** Mouse-button presses from the pen's mouse device. */
    fun handleButton(event: MotionEvent, haptic: () -> Unit): Boolean {
        val action = event.actionMasked
        if (action != MotionEvent.ACTION_BUTTON_PRESS && action != MotionEvent.ACTION_BUTTON_RELEASE) return false
        if (!isPenDevice(event.device)) return false
        if (com.yume.app.BuildConfig.DEBUG) Log.i(TAG, "button ${event.actionButton} action=$action device=${event.device?.name}")
        return dispatch(BUTTON_BASE + event.actionButton, true, action == MotionEvent.ACTION_BUTTON_PRESS, haptic)
    }

    private fun dispatch(code: Int, fromPen: Boolean, press: Boolean, haptic: () -> Unit): Boolean {
        val listening = listener ?: return false
        val teach = learning
        if (teach != null) {
            if (press) {
                setKey(teach, code)
                learning = null
                haptic()
            }
            return true
        }
        val gesture = resolve(code)
        if (gesture == null) {
            if (fromPen && press) lastUnknownKey = code
            return fromPen
        }
        if (press) {
            val action = actionFor(gesture)
            if (action != Action.Off) {
                haptic()
                listening(gesture, action)
            }
        }
        return true
    }
}
