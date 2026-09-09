package art.plume.core

import kotlin.math.hypot

/**
 * THE NAVIGATION GLOBE — six axis balls you can aim the camera with.
 *
 * Blender's gizmo, which is the one people mean when they ask for this: a
 * small sphere in the corner carrying a ball on each end of each world axis.
 * Drag it and the view orbits; tap a ball and the camera goes and looks down
 * that axis exactly. The orbit gesture already exists on the canvas — what the
 * globe adds is the PRECISION, because "straight down +Y" is a thing you can
 * hit with one tap and cannot reliably reach by dragging.
 *
 * The six balls are the six views the camera already knows: this returns
 * [Camera.OrthoView] presets rather than angles of its own, so a ball and the
 * Front/Top/Right the rest of the app talks about cannot drift apart. The
 * balls are placed by PROJECTING the world axes through the camera's own
 * basis, passed in rather than recomputed here — the view matrix is the one
 * authority on where the camera is pointing, and a second derivation of it
 * would be a second thing to keep in step.
 *
 * Everything is in gizmo space: x and y run -1..1 across the globe with y
 * already pointing DOWN the screen, so a caller multiplies by a radius in
 * pixels and draws. Depth runs 1 at the viewer to -1 away from them.
 */
object NavGizmo {

    const val AXIS_X = 0
    const val AXIS_Y = 1
    const val AXIS_Z = 2

    class Ball(
        val axis: Int,
        val positive: Boolean,
        val x: Double,
        val y: Double,
        val depth: Double,
    ) {
        /** The view the camera takes when this ball is tapped. */
        val view: Camera.OrthoView get() = VIEWS[axis * 2 + if (positive) 0 else 1]

        /** X, Y or Z — what the ball is labelled with. */
        val label: String get() = when (axis) {
            AXIS_X -> "X"
            AXIS_Y -> "Y"
            else -> "Z"
        }

        /** In front of the globe's centre, so it is drawn solid and on top. */
        val inFront: Boolean get() = depth >= 0.0
    }

    /**
     * The camera's own presets, in ball order: +X, -X, +Y, -Y, +Z, -Z.
     *
     * Looked up by NAME rather than by position, because the order of
     * [Camera.ORTHO_VIEWS] is the order a menu lists them in and has no reason
     * to stay the order an axis would put them in.
     */
    private val VIEWS: List<Camera.OrthoView> = listOf(
        "Right", "Left", "Top", "Bottom", "Front", "Back",
    ).map { name -> Camera.ORTHO_VIEWS.first { it.name == name } }

    /**
     * Where the six balls sit, FARTHEST FIRST.
     *
     * Sorted so a caller can draw straight down the list and have the near
     * balls land on top of the far ones without thinking about it — the same
     * reason the renderer draws the active guide last.
     */
    fun balls(right: Vec3, up: Vec3, back: Vec3): List<Ball> {
        val out = ArrayList<Ball>(6)
        for (axis in 0..2) {
            for (positive in listOf(true, false)) {
                val s = if (positive) 1.0 else -1.0
                val ax = if (axis == AXIS_X) s else 0.0
                val ay = if (axis == AXIS_Y) s else 0.0
                val az = if (axis == AXIS_Z) s else 0.0
                out.add(
                    Ball(
                        axis, positive,
                        x = ax * right.x + ay * right.y + az * right.z,
                        /* screen y runs DOWN, and the camera's up runs up */
                        y = -(ax * up.x + ay * up.y + az * up.z),
                        depth = ax * back.x + ay * back.y + az * back.z,
                    ),
                )
            }
        }
        out.sortBy { it.depth }
        return out
    }

    /**
     * The ball under ([x], [y]) in gizmo space, or none.
     *
     * Searched FRONT FIRST, which is the opposite of the drawing order: where
     * two balls overlap on screen the near one is the one you can see, so it
     * is the one you meant. An axis pointing almost straight at the camera
     * puts its two balls on top of each other, and without this the far one —
     * the one hidden behind the globe — would take the tap half the time.
     */
    fun hit(balls: List<Ball>, x: Double, y: Double, r: Double): Ball? {
        for (i in balls.indices.reversed()) {
            val b = balls[i]
            if (hypot(x - b.x, y - b.y) <= r) return b
        }
        return null
    }
}
