/*
 * SAFAR 2.0 — Production ESP32 Telemetry, Offline Flash Logger & Crash SOS Firmware
 *
 * Required Arduino Libraries:
 *   - PubSubClient (by Nick O'Leary)
 *   - TinyGPSPlus  (by Mikal Hart)
 *   - MPU6050_light (by rfetick)
 *
 * PHASE F-A Hardened:
 *   1. timeTrusted: true ONLY via real GPS date/time or NTP (> 1700000000).
 *      Phone-time bootstrap from HEARTBEAT and fallback (1762800000 + millis()/1000) never set timeTrusted.
 *   2. isRealGps requires timeTrusted; flash logging strictly gated on isRealGps.
 *      Logs "[TIME] trusted via GPS|NTP" once, and "[TIME] untrusted - not logging" every 30s when GPS has fix.
 *   3. Command authenticity (HEARTBEAT, CLEAR_FLASH, SET_EMERGENCY) with OPEN_SESSION sequence reset.
 *      Logs "[CMD] rejected <name>: stale|replay|untrusted-time".
 *   4. RELEASE_BUILD flag forcing VERIFY_TLS=1, STREAM_ON_BOOT=0, DEMO_FAKE_GPS=0, INDOOR_TABLE_FALLBACK=0, ALLOW_REMOTE_CLEAR=0.
 *      Refuses MQTT if VERIFY_TLS==1 and CA PEM contains "PASTE_YOUR" ("[TLS] CA certificate missing" every 10s).
 *      Prints "[CFG] release=.. tls=.. demo=.. indoor=.." at boot.
 *   5. startSync(lastTs) future cursor check (> epoch + 86400 -> use 0, "[SYNC] app cursor is in the future, ignoring")
 *      and never erases /trips.bin when !timeTrusted.
 *   6. History ACK timeout = 10000 ms with max 3 retries (stop-and-wait).
 */

#include <Arduino.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <PubSubClient.h>
#include <Wire.h>
#include <TinyGPSPlus.h>
#include <MPU6050_light.h>
#include <LittleFS.h>
#include <Preferences.h>
#include <time.h>
#include "safar_types.h"
#include "safar_crypto.h"

// ============================================================================
// 1. WI-FI CREDENTIALS (Apna Wi-Fi / Phone Hotspot Name aur Password yahan dalein)
// ============================================================================
#define WIFI_SSID     "YOUR_WIFI_NAME"
#define WIFI_PASSWORD "YOUR_WIFI_PASSWORD"

#if __has_include("secrets.h")
#include "secrets.h"
#else
#include "secrets.h.example"
#endif

#ifndef EMQX_CA_PEM
#define EMQX_CA_PEM SAFAR_MQTT_ROOT_CA_PEM
#endif

// ============================================================================
// PHASE F-A Task 4: RELEASE_BUILD & FEATURE FLAGS
// ============================================================================
#ifndef RELEASE_BUILD
#define RELEASE_BUILD 0
#endif

#define VERIFY_TLS            0
#define STREAM_ON_BOOT        1
#define DEMO_FAKE_GPS         0
#define INDOOR_TABLE_FALLBACK 1
#define ALLOW_REMOTE_CLEAR    1

#if RELEASE_BUILD == 1
  #undef VERIFY_TLS
  #define VERIFY_TLS            1
  #undef STREAM_ON_BOOT
  #define STREAM_ON_BOOT        0
  #undef DEMO_FAKE_GPS
  #define DEMO_FAKE_GPS         0
  #undef INDOOR_TABLE_FALLBACK
  #define INDOOR_TABLE_FALLBACK 0
  #undef ALLOW_REMOTE_CLEAR
  #define ALLOW_REMOTE_CLEAR    0
#endif

