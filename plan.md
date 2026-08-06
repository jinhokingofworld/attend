# Attend MVP 구현 계획

## 1. 구현 원칙

- 기존 코드는 프로토타입으로 간주한다. Java 21, Spring Boot, Thymeleaf, MyBatis, PostgreSQL과 기존 `member.id`만 보존하고 업무 코드는 기능 단위로 교체한다.
- 새 구조는 `common`, `access`, `organization`, `attendance`, `device`, `audit` 기능 패키지로 나눈다.
- 기존 `/member`, `/attendance`, `/api/attendance`, `/authentication`은 호환 API로 유지하지 않는다.
- 레거시 `attendance`, `attendance_log`, `authentications`에는 신규 데이터를 이중 쓰지 않는다.
- 구현 순서는 DB 안전화 → 조직·출석 도메인 → 인증·관리자 웹 → 장치 API → 운영 배포다.

확정된 추가 결정:

- 일반 계정은 시스템 관리자가 발급하는 30분·1회용 회원가입 초대 토큰과 비밀번호 재설정 토큰으로 관리한다. 공개 회원가입은 제공하지 않는다.
- 운영 형태는 단일 HTTPS 애플리케이션 + Neon PostgreSQL이다.
- 현재 Arduino가 없으므로 장치 API는 실제 HTTP 요청과 자동 계약 테스트로 검증한다.
- 실제 펌웨어 단계에서는 빨강·초록 LED만 사용한다.
- 운영 데이터베이스는 기존 더미데이터를 이관하지 않고 신규 빈 Neon PostgreSQL
  DB에서 시작한다. migration 승인값은 `NEW_OR_SAMPLE`이며, 읽기 전용 preflight가
  `FRESH`가 아니면 V001~V011을 적용하지 않는다.

## 2. 단계별 구현

### M0. 기준 확정과 개발환경

- 현재 문서·설정 변경을 별도 기준 커밋으로 보존하고 기존 사용자 변경을 덮어쓰지 않는다.
- JDK 21을 설치하고 Docker 기반 PostgreSQL 15 Testcontainers를 사용한다.
- 선택한 회원가입 초대·비밀번호 재설정 토큰 모델과 HTTP 장치 시험 방식을 DB·보안·UI·테스트 문서에 먼저 반영한다.
- `Clock`, `Asia/Seoul`, correlation ID와 시작 시 고정되는 `admin-write`, `device-api`, `scheduler` feature flag 계약을 만든다.
- `schema.sql`, `data.sql`, 물리 교사 삭제 경로는 신규 기능 작업보다 먼저 운영 artifact에서 차단한다.

### M1. DB와 실행 기반 안전화

- Flyway V001~V011을 문서 순서대로 구현한다.
  - V001은 빈 DB와 정확한 레거시 DB만 허용하고 `member` 행·PK를 보존한다.
  - V002에는 부서·계정·부서 권한과 회원가입 초대·비밀번호 재설정 토큰 모델을 함께 넣는다.
  - V003~V008은 카드·장치, 정책, 출석, event·audit, 복합 FK·인덱스, updated-at trigger를 생성하고 V009는 신규 등록·기본정보 수정·활성화의 정확한 생년월일, 활성 소속–교사 상태 일관성과 종료 소속·카드 연결 이력의 불변성을 강제한다. V010은 audit 시각 강제와 분리 retention worker의 고정 2년 batch 경계를 추가하고, V011은 tag event 수신 시각 강제와 고정 90일 batch 경계를 추가한다.
- 아직 적용된 Flyway 이력이 없으면 회원가입 초대·비밀번호 재설정 토큰 모델을 V002에 포함한다. 외부 DB에 V002가 이미 성공 적용된 사실이 확인되면 기존 파일을 수정하지 않고 다음 versioned migration으로 추가한다.
- 운영 migration은 동일 커밋의 migration을 포함한 고정 Flyway 컨테이너가 Neon direct URL로 실행한다. 웹 애플리케이션에서는 Flyway를 끄고 요구 schema version만 검사한다.
- `migration_owner`, `app_runtime`, `cutover_writer`, `legacy_writer`, `retention_worker` 권한을 분리하고 웹 계정에는 DDL·Flyway history·레거시 DML·retention 삭제 권한을 주지 않는다.
- 역할·권한 SQL과 로컬 PostgreSQL 검증은 M1에서 구현하되, 운영 credential 발급·적용과 migration 배포 job은 최종 배포 단계에서 수행한다.
- 운영 책임자가 DB를 `NEW_OR_SAMPLE`, `LEGACY_OPERATIONAL`, `UNKNOWN`으로 승인하고, read-only preflight가 빈 DB·정확한 레거시 DB·기존 Flyway 관리 DB 여부를 독립 검증한다. 승인과 기술 상태가 다르거나 `UNKNOWN`이면 아무것도 변경하지 않는다.
- 완료 조건은 빈 DB·레거시 fixture migration, 잘못된 schema 전체 rollback, 재시작 데이터 불변, 복합 FK·부분 unique·`RESTRICT` 부정 테스트 통과다.

