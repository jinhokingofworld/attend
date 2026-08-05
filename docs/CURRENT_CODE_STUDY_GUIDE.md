# 현재 코드 학습 순서와 데이터 흐름

이 문서는 레거시 설계가 아니라 **현재 작업 트리에서 실제로 실행 가능한 코드**를 기준으로 한다.

- 현재 관리자 경로: `/admin/**`
- 현재 장치 API: `/api/v1/device/**`
- 현재 펌웨어: `firmware/attend-nfc/attend-nfc.ino`
- 현재 DB 스키마: Flyway `V001`~`V008`
- 제외 대상: `/member`, `/attendance`, `/api/attendance`, 루트 `RFID.ino`

> 코드가 존재한다고 모든 기능이 실행되는 것은 아니다. 기본 설정에서 관리자 쓰기,
> 장치 API, 자동 마감 스케줄러는 모두 꺼져 있다.

| 기능 | 설정 | 기본값 |
|---|---|---:|
| 관리자 쓰기 | `attendance.admin.write-enabled` | `false` |
| 장치 API | `device-api.enabled` | `false` |
| 자동 마감 | `attendance.scheduler.enabled` | `false` |
| 관리자 태깅 이력 | `attendance.admin.show-tag-logs` | `false` |

---

## 1. 전체 학습 순서

DB부터 시작하되, DB만 읽고 끝내면 안 된다. 부서 권한 재검증, 잠금 순서, 출석 판정,
멱등성은 Application Service에 있기 때문이다.

```mermaid
flowchart TD
    S0["0. 현재/레거시 경계 확정<br/>SecurityConfig · feature flags"]
    S1["1. 실행 구조 파악<br/>build.gradle · application.properties"]
    S2["2. DB 스키마 파악<br/>Flyway V001~V008"]
    S3["3. 인증·권한 파악<br/>access + Security filter chain"]
    S4["4. 조직·카드 파악<br/>member · membership · card assignment"]
    S5["5. 정책·출석일 파악<br/>policy · day · target · record"]
    S6["6. NFC 실시간 흐름 파악<br/>firmware · device API · idempotency"]
    S7["7. 사후 처리 파악<br/>manual correction · finalization"]
    S8["8. 운영 경계 파악<br/>migration · health · backup · restore"]
    S9["9. 테스트로 가설 검증<br/>security · isolation · concurrency"]

    S0 --> S1 --> S2 --> S3 --> S4 --> S5 --> S6 --> S7 --> S8 --> S9
```

### 단계별 읽기 목록

#### 0단계 — 현재 코드의 경계

1. [SecurityConfig.java](../src/main/java/com/example/attend/config/SecurityConfig.java)
2. [application.properties](../src/main/resources/application.properties)
3. [MenuController.java](../src/main/java/com/example/attend/controller/MenuController.java)
4. [현재 펌웨어](../firmware/attend-nfc/attend-nfc.ino)
5. [배포 금지 레거시 펌웨어](../RFID.ino)

확인할 질문:

- 어떤 URL이 실제로 허용되는가?
- 어떤 기능이 feature flag에 의해 차단되는가?
- 파일이 남아 있는 것과 실행 가능한 것은 어떻게 다른가?

#### 1단계 — 실행 구조

1. [build.gradle](../build.gradle)
2. [AttendApplication.java](../src/main/java/com/example/attend/AttendApplication.java)
3. [TimeConfiguration.java](../src/main/java/com/example/attend/common/config/TimeConfiguration.java)
4. [CorrelationIdFilter.java](../src/main/java/com/example/attend/operations/logging/CorrelationIdFilter.java)

확인할 질문:

- Java 21, Spring Boot, MyBatis, PostgreSQL이 어디서 연결되는가?
- 요청이 비동기 queue가 아니라 DB commit을 기다리는 동기 흐름이라는 근거는 무엇인가?
- 출석 업무 시간대와 서버 `Clock`은 어떻게 주입되는가?

#### 2단계 — DB 스키마

아래 순서대로 migration을 읽는다.

1. [V001 — member 도입/레거시 채택](../src/main/resources/db/migration/V001__adopt_or_create_member.sql)
2. [V002 — department/account](../src/main/resources/db/migration/V002__create_organization_and_accounts.sql)
3. [V003 — membership/card/device](../src/main/resources/db/migration/V003__create_membership_card_and_device.sql)
4. [V004 — attendance policy](../src/main/resources/db/migration/V004__create_attendance_policy.sql)
5. [V005 — attendance day/target/record](../src/main/resources/db/migration/V005__create_attendance_domain.sql)
6. [V006 — tag event/audit](../src/main/resources/db/migration/V006__create_event_and_audit_log.sql)
7. [V007 — scope FK/index](../src/main/resources/db/migration/V007__add_indexes_and_scope_guards.sql)
8. [V008 — updated_at trigger](../src/main/resources/db/migration/V008__add_updated_at_triggers.sql)

확인할 질문:

- 삭제 대신 `ended_at`, `revoked_at`, `unassigned_at`을 쓰는 테이블은 무엇인가?
- 부서 간 잘못된 연결을 막는 composite FK는 무엇인가?
- 애플리케이션 검증 없이 DB만으로 막을 수 없는 규칙은 무엇인가?

