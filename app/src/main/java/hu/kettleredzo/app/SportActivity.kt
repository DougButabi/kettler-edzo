package hu.kettleredzo.app

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView

// Profilválasztás után: melyik sportot edzi most? Elliptikus tréner, súlyzós edzés vagy kerékpár.
// Fekvőben a csempék egymás mellett, állóban (telefon) egymás alatt vannak.
class SportActivity : KettlerActivity() {
    private lateinit var ui: Ui
    private lateinit var profile: Profile
    private lateinit var goalsV: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Db.get(this).profile(Prefs.currentProfileId(this))
        if (p == null) {
            startActivity(Intent(this, ProfileActivity::class.java))
            finish()
            return
        }
        profile = p
        // Tekerés közben egyenesen a térképre megyünk.
        if (BikeState.running) startActivity(Intent(this, RideActivity::class.java))
        ui = Ui(this)
        val portrait = ui.portrait

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            val e = ui.edge
            if (portrait) setPadding(ui.ipx(35 + e), ui.ipx(30 + e), ui.ipx(35 + e), ui.ipx(30 + e)) else setPadding(ui.ipx(60 + e), ui.ipx(45), ui.ipx(60 + e), ui.ipx(45))
        }
        root.addView(ui.text("KETTLER", 32, INK, ui.light, 0.35f))
        root.addView(ui.text("Szia ${profile.name}, mit edzel ma?", if (portrait) 48 else 64, INK, ui.bold).apply { maxLines = 2 },
            ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(if (portrait) 20 else 40) })
        // A profil céljai és hol tart (koppintásra a célok szerkesztése)
        goalsV = ui.text("", 24, MUTED, ui.medium).apply {
            maxLines = 3
            ellipsize = null
            setOnClickListener { startActivity(Intent(this@SportActivity, GoalsActivity::class.java).putExtra(GoalsActivity.EXTRA_PROFILE_ID, profile.id)) }
        }
        root.addView(goalsV, ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(14) })

        val tiles = LinearLayout(this).apply {
            orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        val bikePhoto = if (profile.sex == Sex.MALE) "res:kerekpar_ferfi" else "res:kerekpar"
        val ellipticalPhoto = if (profile.sex == Sex.MALE) "res:trener_ferfi" else "res:trener"
        val items = listOf(
            Triple(ellipticalPhoto, Sport.ELLIPTICAL.title, "Kettler Vito XL") to { openElliptical() },
            Triple("res:sulyzos", Sport.WEIGHTS.title, liftCaption(profile.id)) to { openWeights() },
            Triple(bikePhoto, Sport.BIKE.title, if (BikeState.running) "Tekerés folyamatban" else "GPS, térkép") to { openBike() },
        )
        items.forEachIndexed { i, (t, onClick) ->
            val lp = if (portrait) {
                ui.lp(MATCH_PARENT, 0, 1f).apply { if (i > 0) topMargin = ui.ipx(24) }
            } else {
                ui.lp(0, MATCH_PARENT, 1f).apply { if (i > 0) leftMargin = ui.ipx(30) }
            }
            tiles.addView(tile(t.first, t.second, t.third, portrait, onClick), lp)
        }
        root.addView(tiles, ui.lp(MATCH_PARENT, 0, 1f).apply { topMargin = ui.ipx(30) })

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // Személyes eszközön nincs profilváltás
        if (!Prefs.personal(this)) {
            bottom.addView(ui.pill("← PROFILOK") {
                startActivity(Intent(this, ProfileActivity::class.java))
                finish()
            })
        }
        bottom.addView(View(this), ui.lp(0, 1, 1f))
        bottom.addView(ui.pill("NAPLÓ") { startActivity(Intent(this, LogActivity::class.java)) })
        root.addView(bottom, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(30) })
        setContentView(root)
    }

    // Közös eszközön a vissza gomb a profilválasztóra visz, nem lép ki az appból.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (Prefs.personal(this)) {
            super.onBackPressed()
            return
        }
        startActivity(Intent(this, ProfileActivity::class.java))
        finish()
    }

    override fun onResume() {
        super.onResume()
        // A pulzuspánt már itt csatlakozik, hogy az edzés indulásakor kész legyen (a kapcsolat megmarad).
        HeartRate.connect(this)
        renderGoals()
    }

    private fun renderGoals() {
        if (!::goalsV.isInitialized) return
        val db = Db.get(this)
        val goals = db.goals(profile.id)
        goalsV.text = if (goals.isEmpty()) {
            "Nincs még célod. Koppints ide, és állíts be napi vagy heti célt."
        } else {
            goals.joinToString("   ·   ") { g ->
                val v = db.goalValue(g)
                g.progressText(v) + if (v >= g.target) " ✓" else ""
            }
        }
    }

    private fun liftCaption(profileId: Long): String =
        if (Db.get(this).activeLift(profileId) != null) "Van folyamatban lévő edzésed" else "Edzésterv, sorozatok, pihenő"

    // Csempe: fekvőben kép fölül, alatta a felirat; állóban kép balra, felirat jobbra.
    private fun tile(image: String, title: String, caption: String, portrait: Boolean, onClick: () -> Unit): View {
        val card = LinearLayout(this).apply {
            orientation = if (portrait) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = ui.rounded(CARD, 28)
            setPadding(ui.ipx(24), ui.ipx(24), ui.ipx(24), ui.ipx(24))
            setOnClickListener { onClick() }
        }
        card.addView(ui.picture(image, title, 300, grow = false))
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (portrait) Gravity.START else Gravity.CENTER_HORIZONTAL
        }
        // Fekvőben a felirat a csempe teljes szélességét kapja, különben a cím (pl. „Elliptikus tréner”) levágódik.
        val align = if (portrait) Gravity.START else Gravity.CENTER_HORIZONTAL
        text.addView(ui.text(title, if (portrait) 38 else 40, INK, ui.bold).apply { maxLines = 2; gravity = align },
            ui.lp(MATCH_PARENT, WRAP_CONTENT))
        text.addView(ui.text(caption, 22, MUTED, ui.regular).apply { maxLines = 2; gravity = align },
            ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(10) })
        card.addView(text, if (portrait) {
            ui.lp(0, WRAP_CONTENT, 1f).apply { leftMargin = ui.ipx(28) }
        } else {
            ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(24) }
        })
        return card
    }

    private fun openElliptical() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun openWeights() {
        startActivity(Intent(this, WeightsActivity::class.java))
    }

    private fun openBike() {
        startActivity(Intent(this, BikeActivity::class.java))
    }
}
