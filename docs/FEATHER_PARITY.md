# Feather parity — measured against the real documentation

The premise of this project is a faithful port, so it is worth writing down
what "faithful" is measured against.

Everything below is quoted from `support.feather.art/docs`, read directly.
An earlier version of this file was assembled from search snippets because
the docs were blocked by this environment's network policy; that policy has
since been widened, and reading the real pages corrected two things this port
had already shipped. Snippets are not a substitute for the page.

## The two corrections

**The mirror remembers its axes.** "The previously used axes are saved, so you
can quickly reactivate the mirror with a single tap, making it very
convenient." Tapping the icon off used to clear them here, which turns a
one-tap reactivation into choosing your axes again — and anyone working
symmetrically toggles the mirror constantly to check the half they are
drawing. Fixed.

**A live plane is drawn in its own colour.** "When the mirror is activated, a
brief interface will appear, showing the global axis in the respective color."
The fold was one grey for every plane, which says how many are on and not
WHICH. Fixed: red X, green Y, blue Z, the same colours the chips use.

## A deliberate divergence, and it is the one the device report asked for

FACT, from the 3D Joystick page: "In perfect views like the front view, side
view, or top view, you can only use two of the three cones on the 3D Joystick.
The shape of the arcs also changes based on the view. **This is a natural
occurrence due to the view direction, so do not be confused.**"

So the greyed-out axis is Feather's documented behaviour, and this build now
departs from it: an axis end-on to the camera takes the vertical drag the
depth strip uses instead of being disabled. That was a device report — "the
axis on the joystick panel greys out certain axes and doesn't let you make
direct changes from that axis" — and the fix stands because it was asked for,
not because Feather does it.

Reverting to strict parity is one line: `usable` in `pushTransform` goes back
to asking `Transform.axisOnScreen(...) != null` per axis.

## The largest gap: there are TWO joysticks

Feather documents a **2D Joystick** and a **3D Joystick** as separate tools.
This build has one that mixes them.

**2D Joystick** — "moves, rotates, and scales objects based on the view
direction. It's very intuitive because it transforms as it appears."

- centre circle: "Tap and hold… to turn it black. Drag to move… The stick can
  move outside the joystick layout. When you release, the stick returns to its
  original position."
- scale handles "above and to the left of the stick… height scaling, width
  scaling, or free scaling. The scaling reference point is the center of the
  screen, marked with a crosshair."
- rotate handle "on the right of the stick".
- **a LOCK**, which this build has nothing like: locked, the stick moves "only
  up, down, left, and right", scaling becomes a single handle and uniform, and
  rotation snaps "in 15-degree increments… useful for rotating to specific
  angles like 90 or 180 degrees".

**3D Joystick** — "moves or rotates objects based on the global XYZ axes".

- "Tap and drag the red, green, or blue **cone**… to move along the respective
  axis."
- "Tap and spin the red, green, or blue **arc**… to rotate along the respective
  axis. The rotation center point is the invisible center of the selected
  object."
- "Tap and drag the **center sphere**… for free rotation… like a trackball."

Anvil's pad has three arcs that do move/rotate/scale depending on a mode
switch, plus a depth strip. Closest to the 3D joystick, with no 2D joystick
and no lock at all.

## Materials are per curve, chosen in the Colour Panel

"All curves drawn with Feather are 3D curves that respond to light." Four
materials, and this build has none of them as a per-curve property — shading
is one environment switch over everything, so a sketch cannot mix a shaded
form with flat line work.

| Material | Documented behaviour |
|---|---|
| Shadeless | "does not respond to lighting or cast shadows. Patterns can be applied." |
| Shaded | "Responds to lighting and casts shadows. Patterns can be applied." |
| Glow | "Responds to the glow area… Does not respond to lighting, does not cast shadows, and patterns cannot be applied. You can adjust its intensity." |
| Cutout | "Responds to the background, making curves appear as the background color or image." |

**Patterns**, which are entirely absent here: "procedurally generated
textures", five of them as of 1.0 — "Dot, Line, Cross, Terrazzo, and Stippled
Dot" — applied from the Colour Panel to Shaded or Shadeless curves only, with
sliders for "intensity, angle, and contrast".

Render Mode is in the **Stage Panel**: "Materials are displayed accurately
only in rendering mode."

## Confirmed as already matching

- **Draw 3D Guide**: "generated perpendicular to your viewing angle and varies
  based on your Field of View (FOV)"; "3D Guides can only be drawn with a pen.
  If you don't have a stylus, enable Finger-Pen."
