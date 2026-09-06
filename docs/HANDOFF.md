# Anvil — session handoff

**Anvil** is an Android drawing app: a faithful clone of **Feather 3D** (iPad).
You draw 2D strokes onto 3D "guide" surfaces you make yourself, so a sketch
becomes geometry. This file is a handoff from a working session — what exists,
what was just built, what is known to be missing, and the working rules that
were learned the hard way.

- **Repo:** `RedRaptor22/Anvil`
- **Branch (develop and push here, nothing else):** `claude/markdown-files-review-2ip6at`
- **Do not open a pull request unless explicitly asked.**
- **Reference documentation:** <https://support.feather.art/docs> — reachable
  with `curl` from the session container. `WebFetch` may report it blocked;
  `curl` works. Everything in the code marked `FACT:` is a direct quote from
  those pages.

---

## 1. How the project is built

Two Gradle modules, and the split is load-bearing:

| Module | What is in it | Rule |
|---|---|---|
| `core/` | All maths and model: geometry, camera, guides, strokes, selection, transforms, document format, library model | **Pure Kotlin. Zero `android.*` imports.** Fully unit-tested on the JVM. |
| `app/` | Android only: five files — `MainActivity.kt`, `Chrome.kt` (all UI), `SketchRenderer.kt` (OpenGL ES 3.0), `Gestures.kt`, `Ui.kt` (custom views) | No business logic. Raises callbacks, renders pushed state. |

The reason for the split is the build environment: **there is no Android SDK in
the session container** (Google Maven is proxy-blocked), so `:app` can only be
compiled by CI. Anything testable therefore belongs in `core/`.

### Commands

```bash
./gradlew :core:test -PcoreOnly     # 415 JVM tests. Always run these.
./tools/appcheck.sh                 # type-checks :app locally. Always run this.
python3 tools/initorder.py          # construction-order check. Always run this.
```

- `tools/appcheck.sh` compiles `app/` against Robolectric's `android-all` jar
  from Maven Central plus the real `core` jar. It is the only local type-check
  for the Android half. It has no `aapt`, so resources and the manifest are
  unchecked — a bad `strings.xml` still only fails in CI.
- CI (`.github/workflows/android.yml`) runs the core tests, `initorder.py`, and
  builds a debug APK attached to the run as an artifact. **CI proves the code
  compiles. It never launches the app.**

---

## 2. Two crash classes that have already cost multiple builds

`Chrome.kt` is a single ~5200-line class that builds the whole interface in an
`init {}` block calling ~23 `buildX()` functions in order. It has produced the
same "app will not open" crash twice, by two different doors. Both guards are
now enforced by `tools/initorder.py`, which runs in CI.

**Door 1 — a builder reads a `lateinit` a later builder assigns.**
Fixed by a `built` flag: `refresh()` starts with `if (!built) return`, and
`init` sets `built = true` before its final `refresh()`. Never remove that
guard; any builder can reach `refresh()` through a helper.

**Door 2 — a property with an initialiser declared *below* the `init` block.**
Kotlin runs property initialisers and `init` blocks in **source order**, so
`private val panel = LinearLayout(act)` written below `init` is still `null`
while the builders run — a `NullPointerException` out of the constructor, out
of `onCreate`, and a launcher that says the app has a bug. This shipped: five
hundred lines of new panels were added at the end of the class with their
fields beside them, and 22 fields were null. **The `init` block now sits at the
very bottom of the class, after every declaration**, and `initorder.py` fails
if any initialised property is declared below it. Add new fields wherever you
like; do not move `init`.

---

## 3. What was built in this session

Every item below is documented behaviour, quoted in the code as `FACT:`. All
of it is on the branch and green in CI.

### Home screen (`52120f5`, `8f35d72`)
Rebuilt from Feather's Home pages, item for item.
- Left sidebar: **Recents** (clock icon) and **Folders** (green folder icon),
  icon-and-word rows, the active one on a soft rounded fill; Settings and Help
  at the bottom.
- Top bar: hide-sidebar, breadcrumb path (tap a crumb to go up), refresh, add
  folder, sort (last modified / last created / name), close.
- Grid of tiles under **Folders** / **Notes** section headings. A folder tile
  shows the four most recently modified notes inside it. A heading with nothing
  under it is not drawn.
- `+` bottom right creates a note **in the folder you are standing in**.
- Selection bar (hold a tile to pick, tap more to add): deselect, rename,
  duplicate, export, **lighten**, delete.
- Drag to organise: hold an *already-picked* tile to lift it, drop on a folder;
  drop on a breadcrumb to move it back out.
