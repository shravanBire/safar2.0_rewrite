package com.example.safar20.data.local

import com.example.safar20.data.network.BinaryPacketCodec.ParsedTripPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shared trip distance and speed math (P1-A5, P1-A6).
 * Filters standstill GPS jitter (< 3 m or both points speed == 0) and GPS teleports (> 250 km/h).
 */
object TripMath {

    private const val EARTH_RADIUS_M = 6371000.0
    const val MIN_MOVING_SPEED_KMH = 1.5f
    private const val MIN_SEGMENT_M = 3.0
    private const val MAX_IMPLIED_SPEED_KMH = 250.0

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
