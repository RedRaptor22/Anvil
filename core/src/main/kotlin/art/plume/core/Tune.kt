package art.plume.core

/**
 * The tuning table, ported one-for-one from `P.TUNE` in `js/core.js`.
 *
 * These are kept together, and kept named, for the same reason the web build
 * does: a number that appears once inside a function is a number nobody can
 * argue with later. The provenance marks carry over unchanged — FACT is
 * documented Feather behaviour, GUESS is a choice we made and should be
 * willing to revisit.
 *
 * Anything here that differs between the two builds is a parity bug, so this
 * file is the first place to look when a sketch measures differently on a
 * phone than it does in a browser.
 */
object Tune {
    /** FACT: Feather's lens runs 10 mm - 500 mm. */
    const val FOCAL_MIN = 10.0
    const val FOCAL_MAX = 500.0

    /**
     * GUESS: the default FOV is unpublished. The spec infers ~100 mm from the
     * grid's 100 m focal reference, but that reference is a distance, not a
     * lens — 50 mm frames a 1 m sketch without flattening it.
     */
    const val FOCAL_DEFAULT = 50.0

    /** 35 mm-format sensor height, for the focal -> fov conversion. */
    const val SENSOR_HEIGHT_MM = 24.0

    /** GUESS: ~4 m back frames a sketch a metre or two across. */
    const val RADIUS_DEFAULT = 4.0

    /** GUESS: the zoom clamps are unpublished. */
    const val RADIUS_MIN = 0.25
    const val RADIUS_MAX = 400.0

    /** The polar angle is held off both poles, where the up vector degenerates. */
    const val PHI_EPS = 0.0025

    /** GUESS: undo depth is unpublished. */
    const val UNDO_DEPTH = 200

    /**
     * GUESS: ~400k retained points (~20 MB of point records) before the oldest
     * steps are dropped, so a long session cannot grow history without bound.
     */
    const val UNDO_POINT_BUDGET = 400_000

    /** FACT: the brush panel runs 1 mm - 300 mm. */
    const val BRUSH_MIN_MM = 1.0
    const val BRUSH_MAX_MM = 300.0

    /** GUESS: orbit sensitivity, radians per pixel. */
    const val ORBIT_PER_PX = 0.0062

    /** GUESS: release-momentum decay per frame, tuned by feel. */
    const val SPIN_DECAY = 0.92
    const val SPIN_STOP = 1e-5

    /** Screen-space resample distance, in pixels. */
    const val MIN_PX = 2.0

    /** FACT (C.2): Stable Stroke is a stabiliser on the input, adjustable. */
    const val STABLE_DEFAULT = 0.45
    const val STABLE_MAX = 0.95

    // ---- guides ---------------------------------------------------------

    /** GUESS: extrusion half-depth = 1.5x the profile's extent. */
    const val GUIDE_DEPTH_FACTOR = 1.5

    /** GUESS: world units. */
    const val GUIDE_DEPTH_MIN = 0.6
    const val GUIDE_DEPTH_MAX = 40.0

    /** GUESS: ...but at least this fraction of the orbit radius. */
    const val GUIDE_DEPTH_OF_VIEW = 0.35

    /**
     * GUESS: the fraction of the depth extruded TOWARD the camera, so the
     * orange starting edge sits at one side of the surface, as documented.
     */
    const val GUIDE_DEPTH_FRONT = 0.12

    /** GUESS: a section line every 250 mm. */
    const val GUIDE_GRID_STEP = 0.25

    /** FACT: a guide "cannot be made completely opaque". */
    const val GUIDE_OPACITY_MAX = 0.92
    const val GUIDE_OPACITY_INIT = 0.42

    /** Profile resampling for the surface mesh, and the sweep path. */
    const val GUIDE_PROFILE_SEG = 96
    const val GUIDE_PATH_SEG = 64

    /** One grid unit is 1000 mm (FACT); the helper spans 40 of them. */
    const val GRID_EXTENT = 40.0
    const val GRID_DIVISIONS = 40
    const val AXIS_LENGTH = 20.0
}
