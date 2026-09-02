package art.plume.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * THE COMPLAINT THIS FILE EXISTS FOR: "the brushes don't stick to the surface
 * of the guide as they should but rather protrude outwards, especially the
 * wide brush."
 *
 * A blade brush is a sheet. Painted on a wall it should be a sheet ON the
 * wall, so every vertex of it sits within about its own thickness of the wall
 * — a couple of millimetres for `wide` — and none of them stands a whole
 * half-width out. Rolling the section by the stylus tilt azimuth, which is
 * what this build used to do, stands the sheet on edge at whatever angle the
 * hand happened to hold: on a nib 3.4 radii wide that is centimetres of
 * protrusion, which is exactly what came back from the device.
 *
 * So the number these tests assert is the distance from the wall, in
 * millimetres, and it is measured rather than eyeballed.
 */
class NibTest {

    /** a stroke across the x axis, painted on the y = 0 plane */
    private fun onFloor(brush: String, radius: Double, n: Int = 12): Stroke {
        val s = Stroke(brush = brush, baseRadius = radius)
        for (i in 0 until n) {
            s.pts.add(
                StrokePoint(
                    Vec3(i * 0.01, 0.0, 0.0),
                    pressure = 1.0,
                    nrm = Vec3(0.0, 1.0, 0.0),
                ),
            )
        }
        return s
    }

    /** how far the furthest vertex stands off the y = 0 plane, in millimetres */
    private fun standoffMM(m: MeshData): Double {
        var worst = 0.0
        for (i in 0 until m.vertexCount) {
            val d = abs(m.positions[i * 3 + 1].toDouble())
            if (d > worst) worst = d
        }
        return worst / MM
    }

    /**
     * What "lies on the surface" means for a RISING brush.
     *
     * `wide` is 3mm thick and stands on the guide rather than straddling it,
     * so the test is no longer "every vertex is near the plane" — it is that
     * nothing sinks BELOW the plane and nothing stands more than the brush's
     * own thickness above it. A blade tipped out of the surface fails both
     * halves; a blade lying on it passes both.
     */
    private fun assertRestsOn(m: MeshData, axis: Int, at: Double, thickMM: Double) {
        var below = 0.0
        var above = 0.0
        for (i in 0 until m.vertexCount) {
            val d = (m.positions[i * 3 + axis].toDouble() - at) / MM
            if (d < below) below = d
            if (d > above) above = d
        }
        assertTrue(below > -0.01, "it sank ${-below}mm into the surface")
        assertTrue(
            above <= thickMM + 0.01,
            "it stood ${above}mm proud of a surface it is ${thickMM}mm thick on",
        )
    }

    @Test
    fun `a wide blade lies on the surface it was painted on`() {
        val s = onFloor("wide", 7.0 * MM)
        Nib.freezeFrames(s)
        val m = StrokeGeometry.build(s)!!
        /* `wide` is 3mm thick and RISES, so it sits on the floor and stands
           3mm proud of it. The nib is 3.4 * 7mm = 23.8mm wide: tipped onto its
           edge the same mesh reaches 23.8mm, which is what this rules out. */
        assertRestsOn(m, axis = 1, at = 0.0, thickMM = 3.0)
    }

    @Test
    fun `a flat blade lies on the surface too`() {
        val s = onFloor("flat", 7.0 * MM)
        Nib.freezeFrames(s)
        val m = StrokeGeometry.build(s)!!
        // flat has no thickMM: its half-thickness is radius * wide * flat
        val halfThick = 7.0 * 3.40 * 0.04
        assertTrue(
            standoffMM(m) < halfThick * 1.05,
            "flat stood ${standoffMM(m)}mm off the floor, expected under $halfThick",
        )
    }

    @Test
    fun `the roll a stylus reports does not turn the nib`() {
        /* The regression itself. Two identical strokes on the same wall, one
           carrying the azimuths a hand at a natural angle reports, and they
           have to come out as the same mesh — the pen's tilt says nothing
           about which way a blade lies on a surface. */
        val plain = onFloor("wide", 7.0 * MM)
        val tilted = onFloor("wide", 7.0 * MM)
        tilted.pts.forEachIndexed { i, p -> p.roll = i * 0.37 }
        Nib.freezeFrames(plain)
        Nib.freezeFrames(tilted)
        val a = StrokeGeometry.build(plain)!!
        val b = StrokeGeometry.build(tilted)!!
        var worst = 0.0
        for (i in a.positions.indices) {
            val d = abs(a.positions[i] - b.positions[i]).toDouble()
            if (d > worst) worst = d
        }
        assertTrue(worst < 1e-9, "tilt moved a vertex by ${worst / MM}mm")
    }

