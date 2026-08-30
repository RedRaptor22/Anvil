# Anvil port roadmap

Every feature in the Plume web build, ordered into eight phases by what
genuinely depends on what. Each phase ends with something you can hold in your
hand — not a milestone on paper — and each one's logic goes into the tested
core before anything draws it.

**62 items · 7 complete · ~10,800 lines of web build to port · ~65% of it is
pure logic that transfers with tests.**

---

## How to tackle it — four rules

1. **Logic into `core` first, with tests, before any pixel depends on it.** Not
   process for its own sake: porting with tests already caught two real bugs in
   the *shipped* web app that nobody had noticed.
2. **Every phase ends installable.** CI builds an APK on each push, so the end
   of a phase is something you can put on a phone and judge, not a green tick.
3. **Port the algorithms, redesign the interface.** The maths must give
   identical answers on both builds. The UI must not — a phone is not a desktop
   with a smaller window.
4. **Fix the web build whenever the port exposes something.** Both repos
   benefit, and the shared tests keep them honest.

---

## Phase 00 — Foundation `DONE`

*A tested engine core and a build that produces an APK.*

- [x] Two-module split, `core` with zero Android imports
- [x] Rotation-minimising frames, closed loops — `Frames.kt`
- [x] Stroke geometry, all 8 brushes, welded rings — `Stroke.kt`
- [x] Nearest-surface query + reprojection — `Surface.kt`
- [x] Spur removal — `Dedupe.kt`
- [x] GL ES 3.0 renderer + gesture layer — `app/` (compiles, never run)
- [x] CI: core tests without an SDK, APK on every push

**Reality:** 19 tests green. The APK builds but has never drawn a frame on a
device — that is the first thing Phase 1 changes.

---

## Phase 01 — Draw and orbit, for real `NEXT`

*Prove the engine on hardware, and make the app usable at its floor.*

- [ ] **Run it on a device — first frame, first stroke (blocking)**
- [ ] Camera controller into core — from `camera.js` orbit/pan/zoom
- [ ] Screen↔world: project, unproject, worldToScreen — from `app.js`
- [ ] Camera-facing draw plane — `refreshDrawPlane`
- [ ] Incremental live stroke buffer — `S.Live`
- [ ] History with a memory budget — `P.History`
- [ ] Grid and axis in the renderer
- [ ] Minimum UI: size, colour, undo, redo, clear
- [ ] Stable-stroke smoothing on input — `TOOL.stable`

**Done when:** you can draw, orbit, undo and clear on a phone without a
keyboard — and the frame budget holds at 120 Hz with a 300 mm brush.

---

## Phase 02 — Guides `LARGEST`

*The Feather premise: draw a surface, then draw on it.*

- [ ] Sweep surface from a stroke — `createFromStroke`, `rebuildSweep`
- [ ] Surface sampling and spans — `sampleSurface`, `surfaceSpan`
- [ ] Project strokes onto a guide — `G.project`
- [ ] Edge behaviour: reach, outline, clamp — `reachAlong`, `insideOutline`
- [ ] Isolation masking — `isMasked`, `invalidateMask`
- [ ] Translucent grid-lined guide shader
- [ ] Flat shape guide — `createFlatFromStroke`
- [ ] Five primitives — cube, pyramid, sphere, torus, tube
- [ ] Bend — `G.bend`, `bendMesh`
- [ ] Loft from selected curves — `loftFromCurves`
- [ ] Guide lifecycle: active, close, save, reuse, opacity

**Done when:** a stroke lands exactly on a curved guide, stays there under every
tool, and the same sketch measures identically in both builds.

---

## Phase 03 — Editing tools

*Everything that changes a curve after it is drawn.*

- [ ] Erase, with splitting — `eraseScreen`, `eraseSphere`
- [ ] Vacuum — `vacuumAt`
- [ ] Smooth — `stepSmooth` + reprojection
- [ ] Liquify: push, pinch, comb — `liquifyApply`
- [ ] Select: tap, sweep, long-press — `tapSelect`, `sweepSelect`
- [ ] Lasso, with a visible boundary — `stepLasso`
- [ ] Transform — needs a touch gizmo, not the desktop joystick
- [ ] Duplicate, mirrored duplicate — `duplicateSelection`
- [ ] Groups: create, delete, rename, hide, assign
- [ ] Restyle a selection from the brush panel — `S.restyle`
- [ ] Fill a whole guide — `fillGuide`
- [ ] Shape snapping: line, curve, circle; hold-to-adjust; press-hold circle
- [ ] Symmetry: mirror X/Z, radial n-fold, fold indicator

