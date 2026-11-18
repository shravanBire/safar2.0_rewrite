#ifndef SAFAR_TYPES_H
#define SAFAR_TYPES_H

#include <stdint.h>
#include <stddef.h>
#include <string.h>

// Packet Opcodes (matching Android BinaryPacketCodec.kt)
static const uint8_t PKT_OPEN_SESSION   = 0x01;
static const uint8_t PKT_LIVE_TELEMETRY = 0x02;
static const uint8_t PKT_HISTORY_CHUNK  = 0x03;
static const uint8_t PKT_HISTORY_ACK    = 0x04;
static const uint8_t PKT_HEARTBEAT      = 0x05;
static const uint8_t PKT_CLEAR_FLASH    = 0x06;
static const uint8_t PKT_SET_EMERGENCY  = 0x07;
static const uint8_t PKT_SOS_EVENT      = 0x08;

// Plain packet sizes (including 1B opcode and 2B trailing CRC16)
static const size_t TRIP_POINT_SIZE     = 16;
static const size_t OPEN_SESSION_SIZE   = 9;   // 1B Opcode + 4B LastSyncedTS + 2B Seq + 2B CRC16
static const size_t LIVE_TELEMETRY_SIZE = 21;  // 1B Opcode + 2B Seq + 16B TripPoint + 2B CRC16
static const size_t HISTORY_CHUNK_SIZE  = 488; // 1B Opcode + 2B ChunkID + 2B TotalChunks + 1B Count + 480B Points + 2B CRC16
static const size_t HISTORY_ACK_SIZE    = 7;   // 1B Opcode + 2B ChunkID + 1B Status + 1B Reserved + 2B CRC16
static const size_t HEARTBEAT_SIZE      = 9;   // 1B Opcode + 4B Timestamp + 2B Seq + 2B CRC16
static const size_t CLEAR_FLASH_SIZE    = 10;  // 1B Opcode + 4B UpToTimestamp + 1B Mode + 2B Seq + 2B CRC16
static const size_t SET_EMERGENCY_SIZE  = 23;  // 1B Opcode + 1B Slot + 1B Len + 16B Number + 2B Seq + 2B CRC16
static const size_t SOS_EVENT_SIZE      = 23;  // 1B Opcode + 16B TripPoint + 1B Reason + 1B State + 2B Seq + 2B CRC16

// Minimum trusted epoch: > 1700000000 UTC (PHASE F-A Task 1)
static const uint32_t MIN_TRUSTED_EPOCH        = 1700000000UL;
static const uint32_t UNTRUSTED_FALLBACK_BASE  = 1762800000UL;
static const uint32_t HEARTBEAT_MAX_DRIFT_SEC   = 600UL;   // PHASE F-A Task 3: ±600 s
static const uint32_t CLEAR_FLASH_MAX_DRIFT_SEC = 120UL;   // PHASE F-A Task 3: ±120 s
static const uint32_t FUTURE_CURSOR_MAX_DRIFT_S = 86400UL; // PHASE F-A Task 5: +86400 s
static const uint32_t HISTORY_ACK_TIMEOUT_MS    = 10000UL; // PHASE F-A Task 6: 10000 ms
static const uint8_t  HISTORY_MAX_RETRIES       = 3;       // PHASE F-A Task 6: max 3 retries

