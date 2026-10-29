package hu.kettleredzo.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

// ---- A súlyzós modul adatai ----

// A kép helye: "res:<drawable neve>" (beépített wger-rajz) vagy "file:<útvonal a files mappán belül>" (saját kép).
data class Equipment(val id: Long, val name: String, val image: String?, val seedKey: String? = null)

data class Exercise(
    val id: Long,
    val name: String,
    val equipmentId: Long?,
    val image: String?,
    val seedKey: String? = null,
)

data class PlannedSet(val reps: Int, val kg: Double) {
    companion object {
        fun parse(json: String): List<PlannedSet> {
            val a = JSONArray(json)
            return (0 until a.length()).map {
                val o = a.getJSONObject(it)
                PlannedSet(o.getInt("reps"), o.getDouble("kg"))
            }
        }

        fun toJson(sets: List<PlannedSet>): String =
            JSONArray(sets.map { JSONObject().put("reps", it.reps).put("kg", it.kg) }).toString()
    }
}

data class PlanItem(val id: Long, val exerciseId: Long, val restSec: Int, val sets: List<PlannedSet>)

data class PlanDay(
    val id: Long,
    val profileId: Long,
    val letter: String,
    val position: Int,
    val items: List<PlanItem> = emptyList(),
)

// Egy edzés egy sorozata: a terv szerinti és a ténylegesen elvégzett ismétlés / súly
data class LiftSet(
    val id: Long,
    val planItemId: Long,
    val exerciseId: Long,
    val itemPos: Int,
    val setPos: Int,
    val plannedReps: Int,
    val plannedKg: Double,
    val reps: Int,
    val kg: Double,
    val restSec: Int,
    val done: Boolean,
    val updatedAt: Long,
)

object Lifting {
    const val DEFAULT_REST_SEC = 90
    const val REST_STEP_SEC = 15

    // Súlyzós edzés MET-értéke (Compendium of Physical Activities: többféle gyakorlat,
    // 8–15 ismétlés). Pulzuspánt nélkül ebből becsülünk: kcal = MET × testsúly × óra.
    const val MET = 3.5

    fun kcal(weightKg: Double, ms: Long) = MET * weightKg * ms / 3_600_000.0

    // Súlylépés: könnyű súlynál 1 kg (kézi súlyzó), nehezebbnél 2,5 kg (tárcsák)
    fun kgStep(kg: Double) = if (kg < 20) 1.0 else 2.5

    // Javasolt emelés a következő alkalomra, ha minden sorozat teljesült
    fun increase(kg: Double) = if (kg < 20) 1.0 else 2.5

    // Ha a befejezés jóval az utolsó sorozat után történik (pl. elfelejtették lezárni),
    // az edzés vége az utolsó sorozat + ennyi idő.
    const val MAX_TAIL_MS = 10 * 60_000L

    // A következő szabad betű (A, B, C…)
    fun nextLetter(days: List<PlanDay>): String {
        val used = days.map { it.letter }.toSet()
        return ('A'..'Z').map { it.toString() }.firstOrNull { it !in used } ?: "?"
    }
}

// A sorozatok röviden, az egymás utáni egyformákat összevonva: "1 × 8 × 15 kg, 3 × 8 × 40 kg"
fun setsText(sets: List<PlannedSet>): String {
    val groups = mutableListOf<Pair<PlannedSet, Int>>()
    for (s in sets) {
        if (groups.lastOrNull()?.first == s) groups[groups.size - 1] = s to groups.last().second + 1 else groups += s to 1
    }
    return groups.joinToString(", ") { (s, n) -> "$n × ${s.reps} × ${kgText(s.kg)} kg" }
}

fun kgText(kg: Double) = if (kg % 1.0 == 0.0) kg.toLong().toString() else formatNum(kg, if (kg * 10 % 1.0 == 0.0) 1 else 2)

// ---- Alapadatok: a felhasználó gépei, a hozzájuk illő gyakorlatok és kész tervek ----

object Catalog {
    private class SeedExercise(val key: String, val name: String, val equipment: String, val image: String?)

    private val EQUIPMENT = listOf(
        Triple("bench", "Fekvenyomó pad", "wger_bench_press"),
        Triple("scott", "Scott-pad", "wger_preacher_curl"),
        Triple("pulldown", "Tárcsás lehúzó", "wger_lat_pulldown"),
        Triple("dumbbell", "Kézi súlyzók", "wger_biceps_curl"),
    )

