# Attend MVP 테스트 계획

> 기준 문서: [PROJECT_DEFINITION.md](./PROJECT_DEFINITION.md), [ARCHITECTURE.md](./ARCHITECTURE.md), [DATABASE_DESIGN.md](./DATABASE_DESIGN.md), [ATTENDANCE_DDL.sql](./ATTENDANCE_DDL.sql), [MIGRATION_PLAN.md](./MIGRATION_PLAN.md), [device-api.yaml](./device-api.yaml), [SECURITY_MATRIX.md](./SECURITY_MATRIX.md), [ADMIN_UI_SPEC.md](./ADMIN_UI_SPEC.md)
>
> 대상 릴리스: 현장 사용 가능한 MVP
>
> 작성 기준일: 2026-07-31
>
> 상태: 구현 전 테스트 기준

## 0. 결론

Attend MVP는 화면이 열리고 NFC 요청 한 건이 성공하는 것만으로 완료되지 않는다. 다음 네 가지를 자동화된 실제 PostgreSQL 테스트와 현장 시험으로 입증해야 한다.

1. 잘못된 출석·중복 출석·부분 저장이 발생하지 않는다.
2. 부서 관리자와 장치가 다른 부서 데이터에 접근하거나 영향을 줄 수 없다.
3. 재시도, 동시 요청, 서버 재시작과 자동 마감 경합에도 최종 결과가 일관된다.
4. 배포·마이그레이션·복구 과정이 운영 데이터를 삭제하거나 샘플 데이터로 덮어쓰지 않는다.

현재 코드의 `contextLoads()` 한 건은 위 조건을 검증하지 못한다. 이 문서의 필수 게이트를 통과하기 전에는 실제 출석 업무에 사용하지 않는다.

---

## 1. 목적과 문서 기준

### 1.1 목적

- 업무 규칙을 반복 가능한 테스트 사례로 변환한다.
- domain, Mapper, application transaction, 보안, HTTP 계약과 펌웨어의 책임을 분리해 검증한다.
- PostgreSQL 제약과 명시적 행 잠금이 실제 동시 transaction에서 동작하는지 확인한다.
- Flyway 신규 DB·레거시 baseline·권한·rollback 절차를 배포 전에 재현한다.
- 사용자 인수 시험과 현장 파일럿의 합격 기준을 고정한다.

### 1.2 기준 문서와 우선순위

| 기준 | 테스트에서 사용하는 내용 |
|---|---|
| `PROJECT_DEFINITION.md` | BR·FR·NFR·AC 업무 요구와 완료 조건 |
| `ARCHITECTURE.md` | 모듈 경계, 보안 chain, 시간·잠금·트랜잭션·배포 구조 |
| `DATABASE_DESIGN.md`, `ATTENDANCE_DDL.sql` | 테이블, FK, CHECK, unique, 상태와 인덱스 |
| `device-api.yaml` | 장치가 관찰하는 정확한 HTTP 요청·응답 계약 |
| `SECURITY_MATRIX.md` | 역할·부서·경로·서비스·DB 권한별 허용과 거부 |
| `ADMIN_UI_SPEC.md` | 화면 필드, 동작, 오류, 접근성과 반응형 기준 |
| `MIGRATION_PLAN.md` | DB 분류, Flyway, 이관, 컷오버, rollback과 복원 |

충돌하면 업무 의미는 프로젝트 정의서, 외부 HTTP 형식은 OpenAPI, 물리 제약은 DDL을 우선한다. 테스트가 문서 간 충돌을 발견하면 기대값을 임의로 정하지 않고 문서를 먼저 수정한다.

### 1.3 범위

포함:

- 정책·날짜·대상자·출석·통계 domain 규칙
- 구성원·소속·카드 상태와 연결 이력
- 장치 인증, check-in, credential test와 멱등성
- 자동 결석, 사후 수동 등록·정정과 감사
- 관리자 웹 인증·인가·부서 격리
- MyBatis SQL, PostgreSQL 제약·잠금·권한
- Flyway, legacy 격리, 백업·복원
- Arduino HTTP 통합, 재시도와 현장 신호
- 성능, 호환성, 관측성과 파일럿

제외:

- 후속 범위인 일반 `USER`, CSV·Excel 내보내기, 자동 날짜 생성
- 다중 교회, 3대 이상 장치의 일괄 관리
- 다중 인스턴스 leader election과 대규모 부하
- 현재 MVP에서 command를 제공하지 않는 부서 비활성화, 정책 폐기와 장치 부서 재배정

---

## 2. 위험 기반 우선순위

| 등급 | 실패 예시 | 릴리스 처리 |
|---|---|---|
| P0 | 운영 데이터 삭제, 다른 부서 데이터 접근, 잘못된 교사 카드 연결, 출석·event 부분 commit | 한 건이라도 실패하면 배포 금지 |
| P1 | 중복 출석, 잘못된 시간 구간, 자동 마감 누락, 권한 우회, retry 계약 위반 | 한 건이라도 실패하면 파일럿 금지 |
| P2 | 잘못된 오류 문구, PRG 누락, 모바일 조작성·접근성 문제 | 수정하거나 승인된 제한사항 기록 |
| P3 | 시각적 정렬·비핵심 문구 문제 | 후속 이슈로 관리 가능 |

테스트 수나 코드 커버리지 숫자는 품질의 대체물이 아니다. P0·P1 규칙은 모든 정상 경계, 대표 부정 경계와 rollback 경로가 명시적인 테스트 ID를 가져야 한다.

---

## 3. 테스트 계층

| 계층 | 도구·환경 | 대상 | 외부 의존 |
|---|---|---|---|
| Domain unit | JUnit, 고정 `Clock` | 정책 판정, 날짜 상태, 통계 계산, 상태 전이 | 없음 |
| Mapper integration | Testcontainers PostgreSQL + Flyway | SQL, FK·CHECK·unique·부분 인덱스, 행 잠금 | 실제 PostgreSQL |
| Application integration | Spring test + Testcontainers | transaction, 권한, audit/event, rollback | 실제 PostgreSQL |
| Security/MVC | MockMvc + 실제 security 설정 + Testcontainers | 두 filter chain, CSRF, 세션, IDOR, PRG | 실제 PostgreSQL |
| Device contract | OpenAPI validation + HTTP integration | schema, status, header, body 제한, 재시도 계약 | 기동한 Spring Boot |
| Concurrency | 두 개 이상의 독립 DB connection/transaction | 잠금 순서와 race condition | 실제 PostgreSQL |
| Migration | 빈 DB·레거시 fixture·복원 DB | V001~V008, baseline 0, 권한, 재현성 | 별도 PostgreSQL |
| Firmware integration | 실제 Arduino·NFC reader·운영 유사 네트워크 | UID, HTTP, timeout, LED·부저 | staging 서버 |
| E2E·파일럿 | 실제 관리자·교사·장치 | 전체 업무 흐름과 운영 절차 | 운영 유사/실환경 |

H2는 PostgreSQL 부분 인덱스, constraint, `ON CONFLICT`, lock과 transaction 동작을 정확히 재현하지 못하므로 통합 테스트 DB로 사용하지 않는다.

---

## 4. 테스트 환경과 데이터 안전

### 4.1 환경

| 환경 | 용도 | 데이터 |
|---|---|---|
| unit | 순수 규칙 | 메모리 객체 |
| integration | Mapper·application·security·contract | 테스트별 임시 PostgreSQL container |
| migration rehearsal | 신규·레거시·복원 시험 | 비식별 fixture와 운영 immutable dump의 승인 복제본 |
| staging | 펌웨어·브라우저·성능·운영 smoke | 가상 교사·시험 카드 |
| production | 승인된 읽기 smoke와 단일 공식 태깅 | 실제 운영 데이터 |

자동 테스트는 운영 DB URL을 받을 수 없어야 한다. 다음 중 하나라도 참이면 테스트 기동을 실패시킨다.

- profile이 `test`가 아님
- DB host·database가 운영 allowlist와 일치
- 임시 database/container 식별자가 없음
- `spring.sql.init.mode`가 `never`가 아님
- migration target과 애플리케이션 지원 schema version이 다름

### 4.2 공통 fixture

최소 fixture는 다음을 포함한다.

