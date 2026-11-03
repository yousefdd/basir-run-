// Basir-Run vibration belt firmware (ESP32-S3, Arduino core 2.x or 3.x).
//
// Five vibration motor MODULES (each with its own transistor and flyback diode: VCC, GND, IN) on:
//   M1 GPIO4 (far left)  M2 GPIO5 (front left)  M3 GPIO6 (centre)  M4 GPIO7 (front right)  M5 GPIO15 (far right)
// Never wire a bare motor to a GPIO pin: a pin gives about 20 mA, a motor draws 60-90 mA and its coil kicks back.
//
// The phone sends protocol v1 frames (docs/belt/BELT_PROTOCOL.md) to the command characteristic. belt_core.h decides
// what to play (stale, old, corrupted and lower-priority frames are dropped, never queued); this file only does the
// radio, the PWM and the safety around them:
//   - Bluetooth lost or no frame for linkTimeoutMs (1.5 s): every motor off;
//   - at power-on the five motors pulse once each, left to right, so the wiring and positions can be checked by feel;
//   - a short full-power kick starts a coin motor that is asked for a weak level (they stall below about 30-40 %).
// Serial (115200) for bench tests without the phone: 1..5 = pulse that motor, t = self-test, s = status.
//
// Board in Arduino IDE: "ESP32S3 Dev Module". Libraries: only the ones that come with the esp32 core.

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include "esp_timer.h"
#include "esp_mac.h"
#include "belt_core.h"

static const uint8_t FW_VERSION = 1;
static const int MOTOR_PINS[belt::MOTORS] = {4, 5, 6, 7, 15};
static const uint32_t PWM_HZ = 20000;   // above hearing: no whine from the motors
static const uint8_t PWM_BITS = 8;
static const uint8_t KICK_BELOW = 110;  // a start weaker than this gets a full-power kick first
static const uint16_t KICK_MS = 25;

#define SERVICE_UUID "6b1e0001-6f2b-4c1a-9a2e-8f6d2a7b0b01"
#define COMMAND_UUID "6b1e0002-6f2b-4c1a-9a2e-8f6d2a7b0b01"
#define STATUS_UUID  "6b1e0003-6f2b-4c1a-9a2e-8f6d2a7b0b01"

struct Incoming { int64_t atMs; uint8_t len; uint8_t data[96]; };

static belt::Core core;
static QueueHandle_t rx;
static BLECharacteristic* statusChar = nullptr;
static volatile bool connected = false, connectEvent = false, disconnectEvent = false;
static uint8_t applied[belt::MOTORS] = {0};
static int64_t kickUntil[belt::MOTORS] = {0};
static uint32_t lastStatusMs = 0;

static inline int64_t nowMs() { return esp_timer_get_time() / 1000; }

static void pwmAttach(int ch, int pin) {
#if ESP_ARDUINO_VERSION_MAJOR >= 3
  (void)ch;
  ledcAttach(pin, PWM_HZ, PWM_BITS);
#else
  ledcSetup(ch, PWM_HZ, PWM_BITS);
  ledcAttachPin(pin, ch);
#endif
}

static void pwmWrite(int m, uint8_t duty) {
#if ESP_ARDUINO_VERSION_MAJOR >= 3
  ledcWrite(MOTOR_PINS[m], duty);
#else
  ledcWrite(m, duty);
#endif
}

static void statusLed(bool on) {
#ifdef RGB_BUILTIN
#if ESP_ARDUINO_VERSION_MAJOR >= 3
  rgbLedWrite(RGB_BUILTIN, 0, 0, on ? 24 : 0);
#else
  neopixelWrite(RGB_BUILTIN, 0, 0, on ? 24 : 0);
#endif
#else
  (void)on;
#endif
}

static void allOff() {
  for (int m = 0; m < belt::MOTORS; m++) { pwmWrite(m, 0); applied[m] = 0; kickUntil[m] = 0; }
}

// Drives the pins from the core's duties, with the start kick for weak levels.
static void drive(int64_t t) {
  uint8_t d[belt::MOTORS];
  core.duties(t, d);
  for (int m = 0; m < belt::MOTORS; m++) {
    if (d[m] > 0 && applied[m] == 0 && d[m] < KICK_BELOW) kickUntil[m] = t + KICK_MS;
    if (d[m] == 0) kickUntil[m] = 0;
    uint8_t out = (d[m] > 0 && t < kickUntil[m]) ? 255 : d[m];
    pwmWrite(m, out);
    applied[m] = d[m];
  }
}

static void pulse(int m, uint16_t ms, uint8_t duty) {
  pwmWrite(m, duty);
  delay(ms);
  pwmWrite(m, 0);
}

