package com.example.safar20

import com.example.safar20.data.local.PreferencesDataStore
import com.example.safar20.data.local.RideExportAndNav
import com.example.safar20.data.local.entity.LocationPointEntity
import com.example.safar20.data.network.BinaryPacketCodec
import com.example.safar20.data.network.CryptoEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase3RiderFeaturesTest {

    private val testKeyHex = "2c9f96465dbcce31fc05cc89c1d37bf13677dd0e596344b50d2a70fd46def6f5"

    @Test
    fun setEmergencyPacket_roundTripAndAead() {
        val plain = BinaryPacketCodec.encodeSetEmergency(
            slot = 1,
            number = "+919876543210",
            sequenceNumber = 42
        )
        assertEquals(BinaryPacketCodec.SET_EMERGENCY_SIZE, plain.size)

        val decodedPlain = BinaryPacketCodec.decodeSetEmergency(plain)
        assertNotNull(decodedPlain)
        assertTrue(decodedPlain!!.isValidCrc)
        assertEquals(1, decodedPlain.slot)
        assertEquals("+919876543210", decodedPlain.number)
        assertEquals(42, decodedPlain.sequenceNumber)

        // Wrap in ChaCha20-Poly1305 AEAD envelope (23B plain -> 49B encrypted frame) and unwrap
        val encrypted = CryptoEngine.encryptFrame(plain, testKeyHex)
        assertNotNull(encrypted)
        assertEquals(49, encrypted!!.size)
        val decrypted = CryptoEngine.decryptFrame(encrypted, testKeyHex)
        assertNotNull(decrypted)
        val decodedAead = BinaryPacketCodec.decodeSetEmergency(decrypted!!)
        assertNotNull(decodedAead)
        assertTrue(decodedAead!!.isValidCrc)
        assertEquals(1, decodedAead.slot)
        assertEquals("+919876543210", decodedAead.number)
        assertEquals(42, decodedAead.sequenceNumber)
    }

    @Test
    fun sosEventPacket_roundTripAndAead() {
        val pt = BinaryPacketCodec.ParsedTripPoint(
            timestamp = 1715001234L,
            latitude = 21.1458000,
            longitude = 79.0882000,
            speedKmh = 64.5f,
            heading = 180,
            isIgnitionOn = true,
            isTripStart = false,
            isTripEnd = false,
            satCount = 9
        )
        val plain = BinaryPacketCodec.encodeSosEvent(
            point = pt,
            reason = BinaryPacketCodec.SOS_REASON_CRASH,
            state = BinaryPacketCodec.SOS_STATE_FIRED,
            sequenceNumber = 105
        )
        assertEquals(BinaryPacketCodec.SOS_EVENT_SIZE, plain.size)

        val encrypted = CryptoEngine.encryptFrame(plain, testKeyHex)
        assertNotNull(encrypted)
        assertEquals(49, encrypted!!.size)
        val decrypted = CryptoEngine.decryptFrame(encrypted, testKeyHex)
        assertNotNull(decrypted)

        val decoded = BinaryPacketCodec.decodeSosEvent(decrypted!!)
        assertNotNull(decoded)
        assertTrue(decoded!!.isValidCrc)
        assertEquals(BinaryPacketCodec.SOS_REASON_CRASH, decoded.reason)
        assertEquals(BinaryPacketCodec.SOS_STATE_FIRED, decoded.state)
        assertEquals(105, decoded.sequenceNumber)
        assertEquals(1715001234L, decoded.tripPoint.timestamp)
        assertEquals(21.1458, decoded.tripPoint.latitude, 0.0001)
        assertEquals(79.0882, decoded.tripPoint.longitude, 0.0001)
        assertEquals(64.5f, decoded.tripPoint.speedKmh, 0.1f)
    }

    @Test
    fun gpx11Builder_containsValidTrackpointsAndSpeedInMetersPerSecond() {
        val points = listOf(
            LocationPointEntity(
                tripId = "TRIP_1",
                deviceId = "SAFAR_01",
                timestamp = 1704067200L, // 2024-01-01T00:00:00Z
                latitude = 21.1458000,
                longitude = 79.0882000,
                speedKmh = 36.0f, // 10.00 m/s
                heading = 90
            ),
            LocationPointEntity(
                tripId = "TRIP_1",
                deviceId = "SAFAR_01",
                timestamp = 1704067260L,
                latitude = 21.1500000,
                longitude = 79.0900000,
                speedKmh = 72.0f, // 20.00 m/s
                heading = 95
            )
        )
        val gpx = RideExportAndNav.buildGpx11Xml("Morning Ride & Tea", points)
        assertTrue(gpx.contains("version=\"1.1\""))
        assertTrue(gpx.contains("<name>Morning Ride &amp; Tea</name>"))
        assertTrue(gpx.contains("<trkpt lat=\"21.1458000\" lon=\"79.0882000\">"))
        assertTrue(gpx.contains("<time>2024-01-01T00:00:00Z</time>"))
        assertTrue(gpx.contains("<speed>10.00</speed>"))
        assertTrue(gpx.contains("<speed>20.00</speed>"))
    }

    @Test
    fun movingDurationMinutes_countsOnlyMovingIntervals() {
        val baseTs = 1704067200L
        val pts = listOf(
            LocationPointEntity(tripId = "T1", deviceId = "D1", timestamp = baseTs, latitude = 21.0, longitude = 79.0, speedKmh = 40f, heading = 0),
            LocationPointEntity(tripId = "T1", deviceId = "D1", timestamp = baseTs + 60, latitude = 21.01, longitude = 79.01, speedKmh = 45f, heading = 0),
            // Stopped for 120s
            LocationPointEntity(tripId = "T1", deviceId = "D1", timestamp = baseTs + 180, latitude = 21.01, longitude = 79.01, speedKmh = 0f, heading = 0),
            // Moving again for 60s
            LocationPointEntity(tripId = "T1", deviceId = "D1", timestamp = baseTs + 240, latitude = 21.02, longitude = 79.02, speedKmh = 50f, heading = 0)
        )
        val movingMin = RideExportAndNav.movingDurationMinutes(pts)
        assertEquals(3, movingMin)
    }

    @Test
    fun emergencyContactsValidationAndTripNamesParsing() {
        assertTrue(PreferencesDataStore.isValidEmergencyNumber(""))
        assertTrue(PreferencesDataStore.isValidEmergencyNumber("+919876543210"))
        assertTrue(PreferencesDataStore.isValidEmergencyNumber("9876543210"))
        assertFalse(PreferencesDataStore.isValidEmergencyNumber("123"))
        assertFalse(PreferencesDataStore.isValidEmergencyNumber("+91-98765-43210"))

        val map = mapOf("TRIP_1" to "Pench Highway Run", "TRIP_2" to "Night City Loop")
        val raw = PreferencesDataStore.serializeTripNames(map)
        val names = PreferencesDataStore.parseTripNames(raw)
        assertEquals("Pench Highway Run", names["TRIP_1"])
        assertEquals("Night City Loop", names["TRIP_2"])
    }
}
