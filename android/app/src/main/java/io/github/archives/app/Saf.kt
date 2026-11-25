package io.github.archives.app

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import uniffi.archives_ffi.Folder
import uniffi.archives_ffi.FolderChild
import uniffi.archives_ffi.NewFile
import uniffi.archives_ffi.SourceItem
import uniffi.archives_ffi.Sources

/** A file or folder in a folder you added. */
class Item(val uri: Uri, val id: String, val name: String, val mime: String, val size: Long, val modified: Long) {
    val isFolder get() = mime == Document.MIME_TYPE_DIR
    val isArchive get() = !isFolder && Saf.isArchive(name, mime)
}

/** Folders you added, through Android's file access (the only way Archives reaches files). */
object Saf {
    private val ARCHIVE_TYPES = setOf(
        "application/zip", "application/x-zip-compressed", "application/x-7z-compressed",
        "application/vnd.rar", "application/x-rar-compressed", "application/x-rar",
        "application/vnd.comicbook+zip", "application/vnd.comicbook-rar", "application/x-cbz", "application/x-cbr",
    )
    private val ARCHIVE_ENDINGS = listOf(".zip", ".7z", ".rar", ".cbz", ".cbr", ".cb7")

    fun isArchive(name: String, mime: String?): Boolean {
        val n = name.lowercase()
        return ARCHIVE_ENDINGS.any { n.endsWith(it) } || mime in ARCHIVE_TYPES
    }

    private val COLUMNS = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED,
    )

    /** What's in a folder: folders first, then files, in name order. Hidden ones (".name") only if [hidden]. */
    fun list(context: Context, tree: Uri, folderId: String, hidden: Boolean = false): List<Item> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
        val out = ArrayList<Item>()
        val cursor = context.contentResolver.query(children, COLUMNS, null, null, null)
            ?: throw java.io.IOException("Android didn't say what's in this folder")
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                if (!hidden && name.startsWith(".")) continue
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                val modified = if (c.isNull(4)) 0L else c.getLong(4)
                out += Item(DocumentsContract.buildDocumentUriUsingTree(tree, id), id, name, c.getString(2) ?: "", size, modified)
            }
        }
        out.sortWith(compareBy<Item> { !it.isFolder }.then { a, b -> Natural.compare(a.name, b.name) })
        return out
    }

    /** What a file is called, as its app tells it. */
    fun name(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
            }
        } catch (e: Exception) {
            // Not every app answers; the address's last part will do.
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Archive"
    }

    /** A folder's name, as Android tells it. */
    fun folderName(context: Context, tree: Uri, id: String): String {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        return name(context, uri).ifEmpty { "Folder" }
    }

    /** Every name in folder [dir] (hidden ones too), in lower case. */
    fun names(context: Context, tree: Uri, dir: String): Set<String> =
        list(context, tree, dir, hidden = true).map { it.name.lowercase() }.toSet()

    /** "Name.ext", or "Name (2).ext" and on if that's [taken] (lower case). */
    fun freeName(taken: Set<String>, stem: String, ext: String): String {
        var n = 1
        while (true) {
            val name = if (n == 1) "$stem$ext" else "$stem ($n)$ext"
            if (name.lowercase() !in taken) return name
            n++
        }
    }

    /** A name a file can have: no slashes or other characters folders refuse. */
    fun safeStem(name: String): String =
        name.map { if (it in "/\\:*?\"<>|" || it.isISOControl()) '_' else it }.joinToString("")
            .trim().trimStart('.').take(120).ifEmpty { "Archive" }

    /**
     * Everything under [picked] (in a folder of [tree]), named from each
     * one's own name down ("Trip/Photos/a.jpg"), folders before what's in
     * them, with each file's address. Dates only if [keepDates].
     */
    fun gather(context: Context, tree: Uri, picked: List<Item>, keepDates: Boolean, cancelled: () -> Boolean): Pair<List<SourceItem>, List<Uri?>> {
        val items = ArrayList<SourceItem>()
        val uris = ArrayList<Uri?>()
        fun date(i: Item): Long? = if (keepDates && i.modified > 0) i.modified / 1000 else null
        fun walk(i: Item, name: String) {
            if (cancelled()) throw Cancelled()
            if (items.size >= MAX_ITEMS) throw java.io.IOException("That's more than ${MAX_ITEMS} files and folders.")
            if (i.isFolder) {
                items += SourceItem(name, true, 0UL, date(i))
                uris += null
                for (k in list(context, tree, i.id, hidden = true)) walk(k, "$name/${k.name}")
            } else {
                items += SourceItem(name, false, i.size.coerceAtLeast(0).toULong(), date(i))
                uris += i.uri
            }
        }
        for (p in picked) walk(p, p.name)
        return items to uris
    }

    private const val MAX_ITEMS = 200_000

    class Cancelled : java.io.IOException("cancelled")
}

