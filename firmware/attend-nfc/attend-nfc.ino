/*
 * Attend NFC device firmware for WiFiNINA-compatible Arduino boards.
 *
 * Required libraries:
 * - MFRC522
 * - WiFiNINA
 * - ArduinoHttpClient
 * - ArduinoJson 7
 *
 * Production uses WiFiSSLClient and verifies SERVER_HOST against the root
 * certificates provisioned with the WiFiNINA Firmware Updater. Plain HTTP is
 * intentionally not implemented.
 */

#include <ArduinoHttpClient.h>
#include <ArduinoJson.h>
#include <MFRC522.h>
#include <SPI.h>
#include <WiFiNINA.h>

#include "config.h"

constexpr byte RFID_RESET_PIN = 9;
constexpr byte RFID_SELECT_PIN = 10;
constexpr byte RED_LED_PIN = 2;
constexpr byte GREEN_LED_PIN = 3;
constexpr unsigned long NETWORK_TIMEOUT_MS = 5000;
constexpr int MAX_AUTOMATIC_RETRIES = 3;
constexpr unsigned long RETRY_DELAYS_MS[] = {2000, 5000, 15000};
constexpr byte PENDING_CHECK_IN_CAPACITY = 8;
constexpr unsigned long DISPATCH_DELAY_MS = 150;
constexpr unsigned long DUPLICATE_TAG_WINDOW_MS = 1500;
constexpr unsigned long WIFI_CONNECT_RETRY_MS = 10000;
constexpr unsigned long WIFI_STATUS_RECHECK_MS = 250;
constexpr unsigned long WIFI_BEGIN_TIMEOUT_MS = 1000;
constexpr byte REQUEST_ID_JITTER_PIN = A0;
constexpr bool SERIAL_LOOP_HEARTBEAT_ENABLED = true;
constexpr unsigned long LOOP_HEARTBEAT_INTERVAL_MS = 1000;
constexpr size_t UID_BUFFER_SIZE = 21;
constexpr size_t REQUEST_ID_BUFFER_SIZE = 32;

MFRC522 rfid(RFID_SELECT_PIN, RFID_RESET_PIN);
WiFiSSLClient tlsClient;
HttpClient httpClient(tlsClient, SERVER_HOST, SERVER_PORT);
unsigned long tagCounter = 0;
uint32_t requestSessionId = 0;

struct PendingCheckIn {
  char uid[UID_BUFFER_SIZE];
  char requestId[REQUEST_ID_BUFFER_SIZE];
  unsigned long capturedAtMicros;
  unsigned long sequenceNo;
  byte retryCount;
  unsigned long nextAttemptAt;
};

PendingCheckIn pendingCheckIns[PENDING_CHECK_IN_CAPACITY];
byte pendingHead = 0;
byte pendingTail = 0;
byte pendingCount = 0;
char lastQueuedUid[UID_BUFFER_SIZE] = "";
unsigned long lastQueuedAt = 0;
unsigned long nextWifiConnectAttemptAt = 0;
bool wifiWasConnected = false;
unsigned long lastLoopHeartbeatAt = 0;

struct DeviceResponse {
  int httpStatus;
  String code;
  int retryAfterSeconds;
  bool validJson;
};

bool isTimeDue(unsigned long now, unsigned long due);

void setLeds(bool red, bool green) {
  digitalWrite(RED_LED_PIN, red ? HIGH : LOW);
  digitalWrite(GREEN_LED_PIN, green ? HIGH : LOW);
}

void pulse(byte pin, int count, unsigned long onMs, unsigned long offMs) {
  setLeds(false, false);
  for (int i = 0; i < count; ++i) {
    digitalWrite(pin, HIGH);
    delay(onMs);
    digitalWrite(pin, LOW);
    if (i + 1 < count) {
      delay(offMs);
    }
  }
}

void signalSuccess(const String &code) {
  if (code == "ALREADY_CHECKED_IN") {
    pulse(GREEN_LED_PIN, 2, 180, 120);
  } else {
    pulse(GREEN_LED_PIN, 1, 700, 0);
  }
}

// This confirms only that the tag was read and placed in RAM for delivery.
// It must stay visually distinct from the later, server-confirmed success.
void signalTagAccepted() {
  pulse(GREEN_LED_PIN, 1, 250, 0);
}

void signalBusinessFailure() {
  pulse(RED_LED_PIN, 1, 700, 0);
}

