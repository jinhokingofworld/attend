# ARM64 전용 Mac 서버 Docker·Cloudflare·Arduino 통합 배포 가이드

이 문서는 Attend 애플리케이션을 개발 Mac에서 빌드한 뒤 ARM64 전용 Mac 서버로
전송하고, Cloudflare Tunnel을 통해 `test.jinhokingoftheworld.cloud`로 공개한 다음
Arduino Nano 33 IoT와 RFID 카드로 실제 출석을 검증하는 전체 절차다.

이 문서 하나만 위에서 아래로 따라가는 것을 기준으로 한다. 테스트 DB와 테스트
도메인만 사용하며 실제 운영 데이터는 넣지 않는다.

## 0. 이 문서의 고정 전제

| 항목 | 값 |
| --- | --- |
| 개발 프로젝트 | `/Users/j/Desktop/attend` |
| SSH 별칭 | `attendance-server` |
| 서버 배포 폴더 | `~/attendance-server` |
| 서버 CPU | ARM64 (`arm64` 또는 `aarch64`) |
| Docker 플랫폼 | `linux/arm64` |
| 최초 테스트 이미지 태그 | `test` |
| 공개 테스트 주소 | `test.jinhokingoftheworld.cloud` |
| 외부 연결 | Cloudflare Tunnel |
| Arduino | Nano 33 IoT + WiFiNINA + MFRC522 |

SSH 별칭이 다르면 모든 `attendance-server`를 실제 별칭으로 바꾼다.

`compose.oci-micro.yaml`은 Oracle Linux AMD64 파일럿용이므로 이 ARM64 Mac 서버
배포에는 사용하지 않는다.

최종 요청 흐름은 다음과 같다.

```text
Nano 33 IoT
    → 인터넷 HTTPS
    → Cloudflare 공개 인증서
    → Cloudflare Tunnel
    → 전용 Mac 서버의 Caddy
    → Attend App 컨테이너
    → 테스트 PostgreSQL DB
```

Cloudflare에는 `en0`의 사설 IP나 서버의 LAN IP를 등록하지 않는다. Tunnel connector가
전용 서버에서 Cloudflare로 먼저 연결하므로 공유기 포트포워딩도 하지 않는다.

## 1. 비밀정보 취급 원칙

다음 값은 Git, Docker 이미지, 빌드 로그, 캡처, 메신저에 넣지 않는다.

- 서버 `.env`
- `firmware/attend-nfc/config.h`
- DB 비밀번호
- Cloudflare Tunnel token
- `ACCOUNT_TOKEN_PEPPER`
- `DEVICE_CREDENTIAL_PEPPER`
- `TRUSTED_PROXY_TOKEN`
- Arduino `DEVICE_KEY`

`.env`와 `config.h`는 서버 또는 개발 장비에서 직접 작성한다. 비밀값을 명령행
인자로 반복 입력하거나 shell history에 남기지 않는다.

## 2. 전체 순서

아래 순서를 바꾸지 않는다.

1. Cloudflare zone 활성화와 테스트 DB 준비
2. 개발 Mac에서 JAR 검증
3. ARM64 이미지 3개 빌드
4. 이미지 tar와 Compose·Caddy 파일 전송
5. 서버에서 이미지 load 및 `.env` 작성
6. Compose 설정과 이미지 태그 검증
7. DB migration을 V020까지 실행
8. Docker-managed Cloudflare Tunnel token을 준비
9. App·Caddy·cloudflared를 Docker 내부에서 검증
10. Tunnel Public Hostname과 외부 HTTPS 검증
11. 관리자·부서·카드·장치 준비
12. Nano 인증서와 credential 시험
13. 장치 활성화 후 실제 RFID 출석 시험

외부 HTTPS가 정상화되기 전에 Arduino 작업으로 넘어가지 않는다.

## 3. 사전 준비 확인

### 3.1 전용 서버 확인

개발 Mac에서 접속한다.

```bash
ssh attendance-server
```

서버에서 확인한다.

```bash
uname -m
docker --version
docker compose version
```

이 배포에서는 `uname -m`이 다음 중 하나여야 한다.

```text
arm64
aarch64
```

Docker Desktop이 실행 중이어야 한다. Docker Desktop 기반 Mac 서버는 재부팅 후에도
사용자가 로그인하고 Docker Desktop이 시작돼야 컨테이너가 복구된다. Docker Desktop의
로그인 시 자동 시작 설정을 켜고, 배포 완료 후 재부팅 시험을 반드시 한다.

### 3.2 Cloudflare 확인

- `jinhokingoftheworld.cloud` zone 상태가 `Active`여야 한다.
- `test.jinhokingoftheworld.cloud`를 Tunnel의 Published application route로 사용할
  권한이 있어야 한다.
- 별도의 A 레코드로 서버 LAN IP를 등록하지 않는다.

### 3.3 테스트 DB 확인

세 종류의 DB 연결정보를 준비한다.

| 용도 | 권한 |
| --- | --- |
| App runtime | 일반 애플리케이션 읽기·쓰기 최소권한 |
| Migration | 스키마 migration 전용 권한 |
| Retention | 고정 retention 함수 실행 전용 권한 |

이 문서는 보존할 운영 데이터가 없는 신규 테스트 DB를 전제로 하므로
`MIGRATION_SOURCE_CLASS=NEW_OR_SAMPLE`을 사용한다. 기존 운영 DB에 이 값을 임의로
사용하면 안 된다.

신규 DB에서 역할과 권한을 아직 준비하지 않았다면
`ops/db/roles/README.md`에 따라 다음 순서를 사용한다.

```text
DB role 관리자: 001_create_login_roles.sql
DB/schema 소유자: 002_prepare_database_for_migration.sql
각 역할 비밀번호를 비밀 저장소에서 별도 발급
이 문서 10절의 migration 실행
migration_owner/객체 소유자: 003_grant_application_privileges.sql
migration_owner/객체 소유자: 004_grant_department_admin_invitation_privileges.sql
```

