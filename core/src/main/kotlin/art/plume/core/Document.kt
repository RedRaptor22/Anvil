package art.plume.core

/**
 * Everything a saved sketch carries beyond its curves and guides.
 *
 * The light and the post effects belong to the SKETCH, not to the app: a
 * drawing lit from the left is a different drawing, and reopening it under the
 * default sun would be a change nobody asked for. Anvil does not model either
 * yet — that is Phase 5 — so they travel as [carried] instead of being
 * dropped, and a file made in the browser survives a round trip through the
 * phone with its lighting intact.
 */
class DocumentEnv {
    var background = Rgba(0.925, 0.918, 0.953)
    var grid = true
    var axis = false
    var fog = false
    var shaded = true

    /**
     * FACT: Feather shows lighting, shadows and effects accurately only in
     * rendering mode. Drawing stays cheap; you ask for the picture.
     */
    var render = false
    var groundShadow = true

    /** Both belong to the sketch, and both travel with it. See the note in serialize. */
    val light = Light()
    val fx = Fx()
}

class DocumentTool {
    var brush = "pen"
    var color = Rgba(0.106, 0.110, 0.129)
    var sizeMM = 14.0
    var opacity = 1.0
    var pressureOn = true
    var pressureTarget = "size"
    var radial = 1
    var stableOn = true
    var stable = Tune.STABLE_DEFAULT
    var mirror: String? = null
    var autoGuide = true
}

/**
 * The `.plume.json` document.
 *
 * **This is the same format the web build reads and writes**, transcribed
 * field for field from `js/doc.js`. That is the whole point of it: until a
 * sketch can move between the two, "the same sketch measures identically in
 * both builds" is a claim nobody can check.
 *
 * Numbers are quantised to six decimals — about a micrometre at 1 unit =
 * 1000 mm, far below what any pen resolves — and stored in flat arrays. That
 * roughly halves the file, which is what makes autosaving on every edit cheap
 * enough not to stall the pen.
 */
object Document {

    const val FORMAT = "plume"

    /**
     * v2 adds named groups with their own visibility. v1 stored `group` as a
     * bare number with no name and no list, so a v1 file is read by inventing
     * one group per distinct id.
     */
    const val VERSION = 2

    private const val Q = 1e6

    /** Six decimals, the same rounding the web build applies. */
    fun q(n: Double): Double = Math.round(n * Q) / Q

    private fun packVec(v: Vec3) = JsonArray.of(doubleArrayOf(q(v.x), q(v.y), q(v.z)))

    private fun unpackVec(a: JsonArray?): Vec3 {
        if (a == null || a.size < 3) return Vec3()
        return Vec3(a[0].asDouble() ?: 0.0, a[1].asDouble() ?: 0.0, a[2].asDouble() ?: 0.0)
    }

    /** `#rrggbb`, which is how the web build writes a colour. */
    fun packColor(c: Rgba): String {
        fun ch(v: Double) = clamp(Math.round(v * 255).toInt(), 0, 255)
        return "#%02x%02x%02x".format(ch(c.r), ch(c.g), ch(c.b))
    }

    fun unpackColor(s: String?, orElse: Rgba): Rgba {
        if (s == null) return orElse
        val hex = s.removePrefix("#")
        if (hex.length < 6) return orElse
        return try {
            Rgba(
                hex.substring(0, 2).toInt(16) / 255.0,
                hex.substring(2, 4).toInt(16) / 255.0,
                hex.substring(4, 6).toInt(16) / 255.0,
            )
        } catch (e: NumberFormatException) {
            orElse
        }
    }

    // ---- strokes ---------------------------------------------------------

