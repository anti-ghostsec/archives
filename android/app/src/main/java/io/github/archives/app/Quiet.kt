package io.github.archives.app

import android.os.Process
import kotlin.system.exitProcess

/**
 * No logs: if Archives ever crashes, it just closes. Android's own crash
 * handler would write what went wrong (which can name your files) to the
 * phone's log, where other tools can read it; this one writes nothing.
 * Java's own logging, which Android also logs, is switched off too.
 */
object Quiet {
    @Volatile private var installed = false

    fun install() {
        if (installed) return
        installed = true
        Thread.setDefaultUncaughtExceptionHandler { _, _ ->
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
        // Android sends java.util.logging (which JNA uses) to its log. With
        // no handlers and the level off, nothing logged that way goes
        // anywhere.
        java.util.logging.LogManager.getLogManager().reset()
        java.util.logging.Logger.getLogger("").level = java.util.logging.Level.OFF
    }
}
