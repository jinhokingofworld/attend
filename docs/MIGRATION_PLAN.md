# Attend 데이터베이스 전환 및 마이그레이션 계획

> 기준 문서: [PROJECT_DEFINITION.md](./PROJECT_DEFINITION.md), [DATABASE_DESIGN.md](./DATABASE_DESIGN.md)
> 대상 단계: M1 개발 기반 안전화
> 대상 환경: Spring Boot 3.5.9, PostgreSQL
> 작성 기준일: 2026-07-31
> 상태: 구현 기준 확정, 운영 데이터 이관은 사전 승인 필요

## 0. 결론

MVP의 데이터베이스 변경 도구는 **Flyway**로 통일한다.

현재 애플리케이션은 시작할 때 기존 테이블을 삭제하고 샘플 데이터를 다시 넣을 수 있다. 따라서 새 기능 구현보다 먼저 파괴적 초기화를 제거한 **안전 릴리스**를 만들어야 한다.

전환 정책은 다음과 같다.

1. `schema.sql`, `data.sql`을 운영 시작 과정에서 실행하지 않는다.
2. DB 구조 변경은 버전이 지정된 Flyway 마이그레이션만 사용한다.
3. 스키마 마이그레이션과 환경별 개인정보 이관을 분리한다.
4. 현재 저장소의 샘플 데이터는 새 DB로 이관하지 않는다.
5. 실제 운영 DB가 존재하면 기존 `member` PK를 유지하고 승인된 구성원만 활성 소속으로 전환하며, 검증된 카드와 승인 계정만 선별 이관한다.
6. 기존 데이터만으로는 과거 출석 대상자 명단을 복원할 수 없으므로, 과거 출석은 기본적으로 읽기 전용 레거시 이력으로 보존한다.
7. 신규 공식 출석률은 컷오버 날짜부터 계산한다.
8. 초기 전환에서는 기존 `member`를 안전하게 확장하고 나머지 세 레거시 테이블을 삭제하거나 이름을 바꾸지 않는다.
9. 운영 DB의 자동 baseline, Flyway `clean`, 적용된 마이그레이션 파일 수정은 금지한다.

이 문서는 SQL을 바로 실행하는 작업 지시서가 아니다. 실제 운영 DB의 성격, 백업 복원 가능 여부와 승인된 이관 목록을 확인한 뒤 실행하는 전환 기준이다.

---

## 1. 목적과 범위

### 1.1 목적

- 애플리케이션 재시작으로 운영 데이터가 삭제되는 위험을 제거한다.
- 기존 `member` 1개를 채택하고 명시된 신규 애플리케이션 테이블을 재현 가능하고 검증 가능한 순서로 생성한다.
- 레거시 데이터의 의미를 왜곡하지 않고 보존한다.
- 신규 애플리케이션 전환 실패 시 복구 가능한 지점을 만든다.
- 개발, 테스트, 운영 DB가 같은 초기화 방식을 공유하지 않게 한다.

### 1.2 포함 범위

- 파괴적 SQL 초기화 제거
- Flyway 도입 및 마이그레이션 버전 구성
- 신규·샘플 DB와 실제 운영 DB의 전환 경로 분리
- 기존 구성원, 계정, NFC 카드의 선별 전환 기준
- 백업, 사전점검, dry-run, 컷오버, 검증, 복구 절차
- 공식 통계 시작점과 과거 이력 처리 기준

### 1.3 제외 범위

- 관리자 화면과 장치 API의 상세 구현
- 근거 자료가 없는 과거 결석과 출석률 복원
- 레거시 테이블의 즉시 삭제
- Flyway를 이용한 환경별 실제 계정·개인정보 seed
- 컷오버 후 신규 데이터를 구버전 스키마로 되돌리는 역이관 도구

---

## 2. 착수 시 확인된 위험과 안전 릴리스 조치

| 근거 | 착수 시 상태 | 위험 | 구현 조치 |
|---|---|---|---|
| 기존 `src/main/resources/schema.sql` | 시작 SQL에 네 테이블과 enum의 `DROP` 포함 | 앱 재시작만으로 전체 데이터 삭제 가능 | 운영 classpath에서 삭제하고 비파괴 Flyway V001~V011으로 교체 |
| `src/main/resources/application.properties` | SQL 초기화를 환경변수로 재활성화할 수 있었음 | 오설정 시 파괴적 SQL 실행 가능 | `spring.sql.init.mode=never`를 고정하고 파괴적 SQL resource 제거 |
| `src/main/resources/application.properties` | `DB_URL`이 없으면 localhost로 fallback | 운영 설정 누락을 숨기고 의도하지 않은 DB에 접속 가능 | 개발 fallback은 유지하되 `prod` 프로필은 URL·계정·비밀번호를 필수값으로 재정의 |
| 기존 `src/main/resources/data.sql` | 공개된 샘플 계정·비밀번호 해시와 잘못된 UID 포함 | 알려진 자격증명과 사용할 수 없는 카드가 생성될 수 있음 | 운영 classpath에서 삭제하고 migration seed 금지 |
| 기존 `attendance` | 부서, 정책 버전, 출석일, 대상자 스냅샷 없음 | 과거 결석과 통계 분모를 복원할 수 없음 | 기본적으로 레거시 이력으로 보존 |
| 기존 시간 컬럼 | `timestamp without time zone` | 저장 당시 시간대가 데이터에 없음 | 승인 전 임의 시간대 변환 금지 |
| 기존 외래 키 | 구성원 삭제 시 출석·로그 `ON DELETE CASCADE` | 이미 삭제된 과거 이력은 복구 불가 | 기존 FK를 `RESTRICT`로 교체하고 종료 이력 사용 |
| 기존 `MemberMapper.xml`, `MemberController` | `DELETE FROM member`와 `member.card_uid` 직접 수정 경로 존재 | 기준 구성원·과거 이력 손실과 카드 이력 우회 | 물리 삭제 API·Mapper와 카드 UID 직접 수정 필드를 제거 |
| 기존 상태 | PostgreSQL enum `IN_TIME`, `TIME_OUT`, `MISS` | 다단계 지각 정책을 표현할 수 없음 | 신규 구조는 정책 구간 행과 `VARCHAR + CHECK` 사용 |
| 기존 `attendance_log` | 장치, 부서, 요청 ID와 응답 원문 없음 | 신규 장치 이벤트로 신뢰성 있게 변환 불가 | 기존 로그를 읽기 전용 보존 |

제거된 `data.sql`이 샘플이었다는 사실과 별개로, 이미 배포된 DB가 샘플 DB인지 실제 운영 DB인지는 저장소만 보고 판단할 수 없다. 운영자가 명시적으로 분류하기 전에는 어떤 데이터도 삭제하거나 이관하지 않는다.

위 안전화는 PostgreSQL 15 Testcontainers에서 fresh·레거시·거부 경로를 통과했지만 실제 운영 배포 증거는 아니다. 운영 복제본, 권한 분리, 백업 복원과 재시작 검증을 별도로 통과해야 한다.

---

## 3. 데이터 의미에 관한 제한

### 3.1 과거 결석을 자동 복원할 수 없는 이유

신규 모델에서 결석은 다음 조건으로만 확정된다.

```text
마감된 출석 날짜
AND 그 날짜의 출석 대상자
AND 출석 기록 없음
= 결석
```

기존 DB에는 날짜별 대상자 명단이 없다. 따라서 기존 `attendance`에 행이 없는 경우는 다음 중 무엇인지 구분할 수 없다.

- 출석 대상이었지만 결석함
- 그 날짜에는 출석 대상이 아니었음
- 출석 날짜 자체가 등록되지 않았음
- 기록이 삭제되었거나 저장에 실패함

그러므로 **행 없음은 `ABSENT`가 아니라 `UNKNOWN`**이다. 현재 교사 전체를 과거 날짜의 대상자로 만들어 결석을 채우는 행위는 데이터 복원이 아니라 데이터 조작에 해당한다.

### 3.2 이관 가능성과 불가능성

| 데이터 | 기본 처리 | 조건 |
|---|---|---|
| 기존 `member` | PK를 유지해 기준 구성원으로 채택 | 부서 매핑과 운영자 승인 필요 |
| 연락처 | 기존 `member`에 원문 보존, 신규 화면 노출은 선택 | 빈 문자열은 원본 백업·승인·정정 기록 후 `NULL` 정규화 가능 |
| 나이·생년월일 | 유효한 기존 `birth`는 정확한 날짜로 보존하고 만 나이·생일 관리에 사용; 정수 `age`는 원본 호환용으로만 보존 | 신규 등록·활성 교사 기본정보 수정·활성화에는 미래가 아닌 정확한 `birth` 필수. 레거시 `birth IS NULL`은 확인된 날짜로 정정하기 전까지 생년월일·파생 나이 미상이며 `age`로 날짜를 역산하지 않음 |
| NFC UID | 선별 이관 가능 | 형식·길이·중복 검증 및 카드 소유자 확인 필요 |
| 로그인 계정 | 선별 이관 가능 | 샘플 계정 제외, 역할 재승인과 비밀번호 재설정 필요 |
| 명시적 `IN_TIME` | 레거시 이력으로만 보존 | 신규 정책 구간을 증명할 수 없음 |
| 명시적 `TIME_OUT` | 레거시 이력으로만 보존 | 지각 단계를 증명할 수 없음 |
| 명시적 `MISS` | 레거시 이력으로만 보존 | 완전한 과거 대상자 명단이 없음 |
| 누락된 출석 행 | 이관 불가 | 결석으로 합성 금지 |
| `attendance_log` | 읽기 전용 보존 | 장치 provenance가 없어 신규 이벤트로 위장 금지 |
| 과거 공식 출석률 | 복원하지 않음 | MVP 신규 통계와 분리 |

