package de.kasparkk.gripwecker

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * BLE-Verbindung zum Grip mit Schreib-Warteschlange.
 * Pro Feature gilt immer nur der neueste Wert, Abstand zwischen zwei Paketen 170 ms
 * (wie in der Web-App). Bei Abbruch neuer Versuch alle 10 s.
 */
@SuppressLint("MissingPermission")
class GripBle(
    private val ctx: Context,
    private val address: String,
    private val autoRetry: Boolean = true,
    private val onState: (String) -> Unit,
) {
    /** Optional: Protokoll jedes Schreibversuchs (fuer die Fehlersuche). */
    var onLog: ((String) -> Unit)? = null
    private val h = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private val pending = LinkedHashMap<Int, Int>()
    private val last = HashMap<Int, Int>()
    private var lastWrite = 0L
    private var pumping = false
    private var closed = false
    var ready = false
        private set

    fun connect() {
        if (closed) return
        val bm = ctx.getSystemService(BluetoothManager::class.java)
        val adapter = bm?.adapter
        if (adapter == null || !adapter.isEnabled) {
            onState("Bluetooth aus")
            if (autoRetry) retry()
            return
        }
        onState("verbinde …")
        try {
            val dev = adapter.getRemoteDevice(address)
            gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            onState("Fehler: ${e.message}")
            if (autoRetry) retry()
        }
    }

    private fun retry() {
        if (closed) return
        h.removeCallbacks(reconnect)
        h.postDelayed(reconnect, 10_000)
    }

    private val reconnect = Runnable { connect() }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            h.post {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    onState("verbunden, suche Dienst …")
                    g.discoverServices()
                } else {
                    ready = false
                    tx = null
                    try { g.close() } catch (_: Exception) {}
                    if (gatt === g) gatt = null
                    if (!closed) {
                        if (autoRetry) {
                            onState("getrennt, neuer Versuch in 10 s")
                            retry()
                        } else {
                            onState("getrennt")
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            h.post {
                val svc = g.getService(Grip.SERVICE)
                val c = svc?.getCharacteristic(Grip.TX)
                if (c == null) {
                    onState("Grip-Dienst nicht gefunden")
                    try { g.disconnect() } catch (_: Exception) {}
                    return@post
                }
                tx = c
                // Antworten abonnieren: der Grip nimmt Befehle erst danach an (wie startNotifications im Browser)
                val rx = svc.getCharacteristic(Grip.RX)
                val d = rx?.getDescriptor(Grip.CCCD)
                if (rx != null && d != null && subscribe(g, rx, d)) {
                    onLog?.invoke("abonniere Antworten …")
                    h.postDelayed(readyFallback, 2500)
                } else {
                    onLog?.invoke("Abo nicht möglich, sende trotzdem")
                    markReady()
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            h.post {
                onLog?.invoke("Abo " + if (status == BluetoothGatt.GATT_SUCCESS) "ok" else "Status $status")
                markReady()
            }
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val v = ch.value ?: return
            h.post { onLog?.invoke("<- " + hex(v)) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            h.post { onLog?.invoke("<- " + hex(value)) }
        }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private val readyFallback = Runnable { if (!ready && tx != null) { onLog?.invoke("Abo ohne Antwort, sende trotzdem"); markReady() } }

    private fun markReady() {
        h.removeCallbacks(readyFallback)
        if (ready || tx == null) return
        ready = true
        last.clear() // nach Neuverbindung alles erneut senden
        onState("verbunden")
        pump()
    }

    @Suppress("DEPRECATION")
    private fun subscribe(g: BluetoothGatt, rx: BluetoothGattCharacteristic, d: BluetoothGattDescriptor): Boolean {
        return try {
            g.setCharacteristicNotification(rx, true)
            val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, v) == BluetoothGatt.GATT_SUCCESS
            } else {
                d.value = v
                g.writeDescriptor(d)
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Wert fuer ein Feature setzen (wird gesendet, sobald verbunden). */
    fun put(fid: Int, v: Int, force: Boolean = false) {
        if (!ready) onLog?.invoke("0x%04x = 0x%02x wartet (noch nicht bereit)".format(fid, v))
        if (force) last.remove(fid)
        if (last[fid] == v && !pending.containsKey(fid)) return
        pending.remove(fid)
        pending[fid] = v
        pump()
    }

    private fun pump() {
        if (pumping || !ready) return
        val wait = 170 - (System.currentTimeMillis() - lastWrite)
        if (wait > 0) {
            pumping = true
            h.postDelayed({ pumping = false; pump() }, wait)
            return
        }
        val it = pending.entries.iterator()
        if (!it.hasNext()) return
        val e = it.next()
        it.remove()
        if (last[e.key] != e.value) {
            val ok = write(Grip.packet(e.key, e.value))
            onLog?.invoke("0x%04x = 0x%02x %s".format(e.key, e.value, if (ok) "ok" else "FEHLER (${lastErr})"))
            if (ok) {
                last[e.key] = e.value
                lastWrite = System.currentTimeMillis()
            } else {
                pending[e.key] = e.value // spaeter nochmal
                lastWrite = System.currentTimeMillis()
            }
        }
        if (pending.isNotEmpty()) pump()
    }

    private var lastErr = ""

    @Suppress("DEPRECATION")
    private fun write(data: ByteArray): Boolean {
        val g = gatt ?: run { lastErr = "kein gatt"; return false }
        val c = tx ?: run { lastErr = "keine Characteristic"; return false }
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                val r = g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                lastErr = "Code $r"
                r == BluetoothGatt.GATT_SUCCESS
            } else {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                c.value = data
                val r = g.writeCharacteristic(c)
                lastErr = "false"
                r
            }
        } catch (e: Exception) {
            lastErr = e.toString()
            false
        }
    }

    fun pendingCount() = pending.size

    fun close() {
        closed = true
        ready = false
        h.removeCallbacksAndMessages(null)
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
    }
}