이 SQL들은 Flyway migration이 아니며 DB provider의 승인된 SQL console 또는 `psql`로
실행한다. `001`과 `002`가 준비되지 않았다면 10절로 넘어가지 않는다.

## 4. 개발 Mac에서 ARM64 이미지 만들기

이 절은 개발 Mac에서 실행한다.

### 4.1 JAR 빌드와 테스트

```bash
cd /Users/j/Desktop/attend

./gradlew check bootJar migrationBootJar retentionBootJar --no-daemon
```

명령이 성공해야 다음 파일이 준비된다.

```text
build/libs/attend-app.jar
build/libs/attend-migration.jar
build/libs/attend-retention.jar
```

### 4.2 이미지 태그 설정

현재 첫 배포에서 이미 만든 이미지와 맞추기 위해 `test`를 사용한다.

```bash
export ATTEND_IMAGE_TAG=test
```

새 버전부터는 `latest`나 재사용하는 `test`보다 커밋 기반의 변경되지 않는 태그를
권장한다.

```bash
export ATTEND_IMAGE_TAG="$(git rev-parse --short=12 HEAD)-arm64"
```

한 배포 작업에서는 세 이미지가 반드시 같은 태그를 사용해야 한다.

### 4.3 이미지 3개 빌드

웹 애플리케이션:

```bash
docker buildx build --platform linux/arm64 \
  --file Dockerfile.prebuilt \
  --target runtime \
  --tag "attend:${ATTEND_IMAGE_TAG}" \
  --load .
```

DB migration:

```bash
docker buildx build --platform linux/arm64 \
  --file Dockerfile.prebuilt \
  --target migration \
  --tag "attend-migration:${ATTEND_IMAGE_TAG}" \
  --load .
```

Retention worker:

```bash
docker buildx build --platform linux/arm64 \
  --file Dockerfile.prebuilt \
  --target retention \
  --tag "attend-retention:${ATTEND_IMAGE_TAG}" \
  --load .
```

`runtime-base`는 중간 build stage이므로 별도로 전송하지 않는다. 최종 이미지는 위 세
개뿐이다.

### 4.4 이미지 아키텍처 검증

```bash
docker image inspect \
  "attend:${ATTEND_IMAGE_TAG}" \
  "attend-migration:${ATTEND_IMAGE_TAG}" \
  "attend-retention:${ATTEND_IMAGE_TAG}" \
  --format '{{index .RepoTags 0}} {{.Os}}/{{.Architecture}}'
```

세 줄 모두 `linux/arm64`여야 한다.

### 4.5 이미지 tar 만들기

```bash
docker save --output attend-images.tar \
  "attend:${ATTEND_IMAGE_TAG}" \
  "attend-migration:${ATTEND_IMAGE_TAG}" \
  "attend-retention:${ATTEND_IMAGE_TAG}"

shasum -a 256 attend-images.tar > attend-images.tar.sha256
ls -lh attend-images.tar attend-images.tar.sha256
```

`attend-images.tar`은 `docker save` 형식이다. 일반 압축파일처럼 `tar -xvf`로 풀지
않는다.

## 5. 서버로 배포 파일 전송

### 5.1 서버 폴더 만들기

개발 Mac에서 실행한다.

```bash
ssh attendance-server 'mkdir -p ~/attendance-server/ops/caddy ~/attendance-server/ops/db/roles ~/attendance-server/secrets'
```

### 5.2 파일 전송

```bash
cd /Users/j/Desktop/attend

scp attend-images.tar attend-images.tar.sha256 \
  compose.prod.yaml compose.migration.yaml \
  compose.cloudflare-tunnel.yaml \
  attendance-server:~/attendance-server/

scp ops/caddy/Caddyfile.tunnel \
  attendance-server:~/attendance-server/ops/caddy/Caddyfile.tunnel

scp ops/db/roles/003_grant_application_privileges.sql \
  ops/db/roles/004_grant_department_admin_invitation_privileges.sql \
  attendance-server:~/attendance-server/ops/db/roles/
```

`.env`와 Arduino `config.h`는 이 명령에 포함하지 않는다.

### 5.3 전송 결과 확인

```bash
ssh attendance-server
cd ~/attendance-server
ls -la
ls -la ops/caddy
```

최소 구조는 다음과 같아야 한다.

```text
attendance-server/
├── attend-images.tar
├── attend-images.tar.sha256
├── compose.prod.yaml
├── compose.migration.yaml
├── compose.cloudflare-tunnel.yaml
├── secrets/
└── ops/
    ├── caddy/
    │   └── Caddyfile.tunnel
    └── db/roles/
        ├── 003_grant_application_privileges.sql
        └── 004_grant_department_admin_invitation_privileges.sql
```

`secrets/cloudflared-tunnel-token`은 서버에서만 만든다. 전송하지 않는다.

무결성을 확인한다.

```bash
shasum -a 256 -c attend-images.tar.sha256
```

결과가 `OK`여야 한다.

## 6. 서버에서 이미지 load

이 절부터는 전용 서버에서 실행한다.

```bash
cd ~/attendance-server
docker load --input attend-images.tar
```

현재 `test` 태그 기준으로 다음 세 이미지가 보여야 한다.

```bash
docker images | grep '^attend'
```

```text
attend                  test
attend-migration        test
attend-retention        test
```

서버에서도 아키텍처를 확인한다.

```bash
docker image inspect attend:test attend-migration:test attend-retention:test \
  --format '{{index .RepoTags 0}} {{.Os}}/{{.Architecture}}'
```

세 줄 모두 `linux/arm64`여야 한다.

## 7. 서버 `.env` 작성

### 7.1 비밀값 생성

서로 다른 랜덤값 세 개를 만든다.

```bash
openssl rand -hex 32
openssl rand -hex 32
openssl rand -hex 32
```