- 부서 A·B
- `SYSTEM_ADMIN` 한 명
- A 전용, B 전용, A+B 겸임 `DEPARTMENT_ADMIN`
- 관리자 권한이 없는 활성 계정
- 부서별 활성 교사 3명과 종료된 소속 1명
- `AVAILABLE`, `ACTIVE`, `LOST`, `RETIRED` 카드
- `INACTIVE`, `ACTIVE`, `REVOKED` 장치
- 정상 1구간 + 지각 2구간인 발행 정책과 잘못된 draft 정책
- 어제·오늘·미래의 `SCHEDULED`, `FINALIZED`, `CANCELED` 날짜
- 정상·지각·결석·미출석 대상자

개인정보는 가상 이름과 가상 연락처만 사용한다. 실제 장치 키, Wi-Fi 비밀번호와 운영 UID를 fixture나 실패 로그에 넣지 않는다.

### 4.3 시간 fixture

기본 시간대는 `Asia/Seoul`이다. 테스트는 주입된 `Clock`을 사용해 최소 다음 시각을 고정한다.

- 태깅 시작 1나노초 전·정확히 시작·1나노초 후
- 정상 구간 상한 정확히·직후
- 각 지각 구간 상한 정확히·직후
- 마지막 허용 시각 직후
- 23:59:59와 다음 날 00:00:00
- 서버 중지 상태로 날짜가 지난 뒤 재기동한 시각

신규 업무 코드에서 직접 호출한 `LocalDateTime.now()` 또는 `LocalDate.now()`는 정적 검사나 코드 리뷰에서 실패로 처리한다.

---

## 5. 단계별 품질 게이트

| 단계 | 필수 합격 조건 |
|---|---|
| M1 안전화 | 파괴적 SQL init 제거, 빈 DB·baseline migration, runtime 권한, 재시작 데이터 보존 |
| M2 출석 domain | 정책·날짜·대상자·통계, 자동 마감, 수동 등록, 카드 원자성과 동시성 |
| M3 관리자 웹 | 실제 계정·부서 인가, 전 화면 validation·PRG, 다른 부서 IDOR 부정 시험 |
| M4 장치 통합 | OpenAPI contract, 장치 상태·키, check-in 멱등성, 실제 UID·응답 기반 신호 |
| M5 운영 준비 | HTTPS/격리망, feature flag, health·log, 백업 복원과 컷오버 리허설 |
| M6 파일럿 | 2개 이상 부서, 5~20명 규모, 최소 4회 운영, 무복구 데이터 손실 0건 |

뒤 단계의 테스트가 앞 단계 실패를 상쇄하지 않는다.

---

## 6. Domain 테스트

### 6.1 정책 판정

| ID | 입력·상황 | 기대 결과 |
|---|---|---|
| DOM-POL-001 | 정상 구간 1개, 지각 구간 1개 이상, 상한 오름차순 | 발행 가능 |
| DOM-POL-002 | 첫 구간이 `LATE` | 발행 거부 |
| DOM-POL-003 | 두 번째 이후 `PRESENT` | 발행 거부 |
| DOM-POL-004 | 지각 구간 없음 | 발행 거부 |
| DOM-POL-005 | 정상 상한이 시작보다 빠름 | 발행 거부 |
| DOM-POL-006 | 상한 중복·역전 | 발행 거부 |
| DOM-POL-007 | 자정을 넘기는 구간 | 발행 거부 |
| DOM-POL-008 | 시작 전·마지막 상한 후 | `CHECK_IN_NOT_OPEN`·`CHECK_IN_CLOSED` |
| DOM-POL-009 | 각 상한과 정확히 같은 시각 | 해당 구간 포함 |
| DOM-POL-010 | 발행 정책 수정·구간 추가·삭제 | 모두 거부 |
| DOM-POL-011 | 새 정책 발행 | 기존 날짜의 정책과 과거 기록 불변 |

### 6.2 출석 날짜와 대상자

| ID | 상황 | 기대 결과 |
|---|---|---|
| DOM-DAY-001 | 오늘 또는 미래 날짜 등록 | 발행 정책과 활성 소속 snapshot 저장 |
| DOM-DAY-002 | 과거 날짜 등록 | 거부 |
| DOM-DAY-003 | 같은 부서·같은 날짜 중복 | 업무 오류 + DB unique 보장 |
| DOM-DAY-004 | 당일 태깅 시작 이후 신규 등록 | 거부 |
| DOM-DAY-005 | 태깅 시작 전 일반 대상 추가·제외 | 허용, audit 저장 |
| DOM-DAY-006 | 태깅 시작 후 일반 대상·정책 변경 | 거부 |
| DOM-DAY-007 | 기록 없는 날짜 취소 | `CANCELED`, 마감·통계 제외 |
| DOM-DAY-008 | 기록 있는 날짜 취소 | 거부 |
| DOM-DAY-009 | 이후 교사 추가·부서 제외 | 기존 snapshot 자동 변경 없음 |
| DOM-DAY-010 | 오늘 날짜 | DB 상태는 `SCHEDULED`, 화면 운영 상태만 `OPEN` |

### 6.3 수동 등록·정정

| ID | 상황 | 기대 결과 |
|---|---|---|
| DOM-MAN-001 | 기존 대상자의 실제 출석 시각 입력 | 서버가 고정 정책으로 상태·구간 계산 |
| DOM-MAN-002 | request에 상태·구간·처리자 ID를 추가 | binding allowlist에서 거부하고 어떤 행도 변경하지 않음 |
| DOM-MAN-003 | `PRESENT`·`LATE`인데 실제 시각 없음 | 거부 |
| DOM-MAN-004 | 실제 시각이 출석 날짜 밖 | 거부 |
| DOM-MAN-005 | 실제 시각이 소속 기간 `[joined_at, ended_at)` 밖 | 거부 |
| DOM-MAN-006 | `ended_at IS NULL`인 현재 소속 | 상한 없는 소속 기간으로 허용 |
| DOM-MAN-007 | 누락자를 `CANCELED`가 아닌 날짜에 사후 등록 | 대상자 + `MANUAL` 기록 원자 생성 |
| DOM-MAN-008 | 누락자를 `CANCELED` 날짜에 등록 | 거부 |
| DOM-MAN-009 | 대상자 생성 또는 기록 생성 강제 실패 | 둘 다 rollback |
| DOM-MAN-010 | 상태에 영향 주는 정정 | `source=MANUAL`, actor·before/after·사유 저장 |
| DOM-MAN-011 | 메모만 변경 | 기존 source 유지, 변경 audit 저장 |
| DOM-MAN-012 | `ABSENT`로 정정 | 시각·구간 snapshot은 `NULL` |
| DOM-MAN-013 | 사유 공백 또는 500자 초과 | 화면·서버에서 거부, DB 변경 없음 |
| DOM-MAN-014 | 실제 시각이 정책 시작 전·마지막 상한 후 | 거부 |
| DOM-MAN-015 | 실제 시각이 구간 시작·상한과 정확히 같음 | 자동 태깅과 같은 포함 경계로 서버 계산 |
| DOM-MAN-016 | 마감 결석을 실제 출석으로 정정 | `AUTO_ABSENCE → MANUAL PRESENT/LATE`, 날짜는 `FINALIZED` 유지 |
| DOM-MAN-017 | 이미 활성 대상자인 교사를 누락자 flow로 등록 | 거부하고 기존 정정 flow로 안내 |
| DOM-MAN-018 | audit insert를 강제 실패 | target·record 변경까지 전체 rollback |
| DOM-MAN-019 | `FINALIZED` 날짜의 누락자 사후 등록 | target + record 생성 후에도 날짜는 `FINALIZED` 유지 |

### 6.4 통계

| ID | 상황 | 기대 결과 |
|---|---|---|
| DOM-STAT-001 | `FINALIZED` 대상 날짜 | 통계 분모에 포함 |
| DOM-STAT-002 | `SCHEDULED`·`CANCELED` 날짜 | 분모에서 제외 |
| DOM-STAT-003 | 정상·각 지각·결석 횟수 | 합계가 동일한 `FINALIZED` 대상 날짜 수와 일치 |
| DOM-STAT-004 | 지각 단계명 변경·새 정책 발행 | 과거 구간 snapshot 표시 유지 |
| DOM-STAT-005 | 수동 정정 | 별도 summary update 없이 정상·지각·결석 횟수와 비율 재계산 |
| DOM-STAT-006 | 레거시 출석 | 신규 공식 통계와 혼합하지 않음 |
| DOM-STAT-007 | 정상·단계별 지각·전체 지각·결석 비율 | 모두 동일한 대상 날짜 수를 분모로 계산 |
| DOM-STAT-008 | 대상 날짜 수가 0 | 0으로 나누지 않고 제품 계약의 `0건`·`—` 표시 |
| DOM-STAT-009 | `is_target = FALSE` 또는 취소 날짜 | 횟수와 분모 모두에서 제외 |

