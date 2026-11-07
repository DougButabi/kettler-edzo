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
// Őrző (watchdog, 2 mp-enként): ha a csatlakozás 20 mp alatt nem jön létre, vagy kapcsolódva
// 15 mp-ig nem jön adat, bont és újrakezdi. "Kapcsolódva" csak a pulzusküldés sikeres
// bekapcsolása után. A pánt bőrkontaktus-jelzését és elemszintjét is figyeljük; az állapotot
// (health) és az utolsó eseményeket a szív ikonra koppintva felugró ablak mutatja (HeartRateDialog).
//
// Edzés közben egy munkamenet gyűjti az adatokat (begin … stats): átlag- és maximumpulzus,
// 5 másodpercenkénti görbe, és a pulzusból számolt kalória (Keytel-képlet). Az, hogy épp
// számít-e az idő (nincs szünet, mozog a gép), a sporttól jön (counting).
@SuppressLint("MissingPermission")
object HeartRate {
    private const val TAG = "HeartRate"
    private val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    private val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Ennél régebbi érték már nem számít élő pulzusnak
    private const val FRESH_MS = 5_000L
    // Ennél nagyobb szünet két mérés között kiesésnek számít (nem számoljuk bele)
    private const val MAX_GAP_MS = 5_000L
    private const val SAMPLE_MS = 5_000L
    // Őrző: csatlakozási időkorlát, adat nélküli kapcsolat türelmi ideje, ellenőrzési ütem
    private const val CONNECT_TIMEOUT_MS = 20_000L
    private const val NO_DATA_MS = 15_000L
    private const val WATCH_MS = 2_000L

    enum class State { OFF, CONNECTING, CONNECTED }

    // Amit a felhasználónak mondunk (szív ikon színe, állapot-ablak)
    enum class Health { NOT_PAIRED, NO_PERMISSION, BT_OFF, IDLE, SEARCHING, NO_DATA, NO_CONTACT, OK }

    class Event(val at: Long, val text: String)

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var gatt: BluetoothGatt? = null
    private var wanted = false

    var state = State.OFF
        private set
    private var lastBpm = 0
    private var lastAt = 0L
    // Bármilyen beérkező mérés (akkor is, ha nincs benne érvényes pulzus, pl. nincs bőrkontaktus)
    private var lastPacketAt = 0L
    private var stateSince = 0L
    // A pánt jelzése: false = nincs bőrkontaktus, null = nem jelzi
    var contact: Boolean? = null
        private set
    var battery: Int? = null
        private set
    // Hányadik egymás utáni sikertelen csatlakozás (a tipphez)
    var failures = 0
        private set

    private val log = ArrayDeque<Event>()
    val events: List<Event> get() = log.toList()

    // Az élő pulzus, ha friss (különben null)
    val bpm: Int? get() = if (lastAt > 0 && SystemClock.elapsedRealtime() - lastAt < FRESH_MS) lastBpm else null

    // Hány ms-a jött az utolsó érvényes pulzus (null: még nem jött)
    val lastDataAgoMs: Long? get() = if (lastAt > 0) SystemClock.elapsedRealtime() - lastAt else null

    private val listeners = mutableSetOf<() -> Unit>()
    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    private fun notifyChanged() = listeners.toList().forEach { it() }

    fun paired(c: Context) = Prefs.hrAddress(c).isNotBlank()

    private fun btOn(c: Context) = c.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    fun health(c: Context): Health {
        val now = SystemClock.elapsedRealtime()
        return when {
            !paired(c) -> Health.NOT_PAIRED
            !hasPermission(c) -> Health.NO_PERMISSION
            !btOn(c) -> Health.BT_OFF
            bpm != null -> Health.OK
            state == State.CONNECTED && contact == false && now - lastPacketAt < FRESH_MS -> Health.NO_CONTACT
            state == State.CONNECTED -> Health.NO_DATA
            wanted -> Health.SEARCHING
            else -> Health.IDLE
        }
    }

