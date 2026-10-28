package hu.kettleredzo.app

import android.content.Context
import java.util.Locale

object Prefs {
    private fun prefs(c: Context) = c.getSharedPreferences("beallitasok", Context.MODE_PRIVATE)

    fun host(c: Context): String = prefs(c).getString("host", "") ?: ""
    fun setHost(c: Context, value: String) = prefs(c).edit().putString("host", value.trim()).apply()

    fun overlay(c: Context): Boolean = prefs(c).getBoolean("overlay", true)
    fun setOverlay(c: Context, value: Boolean) = prefs(c).edit().putBoolean("overlay", value).apply()

    fun goalMin(c: Context): Int = prefs(c).getInt("goalMin", 30)
    fun setGoalMin(c: Context, value: Int) = prefs(c).edit().putInt("goalMin", value).apply()

    // Kézzel beállított fékfokozat (a gép nem tudja megmérni)
    const val LEVEL_MIN = 1
    const val LEVEL_MAX = 30
    fun level(c: Context): Int = prefs(c).getInt("level", 1)
    fun setLevel(c: Context, value: Int) =
        prefs(c).edit().putInt("level", value.coerceIn(LEVEL_MIN, LEVEL_MAX)).apply()
}

private val HU = Locale.forLanguageTag("hu")

fun formatTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(HU, "%d:%02d:%02d", h, m, sec) else String.format(HU, "%d:%02d", m, sec)
}

fun formatNum(value: Double, decimals: Int): String = String.format(HU, "%.${decimals}f", value)
