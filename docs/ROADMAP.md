# Anvil port roadmap

Every feature in the Plume web build, ordered into eight phases by what
genuinely depends on what. Each phase ends with something you can hold in your
hand — not a milestone on paper — and each one's logic goes into the tested
core before anything draws it.

**62 items · 45 complete · ~10,800 lines of web build to port · ~65% of it is
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

## Phase 04 — Files and interchange `7 OF 9`

*Sketches that survive, and open in both builds.*

- [x] `.plume.json` serialize and restore — **the same format** as `doc.js`
- [x] Autosave to device storage — debounced, and forced on pause
- [x] Storage Access Framework: open and save
- [x] Export OBJ + MTL — `objSource`
- [x] Export STL, binary and ASCII — `stlBuffer`
- [x] Export glTF 2.0, embedded buffer — `gltfSource`
- [ ] Export PNG snapshot — needs a framebuffer read, which is Phase 5's territory
- [x] Import OBJ and STL as model guides — `parseOBJ`, `parseSTL`
- [ ] Image references, each with its own layer — `addReference` (needs texturing, Phase 5)

**Done when:** a sketch made on the phone opens in the browser and back again,
with geometry matching to the format's own precision.

**Where it stands.** Seven of nine. The format is transcribed field for field
from `doc.js` rather than invented, which is the whole point of it — until a
sketch can move between the builds, "the same sketch measures identically in
both" has been an unchecked claim since Phase 2. 200 core tests, up from 165.

The **done when** is still not proven, and this is the honest limit: every test
here round-trips through *this* build's own reader. Nothing has yet opened an
Anvil file in the browser or a Pl file on the phone. The format is transcribed
carefully and the tests cover the traps — null tangents rather than zeros, a v1
file with no group list, the sections this build does not model — but a
cross-build check needs someone to do it with both in front of them.

The two open items both want a render pass rather than logic: a PNG snapshot
needs a framebuffer read, and image references need texturing. Both belong with
Phase 5.

What the units taught: **OBJ and STL are millimetres, glTF is metres**, and
getting it backwards hands Blender a sketch a thousand times too big with
nothing in the file to say so. There is a test that measures a 100 mm line
coming out as 100 in one path and 0.1 in the other.

---

## Phase 05 — How it looks

*Lighting and the render pass. Mostly shader work, and GL ES helps.*

- [x] Key light: direction, colour, intensity, ambient — `P.LIGHT`
- [x] Toon banding — `uToon`, `uToonStep`
- [x] Render mode split — fast while drawing, full on demand
- [x] Ground shadow — projected silhouette pass
- [x] Depth of field — **real depth textures here**, not the packed RGBA the web
      build needs. It did get easier: `DEPTH_COMPONENT24` is sampleable off the
      attachment the scene already wrote, so the packing, the unpack constants
      and the web build's second geometry pass all go.
- [x] Film grain and pixelate
- [x] Background colour and fog
- [x] The Scene tab that drives all of it
- [x] PNG snapshot (deferred here from Phase 4)
- [ ] Image references (also deferred from Phase 4) — an imported photo as a
      guide to trace over. OBJ and STL import already build a guide; a texture
      needs the sampler the guide shader does not have yet.

**Done when:** the same sketch under the same light reads the same on both
builds.

**NOT done, and this is the same shape as Phase 4's.** Everything above is
written, and the half that can be checked without a GPU is under test: the
light's defaults, the shadow camera's fit, the aperture's direction, and the
document round trip. But no frame of any of it has been rendered — there is no
GPU in the build environment, and CI compiles the APK rather than running it.
"Reads the same on both builds" needs the two side by side, and until someone
does that this phase is code complete rather than verified.

---

## Phase 06 — Plume's interface, ported

*This phase used to be called "the part that must NOT be ported", on the
grounds that a phone wants bottom sheets rather than a 58px vertical rail.
That was wrong about the web build, not about phones: Plume's own stylesheet
carries a `body.compact` mode that does exactly this — "phones. Rails become
bottom sheets; the dock is the only permanent chrome" — and switches to it
under 720px. There was never a rail to spare a thumb from. So the whole
interface is ported, both layouts included, and the width picks between them
the way `UI.applyMode` does.*

- [x] Design tokens — every `:root` and `body.dark` custom property, as
      `values/` and `values-night/` colours and dimensions
- [x] The icon set — 43 sprite symbols and 8 brush glyphs as VectorDrawables,
      generated from the web build's own sprite by `tools/icons/gen.py`, in
      hollow and filled variants because `button.on svg{fill:currentColor}`
      outranks the hollow default
- [x] The widget vocabulary — `.panel`, `.ico` (with its 3px partner dot),
      `.div`, `.sep`, the draggable `.val`, the range inputs, the toast
- [x] Desktop layout — top-left cluster, help, view readout, tool pill, brush
      rail with its collapse tab, undo pill, guide context bar, selection bar
- [x] Compact layout — bottom sheets over a permanent dock, at Plume's own
      720px threshold
- [x] Back gesture steps out; it does not exit (`UI.closeTopSheet`'s order)
- [x] Orientation, display cutouts, window insets
- [x] Brush panel: type, size, opacity, pressure and the hue wheel
- [x] The transform joystick, and with it the dock's "Move" slot
- [x] Scene, Curves and Import panels
- [x] Onboarding — the web build's six-step walkthrough
- [x] Finger-drawing toggle
- [x] Bend, Loft, Primitives and hold-to-shape, all reachable
- [x] Tooltips on stylus hover and long press; the keyboard map; the numeric
      keypad; the input readout; the save state; the symmetry fold indicator
- [ ] Stylus hover preview of the actual nib
- [ ] Palm rejection settings beyond "a pen outranks a finger"

**Done when:** someone who has never seen the web build can draw on a guide
without being told how.

---

## What running it on a device found

Everything above had been compiled, type-checked and unit-tested. None of
that catches a wrong reading of the reference, and a session with the app in
hand turned up nine of them. Recording what they had in common, because it
is the same shape every time: each was a place where the port had made a
DECISION the web build had already made differently, and had written a
comment justifying it.

- **The nib was aimed by the stylus, not by the surface.** Tilt azimuth was
  mapped onto a rotation about the tangent, which is exactly what lifts a
  blade off a guide. Worst on `wide`, 3.4 radii across; invisible on a round
  pen, which has no direction to get wrong. The web build carries the same
  finding after trying the same thing.
- **A mirrored nib was aimed off its own surface**, because a reflection
  turns the frame the section angle is measured in over. Invisible on a flat
  guide; 58 degrees out on a cylinder.
- **The edge trim was dropped** by the file format and by every point an
  erase or a smooth inserted, and nothing downstream can measure it again.
- **The repeat-tap tool swap was switched off on a phone** — which is where
  the app is used — and nothing updated the pill when a tool changed.
- **Background and light colour were still toasts** from before the colour
  card existed.
- **One finger never orbited.** A camera gesture only ever began on the
  second finger landing.
- **The camera mapping was neither of the two the web build has.** They
  differ by one finger depending on whether a finger is drawing, and the
  lens gesture was unreachable.
- **Every tap was thrown away** by a test that read `fingerDraws &&
  peakFingers == 1`.
- **A pen changed nothing.** First contact is meant to hand the app back to
  the pen-first mapping, once.
- **The keypad opened in the middle of the screen** rather than beside the
  readout it was editing.

The lesson for what is left: a comment explaining why this port differs from
the reference is a place to look first, not a decision that has been made.

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
