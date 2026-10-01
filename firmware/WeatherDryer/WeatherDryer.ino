// ESP8266 Arduino core 3.x, ArduinoJson 7.x, DHT sensor library + Adafruit Unified Sensor.
#include <Arduino.h>
#include <ESP8266WiFi.h>
#include <ESP8266HTTPClient.h>
#include <WiFiClientSecureBearSSL.h>
#include <ArduinoJson.h>
#include <Servo.h>
#include <DHT.h>
#include <time.h>
#include "secrets.h"
#include "firebase_ca.h"

constexpr uint8_t RAIN_PIN = D0, FLOW_PIN = D2, SERVO_PIN = D4;
constexpr uint8_t RAIN_LEVEL = LOW;
constexpr double FLOW_HZ_PER_LPM = 5.5; // EXAMPLE ONLY: calibrate your particular YF-S401.
DHT dht(D3, DHT11);
Servo servo;
volatile uint32_t pulses = 0;
uint64_t totalPulses = 0;
uint32_t sampledAt = 0, dhtAt = 0, candidateAt = 0, netAt = 0, authAt = 0;
uint32_t retryMs = 2000;
bool raining = false, candidate = false, automatic = true, readNext = true;
int angle = 0;
float temperatureC = NAN, humidityPct = NAN;
double flowLpm = 0, totalMl = 0;
String idToken, appliedId = "boot";
BearSSL::X509List *trustAnchors = nullptr;
uint32_t diagnosticAt = 0;
void reportFailure(const char *stage, int code, const String &response) {
  Serial.printf("[%s] HTTP %d\n", stage, code);
  // Never print request URLs, credentials, or successful authentication responses.
  JsonDocument error;
  if (code > 0 && !deserializeJson(error, response)) {
    const char *message = error["error"]["message"] | "";
    if (!*message) message = error["error"] | "";
    Serial.println(message);
  }
}
void IRAM_ATTR onPulse() { ++pulses; }
uint64_t epochMs() { return static_cast<uint64_t>(time(nullptr)) * 1000ULL; }

