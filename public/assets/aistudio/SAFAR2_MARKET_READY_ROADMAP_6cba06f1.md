# SAFAR 2.0 — Market-Ready Roadmap (Phases 1–5) & Agent Handoff
**Revision date:** 2026-11-12
**Relationship to other docs:** `SAFAR2_ARCHITECTURE_UPDATE.md` stays the source of truth for the architecture and wire protocol. **This file is the source of truth for scope, order and acceptance criteria of the remaining work.** If this file proposes a protocol addition (section 9) it is *PROPOSED* until frozen in the architecture doc.

---

## 0. How to use this document (humans and AI agents)
1. Work **phase by phase**. Do not start a phase before the previous phase's Definition of Done (DoD) is met.
2. Every task has an ID (`P1-A3`, `P2-U1`, …) and **acceptance criteria (AC)**. A task is done only when its AC is verified, not when the code compiles.
3. One task = one change set. Touch only the files the task needs. UI tasks must not change networking/crypto code and vice versa.
4. **Global rules (unchanged):**
   - Package is `com.example.safar20`.
   - Never reintroduce Supabase, Nostr, UDP hole punching, or hardcoded keys/credentials.
   - One shared `SafarViewModel`/`SafarRepository`.
   - Real unit conversion (`* 0.621371`), theme/units persisted in DataStore.
   - Use only existing `Color.kt` values and the UiKit design system; no new hex values without updating `DESIGN_SYSTEM.md`.
   - **No Room schema change** unless a task says so. If one does: bump the version, write a real migration, turn on `exportSchema`, and add a migration test. Never rely on `fallbackToDestructiveMigration` once users have data.
   - Never log keys, passwords or raw payloads.
   - No `curl`/shell commands in prompts meant for the Windows Android Studio agent.
5. If something is unclear or conflicts with code, ask the project owner. Do not guess.

## 1. What "market-ready" means here
The product already *works*. It does not yet *feel* like a product. The gap is in four places:

| Pillar | Today | Target |
|---|---|---|
| **Reliability** | Works on the happy path. Some numbers are wrong (distance jitter, ODO resets), duplicate connections are possible, time is not trusted. | Correct numbers, stable connection, survives network drops, reboots and power cuts. |
| **First 5 minutes** | User must type broker URI/user/password and understand states. | Camera tap to pair. No technical term visible. Every state explains itself and offers an action. |
| **Rider features** | Live speed, map, ride list. | Ride detail, export/share, navigate to bike, emergency contact + SOS flow (the headline feature). |
| **Hardware feel** | Keys/Wi-Fi compiled into the firmware. | One-command provisioning, status LED/buzzer, watchdog, OTA, device health. |

**Honest scope:** Phases 1–5 take the project to *beta-ready and evaluation-ready*. Real market launch also needs items listed in section 11 (power design, enclosure, certifications, per-device credentials, push alerts). Present those as the production roadmap.

## 2. Verified baseline (2026-11-12)
**Working (verified in code):** MQTT over TLS to EMQX; E2E ChaCha20-Poly1305 (golden vector tested both sides); live telemetry + retained status + presence; history sync with stop-and-wait ACK and delete-after-ACK; trip stitching and de-duplication in Room; centralized connect logic (foreground + network aware); link/connection states in the header pill; broker settings with TEST CONNECTION; hidden Developer Options; demo rides with `DEMO_` prefix; QR pairing with validation; custom launcher icon; `allowBackup=false`; unit tests for crypto/QR/replay/stitching.
**ESP32 firmware (verified by host tests):** non-blocking tasks (core 0 network / core 1 sensors), trip state machine, RAM→flash batch logger, history sender, authenticated commands, crash-SOS state machine (GSM actions stubbed), GPS diagnostics, indoor fallback (never logged).
**Known issues:** listed per phase below (they are the Phase 1 tasks).

---

## 3. PHASE 1 — Rock solid (target 2–3 days)
**Goal:** numbers are correct, the connection is stable, time is trustworthy, and problems can be diagnosed.

