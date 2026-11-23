package com.yume.app.engine

import android.graphics.Bitmap

/** One row in the layers panel. Groups are rows too, with [isGroup] set. */
data class LayerUi(
    val id: Long,
    val name: String,
    val visible: Boolean,
    val opacity: Float,
    val blend: LayerBlend,
    val alphaLock: Boolean,
    val thumb: Bitmap?,
    val isGroup: Boolean = false,
    val locked: Boolean = false,
    val clipping: Boolean = false,
    val hasMask: Boolean = false,
    val maskEnabled: Boolean = true,
    val editingMask: Boolean = false,
    val maskThumb: Bitmap? = null,
    val expanded: Boolean = true,
    /** 1 when the row sits inside a group. */
    val depth: Int = 0
)

/** Everything the Compose UI needs to draw the editor chrome. */
data class EditorUiState(
    val loaded: Boolean = false,
    val title: String = "Untitled",
    val width: Int = 0,
    val height: Int = 0,
    val layers: List<LayerUi> = emptyList(),
    val activeLayerId: Long = -1L,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val tool: Tool = Tool.Brush,
    val category: BrushCategory = BrushCategory.Brush,
    val presets: List<BrushPreset> = emptyList(),
    val activePresetId: String = "",
    val brushSize: Float = 20f,
    val brushOpacity: Float = 1f,
    val color: Int = 0xFF000000.toInt(),
    val recentColors: List<Int> = emptyList(),
    val palette: List<Int> = defaultPalette,
    val selectionShape: SelectionShape = SelectionShape.Lasso,
    val selectionOp: SelectionOp = SelectionOp.Replace,
    val hasSelection: Boolean = false,
    /** Something is on Yume's clipboard to paste. */
    val canPaste: Boolean = false,
    val transforming: Boolean = false,
    val transformMode: TransformMode = TransformMode.Free,
    val symmetryX: Boolean = false,
    val symmetryY: Boolean = false,
    val symmetryXPos: Float = 0.5f,
    val symmetryYPos: Float = 0.5f,
    val symmetryRadial: Int = 0,
    val symmetryMirror: Boolean = false,
    val guideMode: GuideMode = GuideMode.Off,
    val guideAssist: Boolean = true,
    val gridSize: Float = 128f,
    val vanishingPoints: Int = 2,
    val symmetryLocked: Boolean = false,
    val soloLayerId: Long = -1L,
    val assist: Float = 0.3f,
    val quickShape: Boolean = true,
    val shapeKind: ShapeKind = ShapeKind.Ellipse,
    val liquifyMode: LiquifyMode = LiquifyMode.Push,
    val strokeCount: Long = 0L,
    val liquifySize: Float = 160f,
    val liquifyStrength: Float = 0.5f,
    val shapePerfect: Boolean = false,
    val shapeSides: Int = 5,
    val history: List<HistoryEntry> = emptyList(),
    val adjusting: Boolean = false,
    val colorMode: ColorMode = ColorMode.Wheel,
    /** Bumped whenever the document changes; drives debounced autosave. */
    val documentVersion: Int = 0,
    val backgroundColor: Int = 0xFFFFFFFF.toInt(),
    val zoom: Float = 1f,
    val rotation: Float = 0f,
    val fingerPaint: Boolean = true,
    val chromeVisible: Boolean = true,
    val eyedropperActive: Boolean = false,
    val eyedropperLayerOnly: Boolean = false,
    /** Colour tolerance used by the automatic (magic wand) selection. */
    val wandTolerance: Int = 30,
    /** Paint bucket: colour tolerance (1-100). */
    val fillTolerance: Int = 30,
    /** Paint bucket: find the region on the current layer instead of the composite. */
    val fillLayerOnly: Boolean = false,
    /** Paint bucket: a tap inside the selection fills the whole selection. */
    val fillSelection: Boolean = true,
    /** Paint bucket: grow the region by this many px under anti-aliased edges. */
    val fillExpand: Int = 1,
    /** Gradient tool: linear or radial, from the current colour to [gradientSecond]. */
    val gradientKind: Gradients.Kind = Gradients.Kind.Linear,
    val gradientSecond: Int = 0x00FFFFFF,
    /** Motion blur direction in degrees. */
    val motionAngle: Float = 0f,
    /** Adjustments apply to all layers instead of the active one. */
    val adjustAllLayers: Boolean = false,
    /**
     * Debug: whether documents opened from now on use sparse tile layers.
     * Only surfaced in debuggable builds (Actions panel).
     */
    val sparseLayers: Boolean = false
) {
    /** The selected row: a layer or a group. */
    val activeNode: LayerUi? get() = layers.firstOrNull { it.id == activeLayerId }

    val activeLayer: LayerUi? get() = activeNode?.takeIf { !it.isGroup }

    companion object {
        val defaultPalette: List<Int> = listOf(
            0xFF000000.toInt(), 0xFF3F3F46.toInt(), 0xFF7A7A85.toInt(), 0xFFBFBFC7.toInt(),
            0xFFFFFFFF.toInt(), 0xFFF4E3C8.toInt(), 0xFFE9A13B.toInt(), 0xFFD9694A.toInt(),
            0xFFC63A3A.toInt(), 0xFF9B3A6B.toInt(), 0xFF6A4BA8.toInt(), 0xFF3D5AA8.toInt(),
            0xFF2E7FB8.toInt(), 0xFF2FA3A3.toInt(), 0xFF2E9B62.toInt(), 0xFF6FA83C.toInt(),
            0xFFB7B23A.toInt(), 0xFFE0A96D.toInt(), 0xFFB07A56.toInt(), 0xFF6B4B3A.toInt(),
            0xFF1B2A3A.toInt(), 0xFF243B2F.toInt(), 0xFF3B2A3A.toInt(), 0xFF121214.toInt()
        )
    }
}
