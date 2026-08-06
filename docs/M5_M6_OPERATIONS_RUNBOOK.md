# M5 운영 준비와 M6 파일럿 실행서

> 문서 상태: 배포·실기기 시험 전 실행 기준
>
> 현재 판정: 신규 빈 Neon DB 사용 확정, M5 배포 설정·M6 실물 증거 미수집

이 문서는 실제 배포를 승인하지 않는다. `tag_event_log` 90일과 `audit_log` 2년 retention worker는 구현됐지만,
출석·교사 이력 5년 retention, 백업 저장소·도메인과 담당자가 승인된 뒤에만 운영 전환 명령을 실행한다. `.env`, 장치 key,
교사 UID·연락처를 증적이나 이슈에 첨부하지 않는다.

## 1. 배포 전 필수 결정

| 결정 | 현재 상태 | 미결정 시 조치 |
|---|---|---|
| 공개 DNS hostname과 ACME 담당 이메일 | 미정 | Caddy 기동 금지 |
| Caddy 전용 32-byte 이상 독립 proxy token | 미정 | 공개 app 기동 금지 |
| Neon pooled runtime / direct migration·retention 자격증명 | 미정 | DB 작업 금지 |
| 운영 DB 분류와 이관 승인 | 확정: 신규 빈 Neon DB, `NEW_OR_SAMPLE` | preflight가 `FRESH`가 아니면 migration 금지 |
| 업무 DB 보유기간 | 확정: tag 90일, 교사 기본정보 변경을 포함한 audit 2년, 출석·종료 소속·카드 연결 이력 5년 | tag 90일·audit 2년 worker는 구현; 5년 retention 없이는 전체 기한 삭제 준수 주장 금지 |
| 백업 보유기간·off-host 저장소·접근 담당자·삭제 절차 | 미정 | 실제 데이터 백업 금지 |
| 파일럿 부서 2개, 각 5~20명, 일정 4회 | 미정 | M6 완료 판정 금지 |

### 1.1 확정된 운영 DB 전환 방식

- 운영 DB는 기존 개발 DB와 분리된 **신규 빈 Neon PostgreSQL DB**로 만든다.
- 기존 DB의 데이터는 더미데이터이므로 교사, 카드, 계정, 출석과 로그를 이관하지
  않는다. 기존 DB를 `LEGACY_OPERATIONAL`로 승인하거나 version 0 baseline을 만들지
  않는다.
- migration 실행 시 승인값은 `MIGRATION_SOURCE_CLASS=NEW_OR_SAMPLE`로 주입한다.
  이 값은 저장소의 `.env`나 image에 고정하지 않고 배포 secret source에서 제공한다.
- 읽기 전용 preflight 결과가 `FRESH`일 때만 V001~V011을 적용한다. Neon이 만든
  기본 `public` 스키마에 사용자 객체가 하나라도 있어 `FRESH`가 아니면 자동 정리하지
  않고 새 DB 또는 새 branch를 준비한다.
- 신규 공식 출석 통계는 운영 컷오버 이후 생성한 출석 날짜부터 시작한다.

## 2. M5 사전 검증

1. 고정 commit에서 `./gradlew test`를 통과시킨다.
2. `docker compose -f compose.prod.yaml config --no-env-resolution --quiet`로 누락
   변수와 구문을 확인한다. `config` 전체 출력은 환경값을 렌더링할 수 있으므로 운영
   terminal·CI log에 출력하지 않는다. 이 runtime 파일은 migration 관리자 변수를
   요구하지 않는다. Migration은 별도 `compose.migration.yaml`과 별도 secret
   source로만 실행한다.
3. 빌드 후 image digest를 배포 기록에 고정한다. `latest`나 재빌드한 동일 tag를
   운영 근거로 사용하지 않는다.
