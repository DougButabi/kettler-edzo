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
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.roundToInt

// Tekerés közben, az elliptikus edzésképernyő mintájára: nagy számok (sebesség, idő vagy a
// hátralévő cél, táv, átlag, szint, kalória) és a térkép (állóban a számok alatt, a képernyő kb.
// 60%-án; fekvőben bal oldalt, kb. 40%-on). A mérést a BikeService végzi; kikapcsolt kijelzővel is megy tovább.
class RideActivity : KettlerActivity() {
    private lateinit var ui: Ui
    private lateinit var map: MapView
    private lateinit var track: Polyline
    private lateinit var marker: Marker
    private var drawnPoints = 0
    private var follow = true
    private var lastTouchAt = 0L
    private var zoomedIn = false

    private lateinit var modeV: TextView
    private lateinit var speedV: TextView
    private lateinit var goalLabelV: TextView
    private lateinit var goalValueV: TextView
    private lateinit var progressRow: View
    private lateinit var progressFill: View
    private lateinit var progressRest: View
    private lateinit var percentV: TextView
    private lateinit var goalNoteV: TextView
    private lateinit var distV: TextView
    private lateinit var avgV: TextView
    private lateinit var climbV: TextView
    private lateinit var kcalV: TextView
    private lateinit var statusV: TextView
    private lateinit var followBtn: TextView
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
        if (Prefs.rideScreenOn(this)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        follow = savedInstanceState?.getBoolean(STATE_FOLLOW, true) ?: true
        Maps.init(this)
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
        modeV.text = "KERÉKPÁR   ·   " + modeLabel(s.mode, s.goal, null).uppercase()
        speedV.text = formatNum(s.speedKmh, 1)
        renderGoal()
        distV.text = formatNum(s.distanceKm, 2)
        avgV.text = formatNum(s.avgSpeedKmh, 1)
        climbV.text = s.elevGain.roundToInt().toString()
        kcalV.text = formatNum(s.kcal, 0)

        val (text, color) = when {
            s.gpsError != null -> s.gpsError!! to RED
            !s.hasFix -> "GPS jelre vár…" to RED
            s.paused -> "Szünet" to YELLOW
            s.autoPaused -> "Állsz – automatikus szünet" to YELLOW
            else -> "● GPS rendben" to GREEN
        }
        statusV.text = text
        statusV.setTextColor(color)

        if (s.paused) {
            mainIcon.setImageResource(R.drawable.ic_play)
            mainText.text = "Folytatás"
        } else {
            mainIcon.setImageResource(R.drawable.ic_pause)
            mainText.text = "Szünet"
        }
        finishBtn.visibility = if (s.paused) View.VISIBLE else View.GONE
        renderMap()
    }

    // A jobb felső nagy mező a módhoz igazodik: eltelt idő vagy a hátralévő táv / kalória / idő.
    private fun renderGoal() {
        val s = BikeState
        val time = formatTime(s.movingMs)
        when {
            s.goalReached || s.mode == WorkoutMode.FREE || s.mode == WorkoutMode.PROGRAM -> {
                goalLabelV.text = if (s.goalReached) "CÉL TELJESÍTVE! ✓" else "IDŐ"
                goalValueV.text = time
                goalNoteV.text = if (s.goalReached) "Cél: ${goalText(s.goal)} ${unit(s.mode)}" else ""
            }
            s.mode == WorkoutMode.DISTANCE -> {
                goalLabelV.text = "HÁTRALÉVŐ TÁV"
                goalValueV.text = formatNum(maxOf(0.0, s.goal - s.distanceKm), 2)
                goalNoteV.text = "km   ·   eltelt $time"
            }
            s.mode == WorkoutMode.KCAL -> {
                goalLabelV.text = "HÁTRALÉVŐ KALÓRIA"
                goalValueV.text = formatNum(maxOf(0.0, s.goal - s.kcal), 0)
                goalNoteV.text = "kcal   ·   eltelt $time"
            }
            else -> {
                goalLabelV.text = "HÁTRALÉVŐ IDŐ"
                goalValueV.text = formatTimeUp(maxOf(0L, (s.goal * 60_000).toLong() - s.movingMs))
                goalNoteV.text = "Cél: ${goalText(s.goal)} perc"
            }
        }
        val p = s.progress
        progressRow.visibility = if (s.mode == WorkoutMode.FREE) View.GONE else View.VISIBLE
        (progressFill.layoutParams as LinearLayout.LayoutParams).weight = p
        (progressRest.layoutParams as LinearLayout.LayoutParams).weight = 1f - p
        progressFill.requestLayout()
        percentV.text = "${(p * 100).roundToInt()}%"
    }

    private fun unit(mode: WorkoutMode) = when (mode) {
        WorkoutMode.DISTANCE -> "km"
        WorkoutMode.KCAL -> "kcal"
        else -> "perc"
    }

