package io.github.archives.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import io.github.archives.app.Ui.applyPrivacy
import io.github.archives.app.Ui.button
import io.github.archives.app.Ui.dp
import io.github.archives.app.Ui.label
import io.github.archives.app.Ui.onInsets
import io.github.archives.app.Ui.panel
import io.github.archives.app.Ui.toast
import java.text.DateFormat
import java.util.Date
import uniffi.archives_ffi.ArchiveException
import uniffi.archives_ffi.Job
import uniffi.archives_ffi.Listing
import uniffi.archives_ffi.extract
import uniffi.archives_ffi.humanSize
import uniffi.archives_ffi.listArchive

/**
 * One archive: what's in it, folder by folder, and extracting all of it or
 * the things you pick. It's only read; nothing is written until you choose
 * where (and then only there). Nothing about it is kept once you leave.
 */
class ArchiveActivity : Activity() {
    private lateinit var heading: TextView
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private var backCallback: Any? = null

    private var uri: Uri? = null
    private var name = "Archive"

    /** The folder the archive is in, if it was opened from one you added: "Extract here" goes there. */
    private var tree: Uri? = null
    private var dir: String? = null

    private var listing: Listing? = null
    private var archiveTree: ArchiveTree? = null
    private var password: String? = null
    private var path = ""

    /** Picked to extract, by their path inside. */
    private val picked = LinkedHashSet<String>()

    /** What "Extract to…" is for, while the folder picker is open: everything, or the picked. */
    private var pendingWhich: List<UInt>? = null
    private var pendingAll = true

    override fun onCreate(savedInstanceState: Bundle?) {
        Quiet.install()
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.background)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        header.addView(label("←", 24f).apply {
            setPadding(0, 0, dp(16), 0)
            contentDescription = "Back"
            setOnClickListener { back() }
        })
        heading = label("Archive", 22f).apply {
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        header.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.onInsets { l, t, r, b -> root.setPadding(l, t, r, b) }
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 33) {
            val callback = OnBackInvokedCallback { back() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        }

        uri = when (intent?.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            else -> intent?.data
        }
        tree = intent?.getStringExtra(EXTRA_TREE)?.let { Uri.parse(it) }
        dir = intent?.getStringExtra(EXTRA_DIR)
        val u = uri
        if (u == null) {
            message("There's no archive to open.")
            return
        }
        name = Saf.name(this, u)
        heading.text = name
        load()
    }

    override fun onResume() {
        super.onResume()
        applyPrivacy()
    }

    override fun onDestroy() {
        // The password goes with the window.
        password = null
        super.onDestroy()
    }

    private fun back() {
        if (path.isNotEmpty()) {
            path = path.substringBeforeLast('/', "")
            render()
            scroll.scrollTo(0, 0)
        } else {
            finish()
        }
    }

    @Deprecated("Android 13 on, back is onBackInvokedDispatcher's")
    override fun onBackPressed() {
        back()
    }

    private fun message(text: String) {
        list.removeAllViews()
        list.addView(label(text, 15f, Ui.dim).apply { setPadding(dp(4), dp(16), dp(4), 0) })
    }

    private fun note(text: String) {
        list.addView(label(text, 13f, Ui.dim).apply { setPadding(dp(4), dp(8), dp(4), dp(8)) })
    }

    /** The archive, read; for [block] while it's open. */
    private fun <T> withArchive(block: (Int) -> T): T {
        val u = uri ?: throw java.io.IOException("There's no archive.")
        val pfd = contentResolver.openFileDescriptor(u, "r") ?: throw java.io.IOException("The archive couldn't be opened.")
        return pfd.use { block(it.fd) }
    }