각 값을 다음 항목에 하나씩 사용한다.

1. `ACCOUNT_TOKEN_PEPPER`
2. `DEVICE_CREDENTIAL_PEPPER`
3. `TRUSTED_PROXY_TOKEN`

서비스 사용 후 이 값을 바꾸면 기존 링크, 장치 키 또는 Caddy-App 신뢰가 깨질 수
있으므로 그대로 보관한다.

### 7.2 `.env` 작성

```bash
cd ~/attendance-server
umask 077
nano .env
```

아래 구조를 사용한다. `REPLACE_...`를 실제 테스트 값으로 교체한다.

```dotenv
ATTEND_IMAGE_TAG=test

# App runtime용 pooled DB 연결
DB_URL=jdbc:postgresql://REPLACE_POOLED_HOST/REPLACE_DATABASE?sslmode=require
DB_USERNAME=REPLACE_APP_RUNTIME_ROLE
DB_PASSWORD=REPLACE_APP_RUNTIME_PASSWORD

# Migration 전용 direct DB 연결
FLYWAY_DB_URL=jdbc:postgresql://REPLACE_DIRECT_HOST/REPLACE_DATABASE?sslmode=require
FLYWAY_DB_USERNAME=REPLACE_MIGRATION_ROLE
FLYWAY_DB_PASSWORD=REPLACE_MIGRATION_PASSWORD
MIGRATION_SOURCE_CLASS=NEW_OR_SAMPLE

# Retention 전용 direct DB 연결
RETENTION_DB_URL=jdbc:postgresql://REPLACE_DIRECT_HOST/REPLACE_DATABASE?sslmode=require
RETENTION_DB_USERNAME=REPLACE_RETENTION_ROLE
RETENTION_DB_PASSWORD=REPLACE_RETENTION_PASSWORD
RETENTION_RUN_INTERVAL_SECONDS=86400
RETENTION_CATCHUP_INTERVAL_SECONDS=1

# 공개 테스트 주소
PUBLIC_HOST=test.jinhokingoftheworld.cloud
PUBLIC_BASE_URL=https://test.jinhokingoftheworld.cloud
ACME_CONTACT_EMAIL=REPLACE_OPERATOR_EMAIL

# 반드시 서로 다른 32-byte 이상 랜덤값
ACCOUNT_TOKEN_PEPPER=REPLACE_RANDOM_VALUE_1
DEVICE_CREDENTIAL_PEPPER=REPLACE_RANDOM_VALUE_2
TRUSTED_PROXY_TOKEN=REPLACE_RANDOM_VALUE_3

# 실제 Arduino 테스트에 필요한 flag
DEVICE_API_ENABLED=true
ADMIN_WRITE_ENABLED=true
ATTENDANCE_SCHEDULER_ENABLED=false

# 이번 테스트에서는 Telegram 비활성화
TELEGRAM_NOTIFICATIONS_ENABLED=false
OPERATIONS_TELEGRAM_ENABLED=false
```

주의사항:

- `DEVICE_API_ENABLED`는 한 번만 적는다.
- `PUBLIC_HOST`에는 `https://`나 경로를 넣지 않는다.
- `SQL_INIT_MODE`은 production Compose에서 사용하지 않으므로 넣지 않는다.
- App은 production profile에서 Flyway를 자동 실행하지 않으므로 `FLYWAY_ENABLED`에
  의존하지 않는다. 별도 migration 컨테이너를 사용한다.
- DB 비밀번호에 `#`, 공백 같은 문자가 있으면 Compose `.env` 문법에 맞게 따옴표로
  감싼다.
- 테스트 DB가 fresh DB가 아니라면 `NEW_OR_SAMPLE`로 추측해서 진행하지 않는다.

권한을 확인한다.

```bash
chmod 600 .env
ls -l .env
```

정상 권한은 다음과 같다.

```text
-rw-------
```

## 8. Cloudflare Tunnel용 Caddy 설정

기본 저장소의 Caddyfile은 Caddy가 인터넷에서 직접 ACME 인증서를 받는 공개 서버
구성이다. 이번 테스트 배포에서는 Cloudflare가 외부 HTTPS를 종료한다. 함께 전송한
`compose.cloudflare-tunnel.yaml`은 Caddy의 기본 80·443 host port 공개를 모두 제거하고,
`cloudflared` 컨테이너와 Caddy가 Docker Compose 네트워크 안에서만 통신하도록 기본
Compose를 덮어쓴다.

```text
외부 사용자/Nano -- HTTPS --> Cloudflare
Cloudflare Tunnel -- Docker network HTTP --> caddy:80
Caddy -- Docker private network HTTP --> app:8080
```

Caddy는 host port를 하나도 publish하지 않는다. 따라서 공유기 80·443 포트포워딩과
`localhost:8088` 포트도 만들지 않는다. `cloudflared`만 Docker 네트워크의 `caddy:80`으로
연결한다.

배포 파일을 확인한다.

```bash
cd ~/attendance-server
ls -l compose.cloudflare-tunnel.yaml ops/caddy/Caddyfile.tunnel
```

Tunnel용 Caddyfile은 Cloudflare가 edge에서 덮어쓴 `CF-Connecting-IP` 한 개를 App의
`X-Forwarded-For`로 전달한다. Caddy가 host port로 publish되지 않으므로 인터넷 사용자가
이 header를 넣어 Caddy에 직접 접근할 수 없다.

이 문서의 production 실행 명령은 항상 다음 두 파일을 함께 지정한다.

```text
-f compose.prod.yaml -f compose.cloudflare-tunnel.yaml
```

`compose.prod.yaml`만 단독 실행하면 기본 80·443 공개 구성으로 돌아가므로 금지한다.

현재 기본 Compose가 `ACME_CONTACT_EMAIL`을 필수 환경변수로 선언하므로 Tunnel
구성에서도 `.env`에 값은 남겨 둔다. Tunnel용 Caddyfile 자체는 공개 ACME 발급을
요청하지 않는다.

