# Arduino 실제 출석 테스트 준비 안내서

이 문서는 **Nano 33 IoT + RFID 카드**로 실제 출석을 시험하기 위한 순서다.

목표는 다음 한 줄로 요약할 수 있다.

```text
카드를 Nano에 댄다
→ Nano가 인터넷으로 서버에 알린다
→ 서버가 출석을 저장한다
→ 관리자 화면에서 출석 결과를 확인한다
```

이 문서에서는 운영 서버가 아니라, `test.jinhokingoftheworld.cloud`라는
**테스트 주소**를 사용한다. 테스트 데이터만 사용하고 실제 운영 데이터는 넣지 않는다.

## 0. 전체 그림 먼저 보기

아래 그림에서 화살표는 정보가 지나가는 길이다.

```text
Nano + RFID 카드
        |
        | HTTPS (잠긴 편지처럼 암호화된 통신)
        v
test.jinhokingoftheworld.cloud
        |
        v
Cloudflare
        |
        | Cloudflare Tunnel (Mac이 바깥으로 먼저 연결한 안전한 통로)
        v
Mac의 Caddy
        |
        v
Spring 출석 서버
        |
        v
테스트 PostgreSQL DB
```

각 부품이 필요한 이유는 다음과 같다.

| 부품 | 하는 일 | 필요한 이유 |
| --- | --- | --- |
| 도메인 | 사람이 읽을 수 있는 서버 이름 | Nano가 어느 서버로 갈지 알아야 한다. |
| Cloudflare | 인터넷에서 HTTPS를 제공 | Nano와 서버 사이의 통신을 보호한다. |
| Cloudflare Tunnel | Cloudflare와 Mac을 연결 | 공유기 포트포워딩 없이도 Mac을 외부에 공개한다. |
| Caddy | 요청을 Spring 서버로 전달 | HTTPS 앞단과 프록시 보안 규칙을 담당한다. |
| Spring 서버 | 카드 정보를 출석으로 처리 | 장치·카드·출석 규칙을 확인한다. |
| PostgreSQL | 출석 기록을 보관 | 서버를 껐다 켜도 기록이 남는다. |

## 1. 먼저 알아둘 중요한 약속

### 테스트 주소를 사용한다

이번에는 아래 주소만 사용한다.

```text
test.jinhokingoftheworld.cloud
```

나중에 실제 운영을 시작할 때는 별도 주소(예: `attend.jinhokingoftheworld.cloud`)를
사용한다. 테스트 중 실수해도 실제 운영에 영향을 주지 않게 하기 위해서다.

### 비밀값은 절대 Git에 올리지 않는다

다음은 비밀번호와 같은 비밀값이다.

- `.env`
- `firmware/attend-nfc/config.h`
- Cloudflare Tunnel token
- `DEVICE_KEY`

이 파일과 값은 캡처해서 공유하거나 Git에 `git add`하면 안 된다.

### 이미지와 컨테이너는 다르다

- **이미지**: 서버 프로그램의 포장 상자다.
- **컨테이너**: 그 상자를 실제로 실행한 것이다.

`.env`만 바꿨다면 프로그램 코드가 바뀐 것이 아니다. 그러므로 이미지를 다시 만들지
않고 컨테이너만 다시 만들면 된다. Java 코드나 Dockerfile을 바꿨을 때만 이미지를
다시 빌드한다.

## 2. 준비물 확인

시작하기 전에 아래가 준비되어야 한다.

- [ ] Docker Desktop이 실행 중이다.
- [ ] 테스트용 PostgreSQL DB와 접속 정보가 있다.
- [ ] `jinhokingoftheworld.cloud` 도메인을 Cloudflare 계정에 넣을 수 있다.
- [ ] Nano 33 IoT, RFID 리더, 카드가 있다.
- [ ] Nano가 접속할 Wi-Fi가 있다.
- [ ] 테스트용 시스템 관리자 계정이 있다. 없으면 서버 기동 후 만들어야 한다.

