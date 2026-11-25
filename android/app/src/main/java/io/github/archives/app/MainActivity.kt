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
import android.widget.RadioButton
import android.widget.RadioGroup
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
import java.util.concurrent.Executors
import uniffi.archives_ffi.ArchiveException
import uniffi.archives_ffi.Job
import uniffi.archives_ffi.archiveStem
import uniffi.archives_ffi.humanSize
import uniffi.archives_ffi.make

/**
 * Archives' home: the folders you added, and a way to open any archive.
 * Inside a folder: what's in it. Tap an archive to look inside and extract
 * it; hold things to pick them, and make an archive of them.
 */
class MainActivity : Activity() {
    private class Level(val tree: Uri, val id: String, val title: String)

    private lateinit var places: Places
    private lateinit var heading: TextView
    private lateinit var upButton: TextView
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private val work = Executors.newSingleThreadExecutor()
    private val stack = ArrayList<Level>()
    private var backCallback: Any? = null
    private var shown = 0

    /** What the folder showing holds, to show it again without asking Android. */
    private var lastItems: List<Item>? = null

    /** Things picked (held) for a new archive, by document id. */
    private val picked = LinkedHashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        Quiet.install()
        super.onCreate(savedInstanceState)
        places = Places(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.background)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        upButton = label("←", 24f).apply {
            setPadding(0, 0, dp(16), 0)
            visibility = View.GONE
            contentDescription = "Back"
            setOnClickListener { up() }
        }
        heading = label("Archives", 26f).apply {
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        header.addView(upButton)
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
    }

    override fun onResume() {
        super.onResume()
        applyPrivacy()
        refresh()
    }

    override fun onDestroy() {
        work.shutdown()
        super.onDestroy()
    }

    private fun refresh() {
        val level = stack.lastOrNull()
        upButton.visibility = if (level == null) View.GONE else View.VISIBLE
        heading.text = level?.title ?: "Archives"
        updateBack()
        if (level == null) showHome() else showFolder(level)
    }

    private fun up() {
        if (stack.isEmpty()) return
        stack.removeAt(stack.size - 1)
        picked.clear()
        refresh()
        scroll.scrollTo(0, 0)
    }

    /** Back goes up a folder before it leaves Archives. */
    private fun updateBack() {
        if (Build.VERSION.SDK_INT < 33) return
        val registered = backCallback as? OnBackInvokedCallback
        if (stack.isNotEmpty() && registered == null) {
            val callback = OnBackInvokedCallback { up() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        } else if (stack.isEmpty() && registered != null) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(registered)
            backCallback = null
        }
    }

