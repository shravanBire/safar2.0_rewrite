# AGENTS.md — AI Collaborator & Developer Master Guide for SAFAR 2.0

> **INSTRUCTION FOR AI AGENTS:** Read this file before modifying the project. SAFAR 2.0 uses **edge storage + relay-only MQTT server, end-to-end encrypted (ChaCha20-Poly1305)** + **Local RoomDB** + **Royal Enfield Retro-Moto UI**.

## 1. Core Architecture Rules
1. **Edge Storage + Relay-Only MQTT Server:** All trip points are stored on the ESP32 (`LittleFS`) and synced into the Android app's local `RoomDB` over TLS MQTT (`ssl://...:8883`) encrypted end-to-end with ChaCha20-Poly1305. The MQTT broker is an untrusted relay that never sees plaintext.
2. **Single Shared ViewModel:** `HomeScreen`, `TripsScreen`, and `SettingScreen` share `SafarViewModel` backed by `SafarRepository`.
3. **Accurate Unit Conversion & Trip Math:** Speed and distance conversions (`km/h` <-> `mph`) use `UnitConverter`, and all live/history distance and moving-average calculations use `TripMath`.
4. **MapLibre Reactive Updates:** Marker, camera, and route polylines update inside the `AndroidView` `update` block.
5. **Persist Settings & Diagnostics:** Theme, speed unit, and MQTT broker config persist in `PreferencesDataStore`; diagnostics persist in `AppLog` (`filesDir/safar_diagnostics.log`).

---

## 2. Android Package Structure (`com.example.safar20`)
```text
com.example.safar20/
├── data/
│   ├── local/
│   │   ├── SafarDatabase.kt          # RoomDatabase (@Database version = 2)
│   │   ├── TripMath.kt               # Shared Haversine, segment filter & moving average math
│   │   ├── AppLog.kt                 # 500-line persistent diagnostic ring buffer + FileProvider share
│   │   ├── UnitConverter.kt          # Metric/Imperial conversion utilities
│   │   ├── dao/                      # DeviceDao & TripDao
│   │   ├── entity/                   # DeviceEntity, TripSummaryEntity, LocationPointEntity
│   │   └── PreferencesDataStore.kt   # Persists Theme, Unit, and MQTT Broker Config
│   ├── network/
│   │   ├── MqttTelemetrySource.kt    # TLS 1.2+ MQTT client, sequential channel consumer, history sync
│   │   ├── ChaChaPolyCipher.kt       # RFC 7539 ChaCha20-Poly1305 AEAD + HKDF-SHA256
│   │   └── BinaryPacketCodec.kt      # Parses 16B TripPoint & 488B History Chunks + CRC16-CCITT
│   └── repository/
│       └── SafarRepository.kt        # Coordinates RoomDB, DataStore, QR pairing & TelemetrySource
├── ui/
│   ├── theme/                        # Retro-Moto Palette (CrankcaseBlack, RoyalBrass, CafeCopper)
│   ├── components/                   # TripperSpeedometer, TelemetryHeaderPill, RideTicketCard
│   └── screens/                      # home, trips, settings
├── viewModels/                       # SafarViewModel
└── MainActivity.kt
```