> 이 문서의 DB는 테스트 전용이라는 전제다. 운영 DB를 사용하면 안 된다.

## 3. Cloudflare에 도메인 연결하기

### 왜 먼저 하나요?

Nano는 `test.jinhokingoftheworld.cloud`라는 이름으로 서버를 찾는다. Cloudflare가
이 이름을 관리해야 Tunnel과 HTTPS를 연결할 수 있다.

### 할 일

1. Cloudflare 대시보드에서 `jinhokingoftheworld.cloud`를 추가한다.
2. Cloudflare가 알려 주는 네임서버 두 개를 확인한다.
3. 도메인을 구매한 사이트에서 네임서버를 그 두 값으로 바꾼다.
4. Cloudflare 대시보드의 도메인 상태가 **Active**가 될 때까지 기다린다.

### 성공 확인

- [ ] Cloudflare 도메인 화면에 `Active`가 보인다.

`Active` 전에는 Tunnel 공개 주소를 연결하지 않는다. 아직 Cloudflare가 이 도메인의
길 안내를 맡지 못한 상태이기 때문이다.

## 4. `.env` 파일 작성하기

### 왜 필요한가요?

`.env`는 서버에게 “어떤 DB를 쓰고, 어떤 주소로 공개하고, 어떤 비밀값을 사용할지”
알려 주는 메모장이다.

프로젝트 루트의 [`.env.example`](../.env.example)를 참고해 프로젝트 루트에 `.env`를
작성한다. 기존 테스트 `.env`가 있다면 통째로 덮어쓰지 말고, 이미 맞는 DB 값은
유지한다.

### 반드시 확인할 값

```dotenv
# 브라우저와 Nano가 접속할 테스트 주소
PUBLIC_HOST=test.jinhokingoftheworld.cloud
PUBLIC_BASE_URL=https://test.jinhokingoftheworld.cloud
ACME_CONTACT_EMAIL=본인_이메일

# 이번 테스트에 필요한 기능
ADMIN_WRITE_ENABLED=true
DEVICE_API_ENABLED=true
ATTENDANCE_SCHEDULER_ENABLED=false
```

`PUBLIC_HOST`에는 `https://`나 `/api/...` 같은 경로를 넣지 않는다. 서버 이름만 쓴다.

### 랜덤 비밀값 세 개 만들기

아래 명령을 **세 번** 실행한다.

```bash
openssl rand -hex 32
```

세 출력값을 서로 다르게 아래 항목에 넣는다.

```dotenv
ACCOUNT_TOKEN_PEPPER=첫번째_출력값
DEVICE_CREDENTIAL_PEPPER=두번째_출력값
TRUSTED_PROXY_TOKEN=세번째_출력값
```

| 값 | 쓰는 곳 | 바꾸면 생기는 일 |
| --- | --- | --- |
| `ACCOUNT_TOKEN_PEPPER` | 관리자 초대·비밀번호 재설정 | 기존 관련 링크가 무효가 될 수 있다. |
| `DEVICE_CREDENTIAL_PEPPER` | Arduino 장치 비밀키 검증 | 기존 `DEVICE_KEY`가 작동하지 않을 수 있다. |
| `TRUSTED_PROXY_TOKEN` | Caddy와 Spring 서버 사이 확인 | Caddy를 거치지 않은 요청을 막는다. |

서버를 사용하기 시작한 뒤에는 이 세 값을 함부로 바꾸지 않는다.

### DB 값 확인

`.env`에는 아래 DB 관련 값도 실제 테스트 DB 값으로 들어 있어야 한다.

```dotenv
DB_URL=jdbc:postgresql://...
DB_USERNAME=...
DB_PASSWORD=...

FLYWAY_DB_URL=jdbc:postgresql://...
FLYWAY_DB_USERNAME=...
FLYWAY_DB_PASSWORD=...
MIGRATION_SOURCE_CLASS=NEW_OR_SAMPLE

RETENTION_DB_URL=jdbc:postgresql://...
RETENTION_DB_USERNAME=...
RETENTION_DB_PASSWORD=...
```