    @Test
    fun `the wide axis lies in the surface and crosses the stroke`() {
        val pt = StrokePoint(Vec3(), nrm = Vec3(0.0, 1.0, 0.0))
        val t = Vec3(1.0, 0.0, 0.0)
        val axis = Nib.axisAt(pt, t)
        assertTrue(abs(axis dot t) < 1e-12, "the nib is not across the stroke")
        assertTrue(abs(axis dot pt.nrm!!) < 1e-12, "the nib is not in the surface")
    }

    @Test
    fun `with no surface the nib keeps the axis the sample was taken with`() {
        val pt = StrokePoint(Vec3())
        pt.axis = Vec3(0.0, 0.0, 1.0)
        val axis = Nib.axisAt(pt, Vec3(1.0, 0.0, 0.0))
        assertTrue(axis.distanceTo(Vec3(0.0, 0.0, 1.0)) < 1e-12)
    }

    @Test
    fun `roll round trips through the transported frame`() {
        /* rollOf measures the axis as an angle so it can survive edits that
           know nothing about surfaces; writeRing has to be able to get the
           same direction back out of it. */
        val t = Vec3(1.0, 0.0, 0.0)
        val r = Vec3(0.0, 0.0, 1.0)
        val pt = StrokePoint(Vec3(), nrm = Vec3(0.0, 1.0, 0.0))
        val roll = Nib.rollOf(pt, t, r)
        val b = t cross r
        val u = Vec3(
            r.x * cos(roll) + b.x * sin(roll),
            r.y * cos(roll) + b.y * sin(roll),
            r.z * cos(roll) + b.z * sin(roll),
        )
        val want = Nib.axisAt(pt, t)
        assertTrue(
            u.distanceTo(want) < 1e-9 || u.distanceTo(want * -1.0) < 1e-9,
            "the section basis did not come back to the nib axis",
        )
    }

    @Test
    fun `a paint brush shades as the surface`() {
        /* Every vertex of a sheet gets the surface's normal, so overlapping
           passes read as one wall instead of showing a dark line at each seam.
           A pen is a tube and keeps its own. */
        val sheet = onFloor("wide", 7.0 * MM)
        Nib.freezeFrames(sheet)
        val m = StrokeGeometry.build(sheet)!!
        var off = 0
        for (i in 0 until m.vertexCount) {
            if (abs(m.normals[i * 3 + 1] - 1f) > 1e-4) off++
        }
        assertTrue(off == 0, "$off of ${m.vertexCount} vertices did not light as the wall")

        val tube = onFloor("pen", 7.0 * MM)
        Nib.freezeFrames(tube)
        val t = StrokeGeometry.build(tube)!!
        var varied = 0
        for (i in 0 until t.vertexCount) {
            if (abs(t.normals[i * 3 + 1] - 1f) > 1e-4) varied++
        }
        assertTrue(varied > 0, "a pen lit itself as a sheet")
    }

    @Test
    fun `a squared section takes its normal from its own outline`() {
        /* The ellipse-gradient shortcut this used to take is wrong the moment
           a section is squared off: a rectangle's flat face has ONE normal
           along its whole width, and the shortcut fans it. `rectangle` is
           square = 1 and paints, so this measures `cube`'s wall, which is
           square = 1 and takes its own. */
        val s = Stroke(brush = "cube", baseRadius = 7.0 * MM)
        for (i in 0 until 6) s.pts.add(StrokePoint(Vec3(i * 0.01, 0.0, 0.0), pressure = 1.0))
        Nib.freezeFrames(s)
        val m = StrokeGeometry.build(s)!!
        /* Four flat faces means the ring's normals take four distinct values,
           not one per vertex. */
        val seg = StrokeGeometry.segmentsFor(s)
        val seen = HashSet<String>()
        for (k in 0 until seg) {
            val o = (2 + k) * 3
            seen.add(
                "%.3f,%.3f,%.3f".format(m.normals[o], m.normals[o + 1], m.normals[o + 2]),
            )
        }
        assertTrue(seen.size <= 8, "a square section fanned into ${seen.size} normals")
    }