    /** Lists the archive (asking for the password if it's needed to). */
    private fun load() {
        message("Opening…")
        val pw = password
        Thread {
            val result = try {
                Result.success(withArchive { fd -> listArchive(fd, pw) })
            } catch (e: Throwable) {
                Result.failure(e)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { l ->
                    listing = l
                    archiveTree = ArchiveTree(l.entries.map { it.name }, l.entries.map { it.isDir })
                    path = ""
                    render()
                }.onFailure { e ->
                    when (e) {
                        is ArchiveException.NeedPassword -> askPassword(null) { load() }
                        is ArchiveException.WrongPassword -> askPassword("That password is not correct.") { load() }
                        else -> message(MainActivity.problem(e))
                    }
                }
            }
        }.start()
    }

    /** Asks for the password, then [then]. Cancelled: back where it was. */
    private fun askPassword(why: String?, then: () -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        if (why != null) box.addView(label(why, 14f, Ui.accent).apply { setPadding(0, 0, 0, dp(6)) })
        val field = EditText(this).apply {
            setSingleLine()
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(field)
        box.addView(CheckBox(this).apply {
            text = "Show the password"
            setOnCheckedChangeListener { _, on ->
                field.inputType = InputType.TYPE_CLASS_TEXT or
                    (if (on) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
                field.setSelection(field.text.length)
            }
        })
        AlertDialog.Builder(this)
            .setTitle("“$name” is password-protected")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Open") { _, _ ->
                password = field.text.toString().ifEmpty { null }
                then()
            }
            .setNegativeButton("Cancel") { _, _ ->
                if (listing == null) message("It's locked with a password.")
            }
            .show()
    }

    private fun render() {
        val l = listing ?: return
        val t = archiveTree ?: return
        val y = if (path.isEmpty()) 0 else scroll.scrollY
        list.removeAllViews()
        val total = l.entries.fold(0UL) { a, e -> a + e.size }
        val files = l.entries.count { !it.isDir }
        val locked = l.entries.any { it.encrypted }
        list.addView(label(
            "${l.format} · $files file${if (files == 1) "" else "s"} · ${humanSize(total)} unpacked" + if (locked) " · 🔒" else "",
            13f, Ui.dim,
        ).apply { setPadding(dp(4), 0, dp(4), dp(8)) })

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val wide = { last: Boolean -> LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (!last) marginEnd = dp(8) } }
        if (picked.isEmpty()) {
            if (tree != null && dir != null) bar.addView(button("Extract here") { extractAll(tree!!, dir!!) }, wide(false))
            bar.addView(button("Extract to…") { pickDestination(null) }, wide(true))
        } else {
            val which = pickedEntries()
            if (tree != null && dir != null) bar.addView(button("Extract ${picked.size} here") { extractSome(tree!!, dir!!, which) }, wide(false))
            bar.addView(button("Extract ${picked.size} to…") { pickDestination(which) }, wide(false))
            bar.addView(button("Cancel") {
                picked.clear()
                render()
            })
        }
        list.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
        note(
            if (picked.isEmpty()) {
                "Everything comes out in a new folder, and nothing already there is replaced. Hold things to extract only those."
            } else {
                "The ones picked come out with the folders they're in. Tap to pick or unpick."
            },
        )

        if (path.isNotEmpty()) {
            list.addView(label("📂 $path", 14f, Ui.accent).apply {
                setPadding(dp(4), dp(4), dp(4), dp(8))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.START
            })
        }
        val nodes = t.level(path)
        if (nodes.isEmpty()) note("Nothing here.")
        for (node in nodes.take(MAX_ROWS)) nodeRow(l, node)
        if (nodes.size > MAX_ROWS) note("And ${nodes.size - MAX_ROWS} more, not shown.")
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun nodeRow(l: Listing, node: ArchiveTree.Node) {
        val chosen = node.path in picked
        val entry = node.index?.let { l.entries.getOrNull(it) }
        val icon = when {
            chosen -> "✓"
            node.isDir -> "📁"
            entry?.isLink == true -> "↪"
            entry?.encrypted == true -> "🔒"
            else -> "📄"
        }
        val detail = if (node.isDir || entry == null) {
            null
        } else {
            val date = entry.modified?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it * 1000)) }
            listOfNotNull(humanSize(entry.size), date).joinToString(" · ")
        }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = panel(if (chosen) Ui.picked else Ui.card)
            setPadding(dp(12), dp(10), dp(14), dp(10))
            isClickable = true
            setOnClickListener {
                when {
                    picked.isNotEmpty() -> toggle(node)
                    node.isDir -> {
                        path = node.path
                        render()
                        scroll.scrollTo(0, 0)
                    }
                    else -> toggle(node)
                }
            }
            setOnLongClickListener { toggle(node); true }
        }
        outer.addView(label(icon, 20f, if (chosen) Ui.accent else Ui.text).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(label(node.name, 16f).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        if (detail != null) box.addView(label(detail, 13f, Ui.dim).apply { setPadding(0, dp(2), 0, 0) })
        list.addView(outer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
    }

    private fun toggle(node: ArchiveTree.Node) {
        if (!picked.remove(node.path)) picked += node.path
        render()
    }

    /** The entries the picked things stand for. */
    private fun pickedEntries(): List<UInt> {
        val t = archiveTree ?: return emptyList()
        val byPath = HashMap<String, ArchiveTree.Node>()
        fun walk(at: String) {
            for (n in t.level(at)) {
                byPath[n.path] = n
                if (n.isDir) walk(n.path)
            }
        }
        walk("")
        val out = sortedSetOf<Int>()
        for (p in picked) byPath[p]?.let { out.addAll(t.entries(it)) }
        return out.map { it.toUInt() }
    }

    // ---- Extracting ----

    private fun pickDestination(which: List<UInt>?) {
        pendingWhich = which
        pendingAll = which == null
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_DEST)
        } catch (e: Exception) {
            toast("This phone has no folder picker")
        }
    }

    @Deprecated("Fine for a plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val dest = data?.data
        if (requestCode != REQ_DEST || resultCode != RESULT_OK || dest == null) return
        // Only for this: the permission isn't kept.
        val id = try { DocumentsContract.getTreeDocumentId(dest) } catch (e: Exception) { return }
        val which = pendingWhich
        if (pendingAll || which == null) extractAll(dest, id) else extractSome(dest, id, which)
    }

    /** All of it, as one new folder in folder [into]. */
    private fun extractAll(destTree: Uri, into: String) {
        startExtract(destTree, into, null, true)
    }

    /** [which] entries, straight into folder [into] (with the folders they're in). */
    private fun extractSome(destTree: Uri, into: String, which: List<UInt>) {
        if (which.isEmpty()) return
        startExtract(destTree, into, which, false)
    }

    private fun startExtract(destTree: Uri, into: String, which: List<UInt>?, newFolder: Boolean) {
        val job = Job()
        val folder = SafFolder(this, destTree)
        val pw = password
        Work.run(this, "Extracting “$name”…", job, { _ ->
            withArchive { fd -> extract(fd, name, pw, folder, into, which, newFolder, job) }
        }) { result ->
            result.onSuccess { line ->
                picked.clear()
                render()
                AlertDialog.Builder(this).setMessage(line).setPositiveButton("OK", null).show()
            }.onFailure { e ->
                when (e) {
                    is ArchiveException.NeedPassword -> askPassword(null) { startExtract(destTree, into, which, newFolder) }
                    is ArchiveException.WrongPassword -> askPassword("That password is not correct.") { startExtract(destTree, into, which, newFolder) }
                    else -> AlertDialog.Builder(this).setMessage(MainActivity.problem(e)).setPositiveButton("OK", null).show()
                }
            }
        }
    }

    companion object {
        const val EXTRA_TREE = "io.github.archives.app.TREE"
        const val EXTRA_DIR = "io.github.archives.app.DIR"
        private const val REQ_DEST = 1
        private const val MAX_ROWS = 3000
    }
}
