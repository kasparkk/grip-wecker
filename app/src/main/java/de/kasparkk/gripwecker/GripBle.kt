package de.kasparkk.gripwecker

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
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
    private val onState: (String) -> Unit,
) {
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
            retry()
            return
        }
        onState("verbinde …")
        try {
            val dev = adapter.getRemoteDevice(address)
            gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            onState("Fehler: ${e.message}")
            retry()
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
                        onState("getrennt, neuer Versuch in 10 s")
                        retry()
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            h.post {
                val c = g.getService(Grip.SERVICE)?.getCharacteristic(Grip.TX)
                if (c == null) {
                    onState("Grip-Dienst nicht gefunden")
                    try { g.disconnect() } catch (_: Exception) {}
                    return@post
                }
                tx = c
                ready = true
                last.clear() // nach Neuverbindung alles erneut senden
                onState("verbunden")
                pump()
            }
        }
    }

    /** Wert fuer ein Feature setzen (wird gesendet, sobald verbunden). */
    fun put(fid: Int, v: Int) {
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
            if (write(Grip.packet(e.key, e.value))) {
                last[e.key] = e.value
                lastWrite = System.currentTimeMillis()
            } else {
                pending[e.key] = e.value // spaeter nochmal
                lastWrite = System.currentTimeMillis()
            }
        }
        if (pending.isNotEmpty()) pump()
    }

    @Suppress("DEPRECATION")
    private fun write(data: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = tx ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
            } else {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                c.value = data
                g.writeCharacteristic(c)
            }
        } catch (e: Exception) {
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
