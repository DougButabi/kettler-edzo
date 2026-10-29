package hu.kettleredzo.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.roundToInt

// Egy mentett tekerés a naplóból: az útvonal térképen (zöld a kezdet, piros a vég) és az adatai.
// Fekvőben a térkép bal oldalt, állóban felül.
class TrackActivity : KettlerActivity() {
    private lateinit var ui: Ui
    private var map: MapView? = null

    private val dateFormat = SimpleDateFormat("yyyy. MM. dd. HH:mm", HU)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val w = Db.get(this).workout(intent.getLongExtra(EXTRA_WORKOUT_ID, -1))
        if (w == null) {
            finish()
            return
        }
        ui = Ui(this)
        Maps.init(this)
        val points = Db.get(this).track(w.id).map { GeoPoint(it[0], it[1]) }

        val root = ui.splitRoot()
        root.addView(buildMap(points), if (ui.portrait) ui.lp(MATCH_PARENT, 0, 0.55f) else ui.lp(0, MATCH_PARENT, 0.6f))
        root.addView(ScrollView(this).apply { addView(buildInfo(w, points.size)) },
            if (ui.portrait) ui.lp(MATCH_PARENT, 0, 0.45f) else ui.lp(0, MATCH_PARENT, 0.4f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        map?.onResume()
    }

    override fun onPause() {
        map?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        map?.onDetach()
        super.onDestroy()
    }

    private fun buildMap(route: List<GeoPoint>): View {
        val box = FrameLayout(this)
        if (route.size < 2) {
            box.setBackgroundColor(CARD)
            box.addView(ui.note("Ehhez a tekeréshez nincs mentett útvonal.").apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            return box
        }
        val m = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(14.0)
            controller.setCenter(route.first())
        }
        map = m
        m.overlays.add(Polyline(m).apply {
            setPoints(route)
            outlinePaint.color = Color.parseColor("#E0262626")
            outlinePaint.strokeWidth = ui.px(9)
        })
        m.overlays.add(dot(m, route.first(), GREEN))
        m.overlays.add(dot(m, route.last(), RED))
        // Az első elrendezés után a teljes útvonal kerüljön a képbe.
        m.addOnFirstLayoutListener { _, _, _, _, _ ->
            m.zoomToBoundingBox(BoundingBox.fromGeoPoints(route), false, ui.ipx(60))
        }
        box.addView(m, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        box.addView(ui.text("© OpenStreetMap", 14, MUTED, ui.regular).apply {
            setBackgroundColor(Color.parseColor("#B3FFFFFF"))
            setPadding(ui.ipx(8), ui.ipx(2), ui.ipx(8), ui.ipx(2))
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        return box
    }

    private fun dot(m: MapView, at: GeoPoint, color: Int) = Marker(m).apply {
        position = at
        icon = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(ui.ipx(5), BAR)
            setSize(ui.ipx(34), ui.ipx(34))
        }
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        setInfoWindow(null)
    }

    private fun buildInfo(w: Workout, pointCount: Int): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ui.paneLayout(col, top = 45)
        col.addView(ui.pill("← VISSZA") { finish() }, ui.lp(WRAP_CONTENT, WRAP_CONTENT))
        col.addView(ui.text(dateFormat.format(Date(w.startedAt)), 22, MUTED, ui.medium),
            ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(28) })
        col.addView(ui.text(w.label, 34, INK, ui.bold).apply { maxLines = 2 },
            ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(8) })
        val stats = listOf(
            formatNum(w.distanceKm, 2) to "km",
            formatTime(w.movingMs) to "mozgásidő",
            formatNum(w.avgSpeed, 1) to "átlag km/h",
            formatNum(w.maxSpeed, 1) to "max km/h",
            (w.elevGain ?: 0.0).roundToInt().toString() to "m szint",
            formatNum(w.kcal, 0) to "kcal (becslés)",
        )
        for (pair in stats.chunked(2)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((value, caption) in pair) row.addView(stat(value, caption), ui.lp(0, WRAP_CONTENT, 1f))
            col.addView(row, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(26) })
        }
        if (pointCount < 2) {
            col.addView(ui.note("Az útvonal azért hiányzik, mert a tekerés alatt nem volt elég GPS-pont."),
                ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(30) })
        }
        return col
    }

    private fun stat(value: String, caption: String): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ui.text(value, 48, INK, ui.bold))
        col.addView(ui.text(caption, 18, MUTED, ui.regular), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(4) })
        return col
    }

    companion object {
        const val EXTRA_WORKOUT_ID = "workoutId"
    }
}
