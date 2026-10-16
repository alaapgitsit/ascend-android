package com.ascend.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Reads steps, sleep (deep + total), HRV, weight and exercise sessions from
 *  Health Connect and returns JSON the Ascend web app can import. */
object HealthReader {

    val PERMISSIONS: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class)
    )

    fun isAvailable(context: Context): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private fun dayKey(instant: Instant, zone: ZoneId): String {
        val d = instant.atZone(zone).toLocalDate()
        return "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0

    /** Daily body rows (steps, sleep, deep, HRV, weight). */
    suspend fun readJson(context: Context, days: Long = 14): String {
        val client = HealthConnectClient.getOrCreate(context)
        val zone = ZoneId.systemDefault()
        val end = Instant.now()
        val start = end.minus(days, ChronoUnit.DAYS)
        val range = TimeRangeFilter.between(start, end)

        val steps = HashMap<String, Long>()
        val deepHrs = HashMap<String, Double>()
        val sleepHrs = HashMap<String, Double>()
        val hrvSum = HashMap<String, Double>()
        val hrvCount = HashMap<String, Int>()
        val weightLatest = HashMap<String, Pair<Instant, Double>>()

        // Steps — aggregate per day (Health Connect dedupes across sources by priority).
        try {
            val startL = LocalDateTime.ofInstant(start, zone)
            val endL = LocalDateTime.ofInstant(end, zone)
            val agg = client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(startL, endL),
                    timeRangeSlicer = Period.ofDays(1)
                )
            )
            for (g in agg) {
                val c = g.result[StepsRecord.COUNT_TOTAL] ?: continue
                val d = g.startTime.toLocalDate()
                steps["%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)] = c
            }
        } catch (_: Exception) {}
        if (steps.isEmpty()) {
            try {
                val res = client.readRecords(ReadRecordsRequest(StepsRecord::class, timeRangeFilter = range))
                for (r in res.records) {
                    val k = dayKey(r.startTime, zone)
                    steps[k] = (steps[k] ?: 0L) + r.count
                }
            } catch (_: Exception) {}
        }

        // Sleep. Total = time ACTUALLY ASLEEP = sum of LIGHT + DEEP + REM stages only
        // (never time in bed). Deep = DEEP stage time. We pick, per day, the staged
        // session with the most real sleep; sessions with no stage data are ignored
        // for the total so an in-bed block can't masquerade as sleep.
        val realSleepStages = setOf(
            SleepSessionRecord.STAGE_TYPE_LIGHT,
            SleepSessionRecord.STAGE_TYPE_DEEP,
            SleepSessionRecord.STAGE_TYPE_REM,
            SleepSessionRecord.STAGE_TYPE_SLEEPING
        )
        try {
            val res = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, timeRangeFilter = range))
            val byDay = HashMap<String, MutableList<SleepSessionRecord>>()
            for (r in res.records) {
                byDay.getOrPut(dayKey(r.endTime, zone)) { mutableListOf() }.add(r)
            }
            for ((k, list) in byDay) {
                var bestAsleep = 0.0
                var bestDeep = 0.0
                var haveStaged = false
                for (s in list) {
                    if (s.stages.isEmpty()) continue
                    var asleep = 0.0
                    var deep = 0.0
                    for (st in s.stages) {
                        val h = (st.endTime.toEpochMilli() - st.startTime.toEpochMilli()) / 3600000.0
                        if (st.stage in realSleepStages) asleep += h
                        if (st.stage == SleepSessionRecord.STAGE_TYPE_DEEP) deep += h
                    }
                    if (asleep > bestAsleep) { bestAsleep = asleep; bestDeep = deep; haveStaged = true }
                }
                if (!haveStaged) {
                    // No staged session this day — fall back to the longest plain
                    // session's duration so the user at least sees something, but
                    // only when nothing better exists.
                    val plain = list.maxByOrNull { it.endTime.toEpochMilli() - it.startTime.toEpochMilli() }
                    if (plain != null) bestAsleep = (plain.endTime.toEpochMilli() - plain.startTime.toEpochMilli()) / 3600000.0
                }
                if (bestAsleep in 0.1..16.0) sleepHrs[k] = bestAsleep
                if (bestDeep > 0) deepHrs[k] = bestDeep
            }
        } catch (_: Exception) {}

        // HRV (rmssd) — average per day across all readings.
        try {
            val res = client.readRecords(ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, timeRangeFilter = range))
            for (r in res.records) {
                val k = dayKey(r.time, zone)
                hrvSum[k] = (hrvSum[k] ?: 0.0) + r.heartRateVariabilityMillis
                hrvCount[k] = (hrvCount[k] ?: 0) + 1
            }
        } catch (_: Exception) {}

        // Weight — latest per day.
        try {
            val res = client.readRecords(ReadRecordsRequest(WeightRecord::class, timeRangeFilter = range))
            for (r in res.records) {
                val k = dayKey(r.time, zone)
                val prev = weightLatest[k]
                if (prev == null || r.time.isAfter(prev.first)) {
                    weightLatest[k] = Pair(r.time, r.weight.inKilograms)
                }
            }
        } catch (_: Exception) {}

        val keys = HashSet<String>()
        keys.addAll(steps.keys); keys.addAll(deepHrs.keys); keys.addAll(sleepHrs.keys)
        keys.addAll(hrvSum.keys); keys.addAll(weightLatest.keys)

        val arr = JSONArray()
        for (k in keys.sorted()) {
            val o = JSONObject()
            o.put("date", k)
            steps[k]?.let { o.put("steps", it) }
            deepHrs[k]?.let { o.put("deep", round1(it)) }
            sleepHrs[k]?.let { o.put("sleep", round1(it)) }
            if (hrvCount[k] != null && hrvCount[k]!! > 0) {
                o.put("hrv", round1(hrvSum[k]!! / hrvCount[k]!!))
            }
            weightLatest[k]?.let { o.put("weight", round1(it.second)) }
            arr.put(o)
        }
        return arr.toString()
    }

    private fun mapType(t: Int): String = when (t) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "run"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "bike"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "strength"
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "hiit"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "walk"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "swim"
        else -> "other"
    }

    /** Exercise sessions (runs, rides, strength, HIIT…) with calories, HR and
     *  distance resolved from records overlapping each session window. */
    suspend fun readWorkoutsJson(context: Context, days: Long = 21): String {
        val client = HealthConnectClient.getOrCreate(context)
        val end = Instant.now()
        val start = end.minus(days, ChronoUnit.DAYS)
        val range = TimeRangeFilter.between(start, end)
        val arr = JSONArray()

        val sessions = try {
            client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, timeRangeFilter = range)).records
        } catch (_: Exception) { emptyList() }
        if (sessions.isEmpty()) return "[]"

        // Pull the supporting records once over the whole window, then slice per session.
        val totalCals = try {
            client.readRecords(ReadRecordsRequest(TotalCaloriesBurnedRecord::class, timeRangeFilter = range)).records
        } catch (_: Exception) { emptyList() }
        val activeCals = try {
            client.readRecords(ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, timeRangeFilter = range)).records
        } catch (_: Exception) { emptyList() }
        val hrRecords = try {
            client.readRecords(ReadRecordsRequest(HeartRateRecord::class, timeRangeFilter = range)).records
        } catch (_: Exception) { emptyList() }
        val distRecords = try {
            client.readRecords(ReadRecordsRequest(DistanceRecord::class, timeRangeFilter = range)).records
        } catch (_: Exception) { emptyList() }

        for (s in sessions) {
            val sStart = s.startTime.toEpochMilli()
            val sEnd = s.endTime.toEpochMilli()
            fun overlaps(a: Instant, b: Instant) =
                a.toEpochMilli() < sEnd && b.toEpochMilli() > sStart

            var kcal = 0.0
            for (r in totalCals) if (overlaps(r.startTime, r.endTime)) kcal += r.energy.inKilocalories
            if (kcal <= 0) for (r in activeCals) if (overlaps(r.startTime, r.endTime)) kcal += r.energy.inKilocalories

            var hrSum = 0.0; var hrN = 0; var hrMax = 0L
            for (r in hrRecords) {
                for (sm in r.samples) {
                    val t = sm.time.toEpochMilli()
                    if (t in sStart..sEnd) { hrSum += sm.beatsPerMinute; hrN++; if (sm.beatsPerMinute > hrMax) hrMax = sm.beatsPerMinute }
                }
            }

            var meters = 0.0
            for (r in distRecords) if (overlaps(r.startTime, r.endTime)) meters += r.length.inMeters

            val o = JSONObject()
            o.put("start", sStart)
            o.put("end", sEnd)
            o.put("type", mapType(s.exerciseType))
            if (kcal > 0) o.put("kcal", Math.round(kcal))
            if (hrN > 0) { o.put("avgHr", Math.round(hrSum / hrN)); o.put("maxHr", hrMax) }
            if (meters > 50) o.put("distanceKm", round1(meters / 1000.0))
            val title = s.title ?: s.notes
            if (!title.isNullOrBlank()) o.put("title", title)
            arr.put(o)
        }
        return arr.toString()
    }
}
