# SAFAR 2.0 — Design System & UI/UX Style Guide
**Theme Inspiration:** Royal Enfield Tripper Dash / Vintage Cafe-Racer Instrument Cluster
**Architecture:** Edge storage + relay-only MQTT server, end-to-end encrypted

## 1. Color Palette (`Color.kt`)

### Dark Theme (Default — "Midnight Moto Cockpit")
- **Primary Background (`Crankcase Black`):** `#0E0E10` (Deep matte metallic black)
- **Elevated Surface / Card (`Gunmetal Gauge`):** `#18181B` with `1.dp` border of `#2E2A24` (`BronzeRim`)
- **Primary Accent (`Royal Brass`):** `#D4AF37` (Speedometer needle, active tabs, primary buttons)
- **Secondary Accent (`Cafe Copper`):** `#C86D51` (Route polylines, secondary badges)
- **Telemetry Live Green (`Phosphor Green`):** `#4ADE80` (Active MQTT TLS telemetry with GPS fix)
- **Alert / SOS Red (`Signal Red`):** `#EF4444` (High-RPM redline zone & broker errors)
- **Text Primary (`Warm Ivory`):** `#F4EFE6` (Vintage dial numeral cream)
- **Text Secondary (`Muted Pewter`):** `#9CA3AF`

### Light Theme ("Classic Parchment Day-Dial")
- **Primary Background (`Vintage Parchment`):** `#F4EFE6`
- **Elevated Surface (`Warm Sand`):** `#EAE2D3` with `1.dp` border of `#C5B8A5` (`SandBorder`)
- **Primary Accent (`British Racing Green`):** `#143B27`
- **Secondary Accent (`Burnished Brass`):** `#B5892E`
- **Text Primary (`Espresso Ink`):** `#1C1917`

---

## 2. Typography (`Type.kt`)
- **Display / Speedometer Numerals:** `BebasNeue` (Bold, condensed mechanical odometer look)
- **Section Headers:** `Rajdhani` (SemiBold/Bold, uppercase with `1.5.sp` letter-spacing)
- **Body & Labels:** `Inter` (Clean legibility for maps, diagnostics, and settings)

---

## 3. Core Component Library (`ui/components/`)

### A. `TripperSpeedometer.kt`
- **Outer Bezel:** Double metallic ring (`BronzeRim` outer, `RoyalBrass` inner accent).
- **Dial Ticks:** 0 to 160 km/h ticks; 120+ km/h redline in `SignalRed`.
- **Animated Needle:** Spring-damped mechanical needle sweep.
- **Rolling Odometer:** Digital speed readout in center + mechanical `ODO` lifetime distance counter at the bottom.

### B. `TelemetryHeaderPill.kt`
- Left: Bike Selector Dropdown pill.
- Right: Live Link Badge (`"● LIVE"` in `PhosphorGreen`, `"● LIVE · NO GPS FIX"` in `RoyalBrass`, `"● SYNCING LOGS (72%)"` in `RoyalBrass`, or `"● PARKED • LAST SEEN 10M AGO"`).

### C. `RideTicketCard.kt`
- Vintage Highway Ticket Card for each trip on the selected date:
  - Header with ride number, start/end time, and duration.
  - 3-Column Brass Badge Grid (`KM`, `AVG`, `MAX`).
  - Expandable MapLibre route preview with `CafeCopper` polyline.