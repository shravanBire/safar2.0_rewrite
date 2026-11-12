/*
 * SAFAR 2.0 — Production ESP32 Telemetry & Crash SOS Firmware
 * Phase 1 Hardened:
 *   - P1-F1: Explicit bool timeTrusted (true only after valid GPS time or NTP; false on millis fallback)
 *   - P1-F2: Replay/freshness checks enforced when timeTrusted == true; AEAD + CRC16 always enforced
 *   - P1-F3: #define RELEASE_BUILD 0/1 (when 1, disables serial debug menu and never logs keys/qrUri)
 */

#include <Arduino.h>
#include "safar_types.h"
#include "safar_crypto.h"

#if __has_include("secrets.h")
#include "secrets.h"
#else
#include "secrets.h.example"
#endif

// P1-F3: Set RELEASE_BUILD to 1 for production motorcycles (disables serial menu & key/QR logging)
#ifndef RELEASE_BUILD
#define RELEASE_BUILD 0
#endif

// P1-F1: Time trust state
static bool     timeTrusted      = false;
static uint32_t trustedBaseEpoch = 0;
static uint32_t trustedBaseMs    = 0;

static uint8_t  derivedKey32[32];
static uint32_t bootSalt         = 0;
static uint32_t txCounter        = 0;
static uint16_t liveSeqNum       = 0;
static char     topicId[17]      = {0};
static char     topicCmd[40]     = {0};
static char     topicLive[40]    = {0};
static char     topicStatus[40]  = {0};
static char     topicSync[40]    = {0};

static ReplayGuardState rxReplayGuard;

// Update clock from GPS UTC epoch or modem NTP
void updateSystemEpochFromGpsOrNtp(uint32_t utcEpochSeconds) {
    if (utcEpochSeconds >= MIN_TRUSTED_EPOCH) {
        trustedBaseEpoch = utcEpochSeconds;
        trustedBaseMs    = millis();
        timeTrusted      = true;
    }
}

// Returns current epoch; sets timeTrusted = false if still on millis() fallback
uint32_t getCurrentEpochSeconds() {
    if (timeTrusted && trustedBaseEpoch >= MIN_TRUSTED_EPOCH) {
        return trustedBaseEpoch + ((millis() - trustedBaseMs) / 1000UL);
    }
    timeTrusted = false;
    return millis() / 1000UL;
}

// Verify and handle incoming encrypted command on safar/<topic_id>/cmd
bool handleIncomingCommandFrame(const uint8_t* frame, size_t frameLen) {
    if (frameLen < 28 || frameLen > 128) return false;

    uint8_t plaintext[100];
    uint8_t nonce12[12];
    size_t plainLen = frameLen - 28;

    // 1. Always require valid ChaCha20-Poly1305 AEAD tag with topicCmd as AAD
    if (!SafarCrypto::decryptFrame(
            derivedKey32,
            reinterpret_cast<const uint8_t*>(topicCmd),
            strlen(topicCmd),
            frame,
            frameLen,
            plaintext,
            nonce12)) {
        return false;
    }

    // 2. Always require valid inner CRC16-CCITT
    if (plainLen < 4) return false;
    uint16_t expectedCrc = calculateCrc16(plaintext, plainLen - 2);
    uint16_t receivedCrc = readU16BE(plaintext + plainLen - 2);
    if (expectedCrc != receivedCrc) {
        return false;
    }

    // 3. P1-F2: Enforce replay/freshness checks when timeTrusted is true
    uint32_t nowEpoch = getCurrentEpochSeconds();
    if (!verifyIncomingReplayAndFreshness(timeTrusted, nowEpoch, nonce12, rxReplayGuard)) {
        return false;
    }

    uint8_t opcode = plaintext[0];
    if (opcode == PKT_OPEN_SESSION && plainLen == 24) {
        uint32_t sinceTs = readU32BE(plaintext + 18);
        (void)sinceTs;
        // Trigger LittleFS history sync > sinceTs
        return true;
    } else if (opcode == PKT_HISTORY_ACK && plainLen == 6) {
        uint16_t chunkId = readU16BE(plaintext + 1);
        uint8_t status   = plaintext[3];
        (void)chunkId;
        (void)status;
        return true;
    } else if (opcode == PKT_HEARTBEAT && plainLen == 6) {
        return true;
    } else if (opcode == PKT_CLEAR_FLASH && plainLen == 8) {
        return true;
    }
    return false;
}

void setup() {
    Serial.begin(115200);
    bootSalt = esp_random();

    uint8_t rawSecret[32];
    SafarCrypto::hexToBytes(SAFAR_SHARED_SECRET_HEX, rawSecret, 32);
    SafarCrypto::deriveChaChaKey(rawSecret, derivedKey32);
    memset(rawSecret, 0, sizeof(rawSecret));

    SafarCrypto::deriveTopicId(SAFAR_NOSTR_PUBKEY_HEX, topicId);
    snprintf(topicCmd,    sizeof(topicCmd),    "safar/%s/cmd",    topicId);
    snprintf(topicLive,   sizeof(topicLive),   "safar/%s/live",   topicId);
    snprintf(topicStatus, sizeof(topicStatus), "safar/%s/status", topicId);
    snprintf(topicSync,   sizeof(topicSync),   "safar/%s/sync",   topicId);

#if RELEASE_BUILD == 0
    Serial.println("[SAFAR 2.0] DEV BUILD READY (Commands: c=crash test, s=sync test, f=flush)");
    Serial.print("[SAFAR 2.0] Topic ID: ");
    Serial.println(topicId);
#else
    Serial.println("[SAFAR 2.0] RELEASE BUILD READY");
#endif
}

void loop() {
#if RELEASE_BUILD == 0
    if (Serial.available() > 0) {
        char ch = static_cast<char>(Serial.read());
        if (ch == 'c') {
            Serial.println("[DEV] Simulated crash trigger");
        } else if (ch == 's') {
            Serial.println("[DEV] Simulated sync trigger");
        } else if (ch == 'f') {
            Serial.println("[DEV] Simulated flash flush");
        }
    }
#endif
    delay(50);
}
