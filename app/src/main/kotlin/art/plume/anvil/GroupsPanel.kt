package art.plume.anvil

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import art.plume.core.Group
import art.plume.core.Sketch

/**
 * The groups sheet.
 *
 * Built in code rather than XML because the module has no layouts at all yet
 * and one panel is not the moment to introduce a resource tree. It is a bottom
 * sheet, not a side rail, for the reason `MainActivity` gives: a 58px vertical
 * rail is a desktop shape, and a phone reaches the bottom of its screen with a
 * thumb.
 *
 * Every row is a group. The eye is the only control that changes what is on
 * screen, and it reads its state straight off [Group.visible] each time the
 * panel is refreshed — there is no second copy of that flag here to drift out
 * of step with the model, which is precisely the bug this panel exists to not
 * have.
 */
class GroupsPanel(context: Context, private val host: Host) : LinearLayout(context) {

    interface Host {
        fun model(): Sketch
        fun onToggleVisible(group: Group)
        fun onSelectGroup(group: Group)
        fun onAssignTo(group: Group)
        fun onRename(group: Group, name: String)
        fun onGroup()
        fun onUngroup()
        fun onDelete()
        fun onUndo()
        fun onRedo()
        fun canUndo(): Boolean
        fun canRedo(): Boolean
    }

    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val summary = label("", 11f, DIM)
    private val btnGroup = action("Group") { host.onGroup() }
    private val btnUngroup = action("Ungroup") { host.onUngroup() }
    private val btnDelete = action("Delete") { host.onDelete() }
    private val btnUndo = action("Undo") { host.onUndo() }
    private val btnRedo = action("Redo") { host.onRedo() }

    init {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(14))
        background = GradientDrawable().apply {
            cornerRadii = floatArrayOf(
                dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(),
                0f, 0f, 0f, 0f,
            )
            setColor(PANEL)
        }

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("Groups", 15f, INK).apply {
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(summary)
        })

        addView(ScrollView(context).apply { addView(rows) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)).also { it.topMargin = dp(8) })

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnGroup, grow()); addView(btnUngroup, grow()); addView(btnDelete, grow())
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(8) })

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnUndo, grow()); addView(btnRedo, grow())
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(6) })
    }

    /** Rebuild from the model. Cheap enough at this size to do wholesale. */
    fun refresh() {
        val sk = host.model()
        rows.removeAllViews()

        if (sk.groups.isEmpty()) {
            rows.addView(label(
                if (sk.strokes.size < 2) "Draw a few curves, hold one to select it, then Group."
                else "No groups yet. Hold a curve to select it, then Group.",
                12f, DIM,
            ).apply { setPadding(dp(4), dp(10), dp(4), dp(10)) })
        } else {
            for (g in sk.groups) rows.addView(row(g, sk))
        }

        val sel = sk.selection.size
        summary.text = "${sk.strokes.size} curve${plural(sk.strokes.size)}" +
            if (sel > 0) " · $sel selected" else ""

        btnGroup.isEnabled = sk.wholeGroups(sk.selection).size >= 2
        btnUngroup.isEnabled = sk.selection.any { sk.groupOf(it) != null }
        btnDelete.isEnabled = sel > 0
        btnUndo.isEnabled = host.canUndo()
        btnRedo.isEnabled = host.canRedo()
        for (b in listOf(btnGroup, btnUngroup, btnDelete, btnUndo, btnRedo)) {
            b.alpha = if (b.isEnabled) 1f else 0.4f
        }
    }

    private fun row(g: Group, sk: Sketch): View {
        val members = sk.membersOf(g)
        val selected = members.isNotEmpty() && members.all { it in sk.selection }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(7), dp(6), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(11).toFloat()
                setColor(if (selected) ACTIVE else Color.TRANSPARENT)
            }

            /* The eye. One tap, one flag, and the next frame agrees — the row
               is rebuilt from Group.visible rather than toggling a local copy. */
            addView(Button(context).apply {
                text = if (g.visible) "◉" else "○"
                contentDescription = if (g.visible) "Hide ${g.name}" else "Show ${g.name}"
                textSize = 16f
                setTextColor(if (selected) ON_ACTIVE else INK)
                alpha = if (g.visible) 1f else 0.45f
                background = null
                minWidth = dp(40); minimumWidth = dp(40)
                setPadding(dp(6), 0, dp(6), 0)
                setOnClickListener { host.onToggleVisible(g) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(g.name, 13f, if (selected) ON_ACTIVE else INK))
                addView(label(
                    "${members.size} curve${plural(members.size)}" +
                        if (!g.visible) " · hidden" else "",
                    10f, if (selected) ON_ACTIVE else DIM,
                ))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            /* Assign is only meaningful with something selected that is not
               already in this group; showing it otherwise is a button that
               does nothing, which is worse than no button. */
            if (sk.selection.isNotEmpty() && sk.selection.any { sk.groupOf(it) !== g }) {
                addView(chip("+") { host.onAssignTo(g) })
            }
            addView(chip("⋯") { askRename(g) })

            setOnClickListener { host.onSelectGroup(g) }
        }
    }

    private fun askRename(g: Group) {
        val field = EditText(context).apply {
            setText(g.name)
            setSelection(g.name.length)
        }
        AlertDialog.Builder(context)
            .setTitle("Rename group")
            .setView(field)
            .setPositiveButton("Rename") { _, _ ->
                val name = field.text.toString().trim()
                if (name.isNotEmpty() && name != g.name) host.onRename(g, name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- small builders -------------------------------------------------

    private fun label(text: String, size: Float, color: Int) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(color)
    }

    private fun action(text: String, onClick: () -> Unit) = Button(context).apply {
        this.text = text
        textSize = 12f
        setAllCaps(false)
        setTextColor(INK)
        background = GradientDrawable().apply {
            cornerRadius = dp(11).toFloat()
            setColor(CHIP)
        }
        setOnClickListener { onClick() }
    }

    private fun chip(text: String, onClick: () -> Unit) = Button(context).apply {
        this.text = text
        textSize = 13f
        setAllCaps(false)
        setTextColor(INK)
        background = null
        minWidth = dp(38); minimumWidth = dp(38)
        setPadding(dp(4), 0, dp(4), 0)
        setOnClickListener { onClick() }
    }

    private fun grow() = LinearLayout.LayoutParams(0, dp(42), 1f).also {
        it.leftMargin = dp(3); it.rightMargin = dp(3)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun plural(n: Int) = if (n == 1) "" else "s"

    private companion object {
        // the web build's palette, so the two look like the same program
        const val PANEL = 0xF7F6FAFF.toInt()
        const val INK = 0xFF1B1C21.toInt()
        const val DIM = 0xFF7C7F8C.toInt()
        const val CHIP = 0xFFE4E2EC.toInt()
        const val ACTIVE = 0xFF5AC796.toInt()
        const val ON_ACTIVE = 0xFF10231A.toInt()
    }
}