### 3.3 공식 통계 경계

- 신규 공식 출석률의 시작일은 **운영 컷오버 날짜**다.
- 컷오버 이전 레거시 기록을 화면에 제공할 경우 `레거시 참고 이력`으로 구분한다.
- 레거시 참고 이력은 신규 공식 출석률의 분자나 분모에 포함하지 않는다.
- 레거시 출석은 신규 `attendance_day`, `attendance_target`, `attendance_record`에 넣지 않는다.
- 신규 시스템은 과거 `attendance_day`의 등록을 거부하므로 모든 신규 `FINALIZED` 날짜를 공식 통계에 사용할 수 있다.
- 과거 출석 통합은 현재 MVP의 이관 옵션이 아니다. 필요해지면 과거 부서·정책·대상자별 최종 상태를 표현하는 별도 모델부터 설계한다.

---

## 4. 전환 경로 결정

### 4.1 소스 DB 분류

| 분류 | 판정 기준 | 전환 방식 |
|---|---|---|
| `NEW_OR_SAMPLE` | 저장소 샘플만 있거나 실제 운영에 사용하지 않은 DB | 빈 DB 생성 후 Flyway 적용, 기존 데이터 이관 없음 |
| `LEGACY_OPERATIONAL` | 실제 구성원·카드·출석 업무에 사용한 DB | 기존 DB 보존, 명시적 baseline 0, `member` 채택, 신규 테이블 생성, 승인된 마스터 데이터만 전환 |
| `UNKNOWN` | 소유자·용도·데이터 진위를 확인하지 못함 | 작업 중단, 삭제·baseline·이관 금지 |

단순히 행 수가 적거나 이름이 샘플과 비슷하다는 이유로 `NEW_OR_SAMPLE`로 분류하지 않는다. DB 소유자 또는 운영 책임자의 서면 확인이 필요하다.

### 4.2 전환 승인 기록

실행 전에 아래 항목을 한 건의 전환 기록으로 남긴다.

```text
source_class:
source_database:
source_schema:
cutover_date:
cutover_window:
official_statistics_start_date:
backup_id:
backup_sha256:
application_commit:
flyway_target_version:
approved_member_manifest:
approved_account_manifest:
timezone_interpretation:
operator:
approver:
```

비밀번호, DB 접속정보, 교사 개인정보가 포함된 파일은 Git 저장소에 커밋하지 않는다.

---

## 5. Flyway 적용 기준

### 5.1 도구 선택

Spring Boot가 공식적으로 연동하고 현재 프로젝트 규모에 충분한 Flyway를 사용한다. Liquibase를 동시에 사용하지 않는다.

Gradle 의존성은 Spring Boot dependency management에 버전을 맡기는 것을 기본으로 한다.

```gradle
implementation 'org.flywaydb:flyway-core'
runtimeOnly 'org.flywaydb:flyway-database-postgresql'
```

정확한 의존성 조합은 M1 구현 시 현재 Spring Boot 버전의 dependency report와 테스트로 확인한다.

### 5.2 실행 프로파일과 기본 설정

```properties
spring.sql.init.mode=never
spring.flyway.locations=classpath:db/migration
spring.flyway.default-schema=public
spring.flyway.schemas=public
spring.flyway.baseline-on-migrate=false
spring.flyway.baseline-version=0
spring.flyway.validate-on-migrate=true
spring.flyway.clean-disabled=true
spring.flyway.out-of-order=false
```

초기 운영 전환은 migration 실행과 웹 애플리케이션 시작을 분리한다.

```properties
# Testcontainers test profile
spring.flyway.enabled=true

# 로컬·운영 웹 애플리케이션 runtime
spring.flyway.enabled=false
```

이 저장소의 최초 guarded runner는 다음 환경변수를 비밀 저장소나 승인된 배포 설정에서 주입한 뒤 실행한다.

```text
FLYWAY_DB_URL=<direct JDBC URL>
FLYWAY_DB_USERNAME=<migration_owner>
FLYWAY_DB_PASSWORD=<secret>
MIGRATION_SOURCE_CLASS=NEW_OR_SAMPLE | LEGACY_OPERATIONAL
```

```bash
./gradlew dbMigrate
```

`dbMigrate`는 V017을 고정 target으로 사용한다. Flyway를 호출하기 전에 read-only transaction에서 V001의 catalog·데이터 검사를 실행하고, fresh 또는 정확한 legacy 후보만 허용한다. 이 사전검사는 V001의 `ACCESS EXCLUSIVE` 잠금을 메모리에서 `ACCESS SHARE`로 바꾼 검증 블록을 실행해 실제 DDL 직전의 read-only 위반에 도달한 경우만 통과시킨다. 파일 자체는 수정하지 않으며 V001은 실제 적용 시 다시 원래의 exclusive lock과 전체 검사를 수행한다. history가 없는 schema에는 V009~V012가 만드는 정확한 함수 signature도 사전에 검사해 baseline commit 뒤의 부분 실패를 막는다.

V014는 제약을 `NOT VALID`로 추가한 뒤 검증하는 트랜잭션 migration이다. V015와 V017은
인덱스 작업만 `CREATE INDEX CONCURRENTLY`로 수행하며, 이 migration들의 `.sql.conf`만
transaction을 끈다. runner와 Spring Boot Flyway는 PostgreSQL session-level advisory
lock을 사용해 동시 인덱스 생성과 transactional lock이 서로 기다리는 상태를 방지한다.

사전검사가 거부한 DB에는 `flyway_schema_history`도 만들지 않는다. 기존 공개 샘플 계정은 원문 비밀번호나 BCrypt hash를 artifact에 넣지 않고 공개된 비밀번호 hash 자체의 one-way fingerprint denylist로 탐지해 baseline 전에 중단한다. 사용자명이나 권한을 바꿔도 같은 공개 hash면 거부하며, 실제 V001도 exclusive lock을 잡은 뒤 같은 fingerprint를 재검사한다. 기술 검사가 fresh 또는 legacy 형태를 확인해도 실제 용도는 추정하지 않으며 `MIGRATION_SOURCE_CLASS`에는 운영 책임자의 승인 기록과 같은 분류만 입력한다.

- 운영 migration은 이 runner를 포함한 동일 commit의 고정 컨테이너 또는 승인된 job으로 한 번만 실행한다.
- 운영 웹 애플리케이션 계정에는 DDL 권한을 주지 않는다.
- runner의 Flyway 버전은 애플리케이션 dependency와 같고 `cleanDisabled=true`, baseline 자동화 금지, schema와 location을 코드로 고정한다.
- 로컬 runtime도 먼저 `dbMigrate`를 실행하고 앱 시작 Flyway는 끈다. 테스트 프로필만 Testcontainers DB에 앱 시작 migration을 허용한다.
- `prod` 프로필은 시작 시 성공한 V001~V017 이력을 정확히 비교하고 history 부재·실패·누락·초과 version이면 기동을 실패한다.

적용 원칙은 다음과 같다.

- `schema.sql`과 `data.sql`은 운영 classpath에서 제거하거나 테스트 전용 위치로 옮긴다.
- Spring SQL 초기화와 Flyway를 동시에 스키마 생성 수단으로 사용하지 않는다.
- 운영 DB에는 `baseline-on-migrate=true`를 사용하지 않는다.
- 적용된 migration 파일의 이름과 내용은 수정하지 않는다.
- 변경이 필요하면 다음 버전의 보정 migration을 추가한다.
- `flyway_schema_history`를 수동 편집하지 않는다.
- 원인을 확인하지 않은 `repair`를 실행하지 않는다.
- 운영 환경에서 `clean`을 실행하지 않는다.
- datasource URL은 코드의 `localhost` 고정값이 아니라 `${DB_URL}` 환경변수로 외부화한다.

### 5.3 신규 DB와 기존 DB

#### 신규 또는 샘플 DB

빈 DB에 V001부터 순서대로 적용한다. baseline을 만들지 않는다.

#### 실제 운영 DB

1. 사전점검과 백업 복원 훈련을 통과한다.
2. 기존 `member`, `authentications`, `attendance`, `attendance_log`의 스키마와 건수를 기록한다.
3. `public.flyway_schema_history`가 없고 `member`가 승인된 레거시 형태와 정확히 일치하는지 확인한다.
4. `MIGRATION_SOURCE_CLASS=LEGACY_OPERATIONAL` 승인과 read-only preflight가 일치할 때 guarded runner가 **version 0** baseline을 한 번만 명시적으로 수행한다.
5. 같은 runner가 V001부터 신규 테이블을 기존 테이블 옆에 생성하고 V017에서 고정 종료한 뒤 validate한다.

baseline은 기존 구조가 올바르다는 검증도, 백업도 아니다. baseline 시점의 schema-only dump, 객체 목록과 row count를 별도로 보존한다.

Flyway의 기본 baseline version에 의존하지 않는다. URL과 자격증명은 CLI 인자가 아닌 승인된 비밀 저장소에서 제공한다. 아래는 runner가 코드로 고정한 baseline 옵션의 동등한 CLI 표현이며, 운영자가 preflight를 우회해 별도로 실행하는 명령이 아니다.

```bash
flyway \
  -defaultSchema=public \
  -schemas=public \
  -baselineOnMigrate=false \
  -baselineVersion=0 \
  -baselineDescription="legacy schema accepted" \
  -cleanDisabled=true \
  baseline
```

baseline 직후 다음 결과가 정확히 한 건인지 확인한다.

```sql
SELECT version, description, type, success
FROM public.flyway_schema_history
ORDER BY installed_rank;
```

합격값은 `version = '0'`, `type = 'BASELINE'`, `success = TRUE`다. 이미 history
테이블이 있거나 다음 신규 애플리케이션 테이블 또는 `attend_set_updated_at()` 함수가
하나라도 있으면 자동으로 처리하지 않고 작업을 중단한다.