일반 요청의 `X-Device-Key`가 로그에 노출될 수 있으므로 검증되지 않은 Caddy access
log 설정을 임의로 추가하지 않는다.

## 9. Docker-managed Cloudflare Tunnel token 준비

Cloudflare zone이 `Active`가 된 뒤 Dashboard에서 `remotely-managed Tunnel`을 하나 만든다.
이 Tunnel은 host macOS 서비스가 아니라 아래 Compose `cloudflared` 서비스가 실행한다.

> 아직 zone이 `Active`가 아니라면 이 절은 건너뛰고 10·11절의 Compose 확인과 DB migration을
> 먼저 수행한다. App·Caddy·cloudflared를 처음 `up`하기 직전에 이 절로 돌아온다.

1. Cloudflare Dashboard의 `Networking` 또는 Zero Trust의 `Tunnels`에서 Tunnel을 만든다.
2. 이름은 `attend-server-test`처럼 정한다.
3. Connector 환경은 **Docker**를 선택한다.
4. 화면에 표시되는 설치 명령 전체가 아니라 `eyJ...` 형태의 **Tunnel token 값만** 복사한다.
5. 서버에서 token을 Docker secret 파일로 저장한다.

```bash
cd ~/attendance-server
mkdir -p secrets
chmod 700 secrets
read -r -s 'CF_TUNNEL_TOKEN?Cloudflare Tunnel token: '
echo
printf '%s' "$CF_TUNNEL_TOKEN" > secrets/cloudflared-tunnel-token
unset CF_TUNNEL_TOKEN
chmod 600 secrets/cloudflared-tunnel-token
```

`secrets/cloudflared-tunnel-token`은 `.env`, Git, `scp` 명령, Compose command line에 넣지
않는다. Compose는 이 파일을 container의 `/run/secrets/cloudflared_tunnel_token`으로만
읽기 전용 mount한다. `cloudflared` 2026.7.3은 이 token-file 방식을 지원한다.

Docker image를 받는다.

```bash
docker pull cloudflare/cloudflared:2026.7.3
docker image inspect cloudflare/cloudflared:2026.7.3 \
  --format '{{.Os}}/{{.Architecture}}'
```

결과는 `linux/arm64`여야 한다.

## 10. Compose와 이미지 일치 검증

Compose 전체 렌더링은 비밀 환경값을 출력할 수 있으므로 화면에 전부 출력하지 않는다.

### 10.1 Production Compose 문법 확인

```bash
cd ~/attendance-server

docker compose --env-file .env \
  -f compose.prod.yaml \
  -f compose.cloudflare-tunnel.yaml \
  config --quiet
```

이미지만 확인한다.

```bash
docker compose --env-file .env \
  -f compose.prod.yaml \
  -f compose.cloudflare-tunnel.yaml \
  config --images
```

최소한 다음이 포함돼야 한다.

```text
attend:test
attend-retention:test
caddy:2-alpine
cloudflare/cloudflared:2026.7.3
```

### 10.2 Migration Compose 확인

```bash
docker compose --env-file .env \
  -f compose.migration.yaml \
  config --quiet

docker compose --env-file .env \
  -f compose.migration.yaml \
  config --images
```

결과는 다음 이미지여야 한다.

```text
attend-migration:test
```

`attend:local`, `attend:latest` 또는 존재하지 않는 커밋 태그가 보이면 `.env`의
`ATTEND_IMAGE_TAG`와 실제 `docker images` 결과가 다른 것이다.

### 10.3 Caddy 이미지 받기

`attend-images.tar`에는 Attend 이미지 세 개만 들어 있다. Caddy는 서버 CPU에 맞는
공식 이미지를 서버에서 직접 받는다.

```bash
docker pull caddy:2-alpine
docker image inspect caddy:2-alpine \
  --format '{{.Os}}/{{.Architecture}}'
```

결과는 `linux/arm64`여야 한다.

## 11. DB migration 실행

App보다 먼저 migration을 실행한다.

```bash
cd ~/attendance-server

docker compose --env-file .env \
  -f compose.migration.yaml \
  run --rm --pull never migration
```

현재 artifact의 성공 문구는 다음과 같다.

```text
Database migration validated at target V020.
```

실패하면 App을 시작하지 않는다. 우선 다음을 확인한다.

- `FLYWAY_DB_URL`이 direct DB 주소인지
- migration 전용 계정과 비밀번호가 맞는지
- URL에 `sslmode=require`가 있는지
- fresh 테스트 DB에 `MIGRATION_SOURCE_CLASS=NEW_OR_SAMPLE`이 맞는지
- migration 계정에 `public` schema `USAGE`, `CREATE` 권한이 있는지

migration 명령은 내부에서 사전검사, V020 적용, checksum과 최종 version 검증까지
수행한다.

신규 DB라면 migration 성공 직후, App 시작 전에 다음 두 권한 스크립트를 순서대로
적용한다.

```text
ops/db/roles/003_grant_application_privileges.sql
ops/db/roles/004_grant_department_admin_invitation_privileges.sql
```

`004`까지 빠짐없이 적용해야 V018의 관리자 초대 outbox를 포함한 runtime 권한 검사를
통과할 수 있다. V020은 V019 정책 일정 테이블의 runtime 권한을 migration 안에서
자동으로 부여하지만, 기존 객체 전체의 최소권한 재검증에는 위 두 스크립트가 계속
필요하다.

## 12. App·Caddy·cloudflared 실행

이미지는 서버에서 미리 load했으므로 재빌드하지 않는다.

```bash
cd ~/attendance-server

docker compose --env-file .env \
  -f compose.prod.yaml \
  -f compose.cloudflare-tunnel.yaml \
  up -d --no-build --pull never
```

상태를 확인한다.

