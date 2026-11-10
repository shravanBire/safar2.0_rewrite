// safar_crypto.h  —  SAFAR 2.0 ChaCha20-Poly1305 (RFC 8439) for ESP32 / Arduino
// Self-contained, alignment-safe (no unaligned uint32 casts), verified against reference vectors.
// Frame layout (same as the Android app):  opcode(1) | nonce(12) | ciphertext(n) | tag(16),  AAD = opcode byte.
#pragma once
#include <stdint.h>
#include <stddef.h>
#include <string.h>

#define SAFAR_NONCE_LEN 12
#define SAFAR_TAG_LEN   16
#define SAFAR_FRAME_OVERHEAD (1 + SAFAR_NONCE_LEN + SAFAR_TAG_LEN)   // 29 bytes
#define SAFAR_MAX_INNER 496                                          // >= 485 (history chunk inner)

static inline uint32_t sc_rotl32(uint32_t v, int c) { return (v << c) | (v >> (32 - c)); }
static inline uint32_t sc_load32le(const uint8_t* p) {
  return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}
static inline void sc_store32le(uint8_t* p, uint32_t v) {
  p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); p[2] = (uint8_t)(v >> 16); p[3] = (uint8_t)(v >> 24);
}

#define SC_QR(a, b, c, d) \
  a += b; d ^= a; d = sc_rotl32(d, 16); \
  c += d; b ^= c; b = sc_rotl32(b, 12); \
  a += b; d ^= a; d = sc_rotl32(d, 8);  \
  c += d; b ^= c; b = sc_rotl32(b, 7);

static void sc_chacha_block(const uint8_t key[32], uint32_t counter, const uint8_t nonce[12], uint8_t out[64]) {
  uint32_t in[16], x[16];
  in[0] = 0x61707865; in[1] = 0x3320646e; in[2] = 0x79622d32; in[3] = 0x6b206574;
  for (int i = 0; i < 8; i++) in[4 + i] = sc_load32le(key + 4 * i);
  in[12] = counter;
  in[13] = sc_load32le(nonce); in[14] = sc_load32le(nonce + 4); in[15] = sc_load32le(nonce + 8);
  for (int i = 0; i < 16; i++) x[i] = in[i];
  for (int i = 0; i < 10; i++) {
    SC_QR(x[0], x[4], x[8],  x[12]);
    SC_QR(x[1], x[5], x[9],  x[13]);
    SC_QR(x[2], x[6], x[10], x[14]);
    SC_QR(x[3], x[7], x[11], x[15]);
    SC_QR(x[0], x[5], x[10], x[15]);
    SC_QR(x[1], x[6], x[11], x[12]);
    SC_QR(x[2], x[7], x[8],  x[13]);
    SC_QR(x[3], x[4], x[9],  x[14]);
  }
  for (int i = 0; i < 16; i++) sc_store32le(out + 4 * i, x[i] + in[i]);
}

static void sc_chacha_xor(const uint8_t key[32], uint32_t counter, const uint8_t nonce[12],
                          const uint8_t* in, uint8_t* out, size_t len) {
  uint8_t block[64];
  while (len > 0) {
    sc_chacha_block(key, counter++, nonce, block);
    size_t take = (len < 64) ? len : 64;
    for (size_t i = 0; i < take; i++) out[i] = in[i] ^ block[i];
    in += take; out += take; len -= take;
  }
}

