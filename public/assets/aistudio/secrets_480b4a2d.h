// secrets.h  —  KEEP THIS FILE PRIVATE. Never commit it to git / never paste it in chat.
// (Arduino IDE: put it in the same folder as safar_esp32.ino, it will show up as a tab.)
#pragma once

// ---- Wi-Fi (or phone hotspot) ----
#define WIFI_SSID          "shravan"
#define WIFI_PASS          "shravan@123"

// ---- EMQX broker: host only (no ssl://, no port) ----
#define MQTT_BROKER        "ka0821c7.ala.us-east-1.emqxsl.com"
#define MQTT_PORT          8883
#define MQTT_USER          "safar_esp32"
#define MQTT_PASS          "Safar@123"

// ---- Pairing identity (must match the QR you paste in the app) ----
// DEV_ID = first 16 hex chars of SHA-256 of the 64-char pub hex STRING, e.g.
//   python -c "import hashlib;print(hashlib.sha256(b'<PUB_64_HEX>').hexdigest()[:16])"
#define DEV_ID             "0000000000000000"
// 64 hex chars = 32 byte key (same as the `sec=` value in the QR)
#define SHARED_SECRET_HEX  "0000000000000000000000000000000000000000000000000000000000000000"

// ---- EMQX CA certificate (EMQX console -> your deployment -> Overview -> download CA cert) ----
// Paste the full PEM text between the markers below.
static const char EMQX_CA_PEM[] = R"EOF(
-----BEGIN CERTIFICATE-----
PASTE_YOUR_EMQX_CA_CERTIFICATE_HERE
-----END CERTIFICATE-----
)EOF";