    private fun packPoints(pts: List<StrokePoint>): JsonObject {
        val n = pts.size
        val p = DoubleArray(n * 3)
        val tan = ArrayList<JsonValue>(n * 3)
        val ref = ArrayList<JsonValue>(n * 3)
        val nrm = DoubleArray(n * 3)
        val roll = DoubleArray(n)
        val pressure = DoubleArray(n)
        var hasNrm = false

        for (i in 0 until n) {
            val t = pts[i]
            p[i * 3] = q(t.p.x); p[i * 3 + 1] = q(t.p.y); p[i * 3 + 2] = q(t.p.z)
            /*
             * A missing tangent writes three NULLS, not three zeros. The web
             * build tests `tan[i*3] !== undefined` on load, so a zero vector
             * would read back as a real tangent pointing nowhere and the
             * cross-section built on it would be degenerate.
             */
            if (t.tan != null) {
                tan.add(JsonNumber(q(t.tan!!.x)))
                tan.add(JsonNumber(q(t.tan!!.y)))
                tan.add(JsonNumber(q(t.tan!!.z)))
            } else { tan.add(JsonNull); tan.add(JsonNull); tan.add(JsonNull) }
            if (t.ref != null) {
                ref.add(JsonNumber(q(t.ref!!.x)))
                ref.add(JsonNumber(q(t.ref!!.y)))
                ref.add(JsonNumber(q(t.ref!!.z)))
            } else { ref.add(JsonNull); ref.add(JsonNull); ref.add(JsonNull) }
            t.nrm?.let {
                hasNrm = true
                nrm[i * 3] = q(it.x); nrm[i * 3 + 1] = q(it.y); nrm[i * 3 + 2] = q(it.z)
            }
            roll[i] = q(t.roll)
            pressure[i] = q(t.pressure)
        }

        val o = JsonObject()
        o.put("n", n)
        o.put("p", JsonArray.of(p))
        o.put("tan", JsonArray(tan))
        o.put("ref", JsonArray(ref))
        o.put("roll", JsonArray.of(roll))
        o.put("pressure", JsonArray.of(pressure))
        // tilt is not modelled here yet; the web build reads a null azimuth as
        // "no tilt", which is exactly what a finger reports
        o.put("tiltAz", JsonArray(MutableList(n) { JsonNull as JsonValue }))
        o.put("tiltAlt", JsonArray.of(DoubleArray(n) { 1.0 }))
        if (hasNrm) o.put("nrm", JsonArray.of(nrm))
        return o
    }

    private fun unpackPoints(d: JsonObject): ArrayList<StrokePoint> {
        val n = d.int("n", 0)
        val p = d.arr("p") ?: return ArrayList()
        val tan = d.arr("tan")
        val ref = d.arr("ref")
        val nrm = d.arr("nrm")
        val roll = d.arr("roll")
        val pressure = d.arr("pressure")

        val out = ArrayList<StrokePoint>(n)
        for (i in 0 until n) {
            fun vecAt(a: JsonArray?): Vec3? {
                if (a == null || i * 3 + 2 >= a.size) return null
                val x = a[i * 3].asDouble() ?: return null
                val y = a[i * 3 + 1].asDouble() ?: return null
                val z = a[i * 3 + 2].asDouble() ?: return null
                return Vec3(x, y, z)
            }
            out.add(
                StrokePoint(
                    vecAt(p) ?: Vec3(),
                    tan = vecAt(tan),
                    ref = vecAt(ref),
                    roll = roll?.items?.getOrNull(i)?.asDouble() ?: 0.0,
                    pressure = pressure?.items?.getOrNull(i)?.asDouble() ?: 0.5,
                    nrm = vecAt(nrm),
                ),
            )
        }
        return out
    }

    private fun packStroke(st: Stroke): JsonObject {
        val o = packPoints(st.pts)
        o.put("brush", st.brush)
        o.put("color", packColor(st.color))
        o.put("radius", q(st.baseRadius))
        o.put("opacity", q(st.opacity))
        /* was hardcoded while nothing read it; a stroke drawn with pressure
           on opacity has to keep looking like that after a reload */
        o.put("pressureTarget", st.pressureTarget)
        st.group?.let { o.put("group", it) }
        return o
    }

    private fun unpackStroke(d: JsonObject): Stroke {
        val s = Stroke(
            brush = Brushes.resolve(d.str("brush")).name,
            color = unpackColor(d.str("color"), Rgba(0.1, 0.1, 0.13)),
            baseRadius = d.num("radius", 7.0 * MM),
            opacity = d.num("opacity", 1.0),
            pressureTarget = d.str("pressureTarget") ?: "size",
        )
        s.group = d["group"]?.asInt()
        s.pts.addAll(unpackPoints(d))
        return s
    }

