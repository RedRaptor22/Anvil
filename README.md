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
| tests | 584 in-browser checks | 20 JVM unit tests |
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

Verified by `./gradlew :core:test` — 20 tests, all passing:

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

Written but **not** verified here, because it needs a device:

- the GL ES 3.0 renderer (`app/SketchRenderer.kt`)
- the gesture layer (`app/Gestures.kt`)
- the activity shell (`app/MainActivity.kt`)

There is no Android SDK in the environment these were written in, so **the APK
has never been built or run.** Treat `:app` as a reviewed design, not as
working software.

## Not yet ported

Roughly in the order they matter:

1. **Guides** — the guide-as-sweep surface, projection of strokes onto it, bend,
   loft, primitives. This is the largest remaining piece and belongs in `core`.
2. **The interface.** Deliberately not transliterated: a phone wants a bottom
   sheet and a radial menu, not the desktop's 58px vertical rail.
3. Document save/load, undo beyond a flat stroke list, export (OBJ/STL/glTF),
   lighting controls, the post pass, symmetry, selection and the editing tools.

## Licence

Same as Pl.
