package hu.kettleredzo.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView

// Indító képernyő: ki edz most? Koppintás = belépés, hosszú nyomás = szerkesztés / törlés.
class ProfileActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var grid: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edzés közben nem lehet profilt váltani, ilyenkor egyenesen az edzésképernyőre megyünk.
        if (KettlerState.running) {
            openMain()
            return
        }
        ui = Ui(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(ui.ipx(60), ui.ipx(45), ui.ipx(30), 0)
        }
        root.addView(ui.text("KETTLER", 32, INK, ui.light, 0.35f))
        root.addView(ui.text("Ki edz ma?", 72, INK, ui.bold), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = ui.ipx(40)
        })
        root.addView(ui.text("Hosszan nyomva szerkesztheted vagy törölheted a profilt.", 20, MUTED, ui.regular),
            ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(14) })
        grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, ui.ipx(30))
        }
        root.addView(ScrollView(this).apply { addView(grid) }, ui.lp(MATCH_PARENT, 0, 1f).apply {
            topMargin = ui.ipx(40)
        })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (::ui.isInitialized) refresh()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun refresh() {
        grid.removeAllViews()
        val tiles = Db.get(this).profiles().map { profileTile(it) } + addTile()
        for (rowTiles in tiles.chunked(COLUMNS)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until COLUMNS) {
                val tile = rowTiles.getOrNull(i) ?: View(this)
                row.addView(tile, ui.lp(0, ui.ipx(250), 1f).apply { rightMargin = ui.ipx(30) })
            }
            grid.addView(row, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = ui.ipx(30) })
        }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = ui.rounded(CARD, 28)
        setPadding(ui.ipx(16), 0, ui.ipx(16), 0)
    }

    private fun profileTile(p: Profile): View {
        val card = card()
        val initial = ui.text(p.name.take(1).uppercase(), 48, Color.WHITE, ui.bold).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(BAR)
            }
        }
        card.addView(initial, ui.lp(ui.ipx(100), ui.ipx(100)))
        card.addView(ui.text(p.name, 34, INK, ui.bold), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = ui.ipx(18)
        })
        card.addView(ui.text("${p.age} év · ${weightText(p.weightKg)} kg", 18, MUTED, ui.regular),
            ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(8) })
        card.setOnClickListener { select(p) }
        card.setOnLongClickListener {
            editProfile(p.id)
            true
        }
        return card
    }

    private fun addTile(): View {
        val card = card()
        card.addView(ui.text("+", 80, INK, ui.light))
        card.addView(ui.text("Új profil", 26, INK, ui.medium), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = ui.ipx(10)
        })
        card.setOnClickListener { editProfile(null) }
        return card
    }

    private fun select(p: Profile) {
        Prefs.setCurrentProfileId(this, p.id)
        openMain()
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun editProfile(id: Long?) {
        val intent = Intent(this, ProfileEditActivity::class.java)
        if (id != null) intent.putExtra(ProfileEditActivity.EXTRA_PROFILE_ID, id)
        startActivity(intent)
    }

    private fun weightText(kg: Double) = if (kg % 1.0 == 0.0) kg.toInt().toString() else formatNum(kg, 1)

    companion object {
        private const val COLUMNS = 4
    }
}
