# Attend 보안·권한 매트릭스

## 0. 문서 정보

| 항목 | 내용 |
|---|---|
| 문서 목적 | Attend MVP의 인증 주체, URL·화면·서비스·Mapper·DB 권한을 하나의 검증 가능한 보안 계약으로 정의 |
| 기준·연계 문서 | [프로젝트 정의서](./PROJECT_DEFINITION.md), [시스템 아키텍처](./ARCHITECTURE.md), [데이터베이스 구조 설계](./DATABASE_DESIGN.md), [DB 전환 계획](./MIGRATION_PLAN.md), [장치 API 계약](./device-api.yaml), [관리자 UI 명세](./ADMIN_UI_SPEC.md), [테스트 계획](./TEST_PLAN.md) |
| 적용 범위 | 관리자 웹, NFC 장치 API, Spring 스케줄러, 애플리케이션 runtime, Flyway·컷오버 주체 |
| 적용 단계 | M3 관리자 인증·인가부터 M5 운영 전환까지 |
| 시간 기준 | `Asia/Seoul`; 보안 로그 자체의 저장 시각은 `TIMESTAMPTZ` |

이 문서는 목표 MVP의 규범이다. 현재 코드에 존재하는 단일 `SecurityFilterChain`, 기존 `ADMIN`·`USER` 권한, 전역 ID 조회와 개발용 인증 설정은 이 문서의 충족 증거가 아니다.

---

## 1. 결론과 보안 불변조건

1. `SYSTEM_ADMIN`은 무제한 최고 권한이 아니다. 부서·계정·장치와 전체 운영 상태는 관리할 수 있지만, 교사·카드·정책·출석 날짜·출석 기록 같은 부서 업무는 **그 부서의 활성 `DEPARTMENT_ADMIN` 권한이 별도로 있을 때만** 처리할 수 있다.
2. 한 계정이 두 역할을 함께 가질 수 있다. 이 경우에도 시스템 기능은 `SYSTEM_ADMIN` 자격으로, 부서 기능은 대상 부서의 `DEPARTMENT_ADMIN` 자격으로 각각 판정한다. 시스템 역할을 부서 역할로 암묵적으로 승격하지 않는다.
3. 부서 선택 UI와 버튼 숨김은 사용성 기능일 뿐 보안 통제가 아니다. URL, application service, Mapper SQL과 DB 무결성 제약을 모두 통과해야 한다.
4. 관리자 웹과 장치 API는 서로 다른 `SecurityFilterChain`을 사용한다. 관리자 웹은 세션·form login·CSRF를 사용하고, 장치 API는 장치 코드·비밀키 기반 stateless 인증을 사용한다.
5. 장치의 부서는 헤더나 JSON으로 받지 않고 인증된 `device.department_id`에서만 결정한다. 장치 한 대의 부서는 생성 후 바꾸지 않는다.
6. 체크인은 `ACTIVE` 장치만, credential test는 `INACTIVE` 장치만 허용한다. `REVOKED`는 종결 상태이며 복구·재활성화하지 않는다.
7. `admin-write.enabled`, `device-api.enabled`, `scheduler.enabled`는 가용성 경계다. 어떤 flag도 인증·권한 검사를 대신하거나 우회하지 않는다.
8. HTTP 요청이 보낸 `accountId`, 작업자 유형, `SYSTEM` actor, 부서 ID를 감사 주체나 권한 근거로 신뢰하지 않는다. 작업자는 인증 세션·장치 principal·내부 스케줄러 호출에서 서버가 결정한다.
9. `app_runtime`은 애플리케이션 전체가 공유하는 기술 DB 계정이다. DB grant만으로는 부서별 행 조회를 구분할 수 없다. 따라서 서비스와 Mapper의 부서 scope가 기밀성의 필수 통제이고, 복합 FK는 잘못된 교차 부서 연결을 막는 보조 통제다.
10. MVP에서는 PostgreSQL RLS를 사용하지 않는다. 그러므로 “DB 권한이 부서 격리를 보장한다”라고 주장해서는 안 된다. RLS 없이도 모든 scope 부정 테스트를 통과해야 한다.
11. 비밀번호, 장치 비밀키, DB 자격증명과 Wi-Fi 비밀번호 원문은 저장소·일반 로그·감사 로그·오류 응답에 남기지 않는다.
12. 거부된 요청은 업무 변경이 아니므로 `audit_log`에 성공한 것처럼 남기지 않는다. 대신 최소화된 보안 운영 로그와 실패 지표를 남긴다.

---

## 2. 주체와 신뢰 경계

### 2.1 논리 주체

| 주체 | 인증 수단 | 권한 원천 | 허용 범위 | 명시적 금지 |
|---|---|---|---|---|
| 미인증 사용자 `ANONYMOUS` | 없음 | 없음 | 로그인 화면, 정적 자원, 일반 오류 화면 | 관리자 데이터, 장치 API, 운영 상태 |
| 시스템 관리자 `SYSTEM_ADMIN` | 활성 `account`의 웹 세션 | `account.system_role = 'SYSTEM_ADMIN'` | 부서 생성·조회, 계정 관리, 부서 관리자 지정·해제, 장치 lifecycle, 개인정보 없는 전체 운영 상태 | 부서 업무 자동 접근, DB 직접 접속, 장치 키 재조회 |
| 부서 관리자 `DEPARTMENT_ADMIN(D)` | 활성 `account`의 웹 세션 | 대상 부서 D의 `account_department_role` 활성 행 | D의 교사·소속·카드·정책·출석 날짜·수동 정정·통계·업무 이력 | 다른 부서 데이터, 시스템 계정·장치 credential 관리 |
| 이중 역할 계정 | 같은 웹 세션 | 위 두 권한의 합집합이 아닌 **행위별 별도 판정** | 시스템 기능과 명시적으로 배정된 부서 기능 | 배정되지 않은 부서 기능 |
| 출석 대상 교사 | 웹 인증 없음 | 활성 소속·카드 연결 | 실제 카드 태깅 | 관리자 웹 로그인, UID만으로 신원·권한 인증 |
| `ACTIVE` 장치 | 장치 코드와 장치별 비밀키 | 인증 시 만든 `DevicePrincipal`과 transaction 재검증 | 고정 부서의 check-in | credential test, 관리자 웹, 부서 선택 |
| `INACTIVE` 장치 | 장치 코드와 장치별 비밀키 | 같은 방식 | credential test | check-in, 자동 활성화 |
| `REVOKED` 장치 | 유효했던 키가 남아 있어도 권한 없음 | 종결 상태 | 없음 | check-in, credential test, 키 재발급, 재활성화 |
| 미등록·인증 실패 장치 | 없음 | 없음 | 없음 | `last_seen_at`, `tag_event_log`, 출석 데이터 생성 |
| 시스템 스케줄러 `SYSTEM` | 외부 인증 없음 | 조건부 생성된 내부 bean과 서버 생성 actor | 과거 미마감 날짜의 멱등 자동 마감 | HTTP 호출, 일반 관리자 command, 임의 `SYSTEM` actor 지정 |

`DEPARTMENT_ADMIN(D)`의 D는 로그인 때 선택한 화면 값이 아니라, 요청 시점에 DB에서 확인한 `revoked_at IS NULL`인 활성 권한이다. 로그인 뒤 권한이 회수되더라도 다음 부서 command는 service 검사에서 거부되어야 한다.

### 2.2 DB·운영 주체

| 기술 주체 | 사용 시점 | 허용 범위 | 금지 |
|---|---|---|---|
| `app_runtime` | 운영 Spring Boot process | 신규 14개 테이블의 필요한 DML, `member` 허용 컬럼, sequence 사용, Flyway history 읽기 | DDL, Flyway history 변경, `member` 삭제, 레거시 3개 테이블 DML |
| `migration_owner` | 배포 전 고정 Flyway runner | 승인 migration, schema·history 관리, `info`·`validate` | 웹 runtime 사용, 평상시 애플리케이션 접속 |
| `cutover_writer` | 승인된 컷오버 시간 | bootstrap·importer의 최소 DML | DDL, 레거시 출석·로그 DML, 컷오버 후 로그인 |
| `legacy_writer` | 안전 릴리스와 승인된 rollback 시간 | 기존 앱에 필요한 제한 DML | `member.card_uid` 변경, `member` 삭제, 컷오버 후 상시 로그인 |
| PostgreSQL `PUBLIC` | 모든 DB 사용자 | 기본 연결에 필요한 최소 범위만 | schema 생성, 업무 테이블·sequence·함수의 암묵적 권한 |
| 운영 인프라 담당자 | 승인된 운영 절차 | process 설정, 비밀 저장소, 제한된 application log와 backup | 애플리케이션 역할을 가장한 업무 변경 |

웹 관리자와 장치는 DB에 직접 로그인하지 않는다. 애플리케이션이 사용하는 `app_runtime` 비밀번호를 관리자에게 제공해서도 안 된다.

### 2.3 계정 상태와 세션의 한계

- `account.status = 'ACTIVE'`인 계정만 새로 로그인할 수 있다.
- `DISABLED`, 존재하지 않는 사용자명과 비밀번호 불일치는 외부에 같은 로그인 실패 문구를 반환한다.
- MVP는 계정 비활성화·비밀번호 재설정 직후 기존 세션을 찾아 강제 만료하지 않는다.
- 이 한계를 숨기지 않고 세션 유휴 만료 30분, 절대 만료 8시간을 적용한다.
- 로그아웃은 `POST`와 유효 CSRF token으로만 수행하고 세션과 인증 쿠키를 무효화한다.
- 세션 ID는 로그인 성공 시 교체하고, remember-me는 사용하지 않는다.
- 후속 강제 만료 기능이 생기기 전에는 계정 침해 시 운영자가 계정 비활성화와 함께 애플리케이션 세션 저장소를 통제하는 비상 절차를 실행해야 한다.

---

## 3. 다층 방어 모델

### 3.1 요청 처리 순서

