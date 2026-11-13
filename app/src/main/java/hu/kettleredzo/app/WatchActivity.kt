package hu.kettleredzo.app

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

// Élő elliptikus edzés egy másik készüléken (a ULC /api/edzes/elo végpontja). Az ESP egyszerre csak
// kevés élő kapcsolatot bír: ha egy másik készülék épp edzést vezet, ez a készülék nem kapcsolódik
// az ESP-hez, hanem nézőként a WatchActivity-ben mutatja az edzést.
object LiveWatch {
    class Info(val active: Boolean, val own: Boolean, val device: String, val data: JSONObject?)

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // "Mégis itt edzek": eddig nem váltunk nézőre
    var ignoreUntil = 0L

    fun ignore() {
        ignoreUntil = System.currentTimeMillis() + 3 * 3_600_000L
    }

    val ignored get() = System.currentTimeMillis() < ignoreUntil

    // Háttérszálon lekérdezi; a választ (hiba esetén null) a fő szálon adja vissza.
    fun check(c: Context, onDone: (Info?) -> Unit) {
        val app = c.applicationContext
        if (!Sync.configured(app)) {
            onDone(null)
            return
        }
        executor.execute {
            val info = try {
                val conn = (URL(Prefs.syncUrl(app) + "/api/edzes/elo").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2000
                    readTimeout = 2000
                    setRequestProperty("Authorization", "Bearer " + Prefs.syncToken(app))
                }
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                conn.disconnect()
                val o = JSONObject(text)
                Info(o.optBoolean("active"), o.optBoolean("own"), o.optString("device"), o.optJSONObject("data"))
            } catch (e: Exception) {
                null
            }
            main.post { onDone(info) }
        }
    }

    // Egy másik készüléken fut edzés (és nem kérték, hogy mégis itt eddzenek)
    fun otherActive(info: Info?) = info != null && info.active && !info.own && !ignored
}

class WatchActivity : KettlerActivity() {
    private lateinit var ui: Ui
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var deviceV: TextView
    private lateinit var nameV: TextView
    private lateinit var statusV: TextView
    private lateinit var doneV: TextView
    private lateinit var percentV: TextView
    private lateinit var progressDone: View
    private lateinit var progressRest: View
    private lateinit var timeV: TextView
    private lateinit var etaV: TextView
    private lateinit var kmV: TextView
    private lateinit var kcalV: TextView
    private lateinit var hrV: TextView
    private var misses = 0