// ============================================================================
// HARDWARE PINS (ESP32 NodeMCU-32S)
// ============================================================================
#define GPS_RX_PIN      16   // Connect to GPS TX
#define GPS_TX_PIN      17   // Connect to GPS RX
#define I2C_SDA_PIN     21   // Connect to MPU6050 SDA
#define I2C_SCL_PIN     22   // Connect to MPU6050 SCL
#define SOS_CANCEL_PIN  0    // BOOT button (Active LOW) to cancel SOS countdown
#define STATUS_LED_PIN  2    // Onboard blue LED

// Fallback coordinates for bench testing ONLY when INDOOR_TABLE_FALLBACK == 1 (Nagpur)
static const int32_t FALLBACK_LAT_E7 = 211458000; // 21.1458000 N
static const int32_t FALLBACK_LON_E7 = 790882000; // 79.0882000 E
static const char*   FLASH_LOG_PATH  = "/trips.bin";

// ============================================================================
// GLOBAL STATE
// ============================================================================
HardwareSerial   gpsSerial(1);
TinyGPSPlus      gps;
MPU6050          mpu(Wire);
WiFiClientSecure tlsClient;
PubSubClient     mqtt(tlsClient);

static bool     mpuConnected      = false;
static bool     fsMounted         = false;
static float    refAx = 0.0f, refAy = 0.0f, refAz = 1.0f;

// PHASE F-A Task 1 & 2: Time trust state
static bool     timeTrusted             = false;
static uint32_t trustedBaseEpoch        = 0;
static uint32_t trustedBaseMs           = 0;
static uint32_t phoneBootstrapEpoch     = 0; // Display-only, NEVER sets timeTrusted
static uint32_t phoneBootstrapMs        = 0;
static uint32_t lastUntrustedTimeLogMs  = 0;
static uint32_t lastCaMissingLogMs      = 0;

// Crypto & MQTT topics
static uint8_t  sharedKey32[32]   = {0};
static char     topicId[17]       = {0};
static char     topicStatus[48]   = {0};
static char     topicLive[48]     = {0};
static char     topicHist[48]     = {0};
static char     topicCmd[48]      = {0};
static char     topicEvent[48]    = {0};
static char     topicPresence[48] = {0};

static Preferences nvsPrefs;
static char     emergencySlot0[17] = {0};
static char     emergencySlot1[17] = {0};

static uint16_t liveSeqNum        = 1;
static uint16_t sosSeqNum         = 1;
static bool     sosCountdownActive = false;
static uint8_t  sosReason         = 0;
static uint32_t sosStartMs        = 0;
static uint32_t lastTelemetryMs   = 0;
static uint32_t lastWifiRetryMs   = 0;
static uint32_t lastMqttRetryMs   = 0;
static uint32_t lastStatusPubMs   = 0;

// Trip & History Sync state
static bool     isRiding          = (STREAM_ON_BOOT == 1);
static bool     tripStartPending  = true;
static uint32_t lastMovingMs      = 0;
static CommandReplayState cmdReplayState;

static bool     syncActive         = false;
static bool     waitingForChunkAck = false;
static uint16_t currentChunkId     = 0;
static uint16_t totalSyncChunks    = 0;
static uint32_t syncRequestedSinceTs = 0;
static uint32_t lastChunkSentMs    = 0;
static uint8_t  chunkRetryCount    = 0;

// ============================================================================
// PHASE F-A Task 1 & 2: TIME TRUST MANAGEMENT
// ============================================================================
void markTimeTrusted(uint32_t utcEpochSeconds, const char* sourceLabel) {
    if (utcEpochSeconds > MIN_TRUSTED_EPOCH) {
        bool wasTrusted  = timeTrusted;
        trustedBaseEpoch = utcEpochSeconds;
        trustedBaseMs    = millis();
        timeTrusted      = true;
        if (!wasTrusted) {
            Serial.printf("[TIME] trusted via %s\n", sourceLabel);
        }
    }
}

void checkNtpTimeTrust() {
    if (timeTrusted) return;
    time_t nowSec = time(nullptr);
    if (nowSec > static_cast<time_t>(MIN_TRUSTED_EPOCH)) {
        markTimeTrusted(static_cast<uint32_t>(nowSec), "NTP");
    }
}

