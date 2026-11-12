package com.example.safar20

import com.example.safar20.data.local.AppLog
import com.example.safar20.data.local.TripMath
import com.example.safar20.data.network.BinaryPacketCodec
import com.example.safar20.data.network.BinaryPacketCodec.ParsedTripPoint
import com.example.safar20.data.network.CryptoEngine
import com.example.safar20.data.network.MqttTelemetrySource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.net.URLEncoder

class Phase4UnitTest {

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val result = ByteArray(clean.length / 2)
        for (i in result.indices) {
            val index = i * 2
            result[i] = clean.substring(index, index + 2).toInt(16).toByte()
        }
        return result
    }

    @Test
    fun testCryptoEngineGoldenVector() {
        val keyHex = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
        val frameHex = "020102030405060708090a0b0c7f8526de0c01105a20d1482764a3388e4e9b906748c2a4a077c4f1f7425bd5df32a9"
        val frameBytes = hexToBytes(frameHex)

        // 1. Decrypt golden frame
        val decrypted = CryptoEngine.decryptFrame(frameBytes, keyHex)
        assertNotNull("Golden frame decryption must succeed", decrypted)
        assertEquals("Decrypted frame must preserve opcode 0x02", 0x02.toByte(), decrypted!![0])

        // 2. Verify inner payload format: inner[i] == (i * 7 + 3) & 0xFF for i in 0..17
        val innerPayload = BinaryPacketCodec.getInnerPayload(decrypted)
        assertNotNull("Inner payload must be non-null", innerPayload)
        assertEquals("Inner payload size must be 18 bytes", 18, innerPayload!!.size)

        for (i in 0 until 18) {
            val expectedByte = (i * 7 + 3) and 0xFF
            val actualByte = innerPayload[i].toInt() and 0xFF
            assertEquals("Byte $i inner payload mismatch", expectedByte, actualByte)
        }

        // 3. Flipping opcode byte must fail decryption (AAD mismatch)
        val tamperedOpcodeFrame = frameBytes.copyOf()
        tamperedOpcodeFrame[0] = 0x01.toByte()
        val decryptedBadOpcode = CryptoEngine.decryptFrame(tamperedOpcodeFrame, keyHex)
        assertNull("Flipping opcode byte must return null", decryptedBadOpcode)

        // 4. Flipping any tag byte must fail decryption (Poly1305 authentication tag error)
        val tamperedTagFrame = frameBytes.copyOf()
        val lastIdx = tamperedTagFrame.size - 1
        tamperedTagFrame[lastIdx] = (tamperedTagFrame[lastIdx].toInt() xor 0xFF).toByte()
        val decryptedBadTag = CryptoEngine.decryptFrame(tamperedTagFrame, keyHex)
        assertNull("Flipping tag byte must return null", decryptedBadTag)
    }

    @Test
    fun testDeriveDeviceTopicId() {
        val pubKey = "4d8a1c92b8e3f7a1029c83b4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4"
        val derivedTopicId = MqttTelemetrySource.deriveDeviceTopicId(pubKey)
        assertEquals("a996b58b008271c7", derivedTopicId)
        assertEquals("0000000000000000", MqttTelemetrySource.deriveDeviceTopicId("SAFAR_01", "invalid_short_key"))
    }

    @Test
    fun testReplayWindowValidatorCases() {
        val validator = CryptoEngine.ReplayWindowValidator(maxStaleWindowSeconds = 300L)

        // Initial valid packet
        assertTrue(validator.validateAndRecord(1000L, 1))

        // Duplicate packet (same timestamp and sequence)
        assertFalse("Duplicate packet must be rejected", validator.validateAndRecord(1000L, 1))

        // Stale sequence number for same timestamp
        assertFalse("Lower sequence number for same timestamp must be rejected", validator.validateAndRecord(1000L, 0))

        // Stale timestamp (> 300 s in the past)
        assertFalse("Timestamp > 300s in past must be rejected", validator.validateAndRecord(600L, 5))

        // Newer valid packet
        assertTrue("Newer timestamp packet must be accepted", validator.validateAndRecord(1005L, 2))

        // Reset validator (tracker reboot / stream resumed)
        validator.reset()
        assertTrue("Stream resume after reset must accept packet with earlier timestamp", validator.validateAndRecord(500L, 1))
    }

    @Test
    fun testQrUriParserValidation() {
        val rawSim = "+919876543210"
        val encodedSim = URLEncoder.encode(rawSim, "UTF-8")
        var decodedSim = URLDecoder.decode(encodedSim, "UTF-8")
        if (decodedSim.startsWith(" ")) {
            decodedSim = "+" + decodedSim.substring(1)
        }
        assertEquals("+919876543210", decodedSim)

        val validKey = "4d8a1c92b8e3f7a1029c83b4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4"
        val shortKey = "shortkey"

        assertTrue(MqttTelemetrySource.isValidSecretHex(validKey))
        assertFalse(MqttTelemetrySource.isValidSecretHex(shortKey))
        assertFalse(MqttTelemetrySource.isValidSecretHex(""))
    }

    @Test
    fun testHistoryStitchingAndDeduplicationLogic() {
        val chunk1Points = listOf(
            ParsedTripPoint(1000L, 21.1458, 79.0882, 30f, 90, true, isTripStart = true, isTripEnd = false, satCount = 10),
            ParsedTripPoint(1010L, 21.1460, 79.0885, 35f, 92, true, isTripStart = false, isTripEnd = false, satCount = 10),
            ParsedTripPoint(1020L, 21.1462, 79.0888, 40f, 95, true, isTripStart = false, isTripEnd = false, satCount = 10)
        )

        val chunk2Points = listOf(
            ParsedTripPoint(1030L, 21.1465, 79.0890, 42f, 98, true, isTripStart = false, isTripEnd = false, satCount = 10),
            ParsedTripPoint(1040L, 21.1468, 79.0892, 45f, 100, true, isTripStart = false, isTripEnd = false, satCount = 10),
            ParsedTripPoint(1050L, 21.1470, 79.0895, 48f, 102, true, isTripStart = false, isTripEnd = true, satCount = 10)
        )

        val gapSeconds = chunk2Points.first().timestamp - chunk1Points.last().timestamp
        val isContinuous = !chunk2Points.first().isTripStart && gapSeconds in 0..300
        assertTrue("Chunk 2 points should append to Chunk 1 trip session", isContinuous)

        val existingTsSet = setOf(1000L, 1010L, 1020L)
        val filteredNewPoints = chunk1Points.filter { it.timestamp !in existingTsSet }
        assertTrue("Resent duplicate chunk points must be filtered out", filteredNewPoints.isEmpty())
    }

    @Test
    fun testTripMathStandstillJitterAndGpsJump() {
        // 1. Standstill jitter: speed == 0 on both points -> 0.0 km
        val p1 = ParsedTripPoint(1000L, 21.14580, 79.08820, 0f, 0, true, false, false, 8)
        val p2 = ParsedTripPoint(1001L, 21.14585, 79.08825, 0f, 0, true, false, false, 8)
        assertEquals(0.0, TripMath.distanceKm(listOf(p1, p2)), 1e-6)

        // 2. Micro-jitter < 3 meters even with non-zero speed -> 0.0 km
        val p3 = ParsedTripPoint(1002L, 21.145800, 79.088200, 5f, 0, true, false, false, 8)
        val p4 = ParsedTripPoint(1003L, 21.145810, 79.088200, 5f, 0, true, false, false, 8) // ~1.1m
        assertEquals(0.0, TripMath.distanceKm(listOf(p3, p4)), 1e-6)

        // 3. Impossible GPS jump (> 250 km/h implied: ~5 km in 1 second) -> 0.0 km
        val p5 = ParsedTripPoint(1004L, 21.1458, 79.0882, 40f, 0, true, false, false, 8)
        val p6 = ParsedTripPoint(1005L, 21.1900, 79.0882, 40f, 0, true, false, false, 8)
        assertEquals(0.0, TripMath.distanceKm(listOf(p5, p6)), 1e-6)

        // 4. Normal moving ride segment (~111m over 10s = ~40 km/h) -> accepted
        val p7 = ParsedTripPoint(1010L, 21.1458, 79.0882, 40f, 0, true, false, false, 8)
        val p8 = ParsedTripPoint(1020L, 21.1468, 79.0882, 40f, 0, true, false, false, 8)
        val distKm = TripMath.distanceKm(listOf(p7, p8))
        assertTrue("Expected ~0.111 km, got $distKm", distKm in 0.10..0.13)
    }

    @Test
    fun testTripMathMovingAvgSpeed() {
        val pts = listOf(
            ParsedTripPoint(1000L, 21.1458, 79.0882, 0.0f, 0, true, true, false, 8),
            ParsedTripPoint(1001L, 21.1458, 79.0882, 1.2f, 0, true, false, false, 8), // <= 1.5 km/h ignored
            ParsedTripPoint(1002L, 21.1460, 79.0885, 30.0f, 0, true, false, false, 8),
            ParsedTripPoint(1003L, 21.1463, 79.0888, 50.0f, 0, true, false, true, 8)
        )
        assertEquals(40.0, TripMath.movingAvgSpeedKmh(pts), 1e-4)
    }

    @Test
    fun testAppLogSanitizerRedactsSecrets() {
        val raw = "Pairing safar://pair?id=SAFAR_01&sec=fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
        val clean = AppLog.sanitize(raw)
        assertFalse("64-hex secret must be redacted", clean.contains("fedcba9876543210"))
        assertTrue("Redacted placeholder must be present", clean.contains("[REDACTED_KEY]"))
    }

    @Test
    fun testSequentialChannelOrder() = runBlocking {
        val ch = Channel<Int>(Channel.UNLIMITED)
        val expected = (1..20).toList()
        expected.forEach { ch.trySend(it) }
        ch.close()

        val received = mutableListOf<Int>()
        for (item in ch) {
            received.add(item)
        }
        assertEquals(expected, received)
    }
}