### App tasks
| ID | Task | Acceptance criteria |
|---|---|---|
| P1-A1 | `minSdk` 24 → **28** (ChaCha20-Poly1305 needs API 28). | Build passes. On API 28+ crypto works; no silent failure path remains for API < 28. |
| P1-A2 | `MqttTelemetrySource.start()` must return if already connected **or a connect/reconnect is in progress**. Close the old `MqttClient` before creating a new one. `stop()` ends with `scope.cancel()`. `connectIfReady` must never trigger a second `start()` while state is Connecting. | Toggle airplane mode 10× quickly and switch foreground/background: exactly one client connection exists at any time (broker "Clients" page shows one app client). No duplicate history ingestion. |
| P1-A3 | Process incoming MQTT messages through **one sequential consumer** (Channel + single coroutine). | Live, status and history frames are handled strictly in arrival order (unit test with a fake source). |
| P1-A4 | Remove the default broker URI `ssl://broker.hivemq.com:8883` (default is empty). Remove fallback topics built from `deviceId`; only the pub-key-hash topics remain. | Fresh install has empty broker config; grep finds no `hivemq` and no raw-deviceId topic. |
| P1-A5 | One shared `TripMath.distanceKm(points)` used by `buildTripSummary` **and** live distance. Ignore a segment if: both points have speed 0, **or** the segment is < 3 m, **or** implied speed > 250 km/h. | Unit tests: 5 minutes of standstill jitter adds ≈ 0 km; a GPS jump is ignored; a normal ride matches the expected distance within 1%. |
| P1-A6 | Live stats: reset TRIP distance, AVG and MAX on a trip-start flag or new connection. AVG = mean of moving speeds (> 1.5 km/h). | Unit tests for reset and average. |
| P1-A7 | **ODO = lifetime distance** of the selected device = `SUM(distance_km)` of its saved trips (new DAO Flow, no schema change) + current live trip. Survives restart. | Kill and reopen the app: ODO unchanged. Demo trips (`DEMO_`) are excluded. |
| P1-A8 | If a live point has `satCount == 0`, show **"LIVE · NO GPS FIX"** (RoyalBrass) and keep the last real marker; green LIVE only with a real fix. | With firmware indoor fallback on, the app never shows plain green LIVE for the fake location. |
| P1-A9 | Fix every garbled sequence (`â€¢`, `â€”`, `â€™`) in `.kt`/`.xml`. | `grep "â€"` over the project returns nothing; labels render correctly on device. |
| P1-A10 | `AppLog`: ring buffer of the last 500 lines persisted in `filesDir`; global uncaught-exception handler appends stack traces; Settings → ABOUT → **EXPORT DIAGNOSTICS** shares the file via FileProvider. Logs connection changes, errors, sync start/finish, ride start/end. | A crash and a connection failure both appear in the exported file. A scan of the file finds no keys/passwords/payload bytes. |
| P1-A11 | Move Room from `2.7.0-alpha11` to the latest **stable** only if the build and tests stay green; otherwise skip and note why. | Build green or documented skip. |
| P1-A12 | Update `AGENTS.md`, `PRD.md`, `API_SPEC_AND_DB_SCHEMA.md`, `DESIGN_SYSTEM.md`: remove Nostr, UDP hole punching, "serverless P2P"; describe "edge storage + relay-only MQTT server, end-to-end encrypted". | grep for `nostr|hole|serverless|P2P` in `docs/` finds only intentional historical notes. |

### Firmware tasks
| ID | Task | Acceptance criteria |
|---|---|---|
| P1-F1 | Add `timeTrusted` (true only after real GPS time **or** NTP). `isRealGps` requires `timeTrusted`. The fake fallback epoch is used **only** for display, never for logging. | With GPS fix but no time source yet, nothing is written to flash. After time arrives, logging starts. Points are never dated 2025. |
| P1-F2 | Restore freshness/replay checks, **only when `timeTrusted`**: HEARTBEAT timestamp within ±600 s; CLEAR_FLASH timestamp within ±120 s; both require `(int16_t)(seq - cmdSeq) > 0`. | Host tests: stale heartbeat rejected, replayed seq rejected, stale CLEAR_FLASH ignored, fresh ones accepted. |
| P1-F3 | Release-safe defaults via a single `RELEASE_BUILD` flag: `VERIFY_TLS 1`, `STREAM_ON_BOOT 0`, `DEMO_FAKE_GPS 0`, `INDOOR_TABLE_FALLBACK 0`. CA certificate pasted in `secrets.h`. | With `RELEASE_BUILD 1` the firmware refuses to connect without a valid CA and never sends fallback coordinates. |
| P1-F4 | Keep the host test harness in `firmware/test/` (crypto vs reference vectors, sync, logger, trip FSM, commands) and run it before every firmware release. | `ALL TESTS PASSED` including sanitizers. |