void checkGpsTimeTrust() {
    if (gps.date.isValid() && gps.time.isValid() && gps.date.year() >= 2024) {
        struct tm t = {0};
        t.tm_year = gps.date.year() - 1900;
        t.tm_mon  = gps.date.month() - 1;
        t.tm_mday = gps.date.day();
        t.tm_hour = gps.time.hour();
        t.tm_min  = gps.time.minute();
        t.tm_sec  = gps.time.second();
        time_t epoch = mktime(&t);
        if (epoch > static_cast<time_t>(MIN_TRUSTED_EPOCH)) {
            markTimeTrusted(static_cast<uint32_t>(epoch), "GPS");
        }
    }
}

uint32_t getCurrentEpochSeconds() {
    checkGpsTimeTrust();
    checkNtpTimeTrust();
    if (timeTrusted && trustedBaseEpoch > MIN_TRUSTED_EPOCH) {
        return trustedBaseEpoch + ((millis() - trustedBaseMs) / 1000UL);
    }
    // Display-only phone bootstrap from HEARTBEAT (NEVER sets timeTrusted)
    if (phoneBootstrapEpoch > MIN_TRUSTED_EPOCH) {
        return phoneBootstrapEpoch + ((millis() - phoneBootstrapMs) / 1000UL);
    }
    // Display-only fallback epoch (NEVER sets timeTrusted)
    return UNTRUSTED_FALLBACK_BASE + (millis() / 1000UL);
}

// ============================================================================
// CRYPTO & MQTT PUBLISH HELPERS
// ============================================================================
void fillRandomNonce(uint8_t nonce12[12]) {
    for (int i = 0; i < 3; ++i) {
        uint32_t r = esp_random();
        writeU32LE(nonce12 + (i * 4), r);
    }
}

bool publishEncryptedPacket(const char* topic, const uint8_t* plainPacket, size_t plainLen, bool retained) {
    if (!mqtt.connected()) return false;
    uint8_t nonce12[12];
    fillRandomNonce(nonce12);

    uint8_t encryptedFrame[540];
    size_t frameLen = SafarCrypto::encryptPlainPacket(sharedKey32, nonce12, plainPacket, plainLen, encryptedFrame);
    if (frameLen == 0) return false;

    return mqtt.publish(topic, encryptedFrame, frameLen, retained);
}

// ============================================================================
// LITTLEFS FLASH LOGGING & HISTORY CHUNK SYNC (PHASE F-A Tasks 2, 5, 6)
// ============================================================================
void appendPointToFlash(const TripPoint& pt, bool isRealGps) {
    // PHASE F-A Task 2: Nothing is written to flash unless isRealGps (which requires timeTrusted)
    if (!fsMounted || !timeTrusted || !isRealGps) return;
    File f = LittleFS.open(FLASH_LOG_PATH, FILE_APPEND);
    if (!f) return;
    uint8_t raw16[TRIP_POINT_SIZE];
    packTripPointLE(pt, raw16);
    f.write(raw16, TRIP_POINT_SIZE);
    f.close();
}

size_t countFlashPointsSince(uint32_t sinceTs) {
    if (!fsMounted || !LittleFS.exists(FLASH_LOG_PATH)) return 0;
    File f = LittleFS.open(FLASH_LOG_PATH, FILE_READ);
    if (!f) return 0;
    size_t count = 0;
    uint8_t buf[TRIP_POINT_SIZE];
    while (f.read(buf, TRIP_POINT_SIZE) == TRIP_POINT_SIZE) {
        TripPoint pt = unpackTripPointLE(buf);
        if (pt.timestamp > sinceTs) {
            count++;
        }
    }
    f.close();
    return count;
}

void clearFlashHistory() {
    if (fsMounted && LittleFS.exists(FLASH_LOG_PATH)) {
        LittleFS.remove(FLASH_LOG_PATH);
    }
    syncActive = false;
    waitingForChunkAck = false;
    chunkRetryCount = 0;
}

