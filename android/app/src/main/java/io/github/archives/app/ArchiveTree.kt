package io.github.archives.app

/**
 * The folders inside an archive, from its entries' names ("Trip/a.jpg"):
 * one level at a time, with the folders the names imply even where the
 * archive has no entry for them.
 */
class ArchiveTree(names: List<String>, folders: List<Boolean>) {
    /** Something directly in a folder: [path] is its whole name inside; [index], its entry (if it has one). */
    class Node(val name: String, val path: String, val isDir: Boolean, var index: Int?)

    private val levels = HashMap<String, LinkedHashMap<String, Node>>()
    private val clean = names.map { normal(it) }

    init {
        levels[""] = LinkedHashMap()
        clean.forEachIndexed { i, path ->
            if (path.isEmpty()) return@forEachIndexed
            val parts = path.split('/')
            var at = ""
            for ((k, part) in parts.withIndex()) {
                val last = k == parts.size - 1
                val here = if (at.isEmpty()) part else "$at/$part"
                val level = levels.getOrPut(at) { LinkedHashMap() }
                val dir = !last || folders.getOrElse(i) { false }
                val key = if (dir) "d:$part" else "f:$part"
                val node = level.getOrPut(key) { Node(part, here, dir, null) }
                if (last) node.index = i
                if (dir) levels.getOrPut(here) { LinkedHashMap() }
                at = here
            }
        }
    }

    /** What's directly in folder [path] ("" is the top): folders first, then files, in name order. */
    fun level(path: String): List<Node> =
        (levels[path]?.values ?: emptyList<Node>()).sortedWith(compareBy<Node> { !it.isDir }.then { a, b -> Natural.compare(a.name, b.name) })

    /** The entries [node] stands for: itself, and for a folder everything in it. */
    fun entries(node: Node): List<Int> {
        if (!node.isDir) return listOfNotNull(node.index)
        val prefix = node.path + "/"
        return clean.indices.filter { clean[it] == node.path || clean[it].startsWith(prefix) }
    }

    companion object {
        /** "./a\\b/" -> "a/b". */
        fun normal(name: String): String =
            name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
    }
}
