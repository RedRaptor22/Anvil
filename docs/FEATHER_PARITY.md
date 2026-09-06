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

**Built.** Anvil now has both. The 2D joystick is the default, as it is in
Feather, with all five handles where the documentation puts them — height
above the stick, width to the left, free scale between them, the turn on the
right — and the Lock, which restricts the stick to four directions, collapses
the three scale handles to one uniform one, and makes the rotate handle jump
in whole fifteens rather than easing towards them.

Two things are still ours rather than Feather's, and deliberately.

The 3D joystick keeps a **Move / Turn / Size mode switch** where Feather has
cones, arcs and a centre sphere all live at once. Feather's arrangement is
better and needs a real gizmo drawn in the scene rather than a pad beside it;
the pad's centre in Turn mode already IS the trackball ("free rotation… like a
trackball"), so the substance is there and the shape is not.

And an axis pointing at the camera keeps a usable direction here instead of
being greyed out — recorded below under the joystick's end-on axes.

## Materials are per curve, chosen in the Colour Panel

**Built.** "All curves drawn with Feather are 3D curves that respond to
light." Four materials, and this build had none of them as a per-curve
property — shading was one environment switch over everything, so a sketch
could not mix a shaded form with flat line work. They are a property of the
curve now, on a third page of the colour card, and a curve nobody has given
one to still follows its brush.

| Material | Documented behaviour |
|---|---|
| Shadeless | "does not respond to lighting or cast shadows. Patterns can be applied." |
| Shaded | "Responds to lighting and casts shadows. Patterns can be applied." |
| Glow | "Responds to the glow area… Does not respond to lighting, does not cast shadows, and patterns cannot be applied. You can adjust its intensity." |
| Cutout | "Responds to the background, making curves appear as the background color or image." |

**Patterns**, also built: "procedurally generated textures", five of them as
of 1.0 — "Dot, Line, Cross, Terrazzo, and Stippled Dot" — applied from the
Colour Panel to Shaded or Shadeless curves only, with sliders for "intensity,
angle, and contrast". The one decision that is ours is where a pattern LIVES:
there are no UVs on a swept tube, so it is projected triplanar by the dominant
axis of the normal and measured in world millimetres, the same anchor as the
pencil grain. Two curves crossing therefore agree about where the dots are.

Render Mode is in the **Stage Panel**: "Materials are displayed accurately
only in rendering mode." It is now the button at the top of that panel, and it
moves both halves of the rendered look — lighting and the post pass — which
this build had as two separate switches buried in the Scene tab.

## Home, built from the Home pages

Sidebar (Recents, Folders, Settings, Help), breadcrumb, refresh, new folder,
sort by modified / created / name, a grid of tiles with four-up folder covers,
the + at the bottom right, and the selection bar: deselect, rename, duplicate,
export, lighten, delete. Tap and hold picks; hold something already picked to
drag it into a folder, or onto a crumb to bring it back out.

Two divergences worth naming. **Feather Gallery** (item 4) is a service, not a
feature, so there is nothing to port; the slot is Help instead. And **export**
writes this app's own document rather than a `.feather` file, since a format
nothing here can read back would be a button that only loses work.

**Lighten** is real: Douglas-Peucker with the tolerance measured against the
brush rather than in absolute units, so a tenth of a millimetre off the line
survives on a hairline and is dropped under a 30mm marker.

## One guide at a time, read two ways

FACT: "If any resource is active, you cannot draw or loft a new 3D Guide."

Read once as a rule about the TOOL — refuse to switch to Guide while one is
active — and that was wrong, expensively. With a guide up, which is the normal
state once you have drawn one, the Guide tool could not be selected at all,
and hold-to-shape on a guide profile went with it.

It is a rule about CREATING, and this build now answers it by making the
replacement survivable rather than by refusing: a new guide closes the one it
replaces, which puts it in the quick menu's Recall, and the action pill says
so. Refusing is worse than replacing once replacing is reversible, because the
refusal blocks the tool and the replacement blocks nothing.

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

## Ported from the liquify page

FACT: "Comparing before and after changes is crucial to understanding the
overall impact. Always compare before applying liquify." The page gives
liquify its own bottom menu, and this build had only the Apply half of it:

- **Undo All** — "revert to the state before liquify", now one undoable step
  back to where the session started.
- **Compare** — "Tap and hold 'Compare'… to view the curves before liquify".
  Held, not tapped: the current shape is put aside on the way down and
  restored on the way up, so a look costs nothing and changes nothing.
- Apply ends the SESSION rather than committing anything, because every drag
  is already in the history — "to undo step by step, use the history panel".

The three liquify modes were already right: Push "distorts naturally, as if
pushing or pulling with a finger", Pinch "sharply and precisely", Comb
"gently smooths and aligns… Ideal for straightening wavy curves".

## A second deliberate divergence: the loft averages a bundle