`department`, `account`, `account_credential_token`, `account_department_role`,
`department_membership`, `nfc_card`, `nfc_card_assignment`, `device`,
`attendance_policy_version`, `attendance_band`, `attendance_day`, `attendance_target`,
`attendance_record`, `tag_event_log`, `audit_log`, `telegram_link_token`,
`account_telegram_connection`, `telegram_webhook_update`,
`attendance_notification_outbox`, `finalization_operational_event`

이 20개 목록은 `ops/db/roles/003_grant_application_privileges.sql`의 필수 runtime
테이블 목록과 함께 변경한다. 권한 스크립트의 전체 필수 목록은 이 신규 20개에 채택
테이블 `member`와 Flyway 관리 테이블 `flyway_schema_history`를 더한 22개다. 실제
V001 preflight는 개수만 비교하지 않고 레거시 네 테이블 외의 예상하지 않은
`public` relation도 거부한다.

### 5.4 트랜잭션과 인덱스

- 초기 대상 규모는 부서별 5~20명이므로 maintenance window에서 일반 `CREATE INDEX`를 사용한다.
- 초기 migration은 가능한 한 파일 단위 트랜잭션으로 적용한다.
- `CREATE INDEX CONCURRENTLY`가 필요해지면 트랜잭션 밖에서 실행되는 별도 migration으로 분리한다.
- 한 migration 안에서 애플리케이션 데이터 변환과 장시간 인덱스 작업을 섞지 않는다.

---

## 6. 버전별 스키마 마이그레이션

기준 DDL은 [ATTENDANCE_DDL.sql](./ATTENDANCE_DDL.sql)이다. 이 파일을 운영에서 직접 실행하지 않고 다음 Flyway 파일로 분리한다.

| 버전 | 파일 | 주요 내용 |
|---|---|---|
| V001 | `V001__adopt_or_create_member.sql` | `member` 신규 생성 또는 레거시 확장, 기존 출석·로그 FK의 `RESTRICT` 전환 |
| V002 | `V002__create_organization_and_accounts.sql` | `department`, `account`, `account_credential_token`, `account_department_role` |
| V003 | `V003__create_membership_card_and_device.sql` | `department_membership`, `nfc_card`, `nfc_card_assignment`, `device` |
| V004 | `V004__create_attendance_policy.sql` | `attendance_policy_version`, `attendance_band` |
| V005 | `V005__create_attendance_domain.sql` | `attendance_day`, `attendance_target`, `attendance_record` |
| V006 | `V006__create_event_and_audit_log.sql` | `tag_event_log`, `audit_log` |
| V007 | `V007__add_indexes_and_scope_guards.sql` | 복합 FK, 부분 유일 인덱스, 조회·마감 인덱스 |
| V008 | `V008__add_updated_at_triggers.sql` | 고유 이름의 `attend_set_updated_at()` 함수·trigger와 함수의 `PUBLIC EXECUTE` 회수 |
| V009 | `V009__require_birth_for_active_member_writes.sql` | 기존 결측 행은 그대로 보존하되 신규 등록·기본정보 수정·활성화에 미래가 아닌 정확한 생년월일을 강제하고, 활성 소속–교사 상태 불일치와 종료 소속·카드 연결 이력의 재개방·변조를 차단 |
| V010 | `V010__add_audit_log_retention_worker.sql` | audit 시각 DB 강제, 전역 retention 인덱스와 고정 2년·500행 `SECURITY DEFINER` purge 함수 추가. 권한 script는 별도 `retention_worker`에 이 함수 실행만 부여 |
| V011 | `V011__add_tag_event_log_retention_worker.sql` | tag event 수신 시각 DB 강제, retention 인덱스와 고정 90일·500행 `SECURITY DEFINER` purge 함수 추가. 권한 script는 같은 분리 `retention_worker`에 이 함수 실행만 부여 |
| V012 | `V012__add_attendance_finalization_and_telegram_notifications.sql` | 당일 출석 마감 시각·Telegram 연결·outbox를 추가하고, 검증된 webhook update의 7일·500행 고정 retention 함수를 추가 |
| V013 | `V013__align_attendance_finalization_precision.sql` | 모든 기존 출석일의 마감 경계를 고정 정책 마지막 포함 상한의 정확히 1µs 뒤로 보정 |
| V014 | `V014__add_attendance_finalization_retry_state.sql` | 마감 실패 횟수, 다음 retry 시각, claim version과 lease를 추가해 다중 인스턴스의 1·2·4·8·16분 재시도를 영속화 |
| V015 | `V015__add_attendance_finalization_dispatch_index.sql` | 마감·재시도 dispatch 인덱스를 독립된 비트랜잭션 online migration으로 생성 |
| V016 | `V016__add_finalization_operational_alert_outbox.sql` | 최초 마감 실패 시각과 재시도 소진 운영 이벤트 outbox·Telegram delivery fencing 상태 추가. V015의 기존 6회 소진 날짜도 `PENDING` 사건으로 backfill |
| V017 | `V017__add_attendance_notification_lease_index.sql` | 일반 출석 Telegram의 동적 lease 만료 조회를 위한 `PROCESSING(lease_until, id)` concurrent 부분 인덱스 추가 |
| R | `R__update_member_column_comments.sql` | 적용된 V001 checksum은 바꾸지 않고 `age`·`birth` catalog 설명을 현재 최소수집 계약과 동기화 |

V002는 비밀번호가 없는 `PENDING_SETUP`, 비밀번호가 설정된 `ACTIVE`, 두 형태를 보존할 수 있는 `DISABLED` 상태와 nullable 비밀번호 필드의 일관성 `CHECK`를 함께 생성한다. `account_credential_token`은 `INVITATION`·`RESET`, 64자 lowercase HMAC-SHA-256 hash, 대상·발급 계정, 최대 30분의 발급·만료 시각과 사용·무효 시각을 저장한다. 계정·목적별 미사용·미무효 token 한 건을 보장하는 부분 유일 인덱스는 V007에서 생성한다. V002 적용과 V007에 분리된 부분 유일성을 포함한 PostgreSQL DB 테스트 통과가 계정 생성·회원가입 초대·reset command의 출시 gate다. 원문 token은 관리자가 1회 표시 링크를 복사해 승인된 1:1 메신저로 전달하며, 운영 공개 base URL·HTTPS 승인은 별도 운영 gate다.

### 6.1 버전 작성 규칙

- 테이블명과 컬럼명은 `snake_case`로 고정한다.
- 시간 시점은 `TIMESTAMPTZ`, 업무 날짜는 `DATE`, 정책 시각은 `TIME WITHOUT TIME ZONE`을 사용한다.
- 업무 상태는 PostgreSQL enum 대신 `VARCHAR/TEXT + CHECK`를 사용한다.
- 역사 데이터 FK는 기본적으로 `ON DELETE RESTRICT`를 사용한다.
- 모든 부서 범위 참조는 가능한 경우 복합 FK로 DB에서도 검증한다.
- 활성 소속, 활성 카드 연결과 활성 부서 권한은 부분 유일 인덱스로 보호한다.
- 신규 장치의 기본 상태는 `INACTIVE`로 두고 자격증명 배포와 제한 시험 후에만 활성화한다.
- 버전 SQL에 실제 관리자, 비밀번호, 교사, 카드 UID를 넣지 않는다.
- 일반 migration에서는 `IF EXISTS`로 예상하지 못한 스키마 차이를 숨기지 않는다.
- V001만 `member`가 없는 fresh DB와 승인된 레거시 `member`를 모두 지원한다. catalog에서 컬럼·타입·PK·sequence·unique·FK를 정확히 검사한 뒤 `신규 생성` 또는 `레거시 확장` 한 경로만 실행하고, 제3의 형태이면 `RAISE EXCEPTION`으로 중단한다.
- 레거시 경로에서 `attendance`와 `attendance_log`에는 각각 `member(id)`를 참조하는 FK가 정확히 한 개 있어야 한다. 삭제 동작이 `CASCADE`이면 `RESTRICT`로 교체하고 이미 `RESTRICT`이면 유지한다. FK가 없거나 복수이거나 다른 테이블·컬럼을 참조하면 중단한다. fresh 경로에서 두 테이블이 모두 없을 때만 이 검사를 건너뛴다.
- 마이그레이션 완료 후 동일 버전 재실행이 아니라 `validate`와 새 DB 재구성으로 검증한다.
- 기준 DDL의 바깥쪽 `BEGIN`과 `COMMIT`은 각 migration 파일에 복사하지 않고 Flyway의 transaction 경계를 사용한다.
- 공용 이름 충돌을 피하기 위해 갱신 함수는 `set_updated_at()`이 아니라 `attend_set_updated_at()`을 사용하고 `OR REPLACE`로 기존 함수를 덮어쓰지 않는다.
- 정책 발행 불변성, `is_target = TRUE`, 기록이 생긴 날짜의 취소 금지 같은 교차 테이블 규칙은 M2 서비스 트랜잭션과 PostgreSQL 통합 테스트가 완료되기 전까지 운영 쓰기를 허용하지 않는다. 기록의 날짜–정책–구간–상태 일치는 M1 복합 FK가 우선 방어한다.

### 6.2 초기 전환에서 하지 않는 작업

- 기존 `member`, `authentications`, `attendance`, `attendance_log` 삭제
- 기존 테이블 이름이나 `member.id` 변경
- 별도 `teacher` 테이블 생성 또는 기존 `member` 행 복제
- 기존 PostgreSQL enum 삭제
- 과거 결석 자동 생성
- 샘플 계정 생성
- 기본 비밀번호 생성
- 실제 개인정보 seed