```mermaid
flowchart LR
    A["요청"] --> B["가용성·크기·rate limit"]
    B --> C["SecurityFilterChain 인증"]
    C --> D["URL 수준의 넓은 역할"]
    D --> E["Controller 입력·principal 전달"]
    E --> F["Application service의 정확한 권한·업무 검증"]
    F --> G["department_id가 포함된 Mapper SQL"]
    G --> H["DB grant·CHECK·unique·복합 FK"]
    H --> I["업무 결과와 감사·이벤트 기록"]
```

각 계층의 책임은 다음과 같다.

| 계층 | 반드시 보장할 것 | 이 계층만으로 보장할 수 없는 것 |
|---|---|---|
| 화면 | 허용 기능만 노출, 현재 부서 context 표시, 위험 작업 재확인 | 직접 URL·변조 요청 차단 |
| URL 보안 | 인증 여부와 시스템/부서 역할의 큰 경계 | 계정이 특정 부서 D의 관리자인지 |
| Controller | 형식 검증, 경로 `departmentId`와 인증 principal을 command로 전달 | DB 최신 권한·상태, 트랜잭션 원자성 |
| Application service | 활성 권한, 정확한 use case, 상태 전이, actor 결정, transaction | 최종 경쟁 상태의 유일성 |
| Mapper | 모든 부서 resource 조회·갱신에 `department_id` 포함, 영향 행 수 반환 | 역할의 의미, UI 권한 |
| PostgreSQL | 최소 DML 권한, unique·CHECK·복합 FK·`RESTRICT` | 공용 `app_runtime` 안의 사용자별 행 기밀성 |
| 감사·관측 | 사후 추적과 경보 | 요청 사전 차단 |

어느 한 계층의 검사를 생략하고 다른 계층이 대신한다고 설명해서는 안 된다.

### 3.2 부서 resource 조회 규칙

부서 업무 Mapper는 전역 ID 조회 후 Java에서 부서를 비교하지 않는다.

```sql
SELECT ...
FROM attendance_day
WHERE id = :attendanceDayId
  AND department_id = :authorizedDepartmentId;
```

`member`처럼 전역 기준 테이블인 경우에도 활성·과거 소속의 목적에 맞는 join을 통해 부서 범위를 만든다.

```sql
SELECT m.id, m.name, m.phone, m.active, m.updated_at
FROM member AS m
JOIN department_membership AS dm
  ON dm.member_id = m.id
WHERE m.id = :memberId
  AND dm.department_id = :authorizedDepartmentId;
```

- update·종료 SQL에도 같은 `department_id` 조건을 둔다.
- 요청이 보낸 `departmentId`는 조회 조건일 뿐 권한 증명이 아니다. service가 인증 계정의 활성 역할을 먼저 확인한다.
- 영향 행 수가 예상한 1이 아니면 commit하지 않는다.
- 다른 부서 ID와 존재하지 않는 ID는 동일한 `404` 처리로 존재 여부를 숨긴다.
- 역할 자체가 없는 인증 사용자가 해당 기능군에 접근하면 `403`이다.
- 목록·검색·통계도 단건 조회와 같은 범위를 적용한다. 검색 조건이 없다고 전역 목록으로 바뀌면 안 된다.
- MyBatis 값은 `#{...}`로 bind한다. 사용자 입력을 `${...}`에 넣지 않으며, 정렬 컬럼·방향은 서버 allowlist로만 선택한다.
- 신규 Mapper는 `SELECT *`를 사용하지 않는다.
- 시스템 관리용 전역 query와 부서 업무 query는 메서드와 result type을 분리한다. 전역 시스템 query를 부서 화면에서 재사용하지 않는다.

---

## 4. 두 개의 `SecurityFilterChain`

### 4.1 chain 매트릭스

| 항목 | 장치 API chain | 관리자 웹 chain |
|---|---|---|
| 순서 | `@Order(1)` | 장치 chain 다음 |
| matcher | `/api/v1/device/**` | 나머지 웹 요청 |
| 세션 | `STATELESS`, 생성·사용 금지 | form login, 서버 세션 |
| 인증 | `X-Device-Code` + `X-Device-Key` | username + password |
| CSRF | 이 matcher에만 예외 | 모든 상태 변경 요청에 적용 |
| 실패 형식 | JSON과 안정적인 code | 로그인 redirect 또는 HTML 403·오류 화면 |
| request cache | 사용하지 않음 | 로그인 이전의 안전한 GET에 한해 사용 가능 |
| redirect | 사용 금지 | 로그인 흐름에서만 사용 |
| principal | `DevicePrincipal(deviceId, departmentId, credentialVersion)` | `AccountPrincipal(accountId, systemRole, broadAuthorities)` |
| 가용성 선행 검사 | `device-api.enabled` | 쓰기에서 `admin-write.enabled` |

장치 API matcher는 정의되지 않은 `/api/v1/device/**` 경로도 장치 chain 안에서 JSON `404`로 끝내야 한다. 이를 웹 chain으로 흘려 로그인 HTML을 반환하면 안 된다. 반대로 세션이 있는 브라우저라도 장치 헤더 인증 없이 장치 API를 호출할 수 없다.

### 4.2 관리자 웹 chain

