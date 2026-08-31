# Anvil

The Android build of [Plume](https://github.com/RedRaptor22/Pl), a 3D
sketchbook. Kotlin and OpenGL ES 3.0 — no WebView, no web view wrapped in a
shell.

**Status: a verified foundation, not a finished app.** The drawing engine is
ported and under test; the guide system and the interface are not. See
[What actually works](#what-actually-works) before you judge it, and
[docs/PORTING.md](docs/PORTING.md) for how it maps onto the web build.

---

## Why two repositories

`Pl` is the web build: one HTML page of `<script>` tags, no build step, which is
what makes it quick to change and instantly testable on any device with a URL.
That property is worth keeping, so Anvil does not replace it.

Anvil is a separate Gradle project because an Android app cannot be a page of
script tags. What the two share is not source files — it is the **algorithms**,
re-implemented here in Kotlin and pinned in place by tests that assert the same
properties the web suite asserts. If the two drift, these tests fail rather than
someone's phone drawing differently.

| | Pl (web) | Anvil (Android) |
|---|---|---|
| language | ES5 JavaScript | Kotlin |
| renderer | Three.js r128 (WebGL) | OpenGL ES 3.0, direct |
| tests | 584 in-browser checks | 200 JVM unit tests |
| ships as | a URL | an APK |

## Modules

```
core/   pure Kotlin. No Android dependency at all, so it compiles and its
        tests run on a plain JVM. Everything that is not a screen lives here:
        frame maths, stroke geometry, the nearest-surface query, spur removal.

app/    the Android half: GL renderer, gesture layer, activity. Needs the
        Android SDK, and is only included in the build when one is present.
```

That split is the point. `core` is where the porting risk is — the maths took
real effort to get right in the web build and is easy to get subtly wrong
again — so it is the part that is testable without a device or an emulator.

## Build

**The core, and its tests — needs only a JDK:**

```bash
./gradlew :core:test
```

This is what CI should run. It does not need the Android SDK, and it is how the
port is verified.

**The app — needs the Android SDK:**

```bash
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

`settings.gradle.kts` only includes `:app` when it finds `local.properties` or
`ANDROID_HOME`, so the first command keeps working on a machine with no SDK.

## What actually works

Verified by `./gradlew :core:test` — 200 tests, all passing:

- **Rotation-minimising frames** by double reflection (Wang et al. 2008), the
  same algorithm as the web build. Orthonormal along a helix to 1e-9, finite and
  continuous through an inflection where a Frenet frame would flip.
- **Closed loops.** A ring's two ends share one tangent and one cross-section, so
  a snapped circle has no seam. The test also asserts the *unfixed* behaviour —
  ends 5.625° apart, exactly one angular step — so it cannot pass vacuously.
- **Stroke geometry** for all eight brushes: finite, in-range indices, sealed
  tubes. A ring is welded shut with no caps and no open rim; an open stroke keeps
  its caps.
- **Nearest point on a surface**, agreeing with brute force over every triangle
  to 1e-9 across 400 random probes, including on a coarse mesh and on a plane.
- **Reprojection**, which puts a stroke shoved off a surface back onto it.
- **Spur removal**, which drops a folded sample before it can reverse a tangent,
  while keeping a deliberate sharp corner.
- **The camera and its projection.** A lens focal length in millimetres becomes a
  field of view the way Feather expresses it; a pixel unprojected onto the draw
  plane projects back to the same pixel to 1e-6; a pan moves the sketch by
  exactly the pixels the fingers moved; the orthographic toggle does not shift
  the framing. None of this was testable while the matrices lived in
  `android.opengl.Matrix`, which is why they no longer do.
- **The live stroke buffer.** The geometry you see while the pen is down is
  compared float-for-float against the geometry the commit builds, for all eight
  brushes, including after the buffer has had to grow. An append touches a
  bounded tail rather than the whole tube — which is the difference between
  drawing being linear and being quadratic in stroke length.
- **Undo with a memory budget.** Steps declare what they retain in stroke points,
  so three 200k-point strokes are evicted where two hundred dots would not be,
  and a single step larger than the whole budget is still undoable.
- **Stable Stroke**, which smooths the input before it is projected, and drops
  the jitter of a pen resting on glass without swallowing a slow drift.
- **Guide surfaces.** A stroke extruded along the view into a swept surface,
  whose anchor row comes back out as *exactly* the stroke that made it; a flat
  guide triangulated to the outline you drew rather than a grid clipped to it,
  with the triangle areas summing to the polygon's own; and the five primitives
  at the dimensions the web build gives them.
- **Painting on a guide.** The ray query agrees with brute force over every
  triangle across 400 probes; sampling a surface by arc length and projecting a
  ray onto it land in the same place, so a filled row sits where a hand-drawn
  one would; a stroke running off the edge clamps back and is lit the same way
  a hit is; and on a flat guide the nib is trimmed against the outline you drew
  rather than its bounding box.
- **The editing tools.** The eraser clips its disc against the centreline as a
  continuous polyline, so a thin eraser cuts a segment it crosses even when no
  sample is inside the disc — there is a test that builds exactly that case.
  Smoothing pins the ends and reprojects onto the guide, so paint stays where it
  was painted. Liquify's pinch cannot overshoot the cursor. A fill rounds its
  row count UP, because rounding down leaves a groove down every seam, and
  breaks a row into separate strokes where it leaves the shape and comes back.
  Draw Shape refuses to close an arc into the circle it happens to fit.
- **The document format**, transcribed field for field from the web build's so a
  sketch opens in both. A missing tangent writes nulls rather than zeros, a v1
  file with no group list still opens, and the sections this build does not
  model yet — the light, the post effects — travel through untouched rather than
  being silently dropped.
- **Export and import.** OBJ and STL in millimetres because neither format
  declares a unit; glTF in metres because it does. The binary STL header
  deliberately avoids starting with "solid", or a sniffing reader takes it for
  ASCII. glTF colour is converted to linear, because `baseColorFactor` is.
- **Bend and Loft.** A bent guide follows the stroke whichever way it was drawn
  — four primitives are bent along three directions each and checked to lean the
  way the pen went, because the web build's version deformed along local +X
  regardless and was measured up to 180° out. A loft flips any curve drawn the
  other way round, so the surface does not pinch to a waist and turn itself
  inside out.

Three bugs turned up while porting this, all of which the tests now pin:

- **Roll rotated the canvas backwards.** A view matrix is the camera object's
  inverse, so rolling the camera by +a rotates the view by −a; the renderer had
  it as +a. Invisible until two fingers twist — which had never happened,
  because nothing had ever run.
- **A tapered stroke's preview froze rings at the wrong radius.** Uneven sample
  spacing lets the rewrite window step over a ring, which then keeps the taper
  factor it had on the way past: measured at 0.758 of its radius where the
  answer was 1.0. The web build has the same gap, hidden there because it
  rebuilds exactly on commit.
- **The bounded rewrite window was bounded in name only.** The cap centres live
  at vertex 0 and move on every sample, so a single dirty range covering them
  and the rings spanned the entire stroke — the compute stayed cheap while the
  upload quietly went back to being the length of the stroke.

**Runs on a device.** The APK has been installed and used: it draws, orbits and
undoes on real hardware. Everything in `app/` had only ever been compiled before
that, so this is the check that mattered most and it has now been made.

It is still worth being exact about what the tests do and do not cover. `core`
decides *where a point goes*; `app/` decides *whether anything appears on
screen*. A JVM test proves the first to 1e-9 and says nothing at all about the
second — a mistyped uniform, a buffer bound to the wrong target or a shader
that fails on one vendor's driver would pass every test here and still show a
blank screen. `app/` is checked by running it, which is why that run matters.

## Installing it on a phone

There is nothing to build. Every push produces an APK:

1. Open the [Actions tab](https://github.com/RedRaptor22/Anvil/actions) and click
   the newest green run.
2. Scroll to **Artifacts** and download **anvil-debug-apk** (a zip).
3. Unzip it — inside is `app-debug.apk`.
4. Put it on the phone (email, Drive, USB) and tap it. Android will ask to allow
   installs from this source; that is expected for an app not from the Play
   Store.

**What you will see:** a pale screen with a ground grid, and a control bar along
the bottom. Drag one finger or a stylus to draw; two fingers orbit, pinch and
twist, and three fingers pan. The bar carries brush size, seven ink colours,
undo, redo, clear, and a mode button cycling Draw / Guide / Flat guide.

On an empty page the first stroke becomes a **guide** — a translucent,
grid-lined surface extruded away from you along the view. Orbit, and you are
looking at a sheet you can draw on; the pen then paints onto that surface
rather than onto the screen plane, clamping back to the nearest point if you
run off the edge.

A scrolling tool row reaches draw, erase, vacuum, smooth, select, lasso,
liquify and the two guide kinds; beside it, fill, duplicate, mirror, delete,
save, open and export. Every gesture undoes in one step, and the sketch
autosaves — close the app and it comes back.

There is no brush picker and no transform gizmo yet — that is the current
state, not a fault. Artifacts expire after 90 days.

## Not yet ported

Roughly in the order they matter:

1. **Opening an Anvil file in the browser, and a Pl file on the phone.** The
   format is written to match and the tests round-trip it, but nothing has yet
   carried a sketch between the two builds. That is the check the whole format
   exists for.
2. **Lighting and the render pass** — the key light, toon banding, depth of
   field, and with them the PNG snapshot and image references that Phase 4
   could not finish.
3. **A transform gizmo**, which is the one editing tool with no touch
   equivalent yet.
4. **The interface.** The bottom bar is a floor, not a design. Deliberately not
   transliterated: a phone wants a bottom sheet and a radial menu, not the
   desktop's 58px vertical rail.
3. Document save/load, export (OBJ/STL/glTF), lighting controls, the post pass,
   symmetry, selection and the editing tools.

[docs/ROADMAP.md](docs/ROADMAP.md) orders all of it into eight phases.

## Licence

Same as Pl.