    // ---- guides -----------------------------------------------------------

    private fun packGuide(g: Guide): JsonObject {
        val o = JsonObject()
        o.put("kind", kindName(g.kind))
        o.put("name", g.name)
        o.put("opacity", q(g.opacity))

        g.sweep?.let { sw ->
            val local = DoubleArray(sw.local.size * 3)
            for (i in sw.local.indices) {
                local[i * 3] = q(sw.local[i].x)
                local[i * 3 + 1] = q(sw.local[i].y)
                local[i * 3 + 2] = q(sw.local[i].z)
            }
            val path = DoubleArray(sw.path.size * 3)
            for (i in sw.path.indices) {
                path[i * 3] = q(sw.path[i].x)
                path[i * 3 + 1] = q(sw.path[i].y)
                path[i * 3 + 2] = q(sw.path[i].z)
            }
            o.put(
                "sweep",
                JsonObject()
                    .put("local", JsonArray.of(local))
                    .put("path", JsonArray.of(path))
                    .put("anchorIndex", sw.anchorIndex)
                    .put("anchor", packVec(sw.anchor))
                    .put("basisR", packVec(sw.basisR))
                    .put("basisT", packVec(sw.basisT))
                    .put("depth", q(sw.depth)),
            )
        }

        g.plane?.let { pl ->
            /* A flat guide is its plane and the outline drawn on it — the mesh
               is triangulated back from those on load, so the file carries the
               curve rather than the thousands of triangles made from it. */
            val out = DoubleArray(pl.outline.size * 2)
            for (k in pl.outline.indices) {
                out[k * 2] = q(pl.outline[k].u)
                out[k * 2 + 1] = q(pl.outline[k].v)
            }
            o.put(
                "plane",
                JsonObject()
                    .put("origin", packVec(pl.origin))
                    .put("right", packVec(pl.right))
                    .put("up", packVec(pl.up))
                    .put("normal", packVec(pl.normal))
                    .put("Lu", q(pl.lu))
                    .put("Lv", q(pl.lv))
                    .put("outline", JsonArray.of(out)),
            )
        }

        if (g.kind == GuideKind.PRIMITIVE) {
            o.put(
                "prim",
                JsonObject()
                    .put("kind", g.primitiveKind ?: "cube")
                    .put("seg", g.primitiveSegments)
                    .put("taper", q(g.primitiveTaper)),
            )
        }

        if (g.kind == GuideKind.LOFT) {
            g.loftCurves?.let { curves ->
                val list = JsonArray()
                for (c in curves) {
                    val a = DoubleArray(c.size * 3)
                    for (k in c.indices) {
                        a[k * 3] = q(c[k].x); a[k * 3 + 1] = q(c[k].y); a[k * 3 + 2] = q(c[k].z)
                    }
                    list.add(JsonArray.of(a))
                }
                o.put(
                    "loft",
                    JsonObject().put("tension", q(g.loftTension)).put("curves", list),
                )
            }
        }
        return o
    }

    private fun unpackGuide(d: JsonObject): Guide? {
        val kind = d.str("kind")
        val g: Guide = when {
            d.obj("plane") != null -> flatFrom(d.obj("plane")!!)

            kind == "primitive" && d.obj("prim") != null -> {
                val p = d.obj("prim")!!
                Primitives.create(
                    p.str("kind") ?: "cube", p.int("seg", 24), p.num("taper", 1.0),
                )
            }

            kind == "loft" && d.obj("loft") != null -> {
                val l = d.obj("loft")!!
                val curves = l.arr("curves")?.items?.mapNotNull { c ->
                    c.asArray()?.doubles()?.let { a ->
                        (0 until a.size / 3).map { Vec3(a[it * 3], a[it * 3 + 1], a[it * 3 + 2]) }
                    }
                } ?: emptyList()
                if (curves.size < 2) null
                else GuideEditing.loftFromCurves(curves, l.num("tension", 1.0))
            }

            d.obj("sweep") != null -> {
                val sw = d.obj("sweep")!!
                val local = sw.arr("local")?.doubles() ?: DoubleArray(0)
                val path = sw.arr("path")?.doubles() ?: DoubleArray(0)
                if (local.size < 6 || path.size < 6) null
                else Guides.fromSweep(
                    Sweep(
                        local = (0 until local.size / 3).mapTo(ArrayList()) {
                            Vec3(local[it * 3], local[it * 3 + 1], local[it * 3 + 2])
                        },
                        anchor = unpackVec(sw.arr("anchor")),
                        anchorIndex = sw.int("anchorIndex", 0),
                        path = (0 until path.size / 3).mapTo(ArrayList()) {
                            Vec3(path[it * 3], path[it * 3 + 1], path[it * 3 + 2])
                        },
                        basisR = unpackVec(sw.arr("basisR")),
                        basisT = unpackVec(sw.arr("basisT")),
                        depth = sw.num("depth", 1.0),
                    ),
                )
            }

            else -> null
        } ?: return null

        d.str("name")?.let { g.name = it }
        g.opacity = d.num("opacity", Tune.GUIDE_OPACITY_INIT)
        return g
    }