#### 3단계 — 인증·권한

1. [AccountUserDetailsService.java](../src/main/java/com/example/attend/access/security/AccountUserDetailsService.java)
2. [AccountSecurityMapper.xml](../src/main/resources/com/example/attend/access/infrastructure/mybatis/AccountSecurityMapper.xml)
3. [DatabaseSystemAuthorization.java](../src/main/java/com/example/attend/access/application/DatabaseSystemAuthorization.java)
4. [DatabaseDepartmentAuthorization.java](../src/main/java/com/example/attend/access/application/DatabaseDepartmentAuthorization.java)
5. [DepartmentAuthorizationMapper.xml](../src/main/resources/com/example/attend/access/infrastructure/mybatis/DepartmentAuthorizationMapper.xml)
6. [CredentialTokenService.java](../src/main/java/com/example/attend/access/application/CredentialTokenService.java)

확인할 질문:

- URL 권한과 정확한 부서 권한이 왜 두 번 검사되는가?
- `SYSTEM_ADMIN`이 부서 관리자 권한을 자동으로 갖지 않는 이유는 무엇인가?
- 초대/재설정 raw token이 DB에 저장되지 않는 흐름은 어떻게 구성되는가?

#### 4단계 — 조직·교사·카드

1. [TeacherRosterService.java](../src/main/java/com/example/attend/organization/application/TeacherRosterService.java)
2. [CardManagementService.java](../src/main/java/com/example/attend/organization/application/CardManagementService.java)
3. [DepartmentMembershipExclusionService.java](../src/main/java/com/example/attend/attendance/application/DepartmentMembershipExclusionService.java)
4. [OrganizationMapper.xml](../src/main/resources/com/example/attend/organization/infrastructure/mybatis/OrganizationMapper.xml)

확인할 질문:

- `member`, `department_membership`, `nfc_card_assignment`을 분리한 이유는 무엇인가?
- 카드 상태와 assignment 이력이 각각 무엇을 표현하는가?
- 교사를 제외할 때 물리 삭제 대신 어떤 상태들이 종료되는가?

#### 5단계 — 정책·출석일·기록

1. [AttendancePolicyService.java](../src/main/java/com/example/attend/attendance/application/AttendancePolicyService.java)
2. [AttendancePolicy.java](../src/main/java/com/example/attend/attendance/domain/AttendancePolicy.java)
3. [AttendanceDayService.java](../src/main/java/com/example/attend/attendance/application/AttendanceDayService.java)
4. [AttendanceTargetService.java](../src/main/java/com/example/attend/attendance/application/AttendanceTargetService.java)
5. [AttendanceCorrectionService.java](../src/main/java/com/example/attend/attendance/application/AttendanceCorrectionService.java)
6. [AttendancePolicyMapper.xml](../src/main/resources/com/example/attend/attendance/infrastructure/mybatis/AttendancePolicyMapper.xml)
7. [AttendanceDayMapper.xml](../src/main/resources/com/example/attend/attendance/infrastructure/mybatis/AttendanceDayMapper.xml)
8. [AttendanceRecordMapper.xml](../src/main/resources/com/example/attend/attendance/infrastructure/mybatis/AttendanceRecordMapper.xml)

확인할 질문:

- 정책을 수정하지 않고 버전으로 발행하는 이유는 무엇인가?
- `attendance_target`이 단순 조회 결과가 아니라 snapshot인 이유는 무엇인가?
- `attendance_record`가 policy/band label을 snapshot으로 저장하는 이유는 무엇인가?

#### 6단계 — NFC 장치와 실시간 체크인

1. [현재 펌웨어](../firmware/attend-nfc/attend-nfc.ino)
2. [DeviceApiController.java](../src/main/java/com/example/attend/device/web/DeviceApiController.java)
3. [DeviceCheckInRequestParser.java](../src/main/java/com/example/attend/device/web/DeviceCheckInRequestParser.java)
4. [DeviceAuthenticationService.java](../src/main/java/com/example/attend/device/application/DeviceAuthenticationService.java)
5. [DeviceCheckInService.java](../src/main/java/com/example/attend/device/application/DeviceCheckInService.java)
6. [DeviceApiMapper.xml](../src/main/resources/com/example/attend/device/infrastructure/mybatis/DeviceApiMapper.xml)
7. [DeviceCheckInMapper.xml](../src/main/resources/com/example/attend/device/infrastructure/mybatis/DeviceCheckInMapper.xml)
8. [장치 OpenAPI](device-api.yaml)

확인할 질문:

- `requestId`를 펌웨어가 생성하고 서버가 저장하는 이유는 무엇인가?
- 인증 성공의 `last_seen_at`과 출석 transaction이 왜 분리되어 있는가?
- 최초 HTTP 응답 전체를 `tag_event_log`에 저장하는 이유는 무엇인가?

#### 7단계 — 자동 마감·통계