/**
 * A folder you chose, as the engine writes into it: what's there, new
 * folders and files, renaming and removing. Documents are named by
 * Android's ids for them.
 */
class SafFolder(context: Context, private val tree: Uri) : Folder {
    private val resolver: ContentResolver = context.contentResolver

    private fun uri(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    override fun list(dir: String): List<FolderChild>? {
        return try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir)
            val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
            val cursor = resolver.query(children, cols, null, null, null) ?: return null
            val out = ArrayList<FolderChild>()
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    out += FolderChild(name, id, c.getString(2) == Document.MIME_TYPE_DIR)
                }
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    override fun makeFolder(dir: String, name: String): String? {
        return try {
            DocumentsContract.createDocument(resolver, uri(dir), Document.MIME_TYPE_DIR, name)?.let { DocumentsContract.getDocumentId(it) }
        } catch (e: Exception) {
            null
        }
    }

    override fun newFile(dir: String, name: String): NewFile? {
        // "octet-stream": Android adds no extension of its own to the name.
        val made = try {
            DocumentsContract.createDocument(resolver, uri(dir), "application/octet-stream", name)
        } catch (e: Exception) {
            null
        } ?: return null
        return try {
            val pfd = resolver.openFileDescriptor(made, "w") ?: throw java.io.IOException("can't write")
            NewFile(DocumentsContract.getDocumentId(made), pfd.detachFd())
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, made) }
            null
        }
    }

    override fun rename(dir: String, id: String, name: String): String? {
        return try {
            // Never over anything: Android would make "name (1)" instead.
            val taken = list(dir) ?: return null
            if (taken.any { it.name.equals(name, ignoreCase = true) }) return null
            val renamed = DocumentsContract.renameDocument(resolver, uri(id), name) ?: return null
            DocumentsContract.getDocumentId(renamed)
        } catch (e: Exception) {
            null
        }
    }

    override fun remove(id: String) {
        runCatching { DocumentsContract.deleteDocument(resolver, uri(id)) }
    }

    override fun removeFolder(id: String) {
        runCatching { DocumentsContract.deleteDocument(resolver, uri(id)) }
    }

    override fun freeSpace(dir: String): ULong? {
        return try {
            val authority = tree.authority ?: return null
            val root = DocumentsContract.getTreeDocumentId(tree).substringBefore(':')
            val cols = arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)
            var free: ULong? = null
            resolver.query(DocumentsContract.buildRootsUri(authority), cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(0) == root && !c.isNull(1)) free = c.getLong(1).coerceAtLeast(0).toULong()
                }
            }
            free
        } catch (e: Exception) {
            // Not every app says; then only running out stops it.
            null
        }
    }
}

/** The files going into a new archive, each opened when its turn comes. */
class SafSources(context: Context, private val uris: List<Uri?>) : Sources {
    private val resolver: ContentResolver = context.contentResolver

    override fun open(index: UInt): Int {
        return try {
            val uri = uris.getOrNull(index.toInt()) ?: return -1
            resolver.openFileDescriptor(uri, "r")?.detachFd() ?: -1
        } catch (e: Exception) {
            -1
        }
    }
}