```bash
docker compose --env-file .env \
  -f compose.prod.yaml \
  -f compose.cloudflare-tunnel.yaml \
  ps
```

정상 상태:

- `app`이 `healthy`
- `caddy`가 실행 중
- `cloudflared`가 실행 중
- `retention`이 재시작을 반복하지 않음

Caddy가 host port를 전혀 열지 않았는지 확인한다.

```bash
docker compose --env-file .env \
  -f compose.prod.yaml \
  -f compose.cloudflare-tunnel.yaml \
  port caddy 80
```

이 명령은 host port가 없으므로 실패하거나 빈 결과가 정상이다. `127.0.0.1:8088`,
`0.0.0.0:80`, `0.0.0.0:443` 또는 서버 LAN IP가 나오면 Tunnel override가 적용되지 않은
것이므로 진행하지 않는다.

오류 로그를 확인한다.

```bash
docker compose --env-file .env \
  -f compose.prod.yaml \
  -f compose.cloudflare-tunnel.yaml \
  logs --tail=100 app caddy cloudflared retention
```

### 12.1 App 내부 health 확인

App의 `8080` 포트는 호스트에 publish되지 않는다. 따라서 서버에서
`curl localhost:8080`을 사용하지 않는다.

```bash
docker compose --env-file .env \
  -f compose.prod.yaml \
  -f compose.cloudflare-tunnel.yaml \
  exec -T app curl --fail --silent \
  http://127.0.0.1:8081/actuator/health
```

결과에 다음이 보여야 한다.

```json
{"status":"UP"}
```

### 12.2 Caddy → App 내부 경로 확인

Tunnel용 Caddy는 Docker network에서만 접근된다. App 컨테이너에서 Caddy로 진단 요청을
보내고, 엄격한 proxy filter가 요구하는 단일 client IP를 흉내 내기 위해
`CF-Connecting-IP`를 추가한다.

```bash
docker compose --env-file .env \
  -f compose.prod.yaml \
  -f compose.cloudflare-tunnel.yaml \
  exec -T app curl --silent --output /dev/null \
  --write-out '%{http_code}\n' \
  --header 'Host: test.jinhokingoftheworld.cloud' \
  --header 'CF-Connecting-IP: 127.0.0.1' \
  http://caddy:80/
```

로그인 리다이렉트를 포함한 `2xx` 또는 `3xx`이면 Caddy → App 경로가 연결된 것이다.
이 Caddy HTTP 포트를 서버 외부 인터페이스에 공개하면 안 된다.

## 13. Cloudflare Published application route 설정

Tunnel의 Published application route를 다음과 같이 설정한다.

| 항목 | 값 |
| --- | --- |
| Hostname | `test.jinhokingoftheworld.cloud` |
| Service type | `HTTP` |
| Service URL | `http://caddy:80` |
| HTTP Host Header | `test.jinhokingoftheworld.cloud` |

origin TLS 검증을 끄는 `No TLS Verify`는 사용하지 않는다. origin은 같은 Compose network의
HTTP Caddy service이고, 외부 사용자와 Nano 구간은 Cloudflare 공개 HTTPS로 보호된다.

전체 hostname에 Cloudflare Access 로그인 화면을 걸면 Arduino가 인증할 수 없으므로
이번 테스트에서는 Access application을 연결하지 않는다.

Published route를 저장하면 Cloudflare가 Tunnel용 DNS record를 관리한다. 서버 LAN IP나
`en0` 주소를 별도 A 레코드로 만들지 않는다.

## 14. 외부 HTTPS 검증

서버 내부 경로와 Tunnel이 모두 정상인 뒤 개발 Mac 또는 스마트폰 데이터망에서
확인한다.

```bash
curl --silent --output /dev/null \
  --write-out '%{http_code} %{remote_ip} %{ssl_verify_result}\n' \
  https://test.jinhokingoftheworld.cloud/
```

판정 기준:

- HTTP 상태가 로그인 페이지 또는 리다이렉트에 해당하는 `2xx`/`3xx`
- `ssl_verify_result`가 `0`
- 브라우저에서도 인증서 경고 없이 열림

Arduino API가 Cloudflare Access나 browser challenge에 가로막히지 않는지 무자격
요청으로 확인한다.

```bash
curl --silent --show-error \
  --request POST \
  --header 'Accept: application/json' \
  --write-out '\nHTTP %{http_code}\n' \
  https://test.jinhokingoftheworld.cloud/api/v1/device/credential-tests
```

정상적인 무자격 결과는 Spring App의 JSON `401 DEVICE_UNAUTHORIZED`다. Cloudflare HTML,
로그인 redirect, CAPTCHA 또는 challenge 화면이 나오면 Arduino도 통과하지 못하므로
Cloudflare 정책부터 수정한다.

Cloudflare Tunnel과 외부 HTTPS가 정상화되기 전에는 Nano root certificate를 올리지
않는다. 그 전에 올리면 최종 Cloudflare edge 인증서 체인이 아닌 다른 인증서를 가져올
수 있다.

## 15. 최초 관리자와 테스트 데이터 준비

### 15.1 최초 `SYSTEM_ADMIN`

DB에 계정이 하나도 없다면 migration 후 한 번만 bootstrap한다. 서버에 load한 migration
이미지 안의 interactive CLI를 실제 SSH terminal에서 실행한다. `-T`를 붙이면 안 된다.

```bash
cd ~/attendance-server

docker compose --env-file .env \
  -f compose.migration.yaml \
  run --rm --pull never \
  --entrypoint java migration \
  -Dloader.main=com.example.attend.access.bootstrap.SystemAdminBootstrapCli \
  -cp /app/migration.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher
```

Compose가 서버 `.env`의 `FLYWAY_DB_URL`, `FLYWAY_DB_USERNAME`,
`FLYWAY_DB_PASSWORD`를 주입한다. 관리자 사용자명과 비밀번호는 CLI가 대화형으로
묻는다. 명령행 인자나 `.env`에 관리자 비밀번호를 넣지 않는다.

