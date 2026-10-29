package hu.kettleredzo.app

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout

// Profilválasztás után: melyik sportot edzi most? Elliptikus tréner vagy súlyzós edzés.
class SportActivity : KettlerActivity() {
    private lateinit var ui: Ui

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profile = Db.get(this).profile(Prefs.currentProfileId(this))
        if (profile == null) {
            startActivity(Intent(this, ProfileActivity::class.java))
            finish()
            return
        }
        ui = Ui(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(ui.ipx(60), ui.ipx(45), ui.ipx(60), ui.ipx(45))
        }
        root.addView(ui.text("KETTLER", 32, INK, ui.light, 0.35f))
        root.addView(ui.text("Szia ${profile.name}, mit edzel ma?", 64, INK, ui.bold), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = ui.ipx(40)
        })

        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tiles.addView(tile("res:trener", Sport.ELLIPTICAL.title, "Kettler Vito XL") { openElliptical() }, ui.lp(0, MATCH_PARENT, 1f))
        tiles.addView(
            tile("res:wger_bench_press", Sport.WEIGHTS.title, liftCaption(profile.id)) { openWeights() },
            ui.lp(0, MATCH_PARENT, 1f).apply { leftMargin = ui.ipx(40) },
        )
        root.addView(tiles, ui.lp(MATCH_PARENT, 0, 1f).apply { topMargin = ui.ipx(50) })

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(ui.pill("← PROFILVÁLASZTÁS") {
            startActivity(Intent(this, ProfileActivity::class.java))
            finish()
        })
        bottom.addView(View(this), ui.lp(0, 1, 1f))
        bottom.addView(ui.pill("NAPLÓ") { startActivity(Intent(this, LogActivity::class.java)) })
        root.addView(bottom, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(36) })
        setContentView(root)
    }

    private fun liftCaption(profileId: Long): String =
        if (Db.get(this).activeLift(profileId) != null) "Van folyamatban lévő edzésed" else "Edzésterv, sorozatok, pihenő"

    private fun tile(image: String, title: String, caption: String, onClick: () -> Unit): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = ui.rounded(CARD, 28)
            setPadding(ui.ipx(30), ui.ipx(30), ui.ipx(30), ui.ipx(30))
            setOnClickListener { onClick() }
        }
        card.addView(ui.picture(image, title, 300))
        card.addView(ui.text(title, 44, INK, ui.bold), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(28) })
        card.addView(ui.text(caption, 22, MUTED, ui.regular), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(10) })
        return card
    }

    private fun openElliptical() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun openWeights() {
        startActivity(Intent(this, WeightsActivity::class.java))
    }
}
