# Roadmap

Features Yume could add, mostly ones Procreate or Clip Studio Paint have and
Yume does not yet. The list comes from a comparison of Yume's code and README
with those apps (November 2026). Unchecked items are not built yet.

## Everyday painting

- [x] **Distort, perspective and warp transforms.** Drag single corners
      (distort / perspective) or bend a mesh (warp). Today's transform moves,
      scales, rotates and flips only.
- [x] **Copy and paste.** Copy a selection, or all layers merged, and paste
      it as a new layer. Today there is Cut only.
- [x] **PSD import and export** with layers, blend modes, masks and groups.
- [x] **Smarter fill.**
  - Close small gaps in line art before filling (Clip Studio).
  - Pick a reference layer to fill from (both apps).
  - Drag-to-adjust fill threshold, as in Procreate's ColorDrop.
- [x] **Colour harmony** in the colour picker: complementary,
      split-complementary, analogous, triadic and tetradic.
- [x] **Clone tool**: paint with pixels sampled from another spot.
- [ ] **Effects**: bloom, glitch, halftone, chromatic aberration (Procreate).

## Bigger features

- [ ] **Animation**: frames, onion skins, playback, and GIF or video export.
- [ ] **Vector layers** (Clip Studio): line art whose strokes can be reshaped
      and re-thinned after drawing.
- [ ] **Comic tools** (Clip Studio): panel borders, screentones, speech
      balloons and multi-page projects.
- [ ] **3D**: posable 3D figures as drawing references (Clip Studio) and
      painting on 3D models (Procreate).

## From ibisPaint

- [ ] **Line-art extraction**: turn a sketch or photo into clean lines on a
      transparent layer.
- [ ] **Speed lines and focus lines**: a ruler or filter that draws manga
      radial and parallel lines toward a chosen point.
- [ ] **Frame divider**: split the canvas into comic panels with even gutters
      (overlaps with comic tools above).
- [ ] **Text upgrades**: vertical text, outlined text, and more fonts.
- [ ] **More filters**: pixelate, mosaic, glow, lens flare, clouds, and
      "anime background" style colour grading.
- [ ] **Materials library**: tones, patterns and textures to drop onto the
      canvas.
- [ ] **Optional AI tools** (on device where possible): background removal,
      upscaling, and flat-colour suggestions for line art.

## From Infinite Painter

- [ ] **Pattern mode**: paint one tile and see it repeated live (square,
      brick, hexagon, kaleidoscope), for seamless textures.
- [ ] **More guides**: ellipse and curve guides that strokes snap to, plus
      curvilinear (fisheye) perspective with 4 or 5 points.
- [ ] **Editable shapes**: a shape stays adjustable (corners, roundness,
      stroke and fill) until it is committed.
- [ ] **Gallery folders** (Procreate calls them stacks).

## From desktop apps (Krita, Photoshop, Rebelle)

- [ ] **Adjustment and filter layers**: non-destructive hue, curves, gradient
      map and blur that can be edited or hidden later.
- [ ] **Layer effects**: outline, drop shadow, glow, applied live to a layer.
- [ ] **Wrap-around mode**: the canvas repeats at its edges so strokes cross
      over, for seamless tiles.
- [ ] **Pigment colour mixing**: blending and smudging mix like real paint
      (blue + yellow gives green, not grey).
- [ ] **Navigator**: a small live view of the whole canvas while zoomed in.
- [ ] **Pixel-art mode**: hard 1-pixel brushes, pixel-perfect lines, a pixel
      grid and nearest-neighbour transforms.

## Ideas no other app has

These would build on what Yume already does well: complete stroke history,
huge canvases, and a pen-first interface.

- [ ] **Re-brush a finished stroke**: pick a stroke from the history and
      repaint it with a different brush, size or colour, without redrawing it.
- [ ] **Branching history**: go back to any step and try a different
      direction, keeping both versions to compare or pick from.
- [ ] **Named checkpoints**: save "before lighting" style snapshots inside one
      artwork and flip between them.
- [ ] **Value and colour checks**: a quick greyscale view, colour-blindness
      previews and a "squint" blur that never change the painting.
- [ ] **Palette from an image**: build a palette from a photo or the
      reference window, ranked by how much each colour is used.
- [ ] **Pressure calibration**: draw a few strokes light to heavy, and Yume
      fits a pressure curve to the user's hand and pen.
- [ ] **Warm-up exercises**: lines, ellipses and cubes to trace, scored for
      smoothness and accuracy, with progress over time.
- [ ] **Time and effort map**: shade the canvas by where the most time or
      strokes went (data the timelapse already records).
- [ ] **Paint over a timelapse frame**: open any moment of the timelapse as a
      new artwork.

## To check first

Some code exists for these; check how complete it is before building.

- [ ] Feathering a selection's edge.
- [ ] Dual-texture brushes (Procreate's dual brush).
- [ ] A global pressure curve that applies to every brush.

## Known issues

- [ ] Thumbnails of 8K projects can come out blank in the gallery.
- [ ] Smart-pen pinch, double-press and slide (Xiaomi Focus Pen Pro) never
      reach Yume on HyperOS: the system keeps those keys for its own shortcuts,
      and an accessibility key filter is not given them either. Needs root or
      Xiaomi adding Yume to its stylus allow-list.