### M2. 조직·출석 도메인

- 최소 테스트 actor 계약을 만든 뒤 부서, 교사 소속, NFC 카드와 assignment 상태 전이를 구현한다.
- 교사 삭제는 제거하고 `소속 종료 + 카드 연결 종료 + 카드 상태 전이 + 감사 기록`을 한 트랜잭션으로 처리한다.
- 정책은 draft → 검증 → publish 순서로 구현하고, 발행 후 구간을 불변으로 유지한다.
- 출석 날짜 생성 시 활성 교사를 `attendance_target`으로 snapshot한다.
- 하나의 `receivedAt`과 고정된 정책으로 `PRESENT`, 동적 `LATE`, `ABSENT`를 판정한다.
- 수동 등록·정정, 대상자 누락 추가, 자동 결석 마감과 통계를 구현한다.
- 모든 서비스와 Mapper는 인증된 `departmentId`를 필수로 받고 `department → device/day → event/record` 잠금 순서를 지킨다.
- 자동 마감은 DB procedure가 아니라 Spring scheduler가 날짜별 애플리케이션 트랜잭션을 호출한다. 재기동 시 과거 미마감 날짜를 catch-up한다.

### M3. 계정·보안·관리자 웹

- `AccountPrincipal(accountId, systemRole)`과 활성 부서 권한 조회를 Spring Security에 연결한다.
- 최초 `SYSTEM_ADMIN`은 interactive CLI에서 한 번만 bootstrap하고 기본 계정·공개 비밀번호는 만들지 않는다.
- 계정 상태는 `PENDING_SETUP`, `ACTIVE`, `DISABLED`로 구성한다.
  - 신규 계정은 비밀번호 없이 `PENDING_SETUP`으로 생성하고 화면에는 `초대 대기`로 표시한다.
  - 초대받은 사용자가 회원가입을 완료하면 BCrypt cost 12 비밀번호 hash를 저장하고 `ACTIVE`로 전환한다.
  - 로그인은 `ACTIVE`이면서 hash가 존재하는 계정만 허용한다.
- `account_credential_token`은 `INVITATION`/`RESET` 목적, 256-bit 원문, HMAC-SHA-256 hash, 발급자·발급·만료·사용·무효 시각을 저장한다. 새 발급은 기존 미사용 토큰을 무효화한다.
- 시스템 관리자는 회원가입 초대·비밀번호 재설정 링크를 발급 직후 한 번만 볼 수 있다. 관리자가 링크를 복사해 승인된 1:1 메신저로 직접 전달하며, 애플리케이션은 메신저 계정·연락처를 저장하거나 자동 발송하지 않는다. token은 URL fragment로 전달하고 공개 페이지가 즉시 fragment를 제거한 뒤 POST body로만 제출한다. query/path/access log에는 token을 넣지 않는다.
- 회원가입 초대 수락·비밀번호 재설정 URL은 `/account/setup`, `/account/password-reset`으로 고정하고 `Cache-Control: no-store`, generic 오류와 rate limit을 적용한다.
- 흔한·유출 비밀번호 목록 검사는 MVP 이후 보안 강화 항목으로 미룬다. MVP는 길이·UTF-8 byte 상한·비밀번호 확인과 BCrypt cost 12 저장을 강제한다.
- 관리자 웹과 `/api/v1/device/**`는 별도 Security filter chain으로 구성한다. 웹은 세션·CSRF, 장치는 stateless header 인증을 사용한다.
- 구현 화면 순서는 시스템 부서·계정·권한 → 부서 대시보드 → 교사·카드 → 정책 → 출석 날짜 → 수동 등록·정정 → 감사·운영 화면이다.
- 각 화면은 Controller만 만들지 않고 application transaction, scoped Mapper, audit, validation, PRG와 IDOR 테스트까지 한 묶음으로 완료한다.

### M4. 장치 API와 HTTP 시험 도구

- 시스템 장치 관리에서 장치 생성, 비밀키 1회 표시, credential 시험, 활성화, 비활성화, 교체, 폐기를 구현한다.
- 장치 키는 256-bit 난수로 발급하고 별도 pepper를 사용한 HMAC-SHA-256 hash만 저장한다.
- `DevicePrincipal(deviceId, departmentId, credentialVersion)`을 만드는 장치 인증 filter를 구현한다.
- 다음 계약을 OpenAPI와 정확히 일치시킨다.
  - `POST /api/v1/device/credential-tests`
  - `POST /api/v1/device/check-ins`
  - `X-Device-Code`, `X-Device-Key`
  - `{uid, requestId}`와 공통 응답 envelope
