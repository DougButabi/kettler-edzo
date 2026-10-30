package hu.kettleredzo.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log

// Hangos visszajelzés a cél felé haladásról: előre legenerált magyar mondatok (res/raw/v_<hang>_<kulcs>_<n>.mp3,
// lásd tools/hangok.py), férfi vagy női hangon, kulcsonként véletlenszerű változattal. Bemondás közben a
// többi hang (zene, Kodi) halkabb lesz.
object Voice {
    private const val TAG = "Voice"
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null

    private val ATTRS: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun enabled(c: Context) = Prefs.voiceEvery(c) != VoiceEvery.OFF

    // Egy véletlen változat a kulcshoz (pl. "h" = félúton). Ha épp szól valami, azt leállítja.
    fun say(c: Context, key: String) {
        val app = c.applicationContext
        val voice = if (Prefs.voiceMale(app)) "m" else "f"
        val ids = (1..10).map { app.resources.getIdentifier("v_${voice}_${key}_$it", "raw", app.packageName) }.filter { it != 0 }
        if (ids.isEmpty()) return
        stop(app)
        val am = app.getSystemService(AudioManager::class.java)
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(ATTRS).build()
        am?.requestAudioFocus(req)
        focus = req
        try {
            val afd = app.resources.openRawResourceFd(ids.random())
            player = MediaPlayer().apply {
                setAudioAttributes(ATTRS)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                setOnCompletionListener { stop(app) }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lejátszás", e)
            stop(app)
        }
    }

    private fun stop(c: Context) {
        player?.release()
        player = null
        focus?.let { c.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(it) }
        focus = null
    }
}

// Milyen gyakran szóljon: a cél fele, harmadai vagy negyedei. A 95% és a cél mindig szól, ha be van kapcsolva.
enum class VoiceEvery(val code: String, val title: String, val fractions: List<Double>) {
    OFF("off", "Ki", emptyList()),
    HALF("half", "Fél", listOf(0.5)),
    THIRD("third", "Harmad", listOf(1.0 / 3, 2.0 / 3)),
    QUARTER("quarter", "Negyed", listOf(0.25, 0.5, 0.75));

    companion object {
        fun of(code: String?) = entries.firstOrNull { it.code == code } ?: QUARTER
    }
}

// Egy edzés mérföldkövei: melyik hangzott már el. Ha a következő 30 mp-en belül jönne (a mostani
// tempó alapján), a korábbit kihagyjuk, hogy ne szóljon kettő egymás után.
class Milestones {
    private val done = mutableSetOf<String>()

    fun reset() = done.clear()

    // progress: 0..1, elapsedMs: az eddig számolt idő; lastAt: az „utolsó km / perc” helye (0..1), ha van.
    fun check(c: Context, progress: Double, elapsedMs: Long, lastKey: String?, lastAt: Double?) {
        if (!Voice.enabled(c) || elapsedMs <= 0) return
        val stones = Prefs.voiceEvery(c).fractions.map { it to key(it) }.toMutableList()
        if (lastKey != null && lastAt != null && lastAt > 0) stones += lastAt to lastKey
        stones += 0.95 to "p95"
        stones.sortBy { it.first }
        val rate = progress / elapsedMs
        stones.forEachIndexed { i, (at, key) ->
            if (key in done || progress < at) return@forEachIndexed
            done += key
            val next = stones.drop(i + 1).firstOrNull { it.second !in done }?.first ?: 1.0
            val eta = if (rate > 0) (next - progress) / rate else Double.MAX_VALUE
            if (eta >= 30_000) Voice.say(c, key)
        }
    }

    private fun key(f: Double) = when {
        f < 0.3 -> "q1"
        f < 0.4 -> "t1"
        f < 0.6 -> "h"
        f < 0.7 -> "t2"
        else -> "q3"
    }
}