    // Útvonal, a jelenlegi hely, és a követés: minden új helynél középre állítjuk a térképet
    // (animáció nélkül, mert a gyakori animációk egymást szakítanák meg, és a térkép állva maradna).
    private fun renderMap() {
        val s = BikeState
        val resume = Prefs.followResumeSec(this)
        if (!follow && resume > 0 && System.currentTimeMillis() - lastTouchAt > resume * 1000L) follow = true
        ui.setChosen(followBtn, follow)

        if (s.points.size != drawnPoints) {
            track.setPoints(s.points.map { GeoPoint(it[0], it[1]) })
            drawnPoints = s.points.size
        }
        s.lastLocation?.let {
            val here = GeoPoint(it.latitude, it.longitude)
            marker.position = here
            marker.isEnabled = true
            if (!zoomedIn) {
                map.controller.setZoom(Prefs.mapZoom(this))
                zoomedIn = true
            }
            if (follow) map.controller.setCenter(here)
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

    // Állóban: felül a számok (40%), alattuk a térkép (60%), legalul a sötét sáv.
    // Fekvőben: térkép bal oldalt (40%), jobbra a számok, alul a sáv.
    private fun buildUi(): View {
        val portrait = ui.portrait
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        val content = LinearLayout(this).apply {
            orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        if (portrait) {
            content.addView(buildPanel(), ui.lp(MATCH_PARENT, 0, 0.4f))
            content.addView(buildMap(), ui.lp(MATCH_PARENT, 0, 0.6f))
        } else {
            content.addView(buildMap(), ui.lp(0, MATCH_PARENT, 0.4f))
            content.addView(buildPanel(), ui.lp(0, MATCH_PARENT, 0.6f))
        }
        root.addView(content, ui.lp(MATCH_PARENT, 0, 1f))
        root.addView(buildBottomBar(), ui.lp(MATCH_PARENT, if (portrait) WRAP_CONTENT else ui.ipx(BAR_H)))
        return root
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildMap(): View {
        val box = FrameLayout(this)
        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            val last = BikeState.lastLocation
            controller.setZoom(if (last != null) Prefs.mapZoom(this@RideActivity) else 7.5)
            controller.setCenter(last?.let { GeoPoint(it.latitude, it.longitude) } ?: HUNGARY)
            // Ha kézzel elhúzod a térképet, egy ideig nem ugrik vissza (lásd Beállítások → Térkép).
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_MOVE) {
                    follow = false
                    lastTouchAt = System.currentTimeMillis()
                }
                false
            }
        }
        zoomedIn = BikeState.lastLocation != null
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
        box.addView(map, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        followBtn = ui.choice("◎ Követés", 22) {
            follow = true
            render()
        }.apply { setPadding(ui.ipx(28), 0, ui.ipx(28), 0) }
        box.addView(followBtn, FrameLayout.LayoutParams(WRAP_CONTENT, ui.ipx(70), Gravity.TOP or Gravity.END).apply {
            setMargins(0, ui.ipx(20), ui.ipx(20), 0)
        })
        // Az OpenStreetMap licence szerint a térképen fel kell tüntetni a forrást.
        box.addView(ui.text("© OpenStreetMap", 14, MUTED, ui.regular).apply {
            setBackgroundColor(Color.parseColor("#B3FFFFFF"))
            setPadding(ui.ipx(8), ui.ipx(2), ui.ipx(8), ui.ipx(2))
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        return box
    }

    private fun buildPanel(): View {
        val portrait = ui.portrait
        // Állóban a számok a képernyő 40%-át kapják, ezért kisebbek, mint az elliptikusnál.
        val big = if (portrait) 135 else 130
        val small = if (portrait) 76 else 70
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (portrait) {
                setPadding(ui.ipx(35 + ui.edge), ui.ipx(18 + ui.edge), ui.ipx(35 + ui.edge), ui.ipx(8))
            } else {
                setPadding(ui.ipx(45), ui.ipx(30), ui.ipx(45 + ui.edge), ui.ipx(10))
            }
        }
        // Állóban a 40%-ba nem fér el; a módot ott a jobb oldali mező címe is mutatja.
        modeV = ui.label("", MUTED)
        if (!portrait) panel.addView(modeV)

        // 1. sor: sebesség és idő / hátralévő cél
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val speedCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        speedCol.addView(ui.label("SEBESSÉG"))
        speedV = ui.bigValue(big)
        speedCol.addView(speedV, ui.lp(MATCH_PARENT, ui.ipx(big + 10)).apply { topMargin = ui.ipx(10) })
        speedCol.addView(ui.text("km/h", 30, MUTED, ui.regular))
        row1.addView(speedCol, ui.lp(0, WRAP_CONTENT, 1f))
        row1.addView(ui.line(), ui.lp(1, MATCH_PARENT).apply {
            leftMargin = ui.ipx(30)
            rightMargin = ui.ipx(30)
        })
        val goalCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        goalLabelV = ui.label("IDŐ")
        goalCol.addView(goalLabelV)
        goalValueV = ui.bigValue(big)
        goalCol.addView(goalValueV, ui.lp(MATCH_PARENT, ui.ipx(big + 10)).apply { topMargin = ui.ipx(10) })
        val progress = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        progressRow = progress
        val trackBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ui.rounded(TRACK, 7)
            clipToOutline = true
        }
        progressFill = View(this).apply { background = ui.rounded(YELLOW, 7) }
        progressRest = View(this)
        trackBar.addView(progressFill, ui.lp(0, MATCH_PARENT, 0f))
        trackBar.addView(progressRest, ui.lp(0, MATCH_PARENT, 1f))
        progress.addView(trackBar, ui.lp(0, ui.ipx(14), 1f))
        percentV = ui.text("0%", 18, INK, ui.medium)
        progress.addView(percentV, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ui.ipx(14) })
        goalCol.addView(progress, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(6) })
        goalNoteV = ui.text("", 18, MUTED, ui.regular)
        goalCol.addView(goalNoteV, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(10) })
        row1.addView(goalCol, ui.lp(0, WRAP_CONTENT, 1f))
        panel.addView(row1, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(if (portrait) 0 else 16) })

        // 2. és 3. sor: kisebb csempék
        distV = ui.text("0,00", small, INK, ui.bold)
        avgV = ui.text("0,0", small, INK, ui.bold)
        climbV = ui.text("0", small, INK, ui.bold)
        kcalV = ui.text("0", small, INK, ui.bold)
        panel.addView(ui.line(), separatorLp())
        panel.addView(tileRow(tile(R.drawable.ic_place, "TÁV", distV, "km"), tile(R.drawable.ic_trend, "ÁTLAG", avgV, "km/h")))
        panel.addView(ui.line(), separatorLp())
        panel.addView(tileRow(tile(R.drawable.ic_bars, "SZINT", climbV, "m"), tile(R.drawable.ic_fire, "KALÓRIA", kcalV, "kcal")))
        return panel
    }

    private fun separatorLp() = ui.lp(MATCH_PARENT, 1).apply {
        topMargin = ui.ipx(if (ui.portrait) 12 else 16)
        bottomMargin = ui.ipx(if (ui.portrait) 12 else 16)
    }

    private fun tileRow(a: View, b: View): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(a, ui.lp(0, WRAP_CONTENT, 1f))
        row.addView(ui.line(), ui.lp(1, MATCH_PARENT).apply {
            leftMargin = ui.ipx(30)
            rightMargin = ui.ipx(30)
        })
        row.addView(b, ui.lp(0, WRAP_CONTENT, 1f))
        return row
    }

    private fun tile(iconRes: Int, title: String, value: TextView, unit: String): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ui.icon(iconRes, 28, INK))
        header.addView(ui.label(title), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ui.ipx(18) })
        col.addView(header)
        // A vízszintes LinearLayout alapból alapvonalra igazít, így a mértékegység a szám aljához kerül.
        val valueRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ui.ipx(46), ui.ipx(10), 0, 0)
        }
        valueRow.addView(value)
        valueRow.addView(ui.text(unit, 24, MUTED, ui.regular), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ui.ipx(12) })
        col.addView(valueRow)
        return col
    }

    private fun buildBottomBar(): View {
        val info = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        info.addView(ui.icon(R.drawable.ic_place, 40, Color.WHITE))
        statusV = ui.text("", 22, GREEN, ui.medium)
        info.addView(statusV, ui.lp(0, WRAP_CONTENT, 1f).apply { leftMargin = ui.ipx(20) })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val finish = ui.pillButton(R.drawable.ic_stop, "Befejezés", BAR_LINE, Color.WHITE) { finishRide() }
        finishBtn = finish
        buttons.addView(finish, ui.lp(ui.ipx(250), ui.ipx(85)).apply { rightMargin = ui.ipx(20) })
        val main = ui.pillButton(R.drawable.ic_pause, "Szünet", BUTTON, INK) { onMainButton() }
        mainIcon = main.getChildAt(0) as ImageView
        mainText = main.getChildAt(1) as TextView
        buttons.addView(main, ui.lp(ui.ipx(290), ui.ipx(85)))
        return ui.bottomBar(info, buttons)
    }

    companion object {
        private const val MIN_SAVED_MS = 60_000L
        private const val STATE_FOLLOW = "follow"
        private const val BAR_H = 145
        private val HUNGARY = GeoPoint(47.16, 19.5)
    }
}
