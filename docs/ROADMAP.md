# Anvil port roadmap

Every feature in the Plume web build, ordered into eight phases by what
genuinely depends on what. Each phase ends with something you can hold in your
hand — not a milestone on paper — and each one's logic goes into the tested
core before anything draws it.

**62 items · 38 complete · ~10,800 lines of web build to port · ~65% of it is
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

## Phase 01 — Draw and orbit, for real `DONE`

*Prove the engine on hardware, and make the app usable at its floor.*

- [x] **Run it on a device — first frame, first stroke (blocking)**
- [x] Camera controller into core — from `camera.js` orbit/pan/zoom
- [x] Screen↔world: project, unproject, worldToScreen — from `app.js`
- [x] Camera-facing draw plane — `refreshDrawPlane`
- [x] Incremental live stroke buffer — `S.Live`
- [x] History with a memory budget — `P.History`
- [x] Grid and axis in the renderer
- [x] Minimum UI: size, colour, undo, redo, clear
- [x] Stable-stroke smoothing on input — `TOOL.stable`

**Done when:** you can draw, orbit, undo and clear on a phone without a
keyboard — and the frame budget holds at 120 Hz with a 300 mm brush.

**Verified on hardware.** The APK was installed and the app draws, orbits and
undoes on a real device — which is what closed the blocking item and, with it,
the phase. Everything in `app/` had only ever been compiled until that run.

Three things came out of doing the rest, and all three are the reason the logic
went into `core` first:

- **Roll turned the canvas backwards.** A view matrix is the camera object's
  inverse, so rolling the camera by +a rotates the view by −a; the renderer
  built it as +a. Invisible at rest, which is how it survived never having run.
- **A tapered stroke's preview froze rings at the wrong radius.** The rewrite
  window can step over a ring when samples are unevenly spaced, and the ring
  keeps whatever taper factor it had on the way past — measured at 0.758 of its
  radius where the answer was 1.0. **The web build has the same gap**, hidden
  there because it rebuilds exactly on commit.
- **The bounded rewrite window was bounded in name only.** The cap centres sit
  at vertex 0 and move on every sample, so one dirty range covering both them
  and the rings spans the whole stroke — the compute stayed cheap and the
  upload quietly went back to being the length of the stroke.

---

## Phase 02 — Guides `CODE COMPLETE`

*The Feather premise: draw a surface, then draw on it.*

- [x] Sweep surface from a stroke — `createFromStroke`, `rebuildSweep`
- [x] Surface sampling and spans — `sampleSurface`, `surfaceSpan`
- [x] Project strokes onto a guide — `G.project`
- [x] Edge behaviour: reach, outline, clamp — `reachAlong`, `insideOutline`
- [x] Isolation masking — `isMasked` (the per-viewpoint cache is not ported yet)
- [x] Translucent grid-lined guide shader
- [x] Flat shape guide — `createFlatFromStroke`
- [x] Five primitives — cube, pyramid, sphere, torus, tube
- [x] Bend — `G.bend`, `bendMesh`
- [x] Loft from selected curves — `loftFromCurves`
- [x] Guide lifecycle: active, close, save, reuse, opacity

**Done when:** a stroke lands exactly on a curved guide, stays there under every
tool, and the same sketch measures identically in both builds.

**Where it stands.** All eleven are written and tested — 112 core tests, up
from 19 at the start of Phase 1. The surfaces build, the ray query is checked
against brute force over every triangle, sampling by arc length and projecting
a ray agree with each other, the edge is the outline you drew rather than its
bounding box, and the guide draws.

Two halves of "done when" are NOT closed yet, and both need someone else:
"stays there under every tool" needs the Phase 3 tools to exist, and "measures
identically in both builds" needs the same sketch opened in each, which needs
the Phase 4 file format. Until then this is a phase whose parts are verified
and whose whole is not.

Three things the tests turned up, beyond the edge-on fact below: the pyramid
primitive was a four-sided **prism** — the dimension test checked its height,
and a prism and a cone of the same height share a bounding box, so it could not
tell them apart; an imported **image** guide is allowed to be fully opaque,
which the "never completely opaque" clamp was wrongly applying to; and a loft
rebuilt through the resampler drifts 0.05mm per cycle, which is why the rebuild
path uses the stored curves verbatim.

