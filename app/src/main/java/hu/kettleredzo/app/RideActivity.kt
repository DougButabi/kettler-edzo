package hu.kettleredzo.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

// Tekerés közben: a képernyőt az OpenStreetMap-térkép tölti ki (hol vagy, merre jártál),
// felül egy sávban a sebesség, a táv és az idő, alul Szünet / Folytatás / Befejezés.
// A mérést a BikeService végzi, ez a képernyő csak mutatja; kikapcsolt kijelzővel is megy tovább.
class RideActivity : KettlerActivity() {
    private lateinit var ui: Ui
    private lateinit var map: MapView
    private lateinit var track: Polyline
    private lateinit var marker: Marker
    private var drawnPoints = 0
    private var follow = true

    private lateinit var speedV: TextView
    private lateinit var distV: TextView
    private lateinit var timeV: TextView
    private lateinit var statusV: TextView
    private lateinit var mainIcon: ImageView
    private lateinit var mainText: TextView
    private lateinit var finishBtn: View

    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BikeState.running) {
            finish()
            return
        }
        ui = Ui(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        follow = savedInstanceState?.getBoolean(STATE_FOLLOW, true) ?: true
        // A térképcsempék az app saját mappájába kerülnek (engedély nem kell hozzá).
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid-tiles")
        }
        setContentView(buildUi())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_FOLLOW, follow)
    }

    override fun onResume() {
        super.onResume()
        if (!::ui.isInitialized) return
        if (!BikeState.running) {
            finish()
            return
        }
        map.onResume()
        drawnPoints = -1 // a teljes útvonalat újrarajzoljuk
        BikeState.addListener(listener)
        render()
    }

    override fun onPause() {
        if (::ui.isInitialized) {
            BikeState.removeListener(listener)
            map.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::map.isInitialized) map.onDetach()
        super.onDestroy()
    }

    // ---- Kirajzolás ----

    private fun render() {
        val s = BikeState
        speedV.text = formatNum(s.speedKmh, 1)
        distV.text = formatNum(s.distanceKm, 2)
        timeV.text = formatTime(s.movingMs)

        val (text, color) = when {
            s.gpsError != null -> s.gpsError!! to RED
            !s.hasFix -> "GPS jelre vár…" to RED
            s.paused -> "SZÜNET" to YELLOW
            s.autoPaused -> "ÁLLSZ – automatikus szünet" to YELLOW
            else -> (s.goalStatus() ?: "${formatNum(s.avgSpeedKmh, 1)} km/h átlag   ·   ↑ ${formatNum(s.elevGain, 0)} m   ·   ${formatNum(s.kcal, 0)} kcal") to Color.WHITE
        }
        statusV.text = if (s.goalReached && !s.paused && s.hasFix) "CÉL TELJESÍTVE ✓   ·   ${formatNum(s.kcal, 0)} kcal" else text
        statusV.setTextColor(if (s.goalReached && s.hasFix) GREEN else color)

        if (s.paused) {
            mainIcon.setImageResource(R.drawable.ic_play)
            mainText.text = "Folytatás"
        } else {
            mainIcon.setImageResource(R.drawable.ic_pause)
            mainText.text = "Szünet"
        }
        finishBtn.visibility = if (s.paused) View.VISIBLE else View.GONE

        // Útvonal és a jelenlegi hely
        if (s.points.size != drawnPoints) {
            track.setPoints(s.points.map { GeoPoint(it[0], it[1]) })
            drawnPoints = s.points.size
        }
        s.lastLocation?.let {
            val here = GeoPoint(it.latitude, it.longitude)
            marker.position = here
            marker.isEnabled = true
            if (follow) map.controller.animateTo(here)
        }
        map.invalidate()
    }

    // ---- Gombok ----

    private fun onMainButton() {
        val s = BikeState
        if (s.paused) s.resume() else s.pause()
        render()
    }

    // Lezárja a tekerést, és elmenti a naplóba az útvonallal, ha legalább 1 percig tartott.
    private fun finishRide() {
        val s = BikeState
        s.finish()
        stopService(Intent(this, BikeService::class.java))
        if (s.movingMs < MIN_SAVED_MS) {
            ui.toast("1 percnél rövidebb tekerést nem mentek el.")
        } else {
            Db.get(this).addRide(
                Workout(
                    id = 0,
                    profileId = s.profileId,
                    startedAt = s.startedAt,
                    movingMs = s.movingMs,
                    distanceKm = s.distanceKm,
                    avgSpeed = s.avgSpeedKmh,
                    maxSpeed = s.maxSpeedKmh,
                    kcal = s.kcal,
                    level = 0,
                    mode = s.mode,
                    goal = if (s.mode == WorkoutMode.FREE) null else s.goal,
                    completed = s.goalReached,
                    sport = Sport.BIKE,
                    elevGain = s.elevGain,
                ),
                s.pointsJson(),
            )
            ui.toast("Tekerés elmentve a naplóba.")
        }
        finish()
    }

    // ---- Felület ----

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi(): View {
        val root = FrameLayout(this)
        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(if (BikeState.lastLocation != null) 17.0 else 7.5)
            controller.setCenter(BikeState.lastLocation?.let { GeoPoint(it.latitude, it.longitude) } ?: HUNGARY)
            // Ha kézzel elhúzod a térképet, nem ugrik vissza; a Követés gomb kapcsolja vissza.
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_MOVE) follow = false
                false
            }
        }
        track = Polyline(map).apply {
            outlinePaint.color = Color.parseColor("#E0262626")
            outlinePaint.strokeWidth = ui.px(9)
        }
        marker = Marker(map).apply {
            icon = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(YELLOW)
                setStroke(ui.ipx(5), BAR)
                setSize(ui.ipx(38), ui.ipx(38))
            }
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setInfoWindow(null)
            isEnabled = false // az első GPS-helyig nem látszik
        }
        map.overlays.add(track)
        map.overlays.add(marker)
        root.addView(map, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(buildTopBar(), FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        root.addView(buildBottomBar(), FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        return root
    }

    // Felső sáv: sebesség, táv, idő nagyban, alatta az állapot (GPS, szünet, cél)
    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(OVERLAY)
            setPadding(ui.ipx(35), ui.ipx(18), ui.ipx(35), ui.ipx(16))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        speedV = value()
        distV = value()
        timeV = value()
        row.addView(stat("SEBESSÉG", speedV, "km/h"), ui.lp(0, WRAP_CONTENT, 1f))
        row.addView(stat("TÁV", distV, "km"), ui.lp(0, WRAP_CONTENT, 1f))
        row.addView(stat("IDŐ", timeV, ""), ui.lp(0, WRAP_CONTENT, 1.15f))
        bar.addView(row)
        statusV = ui.text("", 22, Color.WHITE, ui.medium)
        bar.addView(statusV, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(10) })
        return bar
    }

    private fun value() = ui.text("0", if (ui.portrait) 64 else 72, Color.WHITE, ui.bold).apply {
        ellipsize = null
    }

    private fun stat(title: String, value: TextView, unit: String): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ui.barLabel(title))
        // A vízszintes LinearLayout alapból alapvonalra igazít, így a mértékegység a szám aljához kerül.
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        line.addView(value)
        if (unit.isNotEmpty()) {
            line.addView(ui.text(unit, 22, BAR_MUTED, ui.regular), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ui.ipx(8) })
        }
        col.addView(line, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(4) })
        return col
    }

    private fun buildBottomBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(OVERLAY)
            setPadding(ui.ipx(30), ui.ipx(18), ui.ipx(30), ui.ipx(18))
        }
        bar.addView(ui.pill("◎ KÖVETÉS") {
            follow = true
            render()
        })
        bar.addView(View(this), ui.lp(0, 1, 1f))
        val finish = ui.pillButton(R.drawable.ic_stop, "Befejezés", BAR_LINE, Color.WHITE) { finishRide() }
        finishBtn = finish
        bar.addView(finish, ui.lp(ui.ipx(250), ui.ipx(85)).apply { rightMargin = ui.ipx(16) })
        val main = ui.pillButton(R.drawable.ic_pause, "Szünet", BUTTON, INK) { onMainButton() }
        mainIcon = main.getChildAt(0) as ImageView
        mainText = main.getChildAt(1) as TextView
        bar.addView(main, ui.lp(ui.ipx(270), ui.ipx(85)))
        return bar
    }

    companion object {
        private const val MIN_SAVED_MS = 60_000L
        private const val STATE_FOLLOW = "follow"
        private val OVERLAY = Color.parseColor("#E62B2B2B")
        private val HUNGARY = GeoPoint(47.16, 19.5)
    }
}