void startSync(uint32_t lastTs) {
    uint32_t nowEpoch = getCurrentEpochSeconds();
    // PHASE F-A Task 5: If timeTrusted and lastTs > epoch + 86400, ignore the app's lastTs (use 0)
    if (timeTrusted && lastTs > (nowEpoch + FUTURE_CURSOR_MAX_DRIFT_S)) {
        Serial.println("[SYNC] app cursor is in the future, ignoring");
        lastTs = 0;
    }

    syncRequestedSinceTs = lastTs;
    size_t matchingPoints = countFlashPointsSince(lastTs);
    if (matchingPoints == 0) {
        // Send 1 empty completion chunk (totalChunks=1, count=0) so app knows sync is up-to-date
        uint8_t plainChunk[HISTORY_CHUNK_SIZE];
        encodeHistoryChunkPlain(1, 1, 0, nullptr, plainChunk);
        publishEncryptedPacket(topicHist, plainChunk, HISTORY_CHUNK_SIZE, false);
        // PHASE F-A Task 5: If !timeTrusted, never erase /trips.bin in the "nothing new" branch
        if (timeTrusted && lastTs > 0) {
            clearFlashHistory();
        }
        syncActive = false;
        waitingForChunkAck = false;
        chunkRetryCount = 0;
        return;
    }
    totalSyncChunks = static_cast<uint16_t>((matchingPoints + 29) / 30);
    currentChunkId = 1;
    chunkRetryCount = 0;
    syncActive = true;
    waitingForChunkAck = false;
}

void sendCurrentHistoryChunk() {
    if (!syncActive || !fsMounted || !mqtt.connected()) return;
    File f = LittleFS.open(FLASH_LOG_PATH, FILE_READ);
    if (!f) {
        syncActive = false;
        return;
    }

    size_t targetSkip = static_cast<size_t>(currentChunkId - 1) * 30;
    size_t skipped = 0;
    TripPoint chunkPoints[30];
    uint8_t count = 0;
    uint8_t buf[TRIP_POINT_SIZE];

    while (f.read(buf, TRIP_POINT_SIZE) == TRIP_POINT_SIZE) {
        TripPoint pt = unpackTripPointLE(buf);
        if (pt.timestamp <= syncRequestedSinceTs) continue;
        if (skipped < targetSkip) {
            skipped++;
            continue;
        }
        chunkPoints[count++] = pt;
        if (count == 30) break;
    }
    f.close();

    uint8_t plainChunk[HISTORY_CHUNK_SIZE];
    encodeHistoryChunkPlain(currentChunkId, totalSyncChunks, count, chunkPoints, plainChunk);
    if (publishEncryptedPacket(topicHist, plainChunk, HISTORY_CHUNK_SIZE, false)) {
        waitingForChunkAck = true;
        lastChunkSentMs = millis();
    }
}

