package io.github.archives.app

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/** Archives' colours: dark, with sea-green. */
object Ui {
    val background = Color.rgb(0x13, 0x16, 0x19)
    val card = Color.rgb(0x1d, 0x23, 0x27)
    val picked = Color.rgb(0x1f, 0x3d, 0x3a)
    val text = Color.rgb(0xec, 0xee, 0xee)
    val dim = Color.rgb(0x96, 0xa0, 0xa2)
    val accent = Color.rgb(0x4f, 0xc1, 0xb4)

    fun Activity.dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    fun Activity.label(text: String, sizeSp: Float = 16f, color: Int = Ui.text): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    }

    fun Activity.button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Ui.background)
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(accent)
        }
        setPadding(dp(14), dp(10), dp(14), dp(10))
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    /** A flat, round-cornered panel. */
    fun Activity.panel(color: Int = card) = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(color)
    }

    fun Activity.toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    /** Kept out of screenshots, screen recording and the recent-apps view, if so chosen. */
    fun Activity.applyPrivacy() {
        if (Places(this).hidden) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /** Calls [pad] with the space Android's bars and the camera cut-out take: left, top, right, bottom. */
    fun View.onInsets(pad: (Int, Int, Int, Int) -> Unit) {
        setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                pad(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                pad(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }
}