void signalConfigurationFailure() {
  pulse(RED_LED_PIN, 2, 250, 180);
}

void signalTransientFailure() {
  pulse(RED_LED_PIN, 3, 160, 130);
}

[[noreturn]] void haltWithConfigurationFailure(const __FlashStringHelper *message) {
  Serial.println(message);
  while (true) {
    signalConfigurationFailure();
    delay(1500);
  }
}

bool ensureWifiConnected() {
  if (WiFi.status() == WL_CONNECTED) {
    if (!wifiWasConnected) {
      Serial.println(F("Wi-Fi connected"));
      wifiWasConnected = true;
    }
    return true;
  }

  wifiWasConnected = false;
  unsigned long now = millis();
  if (isTimeDue(now, nextWifiConnectAttemptAt)) {
    Serial.println(F("Connecting to Wi-Fi..."));
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    nextWifiConnectAttemptAt = now + WIFI_CONNECT_RETRY_MS;
  }
  return false;
}

void connectWifiForCredentialProvisioning() {
  while (!ensureWifiConnected()) {
    delay(WIFI_STATUS_RECHECK_MS);
  }
}

String uidToCanonicalHex(const MFRC522::Uid &uid) {
  if (uid.size != 4 && uid.size != 7 && uid.size != 10) {
    return String();
  }
  const char hex[] = "0123456789ABCDEF";
  String output;
  output.reserve(uid.size * 2);
  for (byte index = 0; index < uid.size; ++index) {
    output += hex[(uid.uidByte[index] >> 4) & 0x0F];
    output += hex[uid.uidByte[index] & 0x0F];
  }
  return output;
}

// An idempotency key must change after a board reset but must never delay NFC
// delivery for network time synchronization.  This is non-secret session
// jitter, mixed from an unused analog input and timer samples; it is not used
// for authentication.
uint32_t mixRequestIdSession(uint32_t value) {
  value ^= value >> 16;
  value *= 0x7FEB352DUL;
  value ^= value >> 15;
  value *= 0x846CA68BUL;
  value ^= value >> 16;
  return value;
}

void initializeRequestIdSession() {
  uint32_t seed = micros();
  for (byte sample = 0; sample < 16; ++sample) {
    uint32_t analogSample = static_cast<uint32_t>(analogRead(REQUEST_ID_JITTER_PIN));
    seed ^= analogSample << ((sample % 3) * 10);
    seed ^= micros();
    seed = mixRequestIdSession(seed + 0x9E3779B9UL + sample);
    delayMicroseconds(17);
  }
  requestSessionId = seed == 0 ? 0xA5A5A5A5UL : seed;
}

// The ID is generated immediately when the tag enters the RAM queue.  It
// contains the per-boot session, capture time, and monotonic tag sequence.
void assignRequestId(PendingCheckIn &pending) {
  if (pending.requestId[0] != '\0') {
    return;
  }

  int written = snprintf(pending.requestId, sizeof(pending.requestId),
                         "R%08lX-%08lX-%08lX",
                         static_cast<unsigned long>(requestSessionId),
                         pending.capturedAtMicros,
                         pending.sequenceNo);
  if (written < 0
      || static_cast<size_t>(written) >= sizeof(pending.requestId)) {
    haltWithConfigurationFailure(F("Request ID buffer is too small"));
  }

  // This ID is opaque and non-secret.  The actual card UID and credential are
  // deliberately still not printed.
  Serial.print(F("Check-in request ID: "));
  Serial.println(pending.requestId);
}

void initializeRfid() {
  SPI.begin();
  rfid.PCD_Init();
  byte rfidVersion = rfid.PCD_ReadRegister(rfid.VersionReg);
  if (rfidVersion == 0x00 || rfidVersion == 0xFF) {
    Serial.println(F("MFRC522 communication failed"));
  } else {
    Serial.print(F("MFRC522 ready; version: 0x"));
    Serial.println(rfidVersion, HEX);
  }
}

