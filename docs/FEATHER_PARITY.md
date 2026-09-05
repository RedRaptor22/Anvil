# Feather parity — what is confirmed, what is missing, what is guessed

This project's premise is a faithful port, so it is worth writing down what
"faithful" is currently measured against, and how well.

## How this was gathered, and the size of the hole in it

Feather's own documentation (`support.feather.art`), the SIGGRAPH '23 paper
(`dl.acm.org`, "Feather: 3D sketchbook light as a feather", Kim, Hong and
Yang) and a PDF mirror of it are **all blocked by this environment's network
egress policy**. Everything below came from web-search snippets that quote
those pages, which is a real limitation: snippets carry a documented sentence
but not the page around it, so the absence of a feature here is weak evidence
that Feather lacks it.

Anything sourced only from a snippet is marked FACT with its wording; anything
reasoned from behaviour is marked INFERENCE, and the code uses the same
convention.

## Confirmed, and implemented

| Feature | Feather's documented behaviour | Anvil |
|---|---|---|
| Draw 3D Guide | a stroke becomes a sketching surface; strokes from other views project onto it | yes |
| Bend 3D Guide | "the 3D Guide will bend along the drawn line", "bending starts from the orange line", repeatable | yes — including the closed case ("the side of a pot, then bending it into a cylinder") |
| Loft | connects several curves; a slider adjusts how much it bends, "up for smoother curves and down for sharper" | yes, with the tension slider |
| Mirror | three axes below the icon, "red for the X-axis, green for the Y-axis, and blue for the Z-axis"; "you can activate multiple axes at the same time"; mirrors about the global axes | yes, and reflections stay linked to what they reflect |
| Joystick | "a central crosshair and a translation stick in the corner surrounded by a rotation wheel and scaling handles" | yes |
| Liquify, duplicate, delete | listed under selection/transform | yes |
| Finger-pen | "sketch, paint, and edit using just your fingers" | yes |
| Materials and render mode | "select the 'Shade' material… turn on 'Render Mode'" | partly — see below |

## Gaps worth ranking

Ordered by how much of the app they touch, not by how much work they are.

1. **Material is chosen in the colour panel, per curve.** FACT, from Feather's
   own post: "Tap the color panel and select the 'Shade' material. Then, turn
   on 'Render Mode' in the group panel." Anvil has shading as an ENVIRONMENT
   switch that applies to everything, so a sketch cannot mix a shaded form
   with flat line work — which is the normal way anyone uses it. This is the
   largest structural difference found.
2. **Render mode lives in the group panel.** Anvil puts it in the stage panel.
   Small on its own, but it suggests Feather scopes render state to groups
   rather than to the document.
3. **Bake.** Named in Feather's own copy — "Bend it, paint it, and bake it" —
   with no snippet explaining what it does. Most likely committing a guide's
   painted strokes into geometry. Unknown, and worth finding out before
   guessing at it.
4. **Draw Shape assistance.** Feather documents an assistance page for it.
   Anvil has hold-to-shape on the pen path, which may or may not be the same
   feature.
5. **The fold indicator is one colour for all three mirror planes.** Feather
   colours the axes red/green/blue and shows "the global axis in the
   respective color" when a plane is switched on. Anvil's chips are coloured;
   the plane drawn in the scene is not.
6. **The selection overlay.** "an overlay interface that appears when selecting
   curves or objects, and a bottom context menu" — Anvil has a selection bar
   and a context bar, and whether they carry the same actions is unchecked.

## What would close the hole

One person with the app in front of them, or an unblocked route to
`support.feather.art`, would settle points 3, 4 and 6 in minutes. Until then
they stay marked unknown rather than being implemented from a guess — this
project has already spent three rounds of device reports on faults that came
from confidently misreading the reference.
