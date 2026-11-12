# SAFAR 2.0 — Product Requirements Document (PRD) & SRS

## 1. Product Vision & Business Objective (BRD)
**SAFAR 2.0** is a **Smart Motorcycle Telemetry & Safety System** built on **edge storage + relay-only MQTT server, end-to-end encrypted**. All ride logs live on the tracker's flash memory (`LittleFS`) and sync directly into the rider's phone (`RoomDB`) through a lightweight TLS MQTT relay using ChaCha20-Poly1305 AEAD encryption.

### Business Model: BYOS (Bring Your Own SIM)
- Rider inserts a standard 4G SIM into the hardware unit.
- Rider scans the tracker's **QR Code** (`safar://pair?...`) in the SAFAR 2.0 Android App to pair.
- **Zero Cloud Database:** The MQTT relay only forwards encrypted binary frames; no cloud database stores user location history.

---

## 2. Competitor & Market Analysis
| Feature | Commercial Trackers | SAFAR 1.0 (Legacy) | SAFAR 2.0 |
| :--- | :--- | :--- | :--- |
| **Architecture** | Centralized cloud DB | Supabase prototype | **Edge storage + relay-only MQTT server, end-to-end encrypted** |
| **Offline Blind-Spot Logging** | Limited | None (Lost if offline) | **Flash ring-buffer in LittleFS** |
| **SOS Alert Delivery** | App push notification | Not implemented | **Direct GSM Call + SMS (Offline Ready)** |
| **UI Aesthetic** | Generic corporate UI | Basic Material3 Green | **Royal Enfield Retro-Moto Cockpit** |

---

## 3. Target User Personas & User Stories
- **Persona 1: Daily Commuter / College Rider** — Wants parked location persistence, lifetime odometer (`ODO`), and instant GSM call/SMS alerts on crash.
- **Persona 2: Weekend Highway Tourer** — Rides through zero-network mountain passes; wants offline points buffered in flash and synced into the Logbook when back online.

### Core User Stories
- **US-01 (QR Pairing):** Scan `safar://pair` QR code to pair a tracker in seconds.
- **US-02 (Live Cockpit):** View real-time speed on the brass Tripper speedometer, lifetime ODO, live trip stats, and GPS fix health (`LIVE`, `LIVE · NO GPS FIX`, `PARKED`).
- **US-03 (Ride-by-Ride History):** Browse rides by date with distance, moving average speed, max speed, and interactive MapLibre route polylines.
- **US-04 (App-Independent SOS):** Automatic GSM voice call and SMS with coordinates when the IMU detects a high-G impact followed by sustained tilt.

---

## 4. Software Requirements Specification (SRS) & Acceptance Criteria
1. **FR-01 (Device Pairing):** Scan QR URI (`device_id`, `pub`, `sec`, `sim`) and persist to local `RoomDB`.
2. **FR-02 (Encrypted Live Stream):** Stream 1 Hz 16-byte `TripPoint` packets over TLS MQTT (`safar/<sha256(pubkey)[0..15]>/live`) encrypted with ChaCha20-Poly1305.
3. **FR-03 (Flash Trip Sync):** Buffer offline points in `SRAM` + `LittleFS` and sync 30-point chunks (`0x03`) with per-chunk ACKs (`0x04`) into `RoomDB`.
4. **FR-04 (Unit Conversion & Trip Math):** Convert all speeds and distances between `km/h` and `mph` and filter stationary GPS jitter (`< 3m`) and impossible jumps (`> 250 km/h`).
5. **FR-05 (Autonomous Crash SOS):** Trigger a 15-second warning buzzer on $>3.5g$ impact + $>60°$ sustained tilt before placing an emergency GSM call and SMS.