---

## 7. 구성원·소속·카드 테스트

### 7.1 상태 전이

| ID | 작업 | 허용 전이·결과 |
|---|---|---|
| CARD-001 | 자기 부서 장치의 최신 `UNKNOWN_UID` event에서 미등록 카드 등록·연결 | 카드 생성, `AVAILABLE → ACTIVE`, assignment와 audit 생성 |
| CARD-002 | 정상 교체 | 기존 `ACTIVE → AVAILABLE`, 기존 연결 종료, 신규 `AVAILABLE → ACTIVE`, 신규 연결 생성 |
| CARD-003 | 정상 해제 | 연결 종료 + `ACTIVE → AVAILABLE` |
| CARD-004 | 분실 | 연결 종료 + `ACTIVE → LOST` |
| CARD-005 | 영구 폐기 | 필요 시 연결 종료 + `RETIRED` |
| CARD-006 | `RETIRED` 재연결 | 거부 |
| CARD-007 | UID 수정·카드 물리 삭제 | command 미제공, DB 권한 거부 |
| CARD-008 | 카드별 또는 교사별 활성 연결 2건 | 서비스 거부 + 부분 unique 위반 |
| CARD-009 | 다른 부서 소속에 연결 | 거부, 기존 소유자 상세 비노출 |
| CARD-010 | 종료·분실·폐기 사유 공백 | 화면·서버·DB에서 거부 |
| CARD-011 | request body의 처리 관리자 ID 조작 | 무시하고 인증 세션 ID 저장 |
| CARD-012 | 다른 부서의 `UNKNOWN_UID` event ID를 제출 | 404, 카드·소유 부서·UID 상세 비노출 |
| CARD-013 | event 선택 없이 UID 문자열을 직접 제출하거나 event UID를 변조 | 거부, request UID를 카드 식별의 근거로 사용하지 않음 |
| CARD-014 | 과거 `UNKNOWN_UID` 뒤 이미 등록·연결된 UID를 다시 제출 | 현재 카드·assignment 상태 재검증 후 거부 |
| CARD-015 | 자기 부서 최근 `INACTIVE_CARD` event의 `AVAILABLE` 카드 재연결 | 기존 카드 행 재사용·`ACTIVE` 전이; `LOST`·`RETIRED`는 거부 |
| CARD-016 | 같은 event·UID를 두 교사에게 동시 연결 | 정확히 한 transaction만 성공, 활성 assignment 한 건 |
| CARD-017 | 연결 직후 해당 교사가 활성 대상자인 진행 출석일에 태깅 | 신규 assignment로 check-in 성공 |

### 7.2 원자성

| ID | 강제 실패 지점 | 기대 결과 |
|---|---|---|
| CARD-ATOM-001 | member 생성 후 membership 실패 | member까지 rollback |
| CARD-ATOM-002 | membership 후 card 생성 실패 | member·membership까지 rollback |
| CARD-ATOM-003 | 기존 assignment 종료 후 카드 상태 변경 실패 | 기존 assignment·카드 상태 복원 |
| CARD-ATOM-004 | 신규 카드 활성화 후 신규 assignment 실패 | 신규 카드 상태와 기존 카드·assignment 모두 복원 |
| CARD-ATOM-005 | 부서 제외 중 membership 종료 후 card disposition 실패 | membership·assignment·카드·미래 target 전체 복원 |
| CARD-ATOM-006 | audit 저장 실패 | 관련 업무 변경 전체 rollback |

부분 commit된 member, 활성 assignment, 잘못된 카드 상태와 누락 audit가 한 건도 없어야 한다.

### 7.3 부서 제외

| ID | 상황 | 기대 결과 |
|---|---|---|
| ROSTER-001 | 활성 교사 제외 | membership·assignment 종료, 카드 disposition, 사유·actor 저장 |
| ROSTER-002 | 미래 날짜 일괄 제외 선택 | 태깅 시작 전 대상만 `is_target=false` |
| ROSTER-003 | 과거·시작된 날짜 | 대상자·기록 변경 없음 |
| ROSTER-004 | 다른 활성 소속 없음 | `member.active=false` |
| ROSTER-005 | 물리 삭제 요청 | UI·service 미제공, DB 권한 거부 |

---

## 8. Check-in transaction과 동시성

### 8.1 단일 요청

| ID | 상황 | DB와 응답 기대값 |
|---|---|---|
| CHK-001 | 정상 카드·정상 구간 | `PRESENT` record + 확정 event 같은 commit |
| CHK-002 | 지각 구간 | `LATE`와 구체적 band snapshot |
| CHK-003 | 미등록 UID | record 없음, `UNKNOWN_UID` event |
| CHK-004 | 비활성·분실·폐기 카드 | record 없음, 구분된 확정 event |
| CHK-005 | 부서 미소속 | record 없음, `NOT_DEPARTMENT_MEMBER` event |
| CHK-006 | 당일 날짜 없음 | record 없음, `NO_ATTENDANCE_DAY` event |
| CHK-007 | 시작 전·종료 후 | record 없음, 해당 업무 실패 event |
| CHK-008 | DB 오류·transaction timeout | record와 확정 event 모두 rollback |
| CHK-009 | HTTP commit 직전 오류 | 성공 응답 금지 |
| CHK-010 | 태깅 성공 | 같은 내용을 `audit_log`에 중복 저장하지 않음 |
| CHK-011 | 활성 카드·소속이지만 활성 target 행이 없음 | record 없음, `NOT_ATTENDANCE_TARGET` event |
| CHK-012 | target 행은 있으나 `is_target = FALSE` | record 없음, `NOT_ATTENDANCE_TARGET` event |
| CHK-013 | `CANCELED` 또는 `FINALIZED` 날짜 | record 없음, `CHECK_IN_CLOSED` event |
| CHK-014 | 미등록 UID이고 날짜가 없거나 취소됨 | 날짜보다 카드 검사를 먼저 적용해 `UNKNOWN_UID` event와 등록함 항목 생성 |

### 8.2 request ID 멱등성

| ID | 상황 | 기대 결과 |
|---|---|---|
| IDEM-001 | 같은 device·requestId·UID 순차 재요청 | 최초 status·body byte-equivalent 재현, event 1건 |
| IDEM-002 | 같은 요청 동시 2건 | 한 transaction만 처리, 다른 요청은 최초 결과 재현 |
| IDEM-003 | 같은 device·requestId, 다른 UID | `REQUEST_ID_CONFLICT`, 기존 event 불변 |
| IDEM-004 | 다른 device, 같은 requestId | 서로 독립 |
| IDEM-005 | 같은 교사, 다른 requestId 재태깅 | event는 각각, 최종 record는 1건·최초 시각 유지 |
| IDEM-006 | 인프라 실패 후 같은 ID 재시도 | rollback된 요청을 정상 재처리 가능 |
| IDEM-007 | 허용 문자 밖·65자 requestId | event 생성 전 거부 |

### 8.3 공통 잠금 순서

모든 concurrency test는 독립 connection과 barrier/latch를 사용해 다음 순서를 실제로 만든다.

```text
department
→ device(필요 시)
→ tag_event_log(check-in만)
→ attendance_day ID 오름차순
→ membership·card
→ attendance_target·attendance_record
→ audit/event 완성
```