이 프로젝트는 앱 실행 계정, DB 구조를 바꾸는 migration 계정, 오래된 로그를 정리하는
retention 계정을 분리하도록 설계되어 있다. 테스트 환경이라도 빈칸이나 `...`를 넣으면
서버는 실행되지 않는다.

## 5. Caddy와 Cloudflare Tunnel 연결 준비

### 왜 둘 다 필요한가요?

Cloudflare는 Nano가 보는 **공개 HTTPS 문**이다. Caddy는 Mac 안에서 Spring 서버로
요청을 안전하게 전달하는 **안쪽 안내원**이다.

Cloudflare Tunnel은 Mac이 Cloudflare로 먼저 연결한다. 그래서 공유기에서 80·443 포트를
열거나 Mac의 공인 IP를 알아낼 필요가 없다.

### 꼭 기억할 점

현재 프로젝트의 [Caddyfile](../ops/caddy/Caddyfile)은 공개 서버에서 Caddy가 직접
ACME 인증서를 받는 구성이다. Tunnel 방식에서는 아래 구성이 필요하다.

1. Caddy는 Mac 내부용 인증서(`tls internal`)를 사용한다.
2. `cloudflared`는 Caddy의 HTTPS 포트로 연결한다.
3. Cloudflare는 외부 사용자와 Nano에게 공개 HTTPS 인증서를 제공한다.

따라서 Nano에 등록할 인증서는 Caddy 내부 인증서가 아니라
`test.jinhokingoftheworld.cloud`의 **Cloudflare 공개 인증서 체인**이다.

> Tunnel 전용 Caddy/Compose 설정은 현재 기본 파일에 포함되어 있지 않다. 기존 운영
> Caddyfile을 덮어쓰지 말고, Tunnel 전용 override 파일을 만들어 적용한다.

### Cloudflare Tunnel 만들기

Cloudflare 대시보드에서 다음을 수행한다.

1. `Networking` → `Tunnels`로 간다.
2. 새 Tunnel을 만들고 이름을 `attend-mac-test`처럼 정한다.
3. Mac에 `cloudflared` connector를 설치한다.
4. Cloudflare가 주는 Tunnel token으로 connector를 실행한다.
5. Public hostname을 추가한다.

```text
Hostname: test.jinhokingoftheworld.cloud
Service: https://localhost:443
```

Caddy가 `tls internal`을 쓰면 Tunnel의 origin 인증서 검증도 내부 인증서에 맞춰
설정해야 한다. 이 설정은 **Mac 내부의 Tunnel → Caddy 연결**에만 해당한다. Nano와
Cloudflare 사이의 공개 HTTPS 보안을 끄는 설정이 아니다.

### 성공 확인

- [ ] Cloudflare Tunnel 상태가 Healthy 또는 Connected다.
- [ ] Public hostname이 `test.jinhokingoftheworld.cloud`로 등록되어 있다.

## 6. 이미지 빌드와 DB migration

### 왜 migration을 하나요?

DB는 빈 공책이다. migration은 출석 기록을 쓸 표와 규칙을 공책에 만드는 과정이다.
이 프로젝트의 production profile은 앱이 시작될 때 자동으로 DB 구조를 바꾸지 않는다.
그래서 migration을 먼저 한 번 실행해야 한다.

### 6-1. 이미지 빌드

Java 코드나 Dockerfile을 바꾼 경우에만 빌드한다.

```bash
docker compose --env-file .env -f compose.prod.yaml build
docker compose --env-file .env -f compose.migration.yaml build
```

이미 빌드가 성공했고 코드가 바뀌지 않았다면 이 단계는 건너뛴다.

### 6-2. migration 실행