계정 행이 이미 하나라도 있으면 bootstrap을 다시 실행하지 않는다.

### 15.2 테스트 업무 데이터

관리자 웹에서 다음을 준비한다.

- 테스트 부서 생성
- 테스트 수행 계정에 해당 부서의 `DEPARTMENT_ADMIN` 권한 부여
- 권한 부여 뒤 로그아웃하고 다시 로그인
- 테스트 교사/구성원 등록
- 현재 시각을 포함하는 출석 정책 발행
- 오늘 출석 날짜 생성
- 오늘 출석 대상으로 포함
- 현재 시각이 출석 가능 정책 구간인지 확인

`SYSTEM_ADMIN` 권한만으로 부서 출석 업무 권한이 자동 부여되지 않는다. 부서 데이터를
준비하려면 해당 부서의 `DEPARTMENT_ADMIN` 권한도 필요하다.

### 15.3 테스트 장치 등록

시스템 관리 화면에서 장치를 생성한다.

| 항목 | 예시 |
| --- | --- |
| 부서 | 테스트 부서 |
| 장치명 | 교육관 입구 테스트 Nano |
| `DEVICE_CODE` | `NFC-TEST-01` |
| `DEVICE_KEY` | 서버가 생성하고 한 번만 표시하는 값 |

처음 생성된 장치는 `INACTIVE`여야 정상이다. `DEVICE_KEY`는 한 번만 표시되므로 즉시
안전하게 Arduino `config.h`에 옮긴다.

## 16. Arduino 펌웨어 준비

### 16.1 라이브러리

Arduino IDE에서 다음 라이브러리를 설치한다.

- MFRC522
- WiFiNINA
- ArduinoHttpClient
- ArduinoJson 7

하드웨어 조건:

- Nano는 2.4 GHz Wi-Fi를 사용한다.
- WPA2 Personal을 권장하며 captive portal과 WPA3-only 네트워크는 피한다.
- MFRC522에는 5V가 아니라 3.3V를 공급한다.
- 현재 펌웨어 pin은 MFRC522 RST `D9`, SS/SDA `D10`, 빨강 LED `D2`, 초록 LED
  `D3`이다.

반드시 다음 운영 sketch만 연다.

```text
/Users/j/Desktop/attend/firmware/attend-nfc/attend-nfc.ino
```

프로젝트 루트의 `RFID.ino`는 배포 금지 레거시 예제이므로 사용하지 않는다.

### 16.2 `config.h` 작성

개발 Mac에서 다음 파일을 사용한다.

```text
/Users/j/Desktop/attend/firmware/attend-nfc/config.h
```

내용:

```cpp
#pragma once

constexpr char WIFI_SSID[] = "REPLACE_WIFI_NAME";
constexpr char WIFI_PASSWORD[] = "REPLACE_WIFI_PASSWORD";
constexpr char SERVER_HOST[] = "test.jinhokingoftheworld.cloud";
constexpr int SERVER_PORT = 443;
constexpr char DEVICE_CODE[] = "NFC-TEST-01";
constexpr char DEVICE_KEY[] = "REPLACE_ONE_TIME_DEVICE_KEY";

// 첫 credential 연결 시험에서는 반드시 true
constexpr bool CREDENTIAL_PROVISIONING_MODE = true;
```

`SERVER_HOST`에는 `https://`, API 경로 또는 IP 주소를 넣지 않는다.

```text
정상: test.jinhokingoftheworld.cloud
오류: https://test.jinhokingoftheworld.cloud
오류: test.jinhokingoftheworld.cloud/api/v1/device/check-ins
오류: 192.168.x.x
```

`config.h`와 이를 포함해 컴파일한 firmware binary에는 Wi-Fi 비밀번호와
`DEVICE_KEY`가 들어 있으므로 Git이나 공개 artifact에 올리지 않는다.

## 17. Nano 33 IoT NINA firmware와 공개 인증서

### 17.1 NINA firmware 확인

Arduino IDE에서 Nano 33 IoT와 포트를 선택한다. Serial Monitor가 열려 있으면 닫는다.

Arduino IDE 2에서는 `Tools → Firmware Updater`에서 NINA firmware를 확인하고 필요하면
업데이트한다. 또는 `File → Examples → WiFiNINA → Tools → CheckFirmwareVersion` 예제를
사용한다.

### 17.2 Cloudflare 공개 root certificate 업로드

Tunnel과 외부 hostname이 정상인 상태에서 Arduino IDE 2의
`Tools → Upload Root Certificates`를 연다.

추가할 주소:

```text
https://test.jinhokingoftheworld.cloud
```

`Add New`에서 위 URL을 입력하고 `Enter`를 누른 뒤 대상 인증서를 선택해 업로드한다.
`Certificates uploaded` 완료 문구를 확인한다. 펌웨어 `SERVER_HOST`에는 URL이 아니라
계속 hostname만 사용한다.

Nano가 검증하는 것은 서버 loopback Caddy가 아니라 외부에서 보이는 Cloudflare 공개
인증서 체인이다. NINA firmware를 이후 다시 업데이트했다면 root certificate도 다시
확인한다.

## 18. 첫 번째 Nano 시험: credential provisioning

카드를 대기 전에 Wi-Fi, TLS, 장치 코드와 키만 검증한다.

1. `CREDENTIAL_PROVISIONING_MODE = true`를 확인한다.
2. `firmware/attend-nfc/attend-nfc.ino`를 Nano에 업로드한다.
3. Serial Monitor를 `115200` baud로 연다.
4. Nano의 Reset 버튼을 눌러 부팅 처음부터 관찰한다.
5. `Wi-Fi connected`를 확인한다.
6. Serial의 credential 성공 문구와 초록 LED 한 번을 확인한다.
7. 관리자 장치 화면에서 credential 시험 성공 시각/증거를 확인한다.