**Phase 1 DoD:** all ACs verified; a 30-minute soak (app open, network toggled, tracker reset twice) produces no duplicate connections, wrong distance or lost session; exported diagnostics are readable; docs updated.

---

## 4. PHASE 2 — Zero-tech UX (target 3–4 days)
**Goal:** a new user pairs with a camera tap and never sees a technical term. Time from install to first live position ≤ 3 minutes.
**Dependency:** P2-U1 needs QR v2, which the provisioning tool (P5-H1) generates. Build the tool's QR output first, or hand-craft test QRs.

| ID | Task | Acceptance criteria |
|---|---|---|
| P2-U1 | **QR v2 carries the broker.** `safar://pair?v=2&id=…&name=…&pub=…&sec=…&sim=%2B91…&broker=ssl://host:8883&bu=…&bp=…`. App applies broker config automatically; v1 QRs (no broker) still work and fall back to existing Settings values. The Broker card moves to **Settings → Advanced**. *Security note:* credentials inside a QR are acceptable for the prototype only; production uses per-device credentials (section 11). | Scanning one QR on a fresh install leads to a connected tracker without opening Settings. |
| P2-U2 | **First-run onboarding** (max 3 screens, skippable): what SAFAR does; why camera access is needed (shown *before* the system dialog); "Pair your tracker". Permanent-deny path shows "Open settings". | Fresh install shows onboarding once; permission denial never dead-ends. |
| P2-U3 | **Pairing wizard** with a live checklist: ① QR scanned ② Broker reachable ③ Tracker online ④ GPS fix. Each step shows spinner / ✓ / ✗ with a plain-language hint and Retry. Step ③ times out after 60 s with causes ("power", "Wi-Fi/SIM", "wrong key"). | Demo: wizard passes end-to-end; each failure mode (bad broker, tracker off, wrong secret, no GPS) produces the right hint. |
| P2-U4 | **State message catalog** (below) implemented in one place. | Every `ConnectionState` maps to text + optional action; no raw exception text anywhere. |
| P2-U5 | Empty states and first-ride guidance; Trips supports pull-to-refresh = "Sync now" (`requestHistorySync`) with progress. | New user sees guidance, not blank screens; pull-to-refresh shows sync % and ends cleanly. |
| P2-U6 | Settings simplified: Appearance, Units, Trackers (rename/delete), Emergency (P3), About, **Advanced** (broker, TEST CONNECTION). Developer Options stay hidden (7 taps). | A non-technical user never encounters a broker field unless they open Advanced. |
| P2-U7 | Error policy: Snackbar for transient errors, banner for persistent ones, exponential backoff for retries, offline banner when there is no internet. | Airplane-mode test shows the banner and recovers automatically. |

**State message catalog (P2-U4)**

| State | Message | Action |
|---|---|---|
| NO_DEVICE | "Pair your tracker to start" | PAIR |
| BROKER_NOT_CONFIGURED | "Setup incomplete" | Open Advanced |
| NO INTERNET | "Waiting for internet" | — (auto) |
| CONNECTING | "Connecting…" | — |
| BROKER_ERROR | "Can't reach the relay: <plain reason>" | Retry / Check settings |
| WAITING_FOR_TRACKER | "Tracker isn't responding. Is it powered and online?" | Retry / Help |
| LIVE (no fix) | "Tracker is searching for satellites. Move to open sky." | — |
| LIVE | "Live" | — |
| SYNCING | "Syncing rides… 72%" | — |
| PARKED | "Parked · last seen 10 min ago" | Navigate to bike |

**Phase 2 DoD:** a person who has never seen the app pairs a tracker in the demo without help and without typing; all states reproduced and readable.