// Poly1305 over a message whose length is a multiple of 16 (every block gets the 2^128 bit).
static void sc_poly1305_16(const uint8_t key[32], const uint8_t* m, size_t len, uint8_t tag[16]) {
  const uint32_t MASK = 0x3ffffff;
  uint32_t r0 = sc_load32le(key + 0) & 0x3ffffff;
  uint32_t r1 = (sc_load32le(key + 3) >> 2) & 0x3ffff03;
  uint32_t r2 = (sc_load32le(key + 6) >> 4) & 0x3ffc0ff;
  uint32_t r3 = (sc_load32le(key + 9) >> 6) & 0x3f03fff;
  uint32_t r4 = (sc_load32le(key + 12) >> 8) & 0x00fffff;
  uint32_t s1 = r1 * 5, s2 = r2 * 5, s3 = r3 * 5, s4 = r4 * 5;
  uint32_t h0 = 0, h1 = 0, h2 = 0, h3 = 0, h4 = 0;

  while (len >= 16) {
    h0 += (sc_load32le(m + 0)) & MASK;
    h1 += (sc_load32le(m + 3) >> 2) & MASK;
    h2 += (sc_load32le(m + 6) >> 4) & MASK;
    h3 += (sc_load32le(m + 9) >> 6) & MASK;
    h4 += (sc_load32le(m + 12) >> 8) | (1u << 24);

    uint64_t d0 = (uint64_t)h0 * r0 + (uint64_t)h1 * s4 + (uint64_t)h2 * s3 + (uint64_t)h3 * s2 + (uint64_t)h4 * s1;
    uint64_t d1 = (uint64_t)h0 * r1 + (uint64_t)h1 * r0 + (uint64_t)h2 * s4 + (uint64_t)h3 * s3 + (uint64_t)h4 * s2;
    uint64_t d2 = (uint64_t)h0 * r2 + (uint64_t)h1 * r1 + (uint64_t)h2 * r0 + (uint64_t)h3 * s4 + (uint64_t)h4 * s3;
    uint64_t d3 = (uint64_t)h0 * r3 + (uint64_t)h1 * r2 + (uint64_t)h2 * r1 + (uint64_t)h3 * r0 + (uint64_t)h4 * s4;
    uint64_t d4 = (uint64_t)h0 * r4 + (uint64_t)h1 * r3 + (uint64_t)h2 * r2 + (uint64_t)h3 * r1 + (uint64_t)h4 * r0;

    uint32_t c;
    c = (uint32_t)(d0 >> 26); h0 = (uint32_t)d0 & MASK;
    d1 += c; c = (uint32_t)(d1 >> 26); h1 = (uint32_t)d1 & MASK;
    d2 += c; c = (uint32_t)(d2 >> 26); h2 = (uint32_t)d2 & MASK;
    d3 += c; c = (uint32_t)(d3 >> 26); h3 = (uint32_t)d3 & MASK;
    d4 += c; c = (uint32_t)(d4 >> 26); h4 = (uint32_t)d4 & MASK;
    h0 += c * 5; c = h0 >> 26; h0 &= MASK; h1 += c;
    m += 16; len -= 16;
  }

  // full carry
  uint32_t c;
  c = h1 >> 26; h1 &= MASK; h2 += c;
  c = h2 >> 26; h2 &= MASK; h3 += c;
  c = h3 >> 26; h3 &= MASK; h4 += c;
  c = h4 >> 26; h4 &= MASK; h0 += c * 5;
  c = h0 >> 26; h0 &= MASK; h1 += c;

  // compute h + -p
  uint32_t g0 = h0 + 5; c = g0 >> 26; g0 &= MASK;
  uint32_t g1 = h1 + c; c = g1 >> 26; g1 &= MASK;
  uint32_t g2 = h2 + c; c = g2 >> 26; g2 &= MASK;
  uint32_t g3 = h3 + c; c = g3 >> 26; g3 &= MASK;
  uint32_t g4 = h4 + c - (1u << 26);

  // select h if h < p, else h - p
  uint32_t mask = (g4 >> 31) - 1;
  g0 &= mask; g1 &= mask; g2 &= mask; g3 &= mask; g4 &= mask;
  mask = ~mask;
  h0 = (h0 & mask) | g0; h1 = (h1 & mask) | g1; h2 = (h2 & mask) | g2;
  h3 = (h3 & mask) | g3; h4 = (h4 & mask) | g4;

  // h = h % 2^128
  h0 = (h0 | (h1 << 26)) & 0xffffffff;
  h1 = ((h1 >> 6) | (h2 << 20)) & 0xffffffff;
  h2 = ((h2 >> 12) | (h3 << 14)) & 0xffffffff;
  h3 = ((h3 >> 18) | (h4 << 8)) & 0xffffffff;

  // mac = (h + s) % 2^128
  uint64_t f;
  f = (uint64_t)h0 + sc_load32le(key + 16);               sc_store32le(tag + 0, (uint32_t)f);
  f = (uint64_t)h1 + sc_load32le(key + 20) + (f >> 32);   sc_store32le(tag + 4, (uint32_t)f);
  f = (uint64_t)h2 + sc_load32le(key + 24) + (f >> 32);   sc_store32le(tag + 8, (uint32_t)f);
  f = (uint64_t)h3 + sc_load32le(key + 28) + (f >> 32);   sc_store32le(tag + 12, (uint32_t)f);
}

