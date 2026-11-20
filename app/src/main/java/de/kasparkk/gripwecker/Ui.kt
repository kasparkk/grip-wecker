package de.kasparkk.gripwecker

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Kleine Helfer fuer die programmatische Oberflaeche (keine Fremd-Bibliotheken). */
class Ui(private val c: Context) {
    val night = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val bg = if (night) 0xFF1F1F1E.toInt() else 0xFFF6F5F1.toInt()
    val card = if (night) 0xFF2A2A28.toInt() else Color.WHITE
    val text = if (night) 0xFFF1EFE8.toInt() else 0xFF2C2C2A.toInt()
    val sub = if (night) 0xFFB4B2A9.toInt() else 0xFF5F5E5A.toInt()
    val line = if (night) 0x33FFFFFF else 0x22000000
    val accent = if (night) 0xFF85B7EB.toInt() else 0xFF185FA5.toInt()
    val accentBg = if (night) 0xFF0C447C.toInt() else 0xFFE6F1FB.toInt()
    val okText = if (night) 0xFF97C459.toInt() else 0xFF3B6D11.toInt()
    val okBg = if (night) 0xFF27500A.toInt() else 0xFFEAF3DE.toInt()
    val warnText = if (night) 0xFFEF9F27.toInt() else 0xFF854F0B.toInt()
    val warnBg = if (night) 0xFF633806.toInt() else 0xFFFAEEDA.toInt()
    val dangerText = if (night) 0xFFF09595.toInt() else 0xFFA32D2D.toInt()
    val dangerBg = if (night) 0xFF791F1F.toInt() else 0xFFFCEBEB.toInt()

    fun dp(v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    fun rounded(fill: Int, radius: Int = 14, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    fun oval(fill: Int, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    fun tv(s: String, size: Float = 15f, color: Int = text, bold: Boolean = false): TextView = TextView(c).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun col(): LinearLayout = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

    fun row(): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun cardBox(): LinearLayout = col().apply {
        background = rounded(card, 14, line)
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    fun button(s: String, onClick: () -> Unit): Button = Button(c).apply {
        text = s
        isAllCaps = false
        textSize = 15f
        setTextColor(this@Ui.text)
        background = rounded(card, 12, if (night) 0x55FFFFFF else 0x44000000)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    fun chip(s: String, fg: Int, bgc: Int): TextView = tv(s, 12f, fg).apply {
        background = rounded(bgc, 8)
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }

    fun lp(w: Int = LinearLayout.LayoutParams.MATCH_PARENT, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT,
           top: Int = 0, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }

    fun spacer(): View = View(c).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }

    /** Wochentags-Kreise Mo..So, optional antippbar. */
    fun dayRow(days: BooleanArray, editable: Boolean): LinearLayout {
        val names = arrayOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
        val r = row()
        for (i in 0..6) {
            val t = tv(names[i], 12f)
            t.gravity = Gravity.CENTER
            fun paint() {
                if (days[i]) { t.background = oval(accentBg, accent); t.setTextColor(accent) }
                else { t.background = oval(Color.TRANSPARENT, line); t.setTextColor(sub) }
            }
            paint()
            if (editable) t.setOnClickListener { days[i] = !days[i]; paint() }
            val s = if (editable) 38 else 30
            r.addView(t, LinearLayout.LayoutParams(dp(s), dp(s)).apply { rightMargin = dp(5) })
        }
        return r
    }

    /** Farbbalken Rot | Orange | Blau. */
    fun riseBar(): LinearLayout {
        val r = row()
        for (col in intArrayOf(0xFFE24B4A.toInt(), 0xFFEF9F27.toInt(), 0xFF378ADD.toInt())) {
            r.addView(View(c).apply { setBackgroundColor(col) }, LinearLayout.LayoutParams(0, dp(10), 1f))
        }
        r.clipToOutline = true
        r.background = rounded(Color.TRANSPARENT, 5)
        r.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        return r
    }

    fun gripColor(c: Int): Int = when (c) {
        Grip.RED -> 0xFFE24B4A.toInt()
        Grip.ORANGE -> 0xFFEF9F27.toInt()
        Grip.BLUE -> 0xFF378ADD.toInt()
        Grip.PINK -> 0xFFD4537E.toInt()
        else -> 0xFF888780.toInt()
    }
}
