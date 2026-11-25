package io.github.archives.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ArchiveTreeTest {
    private fun names(nodes: List<ArchiveTree.Node>) = nodes.map { (if (it.isDir) "/" else "") + it.name }

    @Test
    fun foldersComeFromNamesToo() {
        val t = ArchiveTree(
            listOf("Trip/", "Trip/b10.jpg", "Trip/b2.jpg", "Other/x/y.txt", "top.txt", "./dot\\win.txt"),
            listOf(true, false, false, false, false, false),
        )
        assertEquals(listOf("/dot", "/Other", "/Trip", "top.txt"), names(t.level("")))
        assertEquals(listOf("b2.jpg", "b10.jpg"), names(t.level("Trip")))
        assertEquals(listOf("/x"), names(t.level("Other")))
        assertEquals(listOf("win.txt"), names(t.level("dot")))
        val trip = t.level("").first { it.name == "Trip" }
        assertEquals(0, trip.index)
        assertEquals(listOf(0, 1, 2), t.entries(trip))
        val other = t.level("").first { it.name == "Other" }
        assertEquals(null, other.index)
        assertEquals(listOf(3), t.entries(other))
        assertEquals(listOf(4), t.entries(t.level("").last()))
    }

    @Test
    fun aFileAndAFolderOfTheSameName() {
        val t = ArchiveTree(listOf("a", "a/b"), listOf(false, false))
        assertEquals(listOf("/a", "a"), names(t.level("")))
        assertEquals(listOf("b"), names(t.level("a")))
    }

    @Test
    fun names() {
        assertEquals("a/b", ArchiveTree.normal("./a\\b/"))
        assertEquals("", ArchiveTree.normal("/"))
    }
}