| ID | 동시에 실행하는 작업 | 합격 기준 |
|---|---|---|
| CON-001 | 같은 교사·다른 request ID check-in 2건 | 한 요청만 `201`, 다른 요청은 `200 ALREADY_CHECKED_IN`; record 1건·event 2건·최초 확정 시각 유지 |
| CON-002 | check-in ↔ 자동 마감 | check-in 선점이면 그 기록을 보존한 뒤 나머지만 결석 처리; 마감 선점이면 `ABSENT`·`FINALIZED` 후 check-in은 `CHECK_IN_CLOSED`이고 덮어쓰기 없음 |
| CON-003 | check-in ↔ 카드 교체 | check-in 선점이면 구 카드 출석을 보존하고 교체 commit; 교체 선점이면 구 카드는 `INACTIVE_CARD`, 신 카드만 이후 유효 |
| CON-004 | check-in ↔ 부서 제외 | check-in 선점이면 출석을 보존한 뒤 제외; 제외 선점이면 종료된 assignment·소속으로 신규 record가 생기지 않음 |
| CON-005 | 날짜 snapshot 생성 ↔ 교사 추가·제외 | 자격 변경 선점이면 새 상태를 전부 반영한 snapshot, 날짜 생성 선점이면 기존 snapshot을 유지하고 부분 명단 없음 |
| CON-006 | check-in ↔ 대상자 제외 | 제외가 시작 전 선점하면 `NOT_ATTENDANCE_TARGET`; check-in이 시작 경계에서 선점하면 기록을 보존하고 뒤의 제외는 거부 |
| CON-007 | check-in ↔ credential 교체·장치 비활성화 | credential version 재검증, 구키 신규 결과 없음 |
| CON-008 | 자동 마감 2건 | `ABSENT`, `FINALIZED`, audit 각각 중복 없음 |
| CON-009 | 여러 미래 날짜를 바꾸는 부서 제외 2건 | day ID 오름차순, deadlock 없음 |
| CON-010 | tag event FK 잠금 ↔ 장치 관리 | `department → device → event` 순서로 deadlock 없음 |
| CON-011 | check-in ↔ 정책 재선택 | 재선택 선점이면 새 고정 정책으로 판정; check-in 선점이면 기존 고정 정책 판정 후 재선택 거부 |
| CON-012 | check-in ↔ 날짜 취소 | 취소 선점이면 `CHECK_IN_CLOSED`; check-in 선점이면 기록 보존 후 취소 거부 |
| CON-013 | 수동 등록·정정 ↔ 자동 마감 | 수동 선점이면 해당 기록 보존 후 나머지만 결석; 마감 선점이면 `FINALIZED`를 유지한 채 감사 가능한 수동 정정 |
| CON-014 | 수동 등록 ↔ NFC check-in | 수동 선점이면 NFC는 `ALREADY_CHECKED_IN`; NFC 선점이면 수동 command가 최신 record를 잠근 뒤 명시적 정정으로만 변경 |
| CON-015 | 같은 미등록 UID를 두 교사에게 연결 | 한 연결만 commit, 다른 요청은 409; 카드·활성 assignment·audit에 부분 행 없음 |

deadlock이나 lock timeout을 단순 재시도로 숨기지 않는다. 예상한 lock 대기 상한 안에 끝나는지와 최종 행을 함께 검증한다.

---

## 9. 자동 마감 테스트

| ID | 상황 | 기대 결과 |
|---|---|---|
| FIN-001 | 과거 `SCHEDULED` 날짜 | 현재 소속 상태와 무관하게 `attendance_target.is_target = TRUE`이고 기록 없는 snapshot 대상자만 `ABSENT`, 이후 `FINALIZED` |
| FIN-002 | 오늘·미래 날짜 | 처리하지 않음 |
| FIN-003 | `CANCELED`·이미 `FINALIZED` | 처리하지 않음 |
| FIN-004 | 일부 대상자 정상·지각 | 기존 기록 보존, 누락자만 결석 |
| FIN-005 | 중간 강제 오류 | 결석·상태·audit 전체 rollback |
| FIN-006 | 같은 날짜 반복 실행 | 결과와 idempotency audit 한 건 |
| FIN-007 | 여러 날짜 중 한 날짜 실패 | 해당 날짜 rollback, 나머지는 계속 처리 |
| FIN-008 | 자정에 서버 중지 후 재기동 | startup catch-up이 모든 과거 미마감 날짜 처리 |
| FIN-009 | 대상자 수와 기록 수 불일치 유도 | `FINALIZED` 전환 거부·오류 기록 |
| FIN-010 | 마감 후 NFC 태깅 | 기존 결석 자동 변경 없음 |
| FIN-011 | 소속은 종료됐지만 `is_target = TRUE`로 고정된 대상자 | 다른 기록이 없으면 `ABSENT` 생성 |
| FIN-012 | 소속은 활성이나 `is_target = FALSE`인 교사 | `ABSENT` 생성·통계 분모 포함 모두 금지 |

---

## 10. DB·Mapper 테스트

### 10.1 제약

다음 제약은 service 사전 검증과 별개로 DB 직접 insert/update로 위반을 시도한다.

| ID | 직접 위반 또는 통합 검증 | 기대 결과 |
|---|---|---|
| DB-CST-001 | username 대소문자만 다른 계정 insert | 대소문자 무시 unique 위반 |
| DB-CST-002 | 같은 계정·부서의 활성 관리자 역할 2건 | 부분 unique 위반 |
| DB-CST-003 | 같은 교사의 활성 소속 2건 또는 같은 부서 활성 소속 중복 | 부분 unique 위반 |
| DB-CST-004 | 같은 UID 카드 2건 | unique 위반 |
| DB-CST-005 | 카드별 또는 교사별 활성 assignment 2건 | 각 부분 unique 위반 |
| DB-CST-006 | 같은 부서·날짜 2건 | unique 위반 |
| DB-CST-007 | 같은 날짜·교사의 target 또는 record 2건 | PK·unique 위반 |
| DB-CST-008 | 같은 `(device_id, request_id)` event 2건 | unique 위반 |
| DB-CST-009 | 빈 값·허용 문자 밖·65자 request ID | CHECK 위반 |
| DB-CST-010 | DB UID가 소문자·비16진수·홀수·8자 미만·32자 초과 | CHECK 위반 |
| DB-CST-011 | DB에는 8~32자 짝수 대문자 UID 저장, API에는 4·7·10-byte만 입력 | DB의 이력 호환 범위와 외부 API allowlist를 각각 통과 |
| DB-CST-012 | record가 없는 target 또는 `is_target = FALSE`를 참조 | FK 없는 target은 DB 거부, 비활성 target은 service가 거부 |
| DB-CST-013 | `PRESENT`·`LATE`의 시각·band snapshot 누락 또는 `ABSENT`에 값 존재 | CHECK 위반 |
| DB-CST-014 | record band가 날짜 고정 정책 밖이거나 parent status와 불일치 | application transaction 거부, record 없음 |
| DB-CST-015 | 소속·assignment 종료 metadata의 처리자·시각·사유 일부만 입력 | all-or-none CHECK 위반 |
| DB-CST-016 | A부서 child를 B부서 day·device·membership에 연결 | 복합 FK 위반 |
| DB-CST-017 | 허용 목록 밖 tag event result code 또는 `SERVER_ERROR` 저장 | CHECK 위반 |
| DB-CST-018 | `PROCESSING` event에 HTTP/body 입력 또는 확정 event에 HTTP/body 누락 | CHECK 위반 |
| DB-CST-019 | audit actor를 0개·2개 이상 지정하거나 DEVICE actor의 부서 누락 | CHECK·복합 FK 위반 |
| DB-CST-020 | 같은 audit idempotency key 2건 | unique 위반 |
| DB-CST-021 | 부모 부서·계정·교사·카드·정책·날짜 물리 삭제 | `ON DELETE RESTRICT` 위반 |
| DB-CST-022 | `updated_at` 대상 행 update | trigger가 DB 시각으로 값을 전진시키고 PUBLIC 함수 실행 권한은 없음 |
| DB-CST-023 | 시험 version·시각 중 하나만 입력, 현재 credential version과 다른 시험 version, 발급 전 시험 시각 또는 시험 증거 없는 `ACTIVE` 상태 입력 | credential 시험 증거·활성화 CHECK 위반 |

### 10.2 Mapper

| ID | 검증 |
|---|---|
| DB-MAP-001 | 신규 Mapper에 `SELECT *` 없음 |
| DB-MAP-002 | 부서 범위 query가 `department_id`를 필수로 받음 |
| DB-MAP-003 | update/delete 영향 행 수를 검사함 |
| DB-MAP-004 | 관리자 query가 권한 없는 다른 부서의 존재 여부를 노출하지 않음 |
| DB-MAP-005 | 통계가 신규 `FINALIZED` 날짜만 사용함 |
| DB-MAP-006 | 미등록 카드함이 해당 부서 `UNKNOWN_UID` event만 조회함 |
| DB-MAP-007 | 레거시 세 테이블 DML Mapper가 신규 경로에 없음 |
| DB-MAP-008 | `member.card_uid`, age, birth를 신규 업무 query가 읽거나 수정하지 않음 |