4. migration 전용 direct 연결정보만 주입한 환경에서 `./gradlew dbPreflight`를
   실행한다. 이 명령은 read-only transaction에서 상태와 일반 사유만 출력하며 URL과
   계정명은 출력하지 않는다. JDBC URL에는 host·database·TLS option만 넣고 사용자명과
   비밀번호는 각각 별도 환경변수로 주입한다. 현재 승인 방식에서는 결과가 반드시
   `FRESH`여야 한다.
5. 운영 DB는 `ops/db/roles` 순서로 준비한 뒤 고정 image tag에서
   `docker compose -f compose.migration.yaml run --rm migration`을 한 번 실행해 V011까지
   적용한다. 이 컨테이너에만 Neon direct URL과 migration 계정을 주입한다. role script의
   `retention_worker` credential은 web runtime과 다른 direct URL·비밀값으로 준비한다.
6. runtime 계정에 DDL, `TEMP`, 레거시 DML 권한이 없는지 기존 DB 권한 검사를
   다시 수행한다.
7. `ADMIN_WRITE_ENABLED=false`, `ADMIN_SHOW_TAG_LOGS=false`, `DEVICE_API_ENABLED=false`,
   `ATTENDANCE_SCHEDULER_ENABLED=false`로 최초 기동한다.
8. App container 내부 health는 다음 명령으로 확인한다.

   ```bash
   docker compose --env-file /etc/attend/attend.env \
     -f compose.prod.yaml \
     exec -T app curl --fail --silent \
     http://127.0.0.1:8081/actuator/health
   ```

   결과는 `UP`이어야 하고, 공개 hostname의 `/actuator/health`는 도달할 수 없어야 한다.
   Management port는 host에 publish하지 않는다.
9. Caddy는 외부 `X-Forwarded-For`와 내부 token header를 upstream에서 덮어쓴다.
   앱은 token이 일치하는 단일 IP만 rate-limit source로 사용하며, app port를 host에
   publish하지 않는다.
10. 관리자 운영 화면에 버전·시작 시각·세 flag·V011 상태가 표시되고 URL·비밀값이
   없는지 확인한다.

### 2.1 Oracle Linux 9 E2.1.Micro 파일럿

`compose.oci-micro.yaml`은 외부 Neon을 사용하는 1GB `VM.Standard.E2.1.Micro` 한 대에서
소프트웨어 파일럿을 수행하기 위한 override다. App·retention worker·Caddy의 hard memory 합계를
768MiB로 제한하고 container swap을 허용하지 않으며, JVM heap·native memory, PID 수와
Docker `json-file` 로그 보유량을 제한한다. 이 구성은 고가용성, 백업 또는 현장 검증을
제공하지 않으므로 운영 완료 근거로 사용하지 않는다.

서버에는 고정 commit으로 만든 `attend:<tag>`의 `linux/amd64` 이미지와 Caddy 이미지를
미리 적재한다. 운영 secret 파일은 저장소 밖에 두고 mode `0600`으로 제한한다. 다음
명령은 고정 commit의 개발·CI 환경에서 JAR를 검증하고, Gradle을 다시 실행하지 않는
`Dockerfile.prebuilt`로 amd64 runtime·migration·retention image를 만든다.

```bash
./gradlew check bootJar migrationBootJar retentionBootJar --no-daemon
export ATTEND_IMAGE_TAG="$(git rev-parse --short=12 HEAD)-amd64"
docker buildx build --platform linux/amd64 \
  --file Dockerfile.prebuilt --target runtime \
  --tag "attend:${ATTEND_IMAGE_TAG}" --load .
docker buildx build --platform linux/amd64 \
  --file Dockerfile.prebuilt --target migration \
  --tag "attend-migration:${ATTEND_IMAGE_TAG}" --load .
docker buildx build --platform linux/amd64 \
  --file Dockerfile.prebuilt --target retention \
  --tag "attend-retention:${ATTEND_IMAGE_TAG}" --load .
docker save --output "attend-images-${ATTEND_IMAGE_TAG}.tar" \
  "attend:${ATTEND_IMAGE_TAG}" \
  "attend-migration:${ATTEND_IMAGE_TAG}" \
  "attend-retention:${ATTEND_IMAGE_TAG}"
```