1. [AttendanceFinalizationScheduler.java](../src/main/java/com/example/attend/attendance/scheduler/AttendanceFinalizationScheduler.java)
2. [FinalizeAttendanceDayService.java](../src/main/java/com/example/attend/attendance/application/FinalizeAttendanceDayService.java)
3. [AttendanceStatisticsService.java](../src/main/java/com/example/attend/attendance/application/AttendanceStatisticsService.java)
4. [AttendanceStatisticsMapper.xml](../src/main/resources/com/example/attend/attendance/infrastructure/mybatis/AttendanceStatisticsMapper.xml)

확인할 질문:

- 자동 결석은 왜 당일 마감 시각 직후가 아니라 다음 날짜부터 생성되는가?
- 한 날짜의 마감 실패가 다른 날짜를 막지 않는 이유는 무엇인가?
- 공식 통계가 `FINALIZED` 날짜만 집계하는 이유는 무엇인가?

#### 8단계 — 운영 경계

1. [DatabasePreflightInspector.java](../src/main/java/com/example/attend/database/DatabasePreflightInspector.java)
2. [DatabaseMigrationRunner.java](../src/main/java/com/example/attend/database/DatabaseMigrationRunner.java)
3. [SchemaVersionGuard.java](../src/main/java/com/example/attend/database/SchemaVersionGuard.java)
4. [RuntimeDatabasePrivilegeGuard.java](../src/main/java/com/example/attend/database/RuntimeDatabasePrivilegeGuard.java)
5. [SystemAdminBootstrapCli.java](../src/main/java/com/example/attend/access/bootstrap/SystemAdminBootstrapCli.java)
6. [backup.sh](../ops/backup/backup.sh)
7. [restore-verify.sh](../ops/backup/restore-verify.sh)

---

## 2. 핵심 DB 관계

```mermaid
erDiagram
    ACCOUNT ||--o{ ACCOUNT_DEPARTMENT_ROLE : receives
    DEPARTMENT ||--o{ ACCOUNT_DEPARTMENT_ROLE : grants
    ACCOUNT ||--o{ ACCOUNT_CREDENTIAL_TOKEN : owns

    DEPARTMENT ||--o{ DEPARTMENT_MEMBERSHIP : contains
    MEMBER ||--o{ DEPARTMENT_MEMBERSHIP : joins

    DEPARTMENT_MEMBERSHIP ||--o{ NFC_CARD_ASSIGNMENT : owns
    MEMBER ||--o{ NFC_CARD_ASSIGNMENT : receives
    NFC_CARD ||--o{ NFC_CARD_ASSIGNMENT : assigned_by

    DEPARTMENT ||--o{ DEVICE : operates

    DEPARTMENT ||--o{ ATTENDANCE_POLICY_VERSION : defines
    ATTENDANCE_POLICY_VERSION ||--|{ ATTENDANCE_BAND : contains

    DEPARTMENT ||--o{ ATTENDANCE_DAY : schedules
    ATTENDANCE_POLICY_VERSION ||--o{ ATTENDANCE_DAY : freezes
    ATTENDANCE_DAY ||--o{ ATTENDANCE_TARGET : snapshots
    DEPARTMENT_MEMBERSHIP ||--o{ ATTENDANCE_TARGET : sourced_from
    MEMBER ||--o{ ATTENDANCE_TARGET : targets

    ATTENDANCE_DAY ||--o{ ATTENDANCE_RECORD : records
    MEMBER ||--o{ ATTENDANCE_RECORD : receives

    DEVICE ||--o{ TAG_EVENT_LOG : receives
    NFC_CARD o|--o{ TAG_EVENT_LOG : resolves_to
    ATTENDANCE_DAY o|--o{ TAG_EVENT_LOG : evaluated_for
    ATTENDANCE_RECORD o|--o{ TAG_EVENT_LOG : creates_or_reuses

    DEPARTMENT ||--o{ AUDIT_LOG : scopes
    ACCOUNT o|--o{ AUDIT_LOG : acts
    DEVICE o|--o{ AUDIT_LOG : acts
    ATTENDANCE_DAY o|--o{ AUDIT_LOG : scopes
```

### 테이블을 읽는 기준

| 영역 | 사실의 원본 |
|---|---|
| 로그인·계정 상태 | `account` |
| 부서 관리자 권한 | `account_department_role` |
| 교사 원본 | `member` |
| 부서 소속 이력 | `department_membership` |
| 카드 상태 | `nfc_card` |
| 카드-교사 연결 이력 | `nfc_card_assignment` |
| 판정 규칙 | `attendance_policy_version`, `attendance_band` |
| 날짜별 대상 snapshot | `attendance_day`, `attendance_target` |
| 공식 출석 결과 | `attendance_record` |
| NFC 요청·멱등 응답 | `tag_event_log` |
| 관리자·시스템 변경 이력 | `audit_log` |

---

## 3. 실행 진입점 전체 지도

