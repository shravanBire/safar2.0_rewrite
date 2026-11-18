package com.example.safar20

import com.example.safar20.data.local.TripMath
import com.example.safar20.data.network.BinaryPacketCodec.ParsedTripPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhaseADataSafetyTest {

    private val testNowSec = 1765000000L // > 1760000000 (2025-10-09 UTC)

    private fun makePoint(
        ts: Long = testNowSec - 600,
        lat: Double = 21.1458,
        lon: Double = 79.0882,
        speedKmh: Float = 45.0f
    ) = ParsedTripPoint(
        timestamp = ts,
        latitude = lat,
        longitude = lon,
        speedKmh = speedKmh,
        heading = 90,
        isIgnitionOn = true,
        isTripStart = false,
        isTripEnd = false,
        satCount = 8
    )

    @Test
    fun a1_syncCursor_ignoresDemoFutureAndPre2025OctTimestamps() {
        val (minValid, maxValid) = TripMath.validTimestampBounds(testNowSec)
        assertEquals(1760000000L, minValid)
        assertEquals(testNowSec + 86400L, maxValid)

        val candidates = listOf(
            "DEMO_SAFAR_1" to (testNowSec - 10),         // DEMO_ must be ignored even though recent
            "TRIP_SAFAR_OLD" to 1759999999L,             // pre-2025-10 (< 1760000000) must be ignored
            "TRIP_SAFAR_FUTURE" to (testNowSec + 86401L),// future (> now + 1 day) must be ignored
            "TRIP_SAFAR_VALID1" to (testNowSec - 3600L), // valid
            "TRIP_SAFAR_VALID2" to (testNowSec - 120L)   // highest valid non-demo timestamp
        )

        val cursor = TripMath.computeValidSyncCursor(candidates, testNowSec)
        assertEquals(testNowSec - 120L, cursor)

        // When only DEMO or invalid points exist, cursor returns 0L
        val onlyInvalid = listOf(
            "DEMO_SAFAR_1" to testNowSec,
            "TRIP_SAFAR_OLD" to 1700000000L,
            "TRIP_SAFAR_FUTURE" to (testNowSec + 200000L)
        )
        assertEquals(0L, TripMath.computeValidSyncCursor(onlyInvalid, testNowSec))
    }

    @Test
    fun a2_invalidPointFilter_rejectsBadTimestampsCoordsAndNullIsland() {
        val validPt = makePoint()
        assertTrue(TripMath.isValidHistoryPoint(validPt, testNowSec))

        // 1. Pre-2025-10 timestamp
        val pre2025Pt = makePoint(ts = 1759999999L)
        assertFalse(TripMath.isValidHistoryPoint(pre2025Pt, testNowSec))

        // 2. Future (> now + 86400) timestamp
        val futurePt = makePoint(ts = testNowSec + 86401L)
        assertFalse(TripMath.isValidHistoryPoint(futurePt, testNowSec))

        // 3. Latitude out of [-90, 90]
        assertFalse(TripMath.isValidHistoryPoint(makePoint(lat = -90.001), testNowSec))
        assertFalse(TripMath.isValidHistoryPoint(makePoint(lat = 90.001), testNowSec))

        // 4. Longitude out of [-180, 180]
        assertFalse(TripMath.isValidHistoryPoint(makePoint(lon = -180.001), testNowSec))
        assertFalse(TripMath.isValidHistoryPoint(makePoint(lon = 180.001), testNowSec))

        // 5. Null Island (lat == 0.0 && lon == 0.0)
        assertFalse(TripMath.isValidHistoryPoint(makePoint(lat = 0.0, lon = 0.0), testNowSec))

        // Equator or Prime Meridian alone is valid if the other coordinate is non-zero
        assertTrue(TripMath.isValidHistoryPoint(makePoint(lat = 0.0, lon = 79.0882), testNowSec))
        assertTrue(TripMath.isValidHistoryPoint(makePoint(lat = 21.1458, lon = 0.0), testNowSec))

        // Batch filter check
        val batch = listOf(
            validPt,
            pre2025Pt,
            futurePt,
            makePoint(lat = 0.0, lon = 0.0),
            makePoint(lat = 95.0, lon = 79.0)
        )
        val (kept, droppedCount) = TripMath.filterValidHistoryPoints(batch, testNowSec)
        assertEquals(1, kept.size)
        assertEquals(4, droppedCount)
    }

    @Test
    fun a4_incrementalDistanceAcrossThreeChunks_matchesFullTripDistanceWithinOneMeter() {
        val allPoints = (0 until 15).map { idx ->
            makePoint(
                ts = testNowSec - 3000L + (idx * 10L),
                lat = 21.1458 + (idx * 0.0008),
                lon = 79.0882 + (idx * 0.0009),
                speedKmh = 35f + (idx % 5) * 4f
            )
        }

        val chunk1 = allPoints.subList(0, 5)
        val chunk2 = allPoints.subList(5, 10)
        val chunk3 = allPoints.subList(10, 15)

        val expectedFullDistanceKm = TripMath.distanceKm(allPoints)
        assertTrue(expectedFullDistanceKm > 0.5)

        // Chunk 1: brand-new trip
        var incrementalDistKm = TripMath.incrementalDistanceKm(
            previousDistanceKm = 0.0,
            lastStoredPoint = null,
            newPoints = chunk1
        )
        var lastStored = chunk1.last()

        // Chunk 2: appended to existing trip using lastStoredPoint of chunk 1
        incrementalDistKm = TripMath.incrementalDistanceKm(
            previousDistanceKm = incrementalDistKm,
            lastStoredPoint = lastStored,
            newPoints = chunk2
        )
        lastStored = chunk2.last()

        // Chunk 3: appended to existing trip using lastStoredPoint of chunk 2
        incrementalDistKm = TripMath.incrementalDistanceKm(
            previousDistanceKm = incrementalDistKm,
            lastStoredPoint = lastStored,
            newPoints = chunk3
        )

        // Within 1 meter (0.001 km)
        assertEquals(expectedFullDistanceKm, incrementalDistKm, 0.001)
    }
}
