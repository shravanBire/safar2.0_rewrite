/*
 * SAFAR 2.0 — Standalone Hardware Diagnostic Sketch
 * Tests:
 *   1. NEO-6M / NEO-8M GPS on GPIO 16 (RX2) & GPIO 17 (TX2)
 *      - Auto-detects if RX/TX wires are swapped!
 *      - Prints Raw NMEA sentences + Parsed Lat/Lng/Sats/HDOP
 *   2. MPU6050 Accelerometer/Gyroscope on GPIO 21 (SDA) & GPIO 22 (SCL)
 *      - Runs I2C Address Scanner first
 *      - Prints Live G-Force, X/Y/Z Acceleration, Tilt Angle, and Crash Trigger test
 *
 * Required Libraries in Arduino IDE:
 *   - TinyGPSPlus (by Mikal Hart)
 *   - MPU6050_light (by rfetick)
 */

#include <Arduino.h>
#include <Wire.h>
#include <TinyGPSPlus.h>
#include <MPU6050_light.h>
#include <math.h>

// ---------------- PIN DEFINITIONS ----------------
#define GPS_RX_PIN  16   // Connect to GPS TX
#define GPS_TX_PIN  17   // Connect to GPS RX
#define I2C_SDA_PIN 21   // Connect to MPU6050 SDA
#define I2C_SCL_PIN 22   // Connect to MPU6050 SCL

HardwareSerial gpsSerial(1);
TinyGPSPlus gps;
MPU6050 mpu(Wire);

bool mpuConnected = false;
float refAx = 0, refAy = 0, refAz = 1.0f;

// Diagnostic state for GPS
bool pinsSwapped = false;
uint32_t currentBaud = 9600;
uint32_t lastReportMs = 0;
uint32_t lastSwapCheckMs = 0;
uint32_t lastCharsCount = 0;
String lastNmeaLine = "";
String currentNmeaBuf = "";

// ---------------- I2C SCANNER ----------------
void scanI2CBus() {
  Serial.println("\n==========================================");
  Serial.println("[I2C SCANNER] Scanning GPIO 21 (SDA) / GPIO 22 (SCL)...");
  int found = 0;
  for (uint8_t addr = 1; addr < 127; addr++) {
    Wire.beginTransmission(addr);
    uint8_t err = Wire.endTransmission();
    if (err == 0) {
      Serial.printf("  -> Found I2C device at 0x%02X", addr);
      if (addr == 0x68 || addr == 0x69) Serial.print(" (MPU6050!)");
      Serial.println();
      found++;
    }
  }
  if (found == 0) {
    Serial.println("  [ERROR] No I2C devices found! Check MPU6050 VCC, GND, SDA(21), SCL(22).");
  }
  Serial.println("==========================================");
}

// ---------------- MPU6050 INIT ----------------
void initMPU() {
  byte status = mpu.begin();
  if (status == 0) {
    mpuConnected = true;
    Serial.println("[MPU6050] Connected OK! Keep board still for 1 sec calibration...");
    mpu.calcOffsets(true, true);
    mpu.update();
    refAx = mpu.getAccX();
    refAy = mpu.getAccY();
    refAz = mpu.getAccZ();
    float norm = sqrtf(refAx*refAx + refAy*refAy + refAz*refAz);
    if (norm > 0.1f) { refAx /= norm; refAy /= norm; refAz /= norm; }
    Serial.printf("[MPU6050] Calibrated Gravity Ref: X=%.2f Y=%.2f Z=%.2f\n", refAx, refAy, refAz);
  } else {
    mpuConnected = false;
    Serial.printf("[MPU6050] Initialization FAILED (status=%d). Check wiring!\n", status);
  }
}

void setup() {
  Serial.begin(115200);
  delay(800);

  Serial.println("\n\n##################################################");
  Serial.println("#   SAFAR 2.0 — GPS + MPU6050 HARDWARE TESTER    #");
  Serial.println("##################################################");

  // 1. Start I2C & Test MPU6050
  Wire.begin(I2C_SDA_PIN, I2C_SCL_PIN);
  scanI2CBus();
  initMPU();

  // 2. Start GPS UART
  Serial.printf("\n[GPS] Starting UART1 on RX=%d (connect to GPS TX), TX=%d (connect to GPS RX) @ %lu baud\n",
                GPS_RX_PIN, GPS_TX_PIN, currentBaud);
  gpsSerial.begin(currentBaud, SERIAL_8N1, GPS_RX_PIN, GPS_TX_PIN);
  lastSwapCheckMs = millis();
}

