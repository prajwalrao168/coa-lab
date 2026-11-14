package com.safetybracelet.ble

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Colors, type and small view helpers shared by both screens. */
object Ui {
    // Palette
    val PAGE = 0xFF0B1214.toInt()      // deep teal-black background
    val CARD = 0xFF131D20.toInt()
    val INK = 0xFFE7F0EF.toInt()       // soft light: text and primary button
    val MUTED = 0xFF8CA2A5.toInt()
    val LINE = 0xFF223237.toInt()
    val TINT = 0xFF1C2D31.toInt()      // soft teal tint for avatars and pills
    val GREEN = 0xFF3CCB9F.toInt()     // protected
    val AMBER = 0xFFF0A93B.toInt()     // connecting
    val RED = 0xFFF2645F.toInt()       // alert
    val GREY = 0xFF5D7276.toInt()      // off
    val ALARM_BG = 0xFF7A1A14.toInt()

    const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    fun round(c: Context, fill: Int, radiusDp: Int, stroke: Int = 0, strokeDp: Int = 0) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(c, radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(c, strokeDp), stroke)
        }

    fun ripple(base: Drawable, color: Int = 0x22FFFFFF) =
        RippleDrawable(ColorStateList.valueOf(color), base, null)

    fun lp(c: Context, w: Int, h: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b)) }

    fun text(c: Context, s: String, sp: Float, color: Int, medium: Boolean = false) =
        TextView(c).apply {
            text = s
            textSize = sp
            setTextColor(color)
            typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        }

    /** Rounded container with a hairline border. */
    fun card(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = round(c, CARD, 20, LINE, 1)
        setPadding(dp(c, 20), dp(c, 18), dp(c, 20), dp(c, 18))
    }

    fun styleButton(c: Context, v: TextView, bg: Int, fg: Int, stroke: Int = 0) {
        v.setTextColor(fg)
        v.background = ripple(round(c, bg, 26, stroke, if (stroke != 0) 2 else 0))
    }

    /** Full-width pill button. */
    fun button(c: Context, label: String, bg: Int, fg: Int, stroke: Int = 0, onClick: () -> Unit) =
        text(c, label, 16f, fg, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(c, 52)
            setPadding(dp(c, 20), 0, dp(c, 20), 0)
            isClickable = true
            styleButton(c, this, bg, fg, stroke)
            setOnClickListener { onClick() }
        }

    /** Quiet text-only action. */
    fun link(c: Context, label: String, color: Int, onClick: () -> Unit) =
        text(c, label, 15f, color, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(c, 40)
            setPadding(dp(c, 12), 0, dp(c, 12), 0)
            isClickable = true
            background = ripple(round(c, 0x00000000, 12))
            setOnClickListener { onClick() }
        }
}
