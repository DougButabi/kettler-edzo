package hu.kettleredzo.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar
import java.util.UUID

enum class Sex(val code: String) {
    MALE("M"),
    FEMALE("F");

    companion object {
        fun of(code: String) = entries.firstOrNull { it.code == code } ?: MALE
    }
}

enum class Sport(val code: String, val title: String) {
    ELLIPTICAL("elliptical", "Elliptikus tréner"),
    WEIGHTS("weights", "Súlyzós edzés"),
    BIKE("bike", "Kerékpár");

    companion object {
        fun of(code: String?) = entries.firstOrNull { it.code == code } ?: ELLIPTICAL
    }
}

data class Profile(
    val id: Long,
    val name: String,
    val birthYear: Int,
    val weightKg: Double,
    val sex: Sex,
) {
    val age get() = Calendar.getInstance().get(Calendar.YEAR) - birthYear
    val maxHr get() = 220 - age
}

data class Workout(
    val id: Long,
    val profileId: Long,
    val startedAt: Long,
    val movingMs: Long,
    val distanceKm: Double,
    val avgSpeed: Double,
    val maxSpeed: Double,
    val kcal: Double,
    val level: Int,
    val avgHr: Int? = null, // a pulzuspánttal töltődik majd
    val maxHr: Int? = null,
    val mode: WorkoutMode = WorkoutMode.FREE,
    val goal: Double? = null,
    val programWeek: Int? = null,
    val completed: Boolean = false, // elérte-e a célt / végigment-e a programon / minden sorozat megvolt
    val sport: Sport = Sport.ELLIPTICAL,
    val title: String? = null, // súlyzós edzésnél a nap betűje, pl. "A"
    val elevGain: Double? = null, // kerékpárnál a megtett szintemelkedés (m)
) {
    val label: String
        get() = when (sport) {
            Sport.ELLIPTICAL -> modeLabel(mode, goal, programWeek)
            Sport.WEIGHTS -> "Súlyzós" + (title?.let { " · $it nap" } ?: "")
            Sport.BIKE -> "Kerékpár · " + modeLabel(mode, goal, null)
        } + if (completed) " ✓" else ""
}

data class Totals(val count: Int, val movingMs: Long, val distanceKm: Double, val kcal: Double)

// Egy súlyzós edzés sorozatainak összesítője a naplóhoz
data class LiftSummary(val doneSets: Int, val totalSets: Int, val volumeKg: Double, val exercises: List<String>)

