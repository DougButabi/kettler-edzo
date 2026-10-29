package hu.kettleredzo.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

// Előtérszolgáltatás tekerés közben: a telefon GPS-éből veszi a helyet (kikapcsolt kijelzővel is),
// másodpercenként lépteti a mozgásidőt és a kalóriát, és frissíti az értesítést.
class BikeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastTick = 0L
    private var notifications: NotificationManager? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            BikeState.onLocation(location)
            BikeState.notifyChanged()
        }

        override fun onProviderDisabled(provider: String) {
            BikeState.gpsError = "A helymeghatározás ki van kapcsolva."
            BikeState.notifyChanged()
        }

        override fun onProviderEnabled(provider: String) {
            BikeState.gpsError = null
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val dt = now - lastTick
            lastTick = now
            Beeper.enabled = Prefs.beeps(this@BikeService)
            when (BikeState.onTick(dt)) {
                // Cél teljesítve: dupla hosszú hang. A tekerés megy tovább, a Befejezést te nyomod.
                RideEvent.GOAL -> {
                    Beeper.long()
                    handler.postDelayed({ Beeper.long() }, 900)
                }
                RideEvent.HALFWAY -> if (Prefs.halfwayBeep(this@BikeService)) {
                    Beeper.short()
                    handler.postDelayed({ Beeper.long() }, 400)
                }
                RideEvent.KM -> if (Prefs.kmBeep(this@BikeService)) Beeper.short()
                RideEvent.NONE -> {}
            }
            notifications?.notify(NOTIFICATION_ID, notification())
            BikeState.notifyChanged()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications?.createNotificationChannel(NotificationChannel(CHANNEL, "Kerékpár", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        requestLocation()
        lastTick = SystemClock.elapsedRealtime()
        handler.post(tick)
    }

    // A helyengedélyt a kezdőképernyő kéri el indítás előtt.
    @SuppressLint("MissingPermission")
    private fun requestLocation() {
        val lm = getSystemService(LocationManager::class.java)
        try {
            val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
            lm.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
        } catch (e: Exception) {
            BikeState.gpsError = "Nincs hozzáférés a helyhez: ${e.message}"
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        getSystemService(LocationManager::class.java).removeUpdates(listener)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val s = BikeState
        val open = PendingIntent.getActivity(this, 0, Intent(this, RideActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val state = when {
            !s.hasFix -> "GPS jelre vár…"
            s.paused -> "Szünet"
            s.autoPaused -> "Áll (automatikus szünet)"
            else -> "${formatNum(s.speedKmh, 1)} km/h"
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Kerékpár · ${formatNum(s.distanceKm, 2)} km · ${formatTime(s.movingMs)}")
            .setContentText(state)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "kerekpar"
        private const val NOTIFICATION_ID = 2
    }
}
