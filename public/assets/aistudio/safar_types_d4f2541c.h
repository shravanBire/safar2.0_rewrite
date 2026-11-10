// safar_types.h - shared types (kept in a header so the Arduino IDE auto-generated prototypes can see them)
#pragma once
#include <stdint.h>

// =================== 16-BYTE TRIP POINT ===================
typedef struct __attribute__((packed)) {
  uint32_t timestamp;       // Unix epoch seconds (UTC)
  int32_t  latE7;
  int32_t  lonE7;
  uint16_t speedDeciKmh;
  uint8_t  headingDiv2;
  uint8_t  flags;           // bit0 ignition/riding, bit1 trip start, bit2 trip end, bits3..7 satellites
} TripPoint;
static_assert(sizeof(TripPoint) == 16, "TripPoint must be exactly 16 bytes");

#define FLAG_RIDING  0x01
#define FLAG_START   0x02
#define FLAG_END     0x04


struct Snapshot { TripPoint pt; bool valid; bool riding; uint32_t updatedMs; };