```mermaid
flowchart LR
    Browser["관리자 브라우저"]
    Firmware["NFC 펌웨어"]
    Scheduler["자동 마감 스케줄러"]
    Operator["운영자 CLI"]

    Proxy["Caddy HTTPS<br/>trusted proxy headers"]
    WebSecurity["웹 SecurityFilterChain<br/>session · CSRF · role"]
    DeviceSecurity["장치 SecurityFilterChain<br/>flag · rate limit · HMAC"]

    WebController["관리자 MVC Controller"]
    DeviceController["DeviceApiController"]
    AppService["Application Service<br/>transaction · authorization · locks"]
    Mapper["MyBatis Mapper XML"]
    DB[("PostgreSQL")]

    Browser --> Proxy --> WebSecurity --> WebController --> AppService
    Firmware --> Proxy --> DeviceSecurity --> DeviceController --> AppService
    Scheduler --> AppService
    Operator --> Migration["Migration / Bootstrap / Backup"] --> DB
    AppService --> Mapper --> DB
```

### 공통 관리자 쓰기 구조

```mermaid
flowchart TD
    Request["Browser POST + CSRF"]
    RouteRole["URL role 확인"]
    WriteFlag{"ADMIN_WRITE_ENABLED?"}
    DbAuth["DB에서 현재 account/role/department 재검증"]
    DeptLock["department 행 잠금"]
    DomainLock["업무 행 잠금 및 규칙 재검증"]
    Mutation["업무 테이블 INSERT/UPDATE"]
    Audit["audit_log INSERT"]
    Commit["같은 transaction commit"]
    Redirect["302 redirect + flash message"]
    Disabled["503 Service Unavailable"]

    Request --> RouteRole --> WriteFlag
    WriteFlag -- "아니오" --> Disabled
    WriteFlag -- "예" --> DbAuth --> DeptLock --> DomainLock --> Mutation --> Audit --> Commit --> Redirect
```

> 대부분의 관리자 변경은 위 흐름을 따르지만, 현재 `replaceDraft()`는 정책 초안을
> 변경하면서 `audit_log`를 작성하지 않는다.

---

## 4. 로그인·계정 데이터 흐름

### 로그인과 워크스페이스 선택

```mermaid
sequenceDiagram
    actor User as 관리자
    participant Browser as Browser
    participant Security as Spring Security
    participant Accounts as AccountUserDetailsService
    participant DB as PostgreSQL
    participant Home as AdminHomeController

    User->>Browser: 사용자명/비밀번호 입력
    Browser->>Security: POST /authentication
    Security->>Security: IP+username rate limit
    Security->>Accounts: loadUserByUsername
    Accounts->>DB: ACTIVE account + role 조회
    DB-->>Accounts: password hash + role projection
    Accounts-->>Security: AccountPrincipal
    Security->>Security: BCrypt 검증 + session ID 교체
    Security-->>Browser: redirect /admin
    Browser->>Home: GET /admin
    Home->>DB: 활성 department workspace 조회
    alt 일반 계정의 workspace가 1개
        Home-->>Browser: 해당 department dashboard로 이동
    else system admin 또는 workspace가 여러 개
        Home-->>Browser: workspace 선택 화면
    end
```

### 계정 초대·비밀번호 설정

```mermaid
sequenceDiagram
    actor Admin as 시스템 관리자
    actor User as 초대 사용자
    participant Web as SystemAdminController
    participant Token as CredentialTokenService
    participant DB as PostgreSQL
    participant Public as CredentialPageController

    Admin->>Web: PENDING_SETUP 계정 생성
    Web->>DB: account INSERT
    Web->>DB: ACCOUNT_CREATED audit INSERT
    Admin->>Web: invite 링크 발행
    Web->>Token: issue(INVITATION)
    Token->>DB: account lock + 기존 token revoke
    Token->>Token: 32-byte raw token + HMAC hash
    Token->>DB: hash/expiry만 INSERT
    Token-->>Admin: fragment 링크 1회 표시

    User->>Public: GET /account/setup#token=raw
    Public-->>User: JS가 fragment를 hidden field로 이동
    User->>Public: POST token + 새 비밀번호
    Public->>Token: consume(INVITATION)
    Token->>DB: token/account lock
    Token->>Token: 만료·상태·비밀번호 정책 검증
    Token->>DB: BCrypt hash + ACTIVE + consumed_at + audit
    Token-->>User: 설정 완료
```

### 계정 상태·역할 변화

```mermaid
stateDiagram-v2
    [*] --> PENDING_SETUP: 계정 생성
    PENDING_SETUP --> ACTIVE: 초대 token 소비
    ACTIVE --> DISABLED: 관리자 비활성화
    PENDING_SETUP --> DISABLED: 관리자 비활성화
    DISABLED --> PENDING_SETUP: password hash 없음
    DISABLED --> ACTIVE: password hash 있음

    state "부서 역할 이력" as RoleHistory {
        [*] --> Assigned: account_department_role INSERT
        Assigned --> Revoked: revoked_at 기록
    }
```

---

## 5. 장치 등록·활성화 흐름

