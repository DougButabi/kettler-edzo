package hu.kettleredzo.app

import android.bluetooth.BluetoothManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import org.json.JSONObject
import kotlin.math.roundToInt

// Profilonkénti rutin az elliptikushoz (a profiles.routine oszlopban, JSON-ban, szinkronizálódik):
// fülhallgató és médiahangerő. Az elliptikus képernyő megnyitásakor fut le (MainActivity).
data class Routine(
    val bt: String = "", // a fülhallgató Bluetooth-címe
    val btName: String = "",
    val volume: Int = -1, // médiahangerő %-ban, -1 = nem állítja
) {
    val isEmpty get() = bt.isBlank() && volume < 0

    fun toJson(): String = JSONObject().put("bt", bt).put("btName", btName).put("volume", volume).toString()

    companion object {
        fun parse(json: String?): Routine = try {
            val o = JSONObject(json ?: "{}")
            Routine(bt = o.optString("bt"), btName = o.optString("btName"), volume = o.optInt("volume", -1))
        } catch (e: Exception) {
            Routine()
        }
    }
}

object RoutineRunner {
    private const val TAG = "Routine"

    // Beállítja a hangerőt. Visszaadja a fülhallgató nevét, ha meg van adva, de nincs csatlakozva (figyelmeztetéshez).
    fun run(c: Context, r: Routine): String? {
        if (r.volume >= 0) setVolume(c, r.volume)
        return if (r.bt.isNotBlank() && !headsetConnected(c, r.bt)) r.btName.ifBlank { r.bt } else null
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
        if (Build.VERSION.SDK_INT < 28) return true // régi Androidon nem látszik a cím, nem figyelmeztetünk
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
