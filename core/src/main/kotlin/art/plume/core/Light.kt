package art.plume.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * One key light and a soft ambient floor.
 *
 * Ported from `P.LIGHT` in js/camera.js, whose note explains why it is one key
 * rather than a rig: Feather's Lighting panel gives a direction you slide, a
 * colour and an intensity, and "a sketchbook wants a predictable read rather
 * than a studio rig".
 *
 * The defaults are not arbitrary. They reproduce the light that was hardcoded
 * in the stroke shader before there was anything to adjust — direction
 * (0.32, 0.62, 0.72), which is altitude 38.2 degrees at azimuth 24, and an
 * ambient of 0.66 against a half-lambert term, which is exactly the
 * `0.66 + 0.34*(d*0.5+0.5)` the shader used to hold. So a sketch drawn before
 * the panel existed still reads the way it did.
 */
class Light {
    /** Radians. 0 is +Z, turning towards +X. */
    var az = 0.4185

    /** Radians above the horizon. */
    var alt = 0.6664

    var color = Rgba(1.0, 1.0, 1.0)
    var intensity = 1.0

    /** How bright the unlit side stays. */
    var ambient = 0.66

    var toon = false
    var toonSteps = 4

    /**
     * The unit vector the light travels FROM — the direction a surface has to
     * face to be fully lit, which is what the shader dots the normal against.
     */
    fun direction(out: Vec3 = Vec3()): Vec3 {
        val ca = cos(alt)
        return out.set(ca * sin(az), sin(alt), ca * cos(az)).normalize()
    }

    /** Clamped the way `applyLight` clamps before it reaches the uniforms. */
    fun ambientClamped(): Double = clamp(ambient, 0.0, 1.0)

    /**
     * At least two, or the band expression divides by zero: the shader does
     * `floor(hl * steps) / (steps - 1)`.
     */
    fun toonStepsClamped(): Int = max(2, Math.round(toonSteps.toDouble()).toInt())

    fun copyFrom(o: Light) {
        az = o.az; alt = o.alt; color = o.color; intensity = o.intensity
        ambient = o.ambient; toon = o.toon; toonSteps = o.toonSteps
    }
}

/**
 * The post pass's settings — depth of field, film grain and pixelation.
 *
 * FACT: Feather carries all three and "shows them accurately only in rendering
 * mode", which is why [DocumentEnv.render] gates them: a full-screen pass every
 * frame is a cost you should be asked for rather than charged.
 */
class Fx {
    var dofOn = false

    /** Low f is a wide aperture is a shallow focus, as on a real camera. */
    var fstop = 5.6

    var grainOn = false

    /** 0..100. */
    var grain = 35.0

    var pixelOn = false

    /** Block size in screen pixels. */
    var pixel = 4.0

    /**
     * What is in focus is what you are orbiting around: the pivot is the point
     * the whole camera model already treats as the subject, so there is no
     * separate focus control to get out of step with the view.
     */
    fun focusDistance(camera: Camera): Double =
        max((camera.eye - camera.pivot).length(), 1e-3)

    /**
     * How far either side of the focus stays sharp. f/22 keeps nearly
     * everything, f/1.4 almost nothing — the same way round as an aperture,
     * and proportional to the focus distance so the effect does not change
     * character as you zoom.
     */
    fun focusRange(focus: Double): Double = max(focus * (fstop / 22.0), 1e-3)

    /** 0..0.22 of a unit of colour, which is where grain stops being texture. */
    fun grainAmount(): Double = if (grainOn) clamp(grain, 0.0, 100.0) / 100.0 * 0.22 else 0.0

    /** The number of blocks across a `w x h` buffer at this block size. */
    fun pixelGridX(w: Int, density: Double): Double = max(1.0, w / max(1.0, pixel * density))

    fun pixelGridY(h: Int, density: Double): Double = max(1.0, h / max(1.0, pixel * density))

    fun copyFrom(o: Fx) {
        dofOn = o.dofOn; fstop = o.fstop
        grainOn = o.grainOn; grain = o.grain
        pixelOn = o.pixelOn; pixel = o.pixel
    }
}

/** An axis-aligned box, and whether anything was ever put in it. */
class Bounds {
    var minX = 0.0; var minY = 0.0; var minZ = 0.0
    var maxX = 0.0; var maxY = 0.0; var maxZ = 0.0
    var empty = true
        private set

    fun clear(): Bounds { empty = true; return this }

    fun add(p: Vec3): Bounds {
        if (empty) {
            minX = p.x; minY = p.y; minZ = p.z
            maxX = p.x; maxY = p.y; maxZ = p.z
            empty = false
        } else {
            if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y
            if (p.z < minZ) minZ = p.z; if (p.z > maxZ) maxZ = p.z
        }
        return this
    }

    fun expand(by: Double): Bounds {
        if (empty) return this
        minX -= by; minY -= by; minZ -= by
        maxX += by; maxY += by; maxZ += by
        return this
    }

