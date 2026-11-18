package com.example.safar20.data.local

import com.example.safar20.data.network.BinaryPacketCodec.ParsedTripPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shared trip distance, speed, sync cursor bounds, and ingest sanity math (Phase A: A1, A2, A4, A6).
 * Filters standstill GPS jitter (< 3 m or both points speed == 0) and GPS teleports (> 250 km/h).
 */
object TripMath {

    private const val EARTH_RADIUS_M = 6371000.0
    const val MIN_MOVING_SPEED_KMH = 1.5f
    private const val MIN_SEGMENT_M = 3.0
    private const val MAX_IMPLIED_SPEED_KMH = 250.0

    // Phase A1 / A2 constants: minValid = 1760000000 (2025-10-09 UTC), future tolerance = +86400s (1 day)
    const val MIN_VALID_TIMESTAMP_SEC = 1760000000L
    const val FUTURE_DRIFT_ALLOWANCE_SEC = 86400L

    /**
     * A1: Computes the valid [minValid, maxValid] timestamp bounds for a given current epoch (seconds).
     */
    fun validTimestampBounds(nowSec: Long): Pair<Long, Long> {
        return MIN_VALID_TIMESTAMP_SEC to (nowSec + FUTURE_DRIFT_ALLOWANCE_SEC)
    }

    /**
     * A1 / A6(1): Pure sync cursor selector used by tests and repository cursor validation.
     * Ignores DEMO_ trips, timestamps < 1760000000, and timestamps > nowSec + 86400.
     */
    fun computeValidSyncCursor(
        candidates: Iterable<Pair<String, Long>>,
        nowSec: Long
    ): Long {
        val (minValid, maxValid) = validTimestampBounds(nowSec)
        return candidates
            .asSequence()
            .filter { (tripId, ts) ->
                !tripId.startsWith("DEMO_") && ts in minValid..maxValid
            }
            .maxOfOrNull { it.second } ?: 0L
    }

    /**
     * A2 / A6(2): Validates whether a received history point is sane for storage.
     * Drops points with timestamp outside [1760000000, nowSec + 86400],
     * latitude outside [-90, 90], longitude outside [-180, 180], or (lat == 0.0 && lon == 0.0).
     */
    fun isValidHistoryPoint(point: ParsedTripPoint, nowSec: Long): Boolean {
        val (minValid, maxValid) = validTimestampBounds(nowSec)
        if (point.timestamp !in minValid..maxValid) return false
        if (point.latitude.isNaN() || point.latitude < -90.0 || point.latitude > 90.0) return false
        if (point.longitude.isNaN() || point.longitude < -180.0 || point.longitude > 180.0) return false
        if (point.latitude == 0.0 && point.longitude == 0.0) return false
        return true
    }

    /**
     * A2 / A6(2): Filters a list of incoming history points, returning (validPoints, droppedCount).
     */
    fun filterValidHistoryPoints(
        points: List<ParsedTripPoint>,
        nowSec: Long
    ): Pair<List<ParsedTripPoint>, Int> {
        val valid = ArrayList<ParsedTripPoint>(points.size)
        var dropped = 0
        for (pt in points) {
            if (isValidHistoryPoint(pt, nowSec)) {
                valid.add(pt)
            } else {
                dropped++
            }
        }
        return valid to dropped
    }

    /**
     * A4 / A6(3): Incremental trip distance calculation.
     * Adds the distance of (listOf(lastStoredPoint) + newPoints) to previousDistanceKm.
     */
    fun incrementalDistanceKm(
        previousDistanceKm: Double,
        lastStoredPoint: ParsedTripPoint?,
        newPoints: List<ParsedTripPoint>
    ): Double {
        if (newPoints.isEmpty()) return previousDistanceKm
        val chain = if (lastStoredPoint != null) {
            listOf(lastStoredPoint) + newPoints
        } else {
            newPoints
        }
        return previousDistanceKm + distanceKm(chain)
    }

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    /**
     * Returns valid segment distance in meters between consecutive points, or 0.0 if ignored.
     * Ignored when:
     * - Both points have speed <= 0
     * - Segment is < 3 meters (standstill jitter)
     * - Implied speed > 250 km/h (GPS jump)
     */
    fun segmentMeters(prev: ParsedTripPoint, curr: ParsedTripPoint): Double {
        if (prev.speedKmh <= 0f && curr.speedKmh <= 0f) return 0.0
        val distM = haversineMeters(prev.latitude, prev.longitude, curr.latitude, curr.longitude)
        if (distM < MIN_SEGMENT_M) return 0.0

        val dtSec = curr.timestamp - prev.timestamp
        if (dtSec > 0L) {
            val impliedKmh = (distM / dtSec.toDouble()) * 3.6
            if (impliedKmh > MAX_IMPLIED_SPEED_KMH) return 0.0
        } else if (prev.timestamp > 0L && curr.timestamp > 0L) {
            // Non-increasing timestamp with a jump >= 70 m in <= 1s
            if (distM * 3.6 > MAX_IMPLIED_SPEED_KMH) return 0.0
        }
        return distM
    }

    fun distanceKm(points: List<ParsedTripPoint>): Double {
        if (points.size < 2) return 0.0
        var totalMeters = 0.0
        for (i in 1 until points.size) {
            totalMeters += segmentMeters(points[i - 1], points[i])
        }
        return totalMeters / 1000.0
    }

    fun movingAvgSpeedKmh(points: List<ParsedTripPoint>): Double {
        val moving = points.filter { it.speedKmh > MIN_MOVING_SPEED_KMH }
        if (moving.isEmpty()) return 0.0
        return moving.sumOf { it.speedKmh.toDouble() } / moving.size
    }
}
