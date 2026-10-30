package hu.kettleredzo.app

import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.roundToInt

// Profilonkénti rutin az elliptikushoz (a profiles.routine oszlopban, JSON-ban, szinkronizálódik):
// indítandó app (YouTube linkkel, Kodi egy sorozat következő részével), fülhallgató, hangerő.
// A Rutin gomb az edzésképernyőn futtatja; a videó a háttérben betöltve vár, a Kettler elöl marad.
data class Routine(
    val app: String = "", // csomagnév, pl. com.google.android.youtube
    val appName: String = "",
    val link: String = "", // YouTube: videó vagy lejátszási lista
    val kodiShowId: Int = -1,
    val kodiShow: String = "",
    val bt: String = "", // a fülhallgató Bluetooth-címe
    val btName: String = "",
    val volume: Int = -1, // médiahangerő %-ban, -1 = nem állítja
) {
    val isEmpty get() = app.isBlank() && bt.isBlank() && volume < 0
    val isYouTube get() = app == YOUTUBE
    val isKodi get() = app == KODI

    fun toJson(): String = JSONObject()
        .put("app", app).put("appName", appName).put("link", link)
        .put("kodiShowId", kodiShowId).put("kodiShow", kodiShow)
        .put("bt", bt).put("btName", btName).put("volume", volume)
        .toString()

    companion object {
        const val YOUTUBE = "com.google.android.youtube"
        const val KODI = "org.xbmc.kodi"

        fun parse(json: String?): Routine = try {
            val o = JSONObject(json ?: "{}")
            Routine(
                app = o.optString("app"), appName = o.optString("appName"), link = o.optString("link"),
                kodiShowId = o.optInt("kodiShowId", -1), kodiShow = o.optString("kodiShow"),
                bt = o.optString("bt"), btName = o.optString("btName"), volume = o.optInt("volume", -1),
            )
        } catch (e: Exception) {
            Routine()
        }
    }
}

object RoutineRunner {
    private const val TAG = "Routine"
    private val executor = Executors.newSingleThreadExecutor()

    // Lefuttatja a rutint. Visszaadja a fülhallgató nevét, ha meg van adva, de nincs csatlakozva (figyelmeztetéshez).
    fun run(a: Activity, r: Routine): String? {
        if (r.volume >= 0) setVolume(a, r.volume)
        val missingBt = if (r.bt.isNotBlank() && !headsetConnected(a, r.bt)) r.btName.ifBlank { r.bt } else null
        if (r.app.isBlank()) return missingBt
        val launch = when {
            r.isYouTube && r.link.isNotBlank() -> Intent(Intent.ACTION_VIEW, Uri.parse(r.link)).setPackage(Routine.YOUTUBE)
            else -> a.packageManager.getLaunchIntentForPackage(r.app)
        }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return missingBt
        // Az app elindul, és ugyanabban a pillanatban a Kettler is visszakerül elé: így nem veszi el a képernyőt
        // (a háttérből az Android már nem engedné vissza a Kettlert, amíg elöl van, igen).
        val back = Intent(a, a.javaClass).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            a.startActivity(launch)
            a.startActivity(back)
        } catch (e: Exception) {
            Log.w(TAG, "Az app nem indul", e)
            return missingBt
        }
        kodiDone = false
        if (r.isKodi && r.kodiShowId >= 0) startKodi(a, r)
        return missingBt
    }

    // A Kodi a következő részt betöltötte-e (ha a háttérben nem sikerült, a „Videó ›” újra megpróbálja)
    @Volatile private var kodiDone = false

    private fun startKodi(c: Context, r: Routine) {
        val app = c.applicationContext
        executor.execute { if (Kodi.playNext(app, r.kodiShowId)) kodiDone = true }
    }

    // A „Videó ›” gomb: előre hozza a rutin appját (a már futó példányt).
    fun bringApp(a: Activity, r: Routine) {
        a.packageManager.getLaunchIntentForPackage(r.app)?.let {
            a.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }
        if (r.isKodi && r.kodiShowId >= 0 && !kodiDone) startKodi(a, r)
    }

    fun setVolume(c: Context, percent: Int) {
        val am = c.getSystemService(AudioManager::class.java) ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (percent / 100.0 * max).roundToInt().coerceIn(0, max), 0)
        } catch (e: SecurityException) {
            Log.w(TAG, "Hangerő", e) // pl. Ne zavarjanak módban
        }
    }

    // Csatlakozik-e a megadott fülhallgató (a hangkimenetek között ott van-e Bluetooth-eszközként)
    fun headsetConnected(c: Context, address: String): Boolean {
        val am = c.getSystemService(AudioManager::class.java) ?: return false
        if (android.os.Build.VERSION.SDK_INT < 28) return true // régi Androidon nem látszik a cím, nem figyelmeztetünk
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) &&
                it.address.equals(address, ignoreCase = true)
        }
    }

    // A tablettel párosított Bluetooth-eszközök (cím, név) a fülhallgató választásához
    @android.annotation.SuppressLint("MissingPermission")
    fun bondedDevices(c: Context): List<Pair<String, String>> = try {
        c.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
            .map { it.address to (it.name ?: it.address) }.sortedBy { it.second.lowercase() }
    } catch (e: SecurityException) {
        emptyList()
    }
}

