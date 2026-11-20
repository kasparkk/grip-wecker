package de.kasparkk.gripwecker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import kotlin.math.roundToInt

/**
 * Laeuft ab Beginn des Sonnenaufgangs bis Aus/Schlummern:
 * haelt die BLE-Verbindung, faehrt das Licht hoch, spielt den Weckton.
 */
class AlarmService : Service() {

    companion object {
        const val ACT_START = "start"
        const val ACT_TEST = "test"
        const val ACT_SNOOZE = "snooze"
        const val ACT_STOP = "stop"
        const val NID = 7
        const val CH_RISE = "rise"
        const val CH_RING = "ring"

        @Volatile var phase = "idle" // idle, rising, ringing
        @Volatile var ringAt = 0L
        @Volatile var pct = 0
        @Volatile var color = 0
        @Volatile var ble = ""
        @Volatile var test = false

        fun channels(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_RISE, "Sonnenaufgang", NotificationManager.IMPORTANCE_LOW))
            val ring = NotificationChannel(CH_RING, "Wecker klingelt", NotificationManager.IMPORTANCE_HIGH)
            ring.setSound(null, null)
            ring.enableVibration(false)
            ring.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ring)
        }

        fun hasBt(c: Context): Boolean =
            Build.VERSION.SDK_INT < 31 ||
                c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private val h = Handler(Looper.getMainLooper())
    private var grip: GripBle? = null
    private var player: MediaPlayer? = null
    private var wake: PowerManager.WakeLock? = null
    private var riseStart = 0L
    private var ringStart = 0L
    private var alarmId = -1L
    private var snoozeMin = 9
    private var sound: String? = null
    private var stopToken = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_START, ACT_TEST -> start(intent)
            ACT_SNOOZE -> snooze()
            ACT_STOP -> stopAlarm()
            else -> if (phase == "idle") stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(i: Intent) {
        val now = System.currentTimeMillis()
        val isTest = i.action == ACT_TEST
        val r = if (isTest) now + 60_000 else i.getLongExtra(Scheduler.EXTRA_RING, 0L)
        goForeground(notif(false))

        if (!isTest && (r == 0L || now > r + 30 * 60_000)) {
            // verpasst (Handy war aus o. ae.)
            Scheduler.reschedule(this)
            finish()
            return
        }
        if (phase != "idle" && r == ringAt && !isTest) return // gleicher Wecker laeuft schon

        stopToken++
        stopSound()
        h.removeCallbacks(tick)
        test = isTest
        ringAt = r
        riseStart = now
        alarmId = if (isTest) -1L else i.getLongExtra(Scheduler.EXTRA_ID, -1L)
        val a = if (isTest) null else Store.alarm(this, alarmId)
        snoozeMin = a?.snoozeMin ?: 9
        sound = a?.sound
        if (i.getBooleanExtra(Scheduler.EXTRA_SNOOZE, false)) Store.clearSnooze(this)

        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gripwecker:alarm")
        }
        wake?.let { if (!it.isHeld) it.acquire((r - now) + 25 * 60_000L) }

        connectGrip()
        if (r - now < 5_000) {
            ring()
        } else {
            phase = "rising"
            grip?.put(Grip.F_THEME, Grip.THEME_FREEZE)
            applyLight(Grip.RED, 3)
            getSystemService(NotificationManager::class.java).notify(NID, notif(false))
        }
        h.post(tick)
    }

    private fun connectGrip() {
        val addr = Store.gripAddress(this)
        if (addr == null) { ble = "kein Grip gewählt"; return }
        if (!hasBt(this)) { ble = "Bluetooth-Berechtigung fehlt"; return }
        if (grip == null) {
            grip = GripBle(this, addr, true) { s -> ble = s }
            grip?.connect()
        }
    }

    private fun applyLight(c: Int, p: Int) {
        color = c
        pct = p
        grip?.put(Grip.F_COLOR, c)
        grip?.put(Grip.F_BRIGHT, Grip.on(p))
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            when (phase) {
                "rising" -> {
                    if (now >= ringAt) {
                        ring()
                    } else {
                        val p = ((now - riseStart).toDouble() / (ringAt - riseStart)).coerceIn(0.0, 1.0)
                        applyLight(Grip.riseColor(p), (3 + 97 * p).roundToInt())
                    }
                }
                "ringing" -> {
                    val v = (0.15f + 0.85f * ((now - ringStart) / 60_000f)).coerceAtMost(1f)
                    try { player?.setVolume(v, v) } catch (_: Exception) {}
                    if (now - ringStart > 20 * 60_000) {
                        // nach 20 min von selbst aus, Licht bleibt an
                        stopAlarm()
                        return
                    }
                }
                else -> return
            }
            h.postDelayed(this, 1000)
        }
    }

    private fun ring() {
        phase = "ringing"
        ringStart = System.currentTimeMillis()
        applyLight(Grip.BLUE, 100)
        playSound()
        getSystemService(NotificationManager::class.java).notify(NID, notif(true))
        try {
            startActivity(Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            // Hintergrundstart blockiert, Vollbild-Benachrichtigung uebernimmt
        }
        if (!test) {
            // Einmal-Wecker abschalten, naechsten Termin stellen
            val list = Store.alarms(this)
            list.firstOrNull { it.id == alarmId && !it.days.any { d -> d } }?.let {
                it.on = false
                Store.saveAlarms(this, list)
            }
            Scheduler.reschedule(this)
        }
    }

    private fun playSound() {
        stopSound()
        if (sound == "none") return
        val uris = listOfNotNull(
            sound?.takeIf { it != "none" }?.let { Uri.parse(it) },
            RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM),
            Settings.System.DEFAULT_ALARM_ALERT_URI,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
        )
        for (u in uris) {
            try {
                val mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                mp.setDataSource(this, u)
                mp.isLooping = true
                mp.setVolume(0.15f, 0.15f)
                mp.prepare()
                mp.start()
                player = mp
                return
            } catch (_: Exception) {
            }
        }
    }

    private fun stopSound() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    private fun snooze() {
        if (phase == "idle") { finish(); return }
        stopSound()
        if (!test) Store.setSnooze(this, System.currentTimeMillis() + snoozeMin * 60_000L, alarmId)
        else Store.setSnooze(this, System.currentTimeMillis() + 60_000L, -1L)
        Scheduler.reschedule(this)
        finish()
    }

    private fun stopAlarm() {
        stopSound()
        Store.clearSnooze(this)
        // Aus: Theme Freeze, Licht bleibt an
        grip?.put(Grip.F_THEME, Grip.THEME_FREEZE)
        Scheduler.reschedule(this)
        finish()
    }

    private fun finish() {
        phase = "idle"
        h.removeCallbacks(tick)
        val token = ++stopToken
        val g = grip
        // kurz warten, damit die letzten Pakete rausgehen
        val delay = if (g != null && g.ready && g.pendingCount() > 0) 1200L else 300L
        h.postDelayed({
            if (token != stopToken) return@postDelayed
            g?.close()
            if (grip === g) grip = null
            ble = ""
            wake?.let { if (it.isHeld) it.release() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, delay)
    }

    override fun onDestroy() {
        stopSound()
        grip?.close()
        grip = null
        wake?.let { if (it.isHeld) it.release() }
        phase = "idle"
        super.onDestroy()
    }

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            if (hasBt(this)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            try {
                startForeground(NID, n, type)
            } catch (e: Exception) {
                startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            }
        } else {
            startForeground(NID, n)
        }
    }

    private fun notif(ringing: Boolean): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val ringPi = PendingIntent.getActivity(
            this, 2,
            Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags
        )
        val snoozePi = PendingIntent.getService(this, 3, Intent(this, AlarmService::class.java).setAction(ACT_SNOOZE), flags)
        val stopPi = PendingIntent.getService(this, 4, Intent(this, AlarmService::class.java).setAction(ACT_STOP), flags)
        val icon = Icon.createWithResource(this, R.drawable.ic_sun)
        val b = Notification.Builder(this, if (ringing) CH_RING else CH_RISE)
            .setSmallIcon(R.drawable.ic_sun)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(ringPi)
            .addAction(Notification.Action.Builder(icon, "Schlummern", snoozePi).build())
            .addAction(Notification.Action.Builder(icon, "Aus", stopPi).build())
        if (ringing) {
            b.setContentTitle("Wecker " + hhmm(ringAt))
                .setContentText("Aufstehen")
                .setFullScreenIntent(ringPi, true)
        } else {
            b.setContentTitle(if (test) "Test: Sonnenaufgang" else "Sonnenaufgang läuft")
                .setContentText("Wecker um " + hhmm(ringAt))
        }
        return b.build()
    }
}

fun hhmm(t: Long): String {
    val z = java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault())
    return "%02d:%02d".format(z.hour, z.minute)
}
