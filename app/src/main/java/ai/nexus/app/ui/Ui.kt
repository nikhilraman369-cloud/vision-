package ai.nexus.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Tiny helpers for building the (deliberately simple) screens in code. */
object Ui {
    const val BG = 0xFF14141F.toInt()
    const val CARD = 0xFF1F1F2E.toInt()
    const val ACCENT = 0xFF6C5CE7.toInt()
    const val USER = 0xFF3D3D5C.toInt()
    const val TEXT = 0xFFECECF4.toInt()
    const val MUTED = 0xFF9A9AB0.toInt()
    const val OK = 0xFF00B894.toInt()
    const val WARN = 0xFFE17055.toInt()

    fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }

    fun Context.vbox(pad: Int = 16) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }

    fun Context.label(text: String, size: Float = 15f, color: Int = TEXT, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun Context.button(text: String, color: Int = ACCENT, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; setTextColor(Color.WHITE)
        background = rounded(color, dp(12).toFloat())
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setOnClickListener { onClick() }
    }

    fun Context.bubble(text: String, fromUser: Boolean, detail: Boolean): View {
        val tv = TextView(this).apply {
            this.text = text; textSize = if (detail) 13f else 15.5f
            setTextColor(if (detail) MUTED else TEXT)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(if (fromUser) USER else CARD, dp(16).toFloat())
            setTextIsSelectable(true)
        }
        return LinearLayout(this).apply {
            gravity = if (fromUser) Gravity.END else Gravity.START
            setPadding(dp(if (fromUser) 48 else 0), dp(4), dp(if (fromUser) 0 else 48), dp(4))
            addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