---

## 11. Migration·데이터 안전 테스트

세부 절차는 `MIGRATION_PLAN.md`를 따르고 다음을 자동 또는 리허설 증적으로 남긴다.

| ID | 환경·작업 | 합격 기준 |
|---|---|---|
| MIG-FRESH-001 | 완전히 빈 DB에 V001~V008 적용 | baseline 행 없이 전체 목표 schema 생성 |
| MIG-FRESH-002 | 같은 migration 집합을 다시 실행하고 validate | 두 번째 schema·data 변경 없음 |
| MIG-SAFE-001 | `NEW_OR_SAMPLE` DB에 baseline 시도 | baseline 금지, history·schema 변경 없음 |
| MIG-SAFE-002 | 승인된 `LEGACY_OPERATIONAL` fixture | 사전조건 통과 후 명시적 version 0 `BASELINE` 정확히 한 행, PK·행·sequence 보존 |
| MIG-SAFE-003 | `UNKNOWN` 분류 DB | 삭제·baseline·migration·이관 없이 중단 |
| MIG-SAFE-004 | 기존 Flyway history가 있는 DB에서 새 baseline 시도 | 자동 추정하지 않고 중단, 기존 history 불변 |
| MIG-SAFE-005 | 신규 14개 테이블·`attend_set_updated_at()`·이름 충돌 객체 중 하나 선존재 | baseline·migration 전 무변경 실패 |
| MIG-SAFE-006 | 레거시 네 테이블 누락, 제3의 `member` 구조 또는 활성 writer 존재 | 사전점검 실패, DDL·data 변경 없음 |
| MIG-SAFE-007 | `baselineOnMigrate=true` 또는 version 0이 아닌 자동 baseline 설정 | 배포 설정 검사 실패 |
| MIG-RUNNER-001 | 실패 가능한 transactional migration | 전체 rollback, 성공 history 없음 |
| MIG-RUNNER-002 | 적용 파일 checksum 변경 | runner의 `flyway validate` 실패, 배포 중단 |
| MIG-RUNNER-003 | pending·out-of-order·repeatable checksum 불일치 | runner의 `info`·`validate` gate 실패 |
| MIG-RUNNER-004 | 운영 runner에서 `clean` 시도 | `cleanDisabled=true`로 거부 |
| MIG-RUNTIME-001 | history의 최신 성공 versioned non-baseline 행이 release manifest target과 일치 | runtime 기동 허용 |
| MIG-RUNTIME-002 | history 없음·실패 행 존재·성공 versioned 행 없음·target 불일치 각각 | runtime이 쓰기 받기 전에 fail fast |
| MIG-RUNTIME-003 | 설치 순서와 문자열 정렬이 다른 version fixture(예: `9`, `10`) | 문자열 `MAX(version)`이 아니라 최신 `installed_rank` 행으로 판정 |
| MIG-RUNTIME-004 | `migration_owner` credential을 웹 runtime에 설정 | 설정 검증 실패 |
| MIG-GRANT-001 | `app_runtime` DDL·history write·member DELETE·legacy DML | table·column·sequence·function 권한으로 거부 |
| MIG-RESTART-001 | 앱 두 번 재시작 | DROP·sample insert·row count 변화 없음 |
| MIG-IMPORT-001 | importer dry-run과 실제 실행 | manifest 승인 건수 일치, 거부 행 보고 |
| MIG-IMPORT-002 | 비정상 UID·중복 카드·공개 샘플 계정 | 이관 거부 |
| MIG-RESTORE-001 | 컷오버 백업을 별도 DB에 복원 | row·PK·sequence·role·table/column/sequence/function grant와 로그인 smoke 모두 일치 |
| MIG-CUTOVER-001 | point of no return 전 복귀 리허설 | 안전 릴리스와 제한된 `legacy_writer` 복구 |
| MIG-CUTOVER-002 | 첫 권위 check-in 이후 장애 | 단순 구버전 복귀 금지, forward fix 또는 승인된 전체 복원 절차 |

운영 dump를 사용하는 리허설은 격리 DB와 승인된 담당자만 사용하고 결과 보고서에서 개인정보를 마스킹한다.

---

## 12. 장치 API 계약 테스트

`device-api.yaml`을 executable contract의 기준으로 사용한다.

### 12.1 공통 입력

| ID | 상황 | 기대 결과 |
|---|---|---|
| API-IN-001 | `X-Device-Code`, `X-Device-Key` 누락 | JSON 인증 오류, redirect/HTML 없음 |
| API-IN-002 | 숫자 내부 device ID 또는 body department ID 시도 | 내부 ID 비노출, 부서 선택 불가 |
| API-IN-003 | 빈 body·깨진 JSON·trailing token | `400 MALFORMED_REQUEST`, event 없음 |
| API-IN-004 | JSON 외 Content-Type, 비 UTF-8 charset 또는 압축 body | `415 UNSUPPORTED_MEDIA_TYPE`, event 없음 |
| API-IN-005 | 유효 JSON을 실제 1024 bytes로 전송 | 크기 단계 통과 후 정상 업무 판정 |
| API-IN-006 | 1025 bytes를 정상·chunked·거짓/없는 Content-Length로 전송 | streaming read가 역직렬화 전 `413`, `tag_event_log`·`attendance_record`·시험 성공 필드 변경 없음; 선행 인증이 성공했다면 `last_seen_at` telemetry만 변경 가능 |
| API-IN-007 | UID 4·7·10-byte 대문자 16진수 | 각각 허용 |
| API-IN-008 | UID 소문자·구분자·홀수·4·7·10-byte 이외 길이 | `422 INVALID_REQUEST`, event 없음 |
| API-IN-009 | requestId 허용 문자·1자·64자 | 허용 |
| API-IN-010 | 빈 값·`.`, `:`, 공백, 65자 requestId | `422 INVALID_REQUEST`, event 없음 |
| API-IN-011 | 추가·중복 JSON member | 추가 member는 `422`, 중복 member는 `400`, event 없음 |
| API-IN-012 | 장치 인증 header를 중복 전송 | `401 DEVICE_UNAUTHORIZED`, 어느 header가 문제인지 비노출 |

### 12.2 인증과 장치 상태

| ID | endpoint·상태 | 기대 결과 |
|---|---|---|
| API-AUTH-001 | check-in + `ACTIVE` | 인증 후 업무 처리 |
| API-AUTH-002 | 유효한 key의 check-in + `INACTIVE`/`REVOKED` | `409 DEVICE_NOT_ACTIVE`, attendance/event 없음 |
| API-AUTH-003 | credential test + `INACTIVE` | `200 CREDENTIAL_VALID`, 현재 시험 version·시각 원자 기록, attendance/tag event/audit 없음 |
| API-AUTH-004 | credential test + `ACTIVE`/`REVOKED` | `409 CREDENTIAL_TEST_NOT_ALLOWED` |
| API-AUTH-005 | 누락·중복·잘못된 code/key | `401 DEVICE_UNAUTHORIZED`, 원문·hash·장치 존재 여부 비노출 |
| API-AUTH-006 | 인증 뒤 key 교체·상태 변경 후 check-in transaction 진입 | `409 DEVICE_STATE_CHANGED`, 선점 event까지 rollback |
| API-AUTH-007 | `device-api.enabled=false` | 인증·`last_seen_at` 전 503, DB write 없음 |
| API-AUTH-008 | rate limit 초과 | 명세의 429·`Retry-After`, 출석 중복 없음 |
| API-AUTH-009 | credential test에 body 전송 | `400 UNEXPECTED_BODY`, 시험 성공 필드·업무 데이터 변경 없음; 선행 인증의 `last_seen_at`만 변경 가능 |
| API-AUTH-010 | 올바른 key 인증 뒤 check-in 업무 실패 | 별도 auth transaction의 `last_seen_at`은 유지되지만 시험 성공 필드는 변경되지 않음 |
| API-AUTH-011 | 잘못된 key 또는 flag 선행 거부 | `last_seen_at`·시험 성공 필드 모두 미갱신 |

### 12.3 장치 credential 수명주기