레거시 테이블 폐기는 신규 시스템의 관찰 기간, 보유기간 정책, 별도 백업과 복원 검증 이후의 후속 migration으로만 검토한다.

V001의 레거시 확장 경로는 다음만 수행한다.

- 기존 `member.id`, `name`, `age`, `phone`, `birth`, `created_at`, `card_uid`와 `member_id_seq` 보존. 단, 사전 승인된 빈 전화번호의 `NULL` 정규화는 기록된 예외
- `active BOOLEAN`, `updated_at TIMESTAMPTZ` 추가
- 기존 행은 `active = FALSE`로 두고 승인 importer가 활성화
- 신규 insert도 `active` 기본값은 `FALSE`이며 구성원·소속 생성 서비스가 한 트랜잭션에서 명시적으로 활성화
- `attendance`와 `attendance_log`의 `member` FK를 `ON DELETE RESTRICT`로 교체
- `member.created_at`은 `TIMESTAMP WITHOUT TIME ZONE` 그대로 유지하고 판정·통계에 사용하지 않음
- 기존 행에 처음 채워지는 `updated_at`은 원본 생성·수정 시각이 아니라 V001 적용 시각을 의미하며, 이 값을 과거 변경 시각으로 해석하지 않음
- V001은 기존 호환성을 위해 `age`, `birth`, `created_at`, `card_uid`에 레거시 comment를 추가한다. 이후 repeatable metadata migration이 `age`를 신규 업무에서 사용하지 않는 원본 호환값, `birth`를 신규 등록·기본정보 수정·활성화에 필요한 생년월일이자 생일 관리·만 나이 계산의 기준값으로 정정하며 적용된 V001 checksum은 바꾸지 않는다.

fresh DB 경로도 최종 컬럼, 기본값, 제약과 comment가 레거시 확장 결과와 같도록 `member`를 생성한다. `member_id_seq`가 `max(id)`보다 뒤처진 경우에만 안전하게 올리고 이미 앞선 sequence를 낮추지 않는다.

---

## 7. 레거시 데이터 이관

### 7.1 Flyway와 데이터 이관의 분리

Flyway에는 모든 환경에서 동일하게 적용할 스키마만 둔다. 실제 운영 데이터는 별도의 일회성 importer로 처리한다.

이유는 다음과 같다.

- 부서 매핑과 계정 역할은 환경마다 다르다.
- 개인정보 입력 파일을 Git에 저장하면 안 된다.
- 잘못된 행을 자동 추정하지 않고 승인·거부 내역을 남겨야 한다.
- importer는 dry-run과 결과 보고서를 제공해야 한다.

importer의 프로그램 코드는 버전 관리할 수 있지만 입력 manifest와 결과 보고서는 접근 제한된 운영 저장소에 보관한다.

### 7.2 필수 manifest

#### 최초 조직 승인 목록

`initial-organization.csv`

```text
department_key,department_name,department_admin_username,approved_by
```

- 최초 시스템 관리자는 공개 기본 비밀번호 없이 일회성 bootstrap 절차로 생성한다.
- 회원가입 초대 token, 최초 비밀번호나 reset token은 CSV와 Git 저장소에 기록하지 않는다.
- 부서와 부서 관리자 승인 목록은 구성원 importer보다 먼저 적용한다.

#### 기존 구성원 활성화·부서 매핑

`legacy-member-crosswalk.csv`

```text
legacy_member_id,department_key,activate_member,retain_phone,approved_by
```

- `legacy_member_id`로 기존 `member` 행을 직접 연결하고 PK를 바꾸지 않는다.
- 이름이 같다는 이유로 구성원 행을 병합하거나 새 행으로 복제하지 않는다.
- 부서를 이름, 카드 UID 또는 출석 기록으로 추정하지 않는다.
- 신규 등록·활성 교사 기본정보 수정·활성화에는 미래가 아닌 정확한 `birth`를 필수로 입력하고 생일 관리와 만 나이 계산에 사용한다. 기존 정수 `age`는 원본 호환을 위해 보존할 뿐 신규 화면의 현재 나이나 업무 판정에 사용하거나 수정하지 않는다. 레거시 결측값과 파생 나이는 확인된 날짜로 정정하기 전까지 미상으로 두며 정수 `age`로 생년월일을 역산하지 않는다.
- `phone`은 운영 필요성과 개인정보 처리 승인이 있을 때만 신규 화면에 노출한다.
- 신규 `department`, `account`, `nfc_card`의 `created_at`은 컷오버 시각으로 기록한다.
- 신규 `joined_at`은 과거 가입일을 추정하지 않고 컷오버 시각으로 기록한다.
- 레거시 `member.created_at`은 타입과 값을 그대로 보존하고 신규 판정·통계의 업무 시각으로 사용하지 않는다.
- 이관 audit action으로 `LEGACY_MASTER_IMPORT`를 남겨 컷오버 소속임을 구분한다.

#### 계정 이관 목록

`legacy-account-crosswalk.csv`

```text
legacy_username,import_account,system_role,department_key,department_role,approved_by
```

- 기존 `ADMIN`을 자동으로 `SYSTEM_ADMIN`으로 승격하지 않는다.
- 최초 `SYSTEM_ADMIN`은 legacy account importer가 아니라 승인된 bootstrap으로만 생성한다.
- bootstrap 사용자명과 레거시 사용자명이 충돌하면 자동 병합하지 않고 이관을 중단한다.
- 기존 `USER`에는 신규 권한을 자동 부여하지 않는다.
- MVP에서 필요하지 않은 `USER` 계정은 이관하지 않거나 `DISABLED`로 둔다.
- 삭제된 공개 샘플 계정·재사용 가능 비밀번호와 알려진 샘플 해시는 모두 폐기한다.
- 이관한 운영 계정도 최초 로그인 전 비밀번호를 재설정한다.
- 사용자명 대소문자 정규화 충돌이 있으면 자동 병합하지 않는다.

#### NFC 카드 이관 목록

`legacy-card-crosswalk.csv`

```text
legacy_member_id,raw_uid,canonical_uid,verification_method,verified_at,verified_by,activate_card
```

- `verification_method`는 `PHYSICAL_RETAG` 또는 `OWNER_CONFIRMATION`만 허용한다.
- `raw_uid`는 해당 `legacy_member_id`의 원본 `member.card_uid`와 정확히 일치해야 한다.
- importer가 원본에서 `canonical_uid`를 다시 계산해 manifest 값과 일치하는지 확인한다.
- 형식 검증만 통과한 카드를 자동 활성화하지 않는다.
- `activate_card = true`이면 승인된 구성원과 활성 소속이 먼저 존재해야 한다.
- 신규 `assigned_at`은 과거 카드 지급일을 추정하지 않고 컷오버 시각으로 기록한다.
- 물리 재태깅 값이 원본 `card_uid`와 다르면 레거시 이관으로 처리하지 않고 신규 카드 등록 흐름으로 보낸다.
- 분실·소유자 불명·형식 오류 카드는 이관하지 않고 실제 카드 등록 흐름에서 다시 처리한다.

### 7.3 NFC UID 검증

1. 원문을 결과 보고서에 보존한다.
2. 앞뒤 공백 제거와 영문 대문자화 이외의 보정은 하지 않는다.
3. 선행 0을 보존한다.
4. `^[0-9A-F]+$`, 짝수 길이, 허용 길이 조건을 검증한다.
5. 정규화 후 중복 UID가 있으면 관련 행 전체를 보류한다.
6. `O → 0`, `L → 1` 같은 추정 변환은 금지한다.
7. 형식 오류, 충돌, 소유자 불명 카드는 재태깅 후 관리자가 연결한다.

제거된 기존 `data.sql`의 샘플 UID는 실제 카드 UID로 이관하지 않는다.

### 7.4 과거 출석과 로그의 격리

MVP에서는 예외 없이 다음과 같이 처리한다.

- `member`: 신규 구성원·소속·카드 업무의 기준 테이블로 계속 사용
- `authentications`: 신규 로그인에 사용하지 않고 읽기 전용으로 보존
- `attendance`: 기존 테이블을 읽기 전용 레거시 이력으로 보존
- `attendance_log`: 기존 테이블을 읽기 전용 기술 로그로 보존
- 신규 `attendance_day`, `attendance_target`, `attendance_record`: 컷오버 날짜부터 사용
- 신규 공식 통계: 컷오버 날짜부터 계산

- 기존 `IN_TIME`, `TIME_OUT`, `MISS`를 신규 `attendance_record`로 변환하지 않는다.
- 레거시 시각은 원본 `timestamp without time zone` 그대로 archive에 보존하고 신규 `TIMESTAMPTZ`로 추정 변환하지 않는다.
- 원래 장치와 request ID를 모르는 로그를 신규 `tag_event_log`로 변환하지 않는다.
- 레거시 조회 화면을 만들 경우 신규 공식 통계 repository와 별도 query 경계를 사용한다.
- 컷오버 시 기존 네 테이블의 immutable dump와 row count·해시를 보존한다.
- 신규 runtime DB 역할은 레거시 `created_at`을 읽기만 하고 `birth`는 승인된 부서 교사 화면에서만 조회·입력·수정한다. 원본 호환 정수 `age`와 `card_uid`는 신규 업무 query에서 읽거나 수정하지 않는다.
- 신규 runtime DB 역할에는 `authentications`, `attendance`, `attendance_log`의 `INSERT`, `UPDATE`, `DELETE` 권한을 주지 않는다.
- 레거시 조회가 필요하면 명시적인 `SELECT` 권한 또는 읽기 전용 view만 제공한다.

### 7.5 importer 실행 조건