DeviceResponse readResponse() {
  int status = httpClient.responseStatusCode();
  if (status < 0) {
    return {status, String(), 0, false};
  }
  int retryAfterSeconds = 0;
  while (httpClient.headerAvailable()) {
    String name = httpClient.readHeaderName();
    String value = httpClient.readHeaderValue();
    if (name.equalsIgnoreCase("Retry-After")) {
      int parsedSeconds = value.toInt();
      retryAfterSeconds = parsedSeconds >= 1 && parsedSeconds <= 300
                            ? parsedSeconds
                            : 0;
    }
  }
  String body = httpClient.responseBody();
  JsonDocument document;
  DeserializationError error = deserializeJson(document, body);
  String code = error ? String() : document["code"].as<String>();
  return {status, code, retryAfterSeconds, !error && code.length() > 0};
}

DeviceResponse postCheckIn(const char *uid, const char *requestId) {
  JsonDocument request;
  request["uid"] = uid;
  request["requestId"] = requestId;
  String body;
  serializeJson(request, body);

  httpClient.setHttpResponseTimeout(NETWORK_TIMEOUT_MS);
  httpClient.beginRequest();
  httpClient.post("/api/v1/device/check-ins");
  httpClient.sendHeader("X-Device-Code", DEVICE_CODE);
  httpClient.sendHeader("X-Device-Key", DEVICE_KEY);
  httpClient.sendHeader("Content-Type", "application/json; charset=UTF-8");
  httpClient.sendHeader("Content-Length", body.length());
  httpClient.beginBody();
  httpClient.print(body);
  httpClient.endRequest();
  DeviceResponse response = readResponse();
  httpClient.stop();
  return response;
}

DeviceResponse testCredential() {
  httpClient.setHttpResponseTimeout(NETWORK_TIMEOUT_MS);
  httpClient.beginRequest();
  httpClient.post("/api/v1/device/credential-tests");
  httpClient.sendHeader("X-Device-Code", DEVICE_CODE);
  httpClient.sendHeader("X-Device-Key", DEVICE_KEY);
  httpClient.sendHeader("Content-Length", 0);
  httpClient.endRequest();
  DeviceResponse response = readResponse();
  httpClient.stop();
  return response;
}

bool isRetryable(const DeviceResponse &response) {
  return response.httpStatus < 0 || response.httpStatus == 429
         || response.httpStatus == 500 || response.httpStatus == 503;
}

bool isConfigurationFailure(const DeviceResponse &response) {
  return response.httpStatus == 401
         || response.code == "DEVICE_NOT_ACTIVE"
         || response.code == "DEVICE_STATE_CHANGED"
         || response.code == "REQUEST_ID_CONFLICT";
}

unsigned long retryDelay(const DeviceResponse &response, int retryIndex) {
  if ((response.httpStatus == 429 || response.httpStatus == 503)
      && response.retryAfterSeconds > 0) {
    return static_cast<unsigned long>(response.retryAfterSeconds) * 1000UL;
  }
  return RETRY_DELAYS_MS[retryIndex];
}

void signalFinalResult(const DeviceResponse &response) {
  if (response.validJson
      && (response.httpStatus == 200 || response.httpStatus == 201)
      && (response.code == "CHECKED_IN" || response.code == "LATE"
          || response.code == "ALREADY_CHECKED_IN")) {
    signalSuccess(response.code);
  } else if (isRetryable(response) || !response.validJson) {
    signalTransientFailure();
  } else if (isConfigurationFailure(response)) {
    signalConfigurationFailure();
  } else {
    signalBusinessFailure();
  }
}

bool isTimeDue(unsigned long now, unsigned long due) {
  return static_cast<long>(now - due) >= 0;
}

bool isRecentDuplicate(const String &uid, unsigned long now) {
  return lastQueuedUid[0] != '\0'
         && uid == lastQueuedUid
         && now - lastQueuedAt < DUPLICATE_TAG_WINDOW_MS;
}

bool enqueueCheckIn(const String &uid) {
  unsigned long now = millis();
  if (isRecentDuplicate(uid, now)) {
    Serial.println(F("Duplicate tag ignored"));
    return true;
  }
  if (pendingCount == PENDING_CHECK_IN_CAPACITY) {
    Serial.println(F("Check-in queue full"));
    return false;
  }

  PendingCheckIn &pending = pendingCheckIns[pendingTail];
  uid.toCharArray(pending.uid, sizeof(pending.uid));
  pending.requestId[0] = '\0';
  pending.capturedAtMicros = micros();
  pending.sequenceNo = ++tagCounter;
  assignRequestId(pending);
  pending.retryCount = 0;
  pending.nextAttemptAt = now + DISPATCH_DELAY_MS;

  pendingTail = (pendingTail + 1) % PENDING_CHECK_IN_CAPACITY;
  ++pendingCount;
  uid.toCharArray(lastQueuedUid, sizeof(lastQueuedUid));
  lastQueuedAt = now;

  Serial.print(F("NFC tag queued; pending: "));
  Serial.println(pendingCount);
  return true;
}

