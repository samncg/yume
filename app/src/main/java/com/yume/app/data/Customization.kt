package com.yume.app.data

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yume.app.engine.Tool
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the user can rearrange: what each gesture does, keyboard
 * shortcuts, the order and visibility of tools, defaults for a new session,
 * and whether tool settings carry over from one project to the next.
 *
 * Values are snapshot state (the UI follows them) and are saved at once.
 */
object Customization {

    /** Something a gesture, shortcut or pen button can do. */
    enum class Action(val label: String) {
        None("Nothing"),
        Undo("Undo"),
        Redo("Redo"),
        ToggleUi("Hide / show interface"),
        ShortcutMenu("Shortcut ring"),
        Brush("Brush"),
        Eraser("Eraser"),
        Smudge("Smudge"),
        ToggleEraser("Brush ↔ eraser"),
        Fill("Fill"),
        Selection("Select"),
        Transform("Transform"),
        Eyedropper("Eyedropper"),
        SwapColour("Previous colour"),
        BrushStudio("Brush Studio"),
        Layers("Layers panel"),
        Colours("Colour panel"),
        Adjustments("Effects"),
        NewLayer("New layer"),
        ClearLayer("Clear layer"),
        SelectAll("Select all"),
        Deselect("Deselect"),
        Copy("Copy"),
        CopyMerged("Copy all layers"),
        Cut("Cut"),
        Paste("Paste"),
        FitToScreen("Fit to screen"),
        ActualSize("Actual size"),
        FlipView("Mirror the view"),
        SizeUp("Brush bigger"),
        SizeDown("Brush smaller"),
        OpacityUp("More opaque"),
        OpacityDown("Less opaque")
    }