    private val poll = object : Runnable {
        override fun run() {
            LiveWatch.check(this@WatchActivity) { info ->
                if (isFinishing) return@check
                when {
                    info != null && info.active && !info.own -> {
                        misses = 0
                        render(info)
                    }
                    // Hálózati hiba: kétszer még várunk, utána visszaadjuk a gépet ennek a készüléknek
                    info == null && ++misses < 3 -> {}
                    else -> {
                        setResult(RESULT_ENDED)
                        finish()
                        return@check
                    }
                }
                handler.postDelayed(this, 3000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this).apply { workoutButtons() }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        handler.post(poll)
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        setResult(RESULT_CANCELED)
        super.onBackPressed()
    }

    private fun buildUi(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(ui.ipx(60), ui.ipx(50), ui.ipx(60), ui.ipx(40))
        }
        deviceV = ui.label("ÉLŐ EDZÉS")
        box.addView(deviceV)
        nameV = ui.text("…", 56, INK, ui.bold)
        box.addView(nameV, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(10) })
        statusV = ui.text("", 26, MUTED, ui.medium)
        box.addView(statusV)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        doneV = ui.text("–", 110, INK, ui.bold)
        percentV = ui.text("", 40, MUTED, ui.bold)
        row.addView(doneV)
        row.addView(percentV, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ui.ipx(30) })
        box.addView(row, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(24) })

        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ui.rounded(TRACK, 10)
            clipToOutline = true
        }
        progressDone = View(this).apply { background = ui.rounded(YELLOW, 10) }
        progressRest = View(this)
        track.addView(progressDone, ui.lp(0, MATCH_PARENT, 0f))
        track.addView(progressRest, ui.lp(0, MATCH_PARENT, 1f))
        box.addView(track, ui.lp(MATCH_PARENT, ui.ipx(20)).apply { topMargin = ui.ipx(16) })

        timeV = ui.text("–", 44, INK, ui.bold)
        etaV = ui.text("–", 44, INK, ui.bold)
        kmV = ui.text("–", 44, INK, ui.bold)
        kcalV = ui.text("–", 44, INK, ui.bold)
        hrV = ui.text("–", 44, INK, ui.bold)
        val cells = listOf("EDZÉSIDŐ" to timeV, "VÁRHATÓ VÉGE" to etaV, "TÁV" to kmV, "ENERGIA" to kcalV, "PULZUS" to hrV)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cells.chunked(if (ui.portrait) 2 else 5).forEach { chunk ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { (label, v) ->
                val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                cell.addView(ui.label(label))
                cell.addView(v)
                r.addView(cell, ui.lp(0, WRAP_CONTENT, 1f))
            }
            repeat((if (ui.portrait) 2 else 5) - chunk.size) { r.addView(View(this), ui.lp(0, 1, 1f)) }
            grid.addView(r, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(24) })
        }
        box.addView(grid, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(16) })

        box.addView(ui.note("Egy másik készülék vezeti az edzést. Ez a készülék most nem kapcsolódik a géphez, " +
            "mert az egyszerre csak egy-két kapcsolatot bír, és a másikat ledobhatja."),
            ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(36) })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(ui.button("Vissza", BUTTON, INK) {
            setResult(RESULT_CANCELED)
            finish()
        }.apply { setPadding(ui.ipx(50), ui.ipx(20), ui.ipx(50), ui.ipx(20)) })
        buttons.addView(ui.button("Mégis itt edzek", INK, android.graphics.Color.WHITE) {
            LiveWatch.ignore()
            setResult(RESULT_OK)
            finish()
        }.apply { setPadding(ui.ipx(50), ui.ipx(20), ui.ipx(50), ui.ipx(20)) },
            ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ui.ipx(24) })
        box.addView(buttons, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(24) })

        return ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(box)
        }
    }

    private fun render(info: LiveWatch.Info) {
        val d = info.data ?: return
        deviceV.text = "ÉLŐ EDZÉS · " + info.device.uppercase(Locale.ROOT)
        nameV.text = d.optString("name").ifBlank { "Valaki" }
        val state = d.optString("state")
        val status = d.optString("status")
        statusV.text = listOf(
            when (state) {
                "szunet" -> "Szünet"
                "kesz" -> "Kész"
                else -> "Edz"
            },
            d.optString("mode_title"),
            status,
        ).filter { it.isNotBlank() }.joinToString("  ·  ")
        val unit = d.optString("unit")
        val goal = d.optDouble("goal", 0.0)
        val done = d.optDouble("done", 0.0)
        fun fmt(v: Double) = when (unit) {
            "perc" -> formatTime((v * 60_000).toLong())
            "kcal" -> formatNum(v, 0)
            else -> formatNum(v, 2)
        }
        val unitText = if (unit == "perc") "" else " $unit"
        doneV.text = if (goal > 0) "${fmt(done)} / ${fmt(goal)}$unitText" else "${fmt(done)}$unitText"
        val pct = d.optInt("percent", 0)
        percentV.text = if (goal > 0) "$pct%" else ""
        val p = (pct / 100f).coerceIn(0f, 1f)
        (progressDone.layoutParams as LinearLayout.LayoutParams).weight = p
        (progressRest.layoutParams as LinearLayout.LayoutParams).weight = 1f - p
        progressDone.requestLayout()
        timeV.text = formatTime(d.optLong("moving_s") * 1000)
        etaV.text = if (d.has("eta")) clock.format(Date(d.optLong("eta") * 1000)) else "–"
        kmV.text = formatNum(d.optDouble("km", 0.0), 2) + " km"
        kcalV.text = "${d.optLong("kcal")} kcal"
        hrV.text = if (d.has("hr")) "${d.optInt("hr")} bpm" else "–"
    }

    private val clock = SimpleDateFormat("HH:mm", Locale.ROOT)

    companion object {
        // A másik készüléken véget ért az edzés: ez a készülék kapcsolódhat a géphez
        const val RESULT_ENDED = RESULT_FIRST_USER
    }
}