- **Bend**: "Bend an existing 3D Guide to create more organic shapes,
  **revolve**, or make a **tube**… the 3D Guide will bend along the drawn
  line. The bending starts from the orange line… You can repeat the Bend 3D
  Guide process multiple times." Revolve and tube are both closed paths, which
  is why the seam weld matters.
- **Draw Shape**: straight lines and circles corrected from the drawn curve,
  hold to adjust length and endpoint, hold to adjust curvature, press-hold-drag
  for a circle, and "This also includes the ability to Bend 3D Guides."
- **Stable Strokes**: a slider, with a preview you can draw in.

## Confirmed from the navigation and selection pages

Read after the first pass, and two of them landed as changes:

- **A selection is GREEN.** "Selected curves are highlighted in green" and
  "Selected resources are highlighted in green". Curves were tinted blue here
  and guides green, so a curve and the guide under it answered the same
  gesture in two different colours. Both green now.
- **Three-finger swipe is the lens, and the guess about its direction was
  right.** "swipe up to increase the field of view (FOV) or swipe down to
  reduce it. The FOV can be adjusted from 10mm to 500mm." Naming the range in
  millimetres is what settles which way "increase" runs — the number is a
  focal length, so up is a longer lens, which is what this build does. The
  limits already match exactly.
- Navigation otherwise matches: one finger rotates, one-finger double tap
  snaps to "Left, Right, Front, Back, Top and Bottom", pinch zooms, two
  fingers pan, three-finger double tap swaps perspective and orthographic.
- **Isolate by 3D Guide** is documented as a select AND erase behaviour:
  "Cover the curves you don't want to select with a 3D Guide. The Select tool
  will not select curves within the guide… It also works for erasing."
- **Select is a toggle to Deselect**: "Tap Select in the tool menu again to
  switch to Deselect, then drag with your pen to deselect", and "Tap Draw or
  Erase to escape" cancels a selection outright.

Two navigation details are documented and not implemented here:

- **The orbit point is a thing you can see and pin.** "Tap and hold on a curve
  or grid to pin the orbit point. Tap and hold in empty space to unpin… If the
  orbit point is not pinned, tap and hold in empty space to reset the view",
  with options to "Pin orbit point" and "Show orbit point". This build pins on
  a hold and resets on empty space, but never draws the point.
- **The orbit point is the depth-of-field focus**: "The orbit point also
  functions as a focus point for Depth of Field (DOF)."

## Ported from the group and duplicate pages

Read and implemented in the same pass:

- **A duplicate lands on the original.** "The duplicated curves are in the same
  position as the original, so be careful not to confuse them." This nudged the
  copy 24px across the glass so it read as a copy — kinder on the eye, and
  wrong: a duplicate you place yourself has a known starting point.
- **There are two symmetric duplicates.** "Symmetrically by View" reflects
  "based on the view direction. If the sketch is skewed to the right, it will
  be duplicated to the left". "Symmetrically by Mirror can only be used when
  the mirror is on… If multiple axes are active, multiple curves will be
  duplicated at once." This build had one, hardwired to X. The button now uses
  the mirror when it is on and the view when it is not.
- **A new group goes directly above the active one**, not at the top of the
  list, which is where this put it.
- **A hidden group refuses ink.** "When a group is hidden, you cannot add new
  curves to it even if it is the active group." Refused at the start of the
  stroke rather than at the end.
- **Group isolation**, which did not exist here: "Tap and hold the eyeball icon
  on the far right to isolate the group. When a group is isolated, only the
  curves within that group are visible… Tap and hold the eyeball icon again to
  exit isolation." Held as a separate state rather than by hiding everything
  else, so backing out does not leave a dozen groups switched off.

Still missing from the same two pages, and bigger: **groups can be
multi-selected** ("You can select multiple groups by tapping another group
while one is already selected"), and selected groups can be **rearranged and
merged**, not only deleted and duplicated.

## Still unread

`/docs/interfaceandgestures/applepencil` and `squeezemenu`,
`/docs/interfaceandgestures/keyboardshortcuts`, `/docs/stagepanel/*`,
`/docs/selection/duplicate`, `/docs/liquify/*`, `/docs/extensions/*`
(clipboard, AR, sequence, export, publish) and `/docs/home/*`. The pages are
reachable; nobody has been through them line by line yet.
