package hu.kettleredzo.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar

enum class Sex(val code: String) {
    MALE("M"),
    FEMALE("F");

    companion object {
        fun of(code: String) = entries.firstOrNull { it.code == code } ?: MALE
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
    val completed: Boolean = false, // elérte-e a célt / végigment-e a programon
) {
    val label: String get() = modeLabel(mode, goal, programWeek) + if (completed) " ✓" else ""
}

data class Totals(val count: Int, val movingMs: Long, val distanceKm: Double, val kcal: Double)

// Helyi adatbázis a tableten: profilok és edzésnapló.
class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "kettler.db", null, 2) {

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
        onUpgrade(db, 1, 2)
    }

    // 2. verzió: edzésmód, cél, program és teljesítés. A régi edzések „Szabad edzés”-ként maradnak meg.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE workouts ADD COLUMN mode TEXT NOT NULL DEFAULT 'free'")
            db.execSQL("ALTER TABLE workouts ADD COLUMN goal REAL")
            db.execSQL("ALTER TABLE workouts ADD COLUMN program INTEGER")
            db.execSQL("ALTER TABLE workouts ADD COLUMN completed INTEGER NOT NULL DEFAULT 0")
        }
    }

    // ---- Profilok ----

    fun profiles(): List<Profile> =
        readableDatabase.rawQuery("SELECT * FROM profiles ORDER BY name COLLATE NOCASE", null).use { c ->
            generateSequence { if (c.moveToNext()) c.toProfile() else null }.toList()
        }

    fun profile(id: Long): Profile? =
        readableDatabase.rawQuery("SELECT * FROM profiles WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.toProfile() else null
        }

    fun saveProfile(p: Profile): Long {
        val v = ContentValues().apply {
            put("name", p.name)
            put("birth_year", p.birthYear)
            put("weight_kg", p.weightKg)
            put("sex", p.sex.code)
        }
        return if (p.id == 0L) {
            writableDatabase.insertOrThrow("profiles", null, v)
        } else {
            writableDatabase.update("profiles", v, "id = ?", arrayOf(p.id.toString()))
            p.id
        }
    }

    fun deleteProfile(id: Long) {
        writableDatabase.delete("profiles", "id = ?", arrayOf(id.toString()))
    }

    // ---- Edzések ----

    fun addWorkout(w: Workout): Long {
        val v = ContentValues().apply {
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
        }
        return writableDatabase.insertOrThrow("workouts", null, v)
    }

    // Végigcsinált programos edzések száma (ebből jön az ajánlott hét)
    fun completedProgramCount(profileId: Long): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM workouts WHERE profile_id = ? AND mode = 'program' AND completed = 1",
            arrayOf(profileId.toString()),
        ).use { c ->
            c.moveToFirst()
            c.getInt(0)
        }

    fun workouts(profileId: Long, since: Long = 0, limit: Int = 500): List<Workout> =
        readableDatabase.rawQuery(
            "SELECT * FROM workouts WHERE profile_id = ? AND started_at >= ? ORDER BY started_at DESC LIMIT $limit",
            arrayOf(profileId.toString(), since.toString()),
        ).use { c ->
            generateSequence { if (c.moveToNext()) c.toWorkout() else null }.toList()
        }

    fun deleteWorkout(id: Long) {
        writableDatabase.delete("workouts", "id = ?", arrayOf(id.toString()))
    }

    fun totals(profileId: Long, since: Long = 0): Totals =
        readableDatabase.rawQuery(
            """SELECT COUNT(*), COALESCE(SUM(moving_ms), 0), COALESCE(SUM(distance_km), 0), COALESCE(SUM(kcal), 0)
               FROM workouts WHERE profile_id = ? AND started_at >= ?""",
            arrayOf(profileId.toString(), since.toString()),
        ).use { c ->
            c.moveToFirst()
            Totals(c.getInt(0), c.getLong(1), c.getDouble(2), c.getDouble(3))
        }

    // Az utolsó `days` nap megtett távja naponként (a legrégebbi elöl, a mai a végén).
    fun dailyKm(profileId: Long, days: Int): DoubleArray {
        val starts = dayStarts(days)
        val out = DoubleArray(days)
        for (w in workouts(profileId, since = starts[0], limit = 10_000)) {
            val i = (0 until days).firstOrNull { w.startedAt >= starts[it] && w.startedAt < starts[it + 1] } ?: continue
            out[i] += w.distanceKm
        }
        return out
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
    )

    companion object {
        @Volatile private var instance: Db? = null

        fun get(ctx: Context): Db = instance ?: synchronized(this) {
            instance ?: Db(ctx).also { instance = it }
        }
    }
}

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