---

## 5. PHASE 3 — Rider features (target 4–5 days)
**Goal:** features a rider expects, and the SOS flow that differentiates the product.

| ID | Task | Acceptance criteria |
|---|---|---|
| P3-R1 | **Ride detail screen** from `RideTicketCard`: map with route polyline (CafeCopper) and start/end markers; stats grid (distance, duration, moving time, avg, max); **speed-over-time chart** drawn with Canvas (no new dependency). | Opens for any ride; units follow the unit setting; works offline (map tiles may be missing but stats/chart show). |
| P3-R2 | **GPX 1.1 export + share** via FileProvider and share intent (time, lat, lon, speed). | File opens in a third-party GPX viewer; coordinates match the ride. |
| P3-R3 | Rename and delete a ride (confirm dialog). Names stored in DataStore as `tripId → name` (no schema change). | Rename survives restart; delete removes summary and points. |
| P3-R4 | **Navigate to bike** (Home and Parked state): opens Google Maps navigation to the last known position; **Share bike location** shares a maps link. Uses the last *real* fix, never fallback coordinates. | Intent works with and without Google Maps installed (falls back to a generic `geo:` intent). |
| P3-R5 | **Emergency contacts** (up to 2: name + number) stored in DataStore; Settings → Emergency. App sends them to the tracker with `SET_EMERGENCY` (section 9); tracker stores them in NVS and uses them in `triggerGsmSos()` when the SIM module exists. | Contact set in app is present on the tracker after reboot (confirmed in `info`). Numbers validated (E.164-like). |
| P3-R6 | **SOS event in the app:** tracker publishes `SOS_EVENT` (countdown started / cancelled / fired). App shows a full-screen alert with map, "Call contact", "Open in Maps". Until the SIM arrives the tracker's fire action is a stub, and the UI labels it clearly in dev builds. | Triggering a crash on the bench shows the alert within 3 s while the app is open. |
| P3-R7 | Ride-complete summary card after a sync ("Ride complete · 18.4 km · max 78 km/h"). | Appears once per finished ride. |
| P3-R8 *(optional)* | Background monitoring via a foreground service with a persistent notification. **Risks to check first:** Android 14/15 foreground-service type rules and time limits, battery impact, user consent. Do not promise push alerts without a server. | Decision recorded before any code is written. |

**Phase 3 DoD:** a rider can review, share and export a ride, find the bike, set an emergency contact, and see an SOS alert end-to-end on the bench.

---

## 6. PHASE 4 — Premium feel (target 3 days)
**Goal:** the app feels polished, local and accessible.

| ID | Task | Acceptance criteria |
|---|---|---|
| P4-V1 | **Motion:** consistent screen transitions; odometer digit roll; needle spring (exists) tuned; crossfade for pill state changes; skeleton loaders for lists. | No abrupt state jumps; animations respect the system "reduce motion" setting. |
| P4-V2 | **Haptics:** pairing success, ride end, SOS alert (strong). | Works on devices with a vibrator; silent otherwise. |
| P4-V3 | **Localization:** all user-facing text through `strings.xml` for **English, Hindi, Marathi**. Verify Devanagari rendering for the display fonts (Rajdhani has Devanagari; check Bebas Neue/Inter fallbacks). | Switching device language updates the entire app; no clipped text; no hardcoded user strings (lint/grep check). |
| P4-V4 | **Accessibility:** `contentDescription` on every icon/button; touch targets ≥ 48 dp; layouts survive 130% font scale; text contrast ≥ 4.5:1 in dark and light themes; TalkBack reads the gauge ("Speed 42 kilometres per hour"). | Accessibility Scanner reports no critical issues on the main screens. |
| P4-V5 | **Landscape/tablet:** Home in landscape = gauge left, map right; wide screens cap content width. | No overlap or cut-off at 360×640 dp through 840×1280 dp. |
| P4-V6 | Android 12+ splash theme and themed (monochrome) icon aligned with `IgnitionSplash`. | No white flash at launch. |
| P4-V7 | **Performance:** cold start target < 2 s on a mid-range phone; no jank on Home during 1 Hz updates (stable state models, no needless recomposition); map tile cache configured. | Measured and recorded; no dropped-frame spikes in a 5-minute live session. |
| P4-V8 | Light-theme parity pass across all screens. | Screenshot review of every screen in both themes. |