```mermaid
sequenceDiagram
    actor Admin as 시스템 관리자
    participant Web as DeviceAdminController
    participant Service as DeviceManagementService
    participant DB as PostgreSQL
    participant Firmware as NFC Firmware
    participant API as DeviceApiController

    Admin->>Web: 장치 생성
    Web->>Service: create(department, code, name)
    Service->>DB: department lock
    Service->>Service: raw key 생성 + HMAC hash
    Service->>DB: INACTIVE device v1 + audit
    Service-->>Web: raw key
    Web-->>Admin: session 기반 1회 표시

    Admin->>Firmware: code/key 설정, provisioning mode=true
    Firmware->>API: POST /api/v1/device/credential-tests
    API->>DB: HMAC 인증 + last_seen_at
    API->>DB: INACTIVE/current version lock
    API->>DB: credential_tested_version/at UPDATE
    API-->>Firmware: CREDENTIAL_VALID

    Admin->>Web: activate
    Web->>Service: activate(deviceId)
    Service->>DB: 시험 증거 재검증
    Service->>DB: INACTIVE → ACTIVE + audit
    Admin->>Firmware: provisioning mode=false로 재배포
```

### 장치 상태 전이

```mermaid
stateDiagram-v2
    [*] --> INACTIVE: create v1
    INACTIVE --> INACTIVE: credential test evidence
    INACTIVE --> ACTIVE: activate
    ACTIVE --> INACTIVE: deactivate
    ACTIVE --> INACTIVE: rotate key + version 증가
    INACTIVE --> INACTIVE: rotate key + version 증가
    ACTIVE --> REVOKED: revoke
    INACTIVE --> REVOKED: revoke
    REVOKED --> [*]
```

---

## 6. 교사·소속·카드 흐름

### 교사 추가

```mermaid
flowchart LR
    Input["이름 · 전화 · 생년월일 · 선택 UID"]
    Validate["입력 검증"]
    Lock["권한 재확인 + department lock"]
    Member["member INSERT<br/>active=true"]
    Membership["department_membership INSERT"]
    HasCard{"UID 입력?"}
    Card["nfc_card INSERT IF ABSENT<br/>AVAILABLE → ACTIVE"]
    Assignment["nfc_card_assignment INSERT"]
    Audit["TEACHER_ADDED audit"]
    Commit["한 transaction commit"]

    Input --> Validate --> Lock --> Member --> Membership --> HasCard
    HasCard -- "예" --> Card --> Assignment --> Audit --> Commit
    HasCard -- "아니오" --> Audit
```

### 카드 연결·교체·종료

```mermaid
flowchart TD
    Start["활성 department membership"]
    Choice{"작업"}

    Connect["연결<br/>활성 assignment 없음 확인"]
    ActivateCard["AVAILABLE card → ACTIVE"]
    NewAssignment["assignment INSERT"]

    Replace["교체<br/>기존 assignment/card lock"]
    ActivateNew["새 card AVAILABLE → ACTIVE"]
    EndOld["기존 assignment 종료<br/>기존 card → AVAILABLE"]
    ReplaceAssignment["새 assignment INSERT"]

    Disconnect["연결 종료<br/>reason + disposition"]
    EndAssignment["assignment unassigned_at 기록"]
    Disposition["card → AVAILABLE / LOST / RETIRED"]

    Audit["audit_log"]

    Start --> Choice
    Choice -- "connect" --> Connect --> ActivateCard --> NewAssignment --> Audit
    Choice -- "replace" --> Replace --> ActivateNew --> EndOld --> ReplaceAssignment --> Audit
    Choice -- "disconnect" --> Disconnect --> EndAssignment --> Disposition --> Audit
```

### 부서에서 교사 제외

```mermaid
flowchart TD
    Request["교사 제외 요청<br/>reason + card disposition"]
    AuthLock["권한 재확인 + department lock"]
    MembershipLock["active membership lock"]
    CardLock["active assignment/card lock"]
    CardClose["assignment 종료<br/>card 상태 변경"]
    MembershipClose["membership ended_at 기록"]
    OtherMembership{"다른 active membership?"}
    DeactivateMember["member.active=false"]
    KeepMember["member.active 유지"]
    Audit["DEPARTMENT_MEMBERSHIP_ENDED audit"]

    Request --> AuthLock --> MembershipLock --> CardLock --> CardClose --> MembershipClose --> OtherMembership
    OtherMembership -- "없음" --> DeactivateMember --> Audit
    OtherMembership -- "있음" --> KeepMember --> Audit
```

> 현재 Controller는 `futureAttendanceDayIds`에 항상 빈 목록을 전달한다. 따라서 이미
> snapshot된 미래 `attendance_target`은 이 흐름에서 제외되지 않는다.

### 미등록 카드 등록함

```mermaid
flowchart LR
    Tag["UNKNOWN_UID 또는 INACTIVE_CARD 태깅"]
    Event["tag_event_log 저장"]
    Inbox["카드 등록함 조회<br/>UID별 event + masked UID"]
    Select["관리자가 event와 교사 선택"]
    Resolve["서버가 eventId로 raw UID 재조회"]
    Validate["같은 department · 아직 연결 가능"]
    Connect["공통 card connect transaction"]

    Tag --> Event --> Inbox --> Select --> Resolve --> Validate --> Connect
```

---

## 7. 정책·출석일·대상자 흐름

### 정책 생명주기

