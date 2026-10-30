package hu.kettleredzo.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import java.util.UUID
import kotlin.math.max

// Pulzusmérő pánt (pl. Magene) a szabványos Bluetooth LE pulzus-szolgáltatáson (0x180D) át.
// A sportválasztón és az edzésképernyőkön rácsatlakozunk (connect), és kapcsolódva maradunk;
// csak a Kilépés az appból bontja (disconnect). Kiesés után magától újrapróbálkozik.
//
// Edzés közben egy munkamenet gyűjti az adatokat (begin … stats): átlag- és maximumpulzus,
// 5 másodpercenkénti görbe, és a pulzusból számolt kalória (Keytel-képlet). Az, hogy épp
// számít-e az idő (nincs szünet, mozog a gép), a sporttól jön (counting).
@SuppressLint("MissingPermission")
object HeartRate {
    private const val TAG = "HeartRate"
    private val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Ennél régebbi érték már nem számít élő pulzusnak
    private const val FRESH_MS = 5_000L
    // Ennél nagyobb szünet két mérés között kiesésnek számít (nem számoljuk bele)
    private const val MAX_GAP_MS = 5_000L
    private const val SAMPLE_MS = 5_000L

    enum class State { OFF, CONNECTING, CONNECTED }

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var gatt: BluetoothGatt? = null
    private var wanted = false

    var state = State.OFF
        private set
    private var lastBpm = 0
    private var lastAt = 0L

    // Az élő pulzus, ha friss (különben null)
    val bpm: Int? get() = if (lastAt > 0 && SystemClock.elapsedRealtime() - lastAt < FRESH_MS) lastBpm else null

    private val listeners = mutableSetOf<() -> Unit>()
    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    private fun notifyChanged() = listeners.toList().forEach { it() }

    fun paired(c: Context) = Prefs.hrAddress(c).isNotBlank()

    // Az edzésképernyők szív ikonjának színe: piros, ha jön a pulzus; csatlakozás közben pirosan villog
    // (félmásodpercenként halvány); null, ha nincs pánt vagy nem is próbálunk csatlakozni.
    fun heartColor(): Int? = when {
        bpm != null -> HEART
        wanted -> if ((SystemClock.elapsedRealtime() / 500) % 2 == 0L) HEART else HEART_DIM
        else -> null
    }

    private val HEART = android.graphics.Color.parseColor("#E53935")
    private val HEART_DIM = android.graphics.Color.parseColor("#F3B4B2")

    fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun hasPermission(c: Context) = permissions().all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    // ---- Kapcsolat ----