    // Az állapot egy mondatban és egy tipp hozzá (állapot-ablak, Beállítások)
    fun describe(c: Context): Pair<String, String?> {
        val name = Prefs.hrName(c).ifBlank { "pánt" }
        return when (health(c)) {
            Health.NOT_PAIRED -> "Nincs pulzusmérő párosítva" to "Beállítások › Pulzusmérő › Keresés."
            Health.NO_PERMISSION -> "Nincs Bluetooth-engedély" to "Beállítások › Pulzusmérő › Keresés, és engedélyezd."
            Health.BT_OFF -> "Ki van kapcsolva a Bluetooth" to "Kapcsold be a tableten / telefonon."
            Health.OK -> "Rendben · ${bpm} bpm" to null
            Health.NO_CONTACT -> "Kapcsolódva, de nincs bőrkontaktus" to
                "Nedvesítsd meg az elektródákat (a pánt belső, gumis csíkjai), és húzd a pántot szorosabbra, közvetlenül a mell alá."
            Health.NO_DATA -> "Kapcsolódva, de nem jön pulzus" to
                "Nedvesítsd meg az elektródákat, igazítsd a pántot. Ha 15 mp-en belül sem jön adat, magától újracsatlakozik."
            Health.SEARCHING -> "Keresem: $name" to if (failures >= 2) {
                "Nem érem el a pántot. Rajtad van, és be van kapcsolva? A pánt egyszerre csak egy készülékhez kapcsolódik: " +
                    "nincs nyitva az app (vagy a Magene app) a másik telefonon / tableten? Ha ott fut, lépj ki belőle."
            } else {
                "A pánt akkor kapcsol be, ha rajtad van (nedves elektródával)."
            }
            Health.IDLE -> "Nincs kapcsolat" to "Nyomd meg az Újracsatlakozást."
        }
    }

    // Az edzésképernyők szív ikonjának színe: piros, ha jön a pulzus; kereséskor pirosan villog;
    // narancs, ha kapcsolódva van, de nem jön (érvényes) pulzus; szürke, ha ki van kapcsolva a
    // Bluetooth; null, ha nincs pánt vagy nem is próbálunk csatlakozni.
    fun heartColor(): Int? {
        val c = app
        val h = if (c != null) health(c) else if (bpm != null) Health.OK else if (wanted) Health.SEARCHING else Health.IDLE
        return when (h) {
            Health.OK -> HEART
            Health.NO_DATA, Health.NO_CONTACT -> HEART_WARN
            Health.SEARCHING -> if ((SystemClock.elapsedRealtime() / 500) % 2 == 0L) HEART else HEART_DIM
            Health.BT_OFF -> if (wanted) HEART_OFF else null
            else -> null
        }
    }

    private val HEART = android.graphics.Color.parseColor("#E53935")
    private val HEART_DIM = android.graphics.Color.parseColor("#F3B4B2")
    private val HEART_WARN = android.graphics.Color.parseColor("#F08A24")
    private val HEART_OFF = android.graphics.Color.parseColor("#9A9A9A")

    fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun hasPermission(c: Context) = permissions().all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun event(text: String) {
        Log.i(TAG, text)
        main.post {
            log.addFirst(Event(System.currentTimeMillis(), text))
            while (log.size > 12) log.removeLast()
            notifyChanged()
        }
    }

    // ---- Kapcsolat ----

    fun connect(c: Context) {
        val ctx = c.applicationContext
        app = ctx
        val address = Prefs.hrAddress(ctx)
        if (address.isBlank() || !hasPermission(ctx)) return
        if (!wanted) {
            wanted = true
            main.removeCallbacks(watchdog)
            main.postDelayed(watchdog, WATCH_MS)
        }
        if (gatt != null) return
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        // Kikapcsolt Bluetooth: az őrző újrapróbálja, amint bekapcsolják
        if (!adapter.isEnabled) return
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            return
        }
        main.removeCallbacks(retry)
        setState(State.CONNECTING)
        event("Csatlakozás: ${Prefs.hrName(ctx).ifBlank { address }}")
        gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    // Kézi újrakezdés (állapot-ablak): bont, és azonnal újra csatlakozik
    fun reconnect(c: Context) {
        event("Újracsatlakozás (kézi)")
        failures = 0
        drop()
        wanted = false
        connect(c)
    }

    fun disconnect() {
        wanted = false
        main.removeCallbacks(retry)
        main.removeCallbacks(watchdog)
        drop()
        setState(State.OFF)
    }

    // A GATT-kapcsolat lezárása (a "wanted" szándék marad)
    private fun drop() {
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
        lastPacketAt = 0
        contact = null
        state = State.OFF
    }

    // Elfelejti a pántot (Beállítások)
    fun forget(c: Context) {
        disconnect()
        Prefs.setHrDevice(c, "", "")
        log.clear()
        battery = null
        failures = 0
    }

    private val retry = Runnable { app?.let { if (wanted) connect(it) } }