성공한 서버 응답은 `200 CREDENTIAL_VALID`이다. Serial에는 HTTP status 전체가 아니라
성공/실패 문구만 나온다. 이 모드에서는 RFID 카드를 읽지 않는 것이 정상이다.

credential 시험은 부팅 시 한 번만 수행하며 실패 후 자동 재시도하지 않는다. 설정을
고친 뒤 Reset 또는 전원 재연결이 필요하다. 짧은 시간에 반복 Reset하면 credential
시험 rate limit에 걸릴 수 있으므로 실패 후 최소 20초 이상 두고 다시 시도한다.

현재 Caddy/App 설정은 정상 요청을 access log에 반드시 남기지 않는다. 로그에 POST
경로가 보이지 않는다는 이유만으로 실패로 판정하지 않는다. 관리자 화면의 credential
시험 증거, LED, 오류 로그를 함께 확인한다. 장치 키가 포함된 raw header logging을
추가하면 안 된다.

실패 판정:

| 증상 | 우선 확인 |
| --- | --- |
| `Wi-Fi connected`가 없음 | SSID, 비밀번호, 2.4 GHz 호환, Wi-Fi 수신 상태 |
| provisioning 중 빨강 2회 | DNS/TLS/App 또는 `DEVICE_CODE`, `DEVICE_KEY`, 장치 상태 |
| `CREDENTIAL_TEST_NOT_ALLOWED` | 장치가 이미 ACTIVE/REVOKED인지 확인 |

## 19. 장치 활성화와 실제 RFID 출석

credential 시험이 성공한 뒤에만 다음 순서를 진행한다.

```text
INACTIVE
  → credential provisioning 성공
  → 관리자 화면에서 ACTIVE
  → 펌웨어 provisioning mode false
  → 실제 카드 태그
```

### 19.1 장치 활성화

관리자 화면에서 장치를 `ACTIVE`로 전환한다.

### 19.2 실제 출석 모드 업로드

`config.h`를 변경한다.

```cpp
constexpr bool CREDENTIAL_PROVISIONING_MODE = false;
```

Nano에 다시 업로드한다.

### 19.3 카드 태그 전 확인

- 장치가 `ACTIVE`
- 테스트 교사가 장치의 고정 부서에 활성 소속
- 오늘 출석 날짜가 열림
- 테스트 교사가 오늘 출석 대상
- 현재 시각이 출석 허용 구간

### 19.4 처음 쓰는 RFID 카드 등록

새 카드의 UID는 Serial에 출력되지 않는다. 다음 흐름으로 등록한다.

1. ACTIVE 장치의 일반 모드에서 미등록 카드를 처음 태그한다.
2. 빨강 LED 한 번과 `UNKNOWN_UID`는 이 단계에서 정상이다.
3. 부서 관리자 화면의 카드 등록함에서 최근 미등록 카드 이벤트를 찾는다.
4. 활성 카드가 없는 테스트 교사에게 해당 카드를 연결한다.
5. 카드가 `ACTIVE`이고 장치와 같은 부서에 연결됐는지 확인한다.
6. 카드를 리더에서 완전히 떼었다가 다시 태그한다.

이미 등록된 ACTIVE 테스트 카드가 있다면 이 등록 단계는 건너뛴다.

### 19.5 카드 태그와 결과

| LED | 의미 |
| --- | --- |
| 초록 1회 | 신규 정상 출석 또는 지각 저장 |
| 초록 2회 | 이미 출석 처리됨 |
| 빨강 1회 | 카드·부서·날짜·대상 등 업무 규칙 거부 |
| 빨강 2회 | 장치 인증·상태·설정 오류 |
| 빨강 3회 | 네트워크/TLS/서버 오류 또는 재시도 소진 |

최종 성공 조건:

- 관리자 화면에 실제 출석 기록이 생성됨
- DB의 출석 기록과 관리자 화면 결과가 일치함
- 동일 카드를 다시 태그하면 초록 LED 두 번과 `ALREADY_CHECKED_IN`

## 20. 상태 확인 명령

### App·Caddy·cloudflared·retention

```bash
cd ~/attendance-server

docker compose --env-file .env \
  -f compose.prod.yaml \
  -f compose.cloudflare-tunnel.yaml ps

docker compose --env-file .env \
  -f compose.prod.yaml \
  -f compose.cloudflare-tunnel.yaml \
  logs --tail=100 app caddy cloudflared retention
```

### App health

```bash
docker compose --env-file .env \
  -f compose.prod.yaml \
  -f compose.cloudflare-tunnel.yaml \
  exec -T app curl --fail --silent \
  http://127.0.0.1:8081/actuator/health
```

### Tunnel

```bash
docker compose --env-file .env \
  -f compose.prod.yaml \
  -f compose.cloudflare-tunnel.yaml \
  logs --tail=100 cloudflared
```

Dashboard에서도 connector가 `Healthy`인지 확인한다.

### 외부 HTTPS

```bash
curl -I https://test.jinhokingoftheworld.cloud
```

## 21. 장애 확인 순서

안쪽부터 바깥쪽으로 확인한다.

```text
1. DB migration V020
2. App 컨테이너와 내부 health
3. Caddy → App
4. cloudflared connector
5. Cloudflare Published route와 외부 HTTPS
6. Nano Wi-Fi와 root certificate
7. DEVICE_CODE / DEVICE_KEY / 장치 상태
8. 카드·부서·출석 날짜·대상
```

### 이미지 이름 오류

```bash
docker compose --env-file .env \
  -f compose.prod.yaml \
  -f compose.cloudflare-tunnel.yaml config --images
docker images | grep '^attend'
```

두 결과의 태그가 같아야 한다.

### App 재시작 반복

```bash
docker compose --env-file .env \
  -f compose.prod.yaml \
  -f compose.cloudflare-tunnel.yaml logs --tail=200 app
```