- importer 범위는 기존 `member` 활성화, 현재 부서 소속, 검증된 카드와 승인 계정으로 제한한다.
- 기본 모드는 `dry-run`이어야 한다.
- 승인 manifest에 없는 행은 이관하지 않는다.
- 한 실행에는 고유한 batch ID를 부여한다.
- 실제 쓰기는 단일 batch 트랜잭션으로 처리한다.
- 예상하지 못한 거부 행이 한 건이라도 있으면 commit하지 않는다.
- 입력 파일 해시, 원본 PK, 신규 PK, 처리 결과, 거부 사유를 결과 보고서에 남긴다.
- 같은 batch를 다시 실행해 중복 구성원·소속·카드 연결이 생기지 않도록 멱등성을 보장한다.

---

## 8. 사전점검

### 8.1 실행 전 게이트

다음 항목 중 하나라도 충족하지 않으면 운영 migration을 실행하지 않는다.

- [ ] 소스 DB가 `NEW_OR_SAMPLE` 또는 `LEGACY_OPERATIONAL`로 승인됨
- [ ] DB명, schema, 접속 계정과 서버가 운영 대상과 일치함
- [ ] 장치 API, 관리자 쓰기와 스케줄러를 중지할 수 있음
- [ ] `LEGACY_OPERATIONAL`이면 전체 백업을 별도 DB에 실제 복원함
- [ ] `LEGACY_OPERATIONAL`이면 백업 파일 해시와 생성 시각을 기록함
- [ ] 운영 전환과 동일한 분류의 격리 DB에서 migration을 완료함
- [ ] `LEGACY_OPERATIONAL`이면 조직·교사·카드·계정 manifest가 승인됨
- [ ] 최초 시스템 관리자 bootstrap과 비밀번호 전달 방식이 승인됨
- [ ] 컷오버 날짜와 공식 통계 시작일이 확정됨
- [ ] 롤백 책임자와 4시간 이내 복원 경로가 확인됨

### 8.2 DB 식별

```sql
SELECT current_database(),
       current_user,
       current_schema(),
       current_setting('TimeZone'),
       version();

SELECT to_regclass('public.flyway_schema_history');

SELECT to_regprocedure('public.attend_set_updated_at()');
```

`current_schema()`가 `public`이 아니면 작업을 중단한다. baseline 전에는 `flyway_schema_history`와 `attend_set_updated_at()` 함수가 모두 없어야 한다.

baseline 전에 다음 조건을 추가로 확인한다.

- `member`, `authentications`, `attendance`, `attendance_log` 네 기존 테이블이 모두 `public`에 존재한다.
- `member`는 현재 코드의 컬럼·타입·PK·`member_id_seq`·`card_uid` unique 구조와 정확히 일치하고 아직 `active`, `updated_at`이 없다.
- 5.3에 명시한 신규 애플리케이션 테이블 20개는 하나도 존재하지 않는다.
- 신규 migration과 이름이 충돌하는 함수·trigger·index가 없다.
- history 테이블이 이미 있으면 새 baseline을 만들지 않고 기존 이력을 별도로 조사한다.
- 애플리케이션, 테스트 프로세스와 관리 도구가 같은 DB를 사용하고 있지 않다.

### 8.3 레거시 건수와 범위

```sql
SELECT 'member' AS table_name, count(*) FROM member
UNION ALL
SELECT 'authentications', count(*) FROM authentications
UNION ALL
SELECT 'attendance', count(*) FROM attendance
UNION ALL
SELECT 'attendance_log', count(*) FROM attendance_log;

SELECT min(attend_date) AS first_date,
       max(attend_date) AS last_date,
       count(*) AS total
FROM attendance;

SELECT status::text, count(*)
FROM attendance
GROUP BY status
ORDER BY status::text;
```

PK 최댓값, sequence 값, enum label, FK와 unique constraint의 실제 존재 여부도 schema-only 보고서에 저장한다.

`member` 채택 전에는 다음을 추가 확인한다.

```sql
SELECT count(*) AS blank_name
FROM member
WHERE name IS NULL OR btrim(name) = '';

SELECT id, phone
FROM member
WHERE phone IS NOT NULL
  AND btrim(phone) = '';

SELECT pg_get_serial_sequence('public.member', 'id') AS member_sequence,
       max(id) AS max_member_id
FROM member;
```

- `blank_name`은 0이어야 한다.
- 빈 문자열 전화번호가 한 건이라도 있으면 V001을 시작하지 않는다. immutable 원본 백업 후 개인정보 담당자의 승인을 받아 해당 값만 `NULL`로 바꾸고, 대상 PK·변경 전후 값·실행자를 별도 정정 기록에 남긴 뒤 다시 검사한다.
- `member_id_seq`의 다음 값이 기존 `max(id)`와 충돌하지 않아야 한다.
- `member.created_at`은 `timestamp without time zone` 원문으로 백업한다.
- `member.card_uid` 정규화 충돌과 무효값은 신규 카드 테이블로 옮기지 않는다.

### 8.4 출석 데이터 품질

```sql
SELECT
    count(*) FILTER (WHERE member_id IS NULL) AS null_member,
    count(*) FILTER (WHERE attend_date IS NULL) AS null_date,
    count(*) FILTER (WHERE attend_time IS NULL) AS null_time
FROM attendance;

SELECT member_id, attend_date, count(*)
FROM attendance
GROUP BY member_id, attend_date
HAVING count(*) > 1;

SELECT a.attend_id, a.member_id
FROM attendance AS a
LEFT JOIN member AS m ON m.id = a.member_id
WHERE a.member_id IS NOT NULL
  AND m.id IS NULL;

SELECT attend_id, attend_time, attend_date
FROM attendance
WHERE attend_time IS NOT NULL
  AND attend_date IS NOT NULL
  AND attend_time::date <> attend_date;
```

### 8.5 카드와 계정 품질

```sql
SELECT id, card_uid
FROM member
WHERE card_uid IS NOT NULL
  AND (
      upper(btrim(card_uid)) !~ '^[0-9A-F]+$'
      OR char_length(btrim(card_uid)) NOT BETWEEN 8 AND 32
      OR mod(char_length(btrim(card_uid)), 2) <> 0
  );

SELECT upper(btrim(card_uid)) AS normalized_uid, count(*)
FROM member
WHERE card_uid IS NOT NULL
GROUP BY upper(btrim(card_uid))
HAVING count(*) > 1;

SELECT lower(btrim(username)) AS normalized_username, count(*)
FROM authentications
GROUP BY lower(btrim(username))
HAVING count(*) > 1;
```

현재 리더가 실제로 반환하는 UID byte 길이와 표현 형식은 장치 API 명세에서 고정하고, DB의 허용 길이와 일치시킨다.

### 8.6 백업과 복원

- PostgreSQL custom format으로 schema와 data 전체를 백업한다.
- `pg_dumpall --globals-only` 또는 동등한 재현 스크립트로 DB 역할과 GRANT를 별도 백업한다.
- 운영 서버와 분리된 접근 제한 위치에 저장한다.
- 백업 파일의 SHA-256, 파일 크기, 생성 시각, DB 식별정보를 기록한다.
- 별도의 격리된 DB에 `pg_restore`를 수행한다.
- 격리 환경에 역할·권한을 복원하고 로그인 상태, table·sequence·함수 권한까지 검증한다.
- 복원 DB에서 테이블 건수와 핵심 PK 최댓값을 원본과 비교한다.
- 복원 DB에서 Flyway migration을 실제 적용하고 전체 검증한 뒤 해당 격리 DB를 폐기한다.
- 백업 파일이 있다는 사실이 아니라 **복원 성공**을 완료 조건으로 사용한다.

cluster-global 역할 백업에는 비밀번호 해시가 포함될 수 있으므로 DB 백업과 같은 수준으로 암호화하고 접근을 제한한다.

### 8.7 DB 역할

| 역할 | 허용 범위 |
|---|---|
| `migration_owner` | Flyway history와 신규 schema DDL. 웹 애플리케이션에서 사용 금지 |
| `legacy_writer` | 안전 릴리스가 실제 사용하는 기존 테이블 최소 DML과 sequence 권한. `member`는 전체 SELECT, `name`·`age`·`phone`·`birth`의 column-level INSERT·UPDATE만 허용하고 `card_uid` UPDATE와 물리 DELETE는 금지. 컷오버 시 로그인 또는 쓰기 권한 차단 |
| `cutover_writer` | bootstrap·importer용 현재 애플리케이션 테이블 DML, 신규 identity sequence와 `member_id_seq`의 `USAGE`, 승인 이관에 필요한 `member` 최소 SELECT 및 `active` UPDATE. 레거시 출석·로그 DML과 DDL 금지, 컷오버 후 회수 |
| `app_runtime` | 현재 애플리케이션 테이블의 최소 DML, 신규 identity sequence와 `member_id_seq`의 `USAGE`, `member` 허용 컬럼의 최소 SELECT·INSERT·UPDATE, schema 호환성 확인용 `flyway_schema_history` SELECT. DDL, history 변경, `member` DELETE와 세 레거시 테이블 DML 금지 |

재현 가능한 역할 생성·migration 준비·runtime grant SQL과 실행 순서는
[`ops/db/roles`](../ops/db/roles/README.md)에 둔다. SQL에는 비밀번호를 넣지 않으며
실제 credential 발급과 운영 적용은 최종 배포 단계에서 수행한다.