// A tableten futó Kodi vezérlése a JSON-RPC-n át (Kodi: Beállítások › Szolgáltatások › Vezérlés ›
// „Távvezérlés engedélyezése HTTP-n”). Cím: 127.0.0.1, a port, felhasználó és jelszó a Prefs-ben.
object Kodi {
    private const val TAG = "Kodi"

    private fun call(c: Context, method: String, params: JSONObject = JSONObject()): JSONObject {
        val conn = URL("http://127.0.0.1:${Prefs.kodiPort(c)}/jsonrpc").openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 10000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        val auth = "${Prefs.kodiUser(c)}:${Prefs.kodiPass(c)}"
        conn.setRequestProperty("Authorization", "Basic " + Base64.encodeToString(auth.toByteArray(), Base64.NO_WRAP))
        try {
            val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode == 401) throw IllegalStateException("a Kodi elutasította a felhasználónevet vagy jelszót")
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            o.optJSONObject("error")?.let { throw IllegalStateException(it.optString("message")) }
            return o.optJSONObject("result") ?: JSONObject()
        } finally {
            conn.disconnect()
        }
    }

    // A Kodi sorozatai (id, cím). Hiba esetén kivételt dob, a szövege a felhasználónak szól.
    fun tvShows(c: Context): List<Pair<Int, String>> {
        val r = try {
            call(c, "VideoLibrary.GetTVShows", JSONObject().put("sort", JSONObject().put("method", "title")))
        } catch (e: java.io.IOException) {
            throw IllegalStateException("a Kodi nem érhető el (fut a Kodi, és be van kapcsolva a távvezérlés HTTP-n?)")
        }
        val a = r.optJSONArray("tvshows") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it).let { s -> s.getInt("tvshowid") to s.getString("label") } }
    }

    // A sorozat első, még nem látott része (évad, rész szerint), és lejátszás a megszakított helyről.
    // A Kodi indulására legfeljebb 20 mp-et vár.
    fun playNext(c: Context, showId: Int): Boolean {
        val deadline = System.currentTimeMillis() + 20_000
        while (true) {
            try {
                val r = call(c, "VideoLibrary.GetEpisodes", JSONObject().put("tvshowid", showId)
                    .put("properties", JSONArray().put("season").put("episode").put("playcount")))
                val a = r.optJSONArray("episodes") ?: return true
                val next = (0 until a.length()).map { a.getJSONObject(it) }
                    .filter { it.optInt("playcount") == 0 }
                    .minByOrNull { it.optInt("season") * 1000 + it.optInt("episode") } ?: return true
                call(c, "Player.Open", JSONObject()
                    .put("item", JSONObject().put("episodeid", next.getInt("episodeid")))
                    .put("options", JSONObject().put("resume", true)))
                return true
            } catch (e: java.io.IOException) {
                if (System.currentTimeMillis() > deadline) {
                    Log.w(TAG, "A Kodi nem válaszolt", e)
                    return false
                }
                Thread.sleep(1000)
            } catch (e: Exception) {
                Log.w(TAG, "Kodi", e)
                return false
            }
        }
    }
}