| ID | 작업·경합 | 기대 결과 |
|---|---|---|
| DEV-LIFE-001 | 장치 신규 등록 | 고엔트로피 key 원문 1회 표시, hash만 저장, 상태 `INACTIVE` |
| DEV-LIFE-002 | `ACTIVE → INACTIVE` | 즉시 check-in 차단, 시험 version·시각 초기화, 과거 event·record 보존 |
| DEV-LIFE-003 | key 교체 중 한 단계 강제 실패 | `INACTIVE` 전이·새 hash·version 증가·발급 시각·시험 필드 초기화 모두 commit 또는 rollback |
| DEV-LIFE-004 | key 교체 commit 후 구 key 사용 | 즉시 `401`, `last_seen_at`·event 변경 없음 |
| DEV-LIFE-005 | 최근 `last_seen_at`만 있거나 이전 version 시험 뒤 활성화 요청 | 거부; 현재 version의 시험 version·시각이 원자 기록된 뒤에만 `ACTIVE` 허용 |
| DEV-LIFE-006 | `REVOKED` 재활성화·key 재발급·부서 변경 | 모두 거부, 종결 상태 유지 |
| DEV-LIFE-007 | 생성·비활성화·활성화·교체·폐기 | actor·사유·version·상태 audit 존재, key/hash 없음 |
| DEV-LIFE-008 | 인증 성공 뒤 업무 실패와 인증 실패 비교 | 전자는 `last_seen_at` 유지, 후자는 갱신 없음 |
| DEV-LIFE-009 | credential-test와 key 교체 경합 | test가 먼저면 교체가 증거를 초기화하고, 교체가 먼저면 구 version test 갱신 0행·`409 CREDENTIAL_TEST_NOT_ALLOWED` |
| DEV-LIFE-010 | 일반 check-in 인증 성공 또는 credential-test 업무 실패 | `last_seen_at`은 변경될 수 있지만 시험 version·시각은 변경되지 않아 활성화 근거가 되지 않음 |
| DEV-LIFE-011 | credential-test와 활성화·폐기 상태 변경 경합 | test의 장치 잠금 시 상태가 `INACTIVE`일 때만 200과 시험 증거 갱신; 상태 변경이 먼저면 409와 시험 증거 불변 |

### 12.4 응답과 재시도

| HTTP | 대표 code | 자동 재시도 | 새 tag event |
|---:|---|---|---|
| 200 | `ALREADY_CHECKED_IN`, `CREDENTIAL_VALID` | 아니오 | check-in 결과만 기존/확정 event |
| 201 | `CHECKED_IN`, `LATE` | 아니오 | 예 |
| 400 | `MALFORMED_REQUEST`, `UNEXPECTED_BODY` | 아니오 | 아니오 |
| 401 | `DEVICE_UNAUTHORIZED` | 아니오 | 아니오 |
| 404 | `UNKNOWN_UID` | 아니오 | 예 |
| 409 | 카드·소속·날짜·대상자·시간 업무 실패 | 아니오 | 명세에 표시된 결정적 실패만 예 |
| 409 | `REQUEST_ID_CONFLICT`, `DEVICE_NOT_ACTIVE`, `DEVICE_STATE_CHANGED`, `CREDENTIAL_TEST_NOT_ALLOWED` | 아니오 | 아니오 |
| 413 | `PAYLOAD_TOO_LARGE` | 아니오 | 아니오 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | 아니오 | 아니오 |
| 422 | `INVALID_REQUEST` | 아니오 | 아니오 |
| 429 | `RATE_LIMITED` | `Retry-After` 뒤 같은 UID/requestId | 아니오 |
| 500 | `SERVER_ERROR` | 2초·5초·15초, 최대 3회 | rollback |
| 503 | `SERVICE_UNAVAILABLE` | `Retry-After` 뒤 같은 UID/requestId | flag·overload는 없음, dependency transaction은 rollback |

- 모든 status와 JSON code 조합이 OpenAPI response에 정의되어야 한다.
- 성공·업무 실패 응답은 공통 envelope와 필수 `requestId`, `serverTime` 규칙을 지켜야 한다.
- 인증·body 파싱처럼 requestId를 신뢰할 수 없는 단계는 명세의 nullable/생략 규칙을 지켜야 한다.
- 연락처, 다른 부서, 내부 DB ID, credential hash와 stack trace를 반환하지 않는다.
- retryable 응답은 같은 requestId·UID를 사용하며 `Retry-After`가 있으면 따른다.
- 결정적 4xx와 성공·중복 결과는 자동 재시도하지 않는다.
- `SERVER_ERROR`를 확정 event로 저장하지 않고 복구 후 같은 ID가 정상 처리될 수 있어야 한다.
- OpenAPI examples도 schema validator를 통과해야 한다.

| ID | 재시도 검증 | 기대 결과 |
|---|---|---|
| API-RETRY-001 | 응답 없음 또는 `500` | 최초 전송과 같은 UID/requestId로 2초·5초·15초 후 자동 재전송 최대 3회 |
| API-RETRY-002 | `429`·`503`과 `Retry-After: 1`, `300` | 지정 초 이상 기다리고 최초 전송 이후 자동 재전송 최대 3회 |
| API-RETRY-003 | `Retry-After` 누락·0·301·HTTP-date | contract 위반으로 기록하고 무제한 즉시 재시도하지 않음 |
| API-RETRY-004 | `200`·`201`·결정적 `4xx` | 자동 재시도 0회 |
| API-RETRY-005 | 재시도 중 최초 확정 응답 수신 | 즉시 중단, 동일 event/record 외 추가 부작용 없음 |

초기 rate limit 값도 독립된 contract test로 고정한다.

| ID | bucket 검증 | 기대 결과 |
|---|---|---|
| API-RATE-001 | 한 effective source의 인증 전 burst 20회와 21번째 | 20회까지 인증 단계 처리, 21번째 `429`; 초당 1 token 회복 |
| API-RATE-002 | 서로 다른 source와 위조 `Forwarded`·`X-Forwarded-For` | bucket 독립, 비신뢰 proxy header는 무시 |
| API-RATE-003 | 한 인증 check-in 장치의 burst 10회와 11번째 | 10회 처리, 11번째 `429`; 초당 1 token 회복 |
| API-RATE-004 | 한 credential-test 장치의 burst 2회와 3번째 | 2회 처리, 3번째 `429`; 20초당 1 token 회복 |
| API-RATE-005 | 서로 다른 인증 장치 | 장치별 bucket 독립 |
| API-RATE-006 | 모든 429 | 정수 `Retry-After` 1~300, 초과 요청의 event·attendance 없음 |

### 12.5 수신 시각

| ID | 시각 검증 | 기대 결과 |
|---|---|---|
| API-TIME-001 | 느린 request body를 두 구간 경계에 걸쳐 전송 | 인증과 전체 body 검증이 끝난 직후 application service 호출 전에 캡처한 한 `receivedAt`만 업무 날짜, 구간, `checkedInAt`, event 시각과 응답 `serverTime`에 사용 |

---

## 13. 보안 테스트

상세 기대값은 `SECURITY_MATRIX.md`를 기준으로 한다.

### 13.1 관리자 웹

| 보안 명세 ID | 이 계획에서 검증할 범위 |
|---|---|
| `SEC-CHAIN-01~06` | 두 filter chain의 순서·stateless 경계·CSRF 범위·상호 인증 차단 |
| `SEC-WEB-01~12` | 미인증·CSRF·GET command·역할 분리·권한 회수·정렬 주입·write flag |
| `SEC-AUTH-01~13` | 동일 로그인 실패·두 rate bucket·session fixation·logout·두 만료 경계와 안전한 bootstrap·최초 설정·reset gate |
| `SEC-IDOR-DEPARTMENT-01` | 다른 부서 namespace와 목록 진입 |
| `SEC-IDOR-TEACHER-01`, `SEC-IDOR-CARD-01`, `SEC-IDOR-INBOX-01` | 교사·카드·등록함의 실제 다른 부서 ID |
| `SEC-IDOR-POLICY-01`, `SEC-IDOR-DAY-01`, `SEC-IDOR-RECORD-01` | 정책·날짜·대상자·기록 조회와 command |
| `SEC-IDOR-STATISTICS-01`, `SEC-IDOR-DEVICE-01`, `SEC-IDOR-HISTORY-01` | 집계·장치·감사·태깅 이력의 간접 누출 |
| `SEC-IDOR-QUERY-01`, `SEC-IDOR-BODY-01`, `SEC-IDOR-CHILD-01` | query·hidden/body·부모/자식 혼합 변조 |