- `PUBLIC`에 불필요한 테이블·sequence·함수 실행 권한을 주지 않는다.
- DB/schema 소유자가 baseline 전에 `REVOKE CREATE ON SCHEMA public FROM PUBLIC`을 실행하고 `migration_owner`에만 필요한 schema 권한을 부여한다.
- V008은 함수 생성 직후 `attend_set_updated_at()`의 `PUBLIC EXECUTE` 권한을 회수한다.
- `authentications`, `attendance`, `attendance_log`의 읽기 전용 상태는 코드 관례가 아니라 `REVOKE`로 강제한다.
- `legacy_writer`에도 table-level `UPDATE ON member`를 주지 않는다. 안전 릴리스가 사용하는 기존 컬럼만 column-level로 허용해 제거한 카드 수정 경로를 DB 권한으로도 차단한다.
- `member`는 column-level 권한으로 `id`, `name`, `phone`, `birth`, `created_at`, `active`, `updated_at`만 조회한다. `name`, `phone`, `birth`, `active`만 INSERT·UPDATE하고 `created_at`, `updated_at`은 직접 수정하지 않는다. `updated_at`은 trigger만 갱신하며 원본 호환 정수 `age`, `card_uid`의 runtime 접근과 모든 `member` DELETE는 차단한다.
- 신규 애플리케이션의 `member` query는 명시적 컬럼 목록을 사용한다. 권한으로 차단한 레거시 컬럼까지 요구하는 `SELECT *` Mapper를 신규 배포물에 남기지 않는다.
- 애플리케이션 artifact의 요구 schema version과 history를 시작 시 비교하되 `app_runtime`은 `flyway_schema_history`를 읽을 수만 있고 변경할 수 없다. V017 release는 history 부재, 실패 행, V001~V017의 누락·중복·초과 version에서 기동을 실패한다. 문자열 `MAX(version)`은 사용하지 않고 Flyway `MigrationVersion`으로 전체 적용 순서를 비교하며 checksum은 runner의 `flyway validate`로 검증한다.
- V010~V012 retention은 웹 runtime grant에 `DELETE`를 추가하지 않는다. 별도 `retention_worker`가 direct table 권한 없이 고정 2년 audit·90일 tag event·7일 Telegram webhook replay cutoff function만 실행하며, worker credential은 웹 container에 주입하지 않는다.
- 신규 테이블, identity sequence와 함수 권한은 재현 가능한 별도 권한 스크립트로 관리한다.
- 신규 배포물에 세 레거시 테이블 쓰기 Mapper, 기존 `member.card_uid` 수정과 `DELETE FROM member` 경로가 포함되지 않았는지 확인한다.
- 롤백 때문에 `legacy_writer`가 다시 필요하면 승인 후 제한된 시간 동안만 복구한다.

---

## 9. 실행 절차

### 9.1 1단계: 안전 릴리스

새 스키마 기능을 포함하지 않은 상태에서 먼저 다음 변경만 배포한다.

1. 외부 운영 설정에 `SPRING_SQL_INIT_MODE=never`를 먼저 강제한다.
2. `DELETE FROM member` Mapper와 `/member/delete` 물리 삭제 경로를 제거하거나 명시적으로 차단하고, 기존 `updateMember`에서 `card_uid` 변경을 제거한다.
3. `member` DELETE를 제외한 최소 권한 `legacy_writer`와 필요한 sequence 권한을 만들고 운영 복제본에서 기존 기능을 검증한다.
4. `schema.sql`, `data.sql`을 운영 배포 artifact와 실행 경로에서 제거한다.
5. datasource URL을 `${DB_URL}`로 외부화하고 안전 릴리스가 `legacy_writer`를 사용하게 한다.
6. 개발·테스트·운영 DB 설정을 분리한다.
7. 빌드 artifact 내부에 파괴적 SQL과 샘플 seed가 없는지 검사한다.
8. 앱을 연속 두 번 재시작한다.
9. 재시작 전후 schema dump, row count와 핵심 데이터 checksum이 변하지 않는지 검증한다.
10. 이 검증을 통과한 버전을 가장 오래된 허용 롤백 대상으로 보관한다.

안전 릴리스 자체도 배포 전 백업 후 적용한다.

파괴적 `schema.sql`이 포함된 그 이전 바이너리는 어떤 경우에도 롤백 대상으로 사용하지 않는다. 안전 릴리스 자체에 문제가 생기면 SQL 초기화를 외부 설정으로 계속 차단한 상태에서 forward fix한다.

### 9.2 2단계: M1 스키마 리허설

1. 새 빈 PostgreSQL DB에 V001~V017을 적용하고 catalog·부정 테스트를 수행한다.
2. 제거된 기존 `schema.sql`과 동일한 `member`, `authentications`, `attendance`, `attendance_log` 테스트 fixture를 만든다.
3. fixture의 version 0 baseline 사전조건과 결과를 검증한다.
4. fixture에 V001~V017을 적용한다. `member` 행·PK·원본 컬럼·sequence, `authentications` 전체, `attendance`·`attendance_log` 행과 원본 컬럼이 보존되는지 확인한다. 두 출석 테이블의 `member` FK 삭제 동작이 승인된 `CASCADE → RESTRICT` 변경 외에는 달라지지 않았는지도 확인한다.
5. fresh 경로와 레거시 채택 경로의 최종 `member` 컬럼·제약이 일치하는지 catalog로 비교한다.
6. 실제 운영 DB가 있으면 최근 백업을 격리 DB에 복원해 같은 schema 절차를 수행한다.
7. 각 경로를 다시 만든 깨끗한 DB에서 처음부터 한 번 더 재현한다.

M1 리허설은 신규 앱의 관리자·출석·장치 기능, 실제 importer와 자동 마감 완료를 요구하지 않는다.

### 9.3 3단계: M4~M5 전체 컷오버 리허설

1. `LEGACY_OPERATIONAL`이면 최근 운영 백업을 격리 DB에 복원하고, `NEW_OR_SAMPLE`이면 새 빈 DB를 만든다.
2. 실제 배포 release의 고정된 migration location과 checksum을 준비한다.
3. `LEGACY_OPERATIONAL`에만 version 0 baseline을 적용한다.
4. 승인 기록의 `flyway_target_version`까지 migration을 실제 적용한다.
5. 제한된 `cutover_writer` 권한을 부여한다.
6. 최초 시스템 관리자와 부서를 생성한다.
7. 승인 계정을 이관하거나 새로 생성한 뒤 부서 관리자 역할을 부여한다.
8. 승인된 기존 `member`를 활성화하고 현재 소속·검증 카드를 importer dry-run 후 실제 전환한다.
9. `app_runtime`의 table·column·신규 identity sequence·`member_id_seq`·함수 권한을 실제 운영과 동일하게 적용한다.
10. 신규 앱을 모든 쓰기 feature flag가 꺼진 상태로 시작해 읽기 smoke test를 수행한다.
11. 실운영 9.4의 제한 관리자 활성화, 비허용 쓰기 거부, 단일 장치 활성화, 장치 API와 스케줄러 전환 순서를 그대로 재연한다.
12. 신규 애플리케이션의 쓰기 smoke test와 모든 부정 테스트를 수행한다.
13. M2 자동 마감의 동시 실행, 부분 실패 rollback과 재기동 복구를 시험한다.
14. `cutover_writer` 권한을 회수한다.
15. 같은 절차를 다시 복원하거나 생성한 깨끗한 DB에서 처음부터 한 번 더 재현한다.

### 9.4 4단계: 실제 운영 컷오버

이 단계는 V001~V011만으로 실행하지 않는다. M2의 교차 테이블 업무 규칙과 자동 마감 트랜잭션, M3 관리자 보안, M4 장치 API가 자동화된 부정·동시성 테스트를 통과하고, 전체 release의 `flyway_target_version`과 migration checksum이 승인된 뒤에만 진행한다.

1. 실제 출석이 없는 maintenance window를 시작한다.
2. 관리자 쓰기, 장치 API와 스케줄러를 중지한다.
3. `legacy_writer`의 로그인 또는 DML 권한을 차단하고 활성 쓰기 연결을 종료한다.
4. 레거시 schema dump, row count와 핵심 데이터 checksum을 고정한다.
5. 최종 전체 백업을 생성하고 해시와 복원 가능 백업 ID를 기록한다.
6. 기존 운영 DB라면 사전조건을 재확인한 뒤 version 0 baseline을 수행한다.
7. 별도 migration runner로 승인된 `flyway_target_version`까지 적용하고 `info`, `validate`를 통과시킨다.
8. `cutover_writer` 로그인을 제한된 시간 동안 활성화하고 승인된 최소 권한을 부여한다.
9. `cutover_writer`로 최초 시스템 관리자와 부서를 생성한다.
10. 승인 계정을 이관하거나 새로 생성한 뒤 부서 관리자 역할을 부여한다.
11. `LEGACY_OPERATIONAL`이면 승인된 기존 `member` 활성화·현재 소속·검증 카드 importer를 실행한다.
12. `app_runtime`에 신규 테이블·identity sequence, `member_id_seq`와 `member` 허용 컬럼의 최소 권한만 주고 `member` DELETE와 세 레거시 테이블의 DML을 명시적으로 회수한다.
13. 환경 기반 immutable 설정에서 `admin-write.enabled=false`, `device-api.enabled=false`, `scheduler.enabled=false`로 신규 앱을 시작한다.
14. 로그인, 부서 격리와 이관 결과를 읽기 전용으로 먼저 확인한다.
15. 설정을 변경해 controlled restart하고 schema 검사·health 확인을 다시 통과한 뒤 `admin-write`만 지정된 컷오버 운영자와 maintenance 네트워크에 제한 활성화한다.
16. 장치·자격증명, 발행 정책·구간, 오늘 출석 날짜·대상자와 실제 교사 카드 연결을 준비하고 비허용 관리자의 쓰기가 거부되는지 확인한다.
17. 세 flag를 다시 `false`로 바꾸어 controlled restart하고 모든 검증 결과와 컷오버 백업을 확인한 뒤 첫 권위 있는 체크인 트랜잭션에 대한 go/no-go 승인을 받는다.
18. 다른 장치는 `INACTIVE`와 네트워크 격리 상태로 두고, 승인된 장치 한 대만 credential test endpoint에 접근할 수 있는 maintenance 네트워크에 연결한다.
19. `device-api.enabled=true`와 컷오버 운영자에게만 제한된 `admin-write.enabled=true`를 반영해 controlled restart와 schema·health 확인을 마친다. 승인 장치를 `INACTIVE`로 둔 채 credential test를 통과시킨 뒤 관리자가 `ACTIVE`로 전환하고 실제 교사의 공식 출석 카드를 한 번 태깅한다. **`tag_event_log`와 필요하면 `attendance_record`를 함께 저장하는 첫 권위 있는 체크인 트랜잭션의 commit이 point of no return이다.**
20. 저장된 구성원·부서·날짜·정책 구간, 이벤트 응답과 장치 표시를 확인한 뒤 나머지 장치를 활성화한다.
21. 과거 `SCHEDULED` 날짜가 없고 자동 마감 동시성 시험이 통과했음을 재확인한다. `scheduler.enabled=true`를 반영한 controlled restart와 schema·health 확인 후 스케줄러 실행 상태를 검증한다.
22. `cutover_writer` 권한을 회수하고 의도한 정상 운영 flag 조합으로 마지막 controlled restart와 smoke test를 수행한 뒤 일반 관리자 쓰기를 재개하고 컷오버 완료 시각을 기록한다.
23. 기존 `member`는 운영 기준 테이블로 유지하고 세 레거시 테이블의 DML은 차단한다. 컷오버 직전 생성한 기존 네 테이블의 immutable dump도 변경하지 않고 보관한다.