```bash
docker compose --env-file .env -f compose.migration.yaml run --rm migration
```

### 성공 확인

- [ ] 명령이 오류 없이 끝난다.
- [ ] DB에 migration version이 기록된다.

오류가 나면 App을 먼저 실행하지 말고 오류의 마지막 부분을 확인한다. 특히 DB 주소,
계정, 비밀번호, `MIGRATION_SOURCE_CLASS=NEW_OR_SAMPLE`을 다시 확인한다.

## 7. App과 Caddy 시작하기

```bash
docker compose --env-file .env -f compose.prod.yaml up -d
```

`.env`만 수정한 뒤 다시 적용할 때는 아래처럼 컨테이너만 다시 만든다.

```bash
docker compose --env-file .env -f compose.prod.yaml up -d --force-recreate
```

### 상태 확인

```bash
docker compose --env-file .env -f compose.prod.yaml ps
docker compose --env-file .env -f compose.prod.yaml logs -f app caddy
```

확인할 것:

- [ ] `app` 컨테이너가 재시작을 반복하지 않는다.
- [ ] `caddy` 컨테이너가 실행 중이다.
- [ ] Cloudflare Tunnel connector가 Connected다.
- [ ] 브라우저에서 `https://test.jinhokingoftheworld.cloud`가 열린다.

테스트 주소가 열리기 전에는 Arduino를 업로드하지 않는다. 먼저 컴퓨터 브라우저로
도메인·Tunnel·Caddy·앱의 길이 모두 연결됐는지 확인하는 것이 더 쉽고 빠르다.

## 8. 관리자 계정과 테스트 장치 만들기

### 관리자 계정

관리자 계정이 없다면 프로젝트의 one-time bootstrap 절차로 시스템 관리자 계정을
먼저 만든다. 관리자 사용자명과 비밀번호를 `.env`에 넣지 않는다.

### 장치 등록

브라우저에서 테스트 주소로 로그인한 뒤 시스템 관리 화면에서 `장치 등록`을 누른다.

입력값은 다음처럼 정한다.

| 항목 | 예시 | 누가 정하나요? |
| --- | --- | --- |
| 부서 | 테스트 부서 | 관리자 |
| 장치명 | 교육관 입구 테스트 Nano | 관리자 |
| `DEVICE_CODE` | `NFC-TEST-01` | 관리자 |
| `DEVICE_KEY` | 서버가 만든 긴 비밀값 | 서버가 자동 생성 |

`DEVICE_CODE`는 장치의 이름표이므로 직접 정한다. 다른 장치와 중복되면 안 된다.
`DEVICE_KEY`는 서버가 자동으로 만들며 **한 번만 보여 준다**.

### 아주 중요: 지금은 장치가 INACTIVE여야 한다

처음 만든 장치는 `INACTIVE` 상태다. 이것이 정상이다.

먼저 Nano가 올바른 서버·Wi-Fi·인증서·장치 키를 갖고 있는지 시험해야 한다. 이 시험이
성공하기 전에는 장치를 `ACTIVE`로 만들 수 없다.

## 9. Arduino `config.h` 작성

[config.example.h](../firmware/attend-nfc/config.example.h)를 복사해
`firmware/attend-nfc/config.h`를 만든다.

```cpp
#pragma once

constexpr char WIFI_SSID[] = "Wi-Fi 이름";
constexpr char WIFI_PASSWORD[] = "Wi-Fi 비밀번호";
constexpr char SERVER_HOST[] = "test.jinhokingoftheworld.cloud";
constexpr int SERVER_PORT = 443;
constexpr char DEVICE_CODE[] = "NFC-TEST-01";
constexpr char DEVICE_KEY[] = "장치_등록_직후_한번만_보인_값";

// 첫 연결 시험에서는 true
constexpr bool CREDENTIAL_PROVISIONING_MODE = true;
```

`SERVER_HOST`에는 다음을 넣지 않는다.

