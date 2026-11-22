# Roadmap

Features Yume could add, mostly ones Procreate or Clip Studio Paint have and
Yume does not yet. The list comes from a comparison of Yume's code and README
with those apps (November 2026). Unchecked items are not built yet.

## Everyday painting

- [ ] **Distort, perspective and warp transforms.** Drag single corners
      (distort / perspective) or bend a mesh (warp). Today's transform moves,
      scales, rotates and flips only.
- [ ] **Copy and paste.** Copy a selection, or all layers merged, and paste
      it as a new layer. Today there is Cut only.
- [ ] **PSD import and export** with layers, blend modes, masks and groups.
- [ ] **Smarter fill.**
  - Close small gaps in line art before filling (Clip Studio).
  - Pick a reference layer to fill from (both apps).
  - Drag-to-adjust fill threshold, as in Procreate's ColorDrop.
- [ ] **Colour harmony** in the colour picker: complementary,
      split-complementary, analogous, triadic and tetradic.
- [ ] **Clone tool**: paint with pixels sampled from another spot.
- [ ] **Effects**: bloom, glitch, halftone, chromatic aberration (Procreate).

## Bigger features

- [ ] **Animation**: frames, onion skins, playback, and GIF or video export.
- [ ] **Vector layers** (Clip Studio): line art whose strokes can be reshaped
      and re-thinned after drawing.
- [ ] **Comic tools** (Clip Studio): panel borders, screentones, speech
      balloons and multi-page projects.
- [ ] **3D**: posable 3D figures as drawing references (Clip Studio) and
      painting on 3D models (Procreate).

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