// ============================================================================
// INCOMING COMMAND HANDLER (safar/v1/<topic_id>/cmd) — PHASE F-A Task 3
// ============================================================================
bool handleIncomingCommandFrame(const uint8_t* frame, size_t frameLen) {
    if (frameLen < 33 || frameLen > 128) return false;

    uint8_t plain[64];
    size_t plainLen = SafarCrypto::decryptToPlainPacket(sharedKey32, frame, frameLen, plain);
    if (plainLen < 7 || !verifyPlainCrc16(plain, plainLen)) {
        return false;
    }

    uint8_t opcode = plain[0];
    uint32_t nowEpoch = getCurrentEpochSeconds();

    if (opcode == PKT_OPEN_SESSION && plainLen == OPEN_SESSION_SIZE) {
        uint32_t lastSyncedTs = readU32LE(plain + 1);
        uint16_t seqNum       = readU16LE(plain + 5);
        // PHASE F-A Task 3: OPEN_SESSION resets cmdSeq/cmdSeqValid from its own seq
        resetCmdSeqOnOpenSession(seqNum, cmdReplayState);
        startSync(lastSyncedTs);
        return true;
    }
    if (opcode == PKT_HISTORY_ACK && plainLen == HISTORY_ACK_SIZE) {
        uint16_t ackChunkId = readU16LE(plain + 1);
        uint8_t  status     = plain[3];
        if (syncActive && waitingForChunkAck && ackChunkId == currentChunkId && status == 0) {
            waitingForChunkAck = false;
            chunkRetryCount = 0;
            if (currentChunkId >= totalSyncChunks) {
                if (timeTrusted) {
                    clearFlashHistory();
                } else {
                    syncActive = false;
                }
            } else {
                currentChunkId++;
            }
        }
        return true;
    }
    if (opcode == PKT_HEARTBEAT && plainLen == HEARTBEAT_SIZE) {
        uint32_t hbTs   = readU32LE(plain + 1);
        uint16_t seqNum = readU16LE(plain + 5);
        CommandRejectReason rej = validateAuthenticatedCommand(
            PKT_HEARTBEAT, hbTs, seqNum, timeTrusted, (ALLOW_REMOTE_CLEAR == 1), nowEpoch, cmdReplayState
        );
        if (rej != CMD_ACCEPT) {
            Serial.printf("[CMD] rejected HEARTBEAT: %s\n", rejectReasonToString(rej));
            return false;
        }
        // Display-only phone bootstrap when !timeTrusted (NEVER sets timeTrusted)
        if (!timeTrusted && hbTs > MIN_TRUSTED_EPOCH) {
            phoneBootstrapEpoch = hbTs;
            phoneBootstrapMs    = millis();
        }
        return true;
    }
    if (opcode == PKT_CLEAR_FLASH && plainLen == CLEAR_FLASH_SIZE) {
        uint32_t upToTs = readU32LE(plain + 1);
        uint8_t  mode   = plain[5];
        uint16_t seqNum = readU16LE(plain + 6);
        (void)mode;
        CommandRejectReason rej = validateAuthenticatedCommand(
            PKT_CLEAR_FLASH, upToTs, seqNum, timeTrusted, (ALLOW_REMOTE_CLEAR == 1), nowEpoch, cmdReplayState
        );
        if (rej != CMD_ACCEPT) {
            Serial.printf("[CMD] rejected CLEAR_FLASH: %s\n", rejectReasonToString(rej));
            return false;
        }
        clearFlashHistory();
        return true;
    }
    if (opcode == PKT_SET_EMERGENCY && plainLen == SET_EMERGENCY_SIZE) {
        uint8_t  slot   = plain[1];
        uint8_t  numLen = plain[2];
        uint16_t seqNum = readU16LE(plain + 19);
        CommandRejectReason rej = validateAuthenticatedCommand(
            PKT_SET_EMERGENCY, nowEpoch, seqNum, timeTrusted, (ALLOW_REMOTE_CLEAR == 1), nowEpoch, cmdReplayState
        );
        if (rej != CMD_ACCEPT) {
            Serial.printf("[CMD] rejected SET_EMERGENCY: %s\n", rejectReasonToString(rej));
            return false;
        }
        if (slot <= 1 && numLen <= 16) {
            char numBuf[17] = {0};
            memcpy(numBuf, plain + 3, numLen);
            numBuf[numLen] = '\0';
            if (slot == 0) {
                strncpy(emergencySlot0, numBuf, sizeof(emergencySlot0) - 1);
                nvsPrefs.putString("emg0", emergencySlot0);
            } else {
                strncpy(emergencySlot1, numBuf, sizeof(emergencySlot1) - 1);
                nvsPrefs.putString("emg1", emergencySlot1);
            }
        }
        return true;
    }
    return false;
}

void onMqttMessage(char* topic, byte* payload, unsigned int length) {
    if (strcmp(topic, topicCmd) == 0 && payload != nullptr && length > 0) {
        handleIncomingCommandFrame(payload, static_cast<size_t>(length));
    }
}

