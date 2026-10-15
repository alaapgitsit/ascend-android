package com.ascend.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
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

/** Reads steps, sleep (deep + total), HRV and weight from Health Connect and
 *  returns a JSON array of daily rows the Ascend web app can import. */
object HealthReader {

    val PERMISSIONS: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class)
    )

    fun isAvailable(context: Context): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private fun dayKey(instant: Instant, zone: ZoneId): String {
        val d = instant.atZone(zone).toLocalDate()
        return "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0

    /** Pulls the last [days] days and returns a JSON array string. */
    suspend fun readJson(context: Context, days: Long = 10): String {
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

        // Steps — aggregate per day (Health Connect dedupes across sources by the
        // priority you set in Health Connect, e.g. RingConn above the phone).
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
        // Fallback: raw sum if aggregation produced nothing
        if (steps.isEmpty()) {
            try {
                val res = client.readRecords(ReadRecordsRequest(StepsRecord::class, timeRangeFilter = range))
                for (r in res.records) {
                    val k = dayKey(r.startTime, zone)
                    steps[k] = (steps[k] ?: 0L) + r.count
                }
            } catch (_: Exception) {}
        }

        // Sleep. Total = time actually ASLEEP (sum of sleep stages, excluding awake/out-of-bed);
        // Deep = deep-stage time. Assign to the local date the session ENDS on.
        val awakeStages = setOf(
            SleepSessionRecord.STAGE_TYPE_AWAKE,
            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED
        )
        try {
            val res = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, timeRangeFilter = range))
            for (r in res.records) {
                val k = dayKey(r.endTime, zone)
                var asleep = 0.0
                var deep = 0.0
                if (r.stages.isNotEmpty()) {
                    for (st in r.stages) {
                        val h = (st.endTime.toEpochMilli() - st.startTime.toEpochMilli()) / 3600000.0
                        if (st.stage !in awakeStages) asleep += h
                        if (st.stage == SleepSessionRecord.STAGE_TYPE_DEEP) deep += h
                    }
                } else {
                    asleep = (r.endTime.toEpochMilli() - r.startTime.toEpochMilli()) / 3600000.0
                }
                if (asleep > 0) sleepHrs[k] = (sleepHrs[k] ?: 0.0) + asleep
                if (deep > 0) deepHrs[k] = (deepHrs[k] ?: 0.0) + deep
            }
        } catch (_: Exception) {}

        // HRV (rmssd) — average per day
        try {
            val res = client.readRecords(ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, timeRangeFilter = range))
            for (r in res.records) {
                val k = dayKey(r.time, zone)
                hrvSum[k] = (hrvSum[k] ?: 0.0) + r.heartRateVariabilityMillis
                hrvCount[k] = (hrvCount[k] ?: 0) + 1
            }
        } catch (_: Exception) {}

        // Weight — latest per day
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
}