    private val EXERCISES = listOf(
        SeedExercise("bench_press", "Fekvenyomás rúddal", "bench", "wger_bench_press"),
        SeedExercise("db_bench_press", "Fekvenyomás kézi súlyzóval", "bench", "wger_db_bench_press"),
        SeedExercise("incline_db_press", "Ferde fekvenyomás kézi súlyzóval", "bench", "wger_incline_db_press"),
        SeedExercise("db_fly", "Tárogatás kézi súlyzóval", "bench", "wger_db_fly"),
        SeedExercise("preacher_curl", "Bicepszhajlítás Scott-padon", "scott", "wger_preacher_curl"),
        SeedExercise("lat_pulldown", "Lehúzás mellhez", "pulldown", "wger_lat_pulldown"),
        SeedExercise("triceps_pushdown", "Tricepsz letolás", "pulldown", "wger_triceps_pushdown"),
        SeedExercise("biceps_curl", "Bicepszhajlítás kézi súlyzóval", "dumbbell", "wger_biceps_curl"),
        SeedExercise("hammer_curl", "Kalapácshajlítás", "dumbbell", "wger_hammer_curl"),
        SeedExercise("shoulder_press", "Vállból nyomás kézi súlyzóval", "dumbbell", "wger_shoulder_press"),
        SeedExercise("lateral_raise", "Oldalemelés", "dumbbell", "wger_lateral_raise"),
        SeedExercise("db_row", "Egykezes evezés", "dumbbell", "wger_db_row"),
        SeedExercise("triceps_overhead", "Tricepsznyújtás fej fölött", "dumbbell", "wger_triceps_overhead"),
        SeedExercise("shrug", "Vállvonogatás", "dumbbell", "wger_shrug"),
        SeedExercise("goblet_squat", "Guggolás kézi súlyzóval", "dumbbell", "wger_dumbbell_squat"),
        SeedExercise("lunge", "Kitörés kézi súlyzóval", "dumbbell", "wger_lunge"),
        SeedExercise("romanian_deadlift", "Román felhúzás", "dumbbell", "wger_romanian_deadlift"),
    )

    // 0.6-ban ezek még más néven / kép nélkül kerültek be; a 4. adatbázis-verzió frissíti őket,
    // ha a felhasználó közben nem írta át.
    val RENAMED = mapOf(
        "goblet_squat" to "Serleges guggolás",
        "romanian_deadlift" to "Román felhúzás kézi súlyzóval",
    )

    // A Névjegyben feltüntetett szerző (a rajzok CC-BY-SA licencűek, forrás: everkinetic.com, wger.de)
    val IMAGE_CREDITS = listOf("Everkinetic (CC-BY-SA 3.0 és 4.0)")

    // 4. adatbázis-verzió: a kép nélküli beépített gyakorlatok képet kapnak, a régi nevek frissülnek.
    fun upgradeTo4(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        for (e in EXERCISES) {
            if (e.image != null) {
                db.execSQL(
                    "UPDATE exercises SET image = ?, updated_at = ? WHERE seed_key = ? AND image IS NULL",
                    arrayOf("res:${e.image}", now, e.key),
                )
            }
            RENAMED[e.key]?.let { old ->
                db.execSQL(
                    "UPDATE exercises SET name = ?, updated_at = ? WHERE seed_key = ? AND name = ?",
                    arrayOf(e.name, now, e.key, old),
                )
            }
        }
    }

    // A beépített elem eredeti képe (ha a felhasználó lecserélte, ezzel állítható vissza)
    fun defaultEquipmentImage(seedKey: String?) = EQUIPMENT.firstOrNull { it.first == seedKey }?.let { "res:${it.third}" }
    fun defaultExerciseImage(seedKey: String?) = EXERCISES.firstOrNull { it.key == seedKey }?.image?.let { "res:$it" }

    // A módosítatlan beépített elemek időbélyege: szinkronkor sosem írják felül egy másik eszközön tett módosítást.
    const val SEED_TIME = 1L

    // 7. adatbázis-verzió: a már telepített, de nem módosított beépített elemek is SEED_TIME-ot kapnak.
    fun resetSeedTimes(db: SQLiteDatabase) {
        for ((key, name, image) in EQUIPMENT) {
            db.execSQL(
                "UPDATE equipment SET updated_at = ? WHERE seed_key = ? AND name = ? AND image = ? AND deleted = 0",
                arrayOf(SEED_TIME, key, name, "res:$image"),
            )
        }
        for (e in EXERCISES) {
            db.execSQL(
                "UPDATE exercises SET updated_at = ? WHERE seed_key = ? AND name = ? AND image IS ? AND deleted = 0",
                arrayOf(SEED_TIME, e.key, e.name, e.image?.let { "res:$it" }),
            )
        }
    }