    fun centre(out: Vec3 = Vec3()): Vec3 =
        out.set((minX + maxX) * 0.5, (minY + maxY) * 0.5, (minZ + maxZ) * 0.5)

    /** Half the diagonal — the radius of the sphere the box fits inside. */
    fun radius(): Double {
        if (empty) return 0.0
        val dx = maxX - minX; val dy = maxY - minY; val dz = maxZ - minZ
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz) * 0.5
    }

    /** The longest side, which is what framing a view wants. */
    fun extent(): Double =
        if (empty) 0.0 else max(maxX - minX, max(maxY - minY, maxZ - minZ))

    companion object {
        /** Every point of every VISIBLE stroke. */
        fun of(sketch: Sketch): Bounds {
            val b = Bounds()
            for (s in sketch.strokes) {
                if (!sketch.visible(s)) continue
                for (sp in s.pts) b.add(sp.p)
            }
            return b
        }
    }
}

/**
 * Where to stand to cast the ground shadow, and how far it reaches.
 *
 * Ported from `P.updateGroundShadow`, and it is worth repeating the note there
 * on why this is NOT a shadow map: the depth-map comparison wants surfaces
 * thick and flat enough to bias against, and almost everything Plume draws is a
 * thin tube — "the bias that stops the acne is the bias that lifts the shadow
 * off the object it belongs to". What the ground needs is the simpler question,
 * is anything between this patch and the light, and the answer is the sketch's
 * SILHOUETTE seen from the light. No depth compare, no bias, nothing to tune.
 *
 * This half is the geometry, so it can be checked without a GL context: the
 * renderer only has to draw the strokes flat through [viewProj] and sample the
 * result on a quad at [planeX], [planeZ] of size [size].
 */
class ShadowFit {
    val eye = Vec3()
    val centre = Vec3()
    var half = 0.0
    var near = 0.0
    var far = 0.0
    val view = Mat4()
    val projection = Mat4()
    val viewProj = Mat4()

    val planeX: Double get() = centre.x
    val planeZ: Double get() = centre.z
    val size: Double get() = half * 2.0
}

object GroundShadow {

    /** Below this the sun is on the horizon and there is nothing sensible to cast. */
    const val MIN_ALT = 0.05

    /**
     * True when a shadow should be drawn at all. Kept here rather than in the
     * renderer so the rule is one place and testable.
     */
    fun active(env: DocumentEnv, light: Light, bounds: Bounds): Boolean =
        env.render && env.groundShadow && !bounds.empty && light.alt >= MIN_ALT

    /**
     * Fit the light's camera to the sketch.
     *
     * The ground a shadow can land on reaches out by however far the light
     * leans — a low sun throws a long one — so the half-extent grows as the
     * altitude falls, and is capped so a sun near the horizon cannot ask for a
     * target the size of the world.
     */
    fun fit(bounds: Bounds, light: Light, out: ShadowFit = ShadowFit()): ShadowFit {
        bounds.centre(out.centre)
        val radius = max(bounds.radius(), 0.05)
        val dir = light.direction()

        val reach = radius + abs(out.centre.y) / max(tan(light.alt), MIN_ALT)
        out.half = kotlin.math.min(radius + reach, radius * 40.0 + 1.0)

        out.near = 0.01
        out.far = out.half * 4.0 + radius * 4.0 + 2.0
        out.eye.set(
            out.centre.x + dir.x * (out.half * 2.0 + radius),
            out.centre.y + dir.y * (out.half * 2.0 + radius),
            out.centre.z + dir.z * (out.half * 2.0 + radius),
        )

        /*
         * The light can stand straight overhead, where the usual +Y up vector
         * is parallel to the view direction and lookAt degenerates. Leaning on
         * +Z there is what keeps a noon sun from producing a matrix of NaNs.
         */
        val up = if (abs(dir.y) > 0.999) Vec3(0.0, 0.0, 1.0) else Vec3(0.0, 1.0, 0.0)
        Mat4.lookAt(out.eye, out.centre, up, out.view)
        Mat4.orthographic(
            -out.half, out.half, -out.half, out.half, out.near, out.far, out.projection,
        )
        Mat4.multiply(out.projection, out.view, out.viewProj)
        return out
    }

    /**
     * What the fit depends on. The renderer re-runs the silhouette pass only
     * when this changes: orbiting the camera does not move a shadow cast by a
     * fixed light onto a fixed ground, so spinning the view costs nothing.
     */
    fun signature(bounds: Bounds, light: Light): String {
        fun q(v: Double) = (Math.round(v * 10000.0) / 10000.0).toString()
        if (bounds.empty) return "empty"
        return listOf(
            q(light.az), q(light.alt),
            q(bounds.minX), q(bounds.minY), q(bounds.minZ),
            q(bounds.maxX), q(bounds.maxY), q(bounds.maxZ),
        ).joinToString(",")
    }
}