```text
https://test.jinhokingoftheworld.cloud  # https://를 넣으면 안 됨
test.jinhokingoftheworld.cloud/api/...  # 경로를 넣으면 안 됨
192.168.x.x                             # 이번 공개 도메인 방식에서는 사용하지 않음
```

`config.h`는 Git에서 제외되는 비밀 파일이다. 절대 커밋하지 않는다.

## 10. Nano의 WiFiNINA 인증서 준비

### 왜 하나요?

Nano는 처음 보는 HTTPS 서버를 바로 믿지 않는다. “이 주소가 진짜
`test.jinhokingoftheworld.cloud`인지” 확인할 수 있도록 Root Certificate를 넣어 줘야
한다.

### 할 일

1. Arduino IDE에서 Nano 33 IoT를 연결한다.
2. `Tools` → `WiFi101 / WiFiNINA Firmware Updater`를 연다.
3. NINA firmware가 호환되는 버전인지 확인하고 필요하면 업데이트한다.
4. Root certificate 업로드 화면에서 다음처럼 **도메인명만** 입력한다.

```text
test.jinhokingoftheworld.cloud
```

`https://`나 `/` 뒤의 경로는 넣지 않는다.

5. 인증서를 보드에 업로드한다.

이 단계는 Cloudflare Tunnel과 공개 hostname이 정상 동작한 뒤에 해야 한다. 그래야
Updater가 Cloudflare가 제공하는 공개 인증서 체인을 가져올 수 있다.

## 11. 첫 번째 Nano 시험: 카드 없이 연결만 확인

### 왜 카드를 아직 대지 않나요?

처음에는 RFID 카드 문제와 네트워크 문제를 섞지 않는다. Wi-Fi·HTTPS·장치 키가
정상인지부터 확인하면 문제가 생겼을 때 찾기 쉽다.

### 할 일

1. `CREDENTIAL_PROVISIONING_MODE = true`인지 확인한다.
2. Arduino IDE에서 Nano에 업로드한다.
3. Serial Monitor를 `115200` baud로 연다.
4. `Wi-Fi connected`가 나오는지 확인한다.
5. Caddy/App 로그에서 아래 요청을 확인한다.

```text
POST /api/v1/device/credential-tests
```

6. 성공 응답은 `200 CREDENTIAL_VALID`이다.

### 성공 신호

- [ ] Serial Monitor에 `Wi-Fi connected`가 보인다.
- [ ] Caddy/App 로그에 credential test 요청이 보인다.
- [ ] Nano가 초록 LED를 한 번 켠다.
- [ ] 관리자 화면에서 credential 시험 성공 증거가 보인다.

이 모드에서는 RFID 카드를 읽지 않는다. 정상이다.

## 12. 장치 활성화와 실제 RFID 출석 시험

### 12-1. 장치 활성화

credential test가 성공한 뒤에만 관리자 화면에서 장치를 `ACTIVE`로 바꾼다.

이 순서는 반드시 지킨다.

```text
INACTIVE 장치
→ provisioning 시험 성공
→ 관리자 화면에서 ACTIVE 전환
→ 실제 출석 허용
```

### 12-2. 실제 출석 모드로 변경

`config.h`에서 아래를 바꾼다.

```cpp
constexpr bool CREDENTIAL_PROVISIONING_MODE = false;
```

Nano에 다시 업로드한다.

### 12-3. 카드 태그

카드를 대기 전에 아래도 확인한다.

- [ ] 카드가 `ACTIVE` 상태다.
- [ ] 카드 소유자가 장치의 고정 부서에 속한다.
- [ ] 오늘 출석 날짜가 열려 있다.
- [ ] 카드 소유자가 오늘 출석 대상이다.

카드를 Nano에 댄다.

### 성공 확인

