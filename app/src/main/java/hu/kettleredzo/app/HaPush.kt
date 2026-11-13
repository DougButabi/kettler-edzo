package hu.kettleredzo.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// Élő edzésadat a Home Assistantnek (Nappali dashboard, tréner kártya): elliptikus edzés közben
// 5 mp-enként, állapotváltáskor (szünet, cél teljesítve, vége) azonnal POST a webhookra.
// Hiba esetén csendben kihagyja; az edzést semmi nem akasztja meg.
object HaPush {
    private const val EVERY_MS = 5_000L
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var lastSentAt = 0L
    private var lastState = ""

    // Az utolsó küldés eredménye a Beállításokhoz (pl. "18:42:10 · rendben")
    @Volatile var status = ""
        private set

    // A szolgáltatás másodpercenkénti ütemezőjéből: futó edzésnél küld, ha esedékes vagy változott az állapot.
    fun tick(c: Context) {
        val s = KettlerState
        if (!s.running) return
        val state = stateOf()
        val now = System.currentTimeMillis()
        if (state != lastState || now - lastSentAt >= EVERY_MS) send(c, state)
    }

    // Az edzés lezárásakor (mentve vagy sem), a KettlerState.finish() után.
    fun finished(c: Context) = send(c, "vege", force = true)

    private fun stateOf(): String {
        val s = KettlerState
        return when {
            s.goalReached && s.paused -> "kesz"
            s.paused -> "szunet"
            else -> "fut"
        }
    }

    private fun send(c: Context, state: String, force: Boolean = false) {
        val url = Prefs.haWebhook(c)
        // A ULC is megkapja (élő állapot a többi készüléknek, lásd LiveWatch)
        val ulc = Sync.configured(c)
        if (url.isBlank() && !ulc) return
        // Ha az előző kérés még lóg (pl. nem érhető el a HA), a soron következő periodikusat kihagyjuk.
        if (!force && busy.get()) return
        lastState = state
        lastSentAt = System.currentTimeMillis()
        val body = payload(c, state).toString()
        val app = c.applicationContext
        busy.set(true)
        executor.execute {
            try {
                if (url.isNotBlank()) {
                    status = try {
                        val code = post(url, body, null)
                        clock() + if (code in 200..299) " · rendben" else " · hiba: HTTP $code"
                    } catch (e: Exception) {
                        clock() + " · nem érhető el (${e.javaClass.simpleName})"
                    }
                }
                if (ulc) {
                    try {
                        post(Prefs.syncUrl(app) + "/api/edzes/elo", body, Prefs.syncToken(app))
                    } catch (_: Exception) {
                    }
                }
            } finally {
                busy.set(false)
            }
        }
    }

    private fun post(url: String, body: String, token: String?): Int {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 3000
            readTimeout = 3000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        conn.disconnect()
        return code
    }

    private fun clock() = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())

    private fun payload(c: Context, state: String): JSONObject {
        val s = KettlerState
        val p = Db.get(c).profile(s.profileId)
        val now = System.currentTimeMillis()
        val (unit, goal, done) = when (s.mode) {
            WorkoutMode.DISTANCE -> Triple("km", s.goal, s.workoutKm)
            WorkoutMode.KCAL -> Triple("kcal", s.goal, s.kcal)
            WorkoutMode.TIME -> Triple("perc", s.goal, s.movingMs / 60_000.0)
            WorkoutMode.PROGRAM -> Triple("perc", (s.program?.totalMs ?: 0L) / 60_000.0, s.programElapsedMs / 60_000.0)
            WorkoutMode.FREE -> Triple("km", 0.0, s.workoutKm)
        }
        return JSONObject().apply {
            put("state", state)
            put("name", p?.name ?: "")
            put("sex", p?.sex?.code ?: "")
            put("mode", s.mode.code)
            put("mode_title", if (s.mode == WorkoutMode.PROGRAM) s.program?.title ?: s.mode.title else s.mode.title)
            put("unit", unit)
            put("goal", round(goal, 2))
            put("done", round(done, 2))
            put("percent", Math.round(s.progress * 100))
            put("reached", s.goalReached)
            put("moving_s", s.movingMs / 1000)
            put("km", round(s.workoutKm, 2))
            put("kcal", Math.round(s.kcal))
            put("speed", round(s.speedKmh, 1))
            put("avg_speed", round(s.avgSpeedKmh, 1))
            s.heartRate?.let { put("hr", it) }
            put("status", s.goalStatus() ?: "")
            put("started", s.startedAt / 1000)
            eta(now)?.let { put("eta", it / 1000) }
            put("ts", now / 1000)
        }
    }

    // A cél várható elérése (epoch ms) az eddigi tempóból; szabad edzésnél, teljesült célnál vagy túl korán null.
    private fun eta(now: Long): Long? {
        val s = KettlerState
        if (s.goalReached || !s.running) return null
        val remainingMs: Double = when (s.mode) {
            WorkoutMode.TIME -> s.remainingMs.toDouble()
            WorkoutMode.PROGRAM -> ((s.program?.totalMs ?: return null) - s.programElapsedMs).toDouble()
            WorkoutMode.DISTANCE, WorkoutMode.KCAL -> {
                val done = if (s.mode == WorkoutMode.DISTANCE) s.workoutKm else s.kcal
                val left = if (s.mode == WorkoutMode.DISTANCE) s.remainingKm else s.remainingKcal
                if (s.movingMs < 30_000 || done <= 0.0) return null
                left / (done / s.movingMs)
            }
            WorkoutMode.FREE -> return null
        }
        return now + remainingMs.toLong()
    }

    private fun round(v: Double, digits: Int): Double {
        if (v.isNaN() || v.isInfinite()) return 0.0
        val f = Math.pow(10.0, digits.toDouble())
        return Math.round(v * f) / f
    }
}
