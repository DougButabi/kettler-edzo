package hu.kettleredzo.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray

// Első indításkor (és visszavont kulcs után): bejelentkezés a ULC-be, és az eszköz típusa.
// Közös eszköz (pl. tablet a gépnél): profilválasztóval megy tovább. Személyes eszköz (telefon):
// itt választja ki, kié, és onnantól csak az a profil létezik rajta. Kulcs nélkül nem lehet továbblépni.
class SetupActivity : KettlerActivity() {
    private lateinit var ui: Ui
    private lateinit var pane: LinearLayout
    private var personal = false
    private var waitingForNewProfile: Set<Long>? = null // „Új profil” előtti profilok, hogy felismerjük az újat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this).apply { settingsMode() }
        personal = savedInstanceState?.getBoolean(STATE_PERSONAL) ?: Prefs.personal(this)
        val root = ui.splitRoot()
        val left = ui.sidePanel("Üdv!")
        if (!ui.portrait) {
            left.addView(ui.note(
                "A Kettler edző az otthoni szerverrel (ULC) tartja szinkronban az edzéseket, így a telefon " +
                    "és a tablet ugyanazt látja. Az első indításhoz otthoni wifi kell.",
                6,
            ), ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(40) })
        }
        root.addView(left, ui.sideLp(0.38f))
        pane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ui.paneLayout(pane)
        root.addView(ScrollView(this).apply { addView(pane) }, ui.mainLp(0.62f))
        setContentView(root)
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PERSONAL, personal)
    }

    override fun onResume() {
        super.onResume()
        val before = waitingForNewProfile ?: return
        waitingForNewProfile = null
        // Az „Új profil” után: az új profil lesz ennek az eszköznek a gazdája.
        val created = Db.get(this).profiles().filter { it.id !in before }.maxByOrNull { it.id } ?: return
        Sync.profileUuid(this, created.id)?.let { bind(it, created.id) }
    }

    private fun render() {
        pane.removeAllViews()
        if (Sync.configured(this) && Prefs.personal(this) && Prefs.boundProfileUuid(this).isBlank()) buildChooser() else buildLogin()
    }

    // ---- 1. lépés: bejelentkezés és az eszköz típusa ----

    private fun buildLogin() {
        pane.addView(ui.label("ULC CÍME"))
        val url = field(ID_URL, Prefs.syncUrl(this), Prefs.DEFAULT_SYNC_URL, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        pane.addView(url, ui.fieldLp())
        pane.addView(ui.label("ULC-FELHASZNÁLÓNÉV"), sectionLp())
        val user = field(ID_USER, "", "pl. tamas", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        pane.addView(user, ui.fieldLp())
        pane.addView(ui.label("JELSZÓ"), sectionLp())
        val password = field(ID_PASSWORD, "", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, last = true)
        pane.addView(password, ui.fieldLp())

        pane.addView(ui.label("EZ AZ ESZKÖZ"), sectionLp())
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lateinit var sharedBtn: TextView
        lateinit var personalBtn: TextView
        val explain = ui.note("", 4)
        fun update() {
            ui.setChosen(sharedBtn, !personal)
            ui.setChosen(personalBtn, personal)
            explain.text = if (personal) {
                "Egy emberé (pl. a telefonod): csak az ő profilja lesz rajta, másikat nem lehet választani."
            } else {
                "Többen használják (pl. a tablet a gépnél): indításkor mindenki a saját profilját választja."
            }
        }
        sharedBtn = ui.choice("Közös") { personal = false; update() }
        personalBtn = ui.choice("Személyes") { personal = true; update() }
        row.addView(sharedBtn, ui.lp(0, ui.ipx(90), 1f))
        row.addView(personalBtn, ui.lp(0, ui.ipx(90), 1f).apply { leftMargin = ui.ipx(30) })
        pane.addView(row, ui.fieldLp())
        pane.addView(explain, ui.fieldLp())
        update()

        val status = ui.text("", 22, DANGER, ui.medium).apply { maxLines = 3 }
        pane.addView(ui.button("Bejelentkezés", BAR, Color.WHITE) {
            if (user.text.isBlank() || password.text.isEmpty()) {
                status.text = "Add meg a felhasználónevet és a jelszót."
                return@button
            }
            status.setTextColor(MUTED)
            status.text = "Bejelentkezés…"
            val url0 = url.text.toString().ifBlank { Prefs.DEFAULT_SYNC_URL }
            Sync.login(this, url0, user.text.toString(), password.text.toString(), personal) { error ->
                if (isFinishing) return@login
                if (error != null) {
                    status.setTextColor(DANGER)
                    status.text = error
                    return@login
                }
                if (personal) {
                    render()
                } else {
                    status.text = "Szinkronizálás…"
                    Sync.runAsync(this) { openApp() }
                }
            }
        }, ui.lp(ui.ipx(320), ui.ipx(85)).apply { topMargin = ui.ipx(30) })
        pane.addView(status, ui.fieldLp())
        pane.addView(ui.note(
            "Ugyanazzal a felhasználóval lépj be, amivel a ULC weboldalára. A jelszót az app nem tárolja: a ULC " +
                "saját kulcsot ad ennek az eszköznek, amit a ULC Edzés oldalán vissza is vonhatsz.",
            6,
        ), sectionLp())
    }

    // ---- 2. lépés (személyes eszköz): kié? ----

    private fun buildChooser() {
        pane.addView(ui.label("KIÉ EZ AZ ESZKÖZ?"))
        pane.addView(ui.note("Válaszd ki a profilodat. Később csak a ULC-ben, az eszköz kulcsának visszavonásával változtatható."),
            ui.fieldLp())
        val server = JSONArray(Prefs.setupProfiles(this))
        val serverUuids = (0 until server.length()).map { server.getJSONObject(it).getString("uuid") }.toSet()
        val status = ui.text("", 22, MUTED, ui.medium).apply { maxLines = 3 }
        for (i in 0 until server.length()) {
            val o = server.getJSONObject(i)
            pane.addView(card(o.getString("name"), "${o.optInt("birth_year")} · a ULC-ben") {
                status.text = "Letöltés…"
                pick(o.getString("uuid"), status)
            }, ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(14) })
        }
        // Ezen az eszközön létrehozott, még fel nem töltött profilok
        for (p in Db.get(this).profiles()) {
            val uuid = Sync.profileUuid(this, p.id) ?: continue
            if (uuid in serverUuids) continue
            pane.addView(card(p.name, "${p.birthYear} · ezen az eszközön") { bind(uuid, p.id) },
                ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(14) })
        }
        pane.addView(ui.button("+ Új profil", BAR, Color.WHITE) {
            waitingForNewProfile = Db.get(this).profiles().map { it.id }.toSet()
            startActivity(Intent(this, ProfileEditActivity::class.java))
        }, ui.lp(ui.ipx(320), ui.ipx(85)).apply { topMargin = ui.ipx(24) })
        pane.addView(status, ui.fieldLp())
    }

    // A ULC-ben lévő profil: szinkron után már nálunk is megvan.
    private fun pick(uuid: String, status: TextView) {
        Prefs.setBoundProfileUuid(this, uuid)
        Sync.runAsync(this) { r ->
            if (isFinishing) return@runAsync
            val id = Sync.boundProfileId(this)
            if (id == null) {
                Prefs.setBoundProfileUuid(this, "")
                status.text = if (r.ok) "A profil nem jött le, próbáld újra." else "Nem sikerült: ${r.message}"
                return@runAsync
            }
            bind(Prefs.boundProfileUuid(this), id)
        }
    }

    private fun bind(uuid: String, id: Long) {
        Prefs.setBoundProfileUuid(this, uuid)
        Prefs.setCurrentProfileId(this, id)
        Sync.runAsync(this)
        openApp()
    }

    private fun openApp() {
        if (isFinishing) return
        startActivity(Intent(this, ProfileActivity::class.java))
        finish()
    }

    private fun card(title: String, subtitle: String, onClick: () -> Unit): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.rounded(CARD, 24)
            setPadding(ui.ipx(28), ui.ipx(18), ui.ipx(28), ui.ipx(18))
            setOnClickListener { onClick() }
        }
        card.addView(ui.text(title, 32, INK, ui.bold))
        card.addView(ui.text(subtitle, 20, MUTED, ui.regular), ui.lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.ipx(6) })
        return card
    }

    // Rögzített azonosító: forgatás után az Android visszaírja a beírt szöveget.
    private fun field(id: Int, value: String, hint: String, type: Int, last: Boolean = false): EditText =
        ui.field(value, hint, type, last).apply { this.id = id }

    private fun sectionLp() = ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.ipx(40) }

    companion object {
        private const val STATE_PERSONAL = "personal"
        private const val ID_URL = 0x5e7101
        private const val ID_USER = 0x5e7102
        private const val ID_PASSWORD = 0x5e7103
    }
}