static void selfTest() {
  for (int m = 0; m < belt::MOTORS; m++) { pulse(m, 160, 200); delay(220); }
}

static void sendStatus() {
  if (!connected || statusChar == nullptr) return;
  uint8_t s[12];
  s[0] = 0xB6; s[1] = 1;
  s[2] = core.lastSeqSeen & 0xFF; s[3] = core.lastSeqSeen >> 8;
  s[4] = core.drops & 0xFF; s[5] = (core.drops >> 8) & 0xFF;
  s[6] = core.played & 0xFF; s[7] = (core.played >> 8) & 0xFF;
  s[8] = (core.isPlaying(nowMs()) ? 1 : 0) | (connected ? 2 : 0);
  s[9] = FW_VERSION;
  s[10] = 0;
  s[11] = belt::crc8(s, 11);
  statusChar->setValue(s, sizeof(s));
  statusChar->notify();
}

class ServerEvents : public BLEServerCallbacks {
  void onConnect(BLEServer*) override { connected = true; connectEvent = true; }
  void onDisconnect(BLEServer*) override { connected = false; disconnectEvent = true; }
};

class CommandEvents : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override {
    Incoming in;
    in.atMs = nowMs();                       // the receive time, before any queueing delay in this firmware
#if ESP_ARDUINO_VERSION_MAJOR >= 3
    String v = c->getValue();
    size_t n = v.length();
    const uint8_t* p = (const uint8_t*)v.c_str();
#else
    std::string v = c->getValue();
    size_t n = v.size();
    const uint8_t* p = (const uint8_t*)v.data();
#endif
    if (n == 0 || n > sizeof(in.data)) return;
    in.len = (uint8_t)n;
    memcpy(in.data, p, n);
    // never block the radio; if the loop is behind, the oldest frame goes (newer ones matter more)
    if (xQueueSend(rx, &in, 0) != pdTRUE) {
      Incoming dropped;
      xQueueReceive(rx, &dropped, 0);
      xQueueSend(rx, &in, 0);
    }
  }
};

void setup() {
  Serial.begin(115200);
  for (int m = 0; m < belt::MOTORS; m++) { pinMode(MOTOR_PINS[m], OUTPUT); digitalWrite(MOTOR_PINS[m], LOW); pwmAttach(m, MOTOR_PINS[m]); }
  allOff();
  rx = xQueueCreate(6, sizeof(Incoming));
  selfTest();

  uint8_t mac[6];
  esp_read_mac(mac, ESP_MAC_BT);
  char name[20];
  snprintf(name, sizeof(name), "BasirBelt-%02X%02X", mac[4], mac[5]);
  BLEDevice::init(name);
  BLEDevice::setMTU(185);
  BLEServer* server = BLEDevice::createServer();
  server->setCallbacks(new ServerEvents());
  BLEService* svc = server->createService(SERVICE_UUID);
  BLECharacteristic* cmd = svc->createCharacteristic(COMMAND_UUID, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  cmd->setCallbacks(new CommandEvents());
  statusChar = svc->createCharacteristic(STATUS_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  statusChar->addDescriptor(new BLE2902());
  svc->start();
  BLEAdvertising* adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.printf("Basir belt firmware %d ready as %s\n", FW_VERSION, name);
}

void loop() {
  if (connectEvent) { connectEvent = false; core.onConnect(); allOff(); statusLed(true); Serial.println("phone connected"); }
  if (disconnectEvent) {
    disconnectEvent = false;
    core.onDisconnect(); allOff(); statusLed(false);
    xQueueReset(rx);                         // nothing from before the disconnect is played later
    Serial.println("phone disconnected: motors off, advertising again");
    delay(200);
    BLEDevice::startAdvertising();
  }
  Incoming in;
  while (xQueueReceive(rx, &in, 0) == pdTRUE) {
    belt::Result r = core.receive(in.data, in.len, in.atMs);
    if (r >= belt::DROP_CRC) Serial.printf("dropped: %s\n", belt::resultName(r));
  }
  if (connected) drive(nowMs()); else allOff();

  if (millis() - lastStatusMs >= 1000) { lastStatusMs = millis(); sendStatus(); }

  if (Serial.available()) {
    int c = Serial.read();
    if (c >= '1' && c <= '5') pulse(c - '1', 300, 220);
    else if (c == 't') selfTest();
    else if (c == 's') Serial.printf("connected=%d played=%u drops=%u lastSeq=%u\n", connected, (unsigned)core.played, (unsigned)core.drops, core.lastSeqSeen);
  }
  delay(2);
}
