# SAFAR 2.0 — Wire Protocol Specification & Local Database Schema

## 1. QR Code Pairing Payload Format
`safar://pair?id=SAFAR_01&name=Classic350&pub=<64_HEX_DEVICE_PUBKEY>&sec=<64_HEX_CHACHA20_KEY>&sim=+919876543210`

---

## 2. Binary Wire Protocol (ESP32 <-> Android App over TLS MQTT)
All application frames are encrypted end-to-end using **ChaCha20-Poly1305 (RFC 7539)** with a 12-byte nonce (`4B epoch || 4B boot_salt || 4B counter`), 16-byte Poly1305 tag, and topic string as AAD. Key is derived via `HKDF-SHA256(ikm = shared_secret, salt = "SAFAR2-v1", info = "safar2-chacha20-key", len = 32)`.

### MQTT Topics (`<topic_id> = hex(SHA256(pubKeyHex))[0..15]`)
- `safar/<topic_id>/cmd` — App -> ESP32 commands (`0x01`, `0x04`, `0x05`, `0x06`, `0x07`)
- `safar/<topic_id>/live` — ESP32 -> App live 1 Hz telemetry (`0x02`)
- `safar/<topic_id>/status` — ESP32 -> App retained last-known state (`0x02`)
- `safar/<topic_id>/sync` — ESP32 -> App history chunks (`0x03`)
- `safar/<topic_id>/presence` — ESP32 -> App LWT online/offline byte (`1` or `0`, retained)

### A. 16-Byte Packed `TripPoint` Struct
```c
struct __attribute__((packed)) TripPoint {
    uint32_t timestamp;     // Bytes 0..3  : Unix Epoch Seconds (UTC)
    int32_t  lat_e7;        // Bytes 4..7  : Latitude * 10,000,000
    int32_t  lon_e7;        // Bytes 8..11 : Longitude * 10,000,000
    uint16_t speed_decideg; // Bytes 12..13: Speed in km/h * 10
    uint8_t  heading_div2;  // Byte 14     : Course angle / 2
    uint8_t  flags;         // Byte 15     : Bit 0=Ignition, Bit 1=Start, Bit 2=End, Bits 3..7=Sats
}; // Total = 16 Bytes
```

### B. Packet Opcodes (Plaintext inside ChaCha20-Poly1305 envelope)
| Opcode | Name | Direction | Size | Description |
| :--- | :--- | :--- | :--- | :--- |
| `0x01` | `PKT_OPEN_SESSION` | App -> ESP32 | 24 Bytes | Reserved (18B) + `Last_Synced_TS` (4B) + CRC16 (2B) |
| `0x02` | `PKT_LIVE_TELEMETRY` | ESP32 -> App | 20 Bytes | Opcode (1B) + Seq_Num (2B) + TripPoint (16B) + CRC16 (2B) = 21B |
| `0x03` | `PKT_HISTORY_CHUNK` | ESP32 -> App | 488 Bytes | Opcode (1B) + Chunk_ID (2B) + Total_Chunks (2B) + Count (1B) + 30x TripPoint (480B) + CRC16 (2B) |
| `0x04` | `PKT_HISTORY_ACK` | App -> ESP32 | 6 Bytes | Opcode (1B) + Chunk_ID (2B) + Status (1B) + CRC16 (2B) |
| `0x05` | `PKT_HEARTBEAT` | Bidirectional | 6 Bytes | Opcode (1B) + Ping_ID (2B) + Status (1B) + CRC16 (2B) |
| `0x06` | `PKT_CLEAR_FLASH` | App -> ESP32 | 8 Bytes | Opcode (1B) + UpToTimestamp (4B) + Mode (1B) + CRC16 (2B) |

---

## 3. Android Local RoomDB Schema (`com.example.safar20.data.local`)

### Table 1: `devices` (`DeviceEntity.kt`)
- `device_id`: String (`@PrimaryKey`)
- `device_name`: String
- `nostr_pub_key`: String (64-hex device public identifier used to derive topic hash)
- `shared_secret_hex`: String (64-hex master secret)
- `sim_number`: String
- `last_synced_timestamp`: Long
- `last_lat`: Double?, `last_lon`: Double?, `last_speed`: Float?
- `is_selected`: Boolean

### Table 2: `trip_summaries` (`TripSummaryEntity.kt`)
- `trip_id`: String (`@PrimaryKey`)
- `device_id`: String
- `start_timestamp`: Long, `end_timestamp`: Long
- `distance_km`: Double, `duration_minutes`: Int
- `avg_speed_kmh`: Double, `max_speed_kmh`: Double

### Table 3: `location_points` (`LocationPointEntity.kt`)
- `id`: Long (`@PrimaryKey(autoGenerate = true)`)
- `trip_id`: String (Indexed)
- `device_id`: String (Indexed)
- `timestamp`: Long (Indexed)
- `latitude`: Double, `longitude`: Double
- `speed_kmh`: Float, `heading`: Int