    private val watchdog = object : Runnable {
        override fun run() {
            val c = app
            if (!wanted || c == null) return
            val now = SystemClock.elapsedRealtime()
            when {
                gatt == null && state == State.OFF && !main.hasCallbacksCompat(retry) -> {
                    if (btOn(c)) connect(c)
                }
                state == State.CONNECTING && now - stateSince > CONNECT_TIMEOUT_MS -> {
                    failures++
                    event("Nem sikerült csatlakozni 20 mp alatt, újrakezdem")
                    drop()
                    setState(State.OFF)
                    connect(c)
                }
                state == State.CONNECTED && now - max(lastPacketAt, stateSince) > NO_DATA_MS -> {
                    failures++
                    event("Kapcsolódva, de 15 mp óta nem jön adat, újracsatlakozom")
                    drop()
                    setState(State.OFF)
                    connect(c)
                }
            }
            main.postDelayed(this, WATCH_MS)
        }
    }

    private fun Handler.hasCallbacksCompat(r: Runnable) = if (Build.VERSION.SDK_INT >= 29) hasCallbacks(r) else false

    private fun setState(s: State) {
        if (state != s) stateSince = SystemClock.elapsedRealtime()
        state = s
        main.post { notifyChanged() }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                event("Kapcsolódott, szolgáltatások lekérése…")
                main.post { if (gatt === g) stateSince = SystemClock.elapsedRealtime() }
                g.discoverServices()
                return
            }
            // Megszakadt: ha még kell, néhány másodperc múlva újra próbáljuk.
            main.post {
                if (gatt !== g) {
                    g.close()
                    return@post
                }
                event(if (state == State.CONNECTED) "Megszakadt a kapcsolat (kód $status)" else "Nem sikerült csatlakozni (kód $status)")
                if (state != State.CONNECTED) failures++
                g.close()
                gatt = null
                lastAt = 0
                lastPacketAt = 0
                contact = null
                setState(State.OFF)
                if (wanted) main.postDelayed(retry, 3_000)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
            val d = ch?.getDescriptor(CCCD)
            if (status != BluetoothGatt.GATT_SUCCESS || ch == null || d == null) {
                event(if (ch == null) "A pánton nem találom a pulzus-szolgáltatást" else "Szolgáltatás-lekérés hiba (kód $status)")
                // Az őrző újrakezdi (CONNECTING időkorlát)
                return
            }
            g.setCharacteristicNotification(ch, true)
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
            if (!ok) event("Nem sikerült bekapcsolni a pulzusküldést")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid != CCCD) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                event("A pulzusküldés bekapcsolása hibát adott (kód $status)")
                return
            }
            main.post {
                if (gatt !== g) return@post
                failures = 0
                event("Kapcsolódva, pulzusra vár")
                setState(State.CONNECTED)
            }
            // Elemszint (ha a pánt tudja); a pulzusküldés már be van kapcsolva
            g.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL)?.let {
                try { g.readCharacteristic(it) } catch (e: Exception) { Log.w(TAG, "Elemszint", e) }
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            onRead(ch, value, status)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) onRead(ch, ch.value ?: return, status)
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

    private fun onRead(ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        if (ch.uuid != BATTERY_LEVEL || status != BluetoothGatt.GATT_SUCCESS || value.isEmpty()) return
        val level = value[0].toInt() and 0xFF
        main.post {
            battery = level
            event("Elemszint: $level%")
        }
    }

    // Heart Rate Measurement: az első bájt jelzői közül a 0. bit mondja meg, hogy 8 vagy 16 bites
    // az érték; a 2. bit, hogy a pánt jelzi-e a bőrkontaktust, az 1. bit pedig maga a kontaktus.
    private fun onMeasurement(v: ByteArray) {
        if (v.isEmpty()) return
        val flags = v[0].toInt()
        val touch: Boolean? = if (flags and 0x04 != 0) flags and 0x02 != 0 else null
        val bpm = when {
            v.size < 2 -> 0
            flags and 1 == 0 -> v[1].toInt() and 0xFF
            v.size < 3 -> 0
            else -> (v[1].toInt() and 0xFF) or ((v[2].toInt() and 0xFF) shl 8)
        }
        main.post {
            val now = SystemClock.elapsedRealtime()
            lastPacketAt = now
            if (touch != contact) {
                if (touch == false) event("Nincs bőrkontaktus")
                else if (touch == true && contact == false) event("Van bőrkontaktus")
                contact = touch
            }
            if (state != State.CONNECTED) setState(State.CONNECTED)
            if (bpm in 25..250) {
                if (lastAt == 0L) event("Jön a pulzus ($bpm bpm)")
                record(bpm, now)
                lastBpm = bpm
                lastAt = now
            }
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