The Loft page says only "Connect curves in sequence" and "Select the curves
you want to connect in sequence" — nothing about merging anything. This build
now reads curves that lie within an eighth of their own length of each other
as ONE section and lofts from their average, because a bundle of overdrawn
strokes otherwise spends the whole surface crossing four millimetres of
bundle. That was asked for from the device, and it is a departure from
Feather, not an implementation of it.

Both divergences so far are the same shape: a device report asked for
something Feather does differently, and the request won. They are listed here
so the next person does not "fix" them back.

## Confirmed matching, from the draw, erase and loft pages

- **The eraser works on POINTS**: "The Eraser removes points from the center
  of the curve, not the surrounding geometry. If you've drawn with a broad
  brush and the Eraser doesn't touch the center, it may appear as though it's
  not erasing." That is exactly this build's behaviour, including the
  surprise.
- **Vacuum is the eraser's partner**: "Tap the Eraser again in the tool menu
  to switch to the Vacuum tool… erase all curves it touches."
- **Isolate by 3D Guide** covers erase as well as select, stated on both pages.
- **Loft**: two or more curves preview immediately, the left slider is
  tension — "Slide up for smoother curves and slide down for sharper bends" —
  and Done or Cancel finishes it.
- **Drawing on an imported image or model**: "The image acts like a flat 3D
  Guide, and you cannot draw outside its boundaries"; "The model acts like a
  curved 3D Guide."

## The bend, from six screenshots

Three separate faults, each reproduced in a probe before it was touched.

**The section turned over when you bent towards yourself.** The transport was
seeded by carrying the drawn right round by the SHORTEST rotation from the
extrusion axis to the new tangent. A path leaving the anchor back towards the
camera is close to the reverse of that axis, so the shortest rotation is close
to a half turn — and a half turn rolls the section over. A guide whose outer
wall stood on the left came back with the wall on the right. Measured over
twenty-four directions: bending across the screen kept the wall left, bending
towards the viewer put it right. The seed is now the drawn right PROJECTED
onto the plane the new tangent defines — the frame closest to the one the
profile was drawn in, out of all the frames perpendicular to the path — so the
section tips as the path tips and never turns over.

**A ring the hand left open was not welded.** Closure needed the ends within a
twentieth of the path's length, and a hand lifts the pen further out than
that; the screenshot's ring had a visible notch where the two ends passed. The
tolerance is most of a fifth now, and the turning test is what still keeps a
hairpin from being joined across its fold.

**Turns tighter than the profile folded the surface inside out.** Where a path
turns inside the profile's own reach, the inner edge of the section crosses
the centre of the turn and comes out the far side — on screen, a wedge
converging to a point that nothing in the drawing put there. A hand draws
bends far tighter than it means to, especially where the pen slows at the ends
of a stroke. Such turns are eased open until they clear the reach; a bend the
profile fits round is left exactly where it was drawn, which is asserted to
the last decimal place.

**The bend hinged on a point nothing marks.** A.6 says "the bending starts
from the ORANGE LINE, which is the starting point of the 3D Guide", and the
documentation's picture shows that line running the length of the surface from
the dot labelled "Starting point" — the rail traced by the FIRST point of the
profile you drew. It was read as the sweep's `anchor`, which is the profile's
CENTROID: half a profile away, out in the middle of the surface, marking
nothing and drawn nowhere. So a bend slid the guide sideways as it bent — on a
profile one unit long, a stroke begun exactly on the orange line moved the
surface's starting point 0.75 of a unit off it.

The centroid is right for BUILDING a sweep, where it keeps the surface centred
on the stroke you drew, and that is left alone. It is wrong for bending, where
the documentation names the edge. `bend` now re-hangs the profile off its own
first point, so the drawn line becomes the new orange line rather than a
centreline through the middle of the surface. Being re-hung also leaves the
next bend on the same footing as this one, which is what "you can repeat the
Bend 3D Guide process multiple times" needs.

**The orange line is not drawn.** `Guide.anchorRow` is computed on every
rebuild and read by nothing: Anvil has never rendered the line the
documentation puts on the surface and names twice. The bend now works from it
correctly, but you cannot see where a bend will start. Not done, and a real
divergence.

## Known and not done

- **The eyedropper only samples the active group.** "The curve you want to
  sample from must be in the currently Active Group." This build samples any
  visible curve. Left alone deliberately: it is a restriction, not a feature,
  and nobody has asked for it — but it is a divergence and belongs on this
  list.

## Still unread

`/docs/interfaceandgestures/applepencil` and `squeezemenu`,
`/docs/interfaceandgestures/keyboardshortcuts`, `/docs/stagepanel/*`,
`/docs/selection/duplicate`, `/docs/liquify/*`, `/docs/extensions/*`
(clipboard, AR, sequence, export, publish) and `/docs/home/*`. The pages are
reachable; nobody has been through them line by line yet.