// ============================================================================
// TELEMETRY BUILDER & PUBLISHER (PHASE F-A Task 2)
// ============================================================================
bool buildCurrentTripPoint(TripPoint& outPt, bool& outIsRealGps) {
    uint32_t nowEpoch = getCurrentEpochSeconds();
    bool hasGpsFix = gps.location.isValid() && gps.location.age() < 5000 && gps.satellites.value() > 0;

    // PHASE F-A Task 2: isRealGps requires both a valid GPS fix and timeTrusted == true
    outIsRealGps = hasGpsFix && timeTrusted;

    if (hasGpsFix && !timeTrusted) {
        uint32_t nowMs = millis();
        if (lastUntrustedTimeLogMs == 0 || (nowMs - lastUntrustedTimeLogMs) >= 30000UL) {
            lastUntrustedTimeLogMs = nowMs;
            Serial.println("[TIME] untrusted - not logging");
        }
    }

    if (hasGpsFix) {
        outPt.timestamp     = nowEpoch;
        outPt.lat_e7        = static_cast<int32_t>(gps.location.lat() * 10000000.0);
        outPt.lon_e7        = static_cast<int32_t>(gps.location.lng() * 10000000.0);
        float spdKmh        = gps.speed.isValid() ? static_cast<float>(gps.speed.kmph()) : 0.0f;
        if (spdKmh < 1.5f) spdKmh = 0.0f;
        outPt.speed_decideg = static_cast<uint16_t>(spdKmh * 10.0f);
        uint16_t courseDeg  = gps.course.isValid() ? (static_cast<uint16_t>(gps.course.deg()) % 360) : 0;
        outPt.heading_div2  = static_cast<uint8_t>(courseDeg / 2);

        if (spdKmh >= 3.0f) {
            lastMovingMs = millis();
            if (!isRiding) {
                isRiding = true;
                tripStartPending = true;
            }
        }
        uint8_t sats = static_cast<uint8_t>(gps.satellites.value());
        outPt.flags = packFlags(isRiding, tripStartPending, false, sats);
        tripStartPending = false;
        return true;
    }

#if INDOOR_TABLE_FALLBACK == 1
    outPt.timestamp     = nowEpoch;
    outPt.lat_e7        = FALLBACK_LAT_E7;
    outPt.lon_e7        = FALLBACK_LON_E7;
    outPt.speed_decideg = 0;
    outPt.heading_div2  = 0;
    outPt.flags         = packFlags(true, false, false, 0); // satCount == 0
    return true;
#else
    return false;
#endif
}

void publishSosEvent(uint8_t reason, uint8_t state) {
    TripPoint pt;
    bool isReal = false;
    if (!buildCurrentTripPoint(pt, isReal)) {
        pt.timestamp     = getCurrentEpochSeconds();
        pt.lat_e7        = 0;
        pt.lon_e7        = 0;
        pt.speed_decideg = 0;
        pt.heading_div2  = 0;
        pt.flags         = 0;
    }
    uint8_t plain23[SOS_EVENT_SIZE];
    encodeSosEventPlain(pt, reason, state, sosSeqNum++, plain23);
    publishEncryptedPacket(topicEvent, plain23, SOS_EVENT_SIZE, false);
}

void startSosCountdown(uint8_t reason) {
    if (sosCountdownActive) return;
    sosCountdownActive = true;
    sosReason          = reason;
    sosStartMs         = millis();
    publishSosEvent(reason, 1); // state=1: countdown started
}

// ============================================================================
// WI-FI & MQTT CONNECTION MANAGEMENT (PHASE F-A Task 4)
// ============================================================================
void ensureWifiConnected() {
    if (WiFi.status() == WL_CONNECTED) return;
    uint32_t now = millis();
    if (lastWifiRetryMs == 0 || (now - lastWifiRetryMs) >= 10000UL) {
        lastWifiRetryMs = now;
        WiFi.mode(WIFI_STA);
        WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    }
}