    private fun flatFrom(p: JsonObject): Guide? {
        val flat = p.arr("outline")?.doubles() ?: return null
        if (flat.size < 6) return null
        val outline = (0 until flat.size / 2).map { UV(flat[it * 2], flat[it * 2 + 1]) }
        val g = Guide(Guide.freshId(), GuideKind.FLAT)
        g.plane = PlaneData(
            unpackVec(p.arr("origin")), unpackVec(p.arr("right")),
            unpackVec(p.arr("up")), unpackVec(p.arr("normal")),
            p.num("Lu", 1.0), p.num("Lv", 1.0), outline,
        )
        return if (Guides.rebuildFlat(g)) g else null
    }

    private fun kindName(k: GuideKind): String = when (k) {
        GuideKind.DRAW -> "draw"
        GuideKind.FLAT -> "flat"
        GuideKind.PRIMITIVE -> "primitive"
        GuideKind.LOFT -> "loft"
        GuideKind.MODEL -> "model"
        GuideKind.IMAGE -> "image"
    }

    // ---- the whole document ------------------------------------------------

    /**
     * Sections this build does not model yet, kept verbatim so a round trip
     * through the phone does not silently strip anything a browser sketch
     * carried.
     *
     * `env` used to be here because the light and the post effects had nothing
     * to be applied to; Phase 5 models both, so what is still carried is
     * whatever ELSE a future web version might add to these two objects. The
     * modelled keys are overwritten on the way out, so carrying them costs
     * nothing and covers the fields nobody has thought of yet.
     */
    class Carried(val env: JsonObject?, val tool: JsonObject?)