**Done when:** every tool in the web toolbar has a touch equivalent, and each
one is undoable in a single step.

---

## Phase 04 — Files and interchange

*Sketches that survive, and open in both builds.*

- [ ] `.plume.json` serialize and restore — **the same format** as `doc.js`
- [ ] Autosave to device storage (IndexedDB → Room or files)
- [ ] Storage Access Framework: open and save
- [ ] Export OBJ + MTL — `objSource`
- [ ] Export STL, binary and ASCII — `stlBuffer`
- [ ] Export glTF 2.0, embedded buffer — `gltfSource`
- [ ] Export PNG snapshot
- [ ] Import OBJ and STL as model guides — `parseOBJ`, `parseSTL`
- [ ] Image references, each with its own layer — `addReference`

**Done when:** a sketch made on the phone opens in the browser and back again,
with geometry matching to the format's own precision.

---

## Phase 05 — How it looks

*Lighting and the render pass. Mostly shader work, and GL ES helps.*

- [ ] Key light: direction, colour, intensity, ambient — `P.LIGHT`
- [ ] Toon banding — `uToon`, `uToonStep`
- [ ] Render mode split — fast while drawing, full on demand
- [ ] Ground shadow — projected silhouette pass
- [ ] Depth of field — **real depth textures here**, not the packed RGBA the web
      build needs
- [ ] Film grain and pixelate
- [ ] Background colour and fog

**Done when:** the same sketch under the same light reads the same on both
builds. This phase gets *easier* than the web original.

---

## Phase 06 — An interface built for a phone

*The part that must NOT be ported.*

- [ ] Bottom sheets replacing the side rails
- [ ] Radial menu on long-press — a rail costs a thumb reach
- [ ] Brush panel: type, size, opacity, colour, pressure
- [ ] Stylus hover preview of the actual nib
- [ ] Scene, groups and references panels
- [ ] Back gesture steps out; it does not exit
- [ ] Orientation, display cutouts, window insets
- [ ] Onboarding — the web build's six-step walkthrough
- [ ] Finger-drawing toggle and palm rejection settings

**Done when:** someone who has never seen the web build can draw on a guide
without being told how.

---

## Phase 07 — Ship it

*Play Store, and the things that only matter once real people install it.*

- [ ] Release signing and a keystore you cannot lose
- [ ] Confirm the `applicationId` — **permanent** once published
- [ ] Play Console listing, screenshots, privacy policy
- [ ] Internal testing track — the closest thing to the Pages URL
- [ ] Crash and ANR reporting
- [ ] Performance on mid-range hardware, not just flagships
- [ ] Memory ceiling: a big sketch on a 4 GB phone
- [ ] Target SDK and 16 KB page-size compliance

**Done when:** it is on the Play Store and a stranger's phone renders what
yours does.

---

## Two things worth deciding early

**Phase 1 has a blocker nothing else can clear.** The renderer, gestures and
activity compile, but no frame has ever been drawn. Until someone installs the
APK and touches the screen, everything in `app/` is unverified — and bugs there
will be discovered all at once rather than one at a time. That run is the single
highest-value thing to do next, and it takes minutes.

**Phase 2 is roughly half the remaining work.** `guides.js` is 1,702 lines and
everything downstream leans on it: tools edit curves that live on guides, files
store guides, the render pass draws them. It is pure logic, so it is testable
without a device — but it is worth not underestimating, and worth doing before
the interface, so the UI is designed around a workflow that actually exists.

**One flexible edge:** the order above puts guides before tools and files
because tools edit curves that live on guides. That dependency is real. But if a
rougher app in hand sooner matters more, Phase 4 (files) can jump ahead of
Phase 3 — save/load does not strictly need the editing tools.