```mermaid
stateDiagram-v2
    [*] --> DRAFT: createDraft
    DRAFT --> DRAFT: replaceDraft
    DRAFT --> PUBLISHED: publish + 전체 band 검증
    PUBLISHED --> [*]: 애플리케이션에서 불변

    note right of DRAFT
      초안은 불완전한 band도 저장 가능
    end note

    note right of PUBLISHED
      첫 band PRESENT
      이후 band LATE
      upper_time 엄격 증가
    end note
```

### 출석일 생성과 대상 snapshot

```mermaid
flowchart TD
    Request["오늘 또는 미래 날짜 + PUBLISHED policy"]
    Guard["오늘이면 check-in 시작 전인지 확인"]
    Day["attendance_day INSERT<br/>status=SCHEDULED"]
    Memberships["현재 active membership + active member 조회"]
    Snapshot["attendance_target 일괄 INSERT<br/>membership_id snapshot"]
    Audit["ATTENDANCE_DAY_CREATED audit"]

    Request --> Guard --> Day --> Memberships --> Snapshot --> Audit
```

### 대상자 변경

```mermaid
flowchart TD
    Day["SCHEDULED attendance day"]
    Before{"check-in 시작 전?"}
    Action{"대상 변경"}
    Add["active membership 확인<br/>target INSERT 또는 재활성화"]
    RemoveGuard["기존 record 없음 확인"]
    Remove["is_target=false"]
    Audit["ATTENDANCE_TARGET_CHANGED audit"]
    Reject["업무 오류"]

    Day --> Before
    Before -- "아니오" --> Reject
    Before -- "예" --> Action
    Action -- "추가" --> Add --> Audit
    Action -- "제외" --> RemoveGuard --> Remove --> Audit
```

---

## 8. NFC 체크인 전체 데이터 흐름

### 정상·중복·멱등 처리

```mermaid
sequenceDiagram
    actor Person as 태깅 사용자
    participant FW as NFC Firmware
    participant Sec as Device Security Chain
    participant API as DeviceApiController
    participant Service as DeviceCheckInService
    participant DB as PostgreSQL

    Person->>FW: NFC 카드 태깅
    FW->>FW: 4/7/10-byte UID → 대문자 hex
    FW->>FW: boot random + counter → requestId
    FW->>Sec: HTTPS POST /api/v1/device/check-ins
    Sec->>Sec: API flag → IP rate limit → HMAC → device rate limit
    Sec->>DB: last_seen_at UPDATE (별도 transaction)
    Sec->>API: DevicePrincipal
    API->>DB: ACTIVE/current version 사전 조회
    API->>API: media type · 1 KiB · strict JSON 검증
    API->>Service: uid, requestId, server receivedAt

    Service->>DB: department lock → device lock
    Service->>DB: tag_event_log PROCESSING claim

    alt 같은 device/requestId가 이미 존재
        Service->>DB: 기존 event 조회
        alt 기존 UID와 같음
            DB-->>Service: 최초 HTTP status + canonical JSON
            Service-->>FW: 최초 응답 그대로 재현
        else 기존 UID와 다름
            Service-->>FW: REQUEST_ID_CONFLICT
        end
    else 새로운 event
        Service->>DB: card + active assignment + membership 조회
        Service->>DB: 오늘 attendance_day lock
        Service->>DB: active attendance_target lock
        Service->>DB: 고정 policy + bands 조회
        Service->>Service: server receivedAt으로 PRESENT/LATE 판정
        Service->>DB: 기존 attendance_record lock
        alt 기존 record 있음
            Service->>DB: event를 ALREADY_CHECKED_IN으로 완료
        else 기존 record 없음
            Service->>DB: source=NFC attendance_record INSERT
            Service->>DB: event를 CHECKED_IN 또는 LATE로 완료
        end
        Service-->>FW: DB에 저장된 canonical JSON
    end

    FW->>FW: 응답 code에 따라 LED 표시
```

### 체크인 판정 순서

```mermaid
flowchart TD
    Start["인증된 ACTIVE device + 검증된 JSON"]
    Claim{"device + requestId event claim 성공?"}
    Replay{"기존 UID와 동일?"}
    Stored["최초 저장 응답 재현"]
    Conflict["REQUEST_ID_CONFLICT"]

    Card{"nfc_card 존재?"}
    CardActive{"card ACTIVE?"}
    Membership{"같은 department의 active assignment/membership?"}
    Day{"오늘 SCHEDULED attendance_day?"}
    Target{"active attendance_target?"}
    Window{"policy 시간 구간 안?"}
    Record{"기존 attendance_record?"}

    Unknown["UNKNOWN_UID<br/>event 저장"]
    Inactive["INACTIVE_CARD<br/>event 저장"]
    NotMember["NOT_DEPARTMENT_MEMBER<br/>event 저장"]
    NoDay["NO_ATTENDANCE_DAY / CHECK_IN_CLOSED<br/>event 저장"]
    NotTarget["NOT_ATTENDANCE_TARGET<br/>event 저장"]
    Closed["CHECK_IN_NOT_OPEN / CHECK_IN_CLOSED<br/>event 저장"]
    Already["ALREADY_CHECKED_IN<br/>기존 record 유지 + event 저장"]
    Insert["attendance_record INSERT<br/>CHECKED_IN 또는 LATE"]
    Complete["tag_event_log에 결과·HTTP·JSON 저장"]

    Start --> Claim
    Claim -- "아니오" --> Replay
    Replay -- "예" --> Stored
    Replay -- "아니오" --> Conflict
    Claim -- "예" --> Card
    Card -- "아니오" --> Unknown
    Card -- "예" --> CardActive
    CardActive -- "아니오" --> Inactive
    CardActive -- "예" --> Membership
    Membership -- "아니오" --> NotMember
    Membership -- "예" --> Day
    Day -- "아니오" --> NoDay
    Day -- "예" --> Target
    Target -- "아니오" --> NotTarget
    Target -- "예" --> Window
    Window -- "아니오" --> Closed
    Window -- "예" --> Record
    Record -- "예" --> Already
    Record -- "아니오" --> Insert --> Complete
```