- `GET /login`, `POST /login`, 필요한 정적 자원과 일반 오류 화면만 미인증 접근을 허용한다.
- `/admin/system/**`는 `SYSTEM_ADMIN`의 넓은 URL 권한을 요구한다.
- `/admin/departments/**`는 하나 이상의 부서 관리자 역할을 가진 계정만 URL 단계에서 통과시키고, 정확한 부서는 service가 다시 확인한다.
- `/admin/account/**`는 인증된 본인의 비밀번호 변경 같은 자기 계정 기능만 허용한다.
- `/admin/**`의 나머지는 기본적으로 인증을 요구하고, 명시적으로 허용하지 않은 경로는 거부한다.
- 상태 변경은 `POST`, 필요한 경우 `PUT`·`PATCH`·`DELETE`만 사용한다. `GET` controller는 DB 상태를 변경하지 않는다.
- 로그인 처리와 로그아웃에도 CSRF를 적용한다.
- 서버 렌더링 화면은 same-origin만 사용하고 credential을 허용하는 광범위 CORS 설정을 만들지 않는다.
- `returnUrl`을 사용하면 정규화 뒤 같은 origin의 `/admin/**` 상대 경로만 allowlist로 허용한다. 절대 URL, `//` 시작, 역슬래시와 이중 인코딩으로 외부 origin을 만드는 값은 거부한다.
- session cookie는 `HttpOnly`, `SameSite=Lax`를 사용한다. HTTPS 운영에서는 `Secure`를 필수로 한다.
- HTTPS 운영에서는 HSTS를 적용하되, 격리 내부망 HTTP 운영을 선택한 환경에는 잘못된 HSTS header를 보내지 않는다.
- 최소 응답 header는 `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, frame embedding 차단이다.
- 개인정보·출석·키 발급 화면에는 `Cache-Control: no-store`를 적용한다.

### 4.3 CSRF 경계

| 요청 | CSRF token | 근거 |
|---|---:|---|
| 관리자 로그인·로그아웃 | 필수 | 세션 고정·login CSRF와 강제 로그아웃 방지 |
| 시스템 관리 write | 필수 | 브라우저 세션 command |
| 부서 업무 write | 필수 | 브라우저 세션 command |
| 관리자 read-only GET | 불필요 | 반드시 부작용이 없어야 함 |
| `/api/v1/device/**` | 제외 | 세션을 사용하지 않는 장치별 header 인증 |
| 향후 브라우저 JSON API | 필수 | JSON·same-origin만으로 CSRF가 해결되지 않음 |

CSRF를 애플리케이션 전체에서 끄거나 `/api/**` 전체를 예외 처리하지 않는다.

---

## 5. 웹 인증 기준

### 5.1 비밀번호

MVP 구현 기준은 다음과 같다.

- 새 비밀번호는 12~64 Unicode code point이고 UTF-8 인코딩 결과가 72 byte 이하여야 한다.
- 현재 BCrypt의 안전한 입력 한계를 넘는 값을 잘라서 저장하지 않고 검증 오류로 거부한다.
- 대문자·숫자·특수문자 조합을 기계적으로 강제하지 않는다. 공백을 포함한 긴 passphrase를 허용한다.
- 사용자명, 교회명과 흔한·유출 비밀번호 목록에 포함된 값은 거부한다.
- 비밀번호 확인 값은 저장·로그하지 않는다.
- 비밀번호 hash는 Spring `PasswordEncoder`로 만들고 평문·복호화 가능한 암호문을 저장하지 않는다.
- 비밀번호 변경 성공 시 `password_changed_at`을 갱신하고 보안 감사 이력을 남기되 hash와 평문은 before/after에 넣지 않는다.

현재 `account` schema에는 회원가입 초대 상태나 일회용 초대·reset token을 표현하는 컬럼이 없다. 따라서 “시스템 관리자가 임시 비밀번호를 발급하고 다음 로그인에 강제 변경한다”는 기능을 현 schema만으로 안전하게 구현했다고 간주할 수 없다. 배포 전 bootstrap·회원가입 초대·reset 전달 절차와 필요한 schema를 확정해야 하며, 임시 비밀번호를 DB·로그·전자우편에 평문 보관하는 임시 구현은 금지한다.

### 5.2 로그인 시도 제한

MVP는 영구 계정 잠금을 만들지 않고 token bucket 두 개를 함께 적용한다.

| bucket | capacity | refill | key |
|---|---:|---:|---|
| 계정 후보 + source | 5 | 60초당 1 | 정규화한 username hash + effective source |
| source 전체 | 20 | 60초당 1 | effective source |

- 어느 하나라도 소진되면 같은 일반 실패 화면과 `429` 또는 동등한 웹 오류를 반환한다.
- 성공 로그인은 해당 username+source bucket만 초기화할 수 있다.
- 존재하지 않는 username도 동일한 password hash 비용에 가깝게 처리해 계정 존재와 timing 차이를 줄인다.
- `X-Forwarded-For`는 승인된 reverse proxy에서 온 요청일 때만 신뢰하고, 그 외에는 실제 remote address를 사용한다.
- application log에는 username 원문 대신 안정적인 hash 또는 내부 account ID를 우선 사용한다.
- 반복 실패, source 제한과 성공 이후의 비정상 패턴을 운영 지표로 남긴다.

### 5.3 권한 변경

- 시스템 관리자는 부서 관리자 역할을 명시적으로 배정·회수할 수 있고 자신에게 배정하는 것도 기술적으로는 가능하다.
- 이는 `SYSTEM_ADMIN`의 자동 부서 권한이 아니라 별도의 명시적·감사 가능한 권한 변경이다.
- MVP에는 2인 승인과 자기 권한 부여 금지가 없다. 운영상 분리 통제가 필요하다면 배포 전에 추가해야 한다.
- 권한 배정·회수에는 대상 account, department, 실행 account와 시각을 감사한다.
- 부서 command는 세션에 남은 오래된 authority만 믿지 않고 활성 `account_department_role`을 확인한다.

### 5.4 최초 bootstrap·회원가입 초대·reset 출시 gate

- 설치 artifact와 migration에는 공개 username·기본 비밀번호·공통 password hash를 넣지 않는다.
- 최초 `SYSTEM_ADMIN`은 승인된 maintenance window에서 `cutover_writer` 또는 별도 제한 CLI로 한 번만 bootstrap한다. 비밀번호는 운영자가 interactive input으로 직접 넣고 process argument·환경변수·shell history·로그에 남기지 않는다.
- 첫 계정 생성이 성공하면 같은 bootstrap entry point는 추가 계정을 만들 수 없게 닫는다. 이후 계정과 권한은 인증된 시스템 관리자 절차를 따른다.
- 시스템 관리자는 계정을 먼저 생성한 뒤 회원가입 초대 token을 발급한다. 초대받지 않은 사용자가 직접 계정을 생성하는 공개 회원가입은 제공하지 않는다.
- 회원가입 초대와 password reset은 최소 128 bit 난수 token, token hash, 발급·만료·사용 시각과 대상 account를 저장할 모델이 있어야 한다.
- 초대·reset token은 최대 30분만 유효하고 한 번 성공하거나 새 token을 발급하면 이전 token은 즉시 무효다. 원문 token을 DB·감사·application/access log·email 본문에 보관하지 않는다.
- token을 전달할 승인 채널과 HTTPS URL 정책이 확정되지 않았다면 URL만 임의로 만들지 않는다.
- 현재 `account` schema에는 위 token과 초대 대기 상태가 없다. 필요한 Flyway migration과 테스트가 완료되기 전에는 계정 생성·회원가입 초대·reset command를 비활성화하고 성공 UI를 표시하지 않는다.
- 평문 임시 비밀번호를 시스템 관리자가 조회·복사·메일 전송하는 방식은 대체 구현으로 허용하지 않는다.

---

## 6. URL·화면 권한 매트릭스

표에서 `허용(D)`은 요청 계정이 경로의 부서 D에 활성 `DEPARTMENT_ADMIN` 권한이 있을 때만 허용한다는 뜻이다. `SYSTEM_ADMIN+DEPARTMENT_ADMIN(D)` 계정도 D의 부서 업무에서는 `허용(D)` 규칙을 그대로 적용한다.

### 6.1 공통·시스템 화면

| 화면·URL 계열 | 미인증 | `SYSTEM_ADMIN` | `DEPARTMENT_ADMIN` | 장치 | 추가 조건 |
|---|---:|---:|---:|---:|---|
| `GET /login`, `POST /login` | 허용 | 허용 | 허용 | 공개 경로일 뿐 장치 인증 아님 | 로그인 성공 전 관리자 데이터 없음 |
| 정적 자원, 일반 오류 | 허용 | 허용 | 허용 | 공개 응답만 | 민감정보·stack trace 없음 |
| `POST /logout` | 거부 | 허용 | 허용 | 거부 | CSRF 필수 |
| `/admin/account/**` | 거부 | 본인만 | 본인만 | 거부 | account ID를 path/body에서 선택하지 않음 |
| `GET /admin`, `GET /admin/workspaces` | 거부 | 허용 | 허용 | 거부 | 허용된 시작 화면·작업 공간으로만 이동 |
| `GET /admin/system` | 거부 | 허용 | 거부 | 거부 | 시스템 홈 |
| `/admin/system/departments/**` | 거부 | 허용 | 거부 | 거부 | MVP는 생성·조회; 활성 상태 변경 없음 |
| `/admin/system/accounts/**` | 거부 | 허용 | 거부 | 거부 | 생성·비활성화·권한 지정/해제, 안전한 reset 절차만 |
| `/admin/system/devices/**` | 거부 | 허용 | 거부 | 거부 | 장치 생성·키 lifecycle·상태; 부서 변경 금지 |
| `/admin/system/operations` | 거부 | 허용 | 거부 | 거부 | 집계 health·미마감 수·장치 telemetry; 교사 상세 없음 |
| `/admin/system/audit` | 거부 | 허용 | 거부 | 거부 | 시스템·계정·부서·장치 lifecycle action allowlist |
| 원시 application log·backup | 거부 | 자동 허용 안 함 | 거부 | 거부 | 별도 인프라 운영 권한과 승인 절차 |
| 외부 공개 actuator | 거부 | 거부 | 거부 | 거부 | 운영망 제한 또는 별도 인증 필요 |

시스템 운영 상태 조회는 다른 부서의 교사 명단, 연락처, 카드 UID, 출석 상세와 태깅 응답 본문을 보는 우회 경로가 아니다. 장치 운영 화면은 장치 코드·이름·고정 부서·상태·credential version·발급 시각·현재 key 시험 version·시험 시각·`last_seen_at`을 볼 수 있지만 credential hash와 기존 비밀키를 볼 수 없다.

### 6.2 부서 화면

| URL 계열 | 주요 화면·행위 | `SYSTEM_ADMIN` 단독 | `DEPARTMENT_ADMIN(D)` | 다른 부서 관리자 | scope key |
|---|---|---:|---:|---:|---|
| `/admin/departments/{D}` | 오늘의 출석·날짜별 현황 | 거부 | 허용(D) | 404 | D + day |
| `/admin/departments/{D}/teachers/**` | 교사 목록·상세·추가·수정·부서 제외·개인 통계 | 거부 | 허용(D) | 404 | D + membership/member |
| `/admin/departments/{D}/teachers/{memberId}/card/**` | 카드 연결·교체·해제·분실·폐기 | 거부 | 허용(D) | 404 | D + membership/card/assignment |
| `/admin/departments/{D}/cards/inbox/**` | `UNKNOWN_UID` 등록과 `INACTIVE_CARD`인 `AVAILABLE` 카드 재사용 | 거부 | 허용(D) | 404 | D + tag event/card/device |
| `/admin/departments/{D}/policies/**` | 초안·구간·발행·이력 | 거부 | 허용(D) | 404 | D + policy |
| `/admin/departments/{D}/attendance-days/**` | 날짜·대상자·결과·수동 등록·정정·메모 | 거부 | 허용(D) | 404 | D + day/target/record |
| `/admin/departments/{D}/history` | 부서 감사와 태깅 이력 탭 | 거부 | 허용(D) | 404 | D + audit/event |

`SYSTEM_ADMIN` 화면에서 부서 D를 선택했거나 URL을 알고 있다는 사실은 `DEPARTMENT_ADMIN(D)` 권한을 만들지 않는다.

`/history`의 두 탭은 한 화면이어도 같은 전역 query를 공유하지 않는다. 감사 탭은 `audit_log.department_id = D`, 태깅 탭은 `tag_event_log.department_id = D`인 별도 scope query를 사용한다. 부서 dashboard의 장치 정보는 장치명·상태·마지막 인증 성공 시각만 허용한다. 장치 코드 전체, credential version·hash·원문과 lifecycle command는 노출하지 않고, 장치 상세·관리 URL은 `/admin/system/devices/**`에만 둔다.

### 6.3 시스템 command

| command | 시스템 관리자 | 부서 관리자 | 감사 | `admin-write` |
|---|---:|---:|---:|---:|
| 부서 생성 | 허용 | 거부 | 필수 | 필요 |
| 부서 비활성화·재활성화 | MVP 미제공 | MVP 미제공 | 해당 없음 | 해당 없음 |
| 계정 생성·비활성화 | 허용 | 거부 | 필수 | 필요 |
| 시스템 역할 변경 | 승인된 bootstrap·관리 절차만 | 거부 | 필수 | 필요 |
| 부서 관리자 지정·해제 | 허용 | 거부 | 필수 | 필요 |
| 장치 생성과 부서 최초 배정 | 허용 | 거부 | 필수 | 필요 |
| 장치 `department_id` 변경 | 거부 | 거부 | 거부 시 보안 로그 | 필요 |
| 장치 키 발급·즉시 교체 | 허용 | 거부 | 필수, 원문 제외 | 필요 |
| `INACTIVE → ACTIVE` | credential test 확인 뒤 허용 | 거부 | 필수 | 필요 |
| `ACTIVE/INACTIVE → REVOKED` | 허용 | 거부 | 필수 | 필요 |
| `REVOKED` 재활성화·키 발급 | 거부 | 거부 | 거부 시 보안 로그 | 필요 |

### 6.4 부서 command

다음 command는 해당 부서의 활성 관리자만 실행한다. `SYSTEM_ADMIN` 단독 계정은 모두 거부한다.

| command | 추가 검증 | 감사 |
|---|---|---|
| 교사·활성 소속 추가 | 구성원 활성 상태와 MVP 단일 활성 소속, 대상 부서 | actor와 생성 내용 |
| 교사 기본정보 수정 | 대상 부서 소속을 통한 접근, 허용 컬럼만 | 변경 전후 허용 필드 |
| 부서 제외 | 사유, 카드 `AVAILABLE`·`LOST`·`RETIRED` 처리, 미래 대상 선택 | 소속·카드·대상 변경을 한 transaction에 기록 |
| 미등록 UID 카드 등록·연결 | D의 인증 장치 event, 활성 소속, 카드·assignment 잠금 | event ID·내부 card ID·처리 사유; 키·연락처 제외 |
| 카드 교체·해제 | 활성 assignment, 카드 상태 전이, 필수 사유 | before/after와 actor |
| 정책 초안·구간 편집 | DRAFT만, 부모 정책 잠금 | 발행 전 편집 감사 범위는 구현 정책에 따름 |
| 정책 발행 | 전체 구간 검증, 이후 불변 | 필수 |
| 출석 날짜·대상 snapshot 생성 | 오늘·미래, D의 PUBLISHED 정책·활성 소속 | 필수 |
| 날짜 취소 | 태깅 시작 전·기록 없음·필수 사유 | 필수 |
| 일반 대상자 변경 | 태깅 시작 전 | 필수 |
| 사후 누락자 수동 등록 | `CANCELED` 아님, 실제 출석 시각·소속 기간, 대상+기록 원자성 | 필수 |
| 출석 수동 정정 | 실제 시각·고정 정책 서버 재계산, 사유 | before/after 필수 |
| 메모만 수정 | 기존 판정 원천 유지 | before/after 필수 |
| 부서 통계·이력 조회 | D와 공식 `FINALIZED` 경계 | read 자체는 기본 audit 대상 아님 |

클라이언트가 보낸 상태·구간·처리 관리자 ID를 저장하지 않는다. 실제 출석 시각과 사유만 입력받고 상태·구간·actor는 서버가 결정한다.

---

## 7. Application service 권한 매트릭스

### 7.1 서비스별 caller

| application service 계열 | 허용 caller | 권한 검사 | 금지 caller |
|---|---|---|---|
| `DepartmentAdministrationService` | `SYSTEM_ADMIN` | system role과 account 상태 | 부서 관리자·장치 |
| `AccountAdministrationService` | `SYSTEM_ADMIN` | system role, 자기 비활성화 등 위험 규칙 | 부서 관리자·장치 |
| `DepartmentRoleService` | `SYSTEM_ADMIN` | system role, 대상 account·department | 부서 관리자 |
| `DeviceAdministrationService` | `SYSTEM_ADMIN` | system role, 상태 전이, 부서 불변 | 부서 관리자·장치 |
| `OwnAccountService` | 인증 account 본인 | principal account ID 고정 | 다른 account ID |
| `RosterApplicationService` | `DEPARTMENT_ADMIN(D)` | D의 활성 역할 | 시스템 역할만 있는 계정 |
| `CardOrchestrationService` | `DEPARTMENT_ADMIN(D)` | D의 활성 역할과 event·membership scope | 다른 D 관리자 |
| `PolicyApplicationService` | `DEPARTMENT_ADMIN(D)` | D의 활성 역할 | 다른 D·장치 |
| `AttendanceDayApplicationService` | `DEPARTMENT_ADMIN(D)` | D의 활성 역할 | 다른 D·장치 |
| `AttendanceCorrectionService` | `DEPARTMENT_ADMIN(D)` | D의 활성 역할, actor를 principal에서 설정 | 시스템 역할만 있는 계정 |
| `AttendanceQueryService` | `DEPARTMENT_ADMIN(D)` | D의 활성 역할 | 다른 D |
| `DeviceCheckInService` | `ACTIVE DevicePrincipal` | principal의 D, device 상태·version 재검증 | 웹 account·비활성 장치 |
| `DeviceCredentialTestService` | `INACTIVE DevicePrincipal` | credential 유효성과 상태 | 웹 account·활성·폐기 장치 |
| `FinalizeAttendanceDayService` | 내부 scheduler `SYSTEM` | 과거 SCHEDULED 날짜, typed internal actor | HTTP DTO·웹 account·장치 |

Controller가 `SYSTEM`, `ACCOUNT`, `DEVICE` 문자열을 받아 actor 객체를 생성하게 하지 않는다. 외부 principal과 내부 scheduler caller를 Java type 또는 호출 경계로 구분한다.

### 7.2 서비스 검사 순서

관리자 command는 최소 다음 순서를 따른다.

1. 해당 feature flag 확인
2. 인증 principal의 종류 확인
3. 요구되는 system role 또는 대상 D의 활성 부서 역할 확인
4. 경로·form 입력 형식과 허용 필드 확인
5. D scope로 resource를 조회·잠금
6. 업무 상태와 시간 규칙 검증
7. 변경과 `audit_log`를 같은 transaction에 저장
8. 영향 행 수와 결과를 확인한 뒤 commit

권한 실패를 transaction 후반으로 미뤄 resource 정보를 먼저 읽거나 로그에 출력하지 않는다.

---

## 8. Mapper scope와 PostgreSQL 권한

### 8.1 Mapper 규칙

| 데이터 | 부서 업무 Mapper의 필수 조건 |
|---|---|
| `department_membership`·`member` | `department_membership.department_id = :authorizedDepartmentId` |
| `nfc_card`·`nfc_card_assignment` | assignment의 `department_id`와 membership의 D가 모두 일치 |
| 카드 등록함 | `UNKNOWN_UID` event는 `tag_event_log.department_id = D`와 D의 device; 재사용 카드는 같은 D의 `INACTIVE_CARD` event, `nfc_card.status = 'AVAILABLE'`, 활성 assignment 부재와 연결 command 검증을 모두 통과 |
| 정책·구간 | `attendance_policy_version.department_id = D`; band는 정책 join |
| 출석 날짜·대상·기록 | `attendance_day.department_id = D`; 자식은 부모 join 또는 scope FK |
| 태깅 event | `tag_event_log.department_id = D` |
| 감사 이력 | `audit_log.department_id = D` |
| 부서 장치 읽기 | `device.department_id = D`; credential hash 선택 금지 |

IDOR 방지를 위해 자식 ID만으로 조회하지 않는다. 예를 들어 record ID를 받더라도 `attendance_record → attendance_day`를 join해 D를 함께 제한한다.

### 8.2 시스템 query의 최소화

시스템 관리용 query는 다음만 전역 조회할 수 있다.

- 부서 기본 metadata
- account 상태와 역할 metadata
- 장치 code·이름·고정 부서·상태·credential version·발급 시각·현재 key 시험 version·시험 시각·`last_seen_at`
- DB 연결, 미마감 날짜 수, 마지막 scheduler·backup 상태 같은 운영 집계
- 시스템·계정·부서·장치 lifecycle 감사 action

교사 이름·연락처, 전체 UID, 카드 소유자, 출석 상세와 `tag_event_log.response_body`를 system query에 포함하지 않는다.

### 8.3 `app_runtime` 목표 grant

아래는 구현할 최소 권한의 목표다. 실제 GRANT script와 통합 테스트로 검증하기 전에는 충족했다고 간주하지 않는다.

| 테이블 | `app_runtime` 허용 | 금지·제한 |
|---|---|---|
| `department` | `SELECT`, `INSERT` | MVP 상태 변경·`DELETE` |
| `account` | 필요한 컬럼 `SELECT`, `INSERT`, 상태·hash 관련 column `UPDATE` | `DELETE`, hash 조회 화면 노출 |
| `account_department_role` | `SELECT`, `INSERT`, `revoked_at` 등 종료 column `UPDATE` | `DELETE` |
| `member` | `id,name,phone,active,updated_at` SELECT; `name,phone,active` INSERT·UPDATE | `age,birth,card_uid,created_at` runtime 접근, 모든 `DELETE` |
| `department_membership` | `SELECT`, `INSERT`, 종료 metadata column `UPDATE` | 부서·구성원 FK 변경, `DELETE` |
| `nfc_card` | `SELECT`, `INSERT`, `status` column `UPDATE` | UID 변경, `DELETE` |
| `nfc_card_assignment` | `SELECT`, `INSERT`, 종료 metadata column `UPDATE` | card·membership·department 변경, `DELETE` |
| `device` | 필요한 metadata `SELECT`, `INSERT`; 이름·credential·상태·telemetry column `UPDATE` | `department_id`, `device_code` 변경, `DELETE`, hash를 일반 query로 조회 |
| `attendance_policy_version` | `SELECT`, `INSERT`, DRAFT 내용·발행 metadata `UPDATE` | 부서·version 변경, PUBLISHED 수정, `DELETE` |
| `attendance_band` | `SELECT`, DRAFT 정책에서 `INSERT`·`UPDATE`·`DELETE` | PUBLISHED 정책 변경 |
| `attendance_day` | `SELECT`, `INSERT`, 상태·마감·취소 metadata `UPDATE` | 부서·날짜·정책 변경, `DELETE` |
| `attendance_target` | `SELECT`, `INSERT`, 활성·변경 metadata `UPDATE` | scope FK 변경, `DELETE` |
| `attendance_record` | `SELECT`, `INSERT`, 정정 허용 column `UPDATE` | scope FK 변경, `DELETE` |
| `tag_event_log` | `SELECT`, `INSERT`, 선점 행의 결과 확정 `UPDATE` | request identity·scope 변경, `DELETE` |
| `audit_log` | `SELECT`, `INSERT` | `UPDATE`, `DELETE` |
| `flyway_schema_history` | `SELECT` | `INSERT`, `UPDATE`, `DELETE` |
| 레거시 `authentications`, `attendance`, `attendance_log` | 신규 runtime DML 없음 | 모든 DML |

신규 identity sequence와 `member_id_seq`에는 필요한 `USAGE`만 부여한다. `PUBLIC`의 schema create, table·sequence 권한과 생성 함수의 기본 execute는 회수한다.

### 8.4 DB가 막는 것과 못 막는 것

DB가 직접 막아야 하는 항목:

- `(device_id, request_id)`, `(department_id, attendance_date)`, `(attendance_day_id, member_id)` 중복
- 활성 역할·소속·카드 assignment의 부분 유일성
- 잘못된 상태 문자열과 필수 사유·actor 조합
- A부서 정책·membership·device를 B부서 날짜·event·audit에 연결하는 복합 FK 위반
- 역사 데이터의 cascade 삭제
- `app_runtime`의 DDL, history 변경, `member` 삭제와 레거시 DML

DB grant와 FK만으로 막지 못하는 항목:

- `app_runtime`으로 실행된 A부서 관리자의 B부서 `SELECT`
- system query가 불필요한 개인정보를 projection하는 실수
- 발행 정책 변경처럼 여러 행과 현재 상태를 함께 보는 규칙
- `SYSTEM_ADMIN`을 부서 관리자로 오인하는 Java 권한 분기

두 번째 목록은 service·Mapper 설계와 자동화된 보안 테스트가 반드시 보완한다.

---

## 9. 장치 API 보안 매트릭스

### 9.1 endpoint와 상태

| 장치 상태·인증 | `POST /api/v1/device/check-ins` | `POST /api/v1/device/credential-tests` | 저장 영향 |
|---|---|---|---|
| code·key 누락/불일치 | `401 DEVICE_UNAUTHORIZED` | `401 DEVICE_UNAUTHORIZED` | event·`last_seen_at` 없음 |
| `ACTIVE`, 유효 credential | 허용 | `409 CREDENTIAL_TEST_NOT_ALLOWED` | check-in은 업무 transaction; test event 없음 |
| `INACTIVE`, 유효 credential | `409 DEVICE_NOT_ACTIVE` | `200 CREDENTIAL_VALID` | test 성공은 현재 시험 version·시각을 원자 기록하고 `last_seen_at`도 갱신 가능 |
| `REVOKED`, 유효했던 credential | `409 DEVICE_NOT_ACTIVE` | `409 CREDENTIAL_TEST_NOT_ALLOWED` | 업무·event 없음 |
| 인증 후 상태·credential version 변경 | `409 DEVICE_STATE_CHANGED` | `409 CREDENTIAL_TEST_NOT_ALLOWED` | check-in event·시험 성공 필드 없음 |
| `device-api.enabled=false` | `503 SERVICE_UNAVAILABLE` | `503 SERVICE_UNAVAILABLE` | 인증·`last_seen_at` 포함 모든 장치 DB 쓰기 없음 |

유효 code인지, key가 틀렸는지, 장치가 존재하는지를 `401` 문구로 구분하지 않는다. credential test의 `ACTIVE`와 `REVOKED`도 같은 외부 code를 사용해 상태를 세분해 노출하지 않는다.

### 9.2 인증·입력 순서

1. `DeviceApiAvailabilityFilter`가 flag를 검사한다.
2. 인증 전 source rate limit을 적용하고 request body에는 1024 byte를 넘겨 읽지 못하는 bounded stream을 설치한다.
3. 장치 code와 key를 검증하고 상태에 맞는 endpoint인지 확인한다.
4. 인증 성공이면 짧은 별도 transaction에서 `last_seen_at`을 갱신할 수 있다.
5. transfer decoding 뒤 bounded stream이 읽은 실제 크기, content type, JSON과 schema를 검증한다. check-in은 1 KiB까지이고 credential test는 body 자체를 허용하지 않는다.
6. service 호출 직전에 `Clock`으로 `receivedAt`을 한 번 캡처한다.
7. 체크인 transaction에서 `department → device → tag event → attendance day` 규칙에 따라 잠그고 device 상태와 credential version을 다시 검증한다.
8. 인증된 device의 부서만 사용해 결과를 저장한다.

장치 body에는 부서 ID, device ID, 교사 ID와 출석 상태를 받지 않는다. 추가 JSON member와 중복 key도 거부한다.

- `X-Device-Code`와 `X-Device-Key`는 각각 정확히 한 번만 허용한다. 중복·빈 header는 다른 인증 실패와 같은 `401`이다.
- check-in은 `Content-Type: application/json` UTF-8, `Content-Encoding: identity`만 허용한다.
- UID는 구분자 없는 대문자 16진수 4·7·10 byte만 허용한다.
- `requestId`는 `[A-Za-z0-9_-]` 1~64자다.
- 빈 body, 깨진 JSON, 중복 member와 trailing token은 `400`; schema·UID·requestId 위반은 `422`; 1024 byte 초과는 parsing 전 `413`이다.

### 9.3 credential lifecycle

- 시스템 관리자가 새 장치를 한 부서에 생성하면 기본 상태는 `INACTIVE`다.
- 비밀키는 CSPRNG로 최소 128 bit의 엔트로피를 갖게 만들고 발급 시 한 번만 표시한다. 사람이 정한 문자열이나 device code에서 유도하지 않는다.
- 서버에는 검증 가능한 hash와 version, 발급 시각만 저장한다.
- code 조회 뒤 key hash를 검증할 때 timing 차이를 불필요하게 노출하지 않고, 인증 실패 응답은 원인과 무관하게 동일하게 유지한다.
- 원문 전달은 process memory에만 있는 서버 세션의 최대 5분 one-time 객체를 사용한다. 이 객체를 session DB·파일에 직렬화하지 않는다. URL·query·cookie·flash message에 넣지 않고, 최초 `GET /admin/system/devices/{deviceId}/credential-once`가 객체를 원자적으로 소비한 뒤 request-scoped view model로 옮긴다.
- 1회 화면의 렌더링 실패, 새로고침, 뒤로가기와 같은 URL 재호출에서도 원문을 다시 표시하지 않는다. 응답은 `Cache-Control: no-store`이고 브라우저 storage·service worker·history state에 저장하지 않는다.
- 키 교체는 신·구 키 중첩 없이 즉시 수행한다. 장치를 `INACTIVE`로 두고 hash 교체, version 증가, 발급 시각 갱신과 `credential_tested_version`·`credential_tested_at` 초기화를 한 transaction으로 처리한다.
- 교체 commit 직후 구 키는 무효다.
- 실제 장치가 credential test를 통과해도 자동으로 활성화하지 않는다. 성공한 test는 장치 행을 잠그고 상태가 여전히 `INACTIVE`이며 인증 principal의 version과 현재 version이 같은 경우에만 `credential_tested_version`·`credential_tested_at`을 원자 갱신한다. 상태 또는 version이 바뀌면 `409 CREDENTIAL_TEST_NOT_ALLOWED`이고 시험 증거는 불변이다.
- 활성화 service는 잠근 행의 `credential_tested_version = credential_version`과 `credential_tested_at >= credential_issued_at`을 검사한다. `last_seen_at`은 일반 인증도 갱신하는 telemetry이므로 시험 증거로 사용할 수 없다. 화면 버튼 비활성화만으로 대체하지 않는다.
- 관리자가 장치를 비활성화하면 시험 version·시각을 초기화하고 재활성화 전에 새 credential-test를 요구한다.
- `REVOKED` 장치는 새 key를 발급하거나 다시 활성화하지 않는다. 물리 장치를 재사용하려면 새 device code의 행을 만든다.
- 장치 부서를 잘못 배정했다면 기존 행을 `REVOKED`로 끝내고 올바른 부서에 새 장치를 만든다.
- 발급·교체·상태 변경·폐기는 `audit_log`에 기록하되 key·hash는 기록하지 않는다.

### 9.4 rate limit

| 단계·endpoint | key | capacity | refill |
|---|---|---:|---:|
| 인증 전 | 신뢰 가능한 effective source | 20 | 초당 1 |
| 인증 후 check-in | device ID | 10 | 초당 1 |
| 인증 후 credential test | device ID | 2 | 20초당 1 |

- 인증 전·후 bucket을 모두 통과해야 한다.
- `429 RATE_LIMITED`는 1~300초 범위의 `Retry-After`를 반환하고 새 tag event를 만들지 않는다.
- source 주소는 승인된 proxy chain에서만 전달 header를 신뢰한다.
- rate limit key와 지표에 장치 비밀키 원문을 사용하지 않는다.

### 9.5 재시도·응답 최소화

- 응답이 없거나 `429`, `500`, `503`일 때만 자동 재시도한다.
- `429`, `503`은 `Retry-After`를 지키고, `500`·무응답은 2초·5초·15초 간격을 사용한다.
- 최초 전송 이후 최대 3회만 재전송한다.
- check-in 재전송은 동일 UID와 동일 `requestId`를 사용한다.
- 결정적 `4xx`와 `200`, `201`은 자동 재시도하지 않는다.
- 모든 장치 응답은 `Cache-Control: no-store`다.
- 응답에는 교사 이름·연락처·member ID, UID, 카드 소유자, 부서 ID와 다른 부서 존재 여부를 넣지 않는다.
- access log에서 `X-Device-Key`는 전부 제거하거나 고정 문자열로 마스킹한다. 일부 prefix도 남기지 않는다.
- 인증된 카드·소속과 당일 날짜가 있어도 `attendance_target.is_target = TRUE`가 아니면 출석을 만들지 않고 `409 NOT_ATTENDANCE_TARGET`을 확정 event로 저장한다. 이를 `NOT_DEPARTMENT_MEMBER`나 `NO_ATTENDANCE_DAY`로 뭉개지 않는다.

인터넷·공유 Wi-Fi·신뢰할 수 없는 네트워크에서는 장치가 서버 인증서와 승인 CA를 검증하는 HTTPS가 필수다. HTTP는 외부 접근이 차단된 격리 내부망을 운영 책임자가 명시적으로 선택한 경우에만 허용한다.

---

## 10. 스케줄러·내부 시스템 주체

| 항목 | 보안 규칙 |
|---|---|
| 실행 경계 | `scheduler.enabled=true`일 때만 scheduler bean 생성 |
| 외부 endpoint | 만들지 않음 |
| actor | 서버가 만든 `SYSTEM`; request parameter로 받지 않음 |
| 서비스 | 자동 마감 전용 command만 호출 |
| DB 계정 | 별도 superuser가 아니라 `app_runtime` |
| 범위 | 현재 날짜보다 이전인 모든 `SCHEDULED` 날짜 |
| 무결성 | 날짜별 lock, unique, 멱등 audit key |
| 감사 | `attendance-day:{dayId}:finalize`, actor type `SYSTEM` |
| 오류 | 한 날짜 실패를 기록하고 다른 날짜 계속; 부분 transaction rollback |

관리자 화면에 “스케줄러로 실행”을 가장한 공개 command를 추가하지 않는다. 운영상 수동 재실행이 필요하면 같은 내부 service를 호출하는 별도 승인 운영 절차를 설계하고, 웹 `SYSTEM_ADMIN` 권한만으로 자동 허용하지 않는다.

---

## 11. Feature flag 매트릭스

| flag | `false`일 때 | 검사 위치 | `true`가 허용하지 않는 것 |
|---|---|---|---|
| `admin-write.enabled` | 관리자 업무 write를 503 오류 화면으로 거부; read는 허용 | MVC 진입 경계 + application command | 역할·부서·CSRF·상태 검증 우회 |
| `device-api.enabled` | 두 장치 endpoint를 인증 전 JSON 503으로 거부 | 장치 availability filter | 잘못된 key·상태·scope 우회 |
| `scheduler.enabled` | scheduler bean을 생성하지 않음 | Spring configuration | 외부 호출, 임의 날짜·권한 우회 |

- 운영 기본값은 모두 `false`다.
- login·logout과 안전한 GET은 `admin-write`의 업무 write가 아니다. 본인 비밀번호 변경과 account·부서·장치·출석 관련 DB 변경은 flag 대상이다.
- flag는 환경변수 또는 외부 Spring 설정에서 process 시작 시 읽고 immutable하게 유지한다.
- DB table, 관리자 API, 동적 refresh로 flag를 바꾸지 않는다.
- 변경은 승인 설정 수정과 controlled restart를 뜻한다.
- 재기동 시 schema target 검사, health와 해당 단계 smoke test가 다시 통과해야 한다.
- 장치 flag는 인증·rate bucket의 인증 후 상태·`last_seen_at`보다 먼저 검사한다.
- scheduler flag를 끈 상태에서 service method가 HTTP나 임의 account를 통해 호출 가능하면 요구사항 위반이다.

---

## 12. 민감정보·로그·감사

### 12.1 데이터 분류

| 등급 | 예시 | 저장 | 화면·응답 | application log |
|---|---|---|---|---|
| 비밀 | 비밀번호, 장치 key, DB·Wi-Fi 비밀번호 | hash 또는 승인 비밀 저장소; 원문 금지 | 발급 순간 외 재표시 금지 | 전면 금지 |
| 인증 파생값 | password hash, credential hash, session ID, CSRF token | 필요한 저장소 | 일반 관리자에게 금지 | 전면 금지 |
| 개인정보 | 교사 이름·연락처 | 업무 최소 필드 | 해당 부서 관리자만 | 이름 최소화, 연락처 금지 |
| 카드·출석 민감정보 | UID, 카드 연결, 출석·지각·결석·사유 | 업무·event table | 해당 부서 관리자만 | 전체 UID 금지, 내부 ID·마스킹 값 |
| 운영 metadata | device code·상태·version·`last_seen_at`, request ID | 운영 table | 권한별 최소 범위 | 허용하되 key와 결합 금지 |
| 시스템 상태 | health, DB·scheduler·backup 상태 | 운영 지표 | 시스템 관리자 또는 운영망 | 민감 설정값 제외 |

NFC UID는 비밀키는 아니지만 복제 가능한 식별자이며 개인과 연결되면 개인정보가 된다. 허용된 부서 화면도 기본적으로 끝 4자리만 표시하고, application log는 내부 card ID를 우선 사용한다. 공개 로그나 시스템 관리자 전역 화면에 전체 UID를 노출하지 않는다.

### 12.2 세 기록의 경계

| 기록 | 저장 대상 | 저장하지 않는 것 | 조회 권한 |
|---|---|---|---|
| application log | 인증 실패 수, DB timeout, correlation ID, 내부 예외 | 비밀번호·key·hash·session·CSRF, 전체 UID·연락처 | 승인 인프라 운영자; 앱 역할 자동 허용 없음 |
| `tag_event_log` | 인증·형식 검증을 통과한 check-in의 결정적 최초 결과 | 인증 실패, rate limit, malformed request, state race, 일반 관리자 변경 | 해당 부서 관리자 |
| `audit_log` | 관리자·시스템이 성공시킨 업무 변경 | 일반 태깅 중복 기록, key·hash, 불필요한 개인정보 | 해당 부서 관리자 또는 system action allowlist의 시스템 관리자 |

정상·실패·중복 태깅을 `tag_event_log`와 `audit_log`에 동시에 적재하지 않는다. credential test는 출석 업무가 아니므로 tag event와 audit 행을 만들지 않는다. 성공한 시험만 현재 `credential_tested_version`·`credential_tested_at`을 원자 기록하며, 인증 telemetry인 `last_seen_at`도 갱신할 수 있다.

### 12.3 감사 필수 action

| 범주 | 필수 action 예시 | actor |
|---|---|---|
| 시스템 | 부서 생성, account 생성·비활성화, system role 변경 | `ACCOUNT` |
| 권한 | 부서 관리자 지정·해제 | `ACCOUNT` |
| 장치 | 생성, key 발급·교체, 상태 변경, 폐기 | `ACCOUNT` |
| 교사·소속 | 추가, 기본정보 변경, 부서 제외 | `ACCOUNT` |
| 카드 | 등록, 연결, 교체, 해제, 분실, 폐기 | `ACCOUNT` |
| 정책 | 발행 | `ACCOUNT` |
| 출석 날짜 | 생성, 대상 변경, 취소 | `ACCOUNT` |
| 출석 | 수동 등록·정정·메모 변경 | `ACCOUNT` |
| 자동 마감 | 결석 생성과 날짜 마감 | `SYSTEM` |

감사 actor account ID는 세션에서, department ID는 승인된 service scope에서 정한다. form의 hidden field를 사용하지 않는다. before/after JSON은 action별 allowlist로 직렬화하고 domain 객체 전체나 request DTO를 그대로 넣지 않는다. 연락처와 UID 변경은 전체 원문 대신 변경 필드명과 마스킹 값만 남기고, password·credential hash는 필드명조차 불필요하게 복제하지 않는다.

### 12.4 상관 ID

- 관리자 요청: 서버 생성 correlation ID
- 장치 요청: 내부 device ID 또는 마스킹한 device code + `requestId`
- 자동 마감: `attendanceDayId` + audit idempotency key

correlation ID는 비밀이 아니지만 다른 principal의 데이터 조회 권한을 부여하는 토큰으로 사용하지 않는다.

### 12.5 보유·backup

개인정보, 출석, `tag_event_log`, `audit_log`와 backup의 보유기간은 아직 운영 정책으로 확정되지 않았다. 이 상태로 무기한 보존을 기본값으로 삼아서는 안 되며 파일럿 배포 전 결정해야 한다.

- backup은 운영 서버와 다른 접근 제한 위치에 둔다.
- 개인정보가 포함된 backup은 암호화한다.
- 복원 담당자와 접근 기록을 둔다.
- application log rotation과 삭제 정책도 업무 DB 보유정책과 별도로 확정한다.
- 탈퇴 교사의 UID·태깅·감사 이력 처리 방식은 역사 무결성과 개인정보 최소화 요구를 함께 검토한다.

---

## 13. 오류·정보 노출 정책

### 13.1 관리자 웹

| 상황 | 응답 | 정보 노출 |
|---|---|---|
| 미인증 관리자 URL | 로그인 redirect | 원래 안전한 GET 외 body 보존 금지 |
| 인증됐으나 역할 자체 없음 | HTML 403 | resource 존재 정보 없음 |
| 어떤 부서 역할은 있으나 대상 D 권한 없음 | HTML 404 | 존재/권한 차이를 구분하지 않음 |
| D 권한은 있으나 resource ID 없음 | HTML 404 | 같은 문구 |
| 입력 검증 실패 | 400 또는 form 오류 | 허용 필드만, 내부 SQL·class 없음 |
| 업무 상태 충돌 | 409 성격의 오류 화면 | 허용된 현재 상태만 |
| feature flag off | 503 오류 화면 | 설정값·환경변수 내용 없음 |
| 예기치 않은 오류 | 일반 500 화면 | stack trace·SQL·비밀 없음 |

PRG(Post/Redirect/Get)를 사용하더라도 성공하지 않은 변경을 성공 flash message로 표시하지 않는다.

### 13.2 장치 API

장치 API의 정확한 schema·HTTP 상태는 [device-api.yaml](./device-api.yaml)을 따른다. Spring 예외명, SQL constraint 이름과 다른 부서·교사 상세를 응답에 넣지 않는다. `GlobalRestExceptionHandler`와 MVC handler의 적용 범위를 package 또는 marker annotation으로 분리한다.

---

## 14. 부정 테스트 매트릭스

다음은 구현 완료 전에 자동화해야 하는 최소 보안 테스트다.

### 14.1 Filter chain·웹·CSRF

| 안정 ID | 시나리오 | 기대 결과 | 확인할 계층 |
|---|---|---|---|
| `SEC-CHAIN-01` | `/api/v1/device/**`와 관리자 URL에 어떤 chain이 적용되는지 추적 | device chain이 항상 먼저, 나머지만 web chain | security config |
| `SEC-CHAIN-02` | 장치 API 성공·실패 뒤 session과 `Set-Cookie` 검사 | stateless, `JSESSIONID` 생성·사용 없음 | device chain |
| `SEC-CHAIN-03` | 미인증 장치 API와 존재하지 않는 device path 호출 | JSON 401 또는 JSON 404, 로그인 redirect·HTML 없음 | device chain |
| `SEC-CHAIN-04` | CSRF 설정의 matcher 검사 | `/api/v1/device/**`만 예외, 관리자·향후 브라우저 API는 보호 | 두 chain |
| `SEC-CHAIN-05` | 유효 웹 세션만으로 check-in | 401 JSON, session을 장치 인증으로 사용하지 않음 | device chain |
| `SEC-CHAIN-06` | 유효 장치 header로 관리자 URL | 관리자 인증으로 전환되지 않음 | web chain |
| `SEC-WEB-01` | 미인증 사용자가 시스템·부서 화면 GET | 로그인 redirect, 본문 데이터 없음 | web chain |
| `SEC-WEB-02` | 미인증 사용자가 write POST | 변경 없음, 로그인/거부 처리 | web chain |
| `SEC-WEB-03` | 유효 세션이 CSRF 없이 시스템 write | 403, DB·audit 변경 없음 | CSRF |
| `SEC-WEB-04` | 유효 세션이 CSRF 없이 부서 write | 403, DB·audit 변경 없음 | CSRF |
| `SEC-WEB-05` | GET으로 상태 변경 URL 호출 | route 없음 또는 405, 변경 없음 | controller |
| `SEC-WEB-06` | `SYSTEM_ADMIN` 단독이 D의 부서 read URL | 403, 개인정보·resource 존재 정보 없음 | URL + service |
| `SEC-WEB-07` | 부서 관리자만 시스템 account·role·device write | 403 | URL + service |
| `SEC-WEB-08` | 권한 회수 후 기존 세션으로 D command | 활성 역할 재검사로 즉시 거부 | service |
| `SEC-WEB-09` | 이중 역할 계정이 배정되지 않은 D2 업무 접근 | 존재를 숨기는 404 | service + Mapper |
| `SEC-WEB-10` | 시스템 운영 집계에서 교사·UID·출석 상세 projection 시도 | schema·result mapping에 필드 없음 | system Mapper |
| `SEC-WEB-11` | 정렬 parameter에 SQL 조각 입력 | allowlist 거부, SQL 구조 불변 | controller + MyBatis |
| `SEC-WEB-12` | `admin-write=false`에서 모든 관리자 업무 write | 503, DB·audit 변경 없음 | flag + service |
| `SEC-WEB-13` | 로그인·민감 화면의 cookie와 보안 header 검사 | session `HttpOnly`·`SameSite=Lax`, HTTPS `Secure`; `nosniff`·referrer·frame 차단; 민감 응답 `no-store` | web chain + MVC response |
| `SEC-WEB-14` | 외부 origin `returnUrl`, cross-origin credential request와 CORS preflight | 외부 redirect 거부, same-origin `/admin/**`만 허용, 광범위 credential CORS header 없음 | controller + web chain |
| `SEC-WEB-15` | `SYSTEM_ADMIN` 단독이 D의 부서 write URL | 403, DB·audit 변경 없음 | URL + service |

### 14.2 부서 IDOR

각 테스트는 D1 관리자, D2 관리자, 두 부서의 실제 기존 ID와 존재하지 않는 ID를 따로 사용한다. HTTP 응답만 보지 않고 D2 row가 반환·변경되지 않았고 감사 행도 생기지 않았는지 확인한다.

| 안정 ID | 변조 대상 | 시나리오 | 기대 결과·검증 계층 |
|---|---|---|---|
| `SEC-IDOR-DEPARTMENT-01` | 경로 D | D1 세션으로 `/admin/departments/{D2}`와 모든 목록 진입 | 존재하지 않는 부서와 같은 404; service scope |
| `SEC-IDOR-TEACHER-01` | `memberId`·membership | D1 경로에 D2 교사 ID를 넣어 상세·수정·부서 제외 | 404, 변경·audit 없음; service + membership-scoped Mapper |
| `SEC-IDOR-CARD-01` | card·assignment | D1 교사 경로에 D2 카드·assignment ID를 넣어 연결·교체·종료 | 404, 카드 상태 불변; service + Mapper + 복합 FK |
| `SEC-IDOR-INBOX-01` | `UNKNOWN_UID`/`INACTIVE_CARD` event·AVAILABLE card | D1 `/cards/inbox` command에 D2 tag event 또는 카드 ID 입력 | 404, 연결 없음; device/event/card scope Mapper |
| `SEC-IDOR-POLICY-01` | `policyId`·band | D1 경로에 D2 정책·구간 ID를 넣어 조회·수정·발행 | 404, 정책 불변; service + policy Mapper |
| `SEC-IDOR-DAY-01` | `dayId`·target | D1 경로에 D2 출석 날짜·대상 ID를 넣어 조회·취소·대상 변경 | 404, 날짜·대상 불변; service + day Mapper |
| `SEC-IDOR-RECORD-01` | `dayId`+`memberId`·record | D1 경로에서 D2 기록을 수동 등록·정정·메모 변경 | 404, record·audit 없음; service + day/record Mapper |
| `SEC-IDOR-STATISTICS-01` | 기간·교사 query | D1 통계 query에 D2 교사 ID 또는 부서 parameter 추가 | D1 결과만, D2 건수 0; scoped aggregate Mapper |
| `SEC-IDOR-DEVICE-01` | `deviceId` | D1 카드 등록함에 D2 device/event ID를 넣거나 시스템 장치 URL 접근 | 부서 command는 404, 시스템 URL은 403; URL + Mapper |
| `SEC-IDOR-HISTORY-01` | audit·tag event ID | D1 `/history` query에 D2 ID·filter를 넣음 | D1 행만, 직접 ID는 404; 탭별 별도 scoped Mapper |
| `SEC-IDOR-QUERY-01` | query `departmentId` | D1 경로에서 query를 D2로 변조 | query를 권한 근거로 사용하지 않고 D1 scope 또는 400 |
| `SEC-IDOR-HIDDEN-01` | hidden actor·department | D1 form에 D2 또는 다른 `actorAccountId` hidden field 삽입 | 400 또는 무시; 세션 actor와 경로 D만 사용 |
| `SEC-IDOR-BODY-01` | body resource·department | D1 command body에 D2의 ID와 department를 삽입 | 400 또는 D1-scoped 404, D2 변경 없음 |
| `SEC-IDOR-CHILD-01` | 부모·자식 혼합 | D1 부모 경로에 D2 자식 ID를 섞어 write | 영향 행 0, 전체 rollback, audit 없음 |
| `SEC-IDOR-SERVICE-01` | Controller 우회 | service 통합 테스트에서 D1 principal과 D2 command를 직접 호출 | active role 검사에서 거부, Mapper 호출 전 종료 |
| `SEC-IDOR-MAPPER-01` | service 우회 | D1 scope parameter로 D2 기존 ID를 Mapper에 직접 전달 | 결과 0행·영향 0행 |
| `SEC-IDOR-DB-01` | 교차 부서 FK | A부서 자식 행을 B부서 부모에 직접 연결 | 복합 FK 위반, commit 실패 |

### 14.3 계정·세션

| 안정 ID | 시나리오 | 기대 결과 |
|---|---|---|
| `SEC-AUTH-01` | 존재하지 않는 username과 잘못된 password | 같은 문구·유사 처리 시간 |
| `SEC-AUTH-02` | `DISABLED` 계정 로그인 | 일반 인증 실패, 새 세션 없음 |
| `SEC-AUTH-03` | 5회 초과 username+source 시도 | rate limit, 계정 존재 노출 없음 |
| `SEC-AUTH-04` | 한 source가 여러 username을 순회 | source bucket으로 제한 |
| `SEC-AUTH-05` | 위조 `X-Forwarded-For` | 비신뢰 proxy에서는 무시 |
| `SEC-AUTH-06` | 12자 미만, 64 code point 초과 또는 72 UTF-8 byte 초과 password | 잘라 저장하지 않고 거부 |
| `SEC-AUTH-07` | 로그인 전후 session ID 비교 | 성공 시 교체 |
| `SEC-AUTH-08` | CSRF 없는 logout | 거부, 세션 유지 |
| `SEC-AUTH-09` | 정상 logout 뒤 기존 cookie 재사용 | 인증 실패 |
| `SEC-AUTH-10` | idle 30분·absolute 8시간 경계 | 만료 후 재인증 요구 |
| `SEC-AUTH-11` | fresh DB·artifact·migration으로 최초 기동 | 공개 기본 계정·비밀번호·공통 hash가 없고 승인 bootstrap 없이는 로그인 가능한 계정이 생기지 않음 |
| `SEC-AUTH-12` | 회원가입 초대 token의 재사용·만료·교체·DB/log 검사 | 구현됐다면 hash만 저장, 최대 30분·1회성·새 발급 시 구 token 무효; 모델 미구현이면 계정 생성·초대 command와 성공 UI 비활성 |
| `SEC-AUTH-13` | password reset token 재사용·만료·평문 임시 비밀번호 경로 | 구현됐다면 최대 30분·1회성이고 평문/token은 DB·audit·log에 없음; 모델 미구현이면 reset command 비활성 |

기존 세션 강제 만료가 MVP 범위 밖이라는 사실도 별도 인수 테스트와 운영 문서에서 명시한다. 계정 비활성화 직후 기존 세션이 즉시 사라진다고 잘못 테스트해서는 안 된다.

### 14.4 장치

| 안정 ID | 시나리오 | 기대 결과 | DB 확인 |
|---|---|---|---|
| `SEC-DEV-01` | 헤더 누락·중복, 잘못된 code/key | 401 동일 body | event·`last_seen_at` 없음 |
| `SEC-DEV-02` | 웹 세션만으로 check-in | 401 JSON, redirect 없음 | 변경 없음 |
| `SEC-DEV-03` | 장치 credential로 관리자 URL | 로그인/거부, 장치 권한 없음 | 변경 없음 |
| `SEC-DEV-04` | `INACTIVE` check-in | 409 `DEVICE_NOT_ACTIVE` | event·record 없음 |
| `SEC-DEV-05` | `ACTIVE` credential test | 409 `CREDENTIAL_TEST_NOT_ALLOWED` | event·attendance 없음 |
| `SEC-DEV-06` | `REVOKED` 두 endpoint | 각각 계약된 409 | event·attendance 없음 |
| `SEC-DEV-07` | `INACTIVE` credential test 성공 | 200 제한 응답 | 현재 시험 version·시각과 `last_seen_at`만 갱신, 업무 행 없음 |
| `SEC-DEV-08` | test 성공 뒤 자동 check-in | 여전히 INACTIVE이므로 거부 | 자동 상태 전환 없음 |
| `SEC-DEV-09` | 인증 뒤 key rotate·상태 변경과 check-in 경합 | 409 `DEVICE_STATE_CHANGED` | 새 event 없음 |
| `SEC-DEV-10` | 구 key로 교체 commit 후 요청 | 401 | 변경 없음 |
| `SEC-DEV-11` | 장치 body에 departmentId·memberId 추가 | schema 오류 | event·attendance·시험 성공 필드 없음; 선행 인증의 `last_seen_at`만 변경 가능 |
| `SEC-DEV-12` | D1 장치가 D2 UID·resource를 태깅 | D1 범위 업무 결과만; D2 정보 없음 | D2 record 없음 |
| `SEC-DEV-13` | 동일 requestId·동일 UID 재전송 | 최초 HTTP/body 재현 | event 한 행 |
| `SEC-DEV-14` | 동일 requestId·다른 UID | 409 conflict | 최초 event 불변 |
| `SEC-DEV-15` | 1024 byte 초과·중복 JSON key·알 수 없는 field | parsing/schema 단계 거부 | event 없음 |
| `SEC-DEV-16` | 인증 전 source bucket 초과 | 429 + `Retry-After` | event·telemetry 없음 |
| `SEC-DEV-17` | check-in 장치 bucket 초과 | 429 + `Retry-After` | 초과 요청 event 없음 |
| `SEC-DEV-18` | credential-test bucket 초과 | 429 + `Retry-After` | 초과 요청 event 없음 |
| `SEC-DEV-19` | `device-api=false`에서 유효 key 요청 | 인증 전 503 | `last_seen_at` 포함 변경 없음 |
| `SEC-DEV-20` | access/error log 검사 | key·전체 UID·연락처 없음 | log scan 통과 |
| `SEC-DEV-21` | device의 `department_id` update command | 거부 | 기존 값·이력 유지 |
| `SEC-DEV-22` | `REVOKED` 행 재활성화·키 재발급 | 거부 | 상태 불변 |
| `SEC-DEV-23` | key 1회 화면 최초 GET 뒤 새로고침·뒤로가기·URL 재호출 | 원문 재표시 없음, `no-store` | hash 외 지속 저장 없음 |
| `SEC-DEV-24` | 최근 `last_seen_at`은 있지만 현재 version의 시험 필드가 없는 INACTIVE 장치를 직접 활성화 | credential-test 증거가 없어 거부 | 상태 불변 |
| `SEC-DEV-25` | 카드·소속·날짜는 유효하지만 `is_target=false` 또는 target 없음 | 409 `NOT_ATTENDANCE_TARGET`, 개인정보 없음 | record 없음, 확정 event 한 행 |
| `SEC-DEV-26` | 미등록 UID이며 당일 날짜도 없음 | 날짜보다 UID를 먼저 판정해 404 `UNKNOWN_UID` | D의 등록함 event 한 행 |
| `SEC-DEV-27` | D의 `AVAILABLE` 카드가 다시 태깅됨 | 409 `INACTIVE_CARD`, 소유자 정보 없음 | D의 재사용 등록함 event, record 없음 |

### 14.5 스케줄러·DB 주체

| 안정 ID | 시나리오 | 기대 결과 |
|---|---|---|
| `SEC-SYS-01` | `scheduler=false` 기동 | scheduler bean·실행 없음 |
| `SEC-SYS-02` | 자동 마감 HTTP 경로 탐색·호출 | 공개 route 없음 |
| `SEC-SYS-03` | form/body에서 actor type `SYSTEM` 지정 | 입력 거부·무시, SYSTEM audit 없음 |
| `SEC-SYS-04` | 같은 날짜 동시·반복 마감 | record와 SYSTEM audit 각각 한 건 |
| `SEC-DB-01` | `app_runtime`으로 DDL | permission denied |
| `SEC-DB-02` | `app_runtime`으로 Flyway history write | permission denied |
| `SEC-DB-03` | `app_runtime`으로 `member DELETE` | permission denied |
| `SEC-DB-04` | `app_runtime`으로 레거시 3개 테이블 DML | permission denied |
| `SEC-DB-05` | `app_runtime`으로 device `department_id` 직접 update | column privilege로 거부 |
| `SEC-DB-06` | `app_runtime`으로 UID·scope FK 직접 update | column privilege 또는 FK로 거부 |
| `SEC-DB-07` | A부서 자식 행을 B부서 부모와 연결 | 복합 FK 위반 |
| `SEC-DB-08` | `PUBLIC`으로 schema create·업무 함수 execute | permission denied |
| `SEC-DB-09` | runtime으로 `audit_log UPDATE/DELETE` | permission denied |
| `SEC-DB-10` | runtime으로 `tag_event_log DELETE` | permission denied |
| `SEC-DB-11` | `migration_owner` credential로 웹 앱 기동 | 배포 설정 검사에서 실패 |
| `SEC-DB-12` | 컷오버 뒤 `cutover_writer` 로그인 | 회수되어 실패 |

`SEC-DB-05`·`SEC-DB-06` 같은 column-level grant가 실제 권한 script에 구현되지 않았다면 “service가 거부하므로 안전하다”로 테스트를 대체하지 않는다. grant 목표와 구현이 다르면 차이를 배포 blocker로 기록한다.

### 14.6 민감정보·감사

| 안정 ID | 시나리오 | 기대 결과 |
|---|---|---|
| `SEC-LOG-01` | 로그인·장치 인증 실패와 stack trace 발생 | password/key/hash/session/CSRF 없음 |
| `SEC-LOG-02` | 카드·출석 command logging | 전체 UID·연락처 없음 |
| `SEC-LOG-03` | 장치 최초 응답 저장 | 응답에 교사·UID·부서 ID 없음 |
| `SEC-LOG-04` | 카드 교체·부서 제외·수동 정정 | 실제 session actor, reason, 허용 before/after 존재 |
| `SEC-LOG-05` | 일반 태깅 | `tag_event_log`만 존재하고 동일 `audit_log` 없음 |
| `SEC-LOG-06` | credential test | tag event·audit 없음 |
| `SEC-LOG-07` | 장치 key 발급·교체 감사 | action·version은 있고 key/hash는 없음 |
| `SEC-LOG-08` | D1 관리자의 D2 audit ID 조회 | 404 |
| `SEC-LOG-09` | 시스템 감사 화면 | system action allowlist만, 부서 출석 개인정보 없음 |
| `SEC-LOG-10` | backup artifact 접근·복원 | 승인 계정만 접근, 암호화와 접근 기록 확인 |
| `SEC-LOG-11` | 교사 연락처·카드 변경 감사 before/after | 변경 필드와 마스킹 값만, 전체 연락처·UID 없음 |

---

## 15. 구현·검토 체크리스트

### 15.1 코드 구조

- [ ] 장치 API와 관리자 웹에 서로 다른 두 `SecurityFilterChain`이 있음
- [ ] 장치 chain이 먼저 적용되고 unknown device path도 JSON으로 끝남
- [ ] CSRF 예외가 `/api/v1/device/**`보다 넓지 않음
- [ ] Controller가 Mapper를 직접 호출하지 않음
- [ ] `SYSTEM_ADMIN`을 부서 업무에 허용하는 공통 `isAdmin()` 분기가 없음
- [ ] service가 정확한 D의 활성 `DEPARTMENT_ADMIN`을 검사함
- [ ] scheduler의 `SYSTEM` actor를 HTTP 입력으로 만들 수 없음
- [ ] system query와 department-scoped query가 분리됨

### 15.2 SQL·DB

- [ ] 모든 부서 업무 Mapper가 `department_id`를 필수 parameter로 받음
- [ ] child ID 조회가 부모 부서 join 없이 수행되지 않음
- [ ] 사용자 입력을 MyBatis `${}`에 넣지 않음
- [ ] 신규 Mapper에 `SELECT *`가 없음
- [ ] update 영향 행 수를 검증함
- [ ] 역사 데이터는 `ON DELETE RESTRICT`이고 runtime DELETE 권한이 없음
- [ ] `app_runtime`, `migration_owner`, `cutover_writer`, `legacy_writer` credential이 분리됨
- [ ] 권한 script가 table·column·sequence·함수까지 재현 가능함
- [ ] PostgreSQL 실제 환경에서 grant 부정 테스트를 통과함

### 15.3 운영

- [ ] 운영 기본 feature flag가 모두 `false`
- [ ] flag 변경이 controlled restart와 schema·health 재검사를 거침
- [ ] 비밀값이 저장소·artifact·startup log에 없음
- [ ] HTTPS 또는 승인된 격리망 경계가 확정됨
- [ ] reverse proxy 신뢰 범위가 명시됨
- [ ] 세션 idle 30분·absolute 8시간과 cookie 속성이 적용됨
- [ ] 로그인·장치 rate limit이 여러 인스턴스를 쓰는 경우에도 일관되게 동작함
- [ ] application log와 backup 접근자가 별도로 제한됨
- [ ] 개인정보·event·audit·backup 보유기간이 배포 전에 확정됨
- [ ] bootstrap·회원가입 초대·password reset 전달 절차와 필요한 schema가 확정됨

---

## 16. 보안 완료 조건과 남은 위험

보안 완료는 화면에서 메뉴가 보이지 않는 상태가 아니다. 다음 조건을 모두 충족해야 한다.

1. 이 문서의 웹·IDOR·장치·scheduler·DB·로그 부정 테스트가 자동화되어 실제 PostgreSQL에서 통과한다.
2. `SYSTEM_ADMIN` 단독 계정, D1 관리자, D2 관리자, 이중 역할 계정, 각 상태의 장치 fixture를 따로 사용한다.
3. HTTP 응답뿐 아니라 row count, audit·event 부재와 원 transaction rollback을 검증한다.
4. 운영 GRANT script와 실제 DB 권한이 동일함을 확인한다.
5. `device-api.yaml`과 실제 filter·exception handler의 HTTP/code가 contract test에서 일치한다.
6. feature flag를 모두 끈 초기 기동과 단계별 controlled restart를 리허설한다.
7. 민감정보 보유기간, network/TLS, bootstrap·reset 절차가 승인되지 않으면 파일럿 운영을 시작하지 않는다.

남는 MVP 위험은 다음과 같다.

- 공용 `app_runtime` 때문에 애플리케이션 또는 SQL injection이 침해되면 DB grant가 부서별 기밀성을 분리하지 못한다.
- 시스템 관리자는 자신에게 부서 관리자 역할을 지정할 수 있고 MVP에 2인 승인이 없다.
- 비활성화·비밀번호 재설정 직후 기존 세션의 강제 만료가 없다.
- NFC UID는 복제 가능하므로 카드 소지와 실제 사람의 강한 동일성을 보장하지 않는다.
- 단일 인스턴스 in-memory rate limiter라면 재기동 시 bucket이 초기화된다. MVP 단일 인스턴스에서는 수용할 수 있지만 다중 인스턴스로 전환할 때 공유 저장소 또는 edge 제한을 재설계해야 한다.
- 보유기간이 확정되지 않으면 개인정보 최소화와 삭제 요구를 검증할 수 없다.

이 위험을 문서에서 숨기거나 `SYSTEM_ADMIN`, CSRF, DB FK 하나로 해결되었다고 표현해서는 안 된다.
