package io.github.archives.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * The little Archives keeps: the folders you added (Android's permission to
 * look in each, and its name), and whether to hide from screenshots. Nothing
 * else: not which archives you opened, nor what was in them.
 */
class Places(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("archives", Context.MODE_PRIVATE)

    class Folder(val uri: String, val name: String)

    fun folders(): List<Folder> {
        val raw = prefs.getString("folders", null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Folder(o.getString("uri"), o.getString("name"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<Folder>) {
        val a = JSONArray()
        for (f in list) a.put(JSONObject().put("uri", f.uri).put("name", f.name))
        prefs.edit().putString("folders", a.toString()).apply()
    }

    /** A folder you picked: kept, with Android's permission to look in it and change it. */
    fun addFolder(uri: Uri, name: String) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        app.contentResolver.takePersistableUriPermission(uri, flags)
        save(folders().filter { it.uri != uri.toString() } + Folder(uri.toString(), name))
    }

    /** Forgotten, and Android's permission given back. Nothing in it is touched. */
    fun removeFolder(uri: String) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            app.contentResolver.releasePersistableUriPermission(Uri.parse(uri), flags)
        } catch (e: Exception) {
            // Already gone.
        }
        save(folders().filter { it.uri != uri })
    }

    var hidden: Boolean
        get() = prefs.getBoolean("hidden", false)
        set(v) {
            prefs.edit().putBoolean("hidden", v).apply()
        }
}