void ensureMqttConnected() {
    if (WiFi.status() != WL_CONNECTED) return;
    checkNtpTimeTrust();

#if VERIFY_TLS == 1
    if (strstr(EMQX_CA_PEM, "PASTE_YOUR") != nullptr) {
        uint32_t nowMs = millis();
        if (lastCaMissingLogMs == 0 || (nowMs - lastCaMissingLogMs) >= 10000UL) {
            lastCaMissingLogMs = nowMs;
            Serial.println("[TLS] CA certificate missing");
        }
        return;
    }
#endif

    if (mqtt.connected()) return;
    uint32_t now = millis();
    if (lastMqttRetryMs != 0 && (now - lastMqttRetryMs) < 5000UL) return;
    lastMqttRetryMs = now;

    char clientId[36];
    snprintf(clientId, sizeof(clientId), "SafarESP32_%s", topicId);

    bool connected = false;
    if (strlen(SAFAR_MQTT_USER) > 0) {
        connected = mqtt.connect(
            clientId,
            SAFAR_MQTT_USER,
            SAFAR_MQTT_PASS,
            topicPresence,
            1,
            true,
            "0"
        );
    } else {
        connected = mqtt.connect(
            clientId,
            topicPresence,
            1,
            true,
            "0"
        );
    }

    if (connected) {
        digitalWrite(STATUS_LED_PIN, HIGH);
        mqtt.publish(topicPresence, reinterpret_cast<const uint8_t*>("1"), 1, true);
        mqtt.subscribe(topicCmd, 1);

        TripPoint pt;
        bool isReal = false;
        if (buildCurrentTripPoint(pt, isReal)) {
            uint8_t plain21[LIVE_TELEMETRY_SIZE];
            encodeLiveTelemetryPlain(liveSeqNum++, pt, plain21);
            publishEncryptedPacket(topicStatus, plain21, LIVE_TELEMETRY_SIZE, true);
        }
    } else {
        digitalWrite(STATUS_LED_PIN, LOW);
    }
}

// ============================================================================
// ARDUINO SETUP & LOOP
// ============================================================================
void setup() {
    Serial.begin(115200);
    pinMode(STATUS_LED_PIN, OUTPUT);
    pinMode(SOS_CANCEL_PIN, INPUT_PULLUP);
    digitalWrite(STATUS_LED_PIN, LOW);

    // PHASE F-A Task 4: Print active configuration flags once at boot
    Serial.printf(
        "[CFG] release=%d tls=%d demo=%d indoor=%d\n",
        RELEASE_BUILD,
        VERIFY_TLS,
        DEMO_FAKE_GPS,
        INDOOR_TABLE_FALLBACK
    );

    // 1. Load 32-byte ChaCha20-Poly1305 key & derive 16-hex SHA-256 topic ID
    SafarCrypto::hexToBytes(SAFAR_SHARED_SECRET_HEX, sharedKey32, 32);
    SafarCrypto::deriveTopicId(SAFAR_NOSTR_PUBKEY_HEX, topicId);

    snprintf(topicStatus,   sizeof(topicStatus),   "safar/v1/%s/status",   topicId);
    snprintf(topicLive,     sizeof(topicLive),     "safar/v1/%s/live",     topicId);
    snprintf(topicHist,     sizeof(topicHist),     "safar/v1/%s/hist",     topicId);
    snprintf(topicCmd,      sizeof(topicCmd),      "safar/v1/%s/cmd",      topicId);
    snprintf(topicEvent,    sizeof(topicEvent),    "safar/v1/%s/event",    topicId);
    snprintf(topicPresence, sizeof(topicPresence), "safar/v1/%s/presence", topicId);

    // 2. Mount LittleFS & NVS Preferences
    fsMounted = LittleFS.begin(true);
    nvsPrefs.begin("safar", false);
    String s0 = nvsPrefs.getString("emg0", "");
    String s1 = nvsPrefs.getString("emg1", "");
    strncpy(emergencySlot0, s0.c_str(), sizeof(emergencySlot0) - 1);
    strncpy(emergencySlot1, s1.c_str(), sizeof(emergencySlot1) - 1);

    // 3. Init GPS UART1 (RX=16, TX=17) & MPU6050 I2C (SDA=21, SCL=22)
    gpsSerial.begin(9600, SERIAL_8N1, GPS_RX_PIN, GPS_TX_PIN);
    Wire.begin(I2C_SDA_PIN, I2C_SCL_PIN);
    if (mpu.begin() == 0) {
        mpuConnected = true;
        mpu.calcOffsets(true, true);
        mpu.update();
        refAx = mpu.getAccX();
        refAy = mpu.getAccY();
        refAz = mpu.getAccZ();
    }

    // 4. Configure Wi-Fi, NTP, and TLS MQTT
    ensureWifiConnected();
    configTime(0, 0, "pool.ntp.org", "time.google.com");

#if VERIFY_TLS == 1
    tlsClient.setCACert(EMQX_CA_PEM);
#else
    tlsClient.setInsecure();
#endif
    mqtt.setServer(SAFAR_MQTT_HOST, SAFAR_MQTT_PORT);
    mqtt.setBufferSize(1024);
    mqtt.setCallback(onMqttMessage);
}