// RFC 8439 AEAD tag: Poly1305( pad16(AAD) | pad16(CT) | len(AAD) as u64le | len(CT) as u64le ) with one-time key from block 0.
static void sc_aead_tag(const uint8_t key[32], const uint8_t nonce[12], uint8_t aad0,
                        const uint8_t* ct, size_t ctLen, uint8_t tag[16]) {
  uint8_t block0[64];
  sc_chacha_block(key, 0, nonce, block0);                 // first 32 bytes = Poly1305 one-time key

  uint8_t msg[16 + ((SAFAR_MAX_INNER + 15) / 16) * 16 + 16];
  size_t n = 0;
  memset(msg, 0, 16);
  msg[0] = aad0; n = 16;                                  // AAD (1 byte) padded to 16
  memcpy(msg + n, ct, ctLen); n += ctLen;
  while (n % 16) msg[n++] = 0;                            // CT padded to 16
  for (int i = 0; i < 8; i++) msg[n + i] = (uint8_t)(((uint64_t)1 >> (8 * i)) & 0xff);       // len(AAD)=1
  for (int i = 0; i < 8; i++) msg[n + 8 + i] = (uint8_t)(((uint64_t)ctLen >> (8 * i)) & 0xff); // len(CT)
  n += 16;
  sc_poly1305_16(block0, msg, n, tag);
}

// Build frame: out = opcode | nonce | ct | tag.  Returns frame length, or 0 on error.
static int safar_aead_seal(uint8_t opcode, const uint8_t key[32], const uint8_t nonce[12],
                           const uint8_t* inner, size_t innerLen, uint8_t* out) {
  if (innerLen > SAFAR_MAX_INNER) return 0;
  out[0] = opcode;
  memcpy(out + 1, nonce, 12);
  uint8_t* ct = out + 13;
  sc_chacha_xor(key, 1, nonce, inner, ct, innerLen);
  sc_aead_tag(key, nonce, opcode, ct, innerLen, ct + innerLen);
  return (int)(1 + 12 + innerLen + 16);
}

// Verify + decrypt. Returns inner length (>=0) or -1 if frame invalid / tag mismatch.
static int safar_aead_open(const uint8_t* frame, size_t frameLen, const uint8_t key[32],
                           uint8_t* innerOut, size_t innerCap, uint8_t* opcodeOut) {
  if (frameLen < SAFAR_FRAME_OVERHEAD) return -1;
  size_t ctLen = frameLen - SAFAR_FRAME_OVERHEAD;
  if (ctLen > SAFAR_MAX_INNER || ctLen > innerCap) return -1;
  uint8_t opcode = frame[0];
  const uint8_t* nonce = frame + 1;
  const uint8_t* ct = frame + 13;
  const uint8_t* tag = frame + 13 + ctLen;
  uint8_t expect[16];
  sc_aead_tag(key, nonce, opcode, ct, ctLen, expect);
  uint8_t diff = 0;
  for (int i = 0; i < 16; i++) diff |= (uint8_t)(expect[i] ^ tag[i]);   // constant-time compare
  if (diff != 0) return -1;
  sc_chacha_xor(key, 1, nonce, ct, innerOut, ctLen);
  if (opcodeOut) *opcodeOut = opcode;
  return (int)ctLen;
}