Tar와 같은 commit의 Compose 파일을 서버로 전송한 뒤 다음 순서를 그대로 사용한다.
서버에서는 image를 빌드하지 않으며 `local`이나 `latest` tag를 허용하지 않는다.

```bash
export ATTEND_IMAGE_TAG="$(git rev-parse --short=12 HEAD)-amd64"
docker load --input "attend-images-${ATTEND_IMAGE_TAG}.tar"
docker pull --platform linux/amd64 caddy:2-alpine
docker image inspect "attend:${ATTEND_IMAGE_TAG}" \
  --format '{{.Os}}/{{.Architecture}} {{.Id}}'
docker image inspect "attend-retention:${ATTEND_IMAGE_TAG}" \
  --format '{{.Os}}/{{.Architecture}} {{.Id}}'
docker image inspect caddy:2-alpine \
  --format '{{.Os}}/{{.Architecture}} {{index .RepoDigests 0}}'
docker compose --env-file /etc/attend/attend.env \
  -f compose.prod.yaml \
  -f compose.oci-micro.yaml \
  config --no-env-resolution --quiet
docker compose --env-file /etc/attend/attend.env \
  -f compose.prod.yaml \
  -f compose.oci-micro.yaml \
  up -d --no-build --pull never
docker compose --env-file /etc/attend/attend.env \
  -f compose.prod.yaml \
  -f compose.oci-micro.yaml \
  ps
docker compose --env-file /etc/attend/attend.env \
  -f compose.prod.yaml \
  -f compose.oci-micro.yaml \
  exec -T app curl --fail --silent \
  http://127.0.0.1:8081/actuator/health
```

세 image inspect 결과는 모두 `linux/amd64`여야 한다. App이 OOM으로 종료되면
`-XX:+ExitOnOutOfMemoryError` 또는 container OOM kill 뒤 restart policy가 재기동한다.
반복 재기동은 memory limit을 올려 숨기지 말고 `docker compose ps`, container restart
count와 회전된 로그에서 원인을 확인한 뒤 파일럿을 중단한다.

## 3. 백업과 복원

### 3.1 audit retention

`compose.prod.yaml`의 `retention` container는 web app과 다른 `retention_worker`
credential만 받고 시작 직후 한 번, 이후 24시간마다 고정 2년 cutoff batch를 실행한다.
web `.env`의 `DB_USERNAME`/`DB_PASSWORD`를 이 container에 재사용하지 않는다. 성공 출력은
삭제 건수·batch 수만 포함하고 행 ID·이름·연락처·생년월일은 포함하지 않는다. 실패하면
container가 non-zero로 종료되어 Compose restart 정책이 재시도하므로, 반복 restart는
`docker compose logs retention`에서 확인하고 원인을 해결하기 전 무시하지 않는다.

별도 장기 archive를 만들지 않는다. 원칙은 **“백업하고 삭제”가 아니라 “삭제하되,
제한된 수명의 운영 백업에는 일시적으로 남을 수 있음”**이다. backup은 아직 미구성이라
그 일시 보존도 현재 환경에는 존재하지 않는다.

격리 복원본을 실제 서비스에 재투입하기 전에는 먼저 worker one-shot을 성공시킨다.

```bash
docker compose --env-file /etc/attend/attend.env \
  -f compose.prod.yaml \
  run --rm --entrypoint java retention -jar /app/retention.jar
```

출력이 `retention=SUCCESS`인지 확인하고, audit·tag event 삭제 행 수가 모두 0이 나올 때까지 같은
one-shot을 다시 실행한 뒤에만 app을 연결한다. worker 함수는 만료 audit·tag event 외에는 삭제할 수 없으며, 출석·소속·카드
이력의 retention은 이 작업에 포함하지 않는다.

### 3.2 backup과 복원

