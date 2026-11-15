package com.yume.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Cyclone
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FormatColorFill
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Interests
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The Material Symbols (Rounded) icon for a glyph, or null where the app
 * keeps its own drawing (the eraser, clipping masks, the colour dot: shapes
 * Material has no clean equivalent for).
 */
internal fun Glyph.material(): ImageVector? = when (this) {
    Glyph.Gallery -> Icons.Rounded.GridView
    Glyph.Undo -> Icons.AutoMirrored.Rounded.Undo
    Glyph.Redo -> Icons.AutoMirrored.Rounded.Redo
    Glyph.Wrench -> Icons.Rounded.Build
    Glyph.Wand -> Icons.Rounded.AutoFixHigh
    Glyph.Select -> Icons.Rounded.CropFree
    Glyph.Transform -> Icons.Rounded.OpenWith
    Glyph.Brush -> Icons.Rounded.Brush
    Glyph.Smudge -> Icons.Rounded.WaterDrop
    Glyph.Layers -> Icons.Rounded.Layers
    Glyph.Plus -> Icons.Rounded.Add
    Glyph.Close -> Icons.Rounded.Close
    Glyph.Check -> Icons.Rounded.Check
    Glyph.ChevronDown -> Icons.Rounded.ExpandMore
    Glyph.ChevronUp -> Icons.Rounded.ExpandLess
    Glyph.Eye -> Icons.Rounded.Visibility
    Glyph.EyeOff -> Icons.Rounded.VisibilityOff
    Glyph.Trash -> Icons.Rounded.Delete
    Glyph.Copy -> Icons.Rounded.ContentCopy
    Glyph.Merge -> Icons.Rounded.CallMerge
    Glyph.Lock -> Icons.Rounded.Lock
    Glyph.Unlock -> Icons.Rounded.LockOpen
    Glyph.Sliders -> Icons.Rounded.Tune
    Glyph.Share -> Icons.Rounded.IosShare
    Glyph.Picture -> Icons.Rounded.Image
    Glyph.Palette -> Icons.Rounded.Palette
    Glyph.FlipH, Glyph.FlipV -> Icons.Rounded.Flip
    Glyph.Droplet -> Icons.Rounded.Colorize
    Glyph.Info -> Icons.Rounded.Info
    Glyph.ZoomIn -> Icons.Rounded.ZoomIn
    Glyph.ZoomOut -> Icons.Rounded.ZoomOut
    Glyph.Fit -> Icons.Rounded.FitScreen
    Glyph.Rotate -> Icons.Rounded.RotateRight
    Glyph.Sparkle -> Icons.Rounded.AutoAwesome
    Glyph.Grid -> Icons.Rounded.GridOn
    Glyph.Home -> Icons.Rounded.Home
    Glyph.Clear -> Icons.Rounded.DeleteSweep
    Glyph.Save -> Icons.Rounded.Save
    Glyph.More -> Icons.Rounded.MoreHoriz
    Glyph.History -> Icons.Rounded.History
    Glyph.Folder -> Icons.Rounded.Folder
    Glyph.Mask -> Icons.Rounded.Contrast
    Glyph.Grip -> Icons.Rounded.DragIndicator
    Glyph.Bucket -> Icons.Rounded.FormatColorFill
    Glyph.Gradient -> Icons.Rounded.Gradient
    Glyph.Pin -> Icons.Rounded.PushPin
    Glyph.Shape -> Icons.Rounded.Interests
    Glyph.Liquify -> Icons.Rounded.Cyclone
    Glyph.Text -> Icons.Rounded.TextFields
    Glyph.Guides -> Icons.Rounded.Straighten
    Glyph.Eraser, Glyph.Clip, Glyph.ColorDot -> null
}