    @Test
    fun `the nib is trimmed where the guide runs out`() {
        /* A stroke painted along the edge of a guide keeps full width on the
           inside and loses only the overhang. Without this the paint sprang
           out past the wall it was painted on. */
        val frame = SurfaceFrame(
            su = 0.004, sv = 0.5, lu = 1.0, lv = 1.0, outline = null,
            uDir = Vec3(0.0, 0.0, 1.0), vDir = Vec3(1.0, 0.0, 0.0),
        )
        val pt = StrokePoint(Vec3(), nrm = Vec3(0.0, 1.0, 0.0))
        pt.surf = frame
        // stroke runs along +x, so the nib crosses it along z, which is u
        Nib.fitAt(pt, Vec3(1.0, 0.0, 0.0), 0.02)
        val trimmed = kotlin.math.min(pt.fitL, pt.fitR)
        assertTrue(trimmed < 1.0, "the nib was not trimmed at the edge")
        assertTrue(trimmed >= Nib.FIT_MIN, "the nib collapsed to nothing")
        assertTrue(
            kotlin.math.max(pt.fitL, pt.fitR) > 0.99,
            "the side with room lost width it did not have to",
        )
    }

    @Test
    fun `smoothing a trim may pull it in but never push it back out`() {
        val pts = (0 until 7).map { StrokePoint(Vec3(it * 0.01, 0.0, 0.0)) }
        pts.forEach { it.fitL = 1.0; it.fitR = 1.0 }
        pts[3].fitR = 0.2
        val was = pts.map { it.fitR }
        Nib.smoothFit(pts)
        for (i in pts.indices) {
            assertTrue(
                pts[i].fitR <= was[i] + 1e-12,
                "point $i crept back out from ${was[i]} to ${pts[i].fitR}",
            )
        }
        assertTrue(pts[2].fitR < 1.0, "the trim did not spread to its neighbour")
    }

    @Test
    fun `the trim survives a save and a reopen`() {
        /* Measured once against a surface frame that is spent by the freeze,
           so if the file does not carry it there is nothing left to measure it
           from — and every stroke painted along a guide's edge springs back
           out over that edge the moment the sketch is reopened. */
        val sketch = Sketch()
        val s = onFloor("wide", 7.0 * MM, n = 6)
        s.pts[2].fitL = 0.4
        s.pts[2].fitR = 0.9
        sketch.add(s)

        val text = Document.toJsonText(sketch, GuideScene(), Camera())
        val back = Sketch()
        Document.restore(text, back, GuideScene(), Camera())

        val p = back.strokes[0].pts[2]
        assertTrue(abs(p.fitL - 0.4) < 1e-6, "fitL came back as ${p.fitL}")
        assertTrue(abs(p.fitR - 0.9) < 1e-6, "fitR came back as ${p.fitR}")
    }

    @Test
    fun `a stroke with room writes no trim at all`() {
        /* The key is written only where something was actually trimmed, so a
           file whose strokes all had room is what it always was. */
        val sketch = Sketch()
        sketch.add(onFloor("wide", 7.0 * MM, n = 6))
        val text = Document.toJsonText(sketch, GuideScene(), Camera())
        assertTrue(!text.contains("\"fit\""), "an untrimmed stroke wrote a fit array")
    }

    @Test
    fun `an inserted point inherits the trim of its neighbours`() {
        /* Erase and smooth cut a stroke and interpolate a new point at the
           cut. A new point taking the full nib is one bulge in the middle of
           an otherwise clean boundary. */
        val sketch = Sketch()
        val s = Stroke(brush = "wide", baseRadius = 7.0 * MM)
        for (i in 0 until 12) {
            s.pts.add(
                StrokePoint(Vec3(i * 0.06 - 0.33, 0.0, 0.0), pressure = 1.0, nrm = Vec3(0.0, 1.0, 0.0)),
            )
        }
        for (p in s.pts) { p.fitL = 0.5; p.fitR = 0.5 }
        Nib.freezeFrames(s)
        sketch.add(s)
        val was = s.pts.size

        /* the screen eraser cuts BETWEEN samples and interpolates a point at
           the cut, which is the path that can invent a fit from nothing */
        val cam = Camera().apply { resize(1000, 1000) }
        val px = cam.worldToScreen(s.pts[6].p, Vec3())
        Editing.eraseScreen(sketch, cam, px.x, px.y, 20.0)
        val now = sketch.strokes.sumOf { it.pts.size }
        assertTrue(sketch.strokes.isNotEmpty() && now != was, "the eraser did not cut anything")

        var worst = 0.0
        for (st in sketch.strokes) for (p in st.pts) worst = kotlin.math.max(worst, p.fitR)
        assertTrue(worst <= 0.55, "an inserted point took $worst of the nib")
    }