초기 전환은 애플리케이션 시작과 migration 실행을 분리한다. 첫 권위 있는 체크인 트랜잭션이 commit되기 전까지 신규 설정은 삭제하지 않고 격리한 채 안전 릴리스가 무시할 수 있다. 장치 인증 시 별도로 갱신되는 `device.last_seen_at`과 현재 key의 credential-test 성공 증거는 재구성 가능한 운영 상태이므로 이 경계에 포함하지 않는다. `tag_event_log` 또는 `attendance_record`가 권위 있는 체크인 결과로 commit된 뒤에는 출석 성공 여부와 무관하게 구버전 앱으로 단순 복귀할 수 없다.

MVP feature flag는 process 시작 시 환경 또는 외부 Spring 설정에서 읽고 실행 중에는 바뀌지 않는다. 위 단계의 활성화·비활성화는 모두 설정 변경 후 controlled restart를 뜻한다. `device-api.enabled=false`는 장치 인증과 `last_seen_at` 갱신보다 먼저 요청을 차단하므로 전체 쓰기 차단 단계에서 telemetry도 변하지 않는다.

---

## 10. 검증

### 10.1 필수 검증 행렬

| 검증 대상 | 합격 기준 |
|---|---|
| 빈 DB | V001~V017만으로 전체 신규 스키마 생성 |
| 운영 복제본 | baseline 0 이후 V001~V017 성공 |
| `member` 채택 | 원본 PK·행·sequence·레거시 컬럼 보존, 기록된 빈 전화번호 정규화만 예외, 승인 행만 활성화 |
| 전체 release | 고정 artifact에서 승인된 `flyway_target_version`까지 적용되고 checksum 일치 |
| Flyway 상태 | 실패·pending·checksum 불일치 없음 |
| DB 객체 | 예상 FK·CHECK·부분 유일 인덱스·함수·trigger가 이름과 정의까지 일치 |
| 앱 재시작 | 테이블 DROP, 샘플 insert, row count 변화 없음 |
| 승인 구성원 | manifest 승인 수와 활성 `member`·활성 소속 수 일치, 기존 PK 보존 |
| NFC 카드 | 승인된 유효 UID만 존재, 활성 중복 없음 |
| 계정 | 공개 샘플 계정·해시 없음, 승인 역할만 존재 |
| 부서 경계 | 부서가 다른 정책·소속·장치·출석 참조 0건 |
| 출석 기록 | 대상자 없는 기록 0건, 교사·날짜 중복 0건 |
| 자동 마감 | 컷오버 전 과거 `SCHEDULED` 날짜 0건 |
| 레거시 격리 | 컷오버 전 신규 날짜·기록 0건, 세 레거시 테이블 DML 권한 없음 |
| 통계 | 신규 `FINALIZED` 날짜만 분모로 사용하고 레거시 query와 분리 |
| 복구 | 격리 DB 복원 성공, 목표 복구 시간 4시간 이내 |

### 10.2 Flyway 상태

```sql
SELECT installed_rank,
       version,
       description,
       type,
       success,
       installed_on
FROM public.flyway_schema_history
ORDER BY installed_rank;

SELECT *
FROM public.flyway_schema_history
WHERE success = FALSE;
```

위 SQL만으로 pending이나 checksum 불일치를 검출할 수 없다. 운영 runner의 `flyway validate` 성공과 `flyway info`의 pending 0건을 함께 합격 조건으로 사용한다.

### 10.3 핵심 무결성

```sql
SELECT ar.*
FROM attendance_record AS ar
LEFT JOIN attendance_target AS atg
  ON atg.attendance_day_id = ar.attendance_day_id
 AND atg.member_id = ar.member_id
WHERE atg.attendance_day_id IS NULL;

SELECT attendance_day_id, member_id, count(*)
FROM attendance_record
GROUP BY attendance_day_id, member_id
HAVING count(*) > 1;

SELECT nfc_card_id, count(*)
FROM nfc_card_assignment
WHERE unassigned_at IS NULL
GROUP BY nfc_card_id
HAVING count(*) > 1;

SELECT member_id, count(*)
FROM nfc_card_assignment
WHERE unassigned_at IS NULL
GROUP BY member_id
HAVING count(*) > 1;

SELECT ar.id
FROM attendance_record AS ar
JOIN attendance_target AS atg
  ON atg.attendance_day_id = ar.attendance_day_id
 AND atg.member_id = ar.member_id
WHERE atg.is_target = FALSE;

SELECT ar.id
FROM attendance_record AS ar
JOIN attendance_day AS ad
  ON ad.id = ar.attendance_day_id
JOIN attendance_band AS ab
  ON ab.id = ar.attendance_band_id
WHERE ar.policy_version_id <> ad.policy_version_id
   OR ab.policy_version_id <> ar.policy_version_id
   OR ab.parent_status <> ar.status;

SELECT ad.id
FROM attendance_day AS ad
JOIN attendance_policy_version AS apv
  ON apv.id = ad.policy_version_id
WHERE apv.status <> 'PUBLISHED';

SELECT ad.id, count(ar.id)
FROM attendance_day AS ad
JOIN attendance_record AS ar
  ON ar.attendance_day_id = ad.id
WHERE ad.status = 'CANCELED'
GROUP BY ad.id;
```

모든 조회 결과는 0건이어야 한다. 활성 소속, 활성 부서 권한과 활성 카드 연결의 중복도 같은 방식으로 확인한다.

### 10.4 부서 경계

```sql
SELECT ad.id,
       ad.department_id AS day_department,
       apv.department_id AS policy_department
FROM attendance_day AS ad
JOIN attendance_policy_version AS apv
  ON apv.id = ad.policy_version_id
WHERE ad.department_id <> apv.department_id;

SELECT atg.attendance_day_id,
       atg.member_id,
       ad.department_id AS day_department,
       dm.department_id AS membership_department
FROM attendance_target AS atg
JOIN attendance_day AS ad
  ON ad.id = atg.attendance_day_id
JOIN department_membership AS dm
  ON dm.id = atg.membership_id
WHERE ad.department_id <> dm.department_id
   OR atg.member_id <> dm.member_id;
```

### 10.5 레거시 격리와 공식 통계 경계

```sql
-- YYYY-MM-DD를 승인된 컷오버 날짜로 교체한다.
SELECT *
FROM attendance_day
WHERE attendance_date < DATE 'YYYY-MM-DD';

SELECT ar.*
FROM attendance_record AS ar
JOIN attendance_day AS ad
  ON ad.id = ar.attendance_day_id
WHERE ad.attendance_date < DATE 'YYYY-MM-DD';
```

두 결과는 모두 0건이어야 한다. 애플리케이션 통합 테스트에서는 과거 날짜 등록 요청이 거부되고, 레거시 조회 결과가 공식 통계 query에 포함되지 않는지도 검증한다.

### 10.6 자동 마감 안전성

```sql
SELECT *
FROM attendance_day
WHERE attendance_date <
      (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::date
  AND status = 'SCHEDULED';
```

스케줄러 활성화 직전 결과는 0건이어야 한다. 별도 PostgreSQL 통합 테스트에서 다음을 추가로 통과해야 한다.

- 같은 날짜를 두 worker가 동시에 마감해도 교사별 `ABSENT`와 마감 감사 로그가 각각 한 건만 생성됨
- NFC 저장과 자동 마감이 같은 `attendance_day` 잠금 순서를 사용함
- 중간 강제 오류 시 결석 생성과 `FINALIZED` 변경이 모두 rollback됨
- 서버 재기동 후 누락된 과거 `SCHEDULED` 날짜가 다시 처리됨
- 대상자 수와 `PRESENT + LATE + ABSENT` 합계가 일치함

### 10.7 제약과 인덱스 검증

- `pg_constraint`, `pg_indexes`, `pg_trigger`, `pg_proc`에서 예상 객체의 이름과 정의를 확인한다.
- 신규 FK의 삭제 동작이 `ON DELETE RESTRICT`인지 확인한다.
- 잘못된 부서 FK, 중복 활성 소속·카드·권한, 대상자 없는 기록을 실제로 insert/update해 DB가 거부하는 PostgreSQL 통합 테스트를 둔다.
- 발행 정책 수정·삭제, 미발행 정책 선택, 다른 정책의 구간 저장, 비대상자 기록과 기록이 있는 날짜 취소가 서비스에서 거부되는 부정 테스트를 둔다.
- 오늘 대시보드, 과거 `SCHEDULED` 탐색, 개인 통계와 최근 태깅 조회에 필요한 인덱스의 컬럼 순서와 부분 조건을 확인한다.
- 작은 fixture에서 PostgreSQL이 sequential scan을 선택하는 것은 실패로 보지 않고, 예상 인덱스의 존재와 조회 결과 정확성을 우선 검증한다.