    fun serialize(
        sketch: Sketch,
        guides: GuideScene,
        camera: Camera,
        env: DocumentEnv = DocumentEnv(),
        tool: DocumentTool = DocumentTool(),
        carried: Carried? = null,
        modified: Long = System.currentTimeMillis(),
    ): JsonObject {
        val doc = JsonObject()
        doc.put("format", FORMAT)
        doc.put("version", VERSION)
        doc.put("modified", modified.toDouble())

        doc.put(
            "view",
            JsonObject()
                .put("theta", q(camera.theta)).put("phi", q(camera.phi))
                .put("radius", q(camera.radius)).put("roll", q(camera.roll))
                .put("pivot", packVec(camera.pivot))
                .put("focal", q(camera.focal))
                .put("ortho", camera.ortho).put("pinned", camera.pinned),
        )

        // start from whatever the file carried, then overlay what we model
        val envOut = carried?.env ?: JsonObject()
        envOut.put("bg", packColor(env.background))
        envOut.put("grid", env.grid).put("axis", env.axis).put("fog", env.fog)
        envOut.put("shaded", env.shaded).put("render", env.render)
        envOut.put("groundShadow", env.groundShadow)
        /*
         * The light belongs to the SKETCH, not to the app: a drawing lit from
         * the left is a different drawing, and reopening it under the default
         * sun would be a change nobody asked for. The same argument applies to
         * the post effects — a sketch shot at f/2.8 through heavy grain is a
         * different picture from the same curves rendered clean.
         *
         * These two blocks used to be CARRIED: copied through untouched
         * because there was nothing here to apply them to. There is now.
         */
        envOut.put(
            "light",
            JsonObject()
                .put("az", q(env.light.az)).put("alt", q(env.light.alt))
                .put("color", packColor(env.light.color))
                .put("intensity", q(env.light.intensity))
                .put("ambient", q(env.light.ambient))
                .put("toon", env.light.toon)
                .put("toonSteps", env.light.toonSteps),
        )
        envOut.put(
            "fx",
            JsonObject()
                .put("dof", env.fx.dofOn).put("fstop", q(env.fx.fstop))
                .put("grain", env.fx.grainOn).put("grainLevel", q(env.fx.grain))
                .put("pixel", env.fx.pixelOn).put("pixelSize", q(env.fx.pixel)),
        )
        doc.put("env", envOut)

        val toolOut = carried?.tool ?: JsonObject()
        toolOut.put("brush", tool.brush).put("color", packColor(tool.color))
        toolOut.put("sizeMM", q(tool.sizeMM)).put("opacity", q(tool.opacity))
        toolOut.put("pressureOn", tool.pressureOn).put("pressureTarget", tool.pressureTarget)
        toolOut.put("radial", maxOf(1, tool.radial))
        toolOut.put("stableOn", tool.stableOn).put("stable", q(tool.stable))
        if (tool.mirror != null) toolOut.put("mirror", tool.mirror!!) else toolOut.putNull("mirror")
        toolOut.put("autoGuide", tool.autoGuide)
        doc.put("tool", toolOut)

        val groupList = JsonArray()
        for (grp in sketch.groups) {
            groupList.add(
                JsonObject().put("id", grp.id).put("name", grp.name).put("visible", grp.visible),
            )
        }
        doc.put(
            "groups",
            JsonObject()
                .put("active", sketch.groups.firstOrNull()?.id ?: 0)
                .put("list", groupList),
        )

        val strokes = JsonArray()
        for (s in sketch.strokes) strokes.add(packStroke(s))
        doc.put("strokes", strokes)

        val resources = JsonArray()
        for (g in guides.resources) resources.add(packGuide(g))
        doc.put(
            "guides",
            JsonObject()
                .put("active", guides.active?.let { packGuide(it) } ?: JsonNull)
                .put("resources", resources),
        )
        return doc
    }

    fun toJsonText(
        sketch: Sketch,
        guides: GuideScene,
        camera: Camera,
        env: DocumentEnv = DocumentEnv(),
        tool: DocumentTool = DocumentTool(),
        carried: Carried? = null,
        modified: Long = System.currentTimeMillis(),
    ): String = serialize(sketch, guides, camera, env, tool, carried, modified).write()

    /** What a restore produced, and what it could not use. */
    class Restored(
        val ok: Boolean,
        val reason: String? = null,
        val env: DocumentEnv = DocumentEnv(),
        val tool: DocumentTool = DocumentTool(),
        val carried: Carried? = null,
    )