    @Test
    fun `freezing writes the frames onto the points`() {
        val s = onFloor("wide", 7.0 * MM, n = 5)
        Nib.freezeFrames(s)
        for (p in s.pts) {
            assertTrue(p.tan != null && p.ref != null, "a point came back unframed")
            assertTrue(p.surf == null, "the surface frame outlived the freeze")
        }
    }

    @Test
    fun `a frozen stroke keeps its orientation when its points move`() {
        /* What freezing is FOR. Nudging every point — which is what smooth,
           liquify and the joystick do — must not re-aim the nib, because none
           of those tools has a surface to re-aim it against. */
        val s = onFloor("wide", 7.0 * MM)
        Nib.freezeFrames(s)
        val rolls = s.pts.map { it.roll }
        for (p in s.pts) { p.p.y += 0.0002; p.nrm = null }
        val m = StrokeGeometry.build(s)!!
        assertTrue(m.vertexCount > 0)
        for (i in s.pts.indices) {
            assertTrue(abs(s.pts[i].roll - rolls[i]) < 1e-12, "point $i was re-aimed")
        }
    }

    @Test
    fun `a stroke that was never frozen is still aimed by its surface`() {
        /* Fills, snapped shapes and documents written before any of this
           arrive with normals and no frames. They get measured on the way to
           the buffer rather than left at whatever transport chose. */
        val s = onFloor("wide", 7.0 * MM)
        val m = StrokeGeometry.build(s)!!
        assertRestsOn(m, axis = 1, at = 0.0, thickMM = 3.0)
    }

    @Test
    fun `a blade on a wall lies on the wall`() {
        /* The floor is the easy case because the transported reference happens
           to start near it. A vertical wall with the stroke running up it is
           the case that actually caught the bug on the device. */
        val s = Stroke(brush = "wide", baseRadius = 7.0 * MM)
        for (i in 0 until 12) {
            s.pts.add(
                StrokePoint(
                    Vec3(0.0, i * 0.01, 0.0),
                    pressure = 1.0,
                    nrm = Vec3(0.0, 0.0, 1.0),
                ),
            )
        }
        Nib.freezeFrames(s)
        val m = StrokeGeometry.build(s)!!
        assertRestsOn(m, axis = 2, at = 0.0, thickMM = 3.0)
    }

    @Test
    fun `a rising brush stands on the surface rather than sinking into it`() {
        /* The cube is the one brush that is MEANT to leave the surface, and it
           does it in one direction only: everything on the near side of the
           extrusion sits on the wall, and the far side is one full section
           height out. */
        val s = Stroke(brush = "cube", baseRadius = 7.0 * MM)
        for (i in 0 until 8) {
            s.pts.add(
                StrokePoint(
                    Vec3(i * 0.01, 0.0, 0.0), pressure = 1.0, nrm = Vec3(0.0, 1.0, 0.0),
                ),
            )
        }
        Nib.freezeFrames(s)
        val m = StrokeGeometry.build(s)!!
        var below = 0.0
        var above = 0.0
        for (i in 0 until m.vertexCount) {
            val y = m.positions[i * 3 + 1].toDouble()
            if (y < below) below = y
            if (y > above) above = y
        }
        assertTrue(below / MM > -0.01, "the cube sank ${-below / MM}mm into the wall")
        assertTrue(above / MM > 6.0, "the cube did not stand off the wall")
    }

    /** A spiral wrapped round a cylinder of radius 0.3 about the y axis. */
    private fun onCylinder(brush: String): Stroke {
        val s = Stroke(brush = brush, baseRadius = 7.0 * MM)
        for (i in 0 until 14) {
            val a = i * 0.25
            val n = Vec3(cos(a), 0.0, sin(a))
            s.pts.add(
                StrokePoint(
                    Vec3(n.x * 0.3, i * 0.012, n.z * 0.3), pressure = 1.0, nrm = n.copy(),
                ),
            )
        }
        return s
    }

