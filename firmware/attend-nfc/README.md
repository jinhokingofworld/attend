# Attend NFC 펌웨어

대상은 MFRC522와 ATECC508/608 보안 칩이 있는 WiFiNINA 호환 보드(예: Arduino
MKR WiFi 1010, Nano 33 IoT)다. 다른 보드는 TLS 인증서 검증과 부팅 난수원을 다시
설계하기 전까지 지원하지 않는다.

## 준비

1. Arduino IDE/CLI에서 `MFRC522`, `WiFiNINA`, `ArduinoHttpClient`, `ArduinoJson 7`,
   `ArduinoECCX08`을 설치한다.
2. WiFiNINA Firmware Updater로 운영 서버 인증서의 신뢰 root를 보드에 넣는다.
3. `config.example.h`를 `config.h`로 복사하고 Wi-Fi·장치 값을 입력한다.
4. `CREDENTIAL_PROVISIONING_MODE=true`로 업로드해 초록 신호와 관리자 화면의
   credential 시험 시각을 확인한다.
5. 관리자가 장치를 활성화한 뒤 `false`로 바꾸고 다시 업로드한다.

`config.h`와 빌드 산출물은 비밀정보다. Git, 메신저, CI artifact에 올리지 않는다.
서버 hostname 인증에 실패하는 경우 평문 HTTP로 우회하지 말고 인증서와 보드
시간·WiFiNINA firmware를 바로잡는다.

## 신호 계약

| 신호 | 의미 | 자동 재시도 |
|---|---|---|
| 초록 1회(700 ms) | 신규 정상/지각 기록 | 없음 |
| 초록 2회 | 이미 출석됨 | 없음 |
| 빨강 1회(700 ms) | 카드·날짜 등 결정적 업무 거부 | 없음 |
| 빨강 2회 | 인증·장치 상태·설정 오류 | 없음 |
| 빨강 3회 | timeout, 잘못된 응답, 429/500/503 재시도 소진 | 최대 3회 후 표시 |

429·503은 `Retry-After`, 500·무응답은 2/5/15초 간격을 사용한다. 재시도 동안
UID와 requestId는 바꾸지 않는다. LED는 보조 신호이므로 파일럿에서는 HTTP 결과와
DB 기록도 함께 대사한다.
