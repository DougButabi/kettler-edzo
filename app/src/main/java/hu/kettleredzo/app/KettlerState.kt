package hu.kettleredzo.app

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

    // Az aktuális (vagy utolsó) edzés
    var running = false
    var paused = false
    var profileId = -1L
    var startedAt = 0L
    var movingMs = 0L
    var workoutKm = 0.0
    var maxSpeedKmh = 0.0
    private var lastTotalKm = Double.NaN

    var activityVisible = false

    val isMoving get() = connected && rpm > 0.5
    val isCounting get() = running && !paused

    val avgSpeedKmh get() = if (movingMs >= 5000) workoutKm / (movingMs / 3_600_000.0) else 0.0
    val kcal get() = workoutKm * KCAL_PER_KM

    private val listeners = mutableSetOf<() -> Unit>()
    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    fun notifyChanged() = listeners.toList().forEach { it() }

    fun start(profileId: Long) {
        this.profileId = profileId
        running = true
        paused = false
        startedAt = System.currentTimeMillis()
        movingMs = 0
        workoutKm = 0.0
        maxSpeedKmh = 0.0
        lastTotalKm = totalKm
    }

    fun onSpeed(kmh: Double) {
        speedKmh = kmh
        if (isCounting && kmh > maxSpeedKmh) maxSpeedKmh = kmh
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