    /** How far the section basis tips out of the surface, worst point. */
    private fun tipOut(st: Stroke): Double {
        var worst = 0.0
        for (p in st.pts) {
            val t = p.tan ?: continue
            val r = p.ref ?: continue
            val n = p.nrm ?: continue
            val b = t cross r
            val u = Vec3(
                r.x * cos(p.roll) + b.x * sin(p.roll),
                r.y * cos(p.roll) + b.y * sin(p.roll),
                r.z * cos(p.roll) + b.z * sin(p.roll),
            )
            worst = kotlin.math.max(worst, abs(u dot n))
        }
        return worst
    }

    @Test
    fun `a mirrored blade lies on the mirrored surface`() {
        /* A REFLECTION TURNS THE FRAME THE ROLL IS MEASURED IN OVER.
         *
         * The angle is measured from `ref` towards `tan x ref`, and a
         * reflection sends `tan x ref` to MINUS the transform of it — so an
         * angle carried across unchanged comes out reflected about the
         * reference instead of transformed with it.
         *
         * On a FLAT guide the two answers agree, because transport keeps the
         * reference in the surface and the discrepancy vanishes. It is a
         * curved guide that shows it: on a stroke wrapped round a cylinder the
         * mirrored nib tips 58 degrees out of the surface it is painted on.
         */
        val s = onCylinder("wide")
        Nib.freezeFrames(s)
        assertTrue(tipOut(s) < 1e-9, "the nib was not in the surface to begin with")

        val copy = Selection.transformedCopy(s, Mat4.scale(-1.0, 1.0, 1.0, Mat4()))
        assertTrue(tipOut(copy) < 1e-9, "the mirrored nib tipped ${tipOut(copy)} out")

        // and the mesh is still on the cylinder it was mirrored onto
        val mesh = StrokeGeometry.build(copy)!!
        var worst = 0.0
        for (i in 0 until mesh.vertexCount) {
            val x = mesh.positions[i * 3].toDouble()
            val z = mesh.positions[i * 3 + 2].toDouble()
            val d = abs(kotlin.math.hypot(x, z) - 0.3)
            if (d > worst) worst = d
        }
        // on the cylinder's surface, and no further out than it is thick
        assertTrue(worst / MM < 3.1, "the mirrored blade stood ${worst / MM}mm off the cylinder")
    }

    @Test
    fun `a blade keeps lying on a curved surface through a rotation`() {
        val s = onCylinder("wide")
        Nib.freezeFrames(s)
        val copy = Selection.transformedCopy(s, Mat4.rotationZ(0.7, Mat4()))
        assertTrue(tipOut(copy) < 1e-9, "the rotated nib tipped ${tipOut(copy)} out")
    }

    @Test
    fun `a rotated blade keeps lying on its surface`() {
        val s = onFloor("wide", 7.0 * MM)
        Nib.freezeFrames(s)
        // a quarter turn about z sends the floor's normal from +y to -x
        val m = Mat4.rotationZ(PI / 2, Mat4())
        val copy = Selection.transformedCopy(s, m)
        val mesh = StrokeGeometry.build(copy)!!
        var worst = 0.0
        for (i in 0 until mesh.vertexCount) {
            val d = abs(mesh.positions[i * 3].toDouble())
            if (d > worst) worst = d
        }
        assertTrue(worst / MM < 3.1, "the rotated blade stood ${worst / MM}mm off")
    }

    @Test
    fun `a closed ring of paint still lies flat`() {
        val s = Stroke(brush = "wide", baseRadius = 7.0 * MM)
        val n = 24
        for (i in 0..n) {
            val a = i.toDouble() / n * 2 * PI
            s.pts.add(
                StrokePoint(
                    Vec3(cos(a) * 0.2, 0.0, sin(a) * 0.2),
                    pressure = 1.0,
                    nrm = Vec3(0.0, 1.0, 0.0),
                ),
            )
        }
        Nib.freezeFrames(s)
        val m = StrokeGeometry.build(s)!!
        assertRestsOn(m, axis = 1, at = 0.0, thickMM = 3.0)
    }
}