    /**
     * Read a document into [sketch] and [guides], moving [camera] to where it
     * was saved. Both are cleared first: opening a file replaces the drawing.
     */
    fun restore(
        text: String,
        sketch: Sketch,
        guides: GuideScene,
        camera: Camera,
    ): Restored {
        val root = try {
            Json.parse(text).asObject() ?: return Restored(false, "not a document")
        } catch (e: JsonException) {
            return Restored(false, e.message ?: "could not be read")
        }
        if (root.str("format") != FORMAT) return Restored(false, "not a Plume sketch")
        // a file from a newer build may use fields this one would silently drop
        if (root.int("version", 1) > VERSION) return Restored(false, "written by a newer build")

        sketch.clear()
        guides.clear()

        root.obj("view")?.let { v ->
            camera.theta = v.num("theta", camera.theta)
            camera.phi = v.num("phi", camera.phi)
            camera.radius = v.num("radius", camera.radius)
            camera.roll = v.num("roll", camera.roll)
            camera.pivot.set(unpackVec(v.arr("pivot")))
            camera.focal = v.num("focal", camera.focal)
            camera.ortho = v.bool("ortho", false)
            camera.pinned = v.bool("pinned", false)
            camera.apply()
        }

        val env = DocumentEnv()
        root.obj("env")?.let { e ->
            env.background = unpackColor(e.str("bg"), env.background)
            env.grid = e.bool("grid", true)
            env.axis = e.bool("axis", false)
            env.fog = e.bool("fog", false)
            env.shaded = e.bool("shaded", true)
            env.render = e.bool("render", false)
            env.groundShadow = e.bool("groundShadow", true)

            /*
             * A key absent from the file keeps the DEFAULT rather than becoming
             * zero. A v1 sketch has no light block at all, and a light of
             * intensity 0 at ambient 0 is a black drawing — the failure would
             * look like a corrupt file rather than a missing field.
             */
            e.obj("light")?.let { l ->
                env.light.az = l.num("az", env.light.az)
                env.light.alt = l.num("alt", env.light.alt)
                env.light.color = unpackColor(l.str("color"), env.light.color)
                env.light.intensity = l.num("intensity", env.light.intensity)
                env.light.ambient = l.num("ambient", env.light.ambient)
                env.light.toon = l.bool("toon", false)
                env.light.toonSteps = l.int("toonSteps", env.light.toonSteps)
            }
            e.obj("fx")?.let { x ->
                env.fx.dofOn = x.bool("dof", false)
                env.fx.grainOn = x.bool("grain", false)
                env.fx.pixelOn = x.bool("pixel", false)
                env.fx.fstop = x.num("fstop", env.fx.fstop)
                env.fx.grain = x.num("grainLevel", env.fx.grain)
                env.fx.pixel = x.num("pixelSize", env.fx.pixel)
            }
        }

        val tool = DocumentTool()
        root.obj("tool")?.let { t ->
            tool.brush = Brushes.resolve(t.str("brush")).name
            tool.color = unpackColor(t.str("color"), tool.color)
            tool.sizeMM = t.num("sizeMM", tool.sizeMM)
            tool.opacity = t.num("opacity", 1.0)
            tool.pressureOn = t.bool("pressureOn", true)
            tool.pressureTarget = t.str("pressureTarget") ?: "size"
            tool.radial = maxOf(1, t.int("radial", 1))
            tool.stableOn = t.bool("stableOn", true)
            tool.stable = t.num("stable", Tune.STABLE_DEFAULT)
            tool.mirror = t.str("mirror")
            tool.autoGuide = t.bool("autoGuide", true)
        }

        val groupOf = restoreGroups(root, sketch)
        root.arr("strokes")?.items?.forEach { s ->
            s.asObject()?.let { d ->
                val st = unpackStroke(d)
                // the file's group ids are its own; re-point them at the
                // groups this document actually built
                st.group = st.group?.let { groupOf[it]?.id }
                sketch.add(st)
            }
        }

        root.obj("guides")?.let { gs ->
            gs.arr("resources")?.items?.forEach { r ->
                r.asObject()?.let { unpackGuide(it)?.let { g -> guides.save(g) } }
            }
            gs.obj("active")?.let { a -> unpackGuide(a)?.let { guides.setActive(it) } }
        }

        return Restored(
            true, null, env, tool,
            Carried(root.obj("env"), root.obj("tool")),
        )
    }

    /**
     * Groups, or an honest reconstruction of them.
     *
     * A v2 file carries the list. A v1 file carries only a NUMBER per stroke,
     * so every distinct number becomes a group — named, because a nameless
     * group is not something a panel can show — and strokes that were never
     * grouped stay ungrouped.
     */
    private fun restoreGroups(root: JsonObject, sketch: Sketch): Map<Int, StrokeGroup> {
        val list = root.obj("groups")?.arr("list")
        val byOldId = HashMap<Int, StrokeGroup>()
        if (list != null && list.size > 0) {
            for (item in list.items) {
                val o = item.asObject() ?: continue
                val g = sketch.newGroup(o.str("name") ?: "Group")
                g.visible = o.bool("visible", true)
                o["id"]?.asInt()?.let { byOldId[it] = g }
            }
        } else {
            // v1: invent one group per distinct id the strokes mention
            val ids = LinkedHashSet<Int>()
            root.arr("strokes")?.items?.forEach { s ->
                s.asObject()?.get("group")?.asInt()?.let { ids.add(it) }
            }
            for (id in ids) byOldId[id] = sketch.newGroup("Group $id")
        }
        return byOldId
    }
}
