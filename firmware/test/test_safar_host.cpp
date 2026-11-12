#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <assert.h>
#include "../safar_esp32/safar_types.h"
#include "../safar_esp32/safar_crypto.h"

static void testCrc16() {
    const uint8_t sample[] = {0x01, 0x00, 0x00, 0x65, 0x92, 0x05, 0x80};
    uint16_t crc = calculateCrc16(sample, sizeof(sample));
    assert(crc != 0x0000 && crc != 0xFFFF);

    // Deterministic check: same input yields identical CRC
    uint16_t crc2 = calculateCrc16(sample, sizeof(sample));
    assert(crc == crc2);

    // Flipping 1 bit changes CRC
    uint8_t mutated[sizeof(sample)];
    memcpy(mutated, sample, sizeof(sample));
    mutated[3] ^= 0x01;
    assert(calculateCrc16(mutated, sizeof(mutated)) != crc);
    printf("[PASS] testCrc16\n");
}

static void testTripPointPackUnpack() {
    TripPoint orig;
    orig.timestamp     = 1711800000UL;
    orig.lat_e7        = 211458000;
    orig.lon_e7        = 790882000;
    orig.speed_decideg = 543; // 54.3 km/h
    orig.heading_div2  = 105; // 210 deg
    orig.flags         = packFlags(true, true, false, 9);

    uint8_t wire[16];
    packTripPointBE(orig, wire);
    TripPoint decoded = unpackTripPointBE(wire);

    assert(decoded.timestamp     == orig.timestamp);
    assert(decoded.lat_e7        == orig.lat_e7);
    assert(decoded.lon_e7        == orig.lon_e7);
    assert(decoded.speed_decideg == orig.speed_decideg);
    assert(decoded.heading_div2  == orig.heading_div2);
    assert(decoded.flags         == orig.flags);
    assert((decoded.flags & 0x01) != 0);
    assert((decoded.flags & 0x02) != 0);
    assert((decoded.flags & 0x04) == 0);
    assert(unpackSatCount(decoded.flags) == 9);
    printf("[PASS] testTripPointPackUnpack\n");
}

static void testHkdfAndChaChaPolyRoundTrip() {
    const char* pubHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    const char* secHex = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

    char topicId[17];
    SafarCrypto::deriveTopicId(pubHex, topicId);
    assert(strlen(topicId) == 16);

    uint8_t secret32[32];
    assert(SafarCrypto::hexToBytes(secHex, secret32, 32));

    uint8_t key32[32];
    SafarCrypto::deriveChaChaKey(secret32, key32);

    // Build a 21-byte PKT_LIVE_TELEMETRY plaintext
    TripPoint pt = {1711800060UL, 211458000, 790882000, 420, 90, packFlags(true, false, false, 8)};
    uint8_t plain[21];
    plain[0] = PKT_LIVE_TELEMETRY;
    writeU16BE(plain + 1, 7);
    packTripPointBE(pt, plain + 3);
    uint16_t crc = calculateCrc16(plain, 19);
    writeU16BE(plain + 19, crc);

    uint8_t nonce12[12];
    writeU32BE(nonce12 + 0, 1711800060UL);
    writeU32BE(nonce12 + 4, 0xAABBCCDDUL);
    writeU32BE(nonce12 + 8, 1UL);

    const char* aad = "safar/topic/live";
    uint8_t frame[21 + 28];
    SafarCrypto::encryptFrame(key32, nonce12, reinterpret_cast<const uint8_t*>(aad), strlen(aad), plain, 21, frame);

    uint8_t decrypted[21];
    uint8_t outNonce[12];
    bool ok = SafarCrypto::decryptFrame(key32, reinterpret_cast<const uint8_t*>(aad), strlen(aad), frame, sizeof(frame), decrypted, outNonce);
    assert(ok);
    assert(memcmp(plain, decrypted, 21) == 0);
    assert(memcmp(nonce12, outNonce, 12) == 0);

    // Tamper 1 bit of ciphertext -> must fail AEAD verification
    frame[15] ^= 0x01;
    bool tamperedOk = SafarCrypto::decryptFrame(key32, reinterpret_cast<const uint8_t*>(aad), strlen(aad), frame, sizeof(frame), decrypted, outNonce);
    assert(!tamperedOk);

    printf("[PASS] testHkdfAndChaChaPolyRoundTrip\n");
}

static void testTimeTrustedReplayLogic() {
    ReplayGuardState guard;
    uint32_t currentEpoch = 1711800100UL;

    uint8_t nonce1[12];
    writeU32BE(nonce1 + 0, 1711800095UL); // Fresh within 5s
    writeU32BE(nonce1 + 4, 0x11223344UL); // Boot salt
    writeU32BE(nonce1 + 8, 10UL);         // Counter = 10

    // 1. When timeTrusted == true, fresh nonce + counter 10 succeeds
    assert(verifyIncomingReplayAndFreshness(true, currentEpoch, nonce1, guard));

    // 2. Duplicate counter 10 with same bootSalt must be rejected when timeTrusted == true
    assert(!verifyIncomingReplayAndFreshness(true, currentEpoch, nonce1, guard));

    // 3. Incremented counter 11 succeeds
    writeU32BE(nonce1 + 8, 11UL);
    assert(verifyIncomingReplayAndFreshness(true, currentEpoch, nonce1, guard));

    // 4. Stale timestamp (> 300s drift) must be rejected when timeTrusted == true
    uint8_t staleNonce[12];
    writeU32BE(staleNonce + 0, currentEpoch - 600UL);
    writeU32BE(staleNonce + 4, 0x11223344UL);
    writeU32BE(staleNonce + 8, 12UL);
    assert(!verifyIncomingReplayAndFreshness(true, currentEpoch, staleNonce, guard));

    // 5. When timeTrusted == false (cold boot before GPS/NTP fix), frame is accepted if AEAD + CRC16 pass
    assert(verifyIncomingReplayAndFreshness(false, 42UL, staleNonce, guard));

    printf("[PASS] testTimeTrustedReplayLogic\n");
}

int main() {
    testCrc16();
    testTripPointPackUnpack();
    testHkdfAndChaChaPolyRoundTrip();
    testTimeTrustedReplayLogic();
    printf("ALL TESTS PASSED\n");
    return 0;
}