### 13.2 application·Mapper·DB 다층 방어

| 보안 명세 ID | 검증 |
|---|---|
| `SEC-IDOR-SERVICE-01` | Controller를 우회해 D1 principal로 D2 command 직접 호출 |
| `SEC-IDOR-MAPPER-01` | service를 우회해 D1 scope와 D2 ID로 Mapper 직접 호출 |
| `SEC-IDOR-DB-01` | 교차 부서 복합 FK 직접 위반 |
| `SEC-DB-01~12` | DDL·history·member DELETE·레거시 DML·고정 device 부서·append-only log·운영 역할 회수 |
| `DB-MAP-001~008` | 명시적 컬럼, 부서 scope, 영향 행 수와 legacy·개인정보 컬럼 격리 |

### 13.3 민감정보

`SEC-LOG-01~10`을 그대로 자동화해 비밀번호·DB credential·device key/hash·전체 UID·불필요한 연락처가 log, HTML, JSON, audit와 backup 증적에 노출되지 않는지 검사한다. 보안 header, cookie, cache, redirect와 CORS는 `SEC-WEB-13~14`를 실행한다. 실패·rate limit·다른 부서 거부는 존재 여부를 드러내는 message·timing 차이를 허용하지 않는다.

---

## 14. 관리자 UI 테스트

상세 필드와 상태는 `ADMIN_UI_SPEC.md`를 기준으로 한다.

### 14.1 공통

- 성공 POST는 redirect 후 새로고침해도 중복 저장되지 않는 PRG
- validation 오류는 입력값·field error를 유지하고 비밀값은 재표시하지 않음
- 서버 권한 실패와 없는 리소스를 적절한 403/404 화면으로 구분하되 다른 부서 존재 정보는 숨김
- empty, no-result, validation, conflict, server error 상태가 정의됨
- 동작 중 버튼 이중 제출 방지와 서버 멱등/unique 보장이 함께 존재
- flash message가 성공을 가장하지 않음

### 14.2 핵심 화면

| ID | 화면 | 핵심 검증 |
|---|---|---|
| UI-001 | 로그인·비밀번호 | 오류, 잠금/제한, logout, session |
| UI-002 | 부서·계정·권한 | 세 작업 분리, 유효한 중간 상태, 역할 회수 |
| UI-003 | 대시보드 | 동적 지각 단계, 미출석/결석 구분, 최근 event |
| UI-004 | 교사 목록·상세 | 부서 scope, 활성 소속, 카드·출석 이력 |
| UI-005 | 교사 추가·카드 연결 | 미등록 카드 선택, 전체 rollback 오류 |
| UI-006 | 카드 등록함 | 자기 부서 `UNKNOWN_UID`, 다른 부서 정보 비노출 |
| UI-007 | 카드 교체·해제·분실·폐기 | 사유, 상태 전이, 확인 문구 |
| UI-008 | 부서 제외 | 카드 disposition, 미래 대상 선택, 과거 보존 경고 |
| UI-009 | 정책 편집·발행 | 동적 구간 추가·삭제·순서·경계 미리보기 |
| UI-010 | 출석 날짜 | 발행 정책, snapshot, 시작 후 변경 차단 |
| UI-011 | 수동 등록·정정 | 실제 시각, 서버 계산 preview, 사유, 누락자 원자 추가 |
| UI-012 | 장치 관리 | 고정 부서, 키 1회 표시, INACTIVE test, ACTIVE 전환, REVOKED |
| UI-013 | 감사·운영 | actor, reason, masked diff, event와 audit 분리 |

### 14.3 접근성·반응형

- 키보드만으로 form·dialog·동적 구간을 조작
- label, error summary와 field error의 프로그램적 연결
- focus 이동과 dialog focus trap/복귀
- 색상만으로 상태를 구분하지 않고 텍스트·아이콘 병행
- 200% 확대와 모바일 폭에서 수평 조작 손실 없음
- 표는 작은 화면에서 카드형 또는 안전한 가로 스크롤 제공
- 최신 Chrome, Edge와 주요 모바일 브라우저의 핵심 흐름 확인

---

## 15. Arduino·네트워크 통합 테스트

| ID | 상황 | 합격 기준 |
|---|---|---|
| FW-001 | 실제 MFRC522 UID | 바이트별 대문자 16진수, 구분자 없음 |
| FW-002 | HTTP 요청 | 정확한 path, header, JSON과 Content-Length |
| FW-003 | 성공·지각·중복 | HTTP status + JSON code를 모두 확인한 신호 |
| FW-004 | 업무 4xx | 성공 신호 없음, 정의된 오류 신호 |
| FW-005 | 연결·읽기 timeout | 연결 종료, 성공 신호 없음 |
| FW-006 | 응답 없음·retryable 오류 | 같은 requestId·UID로 제한 재시도 |
| FW-007 | 결정적 4xx | 자동 재시도 없음 |
| FW-008 | 전체 응답이 여러 packet으로 분할 | body를 끝까지 읽고 JSON 해석 |
| FW-009 | 재부팅 | 새 boot random 값 + counter로 ID 충돌 방지 |
| FW-010 | 공유망 HTTPS | 서버 인증서 또는 승인 CA 검증 |
| FW-011 | 격리망 HTTP | 외부 접근 차단과 물리·네트워크 경계 확인 |
| FW-012 | credential 교체 | 이전 키 즉시 실패, 새 키 test 후 check-in 성공 |

LED·부저 패턴이 확정되기 전에는 code별 기대 신호를 `TBD`로 숨기지 않고 파일럿 차단 조건으로 관리한다.

---

## 16. 비기능·운영 테스트

### 16.1 성능

| ID | 측정 | 합격 기준 |
|---|---|---|
| PERF-001 | 운영 유사망, 1초 간격 50회 실제 태깅 | p95 2초 이내, 전체 5초 이내 |
| PERF-002 | 부서별 교사 20명·여러 날짜 관리자 조회 | 3초 이내 |
| PERF-003 | 동시 중복·마감 경합 | timeout 없이 정확한 최종 결과 |

태깅 시간은 카드 인식부터 장치 신호 표시까지 측정한다. 서버·네트워크 장애 응답은 성공 latency에서 제외하고 실패 처리 시간으로 별도 기록한다.

### 16.2 복구·백업

| ID | 검증 | 합격 기준 |
|---|---|---|
| OPS-001 | 하루 1회 백업 | 성공 시각·파일·hash 확인 |
| OPS-002 | 자동 마감 직후 백업 | 해당 날짜 결과 포함 |
| OPS-003 | 별도 DB 복원 | 무결성·row count·로그인 읽기 smoke 통과 |
| OPS-004 | 파일럿 전/운영 분기 복원 | 증적과 담당자 기록 |
| OPS-005 | 장애 복구 시간 | RTO 4시간 이내 |
| OPS-006 | 복구 시점 | 24시간 초과 데이터 손실 없음, 사이 기록 수기 대사 |

### 16.3 feature flag와 관측성

- 세 flag 기본값 `false`
- 변경은 환경 설정 + controlled restart만 허용
- `device-api=false`에서 auth·`last_seen_at`·event write 0건
- `admin-write=false`에서 화면 숨김뿐 아니라 command 거부
- `scheduler=false`에서 bean 실행 없음
- schema check 실패 시 write를 받지 않고 기동 실패
- health, 최근 장치 auth, 인증 실패, 결과별 event, 과거 미마감 날짜, 마지막 마감과 백업 상태 확인
- health/Actuator 외부 무인증 노출 금지

---

## 17. End-to-End 시나리오

### E2E-01 초기 설정

```text
시스템 관리자 생성
→ 부서 생성
→ 계정 생성
→ 부서 관리자 권한 부여
→ INACTIVE 장치와 키 생성
→ credential test
→ ACTIVE 전환
```

각 단계는 독립 transaction이며 아직 관리자 없는 부서와 아직 부서 권한 없는 계정이 유효한 중간 상태여야 한다.

### E2E-02 정상 출석일

```text
교사·소속·카드 등록
→ 정책 발행
→ 오늘 날짜와 대상자 snapshot
→ 정상·1차 지각·2차 지각 태깅
→ 중복·미등록·오류 확인
→ 다음 날 자동 마감
→ 통계·감사·event 검증
```