    fun connect(c: Context) {
        val ctx = c.applicationContext
        app = ctx
        val address = Prefs.hrAddress(ctx)
        if (address.isBlank() || !hasPermission(ctx)) return
        wanted = true
        if (gatt != null) return
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!adapter.isEnabled) return
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            return
        }
        setState(State.CONNECTING)
        gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        wanted = false
        main.removeCallbacks(retry)
        gatt?.let {
            try {
                it.disconnect()
                it.close()
            } catch (e: SecurityException) {
                Log.w(TAG, "Lecsatlakozás", e)
            }
        }
        gatt = null
        lastAt = 0
        setState(State.OFF)
    }

    // Elfelejti a pántot (Beállítások)
    fun forget(c: Context) {
        disconnect()
        Prefs.setHrDevice(c, "", "")
    }

    private val retry = Runnable { app?.let { if (wanted) connect(it) } }

    private fun setState(s: State) {
        state = s
        main.post { notifyChanged() }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
                return
            }
            // Megszakadt: ha még kell, néhány másodperc múlva újra próbáljuk.
            main.post {
                if (gatt === g) {
                    g.close()
                    gatt = null
                }
                lastAt = 0
                setState(State.OFF)
                if (wanted) main.postDelayed(retry, 3_000)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
            if (ch == null) {
                Log.w(TAG, "A pánton nincs pulzus-szolgáltatás")
                return
            }
            g.setCharacteristicNotification(ch, true)
            val d = ch.getDescriptor(CCCD) ?: return
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
            setState(State.CONNECTED)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            onMeasurement(value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) onMeasurement(ch.value ?: return)
        }
    }

    // Heart Rate Measurement: az első bájt jelzői közül a 0. bit mondja meg, hogy 8 vagy 16 bites az érték.
    private fun onMeasurement(v: ByteArray) {
        if (v.size < 2) return
        val bpm = if (v[0].toInt() and 1 == 0) {
            v[1].toInt() and 0xFF
        } else {
            if (v.size < 3) return
            (v[1].toInt() and 0xFF) or ((v[2].toInt() and 0xFF) shl 8)
        }
        if (bpm !in 25..250) return
        main.post {
            val now = SystemClock.elapsedRealtime()
            record(bpm, now)
            lastBpm = bpm
            lastAt = now
            if (state != State.CONNECTED) state = State.CONNECTED
            notifyChanged()
        }
    }

    // ---- Edzés-munkamenet ----

    class Stats(val avg: Int?, val max: Int?, val samples: String?, val hrMs: Long, val kcal: Double)

    private var sessionKey: String? = null
    private var profile: Profile? = null
    private var counting: () -> Boolean = { false }
    private var hrMs = 0L
    private var bpmMsSum = 0.0
    private var maxBpm = 0
    private var hrKcal = 0.0
    private var sampleClock = 0L
    private val samples = mutableListOf<Int>()

    // Új munkamenet (ha ugyanazzal a kulccsal már fut egy, pl. a súlyzós edzés folytatásakor, az marad).
    fun begin(key: String, p: Profile, counting: () -> Boolean) {
        this.counting = counting
        if (sessionKey == key) return
        sessionKey = key
        profile = p
        hrMs = 0
        bpmMsSum = 0.0
        maxBpm = 0
        hrKcal = 0.0
        sampleClock = 0
        samples.clear()
    }

    fun end() {
        sessionKey = null
        counting = { false }
    }

    private fun record(bpm: Int, now: Long) {
        val p = profile ?: return
        if (sessionKey == null || lastAt == 0L || !counting()) return
        val dt = now - lastAt
        if (dt <= 0 || dt > MAX_GAP_MS) return
        hrMs += dt
        bpmMsSum += bpm.toDouble() * dt
        maxBpm = max(maxBpm, bpm)
        hrKcal += keytelPerMin(bpm, p) * dt / 60_000.0
        sampleClock += dt
        while (sampleClock >= SAMPLE_MS) {
            samples += bpm
            sampleClock -= SAMPLE_MS
        }
    }

    // A sport saját becslését (pl. km alapján) a pulzusos kalóriával váltja ki arra az időre,
    // amíg volt pulzus; ha a pánt közben kiesett, arra a részre a becslés marad.
    fun kcal(estimate: Double, movingMs: Long): Double {
        if (sessionKey == null || hrMs == 0L || movingMs <= 0) return estimate
        val uncovered = max(0.0, 1.0 - hrMs.toDouble() / movingMs)
        return hrKcal + estimate * uncovered
    }

    fun stats(estimate: Double, movingMs: Long): Stats {
        if (sessionKey == null || hrMs == 0L) return Stats(null, null, null, 0, estimate)
        return Stats(
            avg = (bpmMsSum / hrMs).toInt(),
            max = maxBpm,
            samples = if (samples.isEmpty()) null else JSONArray(samples).toString(),
            hrMs = hrMs,
            kcal = kcal(estimate, movingMs),
        )
    }

    // Keytel és mtsai (2005): energiafelhasználás percenként a pulzusból, testsúlyból, életkorból és nemből.
    fun keytelPerMin(bpm: Int, p: Profile): Double {
        val kj = if (p.sex == Sex.MALE) {
            -55.0969 + 0.6309 * bpm + 0.1988 * p.weightKg + 0.2017 * p.age
        } else {
            -20.4022 + 0.4472 * bpm - 0.1263 * p.weightKg + 0.074 * p.age
        }
        return max(0.0, kj / 4.184)
    }
}