    /** A touch or pen gesture on the canvas. */
    enum class Gesture(val label: String, val default: Action, val choices: List<Action>? = null) {
        TwoFingerTap("Two-finger tap", Action.Undo),
        ThreeFingerTap("Three-finger tap", Action.Redo),
        FourFingerTap("Four-finger tap", Action.ToggleUi),
        TwoFingerHold("Two-finger hold", Action.ShortcutMenu),
        PenButton(
            "Pen side button", Action.Eraser,
            // Held while drawing: either it switches to the eraser for that
            // stroke, or it runs an action when pressed.
            listOf(
                Action.Eraser, Action.Undo, Action.Redo, Action.ToggleUi, Action.ShortcutMenu,
                Action.Eyedropper, Action.None
            )
        )
    }

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("yume_customization", Context.MODE_PRIVATE)
        prefs = p
        // The earlier on / off switches: a gesture that was turned off stays off.
        val old = context.getSharedPreferences("yume_settings", Context.MODE_PRIVATE)
        fun wasOff(key: String) = old.contains(key) && !old.getBoolean(key, true)
        gestures = Gesture.entries.associateWith { g ->
            Action.entries.firstOrNull { it.name == p.getString("g_${g.name}", null) } ?: when {
                g == Gesture.TwoFingerTap && wasOff("twoFingerUndo") -> Action.None
                g == Gesture.ThreeFingerTap && wasOff("threeFingerRedo") -> Action.None
                g == Gesture.FourFingerTap && wasOff("fourFingerUi") -> Action.None
                else -> g.default
            }
        }
        shortcuts = runCatching {
            val o = JSONObject(p.getString("shortcuts", null) ?: error("none"))
            Action.entries.associateWith { a -> o.optString(a.name, "") }.filterValues { it.isNotEmpty() }
        }.getOrDefault(DEFAULT_SHORTCUTS)
        toolOrder = runCatching {
            val a = JSONArray(p.getString("toolOrder", null) ?: error("none"))
            val list = (0 until a.length()).mapNotNull { i -> Tool.entries.firstOrNull { it.name == a.getString(i) } }
            // Tools added in a later version go at the end.
            list + Tool.entries.filter { it !in list }
        }.getOrDefault(DEFAULT_TOOL_ORDER)
        hiddenTools = (p.getStringSet("hiddenTools", emptySet()) ?: emptySet())
            .mapNotNull { n -> Tool.entries.firstOrNull { it.name == n } }.toSet()
        pillOrder = runCatching {
            val a = JSONArray(p.getString("pillOrder", null) ?: error("none"))
            val list = (0 until a.length()).mapNotNull { i -> Pill.entries.firstOrNull { it.name == a.getString(i) } }
            list + Pill.entries.filter { it !in list }
        }.getOrDefault(Pill.entries)
        pillSlots = runCatching {
            val o = JSONObject(p.getString("pillSlots", null) ?: error("none"))
            Pill.entries.associateWith { pill ->
                (PillSlot.entries.firstOrNull { it.name == o.optString(pill.name) } ?: pill.defaultSlot)
                    .takeIf { pill.hidable || it != PillSlot.Hidden } ?: pill.defaultSlot
            }
        }.getOrDefault(Pill.entries.associateWith { it.defaultSlot })
        persistToolSettings = p.getBoolean("persistToolSettings", false)
        defaultTool = Tool.entries.firstOrNull { it.name == p.getString("defaultTool", null) } ?: Tool.Brush
        defaultBrush = p.getString("defaultBrush", "studio-pen") ?: "studio-pen"
        defaultEraser = p.getString("defaultEraser", "") ?: ""
        defaultSmudge = p.getString("defaultSmudge", "") ?: ""
        defaultColor = p.getInt("defaultColor", 0xFF000000.toInt())
        defaultAssist = p.getFloat("defaultAssist", 0.3f)
        defaultFillTolerance = p.getInt("defaultFillTolerance", 30)
        defaultQuickShape = p.getBoolean("defaultQuickShape", true)
        toolState = p.getString("toolState", null)
        toolDefaults = runCatching { JSONObject(p.getString("toolDefaults", null) ?: error("none")) }
            .getOrDefault(JSONObject()).toString()
        ringItems = runCatching {
            val a = JSONArray(p.getString("ringItems", null) ?: error("none"))
            (0 until a.length()).mapNotNull { i -> Action.entries.firstOrNull { it.name == a.getString(i) } }
                .filter { it in RING_CHOICES }.distinct()
        }.getOrNull()?.takeIf { it.size >= RING_MIN } ?: DEFAULT_RING
    }

    private fun put(block: SharedPreferences.Editor.() -> Unit) {
        prefs?.edit()?.apply(block)?.apply()
    }

    // ------------------------------------------------------------ gestures

    var gestures by mutableStateOf(Gesture.entries.associateWith { it.default })
        private set

    fun actionFor(g: Gesture): Action = gestures[g] ?: g.default

    fun setGesture(g: Gesture, a: Action) {
        gestures = gestures + (g to a)
        put { putString("g_${g.name}", a.name) }
    }

    /** The open editor runs actions here; null outside the editor. */
    @Volatile var dispatcher: ((Action) -> Unit)? = null

    /** Runs [a] in the open editor (main thread). False when nothing ran. */
    fun dispatch(a: Action): Boolean {
        if (a == Action.None) return false
        val d = dispatcher ?: return false
        d(a)
        return true
    }

    // ------------------------------------------------------------ shortcuts

    /** Keyboard shortcut per action, as "Ctrl+Shift+Z" style text. */
    var shortcuts by mutableStateOf<Map<Action, String>>(emptyMap())
        private set

    /** The action waiting for its new key combination in Settings, or null. */
    var learning by mutableStateOf<Action?>(null)

    fun setShortcut(a: Action, combo: String) {
        // One combination, one action.
        shortcuts = shortcuts.filterValues { it != combo } + (a to combo)
        saveShortcuts()
    }

    fun clearShortcut(a: Action) {
        shortcuts = shortcuts - a
        saveShortcuts()
    }

    fun resetShortcuts() {
        shortcuts = DEFAULT_SHORTCUTS
        saveShortcuts()
    }

    private fun saveShortcuts() {
        val o = JSONObject()
        for ((a, c) in shortcuts) o.put(a.name, c)
        // Actions without a shortcut are stored as empty, so a cleared default stays cleared.
        for (a in Action.entries) if (!o.has(a.name)) o.put(a.name, "")
        put { putString("shortcuts", o.toString()) }
    }

    /** "Ctrl+Shift+Z" for [event], or null for a lone modifier key. */
    fun comboOf(event: KeyEvent): String? {
        val code = event.keyCode
        if (KeyEvent.isModifierKey(code)) return null
        val parts = ArrayList<String>(4)
        if (event.isCtrlPressed) parts.add("Ctrl")
        if (event.isAltPressed) parts.add("Alt")
        if (event.isShiftPressed) parts.add("Shift")
        if (event.isMetaPressed) parts.add("Meta")
        parts.add(keyName(code))
        return parts.joinToString("+")
    }

    private fun keyName(code: Int): String = when (code) {
        KeyEvent.KEYCODE_LEFT_BRACKET -> "["
        KeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
        KeyEvent.KEYCODE_MINUS -> "-"
        KeyEvent.KEYCODE_EQUALS -> "="
        KeyEvent.KEYCODE_SPACE -> "Space"
        KeyEvent.KEYCODE_TAB -> "Tab"
        KeyEvent.KEYCODE_DEL -> "Backspace"
        KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
        else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
    }

    /**
     * A hardware key in the app: teaches a shortcut while Settings is
     * listening, otherwise runs the action bound to it. True when consumed.
     */
    fun handleKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return learning != null || (comboOf(event)?.let { c -> shortcuts.values.contains(c) } == true && dispatcher != null)
        }
        val combo = comboOf(event) ?: return learning != null
        val teaching = learning
        if (teaching != null) {
            if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                learning = null
                return true
            }
            setShortcut(teaching, combo)
            learning = null
            return true
        }
        val action = shortcuts.entries.firstOrNull { it.value == combo }?.key ?: return false
        if (event.repeatCount > 0 && action !in REPEATABLE) return true
        return dispatch(action)
    }

    private val REPEATABLE = setOf(
        Action.Undo, Action.Redo, Action.SizeUp, Action.SizeDown, Action.OpacityUp, Action.OpacityDown
    )

    val DEFAULT_SHORTCUTS: Map<Action, String> = mapOf(
        Action.Undo to "Ctrl+Z",
        Action.Redo to "Ctrl+Shift+Z",
        Action.Brush to "B",
        Action.Eraser to "E",
        Action.Smudge to "S",
        Action.Fill to "G",
        Action.Selection to "L",
        Action.Transform to "V",
        Action.Eyedropper to "I",
        Action.SwapColour to "X",
        Action.ToggleUi to "Tab",
        Action.NewLayer to "Ctrl+Shift+N",
        Action.ClearLayer to "Backspace",
        Action.SelectAll to "Ctrl+A",
        Action.Deselect to "Ctrl+D",
        Action.Copy to "Ctrl+C",
        Action.CopyMerged to "Ctrl+Shift+C",
        Action.Cut to "Ctrl+X",
        Action.Paste to "Ctrl+V",
        Action.FitToScreen to "Ctrl+0",
        Action.ActualSize to "Ctrl+1",
        Action.FlipView to "H",
        Action.SizeUp to "]",
        Action.SizeDown to "[",
        Action.OpacityUp to "Shift+]",
        Action.OpacityDown to "Shift+[",
        Action.Layers to "F7",
        Action.BrushStudio to "F5",
        Action.Colours to "F6"
    )

    // ------------------------------------------------------------ layout

    val DEFAULT_TOOL_ORDER: List<Tool> = listOf(
        Tool.Selection, Tool.Transform, Tool.Brush, Tool.Smudge, Tool.Eraser,
        Tool.Fill, Tool.Gradient, Tool.Shape, Tool.Liquify, Tool.Text, Tool.Clone
    )

    var toolOrder by mutableStateOf(DEFAULT_TOOL_ORDER)
        private set
    var hiddenTools by mutableStateOf<Set<Tool>>(emptySet())
        private set

    /** The toolbar's tools in order, without the hidden ones. */
    val visibleTools: List<Tool> get() = toolOrder.filter { it !in hiddenTools }

    fun moveTool(t: Tool, by: Int) {
        val list = toolOrder.toMutableList()
        val i = list.indexOf(t)
        val j = (i + by).coerceIn(0, list.size - 1)
        if (i < 0 || i == j) return
        list.removeAt(i)
        list.add(j, t)
        toolOrder = list
        put { putString("toolOrder", JSONArray(list.map { it.name }).toString()) }
    }

    fun setToolHidden(t: Tool, hidden: Boolean) {
        // The brush always stays: there has to be something to paint with.
        if (t == Tool.Brush && hidden) return
        hiddenTools = if (hidden) hiddenTools + t else hiddenTools - t
        put { putStringSet("hiddenTools", hiddenTools.map { it.name }.toSet()) }
    }

    fun resetLayout() {
        toolOrder = DEFAULT_TOOL_ORDER
        hiddenTools = emptySet()
        put { remove("toolOrder"); remove("hiddenTools") }
    }

    /**
     * Moves a shown tool from [from] to [to] in the toolbar's own order
     * (indices into [visibleTools]); the hidden tools keep their places
     * relative to it, at the end.
     */
    fun moveVisibleTool(from: Int, to: Int) {
        val shown = visibleTools.toMutableList()
        if (from !in shown.indices || to !in shown.indices || from == to) return
        shown.add(to, shown.removeAt(from))
        val list = shown + toolOrder.filter { it in hiddenTools }
        toolOrder = list
        put { putString("toolOrder", JSONArray(list.map { it.name }).toString()) }
    }

    // ---------------------------------------------------------- top pills

    /** The glass pills along the top of the editor. */
    enum class Pill(val label: String, val detail: String, val defaultSlot: PillSlot, val hidable: Boolean = true) {
        Document("Document", "Gallery button and the name", PillSlot.Left),
        // Holds Actions (and with it the way back to Settings): never hidden,
        // so the editor can't be locked out of its own menus.
        Edit("History & actions", "History, Actions, Guides", PillSlot.Left, hidable = false),
        Tools("Tools", "Selection, brush, fill… (order set below)", PillSlot.Right),
        Panels("Layers & colour", "Effects, Layers, colour", PillSlot.Right)
    }

    enum class PillSlot(val label: String) { Left("Left"), Center("Centre"), Right("Right"), Hidden("Hidden") }

    var pillOrder by mutableStateOf(Pill.entries.toList())
        private set
    var pillSlots by mutableStateOf(Pill.entries.associateWith { it.defaultSlot })
        private set

    /** The pills placed in [slot], in their order. */
    fun pillsIn(slot: PillSlot): List<Pill> = pillOrder.filter { pillSlots[it] == slot }

    fun movePill(from: Int, to: Int) {
        val list = pillOrder.toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        list.add(to, list.removeAt(from))
        pillOrder = list
        put { putString("pillOrder", JSONArray(list.map { it.name }).toString()) }
    }

    fun setPillSlot(p: Pill, slot: PillSlot) {
        if (slot == PillSlot.Hidden && !p.hidable) return
        pillSlots = pillSlots + (p to slot)
        put { putString("pillSlots", JSONObject(pillSlots.mapKeys { it.key.name }.mapValues { it.value.name }).toString()) }
    }

    fun resetPills() {
        pillOrder = Pill.entries.toList()
        pillSlots = Pill.entries.associateWith { it.defaultSlot }
        put { remove("pillOrder"); remove("pillSlots") }
    }

    // ------------------------------------------------------------ defaults

    /** Tool settings (tool, brushes, colour, tolerances) carry over between projects. */
    var persistToolSettings by mutableStateOf(false)
        private set
    fun setPersistToolSettingsValue(v: Boolean) { persistToolSettings = v; put { putBoolean("persistToolSettings", v) } }

    var defaultTool by mutableStateOf(Tool.Brush)
        private set
    fun setDefaultToolValue(v: Tool) { defaultTool = v; put { putString("defaultTool", v.name) } }

    var defaultBrush by mutableStateOf("studio-pen")
        private set
    fun setDefaultBrushValue(v: String) { defaultBrush = v; put { putString("defaultBrush", v) } }

    /** Empty = the first eraser / blender of the library. */
    var defaultEraser by mutableStateOf("")
        private set
    fun setDefaultEraserValue(v: String) { defaultEraser = v; put { putString("defaultEraser", v) } }

    var defaultSmudge by mutableStateOf("")
        private set
    fun setDefaultSmudgeValue(v: String) { defaultSmudge = v; put { putString("defaultSmudge", v) } }

    var defaultColor by mutableStateOf(0xFF000000.toInt())
        private set
    fun setDefaultColorValue(v: Int) { defaultColor = v; put { putInt("defaultColor", v) } }

    var defaultAssist by mutableStateOf(0.3f)
        private set
    fun setDefaultAssistValue(v: Float) { defaultAssist = v.coerceIn(0f, 1f); put { putFloat("defaultAssist", defaultAssist) } }

    var defaultFillTolerance by mutableStateOf(30)
        private set
    fun setDefaultFillToleranceValue(v: Int) { defaultFillTolerance = v.coerceIn(0, 255); put { putInt("defaultFillTolerance", defaultFillTolerance) } }

    var defaultQuickShape by mutableStateOf(true)
        private set
    fun setDefaultQuickShapeValue(v: Boolean) { defaultQuickShape = v; put { putBoolean("defaultQuickShape", v) } }

    /**
     * Each tool's starting options (selection shape, fill, gradient, shape,
     * liquify, text…), as JSON text so the UI follows changes. Missing keys
     * mean the built-in default.
     */
    var toolDefaults by mutableStateOf("{}")
        private set

    private fun td(): JSONObject = runCatching { JSONObject(toolDefaults) }.getOrDefault(JSONObject())

    fun tdInt(key: String, def: Int): Int = td().optInt(key, def)
    fun tdFloat(key: String, def: Float): Float = td().optDouble(key, def.toDouble()).toFloat()
    fun tdBool(key: String, def: Boolean): Boolean = td().optBoolean(key, def)
    fun tdString(key: String, def: String): String = td().optString(key, def)

    fun setTd(key: String, value: Any) {
        val o = td()
        o.put(key, if (value is Float) value.toDouble() else value)
        toolDefaults = o.toString()
        put { putString("toolDefaults", toolDefaults) }
    }

    fun resetToolDefaults() {
        toolDefaults = "{}"
        put { remove("toolDefaults") }
    }

    // ---------------------------------------------------- shortcut ring

    val DEFAULT_RING: List<Action> = listOf(
        Action.Brush, Action.Eraser, Action.Smudge, Action.Colours, Action.Redo, Action.Undo
    )
    const val RING_MIN = 2
    const val RING_MAX = 10

    /** What the shortcut ring offers, clockwise from the top. */
    var ringItems by mutableStateOf(DEFAULT_RING)
        private set

    private fun saveRing() { put { putString("ringItems", JSONArray(ringItems.map { it.name }).toString()) } }

    fun moveRingItem(from: Int, to: Int) {
        val list = ringItems.toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        list.add(to, list.removeAt(from))
        ringItems = list
        saveRing()
    }

    fun removeRingItem(a: Action) {
        if (ringItems.size <= RING_MIN) return
        ringItems = ringItems - a
        saveRing()
    }

    fun addRingItem(a: Action) {
        if (a in ringItems || ringItems.size >= RING_MAX || a !in RING_CHOICES) return
        ringItems = ringItems + a
        saveRing()
    }

    fun resetRing() {
        ringItems = DEFAULT_RING
        put { remove("ringItems") }
    }

    /** Everything the ring can hold (not itself, not "nothing"). */
    val RING_CHOICES: List<Action> get() = Action.entries.filter { it != Action.None && it != Action.ShortcutMenu }

    /** The last session's tool settings (JSON), for [persistToolSettings]. */
    var toolState: String? = null
        private set

    init {
        // Before Settings are loaded (and in tests) the default shortcuts apply.
        shortcuts = DEFAULT_SHORTCUTS
    }

    fun saveToolState(json: String) {
        toolState = json
        put { putString("toolState", json) }
    }
}
