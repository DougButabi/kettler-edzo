package hu.kettleredzo.app

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import java.io.File
import kotlin.math.max

// Amit a másodpercenkénti lépés jelez (a BikeService ebből ad hangjelzést)
enum class RideEvent { NONE, KM, HALFWAY, GOAL }

// A kerékpáros tekerés közös állapota (a BikeService írja, a képernyők olvassák). Csak fő szálról.
object BikeState {
    // Ez alatt a sebesség alatt nem nő a táv (álló helyzetben a GPS-hely ingadozik).
    private const val DRIFT_KMH = 2.0

    // Ennél pontatlanabb GPS-helyet nem használunk a távhoz.
    private const val MAX_ACCURACY_M = 30f

    // Ha ennyi ideig nincs új hely, a sebességet nullának vesszük.
    private const val FIX_TIMEOUT_MS = 5_000L

    // A magasságingadozást ennyi méteres küszöbbel szűrjük a szintemelkedésnél.
    private const val CLIMB_STEP_M = 3.0

    var mode = WorkoutMode.FREE
    var goal = 0.0

    var running = false
    var paused = false // kézi szünet
    var profileId = -1L
    var weightKg = 75.0
    var startedAt = 0L
    var movingMs = 0L
    var distanceKm = 0.0
    var speedKmh = 0.0
    var maxSpeedKmh = 0.0
    var elevGain = 0.0
    var estKcal = 0.0 // sebesség (MET) alapján becsült kalória

    // Pulzuspánttal a pulzusból (Keytel), nélküle a becslés
    val kcal get() = if (running) HeartRate.kcal(estKcal, movingMs) else estKcal
    var goalReached = false

    // Automatikus szünet küszöbe km/h-ban (0 = kikapcsolva); indításkor a beállításból jön.
    var autoPauseKmh = 3.0
    private var lastWholeKm = 0
    private var halfwayDone = false

    // GPS
    var lastLocation: Location? = null
    var lastFixAt = 0L
    var gpsError: String? = null
    val points = mutableListOf<DoubleArray>() // [lat, lon, magasság, idő ms]

    private var smoothAlt = Double.NaN
    private var climbBase = Double.NaN

    val hasFix get() = lastLocation != null && System.currentTimeMillis() - lastFixAt < FIX_TIMEOUT_MS
    val isCounting get() = running && !paused
    val autoPaused get() = isCounting && autoPauseKmh > 0 && speedKmh < autoPauseKmh
    val avgSpeedKmh get() = if (movingMs >= 5000) distanceKm / (movingMs / 3_600_000.0) else 0.0

    // Haladás a cél felé, 0..1 (szabad tekerésnél 0)
    val progress: Float
        get() {
            val p = when (mode) {
                WorkoutMode.DISTANCE -> if (goal > 0) distanceKm / goal else 0.0
                WorkoutMode.KCAL -> if (goal > 0) kcal / goal else 0.0
                WorkoutMode.TIME -> if (goal > 0) movingMs / (goal * 60_000) else 0.0
                else -> 0.0
            }
            return p.toFloat().coerceIn(0f, 1f)
        }

    val goalMet get() = mode != WorkoutMode.FREE && mode != WorkoutMode.PROGRAM && progress >= 1f

    // pl. "HÁTRA 6,58 km"; szabad tekerésnél null
    fun goalStatus(): String? {
        if (goalReached) return "CÉL TELJESÍTVE ✓"
        return when (mode) {
            WorkoutMode.DISTANCE -> "HÁTRA ${formatNum(max(0.0, goal - distanceKm), 2)} km"
            WorkoutMode.KCAL -> "HÁTRA ${formatNum(max(0.0, goal - kcal), 0)} kcal"
            WorkoutMode.TIME -> "HÁTRA ${formatTimeUp(max(0L, (goal * 60_000).toLong() - movingMs))}"
            else -> null
        }
    }

    private val listeners = mutableSetOf<() -> Unit>()
    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    fun notifyChanged() = listeners.toList().forEach { it() }

    fun configure(mode: WorkoutMode, goal: Double) {
        this.mode = if (mode == WorkoutMode.PROGRAM) WorkoutMode.FREE else mode
        this.goal = goal
    }