void removePendingHead() {
  pendingHead = (pendingHead + 1) % PENDING_CHECK_IN_CAPACITY;
  --pendingCount;
}

void processPendingCheckIn() {
  if (pendingCount == 0) {
    return;
  }

  PendingCheckIn &pending = pendingCheckIns[pendingHead];
  if (!isTimeDue(millis(), pending.nextAttemptAt)) {
    return;
  }

  if (!ensureWifiConnected()) {
    pending.nextAttemptAt = millis() + WIFI_STATUS_RECHECK_MS;
    return;
  }
  assignRequestId(pending);
  Serial.print(F("Sending check-in request ID: "));
  Serial.println(pending.requestId);
  DeviceResponse response = postCheckIn(pending.uid, pending.requestId);
  Serial.print(F("Check-in HTTP status: "));
  Serial.print(response.httpStatus);
  Serial.print(F(", code: "));
  Serial.println(response.code);

  if (isRetryable(response) && pending.retryCount < MAX_AUTOMATIC_RETRIES) {
    pending.nextAttemptAt = millis() + retryDelay(response, pending.retryCount);
    ++pending.retryCount;
    Serial.print(F("Check-in retry scheduled; pending: "));
    Serial.println(pendingCount);
    return;
  }

  signalFinalResult(response);
  removePendingHead();
}

void setup() {
  pinMode(RED_LED_PIN, OUTPUT);
  pinMode(GREEN_LED_PIN, OUTPUT);
  setLeds(false, false);
  Serial.begin(115200);
  Serial.println(F("Attend NFC boot"));
  initializeRequestIdSession();

  httpClient.setHttpResponseTimeout(NETWORK_TIMEOUT_MS);
  // WiFiNINA defaults to a 50-second blocking WiFi.begin() call. Bound it so
  // an unavailable access point cannot monopolize NFC scanning.
  WiFi.setTimeout(WIFI_BEGIN_TIMEOUT_MS);

  if (CREDENTIAL_PROVISIONING_MODE) {
    Serial.println(F("Mode: credential provisioning"));
    connectWifiForCredentialProvisioning();
    DeviceResponse response = testCredential();
    if (response.httpStatus == 200 && response.code == "CREDENTIAL_VALID") {
      signalSuccess(response.code);
      Serial.println(F("Credential valid; activate device, then disable provisioning mode"));
    } else {
      signalConfigurationFailure();
      Serial.println(F("Credential test failed"));
    }
  } else {
    Serial.println(F("Mode: NFC check-in"));
    // Normal attendance mode may scan immediately and retain tags in RAM
    // while Wi-Fi is reconnecting.
    ensureWifiConnected();
  }
  // Initialize the external SPI reader after WiFiNINA has initialized its
  // internal transport, matching the final hardware state used for scanning.
  initializeRfid();
  Serial.println(F("NFC scan mode ready"));
}

void loop() {
  // unsigned long now = millis();
  // if (SERIAL_LOOP_HEARTBEAT_ENABLED
  //     && now - lastLoopHeartbeatAt >= LOOP_HEARTBEAT_INTERVAL_MS) {
  //   Serial.println(F("NFC scan loop alive"));
  //   lastLoopHeartbeatAt = now;
  // }

  if (CREDENTIAL_PROVISIONING_MODE) {
    delay(1000);
    return;
  }

  if (rfid.PICC_IsNewCardPresent() && rfid.PICC_ReadCardSerial()) {
    Serial.println(F("NFC tag detected"));
    String uid = uidToCanonicalHex(rfid.uid);
    if (uid.length() == 0) {
      signalBusinessFailure();
    } else if (enqueueCheckIn(uid)) {
      // UID and credentials are deliberately never printed to Serial.
      signalTagAccepted();
    } else {
      signalBusinessFailure();
    }

    rfid.PICC_HaltA();
    rfid.PCD_StopCrypto1();
  }

  // Delivery happens after the card has already been accepted. This first
  // version still uses a synchronous HTTPS client while a request is active.
  processPendingCheckIn();
  delay(30);
}
