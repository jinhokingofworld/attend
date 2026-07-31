/*
 * Attend NFC device firmware for WiFiNINA-compatible Arduino boards.
 *
 * Required libraries:
 * - MFRC522
 * - WiFiNINA
 * - ArduinoHttpClient
 * - ArduinoJson 7
 * - ArduinoECCX08
 *
 * Production uses WiFiSSLClient and verifies SERVER_HOST against the root
 * certificates provisioned with the WiFiNINA Firmware Updater. Plain HTTP is
 * intentionally not implemented.
 */

#include <ArduinoECCX08.h>
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

MFRC522 rfid(RFID_SELECT_PIN, RFID_RESET_PIN);
WiFiSSLClient tlsClient;
HttpClient httpClient(tlsClient, SERVER_HOST, SERVER_PORT);
byte bootRandom[4];
unsigned long tagCounter = 0;

struct DeviceResponse {
  int httpStatus;
  String code;
  int retryAfterSeconds;
  bool validJson;
};

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

void connectWifi() {
  while (WiFi.status() != WL_CONNECTED) {
    Serial.println(F("Connecting to Wi-Fi..."));
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    unsigned long startedAt = millis();
    while (WiFi.status() != WL_CONNECTED
           && millis() - startedAt < NETWORK_TIMEOUT_MS) {
      delay(250);
    }
    if (WiFi.status() != WL_CONNECTED) {
      signalTransientFailure();
      delay(2000);
    }
  }
  Serial.println(F("Wi-Fi connected"));
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

String nextRequestId() {
  ++tagCounter;
  char requestId[24];
  snprintf(requestId, sizeof(requestId), "B%02X%02X%02X%02X-%08lX",
           bootRandom[0], bootRandom[1], bootRandom[2], bootRandom[3], tagCounter);
  return String(requestId);
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

DeviceResponse postCheckIn(const String &uid, const String &requestId) {
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

void handleTag(const String &uid) {
  String requestId = nextRequestId();
  DeviceResponse response = {-1, String(), 0, false};
  for (int attempt = 0; attempt <= MAX_AUTOMATIC_RETRIES; ++attempt) {
    connectWifi();
    response = postCheckIn(uid, requestId);
    if (!isRetryable(response) || attempt == MAX_AUTOMATIC_RETRIES) {
      break;
    }
    delay(retryDelay(response, attempt));
  }

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

void setup() {
  pinMode(RED_LED_PIN, OUTPUT);
  pinMode(GREEN_LED_PIN, OUTPUT);
  setLeds(false, false);
  Serial.begin(115200);

  if (!ECCX08.begin() || !ECCX08.random(bootRandom, sizeof(bootRandom))) {
    haltWithConfigurationFailure(F("Secure boot random source unavailable"));
  }

  SPI.begin();
  rfid.PCD_Init();
  httpClient.setHttpResponseTimeout(NETWORK_TIMEOUT_MS);
  connectWifi();

  if (CREDENTIAL_PROVISIONING_MODE) {
    DeviceResponse response = testCredential();
    if (response.httpStatus == 200 && response.code == "CREDENTIAL_VALID") {
      signalSuccess(response.code);
      Serial.println(F("Credential valid; activate device, then disable provisioning mode"));
    } else {
      signalConfigurationFailure();
      Serial.println(F("Credential test failed"));
    }
  }
}

void loop() {
  if (CREDENTIAL_PROVISIONING_MODE) {
    delay(1000);
    return;
  }
  if (!rfid.PICC_IsNewCardPresent() || !rfid.PICC_ReadCardSerial()) {
    delay(30);
    return;
  }

  String uid = uidToCanonicalHex(rfid.uid);
  if (uid.length() == 0) {
    signalBusinessFailure();
  } else {
    // UID and credentials are deliberately never printed to Serial.
    handleTag(uid);
  }

  rfid.PICC_HaltA();
  rfid.PCD_StopCrypto1();
  delay(500);
}