void loop() {
    // 1. Feed GPS NMEA parser
    while (gpsSerial.available() > 0) {
        gps.encode(static_cast<char>(gpsSerial.read()));
    }

    // 2. Update MPU6050
    if (mpuConnected) {
        mpu.update();
    }

    // 3. Maintain Wi-Fi & MQTT connection
    ensureWifiConnected();
    ensureMqttConnected();
    if (mqtt.connected()) {
        mqtt.loop();
    }

    // 4. Progress stop-and-wait History Chunk Sync if active (PHASE F-A Task 6: 10000ms timeout, max 3 retries)
    uint32_t now = millis();
    if (syncActive && mqtt.connected()) {
        if (!waitingForChunkAck) {
            sendCurrentHistoryChunk();
        } else if (now - lastChunkSentMs >= HISTORY_ACK_TIMEOUT_MS) {
            if (chunkRetryCount < HISTORY_MAX_RETRIES) {
                chunkRetryCount++;
                sendCurrentHistoryChunk();
            } else {
                syncActive = false;
                waitingForChunkAck = false;
                chunkRetryCount = 0;
            }
        }
    }

    // 5. 1 Hz Live Telemetry & Offline Flash Logging
    if (now - lastTelemetryMs >= 1000UL) {
        lastTelemetryMs = now;
        TripPoint pt;
        bool isRealGps = false;
        if (buildCurrentTripPoint(pt, isRealGps)) {
            if (isRealGps && isRiding) {
                appendPointToFlash(pt, isRealGps);
            }
            if (mqtt.connected()) {
                uint8_t plain21[LIVE_TELEMETRY_SIZE];
                encodeLiveTelemetryPlain(liveSeqNum++, pt, plain21);
                publishEncryptedPacket(topicLive, plain21, LIVE_TELEMETRY_SIZE, false);

                if (now - lastStatusPubMs >= 10000UL) {
                    lastStatusPubMs = now;
                    publishEncryptedPacket(topicStatus, plain21, LIVE_TELEMETRY_SIZE, true);
                }
            }
        }
    }

    // 6. Crash / SOS Countdown state machine
    if (sosCountdownActive) {
        if (digitalRead(SOS_CANCEL_PIN) == LOW) {
            sosCountdownActive = false;
            publishSosEvent(sosReason, 2); // state=2: Cancelled by rider
        } else if (now - sosStartMs >= 15000UL) {
            sosCountdownActive = false;
            publishSosEvent(sosReason, 3); // state=3: SOS fired after 15s
        }
    }

#if RELEASE_BUILD == 0
    if (Serial.available() > 0) {
        char ch = static_cast<char>(Serial.read());
        if (ch == 's') {
            startSync(0);
        } else if (ch == 'f') {
            clearFlashHistory();
        } else if (ch == 'c') {
            startSosCountdown(1);
        }
    }
#endif
}
