package art.plume.core

/**
 * WHAT A CURVE IS MADE OF.
 *
 * FACT: "All curves drawn with Feather are 3D curves that respond to light.
 * Use materials to add depth and vibrancy to your work", and there are four:
 *
 *  - Shadeless — "a basic material that does not respond to lighting or cast
 *    shadows. Patterns can be applied."
 *  - Shaded — "responds to lighting and casts shadows. Patterns can be
 *    applied."
 *  - Glow — "adding a glowing effect to the curves. Does not respond to
 *    lighting, does not cast shadows, and patterns cannot be applied."
 *  - Cutout — "responds to the background, making curves appear as the
 *    background color or image."
 *
 * Until now this build had those as properties of the BRUSH: the glow brush
 * glowed and nothing else could, and a curve drawn with the wrong one had to
 * be redrawn. Feather's are per curve and settable afterwards, which is the
 * difference between a brush and a material — one is how you make the mark,
 * the other is what the mark is.
 *
 * They are strings rather than an enum because that is what the document
 * writes, and a file from a later version naming a material this one has never
 * heard of should fall back rather than fail to open.
 */
object Material {
    const val SHADED = "shaded"
    const val SHADELESS = "shadeless"
    const val GLOW = "glow"
    const val CUTOUT = "cutout"

    /** In the order the panel offers them. */
    val ALL = listOf(SHADED, SHADELESS, GLOW, CUTOUT)

    /** A name from a file, or [SHADED] when it is one this build does not know. */
    fun sanitize(name: String?): String? =
        if (name == null) null else if (name in ALL) name else SHADED

    /**
     * What a brush draws with before anyone chooses.
     *
     * The glow brush's whole identity is that it glows, so it brings its
     * material with it; everything else answers the light.
     */
    fun forBrush(cfg: Brush): String = if (cfg.glow) GLOW else SHADED

    /** FACT: "Patterns cannot be applied when using Glow or Cutout materials." */
    fun takesPattern(material: String): Boolean =
        material == SHADED || material == SHADELESS

    /** FACT: Glow and Cutout do not "cast shadows"; nor does Shadeless. */
    fun castsShadow(material: String): Boolean = material == SHADED
}

/**
 * THE FIVE PATTERNS.
 *
 * FACT: "Patterns are procedurally generated textures… As of version 1.0,
 * Feather supports five patterns: from left to right, 'Dot', 'Line', 'Cross',
 * 'Terrazzo', and 'Stippled Dot'", with sliders for "intensity, angle, and
 * contrast".
 *
 * Procedural is the operative word, and the reason these are five small
 * numbers on a curve rather than an image anywhere: a pattern generated in the
 * shader is resolution-free, costs nothing to store, and survives a curve being
 * scaled, bent or erased in half. The one design decision that is ours is
 * WHERE the pattern lives — it is anchored to the world in millimetres, like
 * the pencil grain, so two curves crossing agree about where the dots are and
 * the print reads as being on the paper rather than on each mark separately.
 */
object Pattern {
    const val NONE = 0
    const val DOT = 1
    const val LINE = 2
    const val CROSS = 3
    const val TERRAZZO = 4
    const val STIPPLE = 5

    val ALL = listOf(DOT, LINE, CROSS, TERRAZZO, STIPPLE)

    /** A number from a file, clamped to something this build can draw. */
    fun sanitize(v: Int): Int = if (v in NONE..STIPPLE) v else NONE
}
