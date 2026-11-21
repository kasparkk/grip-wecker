package de.kasparkk.gripwecker

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * BLE-Verbindung zum Grip mit Schreib-Warteschlange.
 * Pro Feature gilt immer nur der neueste Wert, Abstand zwischen zwei Paketen 170 ms
 * (wie in der Web-App).
 *
 * Robustheit (Android-BLE ist zickiger als Chrome):
 * - Fehlversuche (z. B. Status 133) werden sofort still wiederholt (1 s, 2 s, 3 s …),
 *   erst danach gilt die Verbindung als getrennt.
 * - Abbruch einer laufenden Verbindung: still neu verbinden, Befehle bleiben in der
 *   Warteschlange und werden danach gesendet.
 * - Haengender Verbindungsaufbau wird nach 12 s abgebrochen.
 * - Kurze Pause vor der Dienstsuche (gekoppelte Geraete), bei fehlendem Dienst Cache leeren.
 * - Nach zwei Fehlversuchen: kurzer BLE-Scan nach dem Grip. Hat er eine andere
 *   BLE-Adresse als die gespeicherte (z. B. klassische Adresse gewaehlt), wird diese
 *   gemerkt und kuenftig benutzt. Chrome nimmt immer die BLE-Adresse aus dem Scan.
 */
@SuppressLint("MissingPermission")
class GripBle(
    private val ctx: Context,
    origAddress: String,
    /** true: nach den schnellen Versuchen alle 10 s weiter probieren (Wecker). */
    private val autoRetry: Boolean = true,
    private val onState: (String) -> Unit,
) {
    /** Optional: Protokoll jedes Schreibversuchs (fuer die Fehlersuche). */
    var onLog: ((String) -> Unit)? = null
    private val h = Handler(Looper.getMainLooper())
    private val savedAddress = origAddress
    private var address = Store.leAlias(ctx, origAddress) ?: origAddress
    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private val pending = LinkedHashMap<Int, Int>()
    private val last = HashMap<Int, Int>()
    private var lastWrite = 0L
    private var pumping = false
    private var closed = false
    private var fails = 0
    private var scanned = false
    private var scanCb: ScanCallback? = null
    var ready = false
        private set

    private fun log(m: String) = onLog?.invoke(m)

    fun connect() {
        if (closed) return
        h.removeCallbacks(reconnect)
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            if (autoRetry) {
                onState("Bluetooth aus, neuer Versuch in 10 s")
                h.postDelayed(reconnect, 10_000)
            } else {
                onState("Bluetooth aus")
            }
            return
        }
        onState(if (fails == 0) "verbinde …" else "verbinde … (Versuch ${fails + 1})")
        try {
            val dev = adapter.getRemoteDevice(address)
            gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, h)
            h.postDelayed(connectTimeout, 12_000)
        } catch (e: Exception) {
            log("connectGatt: ${e.message}")
            failed("Fehler: ${e.message}")
        }
    }

    private val reconnect = Runnable { connect() }

    private val connectTimeout = Runnable {
        if (!ready) {
            log("Verbindungsaufbau haengt, breche ab")
            dropGatt()
            failed("Zeitüberschreitung")
        }
    }

    private fun dropGatt() {
        h.removeCallbacks(connectTimeout)
        h.removeCallbacks(discover)
        h.removeCallbacks(readyFallback)
        ready = false
        tx = null
        val g = gatt
        gatt = null
        try { g?.disconnect() } catch (_: Exception) {}
        try { g?.close() } catch (_: Exception) {}
    }

    /** Ein Versuch ist gescheitert oder eine Verbindung abgebrochen. */
    private fun failed(why: String) {
        if (closed) return
        fails++
        if (fails == 2 && !scanned) {
            scanForGrip()
            return
        }
        if (fails <= QUICK) {
            log("$why, neuer Versuch in ${fails} s")
            onState("verbinde … ($why)")
            h.postDelayed(reconnect, fails * 1000L)
        } else if (autoRetry) {
            onState("getrennt, neuer Versuch in 10 s")
            h.postDelayed(reconnect, 10_000)
        } else {
            onState("getrennt")
        }
    }

    // ---------- BLE-Adresse per Scan suchen ----------

    private fun scanForGrip() {
        scanned = true
        val scanner = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        if (scanner == null) { failedAfterScan(); return }
        val name = Store.gripName(ctx)
        onState("verbinde … (suche Grip)")
        log("suche Grip per BLE-Scan (gespeichert: $address)")
        // Filter, damit der Scan auch bei ausgeschaltetem Bildschirm (Wecker) laeuft
        val filters = ArrayList<ScanFilter>()
        filters.add(ScanFilter.Builder().setManufacturerData(0x0057, ByteArray(0)).build()) // Harman
        if (!name.isNullOrBlank()) filters.add(ScanFilter.Builder().setDeviceName(name).build())
        val cbk = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val n = r.device.name ?: r.scanRecord?.deviceName ?: ""
                val match = (name != null && n == name) || n.contains("grip", true)
                if (!match) return
                h.post { foundGrip(r.device.address, n) }
            }

            override fun onScanFailed(errorCode: Int) {
                h.post { log("Scan fehlgeschlagen: $errorCode"); stopScan(); failedAfterScan() }
            }
        }
        scanCb = cbk
        try {
            scanner.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cbk)
        } catch (e: Exception) {
            log("Scan: ${e.message}")
            scanCb = null
            failedAfterScan()
            return
        }
        h.postDelayed(scanTimeout, 8_000)
    }

    private val scanTimeout = Runnable {
        if (scanCb != null) {
            stopScan()
            log("Grip im Scan nicht gesehen. Vermutlich ist er gerade mit einem anderen Gerät verbunden (Laptop-Relais, Chrome?)")
            failedAfterScan()
        }
    }

    private fun stopScan() {
        h.removeCallbacks(scanTimeout)
        val c = scanCb ?: return
        scanCb = null
        try { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(c) } catch (_: Exception) {}
    }

    private fun foundGrip(addr: String, n: String) {
        if (scanCb == null || closed) return
        stopScan()
        if (addr != address) {
            log("Grip hat BLE-Adresse $addr ($n), benutze diese")
            address = addr
            Store.setLeAlias(ctx, savedAddress, addr)
        } else {
            log("Grip im Scan gesehen ($n)")
        }
        h.postDelayed(reconnect, 300)
    }

    private fun failedAfterScan() {
        if (closed) return
        h.postDelayed(reconnect, 1000)
    }

    // ---------- GATT ----------

    private val discover = Runnable {
        val g = gatt ?: return@Runnable
        if (!g.discoverServices()) {
            log("Dienstsuche liess sich nicht starten")
            dropGatt()
            failed("Dienstsuche")
        }
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            h.post {
                if (g !== gatt) { try { g.close() } catch (_: Exception) {}; return@post }
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    h.removeCallbacks(connectTimeout)
                    h.postDelayed(connectTimeout, 12_000) // bis "bereit"
                    onState("verbunden, suche Dienst …")
                    // gekoppelte Geraete schicken direkt nach Verbindung noch Daten; kurz warten
                    h.postDelayed(discover, if (g.device.bondState == BluetoothDevice.BOND_BONDED) 600 else 150)
                } else {
                    val wasReady = ready
                    dropGatt()
                    if (closed) return@post
                    if (wasReady) {
                        // laufende Verbindung abgebrochen: still neu verbinden
                        log("Verbindung abgebrochen (Status $status), verbinde neu")
                        fails = 0
                        scanned = false
                    }
                    failed(if (status == 133) "Status 133" else "Status $status")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            h.post {
                if (g !== gatt) return@post
                val svc = g.getService(Grip.SERVICE)
                val c = svc?.getCharacteristic(Grip.TX)
                if (c == null) {
                    log("Grip-Dienst nicht gefunden (Status $status), leere Cache")
                    refreshCache(g)
                    dropGatt()
                    failed("Dienst fehlt")
                    return@post
                }
                tx = c
                // Antworten abonnieren: der Grip nimmt Befehle erst danach an (wie startNotifications im Browser)
                val rx = svc.getCharacteristic(Grip.RX)
                val d = rx?.getDescriptor(Grip.CCCD)
                if (rx != null && d != null && subscribe(g, rx, d)) {
                    log("abonniere Antworten …")
                    h.postDelayed(readyFallback, 2500)
                } else {
                    log("Abo nicht möglich, sende trotzdem")
                    markReady()
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            h.post {
                if (g !== gatt) return@post
                log("Abo " + if (status == BluetoothGatt.GATT_SUCCESS) "ok" else "Status $status")
                markReady()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) h.post { log("Schreiben meldet Status $status") }
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val v = ch.value ?: return
            h.post { log("<- " + hex(v)) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            h.post { log("<- " + hex(value)) }
        }
    }

    private fun refreshCache(g: BluetoothGatt) {
        try { g.javaClass.getMethod("refresh").invoke(g) } catch (_: Exception) {}
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private val readyFallback = Runnable { if (!ready && tx != null) { log("Abo ohne Antwort, sende trotzdem"); markReady() } }

    private fun markReady() {
        h.removeCallbacks(readyFallback)
        h.removeCallbacks(connectTimeout)
        if (ready || tx == null) return
        ready = true
        fails = 0
        scanned = false
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
        if (!ready) log("0x%04x = 0x%02x wartet (noch nicht bereit)".format(fid, v))
        if (force) last.remove(fid)
        if (last[fid] == v && !pending.containsKey(fid)) return
        pending.remove(fid)
        pending[fid] = v
        pump()
    }

    private var busyStreak = 0

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
            log("0x%04x = 0x%02x %s".format(e.key, e.value, if (ok) "ok" else "FEHLER (${lastErr})"))
            lastWrite = System.currentTimeMillis()
            if (ok) {
                last[e.key] = e.value
                busyStreak = 0
            } else {
                // Wert behalten (falls inzwischen kein neuerer kam) und gleich nochmal
                if (!pending.containsKey(e.key)) pending[e.key] = e.value
                if (++busyStreak >= 15) {
                    // Stack haengt: Verbindung neu aufbauen, Warteschlange bleibt
                    log("Schreiben klemmt, verbinde neu")
                    busyStreak = 0
                    dropGatt()
                    fails = 0
                    scanned = false
                    failed("Schreiben klemmt")
                    return
                }
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
        stopScan()
        dropGatt()
        h.removeCallbacksAndMessages(null)
    }

    companion object {
        /** Schnelle stille Versuche, bevor "getrennt" gemeldet wird. */
        const val QUICK = 4
    }
}
