# Porting Plume to Android

How the web build maps onto this one, what is shared, and the mistakes worth
not repeating.

## What is shared, and how

Nothing is shared as source. The two builds are different languages, and a
transpiler would drag Three.js along with it. What is shared is the
**algorithms**, re-implemented and then pinned by tests that assert the same
properties on both sides. The web suite and `:core`'s tests check the same
facts about the same maths; if the implementations drift, one of them goes red.

| web (`Pl/Plume/js/…`) | Android (`core/…`) | state |
|---|---|---|
| `core.js` `computeTangents`, `transportFrames`, `arcLengths` | `Frames.kt` | ported, tested |
| `strokes.js` `loopsClosed` | `Frames.loopsClosed` | ported, tested |
| `strokes.js` `dedupe` | `Dedupe.kt` | ported, tested |
| `strokes.js` `BRUSH` table, `segOf`, `sectionPoint`, `buildGeometry` | `Stroke.kt` | ported, tested |
| `guides.js` `closestPtTriangle`, `gridOf`, `snapToSurface` | `Surface.kt` | ported, tested |
| `tools.js` `reprojectToGuide` | `Reproject` | ported, tested |
| `guides.js` sweep surface, bend, loft, primitives | — | **not ported** |
| `doc.js`, `export.js`, `import.js` | — | **not ported** |
| `ui.js`, `app.js`, `camera.js`, `fx.js` | `app/` (rewritten, not ported) | design only |

## Platform split

The rule is: **anything that would give a different answer on the two platforms
is a bug.** So the split is not "what is convenient" but "what is inherently a
screen".

**Shared (`core`, pure Kotlin, no Android import):** geometry, maths, the
document model. It must not import `android.*` — that is what keeps it testable
on a JVM, and CI should fail if it ever does.

**Platform-specific (`app`):**

- *Rendering.* Three.js does scene graph, buffers, shader compilation and
  material state. None of that exists in GL ES, so `SketchRenderer` does it
  directly: one VBO set per stroke, uploaded on commit, one shader program
  compiled once at surface creation.
- *Input.* A `PointerEvent` and a `MotionEvent` are not the same shape, and the
  gesture vocabulary is genuinely different (below).
- *Files.* The web build uses IndexedDB and `<input type=file>`; Android uses the
  Storage Access Framework, and should — `ACTION_CREATE_DOCUMENT` for export and
  `ACTION_OPEN_DOCUMENT` for import, so sketches land where the person expects
  rather than in app-private storage.

## Input: what must NOT be copied

The desktop build hangs orbit, pan and zoom off modifier keys and a wheel. A
phone has none of those, so the number of fingers is the mode:

| gesture | does |
|---|---|
| one finger, or a stylus | draws |
| two fingers | orbit, pan and zoom together |
| stylus while fingers are down | still draws — the fingers are a resting hand |

Three rules in `Gestures.kt` earn their place:

1. **A stylus outranks everything.** If the pen is drawing, a second touch is a
   palm and is ignored entirely, not promoted into a camera gesture. Feather's
   premise is resting your hand on the glass.
2. **A finger stroke is cancelled when a second finger lands.** The first touch
   was the start of a pinch, not a mark, and it has to be taken back rather than
   left on the page.
3. **Every batched sample is read**, not just the latest. A pen reports far
   faster than the display refreshes, and `getHistorical*` is where most of
   those samples are. Dropping them visibly corners a fast curve.

Pressure needs care: most panels report `1.0` for a finger regardless of force,
so only a stylus's pressure is trusted; a finger gets a flat 0.5.

## Rendering: the one that will bite

The web build spent a whole session on a stall that a desktop driver hides.
`S.rebuild` disposed the material and made a new one every time; disposing a
`ShaderMaterial` drops the last reference to its GL program, so the next frame
compiled and linked it again — **90 links across a 90-move drag**. Desktop
swallows it. A phone GPU stalls tens to hundreds of milliseconds per link, which
is seconds of freeze.

So here: **the program is compiled once**, in `onSurfaceCreated`, and per-frame
work is uniforms and draw calls only. Buffers are uploaded on commit and not
touched again.

Two more, carried over deliberately:

- `RENDERMODE_WHEN_DIRTY`. A sketchbook is still most of the time and a
  continuous loop is the fastest way to flatten a battery.
- One draw call per stroke is the known ceiling. Merging committed strokes per
  group is the fix when it bites — but measure first; it was never the web
  build's actual bottleneck.

## Mistakes the port already caught

Worth recording, because both were found by writing the Kotlin tests and both
were real bugs in the shipped web build:

**The nearest-surface query indexed vertices.** A guide is a coarse mesh — 520
vertices carrying 896 triangles — so a point can lie exactly ON a large triangle
while the nearest vertex is 67mm away on a different one. Refining over that
vertex's triangles moved points that were already correct: still on the guide,
but slid 21mm along it. Indexing **triangles** agrees with brute force to
0.000mm.

**The search walked in rings of cells, which breaks on a plane.** A flat surface
has no extent in one axis, so that cell size collapses to the 1e-6 floor, a point
50mm off lands at cell index 50000, and clamping it back leaves an empty range —
the query scanned nothing and reported the point as already on the surface. It
grows by **world radius** now: once everything within the radius is tested and
the best hit is nearer than the radius, nothing outside can be closer. That holds
on any mesh, and it was faster too (2.7ms → 1.9ms on the web build's benchmark).

Both fixes were applied to `Pl` as well. That is the value of porting with tests
rather than transliterating.

## Next

1. Move the guide-as-sweep surface into `core`, with tests, then stroke
   projection onto it. Until that lands, Anvil draws on a camera-facing plane
   only, which is the web build's no-guide fallback.
2. Document model and save/load in `core`, sharing the `.plume.json` format so
   a sketch opens in both builds.
3. Then the interface, designed for a phone rather than ported.
