package hu.kettleredzo.app

import kotlin.math.max

data class SegmentState(val index: Int, val segment: Segment, val remainingMs: Long)

// Az app közös állapota. Csak a fő szálról írjuk és olvassuk.
object KettlerState {
    // A kézikönyv szerint elliptikus módban 1 km = 352 kJ (1 kcal = 4,184 kJ).
    const val KCAL_PER_KM = 352.0 / 4.184

    // Az ESP32 nyers adatai
    var connected = false
    var lastError: String? = null
    var rpm = 0.0
    var speedKmh = 0.0
    var totalKm = Double.NaN

    // A kiválasztott edzésmód és cél (km, kcal vagy perc; programnál a program)
    var mode = WorkoutMode.FREE
    var goal = 0.0
    var program: Program? = null

    // Az aktuális (vagy utolsó) edzés
    var running = false
    var paused = false
    var profileId = -1L
    var startedAt = 0L
    var movingMs = 0L
    var programElapsedMs = 0L
    var workoutKm = 0.0
    var maxSpeedKmh = 0.0
    var goalReached = false
    private var lastTotalKm = Double.NaN

    // Hány képernyője látszik az appnak (lásd KettlerActivity)
    var visibleScreens = 0
    val appVisible get() = visibleScreens > 0

    // Pulzus a pántról (a pánt bekötéséig mindig null)
    var heartRate: Int? = null

    val isMoving get() = connected && rpm > 0.5
    val isCounting get() = running && !paused

    val avgSpeedKmh get() = if (movingMs >= 5000) workoutKm / (movingMs / 3_600_000.0) else 0.0
    val kcal get() = workoutKm * KCAL_PER_KM

    // Haladás a cél felé, 0..1 (szabad edzésnél 0)
    val progress: Float
        get() {
            val p = when (mode) {
                WorkoutMode.FREE -> 0.0
                WorkoutMode.DISTANCE -> if (goal > 0) workoutKm / goal else 0.0
                WorkoutMode.KCAL -> if (goal > 0) kcal / goal else 0.0
                WorkoutMode.TIME -> if (goal > 0) movingMs / (goal * 60_000) else 0.0
                WorkoutMode.PROGRAM -> program?.let { programElapsedMs.toDouble() / it.totalMs } ?: 0.0
            }
            return p.toFloat().coerceIn(0f, 1f)
        }

    val goalMet get() = mode != WorkoutMode.FREE && progress >= 1f

    val remainingKm get() = max(0.0, goal - workoutKm)
    val remainingKcal get() = max(0.0, goal - kcal)
    val remainingMs get() = max(0L, (goal * 60_000).toLong() - movingMs)

    // A program aktuális szakasza és a szakaszból hátralévő idő
    fun segmentState(): SegmentState? {
        val segs = program?.segments ?: return null
        var t = programElapsedMs
        segs.forEachIndexed { i, s ->
            val ms = s.seconds * 1000L
            if (t < ms) return SegmentState(i, s, ms - t)
            t -= ms
        }
        return SegmentState(segs.size - 1, segs.last(), 0)
    }

    // Az overlay-sáv elejére: pl. "HÁTRA 6,58 km", "EDZÉS 1:23 · 2/5". Szabad edzésnél null.
    fun goalStatus(): String? {
        if (goalReached) return "CÉL TELJESÍTVE"
        return when (mode) {
            WorkoutMode.FREE -> null
            WorkoutMode.DISTANCE -> "HÁTRA ${formatNum(remainingKm, 2)} km"
            WorkoutMode.KCAL -> "HÁTRA ${formatNum(remainingKcal, 0)} kcal"
            WorkoutMode.TIME -> "HÁTRA ${formatTimeUp(remainingMs)}"
            WorkoutMode.PROGRAM -> segmentState()?.let {
                "${it.segment.type.title} ${formatTimeUp(it.remainingMs)} · ${it.index + 1}/${program!!.segments.size}"
            }
        }
    }

    private val listeners = mutableSetOf<() -> Unit>()
    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    fun notifyChanged() = listeners.toList().forEach { it() }

    fun configure(mode: WorkoutMode, goal: Double, program: Program?) {
        this.mode = mode
        this.goal = goal
        this.program = if (mode == WorkoutMode.PROGRAM) program else null
    }

    fun resetCounters() {
        movingMs = 0
        programElapsedMs = 0
        workoutKm = 0.0
        maxSpeedKmh = 0.0
        goalReached = false
    }

    fun start(profileId: Long) {
        this.profileId = profileId
        running = true
        paused = false
        startedAt = System.currentTimeMillis()
        resetCounters()
        lastTotalKm = totalKm
    }

    fun onSpeed(kmh: Double) {
        speedKmh = kmh
        if (isCounting && kmh > maxSpeedKmh) maxSpeedKmh = kmh
    }

    fun pause() {
        paused = true
    }

    // A cél teljesülése után a Folytatás cél nélkül megy tovább.
    fun resume() {
        paused = false
    }

    fun finish() {
        running = false
        paused = false
    }

    // A távot az ESP összesített értékéhez képest számoljuk, így az ESP-t nem kell nullázni.
    fun onTotalKm(km: Double) {
        if (running) {
            if (!paused && !lastTotalKm.isNaN()) {
                val delta = km - lastTotalKm
                // Ha csökkent, az ESP újraindult vagy nullázták: a nulla óta megtett utat adjuk hozzá.
                workoutKm += if (delta >= 0) delta else km
            }
            lastTotalKm = km
        }
        totalKm = km
    }

    fun onDisconnected(error: String?) {
        connected = false
        lastError = error
        rpm = 0.0
        speedKmh = 0.0
    }
}