- strict JSON, 중복 필드 거부, 1 KiB 제한, UID 4·7·10-byte, request ID 형식, rate limit을 filter 단계에서 검증한다.
- `(device_id, request_id)` event를 선점하고 최초 HTTP status와 canonical JSON을 저장해 동일 요청 재시도에 그대로 반환한다.
- 카드·소속·날짜·대상자·시간 판정과 출석 기록은 같은 업무 트랜잭션으로 확정한다.
- Arduino 대신 다음 HTTP smoke 흐름을 제공한다.
  - credential test: 유효 header와 빈 body
  - check-in: UID와 새 requestId
  - 동일 requestId 재전송
  - 같은 requestId에 다른 UID 전송
  - 인증 실패, 413, rate limit과 상태 경합
- 장치 키는 명령행 인자로 받지 않고 숨김 입력 또는 Git 제외 환경파일로 전달한다.
- 기존 `RFID.ino`는 배포 불가 레거시로 표시한다. 실제 Arduino 확보 후 OpenAPI 클라이언트로 다시 작성하며 그전에는 현장 MVP 완료로 간주하지 않는다.

### M5~M6. 운영과 파일럿

- JDK 21 multi-stage Docker image와 단일 인스턴스 구성을 만든다.
- Caddy에서 공개 HTTPS를 종단하고 애플리케이션은 Neon pooled URL, migration·backup은 direct URL을 사용한다.
- 운영 profile은 DB URL, token/device pepper, 공개 base URL이 없으면 기동 실패시킨다.
- actuator는 내부 health만 노출하고 구조화 로그에서 비밀번호, token, 장치 키, 전체 UID와 연락처를 마스킹한다.
- 백업은 Neon direct URL의 `pg_dump`와 별도 저장소 복원 시험으로 검증한다. 업무 DB 보유기간은 확정됐고, 만료 데이터는 자동 삭제하되 제한된 수명의 운영 백업에만 일시적으로 남을 수 있다. backup의 보유기간·저장 위치·암호화·삭제 담당자가 승인되기 전에는 실제 데이터 backup을 시작하지 않는다.
- 컷오버는 `admin-write → 단일 장치 시험·활성화 → 첫 check-in → 나머지 장치 → scheduler` 순서로 진행한다.
- Arduino 확보 전에는 HTTP simulator로 2개 부서 E2E까지 수행한다. 실제 장치 확보 후 빨강·초록 LED 패턴, 네트워크 timeout, 50회 성능 시험과 4회 현장 파일럿을 추가해야 최종 MVP가 완료된다.

## 3. 테스트와 완료 기준

- H2를 사용하지 않고 모든 Mapper·transaction·migration 테스트를 PostgreSQL Testcontainers에서 실행한다.
- 도메인 테스트는 정책 경계, 날짜 snapshot, 수동 정정, 통계 분모와 주입된 `Clock`을 검증한다.
- 동시성 테스트는 중복 태깅, 같은 requestId, 카드 교체, 소속 종료, 정책 발행, 자동 마감 경합을 독립 DB connection으로 실행한다.
- 보안 테스트는 두 filter chain, CSRF, session fixation, idle 30분·absolute 8시간, 다른 부서 IDOR, token 재사용·만료, 장치 상태·version 경합을 검증한다.
- 계약 테스트는 OpenAPI의 모든 response example과 HTTP status/code 조합을 검증한다.
- migration 테스트는 빈 DB, 정확한 레거시 DB, 알 수 없는 DB, 권한, importer dry-run과 백업 복원을 포함한다.
- 각 단계는 [TEST_PLAN.md](./docs/TEST_PLAN.md)의 해당 안정 ID가 통과해야 다음 feature flag를 열 수 있으며, 최종적으로 AC-01~37을 모두 통과해야 한다.

## 4. 명시적 범위와 가정

- 현재 DB에 실제 데이터가 있는지는 구현 시 preflight가 판정하며, 임의로 삭제하거나 샘플 DB로 추정하지 않는다.
- 기존 `member.age`와 `member.card_uid`는 원본 호환용으로만 보존하고 신규 화면에서는 읽거나 수정하지 않는다. `birth`는 생일 관리와 만 나이 계산의 기준이므로 신규 등록·기본정보 수정·활성화에서 미래가 아닌 정확한 날짜를 필수로 입력하되, 레거시 결측값을 기존 나이에서 추정하지 않는다.
- `SYSTEM_ADMIN`은 명시적으로 부서 관리자 권한을 추가로 받지 않는 한 출석 업무 데이터를 관리할 수 없다.
- 다중 인스턴스, Redis, 메시지 브로커, 네이티브 앱, CSV·Excel 내보내기와 자동 출석 날짜 생성은 제외한다.
- Arduino가 없는 현재 단계의 완료 지점은 서버·웹·HTTP 장치 계약과 운영 배포 준비까지다. 실제 현장 완료는 하드웨어 확보 후 M6에서 판정한다.
