package de.kasparkk.gripwecker

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.ZoneId

@SuppressLint("MissingPermission", "UseSwitchCompatOrMaterialCode", "SetTextI18n")
class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var ui: Ui
    private lateinit var content: LinearLayout
    private var editing: Alarm? = null
    private var soundBtn: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        AlarmService.channels(this)
        content = ui.col().apply { setPadding(ui.dp(16), ui.dp(20), ui.dp(16), ui.dp(32)) }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(ui.bg)
            addView(content)
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        Scheduler.reschedule(this)
        refresh()
    }

    override fun onPause() {
        h.removeCallbacks(loop)
        super.onPause()
    }

    private val loop = Runnable { refresh() }

    private fun refresh() {
        h.removeCallbacks(loop)
        build()
        h.postDelayed(loop, if (AlarmService.phase != "idle") 1000 else 20_000)
    }

    // ---------- Aufbau ----------

    private fun build() {
        content.removeAllViews()

        // Kopfzeile
        val head = ui.row()
        head.addView(ui.tv("Grip-Wecker", 22f, bold = true))
        head.addView(ui.spacer())
        val name = Store.gripName(this)
        val chip = when {
            AlarmService.phase != "idle" && AlarmService.ble == "verbunden" -> ui.chip("Grip verbunden", ui.okText, ui.okBg)
            AlarmService.phase != "idle" -> ui.chip(AlarmService.ble.ifEmpty { "…" }, ui.warnText, ui.warnBg)
            name != null -> ui.chip(name, ui.accent, ui.accentBg)
            else -> ui.chip("Kein Grip gewählt", ui.warnText, ui.warnBg)
        }
        head.addView(chip)
        content.addView(head)

        if (AlarmService.phase != "idle") content.addView(activeCard(), ui.lp(top = 14))

        val setup = setupItems()
        if (setup.isNotEmpty()) content.addView(setupCard(setup), ui.lp(top = 14))

        val alarms = Store.alarms(this)
        if (alarms.isEmpty()) {
            content.addView(ui.tv("Noch kein Wecker. Leg unten einen an.", 15f, ui.sub), ui.lp(top = 20))
        }
        for (a in alarms) content.addView(alarmCard(a), ui.lp(top = 12))

        val btns = ui.row()
        btns.addView(ui.button("Test (60 s)") { startTest() }, LinearLayout.LayoutParams(0, ui.dp(52), 1f).apply { rightMargin = ui.dp(8) })
        btns.addView(ui.button("+ Wecker") { edit(null) }, LinearLayout.LayoutParams(0, ui.dp(52), 1f))
        content.addView(btns, ui.lp(top = 16))
        content.addView(ui.button("Grip wählen") { pickGrip() }, ui.lp(h = ui.dp(48), top = 8))

        val n = Scheduler.next(this, System.currentTimeMillis())
        val foot = if (n == null) "Kein Wecker aktiv"
        else "Nächster Wecker: ${dayName(n.ringAt)} ${hhmm(n.ringAt)}" +
            (if (n.start < n.ringAt) ", Licht ab ${hhmm(n.start)}" else " (Schlummern)")
        content.addView(ui.tv(foot, 13f, ui.sub), ui.lp(top = 16))
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }
        content.addView(ui.tv("Version $ver", 12f, ui.sub), ui.lp(top = 4))
    }

    private fun activeCard(): LinearLayout {
        val c = ui.cardBox()
        val ringing = AlarmService.phase == "ringing"
        val r = ui.row()
        val dot = TextView(this).apply { background = ui.oval(ui.gripColor(AlarmService.color)) }
        r.addView(dot, LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { rightMargin = ui.dp(10) })
        r.addView(ui.tv(
            (if (ringing) "Klingelt" else if (AlarmService.test) "Test läuft" else "Sonnenaufgang läuft") +
                " · ${Grip.colorName(AlarmService.color)} ${AlarmService.pct} %", 15f, bold = true))
        c.addView(r)
        c.addView(ui.tv("Wecker um ${hhmm(AlarmService.ringAt)}", 13f, ui.sub), ui.lp(top = 4))
        val b = ui.row()
        b.addView(ui.button("Schlummern") { svc(AlarmService.ACT_SNOOZE) }, LinearLayout.LayoutParams(0, ui.dp(48), 1f).apply { rightMargin = ui.dp(8) })
        b.addView(ui.button("Aus") { svc(AlarmService.ACT_STOP) }.apply {
            background = ui.rounded(ui.dangerBg, 12, ui.dangerText)
            setTextColor(ui.dangerText)
        }, LinearLayout.LayoutParams(0, ui.dp(48), 1f))
        c.addView(b, ui.lp(top = 12))
        return c
    }

    private fun alarmCard(a: Alarm): LinearLayout {
        val c = ui.cardBox()
        val top = ui.row()
        top.addView(ui.tv("%02d:%02d".format(a.hour, a.minute), 36f, if (a.on) ui.text else ui.sub, bold = true))
        top.addView(ui.spacer())
        val sw = Switch(this).apply {
            isChecked = a.on
            setOnCheckedChangeListener { _, on ->
                val list = Store.alarms(this@MainActivity)
                list.firstOrNull { it.id == a.id }?.on = on
                Store.saveAlarms(this@MainActivity, list)
                Scheduler.reschedule(this@MainActivity)
                h.post { refresh() }
            }
        }
        top.addView(sw)
        c.addView(top)

        val now = System.currentTimeMillis()
        val r = a.nextRing(now)
        val subText = when {
            !a.on || r == null -> "aus" + if (a.days.none { it }) " · einmalig" else ""
            else -> "Sonnenaufgang ab ${hhmm(r - a.riseMin * 60_000L)} · in ${inText(r - now)}" +
                if (a.days.none { it }) " · einmalig" else ""
        }
        c.addView(ui.tv(subText, 13f, ui.sub), ui.lp(top = 2))

        if (a.on) {
            c.addView(ui.dayRow(a.days, false), ui.lp(top = 10))
            c.addView(ui.riseBar(), ui.lp(h = ui.dp(10), top = 12))
            val labels = ui.row()
            labels.addView(ui.tv("Rot 3 %", 12f, ui.sub))
            labels.addView(ui.spacer())
            labels.addView(ui.tv("Orange", 12f, ui.sub))
            labels.addView(ui.spacer())
            labels.addView(ui.tv("Blau 100 %", 12f, ui.sub))
            c.addView(labels, ui.lp(top = 4))
            c.addView(detail("Vorlauf", "${a.riseMin} min"), ui.lp(top = 8))
            c.addView(detail("Weckton", soundName(a.sound)))
            c.addView(detail("Schlummern", "${a.snoozeMin} min"))
        } else {
            c.addView(ui.tv(daysText(a.days), 13f, ui.sub), ui.lp(top = 2))
        }

        c.setOnClickListener { edit(a) }
        c.setOnLongClickListener { confirmDelete(a); true }
        return c
    }

    private fun detail(k: String, v: String): LinearLayout {
        val r = ui.row()
        r.setPadding(0, ui.dp(7), 0, ui.dp(7))
        r.addView(ui.tv(k, 14f, ui.sub))
        r.addView(ui.spacer())
        r.addView(ui.tv(v, 14f))
        return r
    }

    // ---------- Einrichtung ----------

    private class SetupItem(val text: String, val button: String, val action: () -> Unit)

    private fun btPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun setupItems(): List<SetupItem> {
        val l = mutableListOf<SetupItem>()
        if (!btPerms().all { has(it) }) l.add(SetupItem("Bluetooth-Zugriff für den Grip", "Erlauben") {
            requestPermissions(btPerms(), 1)
        })
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS))
            l.add(SetupItem("Benachrichtigungen für den Weckbildschirm", "Erlauben") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            })
        if (Store.gripAddress(this) == null) l.add(SetupItem("Grip auswählen", "Suchen") { pickGrip() })
        if (!Scheduler.canExact(this)) l.add(SetupItem("Exakte Wecker erlauben", "Öffnen") {
            if (Build.VERSION.SDK_INT >= 31) open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        })
        if (Build.VERSION.SDK_INT >= 34 && !getSystemService(NotificationManager::class.java).canUseFullScreenIntent())
            l.add(SetupItem("Weckbildschirm über dem Sperrbildschirm", "Öffnen") {
                open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            })
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
            l.add(SetupItem("Akku-Optimierung aus (empfohlen)", "Öffnen") {
                @SuppressLint("BatteryLife")
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                open(i)
            })
        return l
    }

    private fun setupCard(items: List<SetupItem>): LinearLayout {
        val c = ui.cardBox()
        c.background = ui.rounded(ui.warnBg, 14)
        c.addView(ui.tv("Einrichtung", 15f, ui.warnText, bold = true))
        for (it in items) {
            val r = ui.row()
            r.addView(ui.tv(it.text, 14f, ui.warnText), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(ui.button(it.button) { it.action() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, ui.dp(40)))
            c.addView(r, ui.lp(top = 8))
        }
        return c
    }

    private fun open(i: Intent) {
        try { startActivity(i) } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    // ---------- Aktionen ----------

    private fun svc(action: String) {
        startService(Intent(this, AlarmService::class.java).setAction(action))
        h.postDelayed({ refresh() }, 500)
    }

    private fun startTest() {
        if (Store.gripAddress(this) == null) toast("Kein Grip gewählt, Test nur mit Ton")
        startForegroundService(Intent(this, AlarmService::class.java).setAction(AlarmService.ACT_TEST))
        h.postDelayed({ refresh() }, 500)
    }

    private fun confirmDelete(a: Alarm) {
        AlertDialog.Builder(this)
            .setMessage("Wecker %02d:%02d löschen?".format(a.hour, a.minute))
            .setPositiveButton("Löschen") { _, _ -> delete(a) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun delete(a: Alarm) {
        val list = Store.alarms(this)
        list.removeAll { it.id == a.id }
        Store.saveAlarms(this, list)
        Scheduler.reschedule(this)
        refresh()
    }

    private fun edit(orig: Alarm?) {
        val a = orig?.copy() ?: Alarm(System.currentTimeMillis())
        editing = a
        val v = ui.col().apply { setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(4)) }

        val time = ui.tv("%02d:%02d".format(a.hour, a.minute), 44f, ui.accent, bold = true).apply {
            gravity = Gravity.CENTER
            setOnClickListener {
                TimePickerDialog(this@MainActivity, { _, hh, mm ->
                    a.hour = hh; a.minute = mm
                    text = "%02d:%02d".format(hh, mm)
                }, a.hour, a.minute, true).show()
            }
        }
        v.addView(time)
        v.addView(ui.tv("Zum Ändern auf die Uhrzeit tippen", 12f, ui.sub).apply { gravity = Gravity.CENTER })

        v.addView(ui.tv("Tage (keiner = einmalig)", 13f, ui.sub), ui.lp(top = 16))
        v.addView(ui.dayRow(a.days, true), ui.lp(top = 6))

        v.addView(stepper("Vorlauf", intArrayOf(5, 10, 15, 20, 30, 45, 60), { a.riseMin }, { a.riseMin = it }), ui.lp(top = 16))
        v.addView(stepper("Schlummern", intArrayOf(5, 9, 10, 15, 20), { a.snoozeMin }, { a.snoozeMin = it }), ui.lp(top = 8))

        val sr = ui.row()
        sr.addView(ui.tv("Weckton", 15f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sb = ui.button(soundName(a.sound)) { pickSound(a) }
        soundBtn = sb
        sr.addView(sb, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, ui.dp(42)))
        v.addView(sr, ui.lp(top = 8))

        val b = AlertDialog.Builder(this)
            .setTitle(if (orig == null) "Neuer Wecker" else "Wecker bearbeiten")
            .setView(ScrollView(this).apply { addView(v) })
            .setPositiveButton("Speichern") { _, _ ->
                val list = Store.alarms(this)
                val i = list.indexOfFirst { it.id == a.id }
                a.on = true
                if (i >= 0) list[i] = a else list.add(a)
                Store.saveAlarms(this, list)
                Scheduler.reschedule(this)
                val r = a.nextRing(System.currentTimeMillis())
                if (r != null) toast("Klingelt in ${inText(r - System.currentTimeMillis())}")
                refresh()
            }
            .setNegativeButton("Abbrechen", null)
        if (orig != null) b.setNeutralButton("Löschen") { _, _ -> delete(a) }
        b.setOnDismissListener { editing = null; soundBtn = null }
        b.show()
    }

    private fun stepper(label: String, opts: IntArray, get: () -> Int, set: (Int) -> Unit): LinearLayout {
        val r = ui.row()
        r.addView(ui.tv(label, 15f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val value = ui.tv("${get()} min", 15f).apply { gravity = Gravity.CENTER }
        fun step(d: Int) {
            var i = opts.indexOfFirst { it >= get() }
            if (i < 0) i = opts.size - 1
            if (opts[i] != get() && d < 0) i += 1 // Wert lag zwischen zwei Stufen
            i = (i + d).coerceIn(0, opts.size - 1)
            set(opts[i])
            value.text = "${get()} min"
        }
        r.addView(ui.button("−") { step(-1) }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(42)))
        r.addView(value, LinearLayout.LayoutParams(ui.dp(76), LinearLayout.LayoutParams.WRAP_CONTENT))
        r.addView(ui.button("+") { step(1) }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(42)))
        return r
    }

    private fun pickSound(a: Alarm) {
        val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Weckton")
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                a.sound?.let { Uri.parse(it) } ?: Settings.System.DEFAULT_ALARM_ALERT_URI)
        try { startActivityForResult(i, 10) } catch (e: Exception) { toast("Keine Tonauswahl verfügbar") }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 10 || resultCode != RESULT_OK) return
        val u: Uri? = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        val a = editing ?: return
        a.sound = if (u == null || u == Settings.System.DEFAULT_ALARM_ALERT_URI) null else u.toString()
        soundBtn?.text = soundName(a.sound)
    }

    private fun pickGrip() {
        if (!btPerms().all { has(it) }) { requestPermissions(btPerms(), 1); return }
        val ad = getSystemService(BluetoothManager::class.java)?.adapter
        if (ad == null || !ad.isEnabled) { toast("Bitte Bluetooth einschalten"); return }
        val labels = ArrayList<String>()
        val addrs = ArrayList<String>()
        val names = ArrayList<String>()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        fun add(name: String, addr: String, tag: String) {
            if (addrs.contains(addr)) return
            val first = name.contains("grip", true) && tag == "BLE"
            val idx = if (first) 0 else labels.size
            labels.add(idx, "$name  ($tag)")
            addrs.add(idx, addr)
            names.add(idx, name)
            adapter.notifyDataSetChanged()
        }
        try {
            ad.bondedDevices?.forEach { d ->
                val n = d.name ?: return@forEach
                if (n.contains("jbl", true) || n.contains("grip", true)) add(n, d.address, "gekoppelt")
            }
        } catch (_: Exception) {}

        val scanner = ad.bluetoothLeScanner
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val n = result.device.name ?: result.scanRecord?.deviceName ?: return
                val addr = result.device.address
                runOnUiThread { add(n, addr, "BLE") }
            }
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Grip wählen (suche …)")
            .setAdapter(adapter) { _, which ->
                Store.setGrip(this, addrs[which], names[which])
                toast("${names[which]} gespeichert")
                refresh()
            }
            .setNegativeButton("Abbrechen", null)
            .create()
        dlg.setOnDismissListener { try { scanner?.stopScan(cb) } catch (_: Exception) {} }
        dlg.show()
        try {
            scanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        } catch (e: Exception) {
            toast("Suche fehlgeschlagen: ${e.message}")
        }
        h.postDelayed({
            try { scanner?.stopScan(cb) } catch (_: Exception) {}
            if (dlg.isShowing) dlg.setTitle(if (labels.isEmpty()) "Nichts gefunden. Grip an?" else "Grip wählen")
        }, 15_000)
    }

    // ---------- Text ----------

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun soundName(s: String?): String {
        if (s == null) return "Standard-Weckton"
        return try {
            RingtoneManager.getRingtone(this, Uri.parse(s))?.getTitle(this) ?: "Eigener Ton"
        } catch (e: Exception) { "Eigener Ton" }
    }

    private fun inText(ms: Long): String {
        val m = (ms / 60_000).toInt()
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }

    private fun dayName(t: Long): String {
        val d = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).dayOfWeek.value
        return arrayOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")[d - 1]
    }

    private fun daysText(days: BooleanArray): String {
        val n = arrayOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
        val sel = n.filterIndexed { i, _ -> days[i] }
        return if (sel.isEmpty()) "einmalig" else sel.joinToString(", ")
    }

    @Suppress("unused")
    private fun am() = getSystemService(AlarmManager::class.java)
}