// Helyi adatbázis: profilok, edzésnapló és a súlyzós modul adatai.
// Szinkronhoz minden táblában: uuid (eszközök között egyedi azonosító), updated_at
// (utolsó módosítás, ms) és deleted (törlés csak megjelöléssel, hogy a többi eszköz is értesüljön).
class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "kettler.db", null, 5) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE profiles (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                birth_year INTEGER NOT NULL,
                weight_kg REAL NOT NULL,
                sex TEXT NOT NULL)""",
        )
        // Profil törlésekor az edzései is törlődnek (ON DELETE CASCADE).
        db.execSQL(
            """CREATE TABLE workouts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                profile_id INTEGER NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
                started_at INTEGER NOT NULL,
                moving_ms INTEGER NOT NULL,
                distance_km REAL NOT NULL,
                avg_speed REAL NOT NULL,
                max_speed REAL NOT NULL,
                kcal REAL NOT NULL,
                level INTEGER NOT NULL,
                avg_hr INTEGER,
                max_hr INTEGER)""",
        )
        db.execSQL("CREATE INDEX workouts_profile ON workouts(profile_id, started_at)")
        onUpgrade(db, 1, 5)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 2. verzió: edzésmód, cél, program és teljesítés. A régi edzések „Szabad edzés”-ként maradnak meg.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE workouts ADD COLUMN mode TEXT NOT NULL DEFAULT 'free'")
            db.execSQL("ALTER TABLE workouts ADD COLUMN goal REAL")
            db.execSQL("ALTER TABLE workouts ADD COLUMN program INTEGER")
            db.execSQL("ALTER TABLE workouts ADD COLUMN completed INTEGER NOT NULL DEFAULT 0")
        }
        // 3. verzió: szinkronoszlopok, sportág, és a súlyzós modul táblái.
        if (oldVersion < 3) {
            val now = System.currentTimeMillis()
            for (t in listOf("profiles", "workouts")) {
                db.execSQL("ALTER TABLE $t ADD COLUMN uuid TEXT")
                db.execSQL("ALTER TABLE $t ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE $t ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE $t SET uuid = lower(hex(randomblob(16))), updated_at = $now")
                db.execSQL("CREATE UNIQUE INDEX ${t}_uuid ON $t(uuid)")
            }
            db.execSQL("ALTER TABLE workouts ADD COLUMN sport TEXT NOT NULL DEFAULT 'elliptical'")
            db.execSQL("ALTER TABLE workouts ADD COLUMN title TEXT")
            db.execSQL("ALTER TABLE workouts ADD COLUMN in_progress INTEGER NOT NULL DEFAULT 0")

            val sync = "uuid TEXT NOT NULL UNIQUE, updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0"
            db.execSQL(
                """CREATE TABLE equipment (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    image TEXT,
                    seed_key TEXT,
                    $sync)""",
            )
            db.execSQL(
                """CREATE TABLE exercises (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    equipment_id INTEGER REFERENCES equipment(id),
                    image TEXT,
                    seed_key TEXT,
                    $sync)""",
            )
            // Edzésterv: profilonként betűjeles napok (A, B, C…)
            db.execSQL(
                """CREATE TABLE plan_days (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    profile_id INTEGER NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
                    letter TEXT NOT NULL,
                    position INTEGER NOT NULL,
                    $sync)""",
            )
            // Egy nap egy gyakorlata. A sorozatok JSON-ban: [{"reps":8,"kg":15.0}, …]
            db.execSQL(
                """CREATE TABLE plan_items (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    day_id INTEGER NOT NULL REFERENCES plan_days(id) ON DELETE CASCADE,
                    exercise_id INTEGER NOT NULL REFERENCES exercises(id),
                    position INTEGER NOT NULL,
                    rest_sec INTEGER NOT NULL,
                    sets TEXT NOT NULL,
                    $sync)""",
            )
            // Egy súlyzós edzés sorozatai: a terv szerinti és a ténylegesen elvégzett érték
            db.execSQL(
                """CREATE TABLE workout_sets (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    workout_id INTEGER NOT NULL REFERENCES workouts(id) ON DELETE CASCADE,
                    plan_item_id INTEGER,
                    exercise_id INTEGER NOT NULL REFERENCES exercises(id),
                    item_pos INTEGER NOT NULL,
                    set_pos INTEGER NOT NULL,
                    planned_reps INTEGER NOT NULL,
                    planned_kg REAL NOT NULL,
                    reps INTEGER NOT NULL,
                    weight_kg REAL NOT NULL,
                    rest_sec INTEGER NOT NULL,
                    done INTEGER NOT NULL DEFAULT 0,
                    $sync)""",
            )
            db.execSQL("CREATE INDEX workout_sets_workout ON workout_sets(workout_id)")
            Catalog.seed(db)
        }
        // 4. verzió: egységes gyakorlatrajzok (a hiányzók pótlása, két gyakorlat új neve)
        if (oldVersion < 4) Catalog.upgradeTo4(db)
        // 5. verzió: kerékpár – szintemelkedés és a GPS-útvonal ([[lat, lon, magasság, idő], …] JSON-ban)
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE workouts ADD COLUMN elev_gain REAL")
            db.execSQL(
                """CREATE TABLE tracks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    workout_id INTEGER NOT NULL REFERENCES workouts(id) ON DELETE CASCADE,
                    points TEXT NOT NULL,
                    uuid TEXT NOT NULL UNIQUE, updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0)""",
            )
        }
    }

    // ---- Profilok ----

    fun profiles(): List<Profile> =
        readableDatabase.rawQuery("SELECT * FROM profiles WHERE deleted = 0 ORDER BY name COLLATE NOCASE", null).use { c ->
            generateSequence { if (c.moveToNext()) c.toProfile() else null }.toList()
        }

    fun profile(id: Long): Profile? =
        readableDatabase.rawQuery("SELECT * FROM profiles WHERE id = ? AND deleted = 0", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.toProfile() else null
        }

    fun saveProfile(p: Profile): Long {
        val v = ContentValues().apply {
            put("name", p.name)
            put("birth_year", p.birthYear)
            put("weight_kg", p.weightKg)
            put("sex", p.sex.code)
        }
        return save("profiles", p.id, v)
    }

    // A profillal együtt az edzései és az edzésterve is törlődik.
    fun deleteProfile(id: Long) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.transaction {
            val arg = arrayOf(id.toString())
            db.execSQL("UPDATE workouts SET deleted = 1, updated_at = $now WHERE profile_id = ?", arg)
            db.execSQL(
                "UPDATE plan_items SET deleted = 1, updated_at = $now WHERE day_id IN (SELECT id FROM plan_days WHERE profile_id = ?)",
                arg,
            )
            db.execSQL("UPDATE plan_days SET deleted = 1, updated_at = $now WHERE profile_id = ?", arg)
            softDelete("profiles", id)
        }
    }

    // ---- Edzések ----

    fun addWorkout(w: Workout): Long = save("workouts", 0, workoutValues(w))

    fun updateWorkout(w: Workout) {
        save("workouts", w.id, workoutValues(w).apply { put("in_progress", 0) })
    }

    private fun workoutValues(w: Workout) = ContentValues().apply {
        put("profile_id", w.profileId)
        put("started_at", w.startedAt)
        put("moving_ms", w.movingMs)
        put("distance_km", w.distanceKm)
        put("avg_speed", w.avgSpeed)
        put("max_speed", w.maxSpeed)
        put("kcal", w.kcal)
        put("level", w.level)
        w.avgHr?.let { put("avg_hr", it) }
        w.maxHr?.let { put("max_hr", it) }
        put("mode", w.mode.code)
        w.goal?.let { put("goal", it) }
        w.programWeek?.let { put("program", it) }
        put("completed", if (w.completed) 1 else 0)
        put("sport", w.sport.code)
        put("title", w.title)
        w.elevGain?.let { put("elev_gain", it) }
    }

    // Egy tekerés útvonala: [[lat, lon], …] (üres, ha nincs mentve)
    fun track(workoutId: Long): List<DoubleArray> {
        val json = readableDatabase.rawQuery(
            "SELECT points FROM tracks WHERE workout_id = ? AND deleted = 0 LIMIT 1",
            arrayOf(workoutId.toString()),
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return emptyList()
        val a = org.json.JSONArray(json)
        return (0 until a.length()).map {
            val p = a.getJSONArray(it)
            doubleArrayOf(p.getDouble(0), p.getDouble(1))
        }
    }

    // Kerékpáros edzés mentése az útvonalával együtt
    fun addRide(w: Workout, points: String): Long {
        var id = 0L
        writableDatabase.transaction {
            id = addWorkout(w)
            save("tracks", 0, ContentValues().apply {
                put("workout_id", id)
                put("points", points)
            })
        }
        return id
    }

    fun workout(id: Long): Workout? =
        readableDatabase.rawQuery("SELECT * FROM workouts WHERE id = ? AND deleted = 0", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.toWorkout() else null
        }

    // Végigcsinált programos edzések száma (ebből jön az ajánlott hét)
    fun completedProgramCount(profileId: Long): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM workouts WHERE profile_id = ? AND mode = 'program' AND completed = 1 AND $DONE",
            arrayOf(profileId.toString()),
        ).use { c ->
            c.moveToFirst()
            c.getInt(0)
        }

    fun workouts(profileId: Long, since: Long = 0, limit: Int = 500, sport: Sport? = null): List<Workout> =
        readableDatabase.rawQuery(
            "SELECT * FROM workouts WHERE profile_id = ? AND started_at >= ? AND $DONE AND sport LIKE ? ORDER BY started_at DESC LIMIT $limit",
            arrayOf(profileId.toString(), since.toString(), sport?.code ?: "%"),
        ).use { c ->
            generateSequence { if (c.moveToNext()) c.toWorkout() else null }.toList()
        }

    fun deleteWorkout(id: Long) {
        writableDatabase.transaction {
            for (t in listOf("workout_sets", "tracks")) {
                writableDatabase.execSQL(
                    "UPDATE $t SET deleted = 1, updated_at = ${System.currentTimeMillis()} WHERE workout_id = ?",
                    arrayOf(id.toString()),
                )
            }
            softDelete("workouts", id)
        }
    }

    // Összesítő; ha a sport meg van adva, csak arra a sportra
    fun totals(profileId: Long, since: Long = 0, sport: Sport? = null): Totals =
        readableDatabase.rawQuery(
            """SELECT COUNT(*), COALESCE(SUM(moving_ms), 0), COALESCE(SUM(distance_km), 0), COALESCE(SUM(kcal), 0)
               FROM workouts WHERE profile_id = ? AND started_at >= ? AND $DONE AND sport LIKE ?""",
            arrayOf(profileId.toString(), since.toString(), sport?.code ?: "%"),
        ).use { c ->
            c.moveToFirst()
            Totals(c.getInt(0), c.getLong(1), c.getDouble(2), c.getDouble(3))
        }

    // Az utolsó `days` nap edzésideje percben naponként (a legrégebbi elöl, a mai a végén), mindkét sportból.
    fun dailyMinutes(profileId: Long, days: Int): DoubleArray {
        val starts = dayStarts(days)
        val out = DoubleArray(days)
        for (w in workouts(profileId, since = starts[0], limit = 10_000)) {
            val i = (0 until days).firstOrNull { w.startedAt >= starts[it] && w.startedAt < starts[it + 1] } ?: continue
            out[i] += w.movingMs / 60_000.0
        }
        return out
    }

    // ---- Gépek és gyakorlatok ----

    fun equipmentList(): List<Equipment> =
        readableDatabase.rawQuery("SELECT * FROM equipment WHERE deleted = 0 ORDER BY name COLLATE NOCASE", null).use { c ->
            generateSequence { if (c.moveToNext()) c.toEquipment() else null }.toList()
        }

    // Törölt elemet is visszaad, hogy a régi edzések és tervek neve megmaradjon.
    fun equipment(id: Long): Equipment? =
        readableDatabase.rawQuery("SELECT * FROM equipment WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.toEquipment() else null
        }

    fun saveEquipment(e: Equipment): Long = save("equipment", e.id, ContentValues().apply {
        put("name", e.name)
        put("image", e.image)
        put("seed_key", e.seedKey)
    })

    fun deleteEquipment(id: Long) = softDelete("equipment", id)

    fun exercises(): List<Exercise> =
        readableDatabase.rawQuery("SELECT * FROM exercises WHERE deleted = 0 ORDER BY name COLLATE NOCASE", null).use { c ->
            generateSequence { if (c.moveToNext()) c.toExercise() else null }.toList()
        }

    fun exercise(id: Long): Exercise? =
        readableDatabase.rawQuery("SELECT * FROM exercises WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.toExercise() else null
        }

    fun exerciseBySeed(key: String): Exercise? =
        readableDatabase.rawQuery("SELECT * FROM exercises WHERE seed_key = ? AND deleted = 0", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.toExercise() else null
        }

    fun saveExercise(e: Exercise): Long = save("exercises", e.id, ContentValues().apply {
        put("name", e.name)
        if (e.equipmentId != null) put("equipment_id", e.equipmentId) else putNull("equipment_id")
        put("image", e.image)
        put("seed_key", e.seedKey)
    })

    fun deleteExercise(id: Long) = softDelete("exercises", id)

    // ---- Edzésterv ----

    fun planDays(profileId: Long): List<PlanDay> {
        val days = readableDatabase.rawQuery(
            "SELECT * FROM plan_days WHERE profile_id = ? AND deleted = 0 ORDER BY position",
            arrayOf(profileId.toString()),
        ).use { c ->
            generateSequence {
                if (c.moveToNext()) {
                    PlanDay(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        profileId = profileId,
                        letter = c.getString(c.getColumnIndexOrThrow("letter")),
                        position = c.getInt(c.getColumnIndexOrThrow("position")),
                    )
                } else {
                    null
                }
            }.toList()
        }
        return days.map { it.copy(items = planItems(it.id)) }
    }

    fun planDay(id: Long): PlanDay? {
        val day = readableDatabase.rawQuery("SELECT * FROM plan_days WHERE id = ? AND deleted = 0", arrayOf(id.toString())).use { c ->
            if (!c.moveToFirst()) return null
            PlanDay(
                id = id,
                profileId = c.getLong(c.getColumnIndexOrThrow("profile_id")),
                letter = c.getString(c.getColumnIndexOrThrow("letter")),
                position = c.getInt(c.getColumnIndexOrThrow("position")),
            )
        }
        return day.copy(items = planItems(id))
    }

    private fun planItems(dayId: Long): List<PlanItem> =
        readableDatabase.rawQuery(
            "SELECT * FROM plan_items WHERE day_id = ? AND deleted = 0 ORDER BY position",
            arrayOf(dayId.toString()),
        ).use { c ->
            generateSequence {
                if (c.moveToNext()) {
                    PlanItem(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        exerciseId = c.getLong(c.getColumnIndexOrThrow("exercise_id")),
                        restSec = c.getInt(c.getColumnIndexOrThrow("rest_sec")),
                        sets = PlannedSet.parse(c.getString(c.getColumnIndexOrThrow("sets"))),
                    )
                } else {
                    null
                }
            }.toList()
        }

    // Elmenti a napot a gyakorlataival: a meglévőket frissíti, az újakat felveszi, a kimaradtakat törli.
    fun savePlanDay(day: PlanDay): Long {
        val db = writableDatabase
        var dayId = day.id
        db.transaction {
            dayId = save("plan_days", day.id, ContentValues().apply {
                put("profile_id", day.profileId)
                put("letter", day.letter)
                put("position", day.position)
            })
            val keep = mutableSetOf<Long>()
            day.items.forEachIndexed { i, item ->
                keep += save("plan_items", item.id, ContentValues().apply {
                    put("day_id", dayId)
                    put("exercise_id", item.exerciseId)
                    put("position", i)
                    put("rest_sec", item.restSec)
                    put("sets", PlannedSet.toJson(item.sets))
                })
            }
            for (old in planItems(dayId)) if (old.id !in keep) softDelete("plan_items", old.id)
        }
        return dayId
    }

    fun deletePlanDay(id: Long) {
        writableDatabase.transaction {
            writableDatabase.execSQL(
                "UPDATE plan_items SET deleted = 1, updated_at = ${System.currentTimeMillis()} WHERE day_id = ?",
                arrayOf(id.toString()),
            )
            softDelete("plan_days", id)
        }
    }

    fun deletePlan(profileId: Long) {
        writableDatabase.transaction {
            for (d in planDays(profileId)) deletePlanDay(d.id)
        }
    }

    // ---- Súlyzós edzés ----

    // Elindít egy edzést a nap terve alapján: a naplóba „folyamatban” jelöléssel kerül,
    // a sorozatok pedig rögtön elmentődnek, így az app újraindulása után is folytatható.
    fun startLift(profileId: Long, day: PlanDay): Long {
        var workoutId = 0L
        writableDatabase.transaction {
            workoutId = save("workouts", 0, workoutValues(
                Workout(
                    id = 0, profileId = profileId, startedAt = System.currentTimeMillis(), movingMs = 0,
                    distanceKm = 0.0, avgSpeed = 0.0, maxSpeed = 0.0, kcal = 0.0, level = 0,
                    sport = Sport.WEIGHTS, title = day.letter,
                ),
            ).apply { put("in_progress", 1) })
            day.items.forEachIndexed { i, item ->
                item.sets.forEachIndexed { j, s ->
                    save("workout_sets", 0, ContentValues().apply {
                        put("workout_id", workoutId)
                        put("plan_item_id", item.id)
                        put("exercise_id", item.exerciseId)
                        put("item_pos", i)
                        put("set_pos", j)
                        put("planned_reps", s.reps)
                        put("planned_kg", s.kg)
                        put("reps", s.reps)
                        put("weight_kg", s.kg)
                        put("rest_sec", item.restSec)
                    })
                }
            }
        }
        return workoutId
    }

    // A profil folyamatban lévő súlyzós edzése, ha van
    fun activeLift(profileId: Long): Workout? =
        readableDatabase.rawQuery(
            "SELECT * FROM workouts WHERE profile_id = ? AND in_progress = 1 AND deleted = 0 ORDER BY started_at DESC LIMIT 1",
            arrayOf(profileId.toString()),
        ).use { c ->
            if (c.moveToFirst()) c.toWorkout() else null
        }

    fun liftSets(workoutId: Long): List<LiftSet> =
        readableDatabase.rawQuery(
            "SELECT * FROM workout_sets WHERE workout_id = ? AND deleted = 0 ORDER BY item_pos, set_pos",
            arrayOf(workoutId.toString()),
        ).use { c ->
            generateSequence {
                if (c.moveToNext()) {
                    LiftSet(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        planItemId = c.getLong(c.getColumnIndexOrThrow("plan_item_id")),
                        exerciseId = c.getLong(c.getColumnIndexOrThrow("exercise_id")),
                        itemPos = c.getInt(c.getColumnIndexOrThrow("item_pos")),
                        setPos = c.getInt(c.getColumnIndexOrThrow("set_pos")),
                        plannedReps = c.getInt(c.getColumnIndexOrThrow("planned_reps")),
                        plannedKg = c.getDouble(c.getColumnIndexOrThrow("planned_kg")),
                        reps = c.getInt(c.getColumnIndexOrThrow("reps")),
                        kg = c.getDouble(c.getColumnIndexOrThrow("weight_kg")),
                        restSec = c.getInt(c.getColumnIndexOrThrow("rest_sec")),
                        done = c.getInt(c.getColumnIndexOrThrow("done")) == 1,
                        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
                    )
                } else {
                    null
                }
            }.toList()
        }

    fun saveLiftSet(s: LiftSet) {
        save("workout_sets", s.id, ContentValues().apply {
            put("reps", s.reps)
            put("weight_kg", s.kg)
            put("done", if (s.done) 1 else 0)
        })
    }

    fun discardLift(workoutId: Long) = deleteWorkout(workoutId)

    // A terv egy gyakorlatának minden (súlyos) sorozatát megemeli a következő alkalomra.
    fun raisePlanItem(planItemId: Long) {
        val json = readableDatabase.rawQuery(
            "SELECT sets FROM plan_items WHERE id = ? AND deleted = 0",
            arrayOf(planItemId.toString()),
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return
        val raised = PlannedSet.parse(json).map { if (it.kg > 0) it.copy(kg = it.kg + Lifting.increase(it.kg)) else it }
        save("plan_items", planItemId, ContentValues().apply { put("sets", PlannedSet.toJson(raised)) })
    }

    fun liftSummary(workoutId: Long): LiftSummary {
        val sets = liftSets(workoutId)
        val done = sets.filter { it.done }
        val names = sets.map { it.exerciseId }.distinct().mapNotNull { exercise(it)?.name }
        return LiftSummary(done.size, sets.size, done.sumOf { it.reps * it.kg }, names)
    }

    // Az utoljára elvégzett súlyzós nap betűje (ebből jön az ajánlott következő nap)
    fun lastLiftLetter(profileId: Long): String? =
        readableDatabase.rawQuery(
            "SELECT title FROM workouts WHERE profile_id = ? AND sport = 'weights' AND $DONE ORDER BY started_at DESC LIMIT 1",
            arrayOf(profileId.toString()),
        ).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    // ---- Közös segédfüggvények ----

    // Új sor: kap uuid-t; meglévő sor: frissül. Mindkét esetben beállítja az updated_at-et.
    private fun save(table: String, id: Long, v: ContentValues): Long {
        v.put("updated_at", System.currentTimeMillis())
        return if (id == 0L) {
            v.put("uuid", newUuid())
            writableDatabase.insertOrThrow(table, null, v)
        } else {
            writableDatabase.update(table, v, "id = ?", arrayOf(id.toString()))
            id
        }
    }

    private fun softDelete(table: String, id: Long) {
        writableDatabase.execSQL(
            "UPDATE $table SET deleted = 1, updated_at = ? WHERE id = ?",
            arrayOf(System.currentTimeMillis(), id),
        )
    }

    private inline fun SQLiteDatabase.transaction(block: () -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private fun Cursor.toProfile() = Profile(
        id = getLong(getColumnIndexOrThrow("id")),
        name = getString(getColumnIndexOrThrow("name")),
        birthYear = getInt(getColumnIndexOrThrow("birth_year")),
        weightKg = getDouble(getColumnIndexOrThrow("weight_kg")),
        sex = Sex.of(getString(getColumnIndexOrThrow("sex"))),
    )

    private fun Cursor.toWorkout() = Workout(
        id = getLong(getColumnIndexOrThrow("id")),
        profileId = getLong(getColumnIndexOrThrow("profile_id")),
        startedAt = getLong(getColumnIndexOrThrow("started_at")),
        movingMs = getLong(getColumnIndexOrThrow("moving_ms")),
        distanceKm = getDouble(getColumnIndexOrThrow("distance_km")),
        avgSpeed = getDouble(getColumnIndexOrThrow("avg_speed")),
        maxSpeed = getDouble(getColumnIndexOrThrow("max_speed")),
        kcal = getDouble(getColumnIndexOrThrow("kcal")),
        level = getInt(getColumnIndexOrThrow("level")),
        avgHr = getColumnIndexOrThrow("avg_hr").let { if (isNull(it)) null else getInt(it) },
        maxHr = getColumnIndexOrThrow("max_hr").let { if (isNull(it)) null else getInt(it) },
        mode = WorkoutMode.of(getString(getColumnIndexOrThrow("mode"))),
        goal = getColumnIndexOrThrow("goal").let { if (isNull(it)) null else getDouble(it) },
        programWeek = getColumnIndexOrThrow("program").let { if (isNull(it)) null else getInt(it) },
        completed = getInt(getColumnIndexOrThrow("completed")) == 1,
        sport = Sport.of(getString(getColumnIndexOrThrow("sport"))),
        title = getString(getColumnIndexOrThrow("title")),
        elevGain = getColumnIndexOrThrow("elev_gain").let { if (isNull(it)) null else getDouble(it) },
    )

    private fun Cursor.toEquipment() = Equipment(
        id = getLong(getColumnIndexOrThrow("id")),
        name = getString(getColumnIndexOrThrow("name")),
        image = getString(getColumnIndexOrThrow("image")),
        seedKey = getString(getColumnIndexOrThrow("seed_key")),
    )

    private fun Cursor.toExercise() = Exercise(
        id = getLong(getColumnIndexOrThrow("id")),
        name = getString(getColumnIndexOrThrow("name")),
        equipmentId = getColumnIndexOrThrow("equipment_id").let { if (isNull(it)) null else getLong(it) },
        image = getString(getColumnIndexOrThrow("image")),
        seedKey = getString(getColumnIndexOrThrow("seed_key")),
    )

    companion object {
        // A naplóban csak a lezárt, nem törölt edzések számítanak.
        private const val DONE = "deleted = 0 AND in_progress = 0"

        @Volatile private var instance: Db? = null

        fun get(ctx: Context): Db = instance ?: synchronized(this) {
            instance ?: Db(ctx).also { instance = it }
        }
    }
}

fun newUuid(): String = UUID.randomUUID().toString().replace("-", "")

// Az utolsó `days` nap kezdőidőpontjai, plusz a holnap kezdete a lista végén (days + 1 elem).
fun dayStarts(days: Int): LongArray {
    val c = startOfToday()
    c.add(Calendar.DAY_OF_MONTH, -(days - 1))
    return LongArray(days + 1) {
        val t = c.timeInMillis
        c.add(Calendar.DAY_OF_MONTH, 1)
        t
    }
}

fun startOfWeek(): Long {
    val c = startOfToday()
    while (c.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) c.add(Calendar.DAY_OF_MONTH, -1)
    return c.timeInMillis
}

private fun startOfToday(): Calendar = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}