    @Deprecated("Android 13 on, back is onBackInvokedDispatcher's (updateBack)")
    override fun onBackPressed() {
        if (stack.isNotEmpty()) {
            up()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun section(text: String) {
        list.addView(label(text, 14f, Ui.accent).apply {
            setPadding(dp(4), dp(20), 0, dp(8))
            setTypeface(typeface, Typeface.BOLD)
        })
    }

    private fun note(text: String) {
        list.addView(label(text, 13f, Ui.dim).apply { setPadding(dp(4), dp(8), dp(4), dp(8)) })
    }

    /** A row: an icon, a line in bold, maybe a second one. */
    private fun row(icon: String, main: String, detail: String? = null, chosen: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = panel(if (chosen) Ui.picked else Ui.card)
            setPadding(dp(12), dp(10), dp(14), dp(10))
            isClickable = true
            setOnClickListener { onClick() }
            if (onLongClick != null) setOnLongClickListener { onLongClick(); true }
        }
        outer.addView(label(if (chosen) "✓" else icon, 20f, if (chosen) Ui.accent else Ui.text).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(label(main, 16f).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        if (detail != null) box.addView(label(detail, 13f, Ui.dim).apply { setPadding(0, dp(2), 0, 0) })
        list.addView(outer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
    }

    private fun showHome() {
        list.removeAllViews()
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(button("Open an archive") { pickFile() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        buttons.addView(button("Add a folder") { pickFolder() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        list.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        val folders = places.folders()
        if (folders.isNotEmpty()) {
            section("Folders")
            for (f in folders) {
                row("📁", f.name, onLongClick = {
                    confirm("Remove the folder “${f.name}” from Archives? Nothing in it is deleted; Archives just stops being allowed to look in it.", "Remove") {
                        places.removeFolder(f.uri)
                        refresh()
                    }
                }) {
                    val tree = Uri.parse(f.uri)
                    open(Level(tree, DocumentsContract.getTreeDocumentId(tree), f.name))
                }
            }
            note("Hold a folder to remove it.")
        } else {
            list.addView(label(
                "Open a .zip, .7z or .rar archive to see what's in it and extract it, or add a folder to make archives of what's in it and extract the archives there.\n\n" +
                    "Archives doesn't use the internet, keeps no record of what you open, and sees only the files and folders you choose.",
                15f, Ui.dim,
            ).apply { setPadding(dp(4), dp(24), dp(4), 0) })
        }
        section("Privacy")
        val hidden = places.hidden
        row(if (hidden) "🔒" else "🔓", "Hide from screenshots and recent apps", if (hidden) "On" else "Off") {
            places.hidden = !hidden
            applyPrivacy()
            refresh()
        }
    }

    private fun open(level: Level) {
        stack += level
        picked.clear()
        refresh()
        scroll.scrollTo(0, 0)
    }

    private fun showFolder(level: Level) {
        list.removeAllViews()
        list.addView(label("Loading…", 15f, Ui.dim).apply { setPadding(dp(4), dp(16), 0, 0) })
        lastItems = null
        val ticket = ++shown
        work.execute {
            val items = try { Saf.list(this, level.tree, level.id) } catch (e: Exception) { null }
            runOnUiThread {
                if (ticket != shown || isDestroyed) return@runOnUiThread
                lastItems = items
                picked.retainAll(items.orEmpty().map { it.id }.toSet())
                render(level, items)
            }
        }
    }

    /** A folder's contents, as [showFolder] found them. */
    private fun render(level: Level, items: List<Item>?) {
        val y = scroll.scrollY
        list.removeAllViews()
        when {
            items == null -> list.addView(label("Archives can't look in this folder any more. Remove it and add it again.", 15f, Ui.dim).apply {
                setPadding(dp(4), dp(16), dp(4), 0)
            })
            items.isEmpty() -> list.addView(label("This folder is empty.", 15f, Ui.dim).apply {
                setPadding(dp(4), dp(16), dp(4), 0)
            })
            else -> {
                pickBar(level, items)
                for (item in items.take(MAX_ROWS)) itemRow(level, item)
                if (items.size > MAX_ROWS) note("And ${items.size - MAX_ROWS} more, not shown.")
            }
        }
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun itemRow(level: Level, item: Item) {
        val chosen = item.id in picked
        val icon = when {
            item.isFolder -> "📁"
            item.isArchive -> "🗜"
            else -> "📄"
        }
        val detail = if (item.isFolder) null else humanSize(item.size.coerceAtLeast(0).toULong())
        row(icon, item.name, detail, chosen, onLongClick = { togglePick(level, item) }) {
            when {
                picked.isNotEmpty() -> togglePick(level, item)
                item.isFolder -> open(Level(level.tree, item.id, item.name))
                item.isArchive -> openArchive(item.uri, level)
                else -> togglePick(level, item)
            }
        }
    }

    /** Above a folder's contents, once things are picked: make an archive of them. */
    private fun pickBar(level: Level, items: List<Item>) {
        if (picked.isEmpty()) {
            note("Tap an archive to open it. Hold files or folders to pick them for a new archive.")
            return
        }
        val chosen = items.filter { it.id in picked }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(button("Make an archive of ${chosen.size}") { askMake(level, chosen) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        bar.addView(button("Cancel") {
            picked.clear()
            render(level, lastItems)
        })
        list.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        })
        note("Tap to pick or unpick.")
    }

    private fun togglePick(level: Level, item: Item) {
        if (!picked.remove(item.id)) picked += item.id
        render(level, lastItems)
    }

    // ---- Opening ----

    private fun openArchive(uri: Uri, level: Level?) {
        val intent = Intent(this, ArchiveActivity::class.java).setData(uri)
        if (level != null) {
            intent.putExtra(ArchiveActivity.EXTRA_TREE, level.tree.toString())
            intent.putExtra(ArchiveActivity.EXTRA_DIR, level.id)
        }
        startActivity(intent)
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(intent, REQ_FILE)
        } catch (e: Exception) {
            toast("This phone has no file picker")
        }
    }

    private fun pickFolder() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER)
        } catch (e: Exception) {
            toast("This phone has no folder picker")
        }
    }

    @Deprecated("Fine for a plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_FILE -> openArchive(uri, null)
            REQ_FOLDER -> {
                try {
                    val name = Saf.folderName(this, uri, DocumentsContract.getTreeDocumentId(uri))
                    places.addFolder(uri, name)
                    refresh()
                } catch (e: Exception) {
                    toast("Android didn't let Archives keep that folder")
                }
            }
        }
    }

    private fun confirm(text: String, yes: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(text)
            .setPositiveButton(yes) { _, _ -> action() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Making archives ----

    /** Asks what to call the new archive, and how to make it. */
    private fun askMake(level: Level, chosen: List<Item>) {
        if (chosen.isEmpty()) return
        val stem = Saf.safeStem(
            if (chosen.size == 1) {
                val one = chosen[0]
                if (one.isFolder) one.name else archiveStem(one.name)
            } else {
                level.title
            },
        )
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        val name = EditText(this).apply {
            setText(stem)
            setSingleLine()
            hint = "Name"
        }
        box.addView(label("Name", 13f, Ui.dim))
        box.addView(name)
        box.addView(label("Kind", 13f, Ui.dim).apply { setPadding(0, dp(10), 0, 0) })
        val kinds = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val sevenZ = RadioButton(this).apply { text = ".7z"; id = View.generateViewId() }
        val zip = RadioButton(this).apply { text = ".zip (opens anywhere)"; id = View.generateViewId() }
        kinds.addView(sevenZ)
        kinds.addView(zip)
        kinds.check(zip.id)
        box.addView(kinds)
        box.addView(label("Compression", 13f, Ui.dim).apply { setPadding(0, dp(10), 0, 0) })
        val levels = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val normal = RadioButton(this).apply { text = "Normal"; id = View.generateViewId() }
        val small = RadioButton(this).apply { text = "Smallest"; id = View.generateViewId() }
        val none = RadioButton(this).apply { text = "None"; id = View.generateViewId() }
        levels.addView(normal)
        levels.addView(small)
        levels.addView(none)
        levels.check(normal.id)
        box.addView(levels)
        val password = EditText(this).apply {
            hint = "Password (optional)"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(password, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        val show = CheckBox(this).apply {
            text = "Show the password"
            setOnCheckedChangeListener { _, on ->
                password.inputType = InputType.TYPE_CLASS_TEXT or
                    (if (on) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
                password.setSelection(password.text.length)
            }
        }
        box.addView(show)
        val dates = CheckBox(this).apply {
            text = "Keep when each file was last changed"
            isChecked = false
        }
        box.addView(dates)
        box.addView(label(
            "With a password, .7z hides the files' names too; .zip locks what's in the files, but anyone can see their names.",
            12f, Ui.dim,
        ).apply { setPadding(0, dp(6), 0, dp(4)) })
        AlertDialog.Builder(this)
            .setTitle("New archive in “${level.title}”")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Make") { _, _ ->
                val format = if (kinds.checkedRadioButtonId == sevenZ.id) "7z" else "zip"
                val how = when (levels.checkedRadioButtonId) {
                    small.id -> "maximum"
                    none.id -> "store"
                    else -> "normal"
                }
                val pw = password.text.toString().ifEmpty { null }
                makeArchive(level, chosen, Saf.safeStem(name.text.toString()), format, how, pw, dates.isChecked)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * [chosen] made into "[stem].[format]" in [level]'s folder: written under a
     * hidden name first and named only when whole, so a half-made archive is
     * never seen; cancelled or failed, it's removed.
     */
    private fun makeArchive(level: Level, chosen: List<Item>, stem: String, format: String, how: String, password: String?, keepDates: Boolean) {
        val job = Job()
        val folder = SafFolder(this, level.tree)
        Work.run(this, "Making “$stem.$format”…", job, { cancelled ->
            val (items, uris) = Saf.gather(this, level.tree, chosen, keepDates) { cancelled.get() }
            val work = folder.newFile(level.id, ".archives-${System.currentTimeMillis()}.$format")
                ?: throw java.io.IOException("Android didn't let Archives make a file in this folder.")
            var named: String? = null
            try {
                // Handed back to Android's own handle, so it can be read and written in place.
                val pfd = android.os.ParcelFileDescriptor.adoptFd(work.fd)
                pfd.use { make(items, SafSources(this, uris), it.fd, format, how, password, job) }
                if (cancelled.get()) throw Saf.Cancelled()
                val taken = Saf.names(this, level.tree, level.id)
                val wanted = Saf.freeName(taken, stem, ".$format")
                folder.rename(level.id, work.id, wanted) ?: throw java.io.IOException("Android didn't let Archives name the archive.")
                named = wanted
                wanted
            } finally {
                if (named == null) folder.remove(work.id)
            }
        }) { result ->
            result.onSuccess { made ->
                picked.clear()
                toast("Made “$made”")
            }.onFailure { e -> toast(problem(e)) }
            refresh()
        }
    }

    companion object {
        private const val REQ_FILE = 1
        private const val REQ_FOLDER = 2
        private const val MAX_ROWS = 3000

        /** What went wrong, in words. */
        fun problem(e: Throwable): String = when (e) {
            is ArchiveException.Failed -> e.why
            is ArchiveException.NeedPassword -> "This archive is password-protected."
            is ArchiveException.WrongPassword -> "The password is not correct."
            is ArchiveException.Cancelled, is Saf.Cancelled -> "Cancelled."
            is java.io.IOException -> e.message ?: "Something couldn't be read or written."
            else -> "Something went wrong."
        }
    }
}