**Phase 4 DoD:** side-by-side screenshots (dark/light, English/Hindi) of all screens reviewed; accessibility scan clean; startup and live-session performance recorded.

---

## 7. PHASE 5 — Firmware product-grade (target 4–5 days)
**Goal:** the tracker behaves like a product: provisioned in minutes, self-recovering, observable, updatable.

| ID | Task | Acceptance criteria |
|---|---|---|
| P5-H1 | **Provisioning tool** (`tools/provision.py`, one command per device): generates `pub` (32 random bytes → 64 hex) and `sec` (32 random bytes → 64 hex) with a CSPRNG; computes `DEV_ID = SHA-256(pub hex string as UTF-8)` first 16 hex; fills `secrets.h` from a local config; writes the **QR v2 PNG** and a printable label; appends the record to a private `devices.csv`. Secrets and generated files are git-ignored. | New tracker flashed + QR printed in < 2 minutes. The generated QR pairs in the app and the topics match with no manual hashing. |
| P5-H2 | **On-device Wi-Fi setup** (no recompile): hold the SOS button 5 s at boot → AP mode `SAFAR-XXXX` with a small web form for SSID/password stored in NVS. | Changing Wi-Fi needs no laptop. (The SIM module replaces this later.) |
| P5-H3 | **Watchdog and recovery:** task watchdog on both tasks; reboot if the network task is stuck > 30 s; exponential backoff with jitter for Wi-Fi/MQTT; brownout handling; last reset reason stored. | Soak test: pull Wi-Fi, restart the broker, power-cycle the router; the tracker recovers by itself every time. |
| P5-H4 | **Power-loss-tolerant logging:** flush the RAM buffer every `min(256 points, 60 s)` while riding (updates ADR-03). If a backup-battery/voltage-sense input exists, also flush on power-fail. Verify flash wear with a 24 h soak. | Cutting power mid-ride loses ≤ 60 s of points. |
| P5-H5 | **Status LED + buzzer language** (pins/pattern table to be filled by the owner): boot chime, Wi-Fi connecting, MQTT connected, GPS fix, error, SOS countdown (existing), cancel confirmation. | Each state is distinguishable without a serial monitor. |
| P5-H6 | **Device info:** retained plaintext `…/info` (JSON: firmware version, reset reason, Wi-Fi RSSI, sats/HDOP, emergency slots, free flash). App shows a "Tracker health" card (also feeds P2-U3 step ④). | Card matches serial output; no secrets in the payload. |
| P5-H7 | **OTA update** over HTTPS with CA verification and SHA-256 check, triggered by an authenticated `OTA_REQUEST` (section 9) from Settings → "Update tracker". Rollback if the new image fails to boot. **Check first:** firmware size vs OTA partition size and the resulting LittleFS size (see risks). | Update from a GitHub release `.bin` succeeds; a bad hash or bad image is rejected and the old firmware keeps running. |
| P5-H8 *(hardware-dependent)* | **Ignition sense** input (12 V → 3.3 V isolated/divided) replaces the speed-based "riding" flag; ride ends 60 s after ignition off. | Bench test with a switch; documented wiring. |
| P5-H9 | **Crash SOS hardening:** thresholds tunable via NVS; every state transition logged; false-positive soak (pothole/speed-breaker drive) with no SOS; real GSM (`ATD` + `AT+CMGS`) wired in when the A7670C arrives, under the same MQTT layer. | Documented test drive; SOS fires only on a staged fall. |
| P5-H10 | **SIM path:** A7670C PPPoS under the existing MQTT layer; time from GPS or modem clock keeps `timeTrusted` valid. | Same app, same topics, tracker connected over cellular. |
| P5-H11 | **Tests:** host harness in CI; hardware soak list (24 h run, Wi-Fi drop, broker restart, power cut during flush, GPS lost/regained). | All pass before a release tag. |

