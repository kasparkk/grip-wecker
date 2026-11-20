package de.kasparkk.gripwecker

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Weckbildschirm, auch ueber dem Sperrbildschirm. */
class RingActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var ui: Ui
    private lateinit var sun: FrameLayout
    private lateinit var time: TextView
    private lateinit var status: TextView

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui = Ui(this)

        val root = ui.col().apply {
            gravity = Gravity.CENTER
            setBackgroundColor(ui.bg)
            setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(32))
        }
        sun = FrameLayout(this)
        val icon = ImageView(this).apply { setImageResource(R.drawable.ic_sun) }
        sun.addView(icon, FrameLayout.LayoutParams(ui.dp(44), ui.dp(44), Gravity.CENTER))
        root.addView(sun, LinearLayout.LayoutParams(ui.dp(160), ui.dp(160)))

        time = ui.tv("", 64f, bold = true).apply { gravity = Gravity.CENTER }
        root.addView(time, ui.lp(top = 24))
        status = ui.tv("", 16f, ui.sub).apply { gravity = Gravity.CENTER }
        root.addView(status, ui.lp(top = 6))

        val btns = ui.row()
        val snooze = ui.button("Schlummern") { send(AlarmService.ACT_SNOOZE) }
        val off = ui.button("Aus") { send(AlarmService.ACT_STOP) }.apply {
            background = ui.rounded(ui.dangerBg, 12, ui.dangerText)
            setTextColor(ui.dangerText)
        }
        btns.addView(snooze, LinearLayout.LayoutParams(0, ui.dp(64), 1f).apply { rightMargin = ui.dp(10) })
        btns.addView(off, LinearLayout.LayoutParams(0, ui.dp(64), 1f))
        root.addView(btns, ui.lp(top = 40))
        root.addView(ui.tv("Aus setzt den Grip auf Freeze, das Licht bleibt an", 13f, ui.sub).apply {
            gravity = Gravity.CENTER
        }, ui.lp(top = 12))

        setContentView(root)
        h.post(update)
    }

    private fun send(action: String) {
        startService(Intent(this, AlarmService::class.java).setAction(action))
        finish()
    }

    private val update = object : Runnable {
        override fun run() {
            if (AlarmService.phase == "idle") { finish(); return }
            time.text = hhmm(if (AlarmService.ringAt > 0) AlarmService.ringAt else System.currentTimeMillis())
            val c = AlarmService.color
            sun.background = ui.oval(ui.gripColor(c))
            val what = if (AlarmService.phase == "ringing") "Aufstehen" else "Sonnenaufgang läuft"
            val light = if (c != 0) "${Grip.colorName(c)} · ${AlarmService.pct} %" else ""
            status.text = listOf(light, what, AlarmService.ble).filter { it.isNotEmpty() }.joinToString(" · ")
            h.postDelayed(this, 1000)
        }
    }

    override fun onDestroy() {
        h.removeCallbacks(update)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Zurueck beendet den Wecker nicht
    }
}