One thing worth knowing before touching this again:
**a fresh swept guide is edge-on to the view that made it.** The profile is
extruded ALONG the view, so from where you drew it the surface is a curtain
seen side-on, and a ray down the view axis runs parallel to it and hits
nothing. That is why Feather has you orbit before painting. Every probe that
fired down the sweep axis found nothing and looked like a broken ray query;
there is now a test that states the fact outright.

---

## Phase 03 — Editing tools `11 OF 13`

*Everything that changes a curve after it is drawn.*

- [x] Erase, with splitting — `eraseScreen`, `eraseSphere`
- [x] Vacuum — `vacuumAt`
- [x] Smooth — `stepSmooth` + reprojection
- [x] Liquify: push, pinch, comb — `liquifyApply`
- [x] Select: tap and sweep — `tapSelect`, `sweepSelect` (long-press on a group row is Phase 6)
- [x] Lasso, with a visible boundary — `stepLasso`
- [ ] Transform — the maths is done; it still needs a touch gizmo, not the desktop joystick
- [x] Duplicate, mirrored duplicate — `duplicateSelection`
- [x] Groups: create, delete, rename, hide, assign (no panel yet — Phase 6)
- [x] Restyle a selection from the brush panel — `S.restyle`
- [x] Fill a whole guide — `fillGuide`
- [x] Shape snapping: line, curve, circle; hold-to-adjust (not on the pen path yet)
- [x] Symmetry: mirror X/Z, radial n-fold (the fold indicator is a render pass, Phase 5)

**Done when:** every tool in the web toolbar has a touch equivalent, and each
one is undoable in a single step.

**Where it stands.** Eleven of thirteen, and the "undoable in a single step"
half is met: a sweep of the eraser that cuts a line into six pieces undoes in
one tap, and a minute of pushing with Liquify undoes at once. 165 core tests, up
from 112.

Two are genuinely not done. **Transform** has its maths — the matrix carries the
frozen frame so a rotated curve keeps its shape, and a uniform scale scales the
nib while a stretch does not — but no touch gizmo to drive it, and the desktop
joystick is exactly what must not be ported. **Shape snapping** is fitted and
tested but is not on the pen path yet: it needs the press-and-hold timer wired
into the stroke, which is interaction rather than logic. Groups and the fold
indicator have their logic and want a panel and a render pass respectively.

Three things the tests turned up:

- **The sweep never tested the press point itself.** Stepping from the origin
  starts one whole step past it, so a curve lying exactly under the finger is
  sampled 4px away and missed whenever the tube is thinner than that — about
  3.6px for a 14mm brush at a normal zoom. That is the very stroke a sweep is
  meant to begin with. **The web build has the same gap**, hidden because a real
  hand moves slowly at the start.
- **`setStrokes` re-uploaded every stroke's GL buffers.** Harmless while only
  undo called it; the eraser calls it on every pointer sample, so a drag across
  two hundred curves re-uploaded all two hundred at 120 Hz.
- A test that laid its stroke along the guide's **ruled** direction proved
  nothing, because a swept surface is dead straight that way and smoothing could
  never pull the stroke off it.

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

**Phase 1's blocker is cleared.** The renderer, gestures and activity had only
ever been compiled; the APK has now been run on a device and it draws. That was
the single highest-value thing to do, it took minutes, and it is the reason the
phase can be called done rather than written.

**Phase 2 is roughly half the remaining work.** `guides.js` is 1,702 lines and
everything downstream leans on it: tools edit curves that live on guides, files
store guides, the render pass draws them. It is pure logic, so it is testable
without a device — but it is worth not underestimating, and worth doing before
the interface, so the UI is designed around a workflow that actually exists.

**One flexible edge:** the order above puts guides before tools and files
because tools edit curves that live on guides. That dependency is real. But if a
rougher app in hand sooner matters more, Phase 4 (files) can jump ahead of
Phase 3 — save/load does not strictly need the editing tools.