**Phase 5 DoD:** two trackers provisioned from scratch with the tool; 24 h soak passes; OTA tested; SOS bench-tested; health card shows correct data.

---

## 8. Execution order and dependencies
1. **Phase 1** (everything). 2. **P5-H1 provisioning + QR v2** (small, unlocks Phase 2). 3. **Phase 2.** 4. **P3-R5/R6 (SOS flow) first**, then the rest of Phase 3. 5. **Phase 4.** 6. Remaining **Phase 5** items (H2–H11), with H3/H4 early because they affect reliability.
Agents must re-run the unit tests (app) and host tests (firmware) after every task.

## 9. PROPOSED protocol and QR additions (freeze before implementing)
All frames use the existing layout `opcode(1) | nonce(12) | ciphertext | tag(16)`, AAD = opcode, little-endian, random nonce. Firmware's command inner buffer (currently 32 bytes) must grow for `OTA_REQUEST`.

| Item | Direction | Inner payload | Notes |
|---|---|---|---|
| `0x07 SET_EMERGENCY` | app → tracker (`cmd`) | `slot(1) \| len(1) \| number(16, ASCII, zero padded) \| seq(2)` = 20 B | Fresh-seq check; tracker stores in NVS; reports slots in `info`. |
| `0x08 SOS_EVENT` | tracker → app (`event` topic) | `TripPoint(16) \| reason(1: 1=crash, 2=manual) \| state(1: 1=countdown, 2=cancelled, 3=fired) \| seq(2)` = 20 B | Sent 3× at 2 s intervals (publisher is QoS 0). |
| `0x09 OTA_REQUEST` | app → tracker (`cmd`) | `ts(4) \| sha256(32) \| urlLen(1) \| url(≤120) \| seq(2)` | Requires fresh `ts`, HTTPS URL, CA verify, hash check. |
| Topic `…/info` | tracker → app | plaintext JSON, retained | No secrets. |
| Topic `…/event` | tracker → app | encrypted frames | New subscription in the app. |
| QR v2 | printed on device | `safar://pair?v=2&id&name&pub&sec&sim&broker&bu&bp` | v1 stays valid. |

## 10. Cross-cutting checklists
**Security/privacy:** rotate every key/password that ever appeared in chat or commits; broker credentials per environment; Keystore-backed storage for the shared secret and broker password (optional task, ask first); no secrets in logs; short privacy policy (what is stored: rides on the phone, nothing on the server); a "delete my data" path (delete tracker + history exists).
**Test matrix:** phones on Android 9, 12 and 14; Wi-Fi and mobile data; airplane-mode toggles; broker down; tracker offline; GPS lost/regained; app killed and reopened mid-sync.
**Observability:** app diagnostics export (P1-A10); firmware serial log levels and `info` topic (P5-H6).

## 11. Out of scope for Phases 1–5 (production roadmap slide)
12 V power design (buck converter, protection, backup cell); waterproof enclosure and vibration testing; regulatory approvals for the LTE module and the product (e.g. BIS/WPC — verify the current requirements); per-device broker credentials and a self-hosted broker cluster; push alerts when the app is closed (needs a server or a foreground service, trade-offs in P3-R8); iOS app; fleet/admin dashboard; subscription/billing; theft-alert features.

## 12. Open decisions for the project owner
1. Evaluation date and target (college only, or Play Store/startup later)? This decides how deep Phases 5–6 go.
2. SIM module arrival date (decides when P5-H9/H10 start).
3. Languages beyond English/Hindi/Marathi?
4. APK distribution or Play Store (Phase 6 checklist differs)?
5. OTA partition trade-off: OTA needs two firmware slots. The default OTA scheme keeps ~1.5 MB for LittleFS, but the TLS build may not fit a ~1.2 MB slot; a larger-app scheme shrinks LittleFS to a few hundred KB (≈ hours of log, not days). Measure the firmware size first, then decide.
6. Is background monitoring (P3-R8) worth the battery and policy cost?

## Appendix: Phase 6 (release, not detailed here)
R8/minify with keep rules, signing, versioning, Play listing assets, privacy policy URL, 3-device regression, demo video, pitch deck (architecture in one line: *edge storage + relay-only, end-to-end encrypted server*).