- [ ] Caddy/App 로그에 `POST /api/v1/device/check-ins`가 보인다.
- [ ] 새 출석이면 초록 LED가 한 번 켜진다.
- [ ] 같은 날 같은 카드를 다시 대면 초록 LED가 두 번 켜진다.
- [ ] 관리자 화면에서 실제 출석 기록이 보인다.

## 13. LED 뜻

| LED | 뜻 | 먼저 확인할 곳 |
| --- | --- | --- |
| 초록 1회 | 새 출석 또는 지각 기록 성공 | 관리자 화면의 출석 기록 |
| 초록 2회 | 이미 출석 처리됨 | 같은 카드의 기존 출석 |
| 빨강 1회 | 카드·날짜·출석 대상 등 업무 규칙 거부 | App 로그와 카드/출석 날짜 상태 |
| 빨강 2회 | 장치 키·장치 상태·설정 오류 | `DEVICE_CODE`, `DEVICE_KEY`, ACTIVE 상태 |
| 빨강 3회 | 네트워크/TLS/서버 오류 또는 재시도 소진 | Wi-Fi, Tunnel, Caddy, App 로그 |

## 14. 문제가 생겼을 때 확인 순서

문제는 바깥쪽부터 안쪽으로 확인한다.

```text
1. 브라우저에서 테스트 도메인이 열리는가?
2. Cloudflare Tunnel이 Connected인가?
3. Caddy와 App 컨테이너가 실행 중인가?
4. Nano가 Wi-Fi에 연결됐는가?
5. Nano에 Root Certificate를 등록했는가?
6. DEVICE_CODE와 DEVICE_KEY가 정확한가?
7. 장치가 올바른 상태(INACTIVE 또는 ACTIVE)인가?
8. 카드와 출석 날짜가 준비됐는가?
```

### 자주 하는 실수

| 실수 | 결과 | 해결 |
| --- | --- | --- |
| `SERVER_HOST`에 `https://`를 넣음 | Nano가 서버를 찾지 못할 수 있다. | 도메인명만 넣는다. |
| ACTIVE 장치에서 provisioning 시험 | `CREDENTIAL_TEST_NOT_ALLOWED` | 먼저 INACTIVE로 만든다. |
| credential test 전에 ACTIVE로 전환하려 함 | 활성화가 거부된다. | `true` 모드로 먼저 시험한다. |
| `DEVICE_CREDENTIAL_PEPPER`를 나중에 변경 | 기존 장치 키가 실패한다. | 원래 값으로 되돌리거나 장치 키를 다시 발급한다. |
| `compose.local.yaml`로 Nano를 연결하려 함 | HTTP/loopback이라 Nano가 연결할 수 없다. | Tunnel + Caddy 테스트 구성을 쓴다. |
| 카드가 등록되지 않았거나 다른 부서 카드 | 출석이 저장되지 않는다. | 카드·부서·출석 대상 상태를 확인한다. |

## 15. 완료 기준

아래 항목을 모두 만족하면 실제 테스트가 성공한 것이다.

- [ ] `https://test.jinhokingoftheworld.cloud`가 외부 브라우저에서 열린다.
- [ ] Nano가 Wi-Fi에 연결된다.
- [ ] Nano가 HTTPS 인증서를 신뢰한다.
- [ ] credential test가 `200 CREDENTIAL_VALID`로 성공한다.
- [ ] 장치를 ACTIVE로 전환할 수 있다.
- [ ] RFID 카드 태그가 `POST /api/v1/device/check-ins`를 만든다.
- [ ] 관리자 화면과 DB에서 출석 기록을 확인한다.

## 16. 테스트를 마친 뒤

테스트를 끝낸 뒤에는 다음을 한다.

1. 테스트 장치 키와 `.env`를 외부에 공유하지 않았는지 확인한다.
2. 실제 운영으로 옮길 때는 테스트 DB와 운영 DB를 절대 섞지 않는다.
3. 테스트용 `test.` 주소와 운영용 주소를 분리한다.
4. 필요 없어진 Tunnel은 중지하거나 삭제한다.