### 10.8 smoke test

- 시스템 관리자 로그인과 비밀번호 재설정
- 부서 관리자에게 자기 부서만 노출
- 교사 추가, NFC 카드 연결, 부서 제외 이력 보존
- 정책 발행과 출석 대상 날짜 생성
- 정상 출석, 지각, 중복 태깅과 미등록 UID 응답
- 자동 마감 후 기록 없는 대상자만 결석
- 수동 정정 사유와 감사 로그
- 앱 두 번 재시작 후 데이터 불변

---

## 11. 실패와 롤백

### 11.1 금지 사항

- 운영 DB에서 `flyway clean` 실행
- 적용된 migration 파일 삭제 또는 수정
- `flyway_schema_history` 수동 수정
- 실패 원인을 확인하지 않은 `repair`
- 검증 실패 상태에서 장치 API나 스케줄러 활성화
- 신규 쓰기 이후 구버전 앱을 같은 DB에 단순 재연결

### 11.2 단계별 대응

| 실패 시점 | 대응 |
|---|---|
| 안전 릴리스 전 | 기존 앱 유지, 원인 수정 |
| 안전 릴리스 실패 | 파괴적 구버전으로 복귀하지 않고 `SPRING_SQL_INIT_MODE=never`를 강제한 상태에서 forward fix |
| Flyway transactional migration 실패 | 신규 앱 시작 금지, 완전 rollback 확인 후 아직 성공 적용되지 않은 해당 migration 파일을 수정해 재실행 |
| 비트랜잭션 작업 일부 반영 | 자동 재실행 금지, 백업 복원 또는 수동 대사·정리 후 제한적으로 `repair` 검토 |
| migration 성공, 첫 권위 있는 체크인 트랜잭션 commit 전 | `LEGACY_OPERATIONAL`은 `app_runtime` 차단 → 신규 설정 격리 → `legacy_writer` 복구 → SQL init `never` 재확인 → 안전 릴리스 시작. `NEW_OR_SAMPLE`은 서비스를 계속 중지하고 forward fix |
| importer 실패, 첫 권위 있는 체크인 트랜잭션 commit 전 | importer transaction rollback, 거부 보고서 수정 후 재실행 |
| 읽기 전용 smoke test 실패 | 장치·스케줄러를 계속 차단하고 안전 릴리스로 복귀 |
| 첫 권위 있는 체크인 트랜잭션 commit 이후 실패 | 우선 forward fix, 불가능하면 컷오버 백업 전체 복원과 이후 데이터를 수기 원본으로 재입력·대사 |

성공 적용된 migration의 결함은 적용 파일을 고치지 않고 새 보정 migration으로 처리한다. 반대로 완전히 rollback되어 history에 성공 기록이 없는 실패 migration은 그 실패 파일 자체를 수정하지 않으면 다음 실행에서도 같은 버전에서 막힌다.

최초 시스템 설정과 importer 결과는 첫 권위 있는 체크인 트랜잭션 commit 전까지 안전 릴리스에서 무시하고 이후 재사용하거나 재생성할 수 있다. rollback 과정에서 신규 설정을 즉시 삭제하지 않는다. 권위 있는 체크인 결과로 `tag_event_log` 또는 `attendance_record`가 하나라도 commit된 후에는 단순 애플리케이션 롤백만으로 데이터 일관성을 회복할 수 없다. `device.last_seen_at` 또는 credential-test 성공 증거만 갱신된 상태는 이 경계 이전이다. 이 시점의 전체 복원은 컷오버 이후 데이터를 포기하거나 별도로 대사하는 작업이며, 실행 전에 RPO와 승인자를 기록한다.

### 11.3 즉시 중단 조건

- 대상 DB 식별정보 불일치
- 백업 복원 실패
- migration checksum 또는 schema drift
- manifest 승인 건수와 import 결과 불일치
- 예상하지 못한 거부 행 발생
- 부서 경계 위반
- 대상자 없는 출석 기록
- 과거 합성 결석 발생
- 실제 카드가 다른 교사에게 연결됨
- 테스트 중 기존 네 테이블의 예상하지 못한 변경. 승인된 `member.active`·`updated_at`·comment 추가, 기록된 빈 전화번호의 `NULL` 정정과 두 출석 테이블 FK의 `CASCADE → RESTRICT`만 예외

목표 복구 시간은 프로젝트 정의의 NFR-08에 따라 4시간이다.

---

## 12. 완료 조건

### 12.1 M1 구현 완료

M1은 신규 기능 전체가 아니라 안전한 DB 변경 기반을 완성하는 단계다. 다음 조건을 모두 만족해야 M1을 완료한 것으로 본다.

- [ ] 운영 시작 경로에서 파괴적 `schema.sql`과 샘플 `data.sql`이 실행되지 않음
- [ ] 개발·테스트·운영 데이터소스가 분리됨
- [ ] 빈 PostgreSQL DB를 V001~V017으로 재구성함
- [ ] 대표 레거시 fixture에서 baseline 0과 migration을 두 번 재현함
- [ ] 실제 운영 DB가 있다면 승인된 복제본에서도 같은 절차를 재현함
- [ ] baseline 결과에 version 0 `BASELINE`과 V001~V017이 모두 존재함
- [ ] 적용된 migration의 checksum과 `validate`가 정상임
- [ ] 앱 연속 두 번 재시작 후 schema와 데이터가 변하지 않음
- [ ] 기존 `member` 물리 삭제 API·Mapper가 제거되고 관련 FK가 `ON DELETE RESTRICT`임
- [ ] 운영 DB에서 `clean`과 자동 baseline이 비활성화됨
- [ ] 샘플 계정·구성원·카드를 운영 migration이 생성하거나 활성화하지 않음
- [ ] fresh/legacy 두 경로의 최종 `member` schema가 일치하고 레거시 PK·sequence가 보존됨
- [ ] migration owner와 app runtime 역할이 분리되고 runtime에 DDL 권한이 없음
- [ ] 애플리케이션 artifact의 지원 schema version과 history가 다르면 runtime 기동이 거부됨
- [ ] FK·CHECK·부분 유일 인덱스·trigger의 catalog 검증과 부정 테스트를 통과함
- [ ] 백업을 격리 DB에 복원하는 절차를 한 번 이상 검증함

### 12.2 실제 운영 컷오버 완료

다음 조건은 관리자 웹, 출석 도메인과 장치 API가 구현된 뒤 실제 운영 전환 시 적용한다.

- [ ] 승인된 기존 `member`만 활성화되고 승인된 카드·계정만 이관됨
- [ ] 모든 제외 행에 원문 식별자와 사유가 기록됨
- [ ] 공개 샘플 계정과 비밀번호가 운영에 존재하지 않음
- [ ] 레거시 출석·로그가 신규 출석·이벤트 테이블에 이관되지 않음
- [ ] 컷오버 이전 신규 출석 날짜·기록이 0건이며 과거 날짜 등록이 거부됨
- [ ] 신규 공식 통계가 신규 `FINALIZED` 날짜만 사용함
- [ ] 기존 `member`는 운영 기준으로 보존되고 세 레거시 테이블의 runtime DML 권한이 회수됨
- [ ] 최초 관리자·부서·권한·장치·정책·오늘 날짜·대상자 bootstrap을 확인함
- [ ] 장치 API와 스케줄러 비활성 상태의 읽기 전용 smoke test를 통과함
- [ ] 실제 카드 한 건의 제한된 통합 시험을 통과함
- [ ] 자동 마감 동시 실행·부분 실패·재기동 복구 시험을 통과한 뒤 스케줄러를 활성화함
- [ ] 백업을 격리 DB에 복원하고 4시간 RTO를 충족함
- [ ] 실행자와 승인자가 컷오버 결과에 서명함

---

## 13. 운영 이관 전에 남은 결정

다음 항목은 스키마 파일 작성을 막지는 않지만 하나라도 미확정이면 실제 운영 이관과 컷오버를 시작할 수 없다.

1. 대상 DB가 샘플인지 실제 운영 DB인지
2. 기존 `member`별 소속 부서와 활성화 승인
3. 이관할 최소 개인정보 범위
4. 이관할 기존 계정과 신규 역할
5. 최초 시스템 관리자 생성·비밀번호 전달 방식
6. 최초 부서·부서 관리자·장치·정책을 준비하는 bootstrap 담당자
7. 컷오버 날짜와 공식 통계 시작일
8. 레거시 `timestamp without time zone`을 참고 화면에서 표시하는 방식
9. 레거시 출석·로그를 조회 가능하게 유지할 기간
10. 백업 위치, 보유기간, 암호화와 복원 담당자
11. 고정할 Flyway runner 종류와 버전
12. 컷오버 실행자, go/no-go 승인자와 장애 시 최종 의사결정자

---

## 14. 참고 자료

- [Spring Boot Database Initialization](https://docs.spring.io/spring-boot/how-to/data-initialization.html)
- [Flyway Baselines](https://documentation.red-gate.com/fd/baselines-273973441.html)
- [Flyway Baseline Version](https://documentation.red-gate.com/fd/flyway-baseline-version-setting-277578975.html)
- [Flyway baselineOnMigrate](https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-baseline-on-migrate-setting)
- [Flyway Migration Transaction Handling](https://documentation.red-gate.com/fd/migration-transaction-handling-273973399.html)
