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
import androidx.core.graphics.ColorUtils

/** Colors, type and small view helpers shared by both screens. */
object Ui {

    // Palette
    val PAGE = 0xFF0E0F1A.toInt()
    val CARD = 0xFF181A2B.toInt()
    val RAISED = 0xFF222640.toInt()
    val TEXT = 0xFFF1F2FA.toInt()
    val MUTED = 0xFF8C91B3.toInt()
    val LINE = 0xFF2C3050.toInt()
    val ACCENT = 0xFF7C5CFF.toInt()
    val GREEN = 0xFF3DDC97.toInt()
    val AMBER = 0xFFFFB020.toInt()
    val RED = 0xFFFF4D5E.toInt()
    val GREY = 0xFF5B6080.toInt()
    val ALARM_BG = 0xFF14050A.toInt()

    const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    fun mix(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    fun onColor(bg: Int): Int = if (ColorUtils.calculateLuminance(bg) > 0.45) PAGE else 0xFFFFFFFF.toInt()

    fun round(c: Context, fill: Int, radiusDp: Int, stroke: Int = 0, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {

            setColor(fill)
            cornerRadius = dp(c, radiusDp).toFloat()

            if (strokeDp > 0) setStroke(dp(c, strokeDp), stroke)

        }

    fun circle(fill: Int, stroke: Int = 0, strokeWidth: Int = 0): GradientDrawable =
        GradientDrawable().apply {

            shape = GradientDrawable.OVAL
            setColor(fill)

            if (strokeWidth > 0) setStroke(strokeWidth, stroke)

        }

    fun gradient(c: Context, from: Int, to: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(from, to)).apply {

            cornerRadius = dp(c, radiusDp).toFloat()

        }

    fun ripple(base: Drawable, color: Int = 0x22FFFFFF): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(color), base, null)

    fun lp(c: Context, w: Int, h: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h).apply { setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b)) }

    fun text(c: Context, s: String, sp: Float, color: Int, medium: Boolean = false): TextView =
        TextView(c).apply {

            text = s
            textSize = sp
            setTextColor(color)
            typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)

        }

    fun heavy(c: Context, s: String, sp: Float, color: Int): TextView =
        text(c, s, sp, color).apply {

            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)

        }

    fun caps(c: Context, s: String, sp: Float, color: Int): TextView =
        text(c, s.uppercase(), sp, color, true).apply {

            letterSpacing = 0.14f

        }

    fun card(c: Context): LinearLayout = LinearLayout(c).apply {

        orientation = LinearLayout.VERTICAL
        background = round(c, CARD, 22, LINE, 1)
        setPadding(dp(c, 18), dp(c, 16), dp(c, 18), dp(c, 16))

    }

    fun styleButton(c: Context, v: TextView, bg: Int, fg: Int, stroke: Int = 0, radius: Int = 26) {

        v.setTextColor(fg)
        v.background = ripple(round(c, bg, radius, stroke, if (stroke != 0) 2 else 0))

    }

    /** Full-width pill button. */
    fun button(c: Context, label: String, bg: Int, fg: Int, stroke: Int = 0, onClick: () -> Unit): TextView =
        text(c, label, 16f, fg, true).apply {

            gravity = Gravity.CENTER
            minHeight = dp(c, 52)
            setPadding(dp(c, 20), 0, dp(c, 20), 0)
            isClickable = true
            styleButton(c, this, bg, fg, stroke)
            setOnClickListener { onClick() }

        }

    /** Quiet text-only action. */
    fun link(c: Context, label: String, color: Int, onClick: () -> Unit): TextView =
        text(c, label, 15f, color, true).apply {

            gravity = Gravity.CENTER
            minHeight = dp(c, 40)
            setPadding(dp(c, 12), 0, dp(c, 12), 0)
            isClickable = true
            background = ripple(round(c, 0x00000000, 12))
            setOnClickListener { onClick() }

        }

}
