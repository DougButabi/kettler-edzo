package hu.kettleredzo.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

// Előtérszolgáltatás: tartja a kapcsolatot az ESP32-vel, méri az edzésidőt,
// és edzés közben kiteszi az overlay-sávot más appok (pl. Kodi) fölé.
class WorkoutService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var client: EspClient? = null
    private var overlay: TextView? = null
    private var lastTick = 0L

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            // Az edzésidő csak akkor megy, ha a gép mozog.
            if (KettlerState.isCounting && KettlerState.isMoving) KettlerState.movingMs += now - lastTick
            lastTick = now
            updateOverlay()
            KettlerState.notifyChanged()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
        connect()
        lastTick = SystemClock.elapsedRealtime()
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RECONNECT) connect()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        client?.stop()
        client = null
        removeOverlay()
        KettlerState.onDisconnected(null)
        super.onDestroy()
    }

    private fun connect() {
        client?.stop()
        client = null
        KettlerState.onDisconnected(null)
        val host = Prefs.host(this)
        if (host.isBlank()) return
        val c = EspClient(
            host,
            onValue = { id, v -> handler.post { onValue(id, v) } },
            onConnection = { ok, err -> handler.post { onConnection(ok, err) } },
        )
        client = c
        c.start()
    }

    private fun onValue(id: String, value: Double) {
        val s = KettlerState
        s.connected = true
        when (id) {
            ID_RPM -> s.rpm = value
            ID_SPEED -> s.speedKmh = value
            ID_DISTANCE -> s.onTotalKm(value)
            else -> return
        }
        s.notifyChanged()
    }

    private fun onConnection(connected: Boolean, error: String?) {
        if (connected) {
            KettlerState.connected = true
            KettlerState.lastError = null
        } else {
            KettlerState.onDisconnected(error)
        }
        KettlerState.notifyChanged()
    }

    private fun updateOverlay() {
        val s = KettlerState
        val wanted = s.running && !s.activityVisible && Prefs.overlay(this) && Settings.canDrawOverlays(this)
        if (wanted && overlay == null) addOverlay()
        if (!wanted && overlay != null) removeOverlay()
        overlay?.text = overlayText()
    }

    private fun overlayText(): String {
        val s = KettlerState
        val prefix = when {
            !s.connected -> "⚠ NINCS KAPCSOLAT   "
            s.paused -> "SZÜNET   "
            else -> ""
        }
        return prefix + formatTime(s.movingMs) + "     " +
            formatNum(s.speedKmh, 1) + " km/h     " +
            formatNum(s.rpm, 0) + " rpm     " +
            formatNum(s.workoutKm, 2) + " km     " +
            formatNum(s.kcal, 0) + " kcal     " +
            "fokozat " + Prefs.level(this)
    }

    private fun addOverlay() {
        val view = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(24, 10, 24, 10)
            setBackgroundColor(0x992B2B2B.toInt())
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Az érintés átmegy alatta a Kodihoz, és amíg látszik, a képernyő nem alszik el.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            alpha = 0.8f // Android 12-től efölött elnyelné az érintést
        }
        try {
            getSystemService(WindowManager::class.java).addView(view, lp)
            overlay = view
        } catch (e: Exception) {
            KettlerState.lastError = "Overlay hiba: " + e.message
        }
    }

    private fun removeOverlay() {
        overlay?.let {
            try {
                getSystemService(WindowManager::class.java).removeView(it)
            } catch (_: Exception) {
            }
        }
        overlay = null
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Edzés", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Kettler edző")
            .setContentText("Kapcsolat az ESP32-vel")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        const val ACTION_RECONNECT = "hu.kettleredzo.app.RECONNECT"
        private const val CHANNEL = "edzes"
        private const val NOTIFICATION_ID = 1

        // Az ESPHome a szenzor nevéből képzi az azonosítót, ezért a neveket nem szabad átírni.
        private const val ID_RPM = "sensor/Fordulatszám"
        private const val ID_SPEED = "sensor/Sebesség"
        private const val ID_DISTANCE = "sensor/Táv"
    }
}