### DB 저장 여부

| 결과 | HTTP | `tag_event_log` | `attendance_record` |
|---|---:|---|---|
| `CHECKED_IN`, `LATE` | 201 | terminal event 저장 | 신규 저장 |
| `ALREADY_CHECKED_IN` | 200 | terminal event 저장 | 기존 기록 유지 |
| 카드·소속·날짜·대상·시간 업무 거부 | 404/409 | terminal event 저장 | 저장 안 함 |
| 같은 requestId에 다른 UID | 409 | 기존 event 불변 | 저장 안 함 |
| API off, rate limit, 인증, JSON 오류 | 4xx/5xx | 저장 안 함 | 저장 안 함 |
| transaction 중 device 상태 변경 | 409 | claim rollback | 저장 안 함 |

### 펌웨어 재시도와 LED

```mermaid
flowchart TD
    Send["같은 UID + 같은 requestId로 전송"]
    Response{"응답 유형"}
    Success["CHECKED_IN / LATE<br/>초록 1회"]
    Already["ALREADY_CHECKED_IN<br/>초록 2회"]
    Business["카드·날짜 등 업무 거부<br/>빨강 1회"]
    Config["401 · device state · requestId conflict<br/>빨강 2회"]
    Retry{"network / 429 / 500 / 503<br/>재시도 남음?"}
    Wait["Retry-After 또는 2/5/15초 대기"]
    Exhausted["재시도 소진<br/>빨강 3회"]

    Send --> Response
    Response -- "신규 성공" --> Success
    Response -- "이미 출석" --> Already
    Response -- "결정적 업무 오류" --> Business
    Response -- "설정 오류" --> Config
    Response -- "일시 오류" --> Retry
    Retry -- "예" --> Wait --> Send
    Retry -- "아니오" --> Exhausted
```

---

## 9. 수동 정정·자동 마감·통계

### 수동 출석 정정

```mermaid
flowchart TD
    Request["actualCheckInAt 또는 markAbsent<br/>note · reason · addMissingTarget"]
    Day["department/day lock<br/>CANCELED 거부"]
    Target{"active target?"}
    AddMissing{"addMissingTarget + 실제 시각의 membership?"}
    TargetInsert["attendance_target INSERT/재활성화"]
    Decision{"markAbsent?"}
    Absence["ABSENT<br/>time/band 없음"]
    Evaluate["고정 policy로 actualCheckInAt 재평가"]
    Manual["attendance_record INSERT/UPDATE<br/>source=MANUAL"]
    Audit["ATTENDANCE_MANUALLY_ADDED 또는 ATTENDANCE_CORRECTED"]
    Reject["업무 오류"]

    Request --> Day --> Target
    Target -- "예" --> Decision
    Target -- "아니오" --> AddMissing
    AddMissing -- "예" --> TargetInsert --> Decision
    AddMissing -- "아니오" --> Reject
    Decision -- "예" --> Absence --> Manual --> Audit
    Decision -- "아니오" --> Evaluate --> Manual
```

> 현재 구현은 `CANCELED`만 차단한다. 따라서 미래 `SCHEDULED` 날짜에도
> `markAbsent=true`로 결석 기록을 만들 수 있다.

### 자동 마감

```mermaid
sequenceDiagram
    participant Scheduler as AttendanceFinalizationScheduler
    participant Service as FinalizeAttendanceDayService
    participant DB as PostgreSQL

    loop 기본 5분 fixed delay
        Scheduler->>Service: findPendingDayIds
        Service->>DB: 오늘보다 이전인 SCHEDULED day 조회
        loop 각 day를 독립 transaction으로 처리
            Scheduler->>Service: finalizeDay(dayId)
            Service->>DB: department → day lock
            Service->>DB: target인데 record 없는 행에 AUTO_ABSENCE INSERT
            Service->>DB: day FINALIZED
            Service->>DB: idempotent system audit INSERT
        end
    end
```

### 공식 통계

```mermaid
flowchart LR
    Range["member + inclusive date range"]
    Auth["정확한 department 권한 확인"]
    Targets["is_target=true target"]
    Finalized["day.status=FINALIZED"]
    Records["attendance_record LEFT JOIN"]
    Summary["PRESENT · LATE · ABSENT 집계"]
    Bands["LATE band snapshot별 집계"]

    Range --> Auth --> Targets --> Finalized --> Records --> Summary --> Bands
```

