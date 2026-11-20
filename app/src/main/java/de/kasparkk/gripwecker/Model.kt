package de.kasparkk.gripwecker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** JBL Grip, Protocol 4 ueber BLE. */
object Grip {
    val SERVICE: UUID = UUID.fromString("65786365-6c70-6f69-6e74-2e636f6d0000")
    val TX: UUID = UUID.fromString("65786365-6c70-6f69-6e74-2e636f6d0002")
    val RX: UUID = UUID.fromString("65786365-6c70-6f69-6e74-2e636f6d0001")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val F_THEME = 0x0D40
    const val F_BRIGHT = 0x0D41
    const val F_COLOR = 0x0D46
    const val THEME_FREEZE = 0x8C
    const val ORANGE = 1
    const val PINK = 2
    const val BLUE = 3
    const val RED = 4

    fun packet(fid: Int, v: Int): ByteArray = byteArrayOf(
        0x00, 0xDD.toByte(), 0x02, 0x00, 0x01, 0x00, 0x05, 0x00,
        (fid and 0xFF).toByte(), ((fid shr 8) and 0xFF).toByte(), 0x01, 0x00, (v and 0xFF).toByte()
    )

    /** Helligkeit mit Licht an (0x80 | Prozent). */
    fun on(pct: Int): Int = 0x80 or pct.coerceIn(0, 100)

    fun colorName(c: Int): String = when (c) {
        ORANGE -> "Orange"; PINK -> "Pink"; BLUE -> "Blau"; RED -> "Rot"; else -> ""
    }

    /** Sonnenaufgang: Rot, dann Orange, dann Blau. */
    fun riseColor(p: Double): Int = when {
        p < 1.0 / 3 -> RED
        p < 2.0 / 3 -> ORANGE
        else -> BLUE
    }
}

class Alarm(
    var id: Long,
    var hour: Int = 6,
    var minute: Int = 45,
    var days: BooleanArray = booleanArrayOf(true, true, true, true, true, false, false),
    var on: Boolean = true,
    var riseMin: Int = 30,
    var snoozeMin: Int = 9,
    var sound: String? = null,
) {
    fun copy() = Alarm(id, hour, minute, days.copyOf(), on, riseMin, snoozeMin, sound)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("h", hour).put("m", minute)
        .put("days", days.joinToString("") { if (it) "1" else "0" })
        .put("on", on).put("rise", riseMin).put("snooze", snoozeMin)
        .put("sound", sound ?: "")

    /** Naechster Klingelzeitpunkt nach [now] oder null. */
    fun nextRing(now: Long): Long? {
        if (!on) return null
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val any = days.any { it }
        for (d in 0..7) {
            val date = today.plusDays(d.toLong())
            if (any && !days[date.dayOfWeek.value - 1]) continue
            val t = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
            if (t > now) return t
        }
        return null
    }

    companion object {
        fun fromJson(o: JSONObject): Alarm {
            val ds = o.optString("days", "1111100")
            return Alarm(
                id = o.getLong("id"),
                hour = o.optInt("h", 6),
                minute = o.optInt("m", 45),
                days = BooleanArray(7) { i -> ds.getOrNull(i) == '1' },
                on = o.optBoolean("on", true),
                riseMin = o.optInt("rise", 30),
                snoozeMin = o.optInt("snooze", 9),
                sound = o.optString("sound", "").ifEmpty { null },
            )
        }
    }
}

object Store {
    private fun p(c: Context) = c.getSharedPreferences("grip", Context.MODE_PRIVATE)

    fun alarms(c: Context): MutableList<Alarm> {
        val s = p(c).getString("alarms", null) ?: return mutableListOf()
        return try {
            val a = JSONArray(s)
            MutableList(a.length()) { Alarm.fromJson(a.getJSONObject(it)) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveAlarms(c: Context, list: List<Alarm>) {
        val a = JSONArray()
        list.sortedBy { it.hour * 60 + it.minute }.forEach { a.put(it.toJson()) }
        p(c).edit().putString("alarms", a.toString()).apply()
    }

    fun alarm(c: Context, id: Long): Alarm? = alarms(c).firstOrNull { it.id == id }

    fun gripAddress(c: Context): String? = p(c).getString("grip", null)
    fun gripName(c: Context): String? = p(c).getString("gripName", null)
    fun setGrip(c: Context, address: String, name: String) {
        p(c).edit().putString("grip", address).putString("gripName", name).apply()
    }

    fun forgetGrip(c: Context) {
        p(c).edit().remove("grip").remove("gripName").apply()
    }

    /** Gefundene BLE-Adresse fuer eine gespeicherte (evtl. klassische) Adresse. */
    fun leAlias(c: Context, addr: String): String? = p(c).getString("le:$addr", null)
    fun setLeAlias(c: Context, addr: String, le: String) {
        p(c).edit().putString("le:$addr", le).apply()
    }

    fun snoozeAt(c: Context): Long = p(c).getLong("snoozeAt", 0L)
    fun snoozeId(c: Context): Long = p(c).getLong("snoozeId", -1L)
    fun setSnooze(c: Context, at: Long, id: Long) {
        p(c).edit().putLong("snoozeAt", at).putLong("snoozeId", id).apply()
    }
    fun clearSnooze(c: Context) = setSnooze(c, 0L, -1L)
}

object Scheduler {
    const val EXTRA_ID = "id"
    const val EXTRA_RING = "ringAt"
    const val EXTRA_SNOOZE = "snooze"

    class Next(val id: Long, val ringAt: Long, val start: Long, val snooze: Boolean)

    fun next(c: Context, now: Long): Next? {
        var best: Next? = null
        for (a in Store.alarms(c)) {
            val r = a.nextRing(now) ?: continue
            if (best == null || r < best.ringAt) best = Next(a.id, r, r - a.riseMin * 60_000L, false)
        }
        val s = Store.snoozeAt(c)
        if (s > now && (best == null || s < best.ringAt)) best = Next(Store.snoozeId(c), s, s, true)
        return best
    }

    fun canExact(c: Context): Boolean {
        val am = c.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    /** Stellt den naechsten Systemwecker (setAlarmClock) auf den Beginn des Sonnenaufgangs. */
    fun reschedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val n = next(c, System.currentTimeMillis())
        if (n == null) {
            am.cancel(PendingIntent.getBroadcast(c, 0, Intent(c, AlarmReceiver::class.java), flags))
            return
        }
        if (!canExact(c)) return
        val i = Intent(c, AlarmReceiver::class.java)
            .putExtra(EXTRA_ID, n.id).putExtra(EXTRA_RING, n.ringAt).putExtra(EXTRA_SNOOZE, n.snooze)
        val op = PendingIntent.getBroadcast(c, 0, i, flags)
        val show = PendingIntent.getActivity(c, 1, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val trigger = maxOf(n.start, System.currentTimeMillis() + 1000)
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, show), op)
        } catch (e: SecurityException) {
            // Berechtigung fehlt, MainActivity zeigt den Hinweis
        }
    }
}