#pragma pack(push, 1)
struct TripPoint {
    uint32_t timestamp;      // Bytes 0..3  : Unix Epoch Seconds (UTC, Little-Endian)
    int32_t  lat_e7;         // Bytes 4..7  : Latitude * 10,000,000 (Little-Endian)
    int32_t  lon_e7;         // Bytes 8..11 : Longitude * 10,000,000 (Little-Endian)
    uint16_t speed_decideg;  // Bytes 12..13: Speed in km/h * 10 (Little-Endian)
    uint8_t  heading_div2;   // Byte 14     : Course angle / 2 (0..179)
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

// CRC-16/CCITT-FALSE (poly=0x1021, init=0xFFFF) matching Kotlin BinaryPacketCodec.computeCrc16
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

// Little-Endian helpers matching Kotlin ByteBuffer.order(ByteOrder.LITTLE_ENDIAN)
inline void writeU16LE(uint8_t* out, uint16_t v) {
    out[0] = static_cast<uint8_t>(v & 0xFF);
    out[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
}

inline uint16_t readU16LE(const uint8_t* in) {
    return static_cast<uint16_t>(in[0]) | (static_cast<uint16_t>(in[1]) << 8);
}

inline void writeU32LE(uint8_t* out, uint32_t v) {
    out[0] = static_cast<uint8_t>(v & 0xFF);
    out[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
    out[2] = static_cast<uint8_t>((v >> 16) & 0xFF);
    out[3] = static_cast<uint8_t>((v >> 24) & 0xFF);
}

inline uint32_t readU32LE(const uint8_t* in) {
    return static_cast<uint32_t>(in[0]) |
           (static_cast<uint32_t>(in[1]) << 8)  |
           (static_cast<uint32_t>(in[2]) << 16) |
           (static_cast<uint32_t>(in[3]) << 24);
}

// Pack TripPoint in Little-Endian wire order (16 bytes) matching Kotlin BinaryPacketCodec
inline void packTripPointLE(const TripPoint& pt, uint8_t out16[16]) {
    writeU32LE(out16 + 0, pt.timestamp);
    writeU32LE(out16 + 4, static_cast<uint32_t>(pt.lat_e7));
    writeU32LE(out16 + 8, static_cast<uint32_t>(pt.lon_e7));
    writeU16LE(out16 + 12, pt.speed_decideg);
    out16[14] = pt.heading_div2;
    out16[15] = pt.flags;
}

inline TripPoint unpackTripPointLE(const uint8_t in16[16]) {
    TripPoint pt;
    pt.timestamp     = readU32LE(in16 + 0);
    pt.lat_e7        = static_cast<int32_t>(readU32LE(in16 + 4));
    pt.lon_e7        = static_cast<int32_t>(readU32LE(in16 + 8));
    pt.speed_decideg = readU16LE(in16 + 12);
    pt.heading_div2  = in16[14];
    pt.flags         = in16[15];
    return pt;
}

// Encode 21-byte plain Live Telemetry packet (0x02)
inline void encodeLiveTelemetryPlain(uint16_t seqNum, const TripPoint& pt, uint8_t out21[LIVE_TELEMETRY_SIZE]) {
    out21[0] = PKT_LIVE_TELEMETRY;
    writeU16LE(out21 + 1, seqNum);
    packTripPointLE(pt, out21 + 3);
    uint16_t crc = calculateCrc16(out21, LIVE_TELEMETRY_SIZE - 2);
    writeU16LE(out21 + LIVE_TELEMETRY_SIZE - 2, crc);
}

// Encode 488-byte plain History Chunk packet (0x03)
inline void encodeHistoryChunkPlain(
    uint16_t chunkId,
    uint16_t totalChunks,
    uint8_t count,
    const TripPoint* points,
    uint8_t out488[HISTORY_CHUNK_SIZE]
) {
    memset(out488, 0, HISTORY_CHUNK_SIZE);
    out488[0] = PKT_HISTORY_CHUNK;
    writeU16LE(out488 + 1, chunkId);
    writeU16LE(out488 + 3, totalChunks);
    uint8_t safeCount = (count > 30) ? 30 : count;
    out488[5] = safeCount;
    for (uint8_t i = 0; i < safeCount; ++i) {
        packTripPointLE(points[i], out488 + 6 + (i * TRIP_POINT_SIZE));
    }
    uint16_t crc = calculateCrc16(out488, HISTORY_CHUNK_SIZE - 2);
    writeU16LE(out488 + HISTORY_CHUNK_SIZE - 2, crc);
}

inline bool verifyPlainCrc16(const uint8_t* plain, size_t plainLen) {
    if (plainLen < 4) return false;
    uint16_t expected = calculateCrc16(plain, plainLen - 2);
    uint16_t received = readU16LE(plain + plainLen - 2);
    return expected == received;
}

// Encode 23-byte plain SOS Event packet (0x08)
inline void encodeSosEventPlain(
    const TripPoint& pt,
    uint8_t reason,
    uint8_t state,
    uint16_t seqNum,
    uint8_t out23[SOS_EVENT_SIZE]
) {
    out23[0] = PKT_SOS_EVENT;
    packTripPointLE(pt, out23 + 1);
    out23[17] = reason;
    out23[18] = state;
    writeU16LE(out23 + 19, seqNum);
    uint16_t crc = calculateCrc16(out23, SOS_EVENT_SIZE - 2);
    writeU16LE(out23 + SOS_EVENT_SIZE - 2, crc);
}

// PHASE F-A Task 3: Command sequence & freshness validation
struct CommandReplayState {
    uint16_t cmdSeq      = 0;
    bool     cmdSeqValid = false;
};

enum CommandRejectReason {
    CMD_ACCEPT = 0,
    CMD_REJECT_STALE,
    CMD_REJECT_REPLAY,
    CMD_REJECT_UNTRUSTED_TIME
};

inline void resetCmdSeqOnOpenSession(uint16_t openSessionSeq, CommandReplayState& state) {
    state.cmdSeq      = openSessionSeq;
    state.cmdSeqValid = true;
}

inline bool isSeqNewerWindow(uint16_t incomingSeq, CommandReplayState& state) {
    if (!state.cmdSeqValid) {
        state.cmdSeq      = incomingSeq;
        state.cmdSeqValid = true;
        return true;
    }
    if (static_cast<int16_t>(incomingSeq - state.cmdSeq) > 0) {
        state.cmdSeq = incomingSeq;
        return true;
    }
    return false;
}

inline bool isTimestampWithinDrift(uint32_t packetTs, uint32_t currentEpoch, uint32_t maxDriftSec) {
    if (packetTs == 0 || currentEpoch <= MIN_TRUSTED_EPOCH) return false;
    uint32_t diff = (packetTs > currentEpoch) ? (packetTs - currentEpoch) : (currentEpoch - packetTs);
    return diff <= maxDriftSec;
}

/**
 * PHASE F-A Task 3: Validate command authenticity for HEARTBEAT, CLEAR_FLASH, and SET_EMERGENCY.
 * - Sequence window (int16_t)(seq - cmdSeq) > 0 once cmdSeqValid applies to all three.
 * - When timeTrusted: reject HEARTBEAT if |ts - epoch| > 600s, reject CLEAR_FLASH if |ts - epoch| > 120s.
 * - CLEAR_FLASH additionally requires timeTrusted == true and allowRemoteClear == true.
 */
inline CommandRejectReason validateAuthenticatedCommand(
    uint8_t opcode,
    uint32_t packetTimestamp,
    uint16_t incomingSeq,
    bool timeTrusted,
    bool allowRemoteClear,
    uint32_t currentEpoch,
    CommandReplayState& state
) {
    if (opcode == PKT_CLEAR_FLASH) {
        if (!timeTrusted || !allowRemoteClear) {
            return CMD_REJECT_UNTRUSTED_TIME;
        }
        if (!isTimestampWithinDrift(packetTimestamp, currentEpoch, CLEAR_FLASH_MAX_DRIFT_SEC)) {
            return CMD_REJECT_STALE;
        }
    } else if (opcode == PKT_HEARTBEAT) {
        if (timeTrusted && !isTimestampWithinDrift(packetTimestamp, currentEpoch, HEARTBEAT_MAX_DRIFT_SEC)) {
            return CMD_REJECT_STALE;
        }
    }

    if (!isSeqNewerWindow(incomingSeq, state)) {
        return CMD_REJECT_REPLAY;
    }
    return CMD_ACCEPT;
}

inline const char* rejectReasonToString(CommandRejectReason r) {
    switch (r) {
        case CMD_REJECT_STALE:          return "stale";
        case CMD_REJECT_REPLAY:         return "replay";
        case CMD_REJECT_UNTRUSTED_TIME: return "untrusted-time";
        default:                        return "ok";
    }
}

#endif // SAFAR_TYPES_H