DB URL, runtime 권한, V018 적용 여부, pepper와 proxy token 누락을 확인한다.

### Caddy 오류

```bash
docker compose --env-file .env \
  -f compose.prod.yaml \
  -f compose.cloudflare-tunnel.yaml logs --tail=200 caddy
```

`PUBLIC_HOST`, Caddyfile 문법, `TRUSTED_PROXY_TOKEN`, app health를 확인한다.

### 외부 502/Bad Gateway

1. App health가 UP인지 확인한다.
2. Caddy 내부 진단 curl을 다시 실행한다.
3. Published route가 `http://caddy:80`인지 확인한다.
4. HTTP Host Header를 테스트 hostname으로 맞춘다.
5. `docker compose ... port caddy 80`가 host port를 출력하지 않는지 확인한다.

### Nano TLS 실패

1. 브라우저에서 외부 hostname 인증서 경고가 없는지 확인한다.
2. Cloudflare Tunnel이 최종 상태인지 확인한다.
3. Nano NINA firmware를 확인한다.
4. 최종 hostname의 root certificate를 다시 업로드한다.
5. `SERVER_HOST`가 hostname만 포함하는지 확인한다.

## 22. 설정 변경과 재시작

`.env`만 바꾼 경우 이미지를 다시 빌드하지 않는다.

```bash
docker compose --env-file .env \
  -f compose.prod.yaml \
  -f compose.cloudflare-tunnel.yaml \
  up -d --no-build --pull never --force-recreate
```

`DEVICE_CREDENTIAL_PEPPER`를 바꾸면 기존 장치 키 검증이 깨질 수 있으므로 일반 설정
변경처럼 취급하지 않는다.

## 23. 새 버전 배포

코드가 변경되면 다음 순서를 반복한다.

1. 개발 Mac에서 `check`와 세 JAR 빌드
2. 새 커밋 기반 ARM64 태그로 이미지 3개 빌드
3. 이미지 아키텍처 검증
4. 새 tar와 checksum 전송
5. 서버에서 checksum 확인 후 `docker load`
6. 서버 `.env`의 `ATTEND_IMAGE_TAG`를 새 태그로 변경
7. migration 컨테이너 실행 및 V020/새 목표 version 검증
8. `config --images`로 세 태그 확인
9. App·Caddy·cloudflared·retention 재생성
10. 내부 health → 외부 HTTPS → Arduino 순서로 재검증

서비스를 먼저 `down`할 필요는 없다. 새 태그와 migration이 준비된 뒤 다음 명령으로
교체한다.

```bash
docker compose --env-file .env \
  -f compose.prod.yaml \
  -f compose.cloudflare-tunnel.yaml \
  up -d --no-build --pull never
```

DB migration은 단순 이미지 태그 롤백으로 되돌릴 수 없다. 이전 App 이미지로 되돌릴
때도 현재 DB schema와 호환되는지 먼저 확인한다.

## 24. 종료와 재부팅 시험

테스트 서비스를 중지하되 컨테이너와 네트워크를 유지하려면:

```bash
docker compose --env-file .env \
  -f compose.prod.yaml \
  -f compose.cloudflare-tunnel.yaml stop
```

다시 시작:

```bash
docker compose --env-file .env \
  -f compose.prod.yaml \
  -f compose.cloudflare-tunnel.yaml start
```

Compose 리소스를 내리려면:

```bash
docker compose --env-file .env \
  -f compose.prod.yaml \
  -f compose.cloudflare-tunnel.yaml down
```

최초 배포 완료 전에는 전용 서버를 한 번 재부팅하고 다음을 확인한다.

- Docker Desktop이 자동 시작되는지
- `restart: unless-stopped` 컨테이너가 복구되는지
- cloudflared 컨테이너가 Connected로 복구되는지
- 외부 HTTPS가 다시 열리는지
- App health가 UP인지

## 25. 최종 완료 체크리스트

- [ ] 전용 서버가 ARM64이고 Docker·Compose가 정상
- [ ] Attend 이미지 세 개가 `linux/arm64`
- [ ] Compose가 실제 load한 동일 태그를 참조
- [ ] 서버 `.env` 권한이 `600`
- [ ] 테스트 DB migration이 V020까지 검증됨
- [ ] App health가 `UP`
- [ ] Caddy 내부 프록시가 `2xx`/`3xx`
- [ ] cloudflared connector가 Healthy/Connected
- [ ] 외부 테스트 hostname이 인증서 경고 없이 열림
- [ ] 테스트 관리자·부서·카드·출석 날짜가 준비됨
- [ ] INACTIVE 장치의 credential 시험이 `CREDENTIAL_VALID`
- [ ] 시험 후 장치를 ACTIVE로 전환
- [ ] Nano 실제 카드 태그로 출석 생성
- [ ] 동일 카드 재태그가 이미 출석으로 처리됨
- [ ] 서버 재부팅 뒤 서비스가 자동 복구됨

## 26. 참고 문서

- [Docker image load](https://docs.docker.com/reference/cli/docker/image/load/)
- [Docker Compose up](https://docs.docker.com/reference/cli/docker/compose/up/)
- [Cloudflare Tunnel 설정](https://developers.cloudflare.com/tunnel/setup/)
- [Cloudflare Tunnel origin parameters](https://developers.cloudflare.com/tunnel/advanced/origin-parameters/)
- [Cloudflare Tunnel Docker 설정](https://developers.cloudflare.com/tunnel/setup/)
- [Cloudflare Tunnel token-file run parameter](https://developers.cloudflare.com/tunnel/advanced/run-parameters/)
- [Caddy reverse proxy](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy)
- [Arduino root certificate 업로드](https://support.arduino.cc/hc/en-us/articles/360016119219-Upload-SSL-root-certificates)
- [Arduino WiFiNINA firmware updater](https://support.arduino.cc/hc/en-us/articles/360013896579-Use-the-Firmware-Updater-in-Arduino-IDE)