    fun seed(db: SQLiteDatabase) {
        val equipmentIds = mutableMapOf<String, Long>()
        for ((key, name, image) in EQUIPMENT) {
            equipmentIds[key] = db.insertOrThrow("equipment", null, ContentValues().apply {
                put("name", name)
                put("image", "res:$image")
                put("seed_key", key)
                put("uuid", seedUuid("eq", key))
                put("updated_at", SEED_TIME)
            })
        }
        for (e in EXERCISES) {
            db.insertOrThrow("exercises", null, ContentValues().apply {
                put("name", e.name)
                put("equipment_id", equipmentIds.getValue(e.equipment))
                put("image", e.image?.let { "res:$it" })
                put("seed_key", e.key)
                put("uuid", seedUuid("ex", e.key))
                put("updated_at", SEED_TIME)
            })
        }
    }

    // ---- Kész tervek ----

    class TemplateItem(val exercise: String, val sets: Int, val reps: Int, val kg: Double)
    class Template(val title: String, val description: String, val days: List<List<TemplateItem>>)

    private fun t(exercise: String, sets: Int, reps: Int, kg: Double) = TemplateItem(exercise, sets, reps, kg)

    val TEMPLATES = listOf(
        Template(
            "Kezdő teljes test (A/B)",
            "Heti 2–3 alkalom, a két nap felváltva. Minden alkalommal az egész test dolgozik.",
            listOf(
                listOf(
                    t("bench_press", 3, 10, 20.0),
                    t("lat_pulldown", 3, 10, 20.0),
                    t("shoulder_press", 3, 10, 6.0),
                    t("preacher_curl", 3, 10, 10.0),
                    t("triceps_pushdown", 3, 12, 10.0),
                ),
                listOf(
                    t("incline_db_press", 3, 10, 8.0),
                    t("db_row", 3, 10, 10.0),
                    t("lateral_raise", 3, 12, 4.0),
                    t("hammer_curl", 3, 10, 6.0),
                    t("lunge", 3, 10, 6.0),
                ),
            ),
        ),
        Template(
            "Háromnapos osztott (A/B/C)",
            "A: mell és tricepsz · B: hát és bicepsz · C: váll és láb. Heti 3 alkalom.",
            listOf(
                listOf(
                    t("bench_press", 4, 8, 20.0),
                    t("incline_db_press", 3, 10, 8.0),
                    t("db_fly", 3, 12, 6.0),
                    t("triceps_pushdown", 3, 12, 10.0),
                    t("triceps_overhead", 3, 12, 6.0),
                ),
                listOf(
                    t("lat_pulldown", 4, 10, 20.0),
                    t("db_row", 3, 10, 10.0),
                    t("shrug", 3, 12, 10.0),
                    t("preacher_curl", 3, 10, 10.0),
                    t("hammer_curl", 3, 10, 6.0),
                ),
                listOf(
                    t("shoulder_press", 4, 10, 6.0),
                    t("lateral_raise", 3, 12, 4.0),
                    t("goblet_squat", 3, 12, 10.0),
                    t("lunge", 3, 10, 6.0),
                    t("romanian_deadlift", 3, 10, 10.0),
                ),
            ),
        ),
    )

    // Betölti a kész tervet a profilnak (a régi tervet előbb törli).
    fun applyTemplate(c: Context, profileId: Long, template: Template) {
        val db = Db.get(c)
        db.deletePlan(profileId)
        template.days.forEachIndexed { i, items ->
            val planItems = items.mapNotNull { ti ->
                val ex = db.exerciseBySeed(ti.exercise) ?: return@mapNotNull null
                PlanItem(0, ex.id, Lifting.DEFAULT_REST_SEC, List(ti.sets) { PlannedSet(ti.reps, ti.kg) })
            }
            db.savePlanDay(PlanDay(0, profileId, ('A' + i).toString(), i, planItems))
        }
    }
}

// ---- Képek ----

object Images {
    private val cache = LruCache<String, Bitmap>(24)

    fun load(c: Context, image: String?): Bitmap? {
        if (image.isNullOrBlank()) return null
        cache.get(image)?.let { return it }
        val bmp = when {
            image.startsWith("res:") -> {
                val id = c.resources.getIdentifier(image.removePrefix("res:"), "drawable", c.packageName)
                if (id == 0) null else BitmapFactory.decodeResource(c.resources, id)
            }
            image.startsWith("file:") -> BitmapFactory.decodeFile(File(c.filesDir, image.removePrefix("file:")).path)
            else -> null
        } ?: return null
        cache.put(image, bmp)
        return bmp
    }

    // A választott képet legfeljebb 800 px-esre kicsinyítve az app saját mappájába másolja.
    fun importImage(c: Context, uri: Uri): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bmp = c.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val scale = MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
        val out = if (scale < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        } else {
            bmp
        }
        File(c.filesDir, "kepek").mkdirs()
        val name = "kepek/${newUuid()}.jpg"
        FileOutputStream(File(c.filesDir, name)).use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return "file:$name"
    }

    private const val MAX_SIDE = 800
}
