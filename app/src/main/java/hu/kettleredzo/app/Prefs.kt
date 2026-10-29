package hu.kettleredzo.app

import android.content.Context
import java.util.Locale

object Prefs {
    private fun prefs(c: Context) = c.getSharedPreferences("beallitasok", Context.MODE_PRIVATE)

    fun host(c: Context): String = prefs(c).getString("host", "") ?: ""
    fun setHost(c: Context, value: String) = prefs(c).edit().putString("host", value.trim()).apply()

    fun overlay(c: Context): Boolean = prefs(c).getBoolean("overlay", true)
    fun setOverlay(c: Context, value: Boolean) = prefs(c).edit().putBoolean("overlay", value).apply()

    // A legutóbb választott edzésmód, módonként megjegyzett céllal
    fun mode(c: Context): WorkoutMode = WorkoutMode.of(prefs(c).getString("mode", null))
    fun setMode(c: Context, value: WorkoutMode) = prefs(c).edit().putString("mode", value.code).apply()

    fun goal(c: Context, mode: WorkoutMode): Double {
        val default = when (mode) {
            WorkoutMode.DISTANCE -> 5f
            WorkoutMode.KCAL -> 500f
            WorkoutMode.TIME -> 30f
            else -> 0f
        }
        return prefs(c).getFloat("goal_" + mode.code, default).toDouble()
    }
    fun setGoal(c: Context, mode: WorkoutMode, value: Double) =
        prefs(c).edit().putFloat("goal_" + mode.code, value.toFloat()).apply()

    // Kerékpár: külön megjegyzett mód és célok
    fun bikeMode(c: Context): WorkoutMode = WorkoutMode.of(prefs(c).getString("bikeMode", null))
    fun setBikeMode(c: Context, value: WorkoutMode) = prefs(c).edit().putString("bikeMode", value.code).apply()

    fun bikeGoal(c: Context, mode: WorkoutMode): Double {
        val default = when (mode) {
            WorkoutMode.DISTANCE -> 20f
            WorkoutMode.KCAL -> 500f
            WorkoutMode.TIME -> 60f
            else -> 0f
        }
        return prefs(c).getFloat("bikeGoal_" + mode.code, default).toDouble()
    }
    fun setBikeGoal(c: Context, mode: WorkoutMode, value: Double) =
        prefs(c).edit().putFloat("bikeGoal_" + mode.code, value.toFloat()).apply()

    // Kerékpáros beállítások
    fun autoPause(c: Context): Boolean = prefs(c).getBoolean("bikeAutoPause", true)
    fun setAutoPause(c: Context, value: Boolean) = prefs(c).edit().putBoolean("bikeAutoPause", value).apply()
    fun autoPauseKmh(c: Context): Int = prefs(c).getInt("bikeAutoPauseKmh", 3)
    fun setAutoPauseKmh(c: Context, value: Int) = prefs(c).edit().putInt("bikeAutoPauseKmh", value).apply()

    // Térkép nagyítása tekerés közben (osmdroid zoomszint)
    fun mapZoom(c: Context): Double = prefs(c).getFloat("bikeMapZoom", 17f).toDouble()
    fun setMapZoom(c: Context, value: Double) = prefs(c).edit().putFloat("bikeMapZoom", value.toFloat()).apply()

    // Kézi elhúzás után ennyi mp múlva követ újra (0 = csak a Követés gombra)
    fun followResumeSec(c: Context): Int = prefs(c).getInt("bikeFollowResume", 10)
    fun setFollowResumeSec(c: Context, value: Int) = prefs(c).edit().putInt("bikeFollowResume", value).apply()

    fun kmBeep(c: Context): Boolean = prefs(c).getBoolean("bikeKmBeep", false)
    fun setKmBeep(c: Context, value: Boolean) = prefs(c).edit().putBoolean("bikeKmBeep", value).apply()
    fun halfwayBeep(c: Context): Boolean = prefs(c).getBoolean("bikeHalfway", true)
    fun setHalfwayBeep(c: Context, value: Boolean) = prefs(c).edit().putBoolean("bikeHalfway", value).apply()

    fun rideScreenOn(c: Context): Boolean = prefs(c).getBoolean("bikeScreenOn", true)
    fun setRideScreenOn(c: Context, value: Boolean) = prefs(c).edit().putBoolean("bikeScreenOn", value).apply()

    // A mentett kerékpáros módot betölti a közös állapotba (tekerés közben nem nyúlunk hozzá).
    fun applyBikeMode(c: Context) {
        if (BikeState.running) return
        val m = bikeMode(c)
        BikeState.configure(m, bikeGoal(c, m))
    }

    fun programWeek(c: Context): Int = prefs(c).getInt("programWeek", 1)
    fun setProgramWeek(c: Context, value: Int) = prefs(c).edit().putInt("programWeek", value).apply()

    // A mentett módot betölti a közös állapotba (edzés közben nem nyúlunk hozzá).
    fun applyMode(c: Context) {
        if (KettlerState.running) return
        val m = mode(c)
        KettlerState.configure(m, goal(c, m), Programs.week(programWeek(c)))
    }

    fun currentProfileId(c: Context): Long = prefs(c).getLong("profileId", -1)
    fun setCurrentProfileId(c: Context, value: Long) = prefs(c).edit().putLong("profileId", value).apply()

    // Kézzel beállított fékfokozat (a gép nem tudja megmérni). A fokozatok száma beállítható.
    const val LEVEL_MIN = 1
    const val LEVEL_COUNT_MAX = 30
    fun levelCount(c: Context): Int = prefs(c).getInt("levelCount", 8).coerceIn(2, LEVEL_COUNT_MAX)
    fun setLevelCount(c: Context, value: Int) =
        prefs(c).edit().putInt("levelCount", value.coerceIn(2, LEVEL_COUNT_MAX)).apply()
    fun level(c: Context): Int = prefs(c).getInt("level", 1).coerceIn(LEVEL_MIN, levelCount(c))
    fun setLevel(c: Context, value: Int) =
        prefs(c).edit().putInt("level", value.coerceIn(LEVEL_MIN, levelCount(c))).apply()

    fun beeps(c: Context): Boolean = prefs(c).getBoolean("beeps", true)
    fun setBeeps(c: Context, value: Boolean) = prefs(c).edit().putBoolean("beeps", value).apply()
}

val HU: Locale = Locale.forLanguageTag("hu")

fun formatTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(HU, "%d:%02d:%02d", h, m, sec) else String.format(HU, "%d:%02d", m, sec)
}

// Visszaszámlálásnál felfelé kerekítünk, hogy a 0:00 csak a végén jelenjen meg.
fun formatTimeUp(ms: Long): String = formatTime(ms + 999)

fun formatNum(value: Double, decimals: Int): String = String.format(HU, "%.${decimals}f", value)