### E2E-03 카드 교체와 부서 제외

```text
기존 카드 출석 확인
→ 사유 있는 카드 교체
→ 기존 카드 거부·새 카드 성공
→ 교사 부서 제외와 카드 disposition
→ 미래 기본 명단 제외
→ 과거 출석·연결 이력 보존
```

### E2E-04 사후 수동 등록

```text
대상자 누락 상태로 날짜 경과·마감
→ 실제 출석 시각과 사유 입력
→ 소속 기간·고정 정책 검증
→ target + MANUAL record 원자 생성
→ 통계 재계산·audit 확인
```

### E2E-05 장애와 재기동

```text
check-in 중 DB 실패
→ 성공 신호 없음·부분 행 없음
→ 같은 requestId 재시도
→ 서버 중지 상태로 날짜 경과
→ 재기동 catch-up
→ 백업·별도 DB 복원
```

### 파일럿 인수

| ID | 범위 | 합격 기준 |
|---|---|---|
| PILOT-001 | 실제 2개 이상 부서, 각 5~20명 규모에서 실제 장치로 최소 4회 출석일 운영 | E2E-01~05의 해당 흐름을 포함하고 복구 불가능한 데이터 유실·잘못된 교사 연결 0건, 회차별 대사·장애·수기 보완 증적 보존 |

---

## 18. 인수 기준 추적성

| 인수 기준 | 주 검증 ID |
|---|---|
| AC-01 | `E2E-01`, `UI-002`, `SEC-WEB-06`, `SEC-WEB-15`, `SEC-IDOR-DEPARTMENT-01`, `SEC-IDOR-TEACHER-01`, `SEC-IDOR-CARD-01`, `SEC-IDOR-POLICY-01`, `SEC-IDOR-DAY-01`, `SEC-IDOR-RECORD-01`, `SEC-IDOR-STATISTICS-01`, `SEC-IDOR-HISTORY-01` |
| AC-02 | `DOM-POL-001`, `UI-009`, `E2E-02` |
| AC-03 | `DOM-POL-002~007`, `UI-009` |
| AC-04 | `DOM-POL-009`, `DOM-MAN-015`, `CHK-001~002` |
| AC-05 | `DOM-POL-010~011`, `DOM-DAY-006`, `UI-009~010` |
| AC-06 | `DOM-DAY-002~003`, `DOM-DAY-007~008`, `CHK-013` |
| AC-07 | `DOM-DAY-001`, `DOM-DAY-005~006`, `DOM-DAY-009`, `DOM-MAN-007` |
| AC-08 | `CHK-001~002`, `API-IN-007`, `API-AUTH-001`, `API-TIME-001` |
| AC-09 | `IDEM-005`, `CON-001` |
| AC-10 | `IDEM-001~003` |
| AC-11 | `CHK-003~005`, `SEC-DEV-12`, `SEC-DEV-27` |
| AC-12 | `CHK-006`, `CHK-011~014`, `SEC-DEV-25~27` |
| AC-13 | `DOM-POL-008`, `CHK-007` |
| AC-14 | `API-AUTH-002`, `API-AUTH-005~006`, `SEC-DEV-01`, `SEC-DEV-10~12`, `SEC-IDOR-DEVICE-01` |
| AC-15 | `CHK-008~009`, `FW-004~006`, `API-RETRY-001~005` |
| AC-16 | `FIN-001~004`, `FIN-009`, `FIN-011~012` |
| AC-17 | `FIN-006`, `CON-002`, `CON-008`, `SEC-SYS-04` |
| AC-18 | `FIN-005` |
| AC-19 | `FIN-008`, `E2E-05` |
| AC-20 | `FIN-010`, `DOM-MAN-010`, `DOM-MAN-016`, `DOM-MAN-018`, `DOM-STAT-005` |
| AC-21 | `DOM-STAT-001~009`, `DB-MAP-005` |
| AC-22 | `CARD-002`, `CARD-ATOM-003~004`, `CON-003`, `E2E-03` |
| AC-23 | `MIG-FRESH-002`, `MIG-RESTART-001`, `MIG-RESTORE-001` |
| AC-24 | `PERF-001` |
| AC-25 | `PILOT-001`, `E2E-01~05` |
| AC-26 | `CARD-001`, `CARD-017`, `CARD-ATOM-001~002`, `CARD-ATOM-006`, `UI-005` |
| AC-27 | `ROSTER-001~005`, `CARD-003~005`, `CARD-ATOM-005`, `SEC-IDOR-TEACHER-01`, `SEC-IDOR-CARD-01` |
| AC-28 | `API-AUTH-002~004`, `DEV-LIFE-001~011`, `DB-CST-023`, `SEC-DEV-04~10`, `UI-012` |
| AC-29 | `DOM-MAN-004~009`, `DOM-MAN-014~019`, `CON-013~014`, `E2E-04` |
| AC-30 | `CARD-010~011`, `DB-CST-015`, `SEC-IDOR-BODY-01` |
| AC-31 | `IDEM-007`, `API-IN-009~010`, `DB-CST-009`, `DB-CST-017~018` |
| AC-32 | `CARD-001~008`, `CARD-015~016`, `CARD-ATOM-001~006`, `CON-015` |
| AC-33 | `CHK-010`, `IDEM-001`, `IDEM-005`, `SEC-LOG-05` |
| AC-34 | `E2E-01`, `UI-002`, `UI-012`, `SEC-DEV-21`, `SEC-DB-05` |

모든 AC는 최소 하나의 자동 테스트 또는 명시적인 현장 인수 시험에 연결되어야 한다. 구현 중 AC가 추가되면 같은 변경에서 이 표와 테스트 ID를 함께 추가한다.

---

## 19. 자동화 실행 순서

```text
정적 검사
→ domain unit
→ OpenAPI schema·example 검사
→ Testcontainers + Flyway Mapper/application/security
→ concurrency
→ 빈 DB·baseline migration
→ 애플리케이션 package 검사
→ staging contract·browser
→ 실제 firmware·성능·복원
→ 파일럿
```

### 19.1 Pull request 필수

- compile·unit
- OpenAPI parse와 schema/example 검사
- Testcontainers integration·security
- 신규 migration 빈 DB 적용과 validate
- 문서 내부 링크·ID 중복·추적성 검사

### 19.2 배포 전 필수

- 전체 migration rehearsal
- concurrency·contract 회귀
- 운영 artifact에 `schema.sql`, `data.sql`, 실제 secret이 없는지 검사
- 백업 복원
- 운영 유사 네트워크의 실제 장치 태깅
- feature flag와 rollback runbook 리허설

실패 테스트를 재실행해 우연히 통과시키지 않는다. flaky test는 원인을 제거하거나 격리된 알려진 문제로 승인받기 전까지 필수 gate를 통과한 것으로 보지 않는다.

---

## 20. 결함·증적·완료 조건

### 20.1 결함 기록

각 결함은 다음을 포함한다.

- test ID와 요구사항 ID
- commit·artifact·schema target
- 환경과 고정 시각
- 재현 단계
- 기대값과 실제값
- 관련 행의 비식별 snapshot
- application correlation ID
- 심각도와 임시 대응

### 20.2 증적

- CI 결과와 test report
- migration `info`·`validate` 결과
- concurrency 최종 row query
- OpenAPI validation 결과
- 브라우저·접근성 점검표
- 실제 장치 요청·응답의 비밀값 제거본
- 성능 측정 원본
- 백업 hash와 복원 시험 보고서
- 파일럿 회차별 운영·장애·수기 대사 기록

### 20.3 MVP 출시 완료 조건

- P0·P1 open defect 0건
- AC-01~AC-34 합격
- 필수 자동 테스트 100% 통과
- 다른 부서 IDOR 부정 시험 전부 통과
- 동시성·rollback·마이그레이션·복원 시험 통과
- 실제 Arduino와 2개 이상 부서 구성으로 E2E 통과
- 5~20명 규모 최소 4회 파일럿에서 복구 불가능한 데이터 유실과 잘못된 교사 연결 0건
- 남은 P2·P3는 영향·우회·담당자·기한이 기록됨

현재 조사 환경에는 Java Runtime이 없어 이 문서 작성 시 Gradle 테스트를 실행하지 못했다. 문서 완료는 구현 또는 테스트 통과를 의미하지 않는다.
