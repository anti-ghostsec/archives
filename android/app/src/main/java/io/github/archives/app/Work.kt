package io.github.archives.app

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import io.github.archives.app.Ui.dp
import io.github.archives.app.Ui.label
import java.util.concurrent.atomic.AtomicBoolean
import uniffi.archives_ffi.Job
import uniffi.archives_ffi.humanSize

/** Long work (extracting, making an archive) on its own thread, with how far it's got and Cancel. */
object Work {
    /**
     * Runs [task] off the UI thread while a dialog shows [title], how far
     * [job] has come, and Cancel (which stops [job], and sets the flag
     * [task] gets). [done] gets the result, on the UI thread.
     */
    fun <T> run(activity: Activity, title: String, job: Job, task: (cancelled: AtomicBoolean) -> T, done: (Result<T>) -> Unit) {
        val cancelled = AtomicBoolean(false)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(24), activity.dp(20), activity.dp(24), activity.dp(4))
        }
        box.addView(activity.label(title, 16f).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(Ui.accent)
            isIndeterminate = true
        }
        box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(10)).apply { topMargin = activity.dp(14) })
        val detail = activity.label("", 13f, Ui.dim).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(0, activity.dp(8), 0, 0)
        }
        box.addView(detail)
        val dialog = AlertDialog.Builder(activity)
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ ->
                cancelled.set(true)
                job.cancel()
            }
            .show()
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        val tick = object : Runnable {
            override fun run() {
                if (finished.get()) return
                val total = job.total()
                if (total > 0UL) {
                    bar.isIndeterminate = false
                    bar.progress = job.percent().toInt()
                    val name = job.current().substringAfterLast('/')
                    detail.text = "${humanSize(job.done())} of ${humanSize(total)}" + if (name.isNotEmpty()) "\n$name" else ""
                }
                handler.postDelayed(this, 250)
            }
        }
        handler.post(tick)
        Thread {
            val result = try {
                Result.success(task(cancelled))
            } catch (e: Throwable) {
                Result.failure(e)
            }
            handler.post {
                finished.set(true)
                if (!activity.isDestroyed) {
                    runCatching { dialog.dismiss() }
                    done(result)
                }
                job.destroy()
            }
        }.start()
    }
}
