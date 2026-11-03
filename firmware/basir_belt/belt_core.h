// Basir-Run belt: protocol v1 decoder and the belt's decision rules, without any hardware code.
// The same rules as run.basir.engine.BeltPlayerModel (Kotlin); the two are checked against each other by golden
// vectors (firmware/test/belt_core_test.cpp reads firmware/test/vectors.txt written by the Kotlin side).
// Spec: docs/belt/BELT_PROTOCOL.md
#pragma once
#include <stdint.h>
#include <string.h>

namespace belt {

static const uint8_t MAGIC = 0xB5, VERSION = 1;
static const uint8_t T_PLAY = 1, T_SILENCE = 2, T_HEARTBEAT = 3, T_CONFIG = 4;
static const int HEADER = 13, MAX_STEPS = 12, MOTORS = 5;

enum Result : uint8_t {
  PLAYING = 0, CONFIGURED, SILENCED, HEARTBEAT, DROP_CRC, DROP_OLD_SEQ, DROP_STALE, DROP_LOWER_PRIORITY
};

static inline const char* resultName(Result r) {
  static const char* n[] = {"PLAYING", "CONFIGURED", "SILENCED", "HEARTBEAT", "DROP_CRC", "DROP_OLD_SEQ", "DROP_STALE",
                            "DROP_LOWER_PRIORITY"};
  return r <= DROP_LOWER_PRIORITY ? n[r] : "?";
}

struct Step { uint8_t mask, amp; uint16_t onMs, offMs; };

struct Config {
  uint8_t intensityPct = 100;
  uint8_t trimPct[MOTORS] = {100, 100, 100, 100, 100};
  uint8_t maxDutyPct = 100;
  uint16_t linkTimeoutMs = 1500;
};

static inline uint8_t crc8(const uint8_t* b, int len) {
  uint8_t crc = 0;
  for (int i = 0; i < len; i++) {
    crc ^= b[i];
    for (int k = 0; k < 8; k++) crc = (crc & 0x80) ? (uint8_t)((crc << 1) ^ 0x07) : (uint8_t)(crc << 1);
  }
  return crc;
}
static inline uint16_t get16(const uint8_t* b) { return (uint16_t)(b[0] | (b[1] << 8)); }
static inline uint32_t get32(const uint8_t* b) {
  return (uint32_t)b[0] | ((uint32_t)b[1] << 8) | ((uint32_t)b[2] << 16) | ((uint32_t)b[3] << 24);
}

class Core {
 public:
  Config config;
  uint32_t drops = 0, played = 0;
  uint16_t lastSeqSeen = 0;

  void onConnect() { haveSeq = false; haveOffset = false; lastFrameAt = 0; haveFrame = false; stopAll(); }
  void onDisconnect() { stopAll(); }

  // nowMs: the belt's millis(), widened to 64 bits by the caller.
  Result receive(const uint8_t* b, int len, int64_t nowMs) {
    if (len < HEADER + 1 || b[0] != MAGIC || b[1] != VERSION) return drop(DROP_CRC);
    if (crc8(b, len - 1) != b[len - 1]) return drop(DROP_CRC);
    uint8_t type = b[2], n = b[12];
    uint16_t seq = get16(b + 3);
    uint32_t phone = get32(b + 5);
    uint16_t ttl = get16(b + 9);
    uint8_t pri = b[11];
    // structure check before anything changes state
    if (type == T_PLAY) {
      if (n < 1 || n > MAX_STEPS || len != HEADER + 6 * n + 1) return drop(DROP_CRC);
      for (int i = 0; i < n; i++) if (b[HEADER + 6 * i] > 31) return drop(DROP_CRC);
    } else if (type == T_CONFIG) {
      if (n != 7 || len != HEADER + 10) return drop(DROP_CRC);
    } else if (type == T_SILENCE || type == T_HEARTBEAT) {
      if (len != HEADER + 1) return drop(DROP_CRC);
    } else {
      return drop(DROP_CRC);
    }
    if (haveSeq) {
      uint16_t d = (uint16_t)(seq - lastSeq);
      if (d == 0 || d > 0x7FFF) return drop(DROP_OLD_SEQ);
    }
    lastSeq = seq; haveSeq = true; lastSeqSeen = seq;
    int64_t sample = nowMs - (int64_t)phone;
    if (haveOffset) {
      int64_t gap = haveFrame ? nowMs - lastFrameAt : 0;
      if (gap < 0) gap = 0;
      int64_t crept = offset + gap / 1000;
      offset = sample < crept ? sample : crept;
    } else {
      offset = sample; haveOffset = true;
    }
    lastFrameAt = nowMs; haveFrame = true;
    switch (type) {
      case T_HEARTBEAT: return HEARTBEAT;
      case T_SILENCE: stopAll(); return SILENCED;
      case T_CONFIG: {
        const uint8_t* c = b + HEADER;
        config.intensityPct = c[0];
        for (int i = 0; i < MOTORS; i++) config.trimPct[i] = c[1 + i];
        config.maxDutyPct = c[6];
        config.linkTimeoutMs = get16(c + 7);
        return CONFIGURED;
      }
      default: {
        int64_t age = sample - offset;
        if (age > ttl) return drop(DROP_STALE);
        if (isPlaying(nowMs) && pri < playPriority) return drop(DROP_LOWER_PRIORITY);
        nSteps = n;
        for (int i = 0; i < n; i++) {
          const uint8_t* s = b + HEADER + 6 * i;
          steps[i].mask = s[0]; steps[i].amp = s[1]; steps[i].onMs = get16(s + 2); steps[i].offMs = get16(s + 4);
        }
        playPriority = pri; startedAt = nowMs; played++;
        return PLAYING;
      }
    }
  }

  bool isPlaying(int64_t nowMs) const { return nSteps > 0 && nowMs - startedAt < total(); }

  // PWM duty 0..255 for M1..M5 at nowMs; runs the link watchdog first.
  void duties(int64_t nowMs, uint8_t out[MOTORS]) {
    if (!haveFrame || nowMs - lastFrameAt > config.linkTimeoutMs) stopAll();
    memset(out, 0, MOTORS);
    if (!isPlaying(nowMs)) return;
    int64_t t = nowMs - startedAt;
    for (int i = 0; i < nSteps; i++) {
      const Step& s = steps[i];
      if (t < s.onMs) {
        for (int m = 0; m < MOTORS; m++) if (s.mask & (1 << m)) out[m] = duty(s.amp, m);
        return;
      }
      t -= s.onMs;
      if (t < s.offMs) return;
      t -= s.offMs;
    }
  }

  uint8_t duty(uint8_t amp, int motor) const {
    double v = amp * config.intensityPct / 100.0 * config.trimPct[motor] / 100.0;
    double cap = 255.0 * config.maxDutyPct / 100.0;
    if (v > cap) v = cap;
    long r = (long)(v + 0.5);   // Kotlin roundToInt for positive values
    if (r < 0) r = 0;
    if (r > 255) r = 255;
    return (uint8_t)r;
  }

  void stopAll() { nSteps = 0; playPriority = 0; }

 private:
  bool haveSeq = false, haveOffset = false, haveFrame = false;
  uint16_t lastSeq = 0;
  int64_t offset = 0, lastFrameAt = 0, startedAt = 0;
  Step steps[MAX_STEPS];
  int nSteps = 0;
  uint8_t playPriority = 0;
  int64_t total() const {
    int64_t s = 0;
    for (int i = 0; i < nSteps; i++) s += steps[i].onMs + steps[i].offMs;
    return s;
  }
  Result drop(Result r) { drops++; return r; }
};

}  // namespace belt
