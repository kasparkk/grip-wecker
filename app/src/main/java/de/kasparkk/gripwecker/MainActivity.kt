package de.kasparkk.gripwecker

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * Die Oberflaeche ist die Web-App (assets/web/index.html, Kopie von JBL-grip-light-control).
 * Web Bluetooth gibt es in der WebView nicht, deshalb leitet bridge.js an diese Klasse weiter.
 * Der Wecker laeuft nativ (AlarmService), android.js haengt das Wecker-Formular daran.
 */
@SuppressLint("MissingPermission", "SetJavaScriptEnabled")
class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var web: WebView
    private var grip: GripBle? = null
    private var pendingMic: PermissionRequest? = null
    private var pendingSound: String? = null

    companion object {
        const val ALARM_ID = 1L
        const val REQ_BT = 1
        const val REQ_NOTIF = 2
        const val REQ_MIC = 3
        const val REQ_SOUND = 10
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlarmService.channels(this)
        web = WebView(this)
        web.setBackgroundColor(Color.WHITE)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.allowFileAccess = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme == "http" || u.scheme == "https") {
                    try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                    return true
                }
                return false
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        if (has(Manifest.permission.RECORD_AUDIO)) {
                            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                        } else {
                            pendingMic = request
                            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
                        }
                    } else {
                        request.deny()
                    }
                }
            }
        }
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/web/index.html")

        // Beim ersten Start gleich die wichtigsten Berechtigungen holen
        val need = (btPerms() + notifPerms()).filter { !has(it) }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ_BT)
    }

    override fun onResume() {
        super.onResume()
        Scheduler.reschedule(this)
        js("window.__gw&&__gw.refresh()")
    }

    override fun onDestroy() {
        grip?.close()
        grip = null
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    private fun js(code: String) {
        h.post { try { web.evaluateJavascript(code, null) } catch (_: Exception) {} }
    }

    private fun q(s: String?): String = if (s == null) "null" else JSONObject.quote(s)

    // ---------- Berechtigungen ----------

    private fun btPerms(): List<String> =
        if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun notifPerms(): List<String> =
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            val r = pendingMic
            pendingMic = null
            if (r != null) {
                if (has(Manifest.permission.RECORD_AUDIO)) r.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else r.deny()
            }
        }
        js("window.__gw&&__gw.refresh()")
    }

    private fun setupItems(): JSONArray {
        val a = JSONArray()
        fun add(key: String, text: String, btn: String) = a.put(JSONObject().put("key", key).put("text", text).put("btn", btn))
        if (!btPerms().all { has(it) }) add("bt", "Bluetooth-Zugriff für den Grip", "Erlauben")
        if (!notifPerms().all { has(it) }) add("notif", "Benachrichtigungen für den Wecker", "Erlauben")
        if (Store.gripAddress(this) == null) add("grip", "Grip für den Wecker wählen", "Suchen")
        if (!Scheduler.canExact(this)) add("exact", "Exakte Wecker erlauben", "Öffnen")
        if (Build.VERSION.SDK_INT >= 34 && !getSystemService(NotificationManager::class.java).canUseFullScreenIntent())
            add("full", "Weckbildschirm über dem Sperrbildschirm", "Öffnen")
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
            add("battery", "Akku-Optimierung aus (empfohlen)", "Öffnen")
        return a
    }

    @SuppressLint("BatteryLife")
    private fun fix(key: String) {
        val pkg = Uri.parse("package:$packageName")
        when (key) {
            "bt" -> requestPermissions(btPerms().toTypedArray(), REQ_BT)
            "notif" -> requestPermissions(notifPerms().toTypedArray(), REQ_NOTIF)
            "grip" -> pickGrip(fromWeb = false)
            "exact" -> if (Build.VERSION.SDK_INT >= 31) open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
            "full" -> if (Build.VERSION.SDK_INT >= 34) open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
            "battery" -> open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
        }
    }

    private fun open(i: Intent) {
        try { startActivity(i) } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
        }
    }

    // ---------- Grip suchen ----------

    private fun pickGrip(fromWeb: Boolean) {
        fun fail(msg: String) { toast(msg); if (fromWeb) js("__gb.pickFailed(${q(msg)})") }
        if (!btPerms().all { has(it) }) {
            requestPermissions(btPerms().toTypedArray(), REQ_BT)
            fail("Erst Bluetooth erlauben, dann nochmal")
            return
        }
        val ad = getSystemService(BluetoothManager::class.java)?.adapter
        if (ad == null || !ad.isEnabled) { fail("Bitte Bluetooth einschalten"); return }
        val labels = ArrayList<String>()
        val addrs = ArrayList<String>()
        val names = ArrayList<String>()
        val tags = ArrayList<String>()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        fun add(name: String, addr: String, tag: String) {
            if (addrs.contains(addr)) return
            val first = name.contains("grip", true) && tag == "BLE"
            val idx = if (first) 0 else labels.size
            labels.add(idx, "$name  ($tag)")
            addrs.add(idx, addr)
            names.add(idx, name)
            tags.add(idx, tag)
            adapter.notifyDataSetChanged()
        }
        try {
            ad.bondedDevices?.forEach { d ->
                val n = d.name ?: return@forEach
                if (n.contains("jbl", true) || n.contains("grip", true)) add(n, d.address, "gekoppelt, klassisch")
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
        var chosen = false
        val dlg = AlertDialog.Builder(this)
            .setTitle("Grip wählen (suche …)")
            .setAdapter(adapter) { _, which ->
                chosen = true
                // Wie Chrome: die BLE-Adresse aus dem Scan nehmen, nicht die klassische Kopplungsadresse
                var addr = addrs[which]
                if (tags[which] != "BLE") {
                    val i = names.indices.firstOrNull { tags[it] == "BLE" && names[it] == names[which] }
                    if (i != null) addr = addrs[i]
                }
                Store.setGrip(this, addr, names[which])
                if (fromWeb) js("__gb.picked(${q(addr)},${q(names[which])})")
                else toast("${names[which]} gespeichert")
                js("window.__gw&&__gw.refresh()")
            }
            .setNegativeButton("Abbrechen", null)
            .create()
        dlg.setOnDismissListener {
            try { scanner?.stopScan(cb) } catch (_: Exception) {}
            if (!chosen && fromWeb) js("__gb.pickFailed('Abgebrochen')")
        }
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

    // ---------- Weckton ----------

    private fun pickSound() {
        val cur = Store.alarm(this, ALARM_ID)?.sound?.takeIf { it != "none" }
        val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Weckton")
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                (pendingSound ?: cur)?.let { Uri.parse(it) } ?: Settings.System.DEFAULT_ALARM_ALERT_URI)
        try { startActivityForResult(i, REQ_SOUND) } catch (e: Exception) { toast("Keine Tonauswahl verfügbar") }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SOUND || resultCode != RESULT_OK) return
        val u: Uri? = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (u == null || u == Settings.System.DEFAULT_ALARM_ALERT_URI) {
            pendingSound = null
            js("__gw.soundPicked(null)")
        } else {
            pendingSound = u.toString()
            js("__gw.soundPicked(${q(soundName(pendingSound))})")
        }
    }

    private fun soundName(s: String?): String {
        if (s == null || s == "none") return "Handy-Weckton"
        return try {
            RingtoneManager.getRingtone(this, Uri.parse(s))?.getTitle(this) ?: "Eigener Ton"
        } catch (e: Exception) { "Eigener Ton" }
    }

    // ---------- Status-Texte ----------

    private fun statusJson(): String {
        val now = System.currentTimeMillis()
        val phase = AlarmService.phase
        val text = when (phase) {
            "rising" -> "Sonnenaufgang läuft · ${Grip.colorName(AlarmService.color)} ${AlarmService.pct} % · Wecker ${hhmm(AlarmService.ringAt)}"
            "ringing" -> "Klingelt"
            else -> {
                val n = Scheduler.next(this, now)
                when {
                    n == null -> "Aus"
                    n.snooze -> "Schlummert bis ${hhmm(n.ringAt)}"
                    else -> "Nächster: ${dayName(n.ringAt)} ${hhmm(n.ringAt)} · Licht ab ${hhmm(n.start)}"
                }
            }
        }
        val gripLine = when {
            phase != "idle" -> "Wecker-Grip: ${AlarmService.ble}"
            Store.gripName(this) != null -> "Wecker nutzt: ${Store.gripName(this)}"
            else -> "Kein Grip für den Wecker gewählt"
        }
        val others = Store.alarms(this).count { it.id != ALARM_ID && it.on }
        val line = gripLine + if (others > 0) " · $others weitere Wecker aktiv" else ""
        return JSONObject().put("phase", phase).put("text", text).put("line", line).toString()
    }

    private fun dayName(t: Long): String {
        val d = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).dayOfWeek.value
        return arrayOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")[d - 1]
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- Bruecke fuer die Web-App ----------

    inner class Bridge {
        @JavascriptInterface
        fun savedDevice(): String {
            val a = Store.gripAddress(this@MainActivity) ?: return ""
            return JSONObject().put("id", a).put("name", Store.gripName(this@MainActivity) ?: "Grip").toString()
        }

        @JavascriptInterface
        fun forgetDevice() { Store.forgetGrip(this@MainActivity) }

        @JavascriptInterface
        fun pickDevice() { h.post { pickGrip(fromWeb = true) } }

        @JavascriptInterface
        fun connect(id: String) {
            h.post {
                grip?.close()
                grip = null
                if (!btPerms().all { has(it) }) {
                    requestPermissions(btPerms().toTypedArray(), REQ_BT)
                    js("__gb.state('Bluetooth-Berechtigung fehlt')")
                    return@post
                }
                val g = GripBle(this@MainActivity, id, false) { s ->
                    js("log(${q("BLE: " + s)})")
                    when {
                        s == "verbunden" -> js("__gb.state('connected')")
                        s.startsWith("verbinde") || s.startsWith("verbunden,") -> {}
                        else -> js("__gb.state(${q(s)})")
                    }
                }
                g.onLog = { m -> js("log(${q("BLE -> " + m)})") }
                grip = g
                js("log(${q("BLE: verbinde mit $id")})")
                g.connect()
            }
        }

        @JavascriptInterface
        fun disconnect() { h.post { js("log('BLE: getrennt (von der Seite)')"); grip?.close(); grip = null } }

        @JavascriptInterface
        fun write(fid: Int, v: Int) {
            h.post {
                val g = grip
                if (g == null) js("log('BLE: keine native Verbindung, Befehl verworfen')") else g.put(fid, v, true)
            }
        }

        @JavascriptInterface
        fun setup(): String = setupItems().toString()

        @JavascriptInterface
        fun fix(key: String) { h.post { this@MainActivity.fix(key) } }

        @JavascriptInterface
        fun getAlarm(): String {
            val a = Store.alarm(this@MainActivity, ALARM_ID)
                ?: Alarm(ALARM_ID, 7, 30, BooleanArray(7), false, 20, 9, null)
            val days = JSONArray()
            a.days.forEach { days.put(if (it) 1 else 0) }
            val sound = when (a.sound) { null -> "phone"; "none" -> "none"; else -> "custom" }
            return JSONObject()
                .put("on", a.on)
                .put("time", "%02d:%02d".format(a.hour, a.minute))
                .put("days", days)
                .put("rise", a.riseMin)
                .put("sound", sound)
                .put("soundName", if (sound == "custom") soundName(a.sound) else JSONObject.NULL)
                .toString()
        }

        @JavascriptInterface
        fun saveAlarm(json: String): String {
            val c = try { JSONObject(json) } catch (e: Exception) { return "Wecker: ungültige Daten" }
            val ctx = this@MainActivity
            val list = Store.alarms(ctx)
            val old = list.firstOrNull { it.id == ALARM_ID }
            val a = old?.copy() ?: Alarm(ALARM_ID)
            val t = c.optString("time", "07:30").split(":")
            a.hour = t.getOrNull(0)?.toIntOrNull() ?: 7
            a.minute = t.getOrNull(1)?.toIntOrNull() ?: 30
            a.on = c.optBoolean("on", false)
            val d = c.optJSONArray("days")
            a.days = BooleanArray(7) { i -> (d?.optInt(i, 0) ?: 0) != 0 }
            a.riseMin = c.optInt("rise", 20)
            a.sound = when (c.optString("sound", "phone")) {
                "none" -> "none"
                "custom" -> pendingSound ?: old?.sound?.takeIf { it != "none" }
                else -> null
            }
            pendingSound = null
            list.removeAll { it.id == ALARM_ID }
            list.add(a)
            Store.saveAlarms(ctx, list)
            Scheduler.reschedule(ctx)
            if (!a.on) return "Wecker gespeichert (aus)"
            val r = a.nextRing(System.currentTimeMillis()) ?: return "Wecker gespeichert"
            val m = ((r - System.currentTimeMillis()) / 60_000).toInt()
            val inText = if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
            val warn = if (!Scheduler.canExact(ctx)) " · ACHTUNG: exakte Wecker nicht erlaubt (Einrichtung oben)" else ""
            return "Wecker gespeichert, klingelt in $inText$warn"
        }

        @JavascriptInterface
        fun test() {
            h.post {
                if (Store.gripAddress(this@MainActivity) == null) toast("Kein Grip gewählt, Test nur mit Ton")
                startForegroundService(Intent(this@MainActivity, AlarmService::class.java).setAction(AlarmService.ACT_TEST))
            }
        }

        @JavascriptInterface
        fun stopAlarm() { h.post { startService(Intent(this@MainActivity, AlarmService::class.java).setAction(AlarmService.ACT_STOP)) } }

        @JavascriptInterface
        fun snooze() { h.post { startService(Intent(this@MainActivity, AlarmService::class.java).setAction(AlarmService.ACT_SNOOZE)) } }

        @JavascriptInterface
        fun status(): String = statusJson()

        @JavascriptInterface
        fun pickSound() { h.post { this@MainActivity.pickSound() } }
    }
}
