package art.plume.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The home screen's rules, which are all questions a file browser can get
 * wrong: what order things come in, what is inside what, and what happens to
 * the notes in a folder you throw away.
 */
class LibraryTest {

    private fun note(
        id: String, name: String, parent: String? = null,
        created: Long = 0, modified: Long = 0,
    ) = Library.Item(id, name, Library.Kind.NOTE, parent, created, modified)

    private fun folder(id: String, name: String, parent: String? = null) =
        Library.Item(id, name, Library.Kind.FOLDER, parent)

    @Test
    fun `folders come first, then notes in the order you asked for`() {
        val items = listOf(
            note("b", "Beta", modified = 200, created = 10),
            folder("f", "Sketches"),
            note("a", "alpha", modified = 100, created = 900),
        )
        assertEquals(
            listOf("f", "b", "a"),
            Library.sorted(items, Library.Sort.MODIFIED).map { it.id },
        )
        assertEquals(
            listOf("f", "a", "b"),
            Library.sorted(items, Library.Sort.CREATED).map { it.id },
            "newest made, not newest touched",
        )
        /* lowercase 'alpha' before 'Beta': a case-sensitive sort puts every
           capital ahead of every lowercase letter, which nobody reads as
           alphabetical */
        assertEquals(
            listOf("f", "a", "b"),
            Library.sorted(items, Library.Sort.NAME).map { it.id },
        )
    }

    @Test
    fun `two notes with the same name keep a stable order`() {
        val items = listOf(
            note("old", "Untitled", modified = 1),
            note("new", "Untitled", modified = 9),
        )
        val once = Library.sorted(items, Library.Sort.NAME).map { it.id }
        val twice = Library.sorted(items.reversed(), Library.Sort.NAME).map { it.id }
        assertEquals(listOf("new", "old"), once, "the newer one first")
        assertEquals(once, twice, "and the same either way round, not swapping on every draw")
    }

    @Test
    fun `the path reads outermost first, and stops at a broken link`() {
        val items = listOf(
            folder("a", "Work"),
            folder("b", "Chairs", parent = "a"),
            folder("orphan", "Lost", parent = "gone"),
        )
        assertEquals(listOf("Work", "Chairs"), Library.path(items, "b").map { it.name })
        assertEquals(emptyList(), Library.path(items, null).map { it.name })
        assertEquals(listOf("Lost"), Library.path(items, "orphan").map { it.name })
    }

    @Test
    fun `a ring in the index does not hang the screen that draws it`() {
        /* nothing should ever write this, which is exactly why it has to be
           survivable: an index is a file, and files get corrupted */
        val items = listOf(folder("a", "A", parent = "b"), folder("b", "B", parent = "a"))
        val p = Library.path(items, "a")
        assertTrue(p.size <= 2, "it stopped rather than walking forever")
    }

    @Test
    fun `a folder shows the four most recently touched notes in it`() {
        val items = listOf(folder("f", "Box")) + (1..6).map {
            note("n$it", "N$it", parent = "f", modified = it.toLong())
        }
        assertEquals(
            listOf("n6", "n5", "n4", "n3"),
            Library.cover(items, "f").map { it.id },
        )
    }

    @Test
    fun `deleting a folder takes everything under it, however deep`() {
        val items = listOf(
            folder("a", "A"),
            folder("b", "B", parent = "a"),
            note("n1", "one", parent = "a"),
            note("n2", "two", parent = "b"),
            note("outside", "three"),
        )
        val gone = Library.descendants(items, "a").map { it.id }.toSet()
        assertEquals(setOf("b", "n1", "n2"), gone)
        assertFalse("outside" in gone)
    }

    @Test
    fun `a folder cannot be dropped into itself or into its own child`() {
        val items = listOf(folder("a", "A"), folder("b", "B", parent = "a"))
        assertFalse(Library.canMove(items, "a", "a"), "into itself")
        assertFalse(Library.canMove(items, "a", "b"), "into something it contains")
        assertTrue(Library.canMove(items, "b", null), "out to the top, always")
        assertTrue(Library.canMove(items, "b", "a"), "and back where it was")
    }

    @Test
    fun `a duplicate gets the next free name rather than being refused`() {
        val taken = listOf("Chair", "Chair 2", "table")
        assertEquals("Stool", Library.freeName(taken, "Stool"))
        assertEquals("Chair 3", Library.freeName(taken, "Chair"))
        assertEquals("table 2", Library.freeName(taken, "table"))
        assertEquals("Table 2", Library.freeName(taken, "Table"), "case is not a difference")
    }
}
