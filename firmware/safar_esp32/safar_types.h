#ifndef SAFAR_TYPES_H
#define SAFAR_TYPES_H

#include <stdint.h>
#include <stddef.h>
#include <string.h>

// Packet Opcodes
static const uint8_t PKT_OPEN_SESSION   = 0x01;
static const uint8_t PKT_LIVE_TELEMETRY = 0x02;
static const uint8_t PKT_HISTORY_CHUNK  = 0x03;
static const uint8_t PKT_HISTORY_ACK    = 0x04;
static const uint8_t PKT_HEARTBEAT      = 0x05;
static const uint8_t PKT_CLEAR_FLASH    = 0x06;

// Minimum trusted epoch: 2024-01-01 00:00:00 UTC
static const uint32_t MIN_TRUSTED_EPOCH = 1704067200UL;
static const uint32_t MAX_NONCE_DRIFT_SECONDS = 300UL; // 5 minutes

#pragma pack(push, 1)
struct TripPoint {
    uint32_t timestamp;      // Bytes 0..3  : Unix Epoch Seconds (UTC)
    int32_t  lat_e7;         // Bytes 4..7  : Latitude * 10,000,000
    int32_t  lon_e7;         // Bytes 8..11 : Longitude * 10,000,000
    uint16_t speed_decideg;  // Bytes 12..13: Speed in km/h * 10
    uint8_t  heading_div2;   // Byte 14     : Course angle / 2 (0..180)
    uint8_t  flags;          // Byte 15     : Bit0=Ign, Bit1=Start, Bit2=End, Bits3..7=Sats
};
#pragma pack(pop)

static_assert(sizeof(TripPoint) == 16, "TripPoint must be exactly 16 bytes");

inline uint8_t packFlags(bool ignition, bool tripStart, bool tripEnd, uint8_t satCount) {
    uint8_t f = 0;
    if (ignition)  f |= 0x01;
    if (tripStart) f |= 0x02;
    if (tripEnd)   f |= 0x04;
    uint8_t clampedSats = (satCount > 31) ? 31 : satCount;
    f |= static_cast<uint8_t>((clampedSats & 0x1F) << 3);
    return f;
}

inline uint8_t unpackSatCount(uint8_t flags) {
    return (flags >> 3) & 0x1F;
}

// CRC-16/CCITT-FALSE (poly=0x1021, init=0xFFFF) matching Kotlin BinaryPacketCodec.calculateCrc16
inline uint16_t calculateCrc16(const uint8_t* data, size_t len) {
    uint16_t crc = 0xFFFF;
    for (size_t i = 0; i < len; ++i) {
        crc ^= static_cast<uint16_t>(data[i]) << 8;
        for (int b = 0; b < 8; ++b) {
            if (crc & 0x8000) {
                crc = static_cast<uint16_t>((crc << 1) ^ 0x1021);
            } else {
                crc = static_cast<uint16_t>(crc << 1);
            }
        }
    }
    return crc;
}

inline void writeU16BE(uint8_t* out, uint16_t v) {
    out[0] = static_cast<uint8_t>((v >> 8) & 0xFF);
    out[1] = static_cast<uint8_t>(v & 0xFF);
}

inline uint16_t readU16BE(const uint8_t* in) {
    return (static_cast<uint16_t>(in[0]) << 8) | static_cast<uint16_t>(in[1]);
}

inline void writeU32BE(uint8_t* out, uint32_t v) {
    out[0] = static_cast<uint8_t>((v >> 24) & 0xFF);
    out[1] = static_cast<uint8_t>((v >> 16) & 0xFF);
    out[2] = static_cast<uint8_t>((v >> 8) & 0xFF);
    out[3] = static_cast<uint8_t>(v & 0xFF);
}

inline uint32_t readU32BE(const uint8_t* in) {
    return (static_cast<uint32_t>(in[0]) << 24) |
           (static_cast<uint32_t>(in[1]) << 16) |
           (static_cast<uint32_t>(in[2]) << 8)  |
           static_cast<uint32_t>(in[3]);
}

// Pack TripPoint in Big-Endian wire order (16 bytes) matching Kotlin BinaryPacketCodec
inline void packTripPointBE(const TripPoint& pt, uint8_t out16[16]) {
    writeU32BE(out16 + 0, pt.timestamp);
    writeU32BE(out16 + 4, static_cast<uint32_t>(pt.lat_e7));
    writeU32BE(out16 + 8, static_cast<uint32_t>(pt.lon_e7));
    writeU16BE(out16 + 12, pt.speed_decideg);
    out16[14] = pt.heading_div2;
    out16[15] = pt.flags;
}

inline TripPoint unpackTripPointBE(const uint8_t in16[16]) {
    TripPoint pt;
    pt.timestamp     = readU32BE(in16 + 0);
    pt.lat_e7        = static_cast<int32_t>(readU32BE(in16 + 4));
    pt.lon_e7        = static_cast<int32_t>(readU32BE(in16 + 8));
    pt.speed_decideg = readU16BE(in16 + 12);
    pt.heading_div2  = in16[14];
    pt.flags         = in16[15];
    return pt;
}

// P1-F1 & P1-F2: Replay/freshness checks gated on timeTrusted
struct ReplayGuardState {
    uint32_t lastBootSalt = 0;
    uint32_t lastCounter  = 0;
    bool     hasSeenFrame = false;
};

inline bool nonceTimestampFresh(uint32_t nonceEpoch, uint32_t currentEpoch) {
    if (nonceEpoch == 0 || currentEpoch < MIN_TRUSTED_EPOCH) return false;
    uint32_t diff = (nonceEpoch > currentEpoch)
                    ? (nonceEpoch - currentEpoch)
                    : (currentEpoch - nonceEpoch);
    return diff <= MAX_NONCE_DRIFT_SECONDS;
}

inline bool replayWindowValid(ReplayGuardState& state, uint32_t bootSalt, uint32_t counter) {
    if (!state.hasSeenFrame || bootSalt != state.lastBootSalt) {
        state.lastBootSalt = bootSalt;
        state.lastCounter  = counter;
        state.hasSeenFrame = true;
        return true;
    }
    if (counter <= state.lastCounter) {
        return false; // Duplicate or replayed counter within same session salt
    }
    state.lastCounter = counter;
    return true;
}

/**
 * P1-F2: Enforce replayWindowValid and nonceTimestampFresh ONLY when timeTrusted is true.
 * When timeTrusted is false (cold boot before GPS/NTP fix), accept frame once AEAD + CRC16 pass.
 */
inline bool verifyIncomingReplayAndFreshness(
    bool timeTrusted,
    uint32_t currentEpoch,
    const uint8_t nonce12[12],
    ReplayGuardState& guardState
) {
    if (!timeTrusted) {
        return true;
    }
    uint32_t nonceEpoch = readU32BE(nonce12 + 0);
    uint32_t bootSalt   = readU32BE(nonce12 + 4);
    uint32_t counter    = readU32BE(nonce12 + 8);

    if (!nonceTimestampFresh(nonceEpoch, currentEpoch)) {
        return false;
    }
    return replayWindowValid(guardState, bootSalt, counter);
}

#endif // SAFAR_TYPES_H