- **The app opens on Home every launch**, including a fresh install with nothing
  in it. This was broken twice over — a first run went to the walkthrough, and
  Home was skipped entirely when there were no notes — so the one person
  guaranteed never to see it was a first-time user. The walkthrough now waits
  until you first reach the canvas.

Model in `core/Library.kt` (+ `LibraryTest.kt`): sorting, folder trees, cover
thumbnails, cycle-safe path walking, `canMove` (a folder cannot go inside
itself), `freeName`. Notes are still **discovered from disk**, never indexed —
only the two things the filesystem cannot say (a folder exists; which folder a
note is in) are written to `library.json`.

### Materials and patterns (`df33559`)
Per-**curve** materials, not per-brush: **Shadeless / Shaded / Glow / Cutout**,
on a third page of the colour card. `material` is `null` until someone chooses,
which is what makes the glow brush glow with nobody picking anything and keeps
old documents reading identically.
Five procedural patterns — **Dot, Line, Cross, Terrazzo, Stippled Dot** — with
intensity, angle and contrast sliders, generated in the fragment shader.
Refused by Glow and Cutout (the controls grey out rather than vanish).
- No UVs on a swept tube, so a pattern is projected **triplanar by the dominant
  axis of the normal, in world millimetres** — the same anchor as the pencil
  grain, so two crossing curves agree about where the dots are.
- Cutout draws **opaque** (it is a hole filled with the background; a
  translucent hole is a smear). Only Shaded reaches the shadow pass.
- `core/Material.kt`, `MaterialTest.kt`; shader work in `SketchRenderer.kt`.

### Quick menu (`511721e`)
Feather's Squeeze Menu, adapted. Android has no pencil squeeze, so it is
**two fingers held still** — the gesture with nothing else on it (two-finger
tap is already undo, two-finger drag pans). It unfolds where your hand is.
Contents are contextual: undo, redo, **Find Group** always; **New Group** and
**Recall Recent Guide** while drawing; **Select All** and **Stamp** while
selecting.
- The hold dies if the span between the fingers changes — a pinch that begins
  slowly is two fingers resting, and the naive clock ambushed it.
- **Find Group**: the next press names the group under it and activates it.
- **Recall Recent Guide** (`core/GuideScene.kt`): the last *closed* guide, one
  step only; never one that was deleted or filed.
- **Stamp**: each drag lays down a copy that follows the pen, measured at the
  depth it is actually at. A drag that never moved leaves nothing behind.

### Brush presets and colour drag (`572ee49`)
Presets save brush type, colour, size and opacity; **saved per note** in the
document, so a work reopens with the brushes it was drawn with. Chevron above
the brush rail; tap to load, hold to pick, plus to save, bin to delete —
deliberately outside the undo stack, as documented.
Dragging the colour dot adjusts **saturation** (horizontal) and **brightness**
(vertical). A picked palette group keeps its stepped cycling.

### The 2D joystick (`28db5cf`, `e7fc9c8`)
Feather's *default* joystick, which the app did not have — it only had the
global-XYZ one. Five handles where the documentation puts them: height above
the stick, width to the left, free scale between, rotate on the right. Rotation
and scale are about the **screen-centre crosshair**, not the selection.
**Lock** restricts the stick to four directions, collapses the scale handles to
one uniform one, and snaps rotation to whole 15° steps (intermediate angles are
never visited). Maths in `core/Transform.kt` (`View2D`, `lockedDelta`,
`snapStep`) with tests; widget is `Joy2D` in `Ui.kt`.

### Group tab (`19193c7`)
Hold to pick a **group** (not its curves — that moved onto the count), multi-
select, then delete / duplicate / **merge**, plus drag to reorder.
- Deleting several groups is one history step; the undo restores back-to-front
  so each remembered index is still valid.
- Merging records which group every curve came from — the one thing nothing can
  work out afterwards.
- Reordering pushes **no** history step: the panel's order is how the list
  reads, draw order is the stroke list and is untouched.
- Group rows go **green** when their curves are selected, lighter green when
  only some are.

### Smaller documented pieces
- **Render Mode** button at the top of the Stage Panel, moving lighting and the
  post pass together (materials only display correctly in rendering mode).
- **Resource cube**: one control cycling three states — drawable → visible →
  hidden — replacing two separate gestures.
- **Orbit point**: visible crosshair with Show and Pin switches. It is the
  point the view orbits, the depth-of-field focus, and the crosshair the 2D
  joystick turns around — all the same point, always at screen centre.
