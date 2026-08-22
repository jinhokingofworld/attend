#pragma once

// Copy this file to config.h. config.h is ignored by Git and must never be
// attached to an issue, build log or firmware source archive.
constexpr char WIFI_SSID[] = "REPLACE_WITH_WIFI_SSID";
constexpr char WIFI_PASSWORD[] = "REPLACE_WITH_WIFI_PASSWORD";
constexpr char SERVER_HOST[] = "attendance.example.org";
constexpr int SERVER_PORT = 443;
constexpr char DEVICE_CODE[] = "REPLACE_WITH_DEVICE_CODE";
constexpr char DEVICE_KEY[] = "REPLACE_WITH_DEVICE_KEY";

// true: call credential-tests only and refuse NFC check-ins. This safe mode is
// the default for every newly copied config.h.
// false: normal ACTIVE-device operation. Change this only after the system
// administrator confirms credential test evidence and activates the device.
constexpr bool CREDENTIAL_PROVISIONING_MODE = true;
