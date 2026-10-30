package hu.kettleredzo.app

// Profilonkénti napi vagy heti célok. Sportonként a hozzá illő mértékek közül, vagy az összes
// sport kalóriája együtt. A haladást a Db.goalValue számolja (a hét hétfőn kezdődik).

enum class GoalSport(val code: String, val title: String, val sport: Sport?, val metrics: List<GoalMetric>) {
    ELLIPTICAL("elliptical", "Elliptikus", Sport.ELLIPTICAL, listOf(GoalMetric.KM, GoalMetric.MIN, GoalMetric.KCAL, GoalMetric.COUNT)),
    BIKE("bike", "Kerékpár", Sport.BIKE, listOf(GoalMetric.KM, GoalMetric.MIN, GoalMetric.KCAL, GoalMetric.COUNT)),
    WEIGHTS("weights", "Súlyzós", Sport.WEIGHTS, listOf(GoalMetric.MIN, GoalMetric.COUNT, GoalMetric.SETS)),
    ALL("all", "Összesen", null, listOf(GoalMetric.KCAL));

    companion object {
        fun of(code: String?) = entries.firstOrNull { it.code == code } ?: ALL
    }
}

enum class GoalMetric(val code: String, val unit: String, val title: String, val decimals: Int) {
    KM("km", "km", "Táv", 1),
    MIN("min", "perc", "Edzésidő", 0),
    KCAL("kcal", "kcal", "Kalória", 0),
    COUNT("count", "alkalom", "Alkalmak", 0),
    SETS("sets", "sorozat", "Sorozatok", 0);

    companion object {
        fun of(code: String?) = entries.firstOrNull { it.code == code } ?: KCAL
    }
}

enum class GoalPeriod(val code: String, val title: String) {
    DAY("day", "Napi"),
    WEEK("week", "Heti");

    companion object {
        fun of(code: String?) = entries.firstOrNull { it.code == code } ?: WEEK
    }
}

data class Goal(
    val id: Long,
    val profileId: Long,
    val sport: GoalSport,
    val metric: GoalMetric,
    val period: GoalPeriod,
    val target: Double,
) {
    // pl. "Heti elliptikus táv: 20 km"
    val title get() = "${period.title} ${sport.title.lowercase()} ${metric.title.lowercase()}"

    // pl. "Heti 2 / 3 alkalom"
    fun progressText(value: Double) =
        "${period.title} ${sportPrefix()}${formatNum(value, metric.decimals)} / ${goalText(target)} ${metric.unit}"

    private fun sportPrefix() = if (sport == GoalSport.ALL) "" else "${sport.title.lowercase()} "
}
