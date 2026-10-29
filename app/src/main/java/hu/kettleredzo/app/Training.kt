package hu.kettleredzo.app

import android.media.AudioManager
import android.media.ToneGenerator

enum class WorkoutMode(val code: String, val title: String) {
    FREE("free", "Szabad edzés"),
    DISTANCE("distance", "Táv"),
    KCAL("kcal", "Kalória"),
    TIME("time", "Idő"),
    PROGRAM("program", "Program");

    companion object {
        fun of(code: String?) = entries.firstOrNull { it.code == code } ?: FREE
    }
}

enum class SegmentType(val title: String) {
    WORK("EDZÉS"),
    REST("PIHENŐ"),
}

data class Segment(val type: SegmentType, val seconds: Int)

data class Program(val week: Int, val segments: List<Segment>) {
    val title get() = "Kezdő program · $week. hét"
    val totalMs get() = segments.sumOf { it.seconds } * 1000L

    // pl. "2′ edzés · 1′ pihenő · 2′ · 1′ · 2′"
    val summary: String
        get() {
            var workNamed = false
            var restNamed = false
            return segments.joinToString(" · ") {
                val min = "${it.seconds / 60}′"
                when {
                    it.type == SegmentType.WORK && !workNamed -> { workNamed = true; "$min edzés" }
                    it.type == SegmentType.REST && !restNamed -> { restNamed = true; "$min pihenő" }
                    else -> min
                }
            }
        }
}

// A kézikönyv kezdő programja: heti 3 alkalom, edzésszakaszok között 1 perc pihenővel.
// Előtte és utána kb. 5 perc gimnasztika (bemelegítés, levezetés) a gépen kívül.
object Programs {
    const val SESSIONS_PER_WEEK = 3

    val BEGINNER = listOf(
        weeks(1, 2, 2, 2),
        weeks(2, 3, 3, 2),
        weeks(3, 4, 3, 3),
        weeks(4, 5, 4, 4),
    )

    private fun weeks(week: Int, vararg workMinutes: Int): Program {
        val segments = mutableListOf<Segment>()
        workMinutes.forEachIndexed { i, m ->
            if (i > 0) segments += Segment(SegmentType.REST, 60)
            segments += Segment(SegmentType.WORK, m * 60)
        }
        return Program(week, segments)
    }

    fun week(n: Int) = BEGINNER.getOrNull(n - 1) ?: BEGINNER[0]

    fun recommendedWeek(completedSessions: Int) = (completedSessions / SESSIONS_PER_WEEK + 1).coerceAtMost(BEGINNER.size)
}

// pl. "Táv · 10 km", "Program · 2. hét"
fun modeLabel(mode: WorkoutMode, goal: Double?, programWeek: Int?): String = when (mode) {
    WorkoutMode.FREE -> mode.title
    WorkoutMode.DISTANCE -> "${mode.title} · ${goalText(goal ?: 0.0)} km"
    WorkoutMode.KCAL -> "${mode.title} · ${goalText(goal ?: 0.0)} kcal"
    WorkoutMode.TIME -> "${mode.title} · ${goalText(goal ?: 0.0)} perc"
    WorkoutMode.PROGRAM -> "${mode.title} · ${programWeek ?: 1}. hét"
}

fun goalText(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else formatNum(v, 1)

// Rövid hangjelzések az értesítési hangcsatornán, hogy a Kodi hangja mellett is hallani lehessen.
object Beeper {
    var enabled = true
    private var tone: ToneGenerator? = null

    private fun gen(): ToneGenerator? = tone ?: try {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, ToneGenerator.MAX_VOLUME).also { tone = it }
    } catch (e: RuntimeException) {
        null
    }

    fun short() {
        if (enabled) gen()?.startTone(ToneGenerator.TONE_DTMF_1, 150)
    }

    fun long() {
        if (enabled) gen()?.startTone(ToneGenerator.TONE_DTMF_9, 700)
    }

    // A Beállításokból: 3-2-1 és egy hosszú hang, akkor is, ha a hangjelzés ki van kapcsolva.
    fun test(handler: android.os.Handler) {
        for (i in 0..2) handler.postDelayed({ gen()?.startTone(ToneGenerator.TONE_DTMF_1, 150) }, i * 1000L)
        handler.postDelayed({ gen()?.startTone(ToneGenerator.TONE_DTMF_9, 700) }, 3000L)
    }

    fun release() {
        tone?.release()
        tone = null
    }
}
