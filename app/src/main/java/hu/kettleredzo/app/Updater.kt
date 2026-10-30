package hu.kettleredzo.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

// Frissítés az appon belül: a GitHub „legujabb” kiadásának sorszámát összeveti a saját
// versionCode-dal, és ha van újabb, letölti az APK-t és a rendszer telepítőjével feltelepíti.
object Updater {
    private const val TAG = "Updater"
    private const val RELEASE_API = "https://api.github.com/repos/DougButabi/kettler-edzo/releases/tags/legujabb"
    const val APK_URL = "https://github.com/DougButabi/kettler-edzo/releases/download/legujabb/kettler-edzo.apk"

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun currentBuild(c: Context): Long {
        val info = c.packageManager.getPackageInfo(c.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    // A legújabb kiadás sorszáma (a kiadás címéből: „Legújabb build (#28)”), vagy hibaüzenet.
    fun latest(onDone: (Long?, String?) -> Unit) {
        executor.execute {
            val r = try {
                val conn = URL(RELEASE_API).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val title = JSONObject(body).optString("name")
                val n = Regex("#(\\d+)").find(title)?.groupValues?.get(1)?.toLong()
                if (n == null) null to "nem olvasható a kiadás száma" else n to null
            } catch (e: Exception) {
                Log.w(TAG, "Frissítés-ellenőrzés", e)
                null to "nincs internet, vagy a GitHub nem érhető el"
            }
            main.post { onDone(r.first, r.second) }
        }
    }

    // Letöltés és átadás a rendszer telepítőjének. A megerősítő ablakot az UpdateReceiver nyitja meg.
    fun install(c: Context, onProgress: (Int) -> Unit, onError: (String) -> Unit) {
        val app = c.applicationContext
        executor.execute {
            try {
                val conn = URL(APK_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                val total = conn.contentLengthLong
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("kettler-edzo.apk", 0, if (total > 0) total else -1).use { out ->
                        conn.inputStream.use { input ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastPct = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                                if (pct != lastPct) {
                                    lastPct = pct
                                    main.post { onProgress(pct) }
                                }
                            }
                        }
                        session.fsync(out)
                    }
                    val intent = Intent(app, UpdateReceiver::class.java)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    session.commit(PendingIntent.getBroadcast(app, id, intent, flags).intentSender)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Frissítés letöltése", e)
                main.post { onError("a letöltés nem sikerült: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }
}

// A telepítő visszajelzése: a felhasználói megerősítést (Telepítés gomb) itt nyitjuk meg.
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> Log.w("Updater", "Telepítés: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}