// Bounded synchronous REST reference adapter. See README for latency limits.
int request(const String &url, const char *method, const String &payload, String &response) {
  BearSSL::WiFiClientSecure client;
  client.setTrustAnchors(trustAnchors);
  client.setTimeout(2500);
  HTTPClient http;
  http.setTimeout(2500);
  if (!http.begin(client, url)) return -1;
  http.addHeader("Content-Type", "application/json");
  const int code = http.sendRequest(method, payload);
  if (code > 0) response = http.getString();
  if (code < 0) {
    Serial.printf("[Network] %s\n", HTTPClient::errorToString(code).c_str());
    char detail[160] = {};
    const int sslError = client.getLastSSLError(detail, sizeof(detail));
    if (sslError) Serial.printf("[TLS] %d: %s\n", sslError, detail);
  }
  http.end();
  return code;
}
bool authenticate() {
  JsonDocument body;
  body["email"] = DEVICE_EMAIL; body["password"] = DEVICE_PASSWORD;
  body["returnSecureToken"] = true;
  String payload, response; serializeJson(body, payload);
  const int code = request(String("https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=") + FIREBASE_API_KEY,
                           "POST", payload, response);
  JsonDocument data;
  if (code != 200) { reportFailure("Auth", code, response); return false; }
  if (deserializeJson(data, response)) { Serial.println(F("[Auth] Invalid JSON")); return false; }
  idToken = data["idToken"].as<String>();
  authAt = millis();
  if (idToken.length()) Serial.println(F("[Auth] OK"));
  return idToken.length() > 0;
}
String deviceUrl(const char *child) {
  return String(DATABASE_URL) + "/devices/" + DEVICE_ID + child + ".json?auth=" + idToken;
}
bool pollCommand() {
  String response;
  const int code = request(deviceUrl("/control"), "GET", "", response);
  if (code == 401) idToken = "";
  if (code != 200) { reportFailure("Read control", code, response); return false; }
  JsonDocument command;
  if (deserializeJson(command, response)) return false;
  if (command.isNull()) return true;
  const String id = command["commandId"] | "";
  const String mode = command["mode"] | "";
  if (id.isEmpty() || id == appliedId) return true;
  if (!command["targetAngle"].is<int>() || !command["expiresAt"].is<uint64_t>() || !command["issuedAt"].is<uint64_t>()) return true;
  const int target = command["targetAngle"];
  const uint64_t expires = command["expiresAt"], issued = command["issuedAt"], now = epochMs();
  if (expires <= now || expires > now + 20000 || issued > now + 5000 || expires <= issued || expires - issued > 20000) return true;
  if ((mode != "AUTO" && mode != "MANUAL") || (target != 0 && target != 90)) return true;
  const bool resetTotal = id.startsWith("reset-total:");
  if (resetTotal) {
    noInterrupts(); pulses = 0; totalPulses = 0; interrupts();
    totalMl = 0;
    Serial.println(F("[Control] Total water reset to 0"));
  }
  automatic = mode == "AUTO";
  angle = automatic ? (raining ? 90 : 0) : target;
  servo.write(angle);
  appliedId = id; // Ack means commanded angle, not measured mechanical position.
  return true;
}
bool publish() {
  JsonDocument body;
  auto t = body["telemetry"].to<JsonObject>();
  if (isfinite(temperatureC)) t["temperatureC"] = temperatureC;
  if (isfinite(humidityPct)) t["humidityPct"] = humidityPct;
  t["flowLpm"] = flowLpm; t["totalMl"] = totalMl; t["raining"] = raining;
  t["updatedAt"][".sv"] = "timestamp";
  auto r = body["reported"].to<JsonObject>();
  r["mode"] = automatic ? "AUTO" : "MANUAL";
  r["servoAngle"] = angle; r["appliedCommandId"] = appliedId;
  String payload, response; serializeJson(body, payload);
  const int code = request(deviceUrl(""), "PATCH", payload, response);
  if (code == 401) idToken = "";
  if (code != 200) reportFailure("Publish", code, response);
  else Serial.println(F("[Publish] OK: telemetry sent"));
  return code == 200;
}
void setup() {
  Serial.begin(115200); dht.begin();
  Serial.println(F("\nWeatherDryer Firebase: starting"));
  pinMode(RAIN_PIN, INPUT); pinMode(FLOW_PIN, INPUT_PULLUP);
  raining = candidate = digitalRead(RAIN_PIN) == RAIN_LEVEL;
  candidateAt = sampledAt = millis();
  angle = raining ? 90 : 0; servo.attach(SERVO_PIN); servo.write(angle);
  attachInterrupt(digitalPinToInterrupt(FLOW_PIN), onPulse, FALLING);
  trustAnchors = new BearSSL::X509List(GOOGLE_ROOT_CA_BUNDLE);
  Serial.printf("[TLS] Loaded %u trust anchors\n", (unsigned)trustAnchors->getCount());
  WiFi.persistent(false); WiFi.mode(WIFI_STA); WiFi.setAutoReconnect(true);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  configTime(0, 0, "pool.ntp.org", "time.google.com");
}
void loop() {
  uint32_t now = millis();
  if (now - diagnosticAt >= 10000) {
    diagnosticAt = now;
    if (WiFi.status() != WL_CONNECTED) {
      Serial.printf("[WiFi] Not connected, status=%d. Check SSID/password and 2.4 GHz network.\n", (int)WiFi.status());
    } else if (time(nullptr) <= 1700000000) {
      Serial.println(F("[NTP] WiFi connected; waiting for Internet/time sync"));
    } else {
      Serial.printf("[Ready] WiFi OK, clock OK, auth=%s, freeHeap=%u\n", idToken.isEmpty() ? "pending" : "OK", ESP.getFreeHeap());
    }
  }
  const bool raw = digitalRead(RAIN_PIN) == RAIN_LEVEL;
  if (raw != candidate) { candidate = raw; candidateAt = now; }
  if (candidate != raining && now - candidateAt >= 300) raining = candidate;
  if (automatic) {
    const int desired = raining ? 90 : 0;
    if (angle != desired) { angle = desired; servo.write(angle); }
  }
  if (now - sampledAt >= 1000) {
    noInterrupts(); const uint32_t count = pulses; pulses = 0; interrupts();
    const uint32_t elapsed = now - sampledAt; sampledAt = now; totalPulses += count;
    flowLpm = count * 1000.0 / elapsed / FLOW_HZ_PER_LPM;
    totalMl = static_cast<double>(totalPulses) * 1000.0 / (FLOW_HZ_PER_LPM * 60.0);
  }
  if (now - dhtAt >= 2000) {
    dhtAt = now; humidityPct = dht.readHumidity(); temperatureC = dht.readTemperature();
  }
  if (WiFi.status() == WL_CONNECTED && time(nullptr) > 1700000000 && now - netAt >= retryMs) {
    bool ok;
    if (idToken.isEmpty() || now - authAt >= 3000000UL) ok = authenticate(); // Renew at 50 minutes.
    else {
      ok = readNext ? pollCommand() : publish();
      if (ok) readNext = !readNext;
    }
    netAt = millis(); // Backoff starts after the request completes.
    retryMs = ok ? 1000 : (retryMs >= 15000 ? 30000 : retryMs * 2);
    if (!ok) Serial.println(F("Firebase unavailable: retry scheduled (check WiFi, CA, account, rules)."));
  }
  yield();
}
