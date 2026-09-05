package art.plume.core

/**
 * A BRUSH YOU PUT DOWN AND PICK UP AGAIN.
 *
 * FACT: "Save your brush size, color, and shape to create your own presets.
 * Load them anytime to continue working quickly… The brush preset saves the
 * current brush type, color, size, and opacity."
 *
 * Four values and no name. A preset is recognised by its swatch — the colour
 * it is, at the size it is, in the shape it is — the way a jar of paint is
 * recognised on a shelf, and asking for a name before you can keep a colour
 * you just mixed would put a text field between the hand and the work.
 *
 * FACT: "Brush presets are saved per note", so they belong in the document
 * rather than in a setting. That is the right home for a second reason: the
 * palette a drawing was made with is part of the drawing, and a sketch opened
 * on another day should come back with the brushes it was made with rather
 * than whatever was used last.
 */
class BrushPreset(
    val brush: String,
    val color: Rgba,
    val sizeMM: Double,
    val opacity: Double,
) {
    /** Two presets are the same when every value is, so adding one twice is a no-op. */
    fun sameAs(other: BrushPreset): Boolean =
        brush == other.brush &&
            color == other.color &&
            kotlin.math.abs(sizeMM - other.sizeMM) < 1e-9 &&
            kotlin.math.abs(opacity - other.opacity) < 1e-9

    companion object {
        /** As many as the strip can be scrolled through without becoming a filing job. */
        const val LIMIT = 60
    }
}