    fun start(profile: Profile, autoPauseKmh: Double) {
        profileId = profile.id
        weightKg = profile.weightKg
        this.autoPauseKmh = autoPauseKmh
        lastWholeKm = 0
        halfwayDone = false
        running = true
        paused = false
        startedAt = System.currentTimeMillis()
        movingMs = 0
        distanceKm = 0.0
        maxSpeedKmh = 0.0
        elevGain = 0.0
        estKcal = 0.0
        goalReached = false
        points.clear()
        smoothAlt = Double.NaN
        climbBase = Double.NaN
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    fun finish() {
        running = false
        paused = false
    }

    // Új GPS-hely. Tekerés közben ebből nő a táv, a szintemelkedés és az útvonal.
    fun onLocation(loc: Location) {
        val prev = lastLocation
        val now = System.currentTimeMillis()
        val dtMs = if (prev != null) now - lastFixAt else 0L
        lastFixAt = now
        gpsError = null
        val accurate = !loc.hasAccuracy() || loc.accuracy <= MAX_ACCURACY_M
        if (!accurate && prev != null) return // a pontatlan helyet eldobjuk, a régi marad
        lastLocation = loc

        val dist = if (prev != null) prev.distanceTo(loc).toDouble() else 0.0
        speedKmh = when {
            loc.hasSpeed() -> loc.speed * 3.6
            dtMs > 0 -> dist / (dtMs / 1000.0) * 3.6
            else -> 0.0
        }
        if (!isCounting) return

        if (speedKmh >= DRIFT_KMH && speedKmh < 120) {
            distanceKm += dist / 1000.0
            if (speedKmh > maxSpeedKmh) maxSpeedKmh = speedKmh
            if (loc.hasAltitude()) climb(loc.altitude)
        }
        val last = points.lastOrNull()
        if (last == null || distanceBetween(last[0], last[1], loc.latitude, loc.longitude) >= MIN_POINT_GAP_M) {
            points += doubleArrayOf(loc.latitude, loc.longitude, if (loc.hasAltitude()) loc.altitude else Double.NaN, now.toDouble())
        }
    }

    // Másodpercenként: mozgásidő és kalória. Jelzi, ha épp most teljesült a cél, a fele,
    // vagy betelt egy újabb kilométer.
    fun onTick(dtMs: Long): RideEvent {
        if (!hasFix) speedKmh = 0.0
        if (!isCounting || autoPaused) return RideEvent.NONE
        movingMs += dtMs
        estKcal += Cycling.met(speedKmh) * weightKg * dtMs / 3_600_000.0
        if (!goalReached && goalMet) {
            goalReached = true
            return RideEvent.GOAL
        }
        if (!halfwayDone && !goalReached && mode != WorkoutMode.FREE && progress >= 0.5f) {
            halfwayDone = true
            return RideEvent.HALFWAY
        }
        val km = distanceKm.toInt()
        if (km > lastWholeKm) {
            lastWholeKm = km
            return RideEvent.KM
        }
        return RideEvent.NONE
    }

    // Szintemelkedés: simított magasság, és csak a küszöbnél nagyobb emelkedést számoljuk.
    private fun climb(alt: Double) {
        smoothAlt = if (smoothAlt.isNaN()) alt else smoothAlt * 0.8 + alt * 0.2
        if (climbBase.isNaN() || smoothAlt < climbBase) {
            climbBase = smoothAlt
        } else if (smoothAlt - climbBase >= CLIMB_STEP_M) {
            elevGain += smoothAlt - climbBase
            climbBase = smoothAlt
        }
    }

    // Az útvonal JSON-ban a mentéshez: [[lat, lon, magasság, idő], …]
    fun pointsJson(): String = JSONArray(points.map { p ->
        JSONArray().put(p[0]).put(p[1]).put(if (p[2].isNaN()) JSONObject.NULL else p[2]).put(p[3].toLong())
    }).toString()

    private const val MIN_POINT_GAP_M = 5.0

    private fun distanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, out)
        return out[0].toDouble()
    }
}

// OpenStreetMap-térkép (osmdroid) közös beállítása: a csempék az app saját mappájába kerülnek,
// így engedély nem kell hozzá, és a Beállításokból törölhetők.
object Maps {
    fun init(c: Context) {
        Configuration.getInstance().apply {
            userAgentValue = c.packageName
            osmdroidBasePath = File(c.filesDir, "osmdroid")
            osmdroidTileCache = tileDir(c)
        }
    }

    private fun tileDir(c: Context) = File(c.cacheDir, "osmdroid-tiles")

    fun tileBytes(c: Context): Long = tileDir(c).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clearTiles(c: Context) {
        tileDir(c).deleteRecursively()
    }
}

object Cycling {
    // MET a sebesség szerint (Compendium of Physical Activities, kerékpározás). Becslés:
    // a szél, az emelkedő és a kerékpár típusa nincs benne.
    fun met(kmh: Double) = when {
        kmh < 16 -> 4.0
        kmh < 19 -> 6.8
        kmh < 22 -> 8.0
        kmh < 25 -> 10.0
        kmh < 30 -> 12.0
        else -> 15.8
    }
}
