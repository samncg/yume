package com.yume.app.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import kotlin.math.max

/**
 * One paint layer. Pixels live in a [TileStore], which hides whether they are a
 * single surface, sparse tiles or a lazy file.
 */
class Layer(
    val id: Long,
    var name: String,
    val tiles: TileStore,
    var opacity: Float = 1f,
    var blend: LayerBlend = LayerBlend.Normal,
    var visible: Boolean = true,
    var alphaLock: Boolean = false,
    /** Fills follow this layer's lines (at most one layer is the reference). */
    var reference: Boolean = false,
    /** Locked layers refuse painting, clearing, adjusting and transforming. */
    var locked: Boolean = false,
    /** Clip this layer to the alpha of the layer below it. */
    var clipping: Boolean = false,
    /** Optional coverage mask: white keeps the layer, black hides it. */
    var maskTiles: TileStore? = null,
    var maskEnabled: Boolean = true,
    /** 0 = top level, otherwise the id of the group this layer belongs to. */
    var groupId: Long = 0L,
    /** While true, brush strokes are painted into [maskTiles] instead of [tiles]. */
    var editMask: Boolean = false
) {
    /** True when a mask exists, without forcing it to decode. */
    val hasMask: Boolean get() = maskTiles != null

    /** True when the mask pixels are already in memory (no decode). */
    val maskResident: Boolean get() = maskTiles?.isResident == true

    /** True when the layer pixels are already in memory. */
    val isResident: Boolean get() = tiles.isResident

    /** The store the brush writes into: the layer itself or its mask. */
    val paintStore: TileStore
        get() = if (editMask && maskTiles != null) maskTiles!! else tiles

    /** Adopts a lazily loaded mask, keeping its file when [bitmap] is null. */
    fun adoptMask(bitmap: Bitmap?, file: File?) {
        maskTiles = if (bitmap != null || file != null) {
            TileStoreFactory.mask(tiles.width, tiles.height, bitmap, file)
        } else {
            null
        }
    }

    /** Bytes held in RAM by the pixels and mask. */
    fun residentBytes(): Long = tiles.residentBytes() + (maskTiles?.residentBytes() ?: 0L)

    /** Swaps the layer pixels out, using [file] as the backing store. */
    fun releaseToDisk(file: File) {
        tiles.releaseToDisk(file)
    }

    /** Bumped whenever pixels change so the UI can refresh its thumbnail. */
    var revision: Int = 0

    fun touch() {
        revision++
    }

    fun props(): LayerProps = LayerProps(
        name = name,
        opacity = opacity,
        blend = blend,
        visible = visible,
        alphaLock = alphaLock,
        reference = reference,
        locked = locked,
        clipping = clipping,
        maskEnabled = maskEnabled,
        groupId = groupId,
        editMask = editMask
    )

    fun applyProps(p: LayerProps) {
        name = p.name
        opacity = p.opacity
        blend = p.blend
        visible = p.visible
        alphaLock = p.alphaLock
        reference = p.reference
        locked = p.locked
        clipping = p.clipping
        maskEnabled = p.maskEnabled
        groupId = p.groupId
        editMask = p.editMask
    }

    companion object {
        fun blank(width: Int, height: Int): TileStore = TileStoreFactory.blank(width, height)

        /** Decodes at most roughly [maxPx] on the long edge, for thumbnails. */
        fun decodeSampled(file: File, maxPx: Int): Bitmap? {
            if (!file.exists()) return null
            if (TileFile.isTileFile(file)) return TileFile.decodeSampled(file, maxPx)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            val longest = max(bounds.outWidth, bounds.outHeight)
            while (longest / (sample * 2) >= maxPx) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            return BitmapFactory.decodeFile(file.absolutePath, opts)
        }
    }
}

data class LayerProps(
    val name: String,
    val opacity: Float,
    val blend: LayerBlend,
    val visible: Boolean,
    val alphaLock: Boolean,
    val reference: Boolean = false,
    val locked: Boolean = false,
    val clipping: Boolean = false,
    val maskEnabled: Boolean = true,
    val groupId: Long = 0L,
    val editMask: Boolean = false
)

/**
 * A folder of layers composited in isolation, with its own opacity and blend
 * mode. Groups hold one level of layers (groups cannot nest).
 */
class LayerGroup(
    val id: Long,
    var name: String,
    var opacity: Float = 1f,
    var blend: LayerBlend = LayerBlend.Normal,
    var visible: Boolean = true,
    var locked: Boolean = false,
    var expanded: Boolean = true
) {
    fun snapshot(): GroupState = GroupState(this, name, opacity, blend, visible, locked, expanded)
}

data class GroupState(
    val group: LayerGroup,
    val name: String,
    val opacity: Float,
    val blend: LayerBlend,
    val visible: Boolean,
    val locked: Boolean,
    val expanded: Boolean
) {
    fun applyTo(target: LayerGroup = group) {
        target.name = name
        target.opacity = opacity
        target.blend = blend
        target.visible = visible
        target.locked = locked
        target.expanded = expanded
    }
}

/** Marker used by the composite to know whether the document is transparent. */
fun isOpaque(color: Int): Boolean = Color.alpha(color) == 255

/**
 * Non destructive adjustment preview applied while compositing a single layer.
 * [filter] is a colour matrix; [proxy] is a downscaled, pre-blurred copy.
 */
class LayerPreview(
    val layerId: Long,
    val filter: android.graphics.ColorFilter?,
    val proxy: Bitmap?,
    /** Something about to be added as a layer, drawn above its layer (see [InsertImage]). */
    val insert: InsertImage? = null
) {
    /** True when this preview is drawn on layer [id]. */
    fun covers(id: Long): Boolean = layerId == id || (layerId == ALL_LAYERS && proxy == null)

    companion object {
        /** A colour-filter preview applied to every layer (adjust all layers). */
        const val ALL_LAYERS = -2L

        /** No layer is previewed (only an [insert]). */
        const val NO_LAYER = -3L
    }
}