void loop() {
  // 1. Read all incoming bytes from GPS
  while (gpsSerial.available() > 0) {
    char c = (char)gpsSerial.read();
    gps.encode(c);

    // Capture one raw NMEA line for visual confirmation
    if (c == '\n') {
      currentNmeaBuf.trim();
      if (currentNmeaBuf.startsWith("$")) {
        lastNmeaLine = currentNmeaBuf;
      }
      currentNmeaBuf = "";
    } else if (currentNmeaBuf.length() < 100) {
      currentNmeaBuf += c;
    }
  }

  // 2. Update MPU6050
  if (mpuConnected) {
    mpu.update();
  }

  // 3. Auto-check if GPS chars == 0 for 6 seconds (Tests swapped RX/TX wires automatically!)
  uint32_t now = millis();
  if (now - lastSwapCheckMs >= 6000) {
    lastSwapCheckMs = now;
    uint32_t totalChars = gps.charsProcessed();
    if (totalChars == lastCharsCount) {
      // Zero new bytes received in the last 6 seconds! Try flipping RX/TX pins to help diagnose
      pinsSwapped = !pinsSwapped;
      int rxPin = pinsSwapped ? GPS_TX_PIN : GPS_RX_PIN;
      int txPin = pinsSwapped ? GPS_RX_PIN : GPS_TX_PIN;
      gpsSerial.end();
      gpsSerial.begin(currentBaud, SERIAL_8N1, rxPin, txPin);
      Serial.println("\n--------------------------------------------------");
      Serial.printf("[GPS AUTO-DIAGNOSTIC] 0 bytes received! Testing with RX=%d, TX=%d @ %lu baud...\n",
                    rxPin, txPin, currentBaud);
      Serial.println("--------------------------------------------------");
    }
    lastCharsCount = totalChars;
  }

  // 4. Print complete report every 1000 ms
  if (now - lastReportMs >= 1000) {
    lastReportMs = now;

    Serial.println("\n================ HARDWARE REPORT ================");

    // --- GPS SECTION ---
    uint32_t chars = gps.charsProcessed();
    Serial.printf("[GPS UART] Chars Received: %lu | Checksum Pass: %lu | PinMode: %s\n",
                  chars,
                  gps.passedChecksum(),
                  pinsSwapped ? "SWAPPED (RX=17, TX=16) -> SWAP YOUR WIRES!" : "NORMAL (RX=16, TX=17)");

    if (chars == 0) {
      Serial.println("  -> [STATUS] NO SERIAL DATA FROM GPS! Check:");
      Serial.println("     1. Is GPS VCC connected to 5V (VIN) or 3V3 and GND to GND?");
      Serial.println("     2. Is GPS TX connected to ESP32 GPIO 16?");
    } else {
      Serial.printf("  -> [RAW NMEA] %s\n", lastNmeaLine.length() > 0 ? lastNmeaLine.c_str() : "(waiting for newline...)");
      if (gps.location.isValid()) {
        Serial.printf("  -> [GPS FIX OK!] Lat: %.6f | Lng: %.6f | Speed: %.1f km/h | Sats: %u | HDOP: %.1f\n",
                      gps.location.lat(),
                      gps.location.lng(),
                      gps.speed.kmph(),
                      gps.satellites.value(),
                      gps.hdop.hdop());
      } else {
        Serial.printf("  -> [GPS WORKING - WAITING FOR SATELLITES] Sats visible: %u (Keep antenna near window/outdoors)\n",
                      gps.satellites.value());
      }
    }

    // --- MPU6050 SECTION ---
    if (mpuConnected) {
      float ax = mpu.getAccX();
      float ay = mpu.getAccY();
      float az = mpu.getAccZ();
      float totalG = sqrtf(ax*ax + ay*ay + az*az);

      // Compute tilt angle from calibrated upright reference
      float tiltDeg = 0.0f;
      if (totalG > 0.1f) {
        float dot = (ax*refAx + ay*refAy + az*refAz) / totalG;
        if (dot > 1.0f) dot = 1.0f;
        if (dot < -1.0f) dot = -1.0f;
        tiltDeg = acosf(dot) * (180.0f / M_PI);
      }

      Serial.printf("[MPU6050] Accel: X=%.2fg Y=%.2fg Z=%.2fg | Total: %.2fg | Tilt: %.1f deg\n",
                    ax, ay, az, totalG, tiltDeg);

      if (totalG > 2.5f) {
        Serial.println("  -> *** IMPACT / SHAKE DETECTED! ***");
      }
      if (tiltDeg > 60.0f) {
        Serial.println("  -> *** TILT > 60 DEG DETECTED (Bike Fallen State)! ***");
      }
    } else {
      Serial.println("[MPU6050] NOT CONNECTED! Check SDA -> GPIO 21, SCL -> GPIO 22.");
    }
    Serial.println("=================================================");
  }
}
