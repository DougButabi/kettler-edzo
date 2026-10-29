package hu.kettleredzo.app

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date

// Az ESPHome web_server élő eseményfolyamát (SSE, /events) olvassa egy háttérszálon.
// Megszakadás után azonnal újrakapcsolódik; a kiesést csak akkor jelzi, ha GRACE_MS alatt sem sikerül
// (az ESP időnként bontja a kapcsolatot), utána 3 másodpercenként próbálkozik.
class EspClient(
    host: String,
    private val onValue: (id: String, value: Double) -> Unit,
    private val onConnection: (connected: Boolean, error: String?) -> Unit,
) {
    private val url = "http://" + host.trim().removePrefix("http://").trimEnd('/') + "/events"

    @Volatile private var stopped = false
    @Volatile private var conn: HttpURLConnection? = null
    private var lastAlive = 0L // amikor utoljára jött adat az ESP-től
    private var connectedAt = 0L // a mostani kapcsolat kezdete (0: nem jött létre)
    private var lines = 0

    // Hibakereséshez: egy sor a megszakadt kapcsolatról a Beállítások → Kapcsolat oldalra.
    private fun record(error: String?) {
        val now = System.currentTimeMillis()
        val text = if (connectedAt == 0L) {
            "nem sikerült kapcsolódni – ${error ?: "?"}"
        } else {
            "${(now - connectedAt) / 1000} mp-ig élt, $lines sor, utolsó adat ${(now - lastAlive) / 1000} mp-e – " +
                (error ?: "az ESP bontotta")
        }
        synchronized(history) {
            history.addFirst(timeFormat.format(Date(now)) + "  " + text)
            while (history.size > HISTORY_SIZE) history.removeLast()
        }
    }
    private val thread = Thread { loop() }.apply { isDaemon = true }

    fun start() = thread.start()

    fun stop() {
        stopped = true
        thread.interrupt()
        Thread { conn?.disconnect() }.start()
    }

    private fun loop() {
        while (!stopped) {
            var error: String? = null
            connectedAt = 0L
            lines = 0
            try {
                readStream()
            } catch (e: Exception) {
                error = e.javaClass.simpleName + ": " + e.message
                Log.w(TAG, "Kapcsolat megszakadt ($url): $error")
            }
            if (stopped) break
            record(error)
            val lost = System.currentTimeMillis() - lastAlive > GRACE_MS
            if (lost) onConnection(false, error)
            try {
                Thread.sleep(if (lost) 3000 else 300)
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun readStream() {
        val c = URL(url).openConnection() as HttpURLConnection
        conn = c
        c.connectTimeout = 5000
        c.readTimeout = 20000 // az ESP 10 mp-enként pingel, ennyi csend után halottnak vesszük
        c.setRequestProperty("Accept", "text/event-stream")
        c.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            lastAlive = System.currentTimeMillis()
            connectedAt = lastAlive
            onConnection(true, null)
            var event = ""
            val data = StringBuilder()
            while (!stopped) {
                val line = reader.readLine() ?: break
                lastAlive = System.currentTimeMillis()
                lines++
                when {
                    line.isEmpty() -> {
                        if (event == "state" && data.isNotEmpty()) handleState(data.toString())
                        event = ""
                        data.setLength(0)
                    }
                    line.startsWith("event:") -> event = line.substring(6).trim()
                    line.startsWith("data:") -> data.append(line.substring(5).trim())
                }
            }
        }
    }

    private fun handleState(json: String) {
        try {
            val o = JSONObject(json)
            val value = o.optDouble("value", Double.NaN)
            if (value.isNaN()) return
            val id = Normalizer.normalize(o.optString("id"), Normalizer.Form.NFC)
            onValue(id, value)
        } catch (e: Exception) {
            Log.w(TAG, "Hibás esemény: $json")
        }
    }

    companion object {
        private const val TAG = "EspClient"
        private const val GRACE_MS = 5000L
        private const val HISTORY_SIZE = 8
        private val history = ArrayDeque<String>()
        private val timeFormat = SimpleDateFormat("HH:mm:ss", HU)

        // A legutóbbi megszakadások, a legfrissebb elöl
        fun recentDrops(): List<String> = synchronized(history) { history.toList() }
    }
}
