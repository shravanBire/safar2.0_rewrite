#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <assert.h>
#include "../safar_esp32/safar_types.h"
#include "../safar_esp32/safar_crypto.h"

static void testGoldenCrc16() {
    // Matches Kotlin BinaryPacketCodecTest.testGoldenCrc16Vector ("123456789" -> 0x29B1)
    const char* input = "123456789";
    uint16_t crc = calculateCrc16(reinterpret_cast<const uint8_t*>(input), 9);
    assert(crc == 0x29B1);
    printf("[PASS] testGoldenCrc16\n");
}

static void testTripPointPackUnpackLE() {
    TripPoint orig;
    orig.timestamp     = 1710000000UL;
    orig.lat_e7        = 123456790;
    orig.lon_e7        = 771234567;
    orig.speed_decideg = 654; // 65.4 km/h
    orig.heading_div2  = 90;  // 180 deg
    orig.flags         = packFlags(true, false, true, 12);

    uint8_t wire[16];
    packTripPointLE(orig, wire);
    TripPoint decoded = unpackTripPointLE(wire);

    assert(decoded.timestamp     == orig.timestamp);
    assert(decoded.lat_e7        == orig.lat_e7);
    assert(decoded.lon_e7        == orig.lon_e7);
    assert(decoded.speed_decideg == orig.speed_decideg);
    assert(decoded.heading_div2  == orig.heading_div2);
    assert(decoded.flags         == orig.flags);
    assert((decoded.flags & 0x01) != 0);
    assert((decoded.flags & 0x02) == 0);
    assert((decoded.flags & 0x04) != 0);
    assert(unpackSatCount(decoded.flags) == 12);
    printf("[PASS] testTripPointPackUnpackLE\n");
}

static void testTopicIdAndAeadWireFormat() {
    // Matches Kotlin BinaryPacketCodecTest.testDeriveDeviceTopicIdVector
    const char* pubKey = "4d8a1c92b8e3f7a1029c83b4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4";
    char topicId[17];
    SafarCrypto::deriveTopicId(pubKey, topicId);
    assert(strcmp(topicId, "a996b58b008271c7") == 0);

    // Matches Kotlin BinaryPacketCodecTest.testAeadEncryptedFrameExactSizes
    const char* dummySecret = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";
    uint8_t key32[32];
    assert(SafarCrypto::hexToBytes(dummySecret, key32, 32));

    TripPoint pt = {1710000000UL, 123400000, 775600000, 600, 90, packFlags(true, true, false, 12)};
    uint8_t plainLive[LIVE_TELEMETRY_SIZE];
    encodeLiveTelemetryPlain(1, pt, plainLive);
    assert(verifyPlainCrc16(plainLive, LIVE_TELEMETRY_SIZE));

    uint8_t nonce12[12] = {1,2,3,4,5,6,7,8,9,10,11,12};
    uint8_t encryptedLive[64];
    size_t encLen = SafarCrypto::encryptPlainPacket(key32, nonce12, plainLive, LIVE_TELEMETRY_SIZE, encryptedLive);
    // 21B plain -> 47B encrypted (1B opcode + 12B nonce + 18B ciphertext + 16B tag)
    assert(encLen == 47);
    assert(encryptedLive[0] == PKT_LIVE_TELEMETRY);

    uint8_t decryptedLive[64];
    size_t decLen = SafarCrypto::decryptToPlainPacket(key32, encryptedLive, encLen, decryptedLive);
    assert(decLen == LIVE_TELEMETRY_SIZE);
    assert(memcmp(plainLive, decryptedLive, LIVE_TELEMETRY_SIZE) == 0);
    assert(verifyPlainCrc16(decryptedLive, decLen));

    // Tamper 1 bit -> must fail AEAD authentication
    encryptedLive[20] ^= 0x01;
    assert(SafarCrypto::decryptToPlainPacket(key32, encryptedLive, encLen, decryptedLive) == 0);

    printf("[PASS] testTopicIdAndAeadWireFormat\n");
}

static void testCommandFreshnessAndReplayP1F2() {
    CommandReplayState state;
    uint32_t nowEpoch = 1711801000UL;

    // 1. Fresh HEARTBEAT (within ±600s) and seq=1 -> accepted
    assert(verifyCommandFreshnessAndSeq(PKT_HEARTBEAT, nowEpoch - 100UL, 1, true, nowEpoch, state));

    // 2. Replayed seq=1 -> rejected
    assert(!verifyCommandFreshnessAndSeq(PKT_HEARTBEAT, nowEpoch - 50UL, 1, true, nowEpoch, state));

    // 3. Stale HEARTBEAT (> 600s) even with newer seq=2 -> rejected
    assert(!verifyCommandFreshnessAndSeq(PKT_HEARTBEAT, nowEpoch - 601UL, 2, true, nowEpoch, state));

    // 4. Stale CLEAR_FLASH (> 120s) with newer seq=3 -> rejected
    assert(!verifyCommandFreshnessAndSeq(PKT_CLEAR_FLASH, nowEpoch - 121UL, 3, true, nowEpoch, state));

    // 5. Fresh CLEAR_FLASH (within ±120s) with newer seq=4 -> accepted
    assert(verifyCommandFreshnessAndSeq(PKT_CLEAR_FLASH, nowEpoch - 60UL, 4, true, nowEpoch, state));

    // 6. When timeTrusted == false, command is accepted once AEAD+CRC pass
    assert(verifyCommandFreshnessAndSeq(PKT_CLEAR_FLASH, 50UL, 4, false, 50UL, state));

    printf("[PASS] testCommandFreshnessAndReplayP1F2\n");
}

int main() {
    testGoldenCrc16();
    testTripPointPackUnpackLE();
    testTopicIdAndAeadWireFormat();
    testCommandFreshnessAndReplayP1F2();
    printf("ALL TESTS PASSED\n");
    return 0;
}
