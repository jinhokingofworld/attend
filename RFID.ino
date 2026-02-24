#include <SPI.h>
#include <MFRC522.h>
#include <WiFiNINA.h>
#include <ArduinoHttpClient.h>

#define RST_PIN 9  // Configurable, see typical pin layout above
#define SS_PIN 10  // Configurable, see typical pin layout above
#define RED_PIN 2
#define GREEN_PIN 3

MFRC522 mfrc522(SS_PIN, RST_PIN);  // Create MFRC522 instance.
unsigned long greenLedOnTime = 0;
bool greenLedOn = false;

char ssid[] = "와이파이번호";
char pass[] = "와이파이비번";

char server[] = "서버 IP";
int port = 8080;
bool result = false;

int status = WL_IDLE_STATUS;
WiFiClient client;
HttpClient httpClient = HttpClient(client, server, port);

/**
 * Initialize.
 */
void setup() {
  pinMode(RED_PIN, OUTPUT);
  pinMode(GREEN_PIN, OUTPUT);

  Serial.begin(9600);
  Serial.println("Attempting to connect to ");
  Serial.print(ssid);
  Serial.println("");

  WiFi.begin(ssid, pass);

  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.println(".");
  }

  Serial.println("WiFi Connected");
  Serial.print("AP Name: ");
  Serial.println(ssid);

  // print your WiFi shield's IP address:
  IPAddress ip = WiFi.localIP();
  Serial.print("IP Address: ");
  Serial.println(ip);

  SPI.begin();         // Init SPI bus
  mfrc522.PCD_Init();  // Init MFRC522 card
}

/**
 * Main loop.
 */
void loop() {
  // 새 카드가 읽혀지면 실행됨
  if (mfrc522.PICC_IsNewCardPresent() && mfrc522.PICC_ReadCardSerial()) {

    Serial.print(F("Card UID:"));
    dump_byte_array(mfrc522.uid.uidByte, mfrc522.uid.size);
    Serial.println();

    //http 요청
    sendAttendRequest(1);

    //LED 출력
    if (result == true) {
      digitalWrite(GREEN_PIN, HIGH);
      delay(200);
      digitalWrite(GREEN_PIN, LOW);
    } else {
      digitalWrite(RED_PIN, HIGH);
      delay(200);
      digitalWrite(RED_PIN, LOW);
    }
  }

  //rfid 종료
  mfrc522.PICC_HaltA();
  mfrc522.PCD_StopCrypto1();
  result = false;
  delay(200);
}

/**
 * Helper routine to dump a byte array as hex values to Serial.
 */
void dump_byte_array(byte *buffer, byte bufferSize) {
  for (byte i = 0; i < bufferSize; i++) {
    Serial.print(buffer[i] < 0x10 ? "0" : "");
    Serial.print(buffer[i], HEX);
  }
}

void sendAttendRequest(int uid) {
  char jsonBody[32];
  snprintf(jsonBody, sizeof(jsonBody),
           "{ \"uid\": \"%s\" }", uid);

  if (client.connect(server, 8080)) {
    Serial.println("Connected to server");

    client.println("POST /attendance/1 HTTP/1.1");
    client.print("Host: ");
    client.println(server);
    client.println("Content-Type: application/json");
    client.println("Content-Length: ");
    client.println(strlen(jsonBody));
    client.println();  //end of header
    client.print(jsonBody);

    // while (client.connected()) {
    //     while (client.available()) {
    //         char c = client.read();
    //         Serial.print(c);
    //     }
    // }
    result = true;
  } else {
    Serial.println("Connection failed");
    result = false;
  }
}