- **Lighten** (`core/Simplify.kt`): Douglas-Peucker with the tolerance measured
  **against the brush**, not in absolute units — a tenth of a millimetre off the
  line matters on a hairline and is invisible under a 30 mm marker. Corners
  survive; the ends never move, so a closed loop stays closed.

### Regressions found on-device and fixed (`a4cfcd0`)
- **Hold-to-shape on a guide profile disappeared.** The documented line *"If
  any resource is active, you cannot draw or loft a new 3D Guide"* was read as a
  rule about the **tool**, and `setTool` refused to switch to Guide while one
  was active — which is the normal state once you have drawn one. It is a rule
  about *creating*. The refusal is gone; a new guide now **closes** the one it
  replaces, so the old one is one tap away in Recall and the action pill says so.
- **Primitives could not be transformed.** The joystick read "the active guide,
  if picked", and a staged guide is deliberately not in the guide list. Staging
  now outranks everything for the joystick. It also needed a **staged matrix**,
  replayed on every rebuild, because changing segments or taper rebuilds the
  primitive from scratch and was throwing the position away.

---

## 4. Known gaps, in the order worth doing them

1. **3D joystick as a real in-scene gizmo.** Feather shows coloured cones
   (move), arcs (rotate) and a centre trackball sphere all live at once. Anvil
   keeps a Move / Turn / Size mode switch on a pad beside the canvas. The
   trackball's *substance* is already there (the pad's centre in Turn mode);
   the shape is not. This is the largest remaining divergence.
2. **Eyedropper and Injector scoped to the active group.**
3. **Importing photos as resources** to trace over (3D model import exists;
   image import does not).
4. Resource tab polish: rename by double-tap, multi-select, opacity slider for
   an imported image.

All divergences are recorded in `docs/FEATHER_PARITY.md` with the quotation
that motivated each. **Read that file before changing documented behaviour** —
it also records where a sentence was previously misread, so the same mistake
cannot be re-derived from the same line.

---

## 5. Working rules that were earned

- **Provenance in comments.** `FACT:` = a direct quote from the documentation.
  `INFERENCE:` = reasoned from one. `GUESS:` = neither. Keep using these; they
  are what let a later reader tell a decision from an invention.
- **Reproduce before fixing.** For the guide-bend faults, throwaway probe tests
  measured the wall side across 24 bend directions before a line was changed.
- **Prove a test is not vacuous.** After a fix, *plant the fault back* and
  confirm the test fails. This is how both `initorder.py` checks were
  validated (restoring `init` to its old position reported all 22 null fields).
- **Commit messages are prose**, explaining *why* and what was rejected — not
  bullet lists of what changed.
- **CI green ≠ it works.** The APK job compiles; nothing launches the app.
  Every behavioural claim needs the device. Ask for a screenshot or, on a
  crash, a `logcat` stack trace — guessing from the code has been expensive.
- **Do not narrow, widen or transform the request.** Two of this session's
  worst bugs came from implementing a stricter rule than the documentation
  actually stated.

### Environment notes
- **YouTube is usable for research.** `yt-dlp` downloads and `ffmpeg` (install
  via `pip install imageio-ffmpeg`, then copy the binary onto `PATH`) extracts
  frames, which can then be read as images. Transcripts with timestamps via
  `pip install youtube-transcript-api`. Useful because the written docs say
  *what* a control does but not the order people work in — which is exactly the
  gap that caused the guide-tool mistake.
- Video cannot be watched directly; screenshots and extracted frames only.
- PyPI is reachable. Google Maven is not.

### Commit conventions
Commit messages end with:

```
Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01UM9REU9nNxsT7PFe6q2VjT
```

Never put a model identifier in commit messages, PR titles or bodies, code
comments, or any other artifact pushed to the repository.

---

## 6. Session commits (oldest first)

```
572ee49  Keep a brush you mixed, and mix one by dragging
511721e  The menu comes to your hand
df33559  What the mark is made of, not just how it was made
b79532f  Render mode where Feather keeps it, and one cube with three faces
28db5cf  The arithmetic the view-based joystick needs
52120f5  Home, as Feather lays it out
e7fc9c8  The joystick that works in the picture
19193c7  Groups you can pick, merge and reorder
cd667b5  The point everything turns around, made visible
6d54bce  The panels added at the end of the class were null when it was built
a4cfcd0  Two things I took away without meaning to
8f35d72  Open on Home, always — and the sidebar you actually asked for
```

**Current state:** all twelve green in CI. The last three are unverified on a
device — the launch fix, the two regression fixes, and open-on-Home are the
things to check first.