백업 단계가 별도로 승인되면 `ops/backup/backup.sh`를 하루 1회와 날짜 마감 직후 실행한다. 현재 production Compose에는 backup job이나 상태 파일 mount가 구성되어 있지 않다. 승인 후 결과의 dump,
SHA-256과 UTC 완료 시각을 기록한다. 같은 host의 로컬 디스크만 저장소로 인정하지
않는다.

파일럿 전과 운영 중 정기적으로 새 빈 격리 DB를 만들고
`ops/backup/restore-verify.sh`를 실행한다. 스크립트는 기존 DB를 지우지 않으며 빈
DB가 아니면 중단한다. 복원 후에는 계정·교사·출석의 **건수만** 원본과 대사하고
로그인 읽기 smoke를 수행한다. RTO 목표는 4시간, RPO 목표는 24시간이다.

## 4. 컷오버 순서

다음 단계를 건너뛰거나 여러 flag를 동시에 열지 않는다.

1. `admin-write=true`로 controlled restart 후 부서·관리자·교사·카드·정책·오늘
   날짜와 대상을 설정한다.
2. 한 장치를 `INACTIVE`로 만들고 key를 1회 주입한다. provisioning 펌웨어로
   credential test 증거를 확인한 뒤 그 장치만 `ACTIVE`로 바꾼다.
3. `device-api=true`로 restart하고 시험 카드 한 장의 첫 check-in을 화면·DB와
   대사한다. 같은 requestId 재전송이 최초 canonical 응답인지 확인한다.
4. 나머지 장치를 같은 절차로 활성화한다.
5. 하루 마감과 수동 대사까지 확인한 뒤 마지막으로 `scheduler=true`를 켠다.

오류가 발생하면 scheduler → device API → admin write 역순으로 flag를 닫는다.
DB migration을 되돌리거나 dump를 운영 DB 위에 덮어쓰지 않는다. 데이터 복구가
필요하면 새 격리 DB 복원·대사·별도 승인 절차를 사용한다.

## 5. M6 실기기 시험

하드웨어가 없는 개발 단계에서는
[LOCAL_HTTP_DEMO.md](./LOCAL_HTTP_DEMO.md)의 loopback 전용 Compose와 Postman
컬렉션으로 두 부서의 credential, 최초 기록, 멱등 replay와 requestId 충돌을 먼저
검증한다. 이 검증은 NFC 판독·LED·TLS·실제 성능 증거가 아니므로 M6 완료 근거로
계상하지 않는다.

펌웨어 대상과 설치 절차는 `firmware/attend-nfc/README.md`를 따른다. Arduino 확보
전에는 `scripts/pilot-http-simulator.sh`로 준비된 서로 다른 두 부서의 최초 기록,
멱등 replay, requestId 충돌을 확인할 수 있다. 이 결과는 서버 계약 증거일 뿐
FW-001~012를 대체하지 않는다.

실기기에서는 다음을 모두 기록한다.

- 4/7/10-byte UID의 대문자 canonical 전송
- 전체 HTTP body가 packet으로 나뉘어도 code 판정 성공
- 성공·이미 출석·업무 거부·인증 오류·네트워크 오류 LED 패턴
- timeout과 429/500/503에서 같은 UID/requestId로 최대 3회 재시도
- 인증서 오류에서 평문 연결 없이 실패
- credential 교체 직후 이전 key 실패, 새 key 시험·활성화 후 성공
- 1초 간격 실제 태깅 50회의 p95 2초 이내, 전체 5초 이내

## 6. 4회 파일럿 완료 기준

각 회차마다 출석 대상 snapshot, 장치 event, 최종 출석 기록, 자동 결석, 수동 정정과
감사 이력을 인원 수 기준으로 대사한다. 장애와 수기 보완도 상관 ID와 함께 기록하되
UID·연락처는 마스킹한다.

M6 완료는 두 부서 이상, 부서별 5~20명, 최소 4회 운영에서 잘못 연결된 교사와
복구 불가능한 데이터 유실이 모두 0건일 때만 선언한다. 현재 저장소만으로는 이
증거를 만들 수 없으므로 상태는 `실기기 시험 대기`다.