---

## 10. DB migration·배포·백업 흐름

### Migration과 운영 애플리케이션 기동

```mermaid
flowchart TD
    Preflight["dbPreflight<br/>read-only transaction"]
    Class{"DB 분류"}
    Fresh["FRESH"]
    Legacy["LEGACY_CANDIDATE"]
    Managed["ALREADY_MANAGED"]
    Reject["REJECTED<br/>중단"]
    Approval["MIGRATION_SOURCE_CLASS와 대조"]
    Baseline["legacy만 baseline 0"]
    Migrate["Flyway V001~V008"]
    Validate["Flyway validate + exact version guard"]
    Grants["runtime 최소 권한 적용"]
    Start["prod app 시작<br/>Flyway auto-migrate=false"]
    RuntimeGuard["SchemaVersionGuard<br/>RuntimeDatabasePrivilegeGuard<br/>ProductionAdminSecurityGuard"]
    Health["loopback /actuator/health"]
    Proxy["Caddy가 app traffic 개방"]

    Preflight --> Class
    Class --> Fresh --> Approval
    Class --> Legacy --> Approval
    Class --> Managed --> Migrate
    Class --> Reject
    Approval --> Baseline --> Migrate
    Approval --> Migrate
    Migrate --> Validate --> Grants --> Start --> RuntimeGuard --> Health --> Proxy
```

### 백업·복원 검증

```mermaid
flowchart LR
    DirectDB[("PostgreSQL direct URL")]
    Dump["pg_dump custom format<br/>partial file"]
    Rename["atomic rename"]
    Checksum["SHA-256 checksum"]
    Artifact["backup artifact"]

    Verify["checksum 검증"]
    Empty["격리 대상 DB가 비었는지 확인"]
    Restore["pg_restore<br/>--clean 사용 안 함"]
    Schema["필수 테이블 + Flyway history 검증"]

    DirectDB --> Dump --> Rename --> Checksum --> Artifact
    Artifact --> Verify --> Empty --> Restore --> Schema
```

---

## 11. 현재 구현을 읽을 때 오해하면 안 되는 점

1. **현재 코드에는 DB RLS가 없다.** 부서 격리는 Service 권한 검사, SQL의
   `department_id`, composite FK에 의존한다.
2. **`SYSTEM_ADMIN`과 `DEPARTMENT_ADMIN`은 별개다.** 시스템 관리자도 부서 역할이
   없으면 부서 업무 데이터에 접근할 수 없다.
3. **관리자 쓰기 flag는 공개 setup/reset 소비에도 적용된다.** 링크 발행 후 flag를
   끄면 사용자는 503을 받는다.
4. **계정 disable은 기존 세션을 즉시 제거하지 않는다.** 중요 Service는 DB 권한
   재검증으로 막지만 세션은 idle 30분 또는 absolute 8시간까지 남을 수 있다.
5. **장치 HMAC 인증 성공 시 업무 검증보다 먼저 `last_seen_at`이 기록된다.** 이후
   JSON 오류나 상태 오류가 나도 최근 접속 시각은 갱신될 수 있다.
6. **카드 등록함에는 기간 제한과 `LIMIT`이 없다.** 카드가 다시 `AVAILABLE`이 되면
   오래된 이벤트가 재등장할 수 있다.
7. **정책 초안 `replaceDraft()`는 현재 audit을 남기지 않는다.**
8. **교사 제외 UI는 미래 target ID를 전달하지 않는다.** 미래 snapshot이 남아 자동
   결석으로 이어질 수 있다.
9. **Operations 화면의 backup 상태는 실제 감시 결과가 아니라 확인 불가 상태다.**

---

## 12. 새로운 흐름을 추적하는 방법

어떤 기능을 추가로 분석하더라도 아래 순서를 반복한다.

```mermaid
flowchart LR
    Route["1. Route / Scheduler / CLI"]
    Security["2. Security · flag · CSRF"]
    Controller["3. Controller input/output"]
    Service["4. Application Service transaction"]
    Locks["5. authorization · lock order · time guard"]
    Mapper["6. Mapper interface/XML"]
    Constraints["7. migration constraint/index"]
    SideEffect["8. audit/event/response/retry"]
    Tests["9. integration test로 실제 계약 확인"]

    Route --> Security --> Controller --> Service --> Locks --> Mapper --> Constraints --> SideEffect --> Tests
```

각 흐름마다 다음 질문에 답할 수 있으면 코드 이해가 끝난 것이다.

- 누가 이 흐름을 시작하는가?
- 어떤 인증·권한·feature flag를 통과하는가?
- transaction은 어디서 시작하고 끝나는가?
- 어떤 행을 어떤 순서로 잠그는가?
- 어떤 테이블을 읽고 변경하는가?
- 실패하면 무엇이 rollback되고 무엇이 남는가?
- 재시도하면 같은 결과가 재현되는가?
- `audit_log` 또는 `tag_event_log`에 어떤 흔적이 남는가?
- 사용자·장치가 최종적으로 어떤 응답을 받는가?
