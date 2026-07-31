# Attend 관리자 웹 UI 명세

> Spring MVC + Thymeleaf 기반 반응형 관리자 웹

## 0. 문서 정보

| 항목 | 내용 |
|---|---|
| 문서 상태 | 구현 기준 초안 v1.0 |
| 기준 시간대 | `Asia/Seoul` |
| 대상 릴리스 | 현장 사용 가능한 1차 운영 버전(MVP) |
| 기준 문서 | [PROJECT_DEFINITION.md](./PROJECT_DEFINITION.md), [ARCHITECTURE.md](./ARCHITECTURE.md), [DATABASE_DESIGN.md](./DATABASE_DESIGN.md) |
| 관련 구현 문서 | [device-api.yaml](./device-api.yaml), [SECURITY_MATRIX.md](./SECURITY_MATRIX.md), [TEST_PLAN.md](./TEST_PLAN.md), [ATTENDANCE_DDL.sql](./ATTENDANCE_DDL.sql), [MIGRATION_PLAN.md](./MIGRATION_PLAN.md) |

### 0.1 문서 목적

이 문서는 관리자 웹의 정보 구조, 역할별 내비게이션, 화면 상태, 입력 검증, 안전장치와 화면별 인수 기준을 정의한다. HTML 모양만 정하는 문서가 아니다. 화면에서 허용하는 작업과 서버의 인가·트랜잭션 규칙이 서로 어긋나지 않게 하는 구현 계약이다.

### 0.2 핵심 결론

1. 관리자 웹은 Spring MVC와 Thymeleaf로 서버 렌더링한다. 별도 SPA는 만들지 않는다.
2. JavaScript는 동적 지각 단계 편집, 제출 중 상태, 선택적 새로고침 같은 점진적 향상에만 사용한다. JavaScript가 없어도 핵심 업무를 완료할 수 있어야 한다.
3. `SYSTEM_ADMIN`과 `DEPARTMENT_ADMIN`의 작업 공간을 명시적으로 분리한다. `SYSTEM_ADMIN`이라는 이유만으로 부서의 교사·카드·정책·출석을 조회하거나 변경할 수 없다.
4. 부서 업무 URL은 항상 `departmentId`를 포함하지만, URL의 값은 권한의 근거가 아니다. Controller, application service와 SQL의 `department_id` 조건에서 같은 부서 권한을 다시 확인한다.
5. 모든 상태 변경은 CSRF 보호를 받는 `POST`와 PRG(Post/Redirect/Get)를 기본으로 한다. 성공 응답은 `303 See Other`로 결과 화면에 이동한다.
6. 화면에서 숨긴 버튼은 보안 통제가 아니다. 직접 URL 요청도 서버가 거부해야 한다.
7. 물리 삭제 UI를 제공하지 않는다. 교사는 `부서 제외`, 카드는 `연결 해제·분실·폐기`, 날짜는 `취소`, 권한은 `해제`, 장치는 `폐기`로 상태와 이력을 보존한다.
8. 출석 상태와 지각 단계는 관리자가 직접 고르지 않는다. 실제 출석 시각과 해당 날짜에 고정된 정책으로 서버가 계산한다.
9. 장치 자격증명 시험은 실제 `INACTIVE` 장치가 장치 API를 호출해 수행한다. 브라우저가 장치 키를 받아 대신 시험하지 않는다.

### 0.3 범위 밖

- 일반 교사 개인 화면과 `USER` 역할
- 네이티브 모바일 앱
- 부서 비활성화·재활성화
- 발행 정책 폐기
- 장치의 부서 재배정
- CSV·Excel 내보내기
- 운영 feature flag를 웹에서 즉시 바꾸는 기능
- 계정 비활성화·비밀번호 재설정과 동시에 기존 세션을 강제 만료하는 기능
- 대규모 실시간 관제, WebSocket과 별도 프런트엔드 상태 저장소

---

## 1. 사용자, 권한과 작업 공간

### 1.1 사용자 역할

| 사용자 | 작업 공간 | 허용 범위 |
|---|---|---|
| 비로그인 사용자 | 로그인 | 로그인만 가능 |
| `SYSTEM_ADMIN` | 시스템 관리 | 부서, 계정, 부서 관리자 권한, 장치, 시스템 범위 감사와 전체 운영 상태 |
| `DEPARTMENT_ADMIN` | 권한이 있는 각 부서 | 대시보드, 교사·소속, 카드, 정책, 출석 날짜·대상자, 출석 정정, 부서 범위 감사·태깅 이력 |
| 두 권한을 모두 가진 계정 | 시스템 관리와 허용된 부서 중 명시적으로 선택 | 현재 선택한 작업 공간의 권한만 사용 |

`SYSTEM_ADMIN` 단독 계정에는 부서 대시보드로 진입하는 링크를 보여주지 않는다. 해당 계정이 특정 부서의 활성 `DEPARTMENT_ADMIN` 권한도 가질 때만 그 부서를 선택할 수 있다. 시스템 관리 화면에서 부서 상세를 조회하는 것과 해당 부서의 출석 업무를 수행하는 것은 서로 다른 권한이다.

### 1.2 권한 판정 원칙

- `/admin/system/**`: 활성 `SYSTEM_ADMIN`만 접근한다.
- `/admin/departments/{departmentId}/**`: 해당 부서의 활성 `DEPARTMENT_ADMIN`만 접근한다.
- `/admin/account/**`: 로그인한 본인의 계정 작업만 접근한다.
- 다른 부서의 식별자를 넣은 요청은 리소스 존재 여부를 드러내지 않도록 `404`로 처리한다.
- 올바른 시스템 리소스지만 역할 자체가 없는 요청은 `403`으로 처리한다.
- 계정 ID, 처리 관리자 ID, 부서 ID를 hidden input으로 받아 권한 근거로 사용하지 않는다.
- 작업자 계정 ID는 Spring Security 인증 주체에서 얻는다.
- `admin-write.enabled=false`이면 역할이 있어도 모든 관리자 업무 쓰기를 `503`으로 거부한다. 화면에도 읽기 전용 운영 모드 배너를 표시한다.

### 1.3 작업 공간 선택

로그인 후 이동 규칙은 다음과 같다.

| 보유 권한 | 첫 화면 |
|---|---|
| `SYSTEM_ADMIN`만 있음 | `/admin/system` |
| 한 부서의 `DEPARTMENT_ADMIN`만 있음 | 해당 부서 대시보드 |
| 여러 부서 또는 시스템·부서 권한을 함께 보유 | `/admin/workspaces` 작업 공간 선택 |
| 활성 작업 공간이 없음 | 권한 없음 안내와 로그아웃만 제공 |

상단 헤더에는 현재 작업 공간을 항상 표시한다.

```text
시스템 관리
아동부 · 부서 관리
유치부 · 부서 관리
```

여러 작업 공간이 있으면 헤더의 `작업 공간 전환` 메뉴에서 이동한다. 전환은 권한을 합치는 동작이 아니며 현재 URL과 내비게이션 범위만 바꾼다.

---

## 2. 정보 구조와 내비게이션

### 2.1 공통 상단 영역

- 서비스명 `Attend`
- 현재 작업 공간 이름과 유형
- 서버 기준 현재 시각과 `Asia/Seoul`
- 계정 메뉴: 내 비밀번호 변경, 작업 공간 전환, 로그아웃
- 읽기 전용 운영 모드 또는 시스템 장애 배너
- 모바일 내비게이션 열기 버튼

### 2.2 시스템 관리 내비게이션

| 순서 | 메뉴 | 경로 | 설명 |
|---:|---|---|---|
| 1 | 시스템 홈 | `/admin/system` | 설정 미완료, 장치와 운영 경고 요약 |
| 2 | 부서 | `/admin/system/departments` | 부서 생성·조회 |
| 3 | 관리자 계정 | `/admin/system/accounts` | 계정 생성·상태·부서 권한 관리 |
| 4 | 장치 | `/admin/system/devices` | 장치 등록·시험·활성화·키 교체·폐기 |
| 5 | 운영 상태 | `/admin/system/operations` | health, DB, scheduler, backup, feature flag |
| 6 | 시스템 감사 | `/admin/system/audit` | 부서·계정·권한·장치 변경만 조회 |

시스템 감사 화면은 부서 출석 업무의 상세 before/after를 자동으로 보여주지 않는다. 시스템 관리자가 특정 부서의 관리자 권한도 보유한 경우에만 그 부서 작업 공간에서 부서 감사 이력을 조회한다.

### 2.3 부서 관리 내비게이션

| 순서 | 메뉴 | 경로 | 설명 |
|---:|---|---|---|
| 1 | 오늘의 출석 | `/admin/departments/{departmentId}` | 오늘 상태와 교사별 현황 |
| 2 | 교사 | `/admin/departments/{departmentId}/teachers` | 명단, 카드, 개인 통계, 부서 제외 |
| 3 | 카드 등록함 | `/admin/departments/{departmentId}/cards/inbox` | 자기 부서 장치에서 태깅된 미등록·재사용 가능 카드 |
| 4 | 출석 정책 | `/admin/departments/{departmentId}/policies` | 초안, 발행 버전과 동적 지각 단계 |
| 5 | 출석 날짜 | `/admin/departments/{departmentId}/attendance-days` | 날짜, 대상자, 결과와 정정 |
| 6 | 이력 | `/admin/departments/{departmentId}/history` | 관리자·시스템 감사와 태깅 이벤트 탭 |

### 2.4 주요 MVC 경로

아래 경로는 화면 구조의 기준이다. 구현 중 임의로 전역 ID 조회 경로를 추가하지 않는다.

| Method | 경로 | 용도 |
|---|---|---|
| `GET`, `POST` | `/login` | 로그인 |
| `POST` | `/logout` | 로그아웃 |
| `GET` | `/admin/workspaces` | 작업 공간 선택 |
| `GET`, `POST` | `/admin/account/password` | 본인 비밀번호 변경 |
| `GET`, `POST` | `/admin/system/departments...` | 부서 목록·생성·상세 |
| `GET`, `POST` | `/admin/system/accounts...` | 계정 목록·생성·상태·권한 |
| `GET`, `POST` | `/admin/system/devices...` | 장치 목록·등록·상태·자격증명 |
| `GET` | `/admin/system/operations` | 전체 운영 상태 |
| `GET` | `/admin/system/audit` | 시스템 범위 감사 |
| `GET` | `/admin/departments/{departmentId}` | 오늘의 출석 |
| `GET`, `POST` | `/admin/departments/{departmentId}/teachers...` | 교사·카드·부서 제외 |
| `GET`, `POST` | `/admin/departments/{departmentId}/cards/inbox...` | 미등록·재사용 가능 카드 연결 |
| `GET`, `POST` | `/admin/departments/{departmentId}/policies...` | 정책 초안·발행 |
| `GET`, `POST` | `/admin/departments/{departmentId}/attendance-days...` | 날짜·대상자·정정 |
| `GET` | `/admin/departments/{departmentId}/history` | 부서 감사·태깅 이력 |

HTML form의 상태 변경은 `_method`로 `PUT`·`DELETE`를 흉내 내지 않고 의미가 분명한 command 경로에 `POST`한다. 예시는 `POST .../publish`, `POST .../exclude`, `POST .../cancel`, `POST .../revoke`다.

---

## 3. 공통 화면·폼 계약

### 3.1 표시 언어와 시간

- 사용자 표시 언어는 한국어다.
- 업무 날짜는 `YYYY.MM.DD`, 상세 시각은 `YYYY.MM.DD HH:mm:ss`로 표시한다.
- 모든 날짜·시각 옆 또는 화면 상단에 `Asia/Seoul` 기준임을 명시한다.
- 정책 시각 입력은 `HH:mm`, 수동 실제 출석 시각은 초까지 입력 가능한 `datetime-local`을 기본으로 한다.
- 저장과 판정은 브라우저 시간대가 아니라 서버의 `Clock`과 `Asia/Seoul` 규칙을 따른다.
- 현재 시각과 출석 상태를 JavaScript만으로 결정하지 않는다.

### 3.2 공통 입력 상한

DB의 `TEXT`가 무제한에 가깝더라도 웹 입력은 운영상 필요한 범위로 제한한다.

| 입력 | 필수 | 웹 상한·정규화 |
|---|---:|---|
| 부서·정책·장치 이름 | 예 | 앞뒤 공백 제거, 1~100자 |
| 계정 사용자명 | 예 | 앞뒤 공백 제거, 1~100자, 대소문자 무시 중복 금지 |
| 교사 이름 | 예 | 앞뒤 공백 제거, 1~255자 |
| 전화번호 | 아니요 | 빈 문자열은 `NULL`, 최대 255자 |
| 지각 단계 표시명 | 예 | 앞뒤 공백 제거, 1~50자 |
| 사유 | 작업별 | 앞뒤 공백 제거, 1~500자 |
| 출석 비고 | 아니요 | 빈 문자열은 `NULL`, 최대 1,000자 |
| 장치 코드 | 예 | 앞뒤 공백 제거, 1~100자, 전역 유일 |
| 비밀번호 | 작업별 | 12~64 Unicode code point이면서 UTF-8 72 byte 이하, 흔한·유출 비밀번호 차단 |

비밀번호에 대문자·소문자·숫자·특수문자 조합을 기계적으로 강제하지 않는다. 정확한 금지 목록, 반복 로그인 제한과 예외 처리는 `SECURITY_MATRIX.md`를 단일 기준으로 사용한다. HTML 속성만 믿지 않고 서버에서 동일하게 검증한다.

### 3.3 상태 이름

| 내부 상태 | 화면 표시 |
|---|---|
| `PRESENT` | 정상 출석 |
| `LATE` | 저장된 구간 표시명, 예: `1차 지각` |
| 오늘 기록 없음 | 미출석 |
| `ABSENT` | 결석 |
| `SCHEDULED`, 미래 | 예정 |
| `SCHEDULED`, 오늘 | 진행일 |
| `FINALIZED` | 마감 완료 |
| `CANCELED` | 취소 |
| 정책 `DRAFT` | 초안 |
| 정책 `PUBLISHED` | 발행됨 |
| 카드 `AVAILABLE` | 연결 가능 |
| 카드 `ACTIVE` | 사용 중 |
| 카드 `LOST` | 분실 |
| 카드 `RETIRED` | 폐기 |
| 장치 `INACTIVE` | 비활성 |
| 장치 `ACTIVE` | 활성 |
| 장치 `REVOKED` | 폐기 |

상태는 색만으로 구분하지 않는다. 텍스트, 아이콘 또는 모양을 함께 사용한다. 오늘 기록 없음은 자동 마감 전이므로 `결석`이라고 표시하지 않는다.

### 3.4 PRG와 응답

| 상황 | 응답과 화면 동작 |
|---|---|
| 조회 성공 | `200` HTML |
| 폼 형식·업무 검증 실패 | 쓰기 없이 같은 폼을 `200`으로 다시 렌더링하고 필드 오류 표시 |
| 쓰기 성공 | `303`으로 canonical 상세·목록 화면 이동 후 일회성 flash 성공 메시지 |
| 동시 변경·상태 전이 충돌 | 쓰기 없이 `409` 화면, 현재 상태와 `새로고침` 제공 |
| 다른 부서 식별자 | `404` |
| 역할 부족 | `403` |
| CSRF 실패 | `403`, 입력값을 저장하지 않고 다시 로그인·이전 화면 이동 안내 |
| feature flag 비활성 | `503`, `Retry-After`를 임의로 약속하지 않고 읽기 화면 링크 제공 |
| 예상하지 못한 서버 오류 | 상관 ID가 있는 `500` 오류 화면, 내부 예외·SQL 미노출 |

검증 실패 때 입력값을 유지하되 비밀번호와 장치 비밀키는 다시 채우지 않는다. 성공 flash message는 새로고침 후 재표시하지 않는다.

### 3.5 제출과 중복 방지

- 모든 상태 변경 form에 CSRF token을 포함한다.
- 제출이 시작되면 버튼 문구를 `처리 중…`으로 바꾸고 `aria-busy="true"`를 설정한다.
- JavaScript가 있으면 같은 form의 재제출 버튼을 잠시 비활성화한다.
- 브라우저 버튼 비활성화는 무결성 보장이 아니다. 서버는 행 잠금, 유일 제약과 현재 상태 재검증으로 이중 제출을 처리한다.
- 민감한 작업은 확인 화면에서 최신 대상 상태를 다시 읽고, 제출 시 서버가 다시 잠가 검증한다.
- 성공 후 브라우저 새로고침은 GET만 반복해야 한다.

### 3.6 오류, 빈 화면과 로딩

모든 목록·상세 화면은 다음 상태를 구별한다.

| 상태 | 표현 |
|---|---|
| 데이터 없음 | 오류가 아닌 이유와 다음 가능한 작업을 설명하는 빈 상태 |
| 필터 결과 없음 | `필터 초기화`와 전체 건수 제공 |
| 일부 운영 데이터 수집 실패 | 나머지 데이터를 유지하고 해당 카드에 `확인 불가` 표시 |
| 전체 조회 실패 | 상관 ID, 재시도와 안전한 상위 화면 링크 |
| 제출 중 | 버튼 내부 진행 상태. 처리 완료로 오해할 성공 색상 금지 |
| 장기 처리 | 10초 이상 응답이 없으면 중복 클릭 대신 기다림·상태 확인 안내 |

서버 렌더링 첫 페이지에 의미 없는 skeleton을 만들지 않는다. 브라우저 탐색 중에는 기본 로딩 동작을 사용한다. 선택적으로 목록 일부를 갱신할 때만 해당 영역에 `aria-live="polite"` 상태를 둔다.

### 3.7 목록과 개인정보

- 교사 목록 기본 페이지 크기는 20명, 감사·태깅 이력은 50건으로 한다.
- 필터와 정렬은 query parameter로 표현하고 PRG 후 가능한 범위에서 유지한다.
- 정렬 컬럼은 서버 allowlist로 제한하고 요청값을 SQL에 직접 삽입하지 않는다.
- 카드 UID는 기본적으로 끝 4자리만 보이는 형태로 마스킹한다.
- 다른 부서에서 사용 중인 UID와 충돌해도 소유 부서·교사·전체 UID를 노출하지 않는다.
- 감사 before/after의 비밀번호 hash, 장치 키, 전체 카드 UID와 불필요한 연락처는 표시하지 않는다.

### 3.8 위험 작업 확인 수준

| 수준 | 작업 | 확인 방식 |
|---|---|---|
| 보통 | 교사 정보 수정, 정책 초안 저장 | 일반 form |
| 높음 | 카드 교체·해제·분실·폐기, 부서 제외, 날짜 취소, 정책 발행, 출석 정정, 권한 해제 | 별도 서버 렌더링 확인 화면 + 영향 요약 + 필수 사유 또는 확인 checkbox |
| 매우 높음 | 장치 키 교체, 장치 폐기, 계정 비활성화 | 별도 확인 화면 + 대상 코드·사용자명 재입력 + 영향 요약 |

`window.confirm()`만으로 위험 작업을 확정하지 않는다. 확인 화면에서도 서버는 권한과 상태를 다시 확인한다.

---

## 4. 로그인과 본인 계정

### 4.1 로그인

**경로:** `GET /login`, `POST /login`

| 영역 | 명세 |
|---|---|
| 필드 | 사용자명, 비밀번호 |
| 검증 | 두 필드 필수. 구체적인 계정 존재·상태를 화면에서 구분하지 않음 |
| 버튼 | `로그인` |
| 성공 | 권한 수에 따라 시스템 홈, 부서 대시보드 또는 작업 공간 선택으로 `303` 이동 |
| 실패 | `사용자명 또는 비밀번호를 확인해 주세요.`라는 동일 메시지 |
| 계정 비활성 | 일반 로그인 실패와 동일한 외부 메시지, 보안 로그에는 구분 기록 |
| 세션 만료 | 로그인 폼 위에 `세션이 만료되었습니다. 다시 로그인해 주세요.` |
| 로딩 | 제출 버튼 `로그인 중…`; 비밀번호를 DOM·로그에 다시 출력하지 않음 |

로그인 제한은 사용자명·source 조합 5회 burst와 분당 1회 회복, source 단위 20회 burst와 분당 1회 회복을 적용한다. 제한 중에는 계정 존재 여부를 드러내지 않는 동일한 대기 안내를 보여준다. 관리자 세션은 idle 30분, absolute 8시간을 넘기지 않는다.

`returnUrl`을 사용한다면 같은 origin의 `/admin/**` allowlist만 허용한다. 외부 URL redirect는 금지한다.

**인수 기준**

- `UI-LOGIN-01`: 존재하지 않는 계정, 틀린 비밀번호와 비활성 계정이 동일한 화면 메시지를 사용한다.
- `UI-LOGIN-02`: 인증 전에는 관리자 내비게이션과 데이터가 렌더링되지 않는다.
- `UI-LOGIN-03`: 여러 부서 또는 이중 역할 계정은 작업 공간을 명시적으로 선택한다.

### 4.2 작업 공간 선택

**경로:** `GET /admin/workspaces`

- 시스템 관리 card는 실제 `SYSTEM_ADMIN`에게만 표시한다.
- 부서 card는 활성 `DEPARTMENT_ADMIN` 권한이 있는 부서만 표시한다.
- 부서 이름, 권한 유형과 마지막 방문 시각을 표시할 수 있다.
- 작업 공간이 없으면 관리자에게 권한 요청 안내와 로그아웃만 표시한다.
- 다른 부서 ID를 DOM에 미리 내려보내지 않는다.

**인수 기준**

- `UI-WORKSPACE-01`: `SYSTEM_ADMIN` 단독 계정에는 어떤 부서 업무 card도 표시되지 않는다.
- `UI-WORKSPACE-02`: URL을 직접 입력해도 권한 없는 부서로 진입할 수 없다.

### 4.3 내 비밀번호 변경

**경로:** `GET`, `POST /admin/account/password`

| 필드 | 검증 |
|---|---|
| 현재 비밀번호 | 필수, 서버에서 현재 hash 검증 |
| 새 비밀번호 | 보안 명세의 길이·금지 목록 적용 |
| 새 비밀번호 확인 | 새 비밀번호와 일치 |

성공하면 비밀번호 변경 시각을 기록하고 `303`으로 완료 화면에 이동한다. MVP에서는 다른 기존 세션이 즉시 모두 만료된다고 안내하지 않는다. 실제 구현이 지원하지 않는 보안 효과를 화면에서 약속하면 안 된다.

**인수 기준**

- `UI-PASSWORD-01`: 현재 비밀번호가 틀리면 새 hash를 저장하지 않는다.
- `UI-PASSWORD-02`: 검증 실패 응답에 비밀번호 필드 값을 다시 렌더링하지 않는다.

---

## 5. 시스템 관리 화면

### 5.1 시스템 홈

**경로:** `GET /admin/system`

표시 항목은 다음과 같다.

- 관리자 미지정 부서 수
- 활성 부서 관리자 권한이 없는 활성 계정 수
- 상태별 장치 수와 최근 인증이 없는 장치
- 과거 미마감 출석 날짜 수
- DB·scheduler·backup·feature flag의 경고 요약과 운영 상태 화면 링크

부서별 교사 이름, 카드 UID, 출석 상세는 표시하지 않는다. 시스템 관리자가 부서 관리자 권한을 가진 경우에도 부서 업무는 별도 작업 공간 링크로 진입한다.

빈 상태는 `초기 설정이 완료되었습니다.`와 다음 권장 작업을 보여준다. 운영 정보 일부를 가져오지 못하면 전체 홈을 실패시키지 않고 해당 card를 `확인 불가`로 표시한다.

**인수 기준**

- `UI-SYSTEM-01`: 시스템 홈은 교사·개별 출석·부서 카드 소유자 정보를 노출하지 않는다.
- `UI-SYSTEM-02`: 경고 card는 실제 권한이 있는 해결 화면으로만 연결한다.

### 5.2 부서 목록·생성·상세

**경로**

```text
GET  /admin/system/departments
GET  /admin/system/departments/new
POST /admin/system/departments
GET  /admin/system/departments/{departmentId}
```

목록에는 부서명, 생성일, 활성 관리자 수, 장치 수와 설정 상태를 표시한다. `department.active`는 MVP 예약 필드이므로 비활성화·재활성화 버튼을 제공하지 않는다.

생성 form은 부서명 하나만 받는다.

- 공백 제거 후 필수
- 100자 이하
- 대소문자를 무시한 중복 이름 거부
- 관리자 계정이나 장치를 같은 form에서 함께 만들지 않음

상세 화면에는 부서 기본 정보, 활성 관리자 권한 목록과 장치 요약을 표시한다. 교사·카드·정책·출석 날짜로 이동하는 링크는 현재 계정이 해당 부서의 `DEPARTMENT_ADMIN`도 보유할 때만 표시한다.

성공하면 생성된 상세 화면으로 PRG한다. 중복 이름이면 원래 입력을 보존하고 필드 오류를 표시한다.

**인수 기준**

- `UI-DEPT-01`: 관리자 없는 부서 생성이 정상적인 중간 상태로 허용된다.
- `UI-DEPT-02`: 부서 생성은 계정 생성이나 권한 부여를 암묵적으로 실행하지 않는다.
- `UI-DEPT-03`: `SYSTEM_ADMIN` 단독 사용자는 부서 상세에서도 출석 업무 버튼을 볼 수 없고 직접 호출도 거부된다.

### 5.3 관리자 계정

**경로**

```text
GET  /admin/system/accounts
GET  /admin/system/accounts/new
POST /admin/system/accounts
GET  /admin/system/accounts/{accountId}
GET  /admin/system/accounts/{accountId}/disable
POST /admin/system/accounts/{accountId}/disable
POST /admin/system/accounts/{accountId}/enable
GET  /admin/system/accounts/{accountId}/reset-password
POST /admin/system/accounts/{accountId}/reset-password
```

목록 필터는 계정 상태, 시스템 역할과 부서 관리자 권한 유무다. 사용자명, 상태, `SYSTEM_ADMIN` 여부, 담당 부서와 마지막 비밀번호 변경 시각을 표시한다.

계정 생성 필드는 사용자명과 `SYSTEM_ADMIN` 부여 여부다. 부서 권한은 생성 후 별도 작업으로 부여한다. 시스템 관리자는 계정을 먼저 만든 뒤 회원가입 초대 토큰을 발급하며, 초대받지 않은 사용자가 직접 계정을 만드는 공개 회원가입은 제공하지 않는다. 초대받은 사용자의 비밀번호 설정을 위해 평문 임시 비밀번호를 관리자가 저장·메일 전송하는 방식은 사용하지 않는다.

현재 목표 스키마의 `account.password_hash`는 필수이고 상태는 `ACTIVE`·`DISABLED`뿐이다. 일회용 회원가입 초대·비밀번호 재설정 token의 hash·만료·사용 시각, `PENDING_SETUP` 또는 `must_change_password` 상태는 없다. 따라서 일반 계정 생성·회원가입 초대 수락·비밀번호 재설정 UI를 운영 가능 상태로 만들기 전에 만료되는 1회용 token을 hash로 저장하는 별도 모델을 보안·DB 문서에서 확정하고 필요한 migration을 추가해야 한다.

승인된 운영자 CLI에서 사용자가 비밀번호를 즉시 입력하는 절차는 fresh DB의 **최초 `SYSTEM_ADMIN` 1회 bootstrap 전용**이다. bootstrap을 닫은 뒤의 일반 계정 생성·회원가입 초대·reset 대안으로 재사용하지 않는다.

token 선결 조건이 완료되기 전에는 일반 계정의 웹 생성·회원가입 초대·재설정 command를 활성화하거나 `회원가입 초대 완료`, `비밀번호 재설정 완료`를 표시해서는 안 된다. 평문 임시 비밀번호를 DB·메일·감사 로그에 보관하는 임시 구현도 금지한다.

비활성화 확인 화면에는 다음을 표시한다.

- 사용자명 재입력
- 보유 시스템·부서 권한
- 신규 로그인이 즉시 차단됨
- MVP에서는 이미 발급된 세션의 즉시 강제 만료를 보장하지 않음

현재 로그인한 자기 계정 비활성화와 마지막 활성 `SYSTEM_ADMIN` 비활성화는 거부한다. 재활성화는 기존 권한을 자동 추가하지 않고 남아 있는 활성 권한만 다시 사용할 수 있게 한다.

비밀번호 재설정 화면은 확정된 1회용 token을 발급하고 승인된 전달 절차를 안내하는 기능만 제공한다. token 없는 별도 reset 경로를 대안으로 두지 않는다. 원문 비밀번호를 URL, flash message, application log, 이메일과 감사 before/after에 넣지 않는다. 위 선결 모델이 구현되지 않았다면 버튼 대신 `안전한 재설정 절차가 아직 구성되지 않았습니다.`를 표시하고 쓰기 endpoint도 제공하지 않는다.

**빈·오류 상태**

- 계정이 없으면 `첫 관리자 계정은 설치 절차에서 생성합니다.` 안내
- 중복 사용자명은 필드 오류
- 동시 상태 변경은 `409`와 현재 상태 표시
- 비활성 계정에 권한을 새로 부여하려는 요청은 거부

**인수 기준**

- `UI-ACCOUNT-01`: 계정 생성과 부서 권한 부여는 독립된 성공·실패 단위를 가진다. 안전한 회원가입 초대 수락과 비밀번호 설정이 실패하면 로그인 가능한 불완전 계정을 성공으로 안내하지 않는다.
- `UI-ACCOUNT-02`: 현재 사용자와 마지막 활성 시스템 관리자를 비활성화할 수 없다.
- `UI-ACCOUNT-03`: 비밀번호 원문과 hash가 목록·감사·오류 응답에 나타나지 않는다.

### 5.4 부서 관리자 권한 지정·해제

**위치:** 계정 상세와 부서 상세에서 같은 application command를 호출한다.

권한 부여 form은 기존 계정과 기존 부서를 선택한다. 역할 값은 화면에서 임의 입력받지 않고 `DEPARTMENT_ADMIN`으로 고정한다.

- 계정과 부서가 모두 존재해야 함
- 계정은 `ACTIVE`여야 함
- 동일 계정·부서의 활성 권한 중복 금지
- 다른 부서 권한은 영향받지 않음

해제 확인 화면에는 계정, 부서, 해제 즉시 신규 부서 요청이 거부된다는 점을 표시한다. 자기 권한 해제도 일반 작업과 같이 명시적으로 확인하며, 완료 후 현재 작업 공간 권한이 사라졌으면 시스템 홈 또는 작업 공간 선택으로 이동한다.

**인수 기준**

- `UI-ROLE-01`: 권한 부여가 교사·장치·정책을 함께 생성하지 않는다.
- `UI-ROLE-02`: 한 관리자가 여러 부서 권한을 가질 수 있고 한 부서에도 여러 관리자를 지정할 수 있다.
- `UI-ROLE-03`: 해제된 권한으로 기존 화면 URL을 재호출하면 즉시 거부된다.

---

## 6. 부서 대시보드

### 6.1 오늘의 출석

**경로:** `GET /admin/departments/{departmentId}`

상단에는 부서명, 서버 기준 오늘 날짜·시각, 출석 날짜 상태, 적용 정책과 마지막 갱신 시각을 표시한다.

오늘 출석 날짜가 있으면 다음을 보여준다.

- 전체 대상자 수
- 정상 출석 수
- 정책에 저장된 순서대로 각 지각 단계 수
- 미출석 수
- 마감 완료일이면 결석 수
- 대상자별 이름, 상태·구간, 최초 출석 시각, 판정 원천
- 최근 태깅 결과 요약과 읽기 전용 장치 최근 인증 상태

지각 단계 card와 그룹은 `1차`, `2차`를 하드코딩하지 않고 `attendance_band`와 기록의 구간 snapshot에서 동적으로 만든다.

| 오늘 상태 | 화면 행동 |
|---|---|
| 출석 날짜 없음 | `오늘 출석 날짜가 등록되지 않았습니다.`와 날짜 등록 링크 |
| 시작 전 | 정책 구간과 시작 예정 시각, 대상자 조정 링크 |
| 진행 중 | 정상·각 지각·미출석 그룹과 수동 새로고침 |
| 최종 상한 이후·아직 당일 | 태깅 종료 안내. 기록 없는 교사는 여전히 `미출석` |
| 마감 완료 | 정상·각 지각·결석 합계와 정정 링크 |
| 취소 | 취소 사유와 읽기 전용 상태 |

MVP 기본 동작은 `새로고침` 버튼이다. 자동 갱신을 추가하더라도 사용자가 일시 정지할 수 있어야 하고, 실패 시 마지막 성공 데이터를 지우지 않는다.

**버튼**

- `출석 날짜 등록`
- `대상자 보기`
- `출석 날짜 상세`
- 각 교사의 `수동 등록·정정`
- `새로고침`

권한 또는 상태상 불가능한 버튼은 단순 disabled 상태로만 남기지 않고 이유를 텍스트로 설명한다.

**인수 기준**

- `UI-DASH-01`: 오늘 기록이 없는 대상자는 자동 마감 전 `결석`으로 표시되지 않는다.
- `UI-DASH-02`: 지각 단계 수와 표시명이 정책에 따라 동적으로 바뀐다.
- `UI-DASH-03`: 시스템 관리자 단독 계정은 대시보드를 조회할 수 없다.
- `UI-DASH-04`: 합계는 대상자 수와 모순되지 않으며 오류가 있으면 성공 요약 대신 데이터 불일치 경고를 표시한다.

---

## 7. 교사와 NFC 카드

### 7.1 교사 목록·상세

**경로**

```text
GET /admin/departments/{departmentId}/teachers
GET /admin/departments/{departmentId}/teachers/{memberId}
```

목록 탭은 `재직`, `부서 제외 이력`으로 나눈다. 검색은 이름과 선택적으로 전화번호 일부를 사용한다.

재직 목록에는 이름, 선택 연락처, 카드 연결 상태, 가입일과 오늘 출석 상태를 표시한다. 상세에는 다음을 표시한다.

- 이름과 선택 연락처
- 현재 부서 소속 기간
- 현재 카드 상태와 마스킹 UID
- 카드 연결·교체 이력
- `FINALIZED` 날짜만 사용한 대상 날짜 수, 정상·단계별 지각·전체 지각·결석 횟수와 비율
- 최근 출석 기록과 판정 원천

통계의 모든 비율은 같은 대상 날짜 분모를 사용한다. 현재 진행일과 취소일은 공식 통계에 포함하지 않는다.

빈 상태에는 `아직 등록된 교사가 없습니다.`와 `교사 추가`를 표시한다. 필터 결과가 없으면 `검색 결과가 없습니다.`와 필터 초기화를 제공한다.

**인수 기준**

- `UI-TEACHER-01`: 다른 부서의 구성원 ID를 사용하면 이름이나 소속 여부가 노출되지 않는다.
- `UI-TEACHER-02`: 제외된 교사와 과거 출석·카드 연결 이력은 조회할 수 있지만 물리 삭제 버튼은 없다.
- `UI-TEACHER-03`: 통계는 `FINALIZED` 대상 날짜만 분모로 사용한다.

### 7.2 교사 추가·수정

**경로**

```text
GET  /admin/departments/{departmentId}/teachers/new
POST /admin/departments/{departmentId}/teachers
GET  /admin/departments/{departmentId}/teachers/{memberId}/edit
POST /admin/departments/{departmentId}/teachers/{memberId}/edit
```

| 필드 | 검증 |
|---|---|
| 이름 | 필수, 공백 제거, 1~255자 |
| 전화번호 | 선택, 빈 문자열은 `NULL`, 255자 이하 |
| 카드 연결 | 선택, 카드 등록함의 자기 부서 최근 미등록 또는 재사용 가능 카드 태깅만 선택 |

생년월일과 나이는 신규 화면에 노출하지 않는다. `member.card_uid`도 입력·수정하지 않는다.

`카드 없이 교사 추가`와 `선택한 카드와 함께 추가`를 명확히 구분한다. 카드와 함께 저장하면 교사, 활성 소속, 카드, 활성 assignment와 감사 로그가 모두 성공하거나 모두 rollback되어야 한다.

수정 화면은 이름과 연락처만 변경한다. 카드 작업은 별도의 연결·교체 화면에서 수행한다.

**오류**

- 선택한 미등록 카드가 이미 다른 요청으로 연결됨: `409`, 소유자 정보 없이 다시 선택 안내
- 부서 소속·교사 생성 중 일부 실패: 성공 메시지 없이 전체 실패
- 다른 부서 이벤트 ID: `404`

**인수 기준**

- `UI-TEACHER-FORM-01`: 카드 없이도 교사와 활성 소속을 추가할 수 있다.
- `UI-TEACHER-FORM-02`: 카드 포함 추가에서 어느 한 단계가 실패하면 교사만 남거나 카드만 활성화되지 않는다.
- `UI-TEACHER-FORM-03`: 처리 관리자 ID는 form에서 받지 않는다.

### 7.3 카드 등록함

**경로:** `GET /admin/departments/{departmentId}/cards/inbox`

카드 등록함은 자기 부서의 인증된 장치에서 실제로 태깅된 카드만 다루며 두 탭으로 나눈다.

| 탭 | event·카드 조건 | 저장 시 처리 |
|---|---|---|
| 미등록 태깅 | UID별 최신 `UNKNOWN_UID`, 아직 `nfc_card`가 없음 | `nfc_card` 생성 후 `AVAILABLE → ACTIVE`와 assignment 생성 |
| 재사용 가능 카드 | UID별 최신 `INACTIVE_CARD`, 현재 `nfc_card.status=AVAILABLE`, 활성 assignment 없음 | 기존 카드 `AVAILABLE → ACTIVE`와 assignment 생성 |

`LOST`, `RETIRED`, 현재 `ACTIVE` 카드와 활성 assignment가 있는 카드는 재사용 가능 탭에 나타나지 않는다. 과거 event 코드만으로 상태를 판단하지 않고 목록 조회와 저장 시 현재 카드·assignment를 다시 확인한다.

| 표시 | 설명 |
|---|---|
| 태깅 시각 | 서버 수신 시각 |
| 장치 | 장치 표시명 |
| 카드 식별 | 마스킹 UID |
| 반복 태깅 수 | 같은 UID의 별도 request 수 |
| 현재 등록 가능 여부 | 조회 시점의 카드·assignment 상태로 계산 |

필터는 최근 24시간·7일, 장치와 등록 가능 여부다. 당일 출석 날짜가 없어도 미등록 이벤트는 보여야 한다.

각 탭의 행 작업은 다음 두 가지다.

1. `기존 교사에게 연결`
2. `새 교사와 함께 등록`

수동 UID 문자열 입력은 MVP 기본 경로로 제공하지 않는다. 관리자는 실제 카드를 자기 부서 장치에 태깅해 등록함에서 선택한다. 다른 부서에서 이미 사용 중인 카드라면 `이 카드는 연결할 수 없습니다.`만 표시한다.

목록을 불러오는 동안 새 카드가 태깅될 수 있으므로 `새로고침`을 제공한다. 선택 후 저장 직전에 카드 상태, 장치 부서, 미등록 이벤트와 교사 소속을 다시 검증한다.

**인수 기준**

- `UI-CARD-INBOX-01`: 오늘 출석 날짜가 없어도 미등록 카드가 등록함에 나타난다.
- `UI-CARD-INBOX-02`: 다른 부서의 미등록 이벤트와 카드 소유자 정보는 표시되지 않는다.
- `UI-CARD-INBOX-03`: 과거 `UNKNOWN_UID` 이벤트가 있어도 현재 이미 등록된 카드는 연결 가능 목록에서 제외된다.
- `UI-CARD-INBOX-04`: 회수되어 `AVAILABLE`인 기존 카드는 자기 부서 장치의 최근 `INACTIVE_CARD` 태깅을 근거로 재사용할 수 있고 `LOST`·`RETIRED` 카드는 재사용할 수 없다.

### 7.4 카드 연결·교체·해제

**경로 예시**

```text
GET  /admin/departments/{departmentId}/teachers/{memberId}/card
POST /admin/departments/{departmentId}/teachers/{memberId}/card/assign
GET  /admin/departments/{departmentId}/teachers/{memberId}/card/replace
POST /admin/departments/{departmentId}/teachers/{memberId}/card/replace
GET  /admin/departments/{departmentId}/teachers/{memberId}/card/end
POST /admin/departments/{departmentId}/teachers/{memberId}/card/end
```

연결 대상은 등록함에서 선택한 미등록 UID 또는 현재 `AVAILABLE` 카드여야 한다. 미등록 UID는 카드 행을 만든 뒤 연결하고, 기존 `AVAILABLE` 카드는 행을 새로 만들지 않고 재사용한다. 한 교사에는 활성 카드 한 장만 허용한다.

교체 확인 화면은 기존·신규 마스킹 UID, 새 카드의 최근 태깅 시각, 영향과 필수 사유를 보여준다. 저장은 기존 assignment 종료, 기존 카드 `AVAILABLE`, 새 카드 `ACTIVE`, 새 assignment와 감사 이력을 한 트랜잭션으로 처리한다.

연결 종료 form의 `카드 처리`는 필수다.

| 선택 | 결과 |
|---|---|
| 회수함·재사용 가능 | `AVAILABLE` |
| 미회수·분실 | `LOST` |
| 영구 폐기 | `RETIRED` |

사유는 필수 1~500자다. assignment를 종료하면서 카드를 `ACTIVE`로 남기는 선택은 없다. `RETIRED` 카드는 다시 연결할 수 없다. 잘못 등록한 UID는 수정하지 않고 기존 카드를 폐기한 뒤 올바른 카드를 새로 태깅한다.

**인수 기준**

- `UI-CARD-01`: 카드 상태와 assignment 이력은 모두 commit되거나 모두 rollback된다.
- `UI-CARD-02`: 카드 교체·해제·분실·폐기는 빈 사유를 화면과 서버에서 거부한다.
- `UI-CARD-03`: 교체 직후 기존 카드는 출석에 사용할 수 없고 과거 출석은 교사 기준으로 유지된다.
- `UI-CARD-04`: 다른 부서에서 사용 중인 카드 충돌 화면은 소유자 정보를 노출하지 않는다.

### 7.5 부서 제외

**경로**

```text
GET  /admin/departments/{departmentId}/teachers/{memberId}/exclude
POST /admin/departments/{departmentId}/teachers/{memberId}/exclude
```

버튼 이름은 `삭제`가 아니라 `부서에서 제외`다. 확인 화면에는 다음 영향을 보여준다.

- 종료할 현재 부서 소속
- 종료할 활성 카드 연결과 필수 카드 처리 방식
- 과거 대상자·출석·카드 이력이 보존됨
- 이후 새로 등록하는 출석 날짜의 기본 대상에서 제외됨
- 이미 등록된 미래 출석 날짜 중 태깅 시작 전이라 대상에서 제외할 수 있는 날짜
- 이미 시작됐거나 지난 날짜처럼 자동 변경하지 않는 날짜

| 필드 | 검증 |
|---|---|
| 제외 사유 | 필수, 1~500자 |
| 카드 처리 | 활성 카드가 있으면 `AVAILABLE`, `LOST`, `RETIRED` 중 필수 |
| 기존 미래 날짜 대상 제외 | 날짜별 checkbox, 태깅 시작 전만 선택 가능 |
| 최종 확인 | `위 영향을 확인했습니다.` checkbox |

기존 미래 날짜의 checkbox는 기본적으로 선택하지 않아 암묵적 명단 변경을 막는다. 사용자가 선택한 날짜만 같은 트랜잭션에서 `is_target=false`로 변경한다. 해당 작업 중 날짜 상태나 시작 시각이 바뀌면 전체를 `409`로 rollback하고 최신 영향 목록을 다시 보여준다.

**인수 기준**

- `UI-EXCLUDE-01`: 부서 제외는 `member`, 과거 소속, 대상자, 출석과 카드 이력을 삭제하지 않는다.
- `UI-EXCLUDE-02`: 소속 종료, 카드 assignment 종료·상태 변경, 선택한 미래 대상 제외와 감사 기록은 원자적으로 처리된다.
- `UI-EXCLUDE-03`: 이미 시작한 날짜와 과거 날짜의 대상자·출석은 자동 변경하지 않는다.
- `UI-EXCLUDE-04`: 처리 관리자는 인증 세션에서 결정되고 빈 사유는 저장되지 않는다.

---

## 8. 출석 정책

### 8.1 정책 목록·상세

**경로**

```text
GET /admin/departments/{departmentId}/policies
GET /admin/departments/{departmentId}/policies/{policyId}
```

목록에는 버전, 이름, 상태, 태깅 시작 시각, 최종 허용 시각, 단계 수, 생성·발행 시각을 표시한다. `PUBLISHED`와 `DRAFT`를 구분하고 예약 상태 `RETIRED`의 전이 버튼은 제공하지 않는다.

발행 정책 상세는 읽기 전용이다. 적용 중인 출석 날짜 수와 최근 날짜를 표시하되, 수정·삭제 버튼은 없다. `새 초안으로 복사`는 내용을 복사해 새 버전의 `DRAFT`를 만드는 별도 작업이다.

빈 상태에는 정책이 있어야 출석 날짜를 등록할 수 있다는 설명과 `첫 정책 만들기`를 제공한다.

**인수 기준**

- `UI-POLICY-LIST-01`: 발행된 정책에는 수정·삭제 버튼이 없다.
- `UI-POLICY-LIST-02`: 새 정책 발행이 기존 출석 날짜의 적용 버전을 자동 변경하지 않는다.

### 8.2 정책 초안과 동적 지각 단계

**경로**

```text
GET  /admin/departments/{departmentId}/policies/new
POST /admin/departments/{departmentId}/policies
GET  /admin/departments/{departmentId}/policies/{policyId}/edit
POST /admin/departments/{departmentId}/policies/{policyId}/edit
GET  /admin/departments/{departmentId}/policies/{policyId}/publish
POST /admin/departments/{departmentId}/policies/{policyId}/publish
```

기본 form은 다음 구조다.

| 필드 | 규칙 |
|---|---|
| 정책 이름 | 필수, 1~100자 |
| 태깅 시작 시각 | 필수, `HH:mm` |
| 1번 구간 | `PRESENT`로 고정, 표시명과 상한 시각 필수 |
| 2번 이후 구간 | 모두 `LATE`로 고정, 표시명과 상한 시각 필수 |

첫 구간 이후 최소 한 개의 지각 구간이 있어야 한다. `지각 단계 추가`로 필요한 수만큼 행을 추가하고 각 행에 `위로`, `아래로`, `제거` 버튼을 제공한다. drag-and-drop만으로 순서를 바꾸지 않는다.

클라이언트와 서버가 모두 다음을 검증한다.

1. 첫 구간만 `PRESENT`
2. 두 번째부터 모두 `LATE`
3. 지각 구간 최소 한 개
4. 태깅 시작 시각 `<=` 정상 출석 상한
5. 각 상한 시각이 엄격하게 증가
6. 중복 상한 없음
7. 같은 날짜 안에서 끝나며 자정을 넘기지 않음
8. 모든 표시명이 비어 있지 않음

JavaScript가 없으면 `단계 추가 후 계속 편집`, `위로`, `아래로`, `제거` submit 버튼으로 서버에서 form을 다시 렌더링한다. 이 조작은 초안 저장 전까지 DB 업무 변경으로 보지 않으며 입력값을 보존한다.

각 구간 아래에는 `이 시각과 정확히 같으면 이 단계로 판정됩니다.`를 표시한다. 정책 미리보기는 다음과 같이 경계를 풀어 쓴다.

```text
08:30 전 요청: 아직 출석 시작 전
08:30 ~ 09:00: 정상 출석
09:00 초과 ~ 09:10: 1차 지각
09:10 초과 ~ 09:30: 2차 지각
09:30 초과: 출석 마감
```

발행 확인 화면에는 읽기 전용 전체 구간, 적용 경계, `발행 후 수정·삭제할 수 없음`과 `기존 날짜는 자동 변경되지 않음`을 표시한다. 확인 checkbox 후 발행한다.

발행 중 다른 관리자가 초안을 변경했으면 `409`와 최신 버전을 보여준다. 발행 성공 후 정책 상세로 PRG한다.

**인수 기준**

- `UI-POLICY-01`: 두 개 이상의 지각 단계도 같은 화면 구조로 추가·정렬·검증할 수 있다.
- `UI-POLICY-02`: 정상 구간 추가, 지각 구간 0개, 중복·역전 상한과 자정 초과를 클라이언트와 서버가 모두 거부한다.
- `UI-POLICY-03`: 구간 상한 포함 규칙이 미리보기와 실제 서버 판정에서 일치한다.
- `UI-POLICY-04`: 발행 후 수정 URL을 직접 호출해도 거부되고 새 초안만 만들 수 있다.

---

## 9. 출석 날짜와 대상자

### 9.1 출석 날짜 목록

**경로:** `GET /admin/departments/{departmentId}/attendance-days`

필터는 월, 상태와 정책 버전이다. 목록에는 날짜, 계산된 운영 상태, 정책, 대상자 수, 정상·지각·결석·미출석 합계와 생성자를 표시한다.

- `OPEN`을 DB 상태처럼 표시하거나 저장하지 않는다.
- 오늘 `SCHEDULED`는 화면에서 `진행일`로 계산한다.
- 미래 `SCHEDULED`는 `예정`, 과거 미마감 `SCHEDULED`는 `마감 지연` 경고다.
- `CANCELED`는 공식 통계 합계에서 제외한다.

빈 상태에는 `출석 날짜 등록`과 먼저 발행 정책이 필요한 경우 정책 화면 링크를 표시한다.

**인수 기준**

- `UI-DAY-LIST-01`: 저장 상태와 계산 상태 `진행일`을 혼동하지 않는다.
- `UI-DAY-LIST-02`: 과거 미마감 날짜를 정상 완료로 보이지 않고 운영 경고를 표시한다.

### 9.2 출석 날짜 등록

**경로**

```text
GET  /admin/departments/{departmentId}/attendance-days/new
POST /admin/departments/{departmentId}/attendance-days
```

| 필드 | 검증 |
|---|---|
| 출석 날짜 | 서버 기준 오늘 또는 미래, 같은 부서 중복 금지 |
| 적용 정책 | 자기 부서의 `PUBLISHED` 버전만 선택 |

오늘 날짜는 선택 정책의 태깅 시작 시각 전까지만 등록할 수 있다. form에는 현재 활성 교사 수와 `등록 시점의 활성 교사가 기본 대상자로 복사됩니다.`를 표시한다. 명단은 생성 후 상세 화면에서 조정한다.

등록 성공 시 날짜와 대상자 snapshot이 한 트랜잭션으로 생성되고 상세로 PRG한다. 날짜만 생기고 대상자가 일부만 복사되는 결과를 허용하지 않는다.

**인수 기준**

- `UI-DAY-CREATE-01`: 과거, 같은 부서 중복 날짜와 시작 후 당일 날짜가 거부된다.
- `UI-DAY-CREATE-02`: 다른 부서 정책 ID를 전송해도 선택·등록할 수 없다.
- `UI-DAY-CREATE-03`: 날짜 생성과 기본 대상자 snapshot은 원자적이다.

### 9.3 출석 날짜 상세

**경로:** `GET /admin/departments/{departmentId}/attendance-days/{dayId}`

상단에는 날짜, 저장·계산 상태, 고정 정책 버전, 생성·마감·취소 정보와 집계 검증 결과를 표시한다. 대상자 표에는 이름, 대상 여부, 최종 상태·구간, 시각, 원천과 정정 작업을 표시한다.

| 상태 | 허용 작업 |
|---|---|
| 태깅 시작 전 `SCHEDULED` | 정책 재선택, 일반 대상자 추가·제외, 기록이 없으면 날짜 취소 |
| 태깅 시작 후 `SCHEDULED` | 일반 대상·정책 변경·취소 금지, 누락자 수동 등록과 개별 정정 |
| `FINALIZED` | 개별 정정과 누락자 수동 등록, 상태를 다시 열지 않음 |
| `CANCELED` | 읽기 전용 |

정책 재선택 확인에는 기존·새 정책의 전체 구간을 나란히 표시하고 `기존 출석 기록 없음`, `태깅 시작 전`을 서버에서 다시 검증한다. 변경은 과거 판정을 재계산하지 않는다.

일반 대상자 변경은 활성 소속 교사만 선택하고 사유를 필수로 받는다. 제거는 행을 삭제하지 않고 `is_target=false`와 변경 metadata를 저장한다.

날짜 취소는 기록이 없는 태깅 시작 전 날짜에서만 가능하다. 확인 화면에 날짜 재입력과 1~500자 취소 사유를 요구하고 통계·자동 마감에서 제외된다는 영향을 보여준다.

**인수 기준**

- `UI-DAY-DETAIL-01`: 태깅 시작 이후 일반 대상자·정책 변경과 날짜 취소 버튼이 사라지며 직접 요청도 거부된다.
- `UI-DAY-DETAIL-02`: 기록이 생긴 날짜는 취소할 수 없다.
- `UI-DAY-DETAIL-03`: 대상자 변경은 사유·처리자를 남기고 기존 행을 물리 삭제하지 않는다.
- `UI-DAY-DETAIL-04`: 발행 정책의 상세 표시명과 상한은 현재 정책이 아니라 날짜에 고정된 버전에서 읽는다.

---

## 10. 출석 수동 등록·정정

### 10.1 기존 대상자의 수동 등록·정정

**경로 예시**

```text
GET  /admin/departments/{departmentId}/attendance-days/{dayId}/teachers/{memberId}/correction
POST /admin/departments/{departmentId}/attendance-days/{dayId}/teachers/{memberId}/correction
```

화면 상단에 변경 전 상태, 시각, 구간, 판정 원천, 비고와 날짜의 고정 정책을 표시한다.

작업 유형은 다음 세 가지다.

| 작업 | 입력 | 서버 처리 |
|---|---|---|
| 실제 출석으로 등록·정정 | 실제 출석 시각, 필수 사유, 선택 비고 | 고정 정책으로 `PRESENT` 또는 `LATE`와 구간 계산 |
| 결석으로 정정 | `FINALIZED` 날짜에서만 필수 사유, 선택 비고 | `ABSENT`, 시각·구간 `NULL` |
| 비고만 수정 | 비고 | 상태·시각·구간·기존 source 유지 |

관리자가 `PRESENT`, `LATE`, `1차 지각` 같은 상태·구간을 select로 고르지 않는다. 실제 출석 시각 입력 후 화면이 예상 판정을 보여줄 수 있지만, 최종 판정은 서버가 다시 계산한다.

진행 중인 `SCHEDULED` 날짜에 기록이 없는 교사를 미리 `ABSENT`로 만들 수 없다. 그 상태는 화면상 `미출석`이며 날짜 경과 후 자동 마감이 결석을 생성한다. 결석 정정은 이미 마감된 날짜에서만 제공한다.

실제 출석 시각은 다음 조건을 모두 만족해야 한다.

- 출석 날짜의 `Asia/Seoul` 달력 날짜와 같음
- 해당 교사의 소속 기간 `[joined_at, ended_at)` 안
- 날짜에 고정된 정책의 태깅 시작부터 마지막 상한 안
- `CANCELED` 날짜가 아님

확인 화면은 before/after 예상값과 `source=MANUAL`로 바뀌고 감사 이력이 남는다는 점을 보여준다. `FINALIZED` 날짜를 정정해도 다시 `SCHEDULED`로 열지 않는다.

**인수 기준**

- `UI-CORRECTION-01`: request에 임의 상태·구간 ID를 추가해도 서버가 신뢰하지 않는다.
- `UI-CORRECTION-02`: 날짜·소속 기간·정책 허용 범위 밖 실제 시각을 거부한다.
- `UI-CORRECTION-03`: 상태에 영향을 주는 변경은 `MANUAL`, 처리자, before/after와 사유를 함께 저장한다.
- `UI-CORRECTION-04`: 비고만 수정하면 기존 판정 원천을 유지한다.
- `UI-CORRECTION-05`: `SCHEDULED` 날짜의 기록 없는 대상자를 수동 결석으로 미리 확정할 수 없다.

### 10.2 대상자 누락자의 사후 수동 등록

**경로**

```text
GET  /admin/departments/{departmentId}/attendance-days/{dayId}/manual-registration
POST /admin/departments/{departmentId}/attendance-days/{dayId}/manual-registration
```

이 화면은 태깅 시작 후 또는 마감 후 실제 출석했지만 명단에서 누락된 교사를 위한 예외 흐름이다. 일반 대상자 추가 화면을 우회 수단으로 사용하지 않는다.

| 필드 | 검증 |
|---|---|
| 교사 | 해당 실제 시각에 자기 부서 소속 기간이 존재하는 교사 |
| 실제 출석 시각 | 날짜·소속 기간·고정 정책 범위 안 |
| 사유 | 필수, 1~500자 |
| 비고 | 선택, 1,000자 이하 |

확인 화면에는 `대상자 추가와 수동 출석 기록이 함께 생성됩니다.`를 표시한다. 서버는 활성 `attendance_target(added_source=MANUAL)`과 `attendance_record(source=MANUAL)`를 한 트랜잭션으로 생성한다. 대상자만 추가되거나 기록만 남는 부분 성공은 허용하지 않는다.

`CANCELED` 날짜, 실제 시각에 소속이 없던 교사와 이미 활성 대상자인 교사는 이 흐름에서 거부한다. 이미 대상자라면 기존 수동 등록·정정 화면으로 안내한다.

**인수 기준**

- `UI-MANUAL-01`: 누락자 등록은 대상자와 출석 기록을 한 번에 생성한다.
- `UI-MANUAL-02`: `FINALIZED` 날짜에서도 허용하되 날짜 상태는 바꾸지 않는다.
- `UI-MANUAL-03`: `CANCELED` 날짜와 소속 기간 밖 시각은 거부된다.

---

## 11. 장치 관리

### 11.1 장치 목록·상세

**권한:** `SYSTEM_ADMIN`

**경로**

```text
GET /admin/system/devices
GET /admin/system/devices/{deviceId}
```

목록 필터는 부서, 상태와 최근 인증 여부다. 표시 항목은 장치명, 장치 코드, 고정 배정 부서, 상태, credential version, 발급 시각, 마지막 인증 성공 시각이다.

`last_seen_at`의 화면 이름은 `마지막 인증 성공`이다. 출석 저장 성공으로 잘못 표현하지 않는다.

부서 관리자는 자기 대시보드에서 장치명·상태·마지막 인증 성공을 읽기 전용으로 볼 수 있지만 장치 코드 전체, 키, credential version과 관리 버튼은 볼 수 없다.

**인수 기준**

- `UI-DEVICE-LIST-01`: 장치 부서는 상세 화면에서도 수정 가능한 select가 아니다.
- `UI-DEVICE-LIST-02`: `last_seen_at`을 마지막 출석 성공으로 표시하지 않는다.
- `UI-DEVICE-LIST-03`: 장치 비밀키 hash와 원문은 어떤 목록·상세에도 표시하지 않는다.

### 11.2 장치 등록과 비밀키 1회 표시

**경로**

```text
GET  /admin/system/devices/new
POST /admin/system/devices
GET  /admin/system/devices/{deviceId}/credential-once
```

| 필드 | 검증 |
|---|---|
| 장치명 | 필수, 1~100자 |
| 장치 코드 | 필수, 1~100자, 전역 유일 |
| 배정 부서 | 필수, 기존 활성 부서 |

새 장치는 항상 `INACTIVE`로 생성한다. 서버가 고엔트로피 비밀키를 생성하고 hash만 DB에 저장한다.

성공 후 원문 비밀키는 서버 세션의 짧은 one-time 전달 객체를 통해 한 번만 보여준다. URL, query parameter, cookie, flash message와 로그에 원문을 넣지 않는다. `GET .../credential-once`는 전달 객체를 session에서 원자적으로 제거해 request-scoped view model로 옮긴 뒤 렌더링한다. 렌더링 실패, 새로고침 또는 다시 방문으로 객체가 없으면 원문을 재생성·재조회하지 않고 키 교체 절차를 안내한다.

1회 응답에는 최소 `Cache-Control: no-store`를 설정한다. 호환성을 위해 `no-cache, must-revalidate`와 `Pragma: no-cache`를 함께 적용할 수 있다. 원문을 `localStorage`, `sessionStorage`, service worker cache나 브라우저 history state에 복사하지 않는다. 뒤로가기에서도 캐시된 원문이 다시 렌더링되지 않아야 한다.

1회 화면에는 `복사`, 장치 코드, 비밀키, 설정 완료 checkbox와 다음 단계인 실제 장치 credential test 안내를 표시한다. 브라우저 저장·인쇄를 권장하지 않는다.

**인수 기준**

- `UI-DEVICE-CREATE-01`: 새 장치는 자격증명만 발급받아도 출석 API를 호출할 수 없다.
- `UI-DEVICE-CREATE-02`: 비밀키 원문은 1회 화면 외의 응답·URL·로그·감사 데이터에 남지 않고, 해당 응답은 `Cache-Control: no-store`를 사용한다.
- `UI-DEVICE-CREATE-03`: 부서·장치 코드·credential 생성은 한 번의 성공 단위이며 실패 시 불완전 장치가 활성화되지 않는다.
- `UI-DEVICE-CREATE-04`: 1회 전달 객체는 최초 GET 렌더 시 session에서 소비되며 새로고침·뒤로가기·동일 URL 재호출로 원문이 다시 노출되지 않는다.

### 11.3 credential test와 활성화

credential test는 브라우저 버튼이 장치 비밀키를 서버로 보내는 기능이 아니다.

```text
장치 생성(INACTIVE)
→ 비밀키를 실제 장치에 주입
→ 실제 장치가 POST /api/v1/device/credential-tests 호출
→ 시스템 화면에서 현재 credential의 인증 성공 확인
→ SYSTEM_ADMIN이 ACTIVE 전환
→ 실제 check-in 시험
```

장치 상세의 `시험 상태`는 범용 `last_seen_at`이 아니라 별도 시험 증거로 계산한다.

- `대기 중`: `credential_tested_version IS NULL` 또는 현재 `credential_version`과 다름
- `현재 키 시험 성공`: `credential_tested_version = credential_version`이고 `credential_tested_at >= credential_issued_at`

`INACTIVE` 상태에서는 credential-test만 허용되고 출석 check-in은 거부된다. `ACTIVE`와 `REVOKED` 장치는 credential-test가 거부되어야 한다.

화면은 `시험 결과 새로고침`을 제공한다. 선택적 polling을 사용하면 5초 이상 간격, 일시 정지, 최대 대기시간과 접근 가능한 상태 알림을 둔다. 시험 성공 전 `활성화`는 비활성 상태와 이유를 표시하고 서버 직접 요청도 거부한다.

**인수 기준**

- `UI-DEVICE-TEST-01`: 브라우저가 장치 비밀키를 다시 입력받거나 credential-test API를 대신 호출하지 않는다.
- `UI-DEVICE-TEST-02`: 현재 credential version의 credential-test 성공 증거가 있어야 활성화할 수 있고 일반 check-in 인증이나 `last_seen_at`만으로는 활성화할 수 없다.
- `UI-DEVICE-TEST-03`: credential test는 출석 기록과 `tag_event_log`를 만들지 않는다.
- `UI-DEVICE-TEST-04`: `ACTIVE`, `REVOKED` 장치에는 시험 시작·활성화 버튼이 나타나지 않는다.

### 11.4 키 교체·비활성화·폐기

**경로 예시**

```text
GET  /admin/system/devices/{deviceId}/rotate-credential
POST /admin/system/devices/{deviceId}/rotate-credential
POST /admin/system/devices/{deviceId}/deactivate
POST /admin/system/devices/{deviceId}/activate
GET  /admin/system/devices/{deviceId}/revoke
POST /admin/system/devices/{deviceId}/revoke
```

키 교체 확인 화면은 다음을 명시한다.

- 장치를 즉시 `INACTIVE`로 전환함
- 기존 키가 commit 즉시 무효가 됨
- 신·구 키 중첩 기간이 없음
- 새 키 설정·credential test·재활성화 전까지 출석 불가
- 장치 코드 재입력과 1~500자 사유 필수

성공하면 credential version 증가, 발급 시각 갱신, 새 hash 저장과 기존
`credential_tested_version`·`credential_tested_at` 초기화를 한 트랜잭션으로
처리하고 새 키를 한 번만 보여준다.

`REVOKED`는 분실·침해·영구 폐기의 종결 상태다. 확인 화면에서 장치 코드 재입력과 사유를 요구한다. 폐기 후 재활성화·키 교체·부서 변경 버튼을 제공하지 않는다. 같은 물리 장치를 다시 쓸 때도 새 장치 코드의 새 행을 등록한다.

**인수 기준**

- `UI-DEVICE-ROTATE-01`: 교체 commit 직후 이전 키는 거부되고 새 키 시험 전 장치는 `INACTIVE`다.
- `UI-DEVICE-ROTATE-02`: 키 교체와 폐기는 장치 코드가 일치하지 않거나 사유가 비어 있으면 실행되지 않는다.
- `UI-DEVICE-ROTATE-03`: `REVOKED` 장치의 활성화·키 교체·부서 변경 요청은 UI와 서버에서 모두 거부된다.
- `UI-DEVICE-ROTATE-04`: 기존 장치의 `department_id` 변경 기능은 존재하지 않는다.

---

## 12. 감사, 태깅 이력과 운영

### 12.1 부서 이력

**경로:** `GET /admin/departments/{departmentId}/history`

두 탭을 분리한다.

#### 관리자·시스템 감사

- 교사 추가·수정·부서 제외
- 카드 연결·교체·해제·분실·폐기
- 정책 초안·발행
- 출석 날짜·대상자 변경과 취소
- 수동 등록·정정
- 자동 마감

필터는 기간, 작업 유형, 작업자와 대상 유형이다. 표시 항목은 발생 시각, 작업자 유형·표시명, action, 대상, 사유와 마스킹된 before/after 차이다.

#### 태깅 이벤트

- 장치, 시각, 마스킹 UID, request ID 일부, 결과 코드와 연결된 출석 상태
- 결과 코드는 한국어 설명과 원문 code를 함께 제공
- `PROCESSING`이 비정상적으로 오래 남아 있으면 운영 경고

같은 태깅을 감사 탭에 중복 표시하지 않는다. 인증 실패와 malformed 요청은 `tag_event_log`가 아니라 운영 보안 로그의 영역이므로 이 화면에 정상 업무 이벤트처럼 섞지 않는다.

**인수 기준**

- `UI-HISTORY-01`: 부서 관리자는 자기 부서 이력만 조회한다.
- `UI-HISTORY-02`: 일반 태깅 이벤트가 감사 이력에 중복 생성·표시되지 않는다.
- `UI-HISTORY-03`: 카드 전체 UID, 비밀번호, 장치 키와 불필요한 연락처가 before/after에 노출되지 않는다.

### 12.2 시스템 감사

**경로:** `GET /admin/system/audit`

시스템 감사는 부서 생성, 계정 생성·상태, 부서 관리자 권한과 장치 등록·상태·키 version 변경을 대상으로 한다. 부서 업무 상세는 대상에서 제외한다.

필터는 기간, 작업자, action, 대상 유형과 부서다. 장치 credential 변경은 version과 상태만 표시하고 hash·원문은 표시하지 않는다.

**인수 기준**

- `UI-SYSTEM-AUDIT-01`: 시스템 감사 조회 권한만으로 교사·카드 assignment·출석 정정 상세를 볼 수 없다.
- `UI-SYSTEM-AUDIT-02`: 권한 부여·해제와 장치 상태 전이의 작업자·시각·대상이 추적된다.

### 12.3 운영 상태

**경로:** `GET /admin/system/operations`

| 영역 | 표시 |
|---|---|
| 애플리케이션 | health, 배포 버전, 시작 시각 |
| DB | 연결 가능 여부, 승인된 Flyway target 일치 여부 |
| feature flag | `admin-write`, `device-api`, `scheduler` 현재 값과 읽기 전용 안내 |
| 자동 마감 | 과거 미마감 날짜 수, 마지막 성공·실패 시각과 상관 ID |
| 장치 | 상태별 수, 최근 인증 없음, 인증 실패 추세의 제한된 요약 |
| 백업 | 마지막 성공 시각, 파일 위치가 아닌 저장소 유형, 마지막 복원 시험 |

feature flag는 환경 설정과 controlled restart로만 바꾸므로 toggle·저장 버튼을 제공하지 않는다. 자동 마감을 SYSTEM_ADMIN이 임의 실행하는 버튼도 MVP에는 제공하지 않는다. 시스템 관리자는 부서 출석 업무 권한을 우회해 마감·정정할 수 없다.

백업 상태는 외부 작업이 제공하는 검증된 상태 source가 있을 때만 `성공`으로 표시한다. 연동이 없거나 오래됐으면 `확인 불가` 또는 `기한 초과`로 표시하고 추측하지 않는다.

전체 Actuator 응답, 환경변수, DB URL과 비밀 설정은 화면에 노출하지 않는다.

**인수 기준**

- `UI-OPS-01`: feature flag는 읽기 전용이고 화면에서 동적으로 변경할 수 없다.
- `UI-OPS-02`: 운영 화면은 DB URL, 환경변수 원문, 비밀키와 상세 Actuator 정보를 노출하지 않는다.
- `UI-OPS-03`: 백업 상태 source가 없는데 성공으로 표시하지 않는다.
- `UI-OPS-04`: 과거 미마감 날짜 경고는 보여주지만 시스템 관리자 우회 마감 버튼은 제공하지 않는다.

---

## 13. 반응형·모바일 규칙

### 13.1 레이아웃

- 기본 최소 지원 폭은 320 CSS px다.
- 768px 미만에서는 좌측 내비게이션을 닫힌 drawer로 전환한다.
- 모바일 헤더에도 현재 부서·시스템 작업 공간을 생략하지 않는다.
- 주요 form은 한 열, 넓은 화면에서는 관련 필드만 최대 두 열로 배치한다.
- 화면 전체를 가로 스크롤시키지 않는다. 넓은 표만 자체 스크롤 영역을 사용하고 표 제목과 스크롤 안내를 제공한다.
- 모바일 목록은 핵심 열을 card로 바꿀 수 있지만 상태·대상·작업을 누락하지 않는다.

### 13.2 터치와 입력

- 주요 터치 대상은 최소 44×44 CSS px를 목표로 한다.
- 위험 작업 버튼은 기본 작업 버튼과 충분히 떨어뜨린다.
- 날짜·시각은 native input을 우선하되 지원하지 않는 브라우저를 위한 형식 힌트와 서버 오류를 제공한다.
- 정책 단계 이동은 drag gesture만 사용하지 않고 `위로`·`아래로` 버튼을 제공한다.
- 부서 제외의 날짜 다중 선택은 각 날짜와 정책 시작 시각을 함께 보여준다.

### 13.3 모바일 우선순위

모바일 첫 화면에는 다음 순서로 표시한다.

1. 작업 공간·날짜·운영 경고
2. 오늘의 출석 합계
3. 미출석 대상자
4. 정상·지각 대상자
5. 최근 태깅·장치 상태

시스템 설정의 1회 비밀키 화면과 정책 대량 편집도 모바일에서 작동해야 하지만, 화면이 좁다는 이유로 보안 경고나 영향 요약을 접어 숨기지 않는다.

---

## 14. 접근성 기준

목표는 WCAG 2.2 AA 수준의 핵심 관리자 흐름이다.

- 페이지마다 유일한 `<h1>`과 일관된 heading 순서를 사용한다.
- `header`, `nav`, `main`, `footer` landmark와 `본문으로 건너뛰기` 링크를 제공한다.
- 모든 input은 시각적 `<label>`과 오류 연결용 `aria-describedby`를 가진다.
- 검증 실패 시 페이지 상단 오류 요약으로 focus를 옮기고 각 항목을 필드 anchor와 연결한다.
- flash와 비동기 상태는 성격에 따라 `role="status"` 또는 `role="alert"`를 사용한다.
- 상태와 성공·실패를 색만으로 전달하지 않는다.
- 표에는 caption과 열 header의 `scope`를 지정한다.
- keyboard만으로 내비게이션, 동적 정책 단계 추가·이동·삭제와 확인 작업을 완료할 수 있어야 한다.
- focus indicator를 제거하지 않는다.
- modal을 사용한다면 focus trap, Escape 닫기와 원래 trigger focus 복귀를 구현한다. 위험 작업은 가능하면 별도 확인 페이지를 사용한다.
- 자동 갱신 영역은 사용자가 일시 정지할 수 있고 focus를 임의로 이동하지 않는다.
- 카운트다운이나 시간만으로 입력을 닫지 않는다. 제출 시 서버가 현재 시각으로 최종 판단하고 명확한 충돌 메시지를 제공한다.

---

## 15. 공통 인수 시나리오

### 15.1 권한과 부서 격리

1. `SYSTEM_ADMIN` 단독 계정으로 시스템 관리 화면은 사용 가능하지만 부서 역할 자체가 없으므로 부서 업무 namespace 접근은 `403`이어야 한다.
2. A부서 관리자가 B부서의 `memberId`, `policyId`, `dayId`, event ID를 A부서 URL 또는 B부서 URL에 넣으면 존재를 숨기는 `404`가 반환되고 데이터가 노출·변경되지 않아야 한다.
3. 시스템·부서 이중 역할 계정도 현재 선택한 부서의 활성 권한이 있는 경우에만 부서 업무를 수행해야 한다.
4. 역할 해제 후 열려 있던 form을 제출하면 서버가 재검증해 거부해야 한다.

### 15.2 상태와 동시성

1. form을 두 탭에서 열고 한쪽에서 먼저 변경한 뒤 다른 쪽을 제출하면 오래된 상태로 덮어쓰지 않고 `409`를 보여야 한다.
2. 두 번 클릭·새로고침에도 계정, 날짜, 출석, 카드 assignment가 중복 생성되지 않아야 한다.
3. 태깅 시작 경계에서 정책·대상자·부서 제외를 제출하면 서버가 잠금 뒤 시각을 다시 확인하고 전체 성공 또는 전체 rollback해야 한다.
4. `admin-write.enabled=false`에서 버튼을 조작하거나 직접 POST해도 쓰기가 발생하지 않아야 한다.

### 15.3 화면 복원력

1. validation 오류 후 일반 입력은 유지되고 비밀번호·장치 비밀키는 비워져야 한다.
2. 성공 POST를 새로고침해도 command가 다시 실행되지 않아야 한다.
3. DB·운영 probe 일부 실패가 개인정보를 포함한 예외 메시지로 노출되지 않아야 한다.
4. JavaScript를 끈 상태에서도 로그인, 교사 추가, 카드 연결, 정책 단계 편집·발행, 날짜 등록, 정정과 장치 상태 관리의 핵심 흐름을 완료할 수 있어야 한다.

---

## 16. 구현 순서

1. 공통 layout, 로그인, 작업 공간 선택, 403·404·409·500·503 화면
2. 시스템 부서·계정·권한 화면
3. 부서 대시보드와 부서 scope read query
4. 교사와 카드 등록함·카드 orchestration·부서 제외
5. 정책 초안·동적 구간·발행
6. 출석 날짜·대상자와 상태별 상세
7. 수동 등록·정정
8. 장치 등록·credential test 상태·활성화·키 교체·폐기
9. 부서·시스템 감사와 운영 상태
10. 모바일·접근성·브라우저·부정 권한 회귀 시험

화면을 먼저 만들어 Mapper를 직접 호출하는 방식으로 진행하지 않는다. 각 단계는 application service, 부서 범위 SQL, 감사와 MVC·보안 테스트가 함께 완료되어야 한다.

---

## 17. 구현 전 확인 항목

아래 값은 UI 구조를 막지는 않지만 실제 배포 전에 관련 문서에서 확정해야 한다.

1. 회원가입 초대·비밀번호 재설정을 위한 만료형 token 모델과 승인된 전달 채널. 제한 CLI는 fresh DB의 최초 `SYSTEM_ADMIN` 1회 bootstrap에만 사용하며 일반 계정 초대·reset 대안으로 사용하지 않음
2. 흔한·유출 비밀번호 금지 목록의 제공 방식과 갱신 책임
3. 장치 수·설치 위치와 장치별 표시명
4. 설치 장치별로 확정된 4·7·10-byte UID를 구분자 없는 대문자 16진수로 실제 읽고 전송하는 현장 호환성
5. 개인정보·감사·태깅 이력 보유기간
6. 백업 작업 상태를 운영 화면에 전달할 source와 기한 초과 기준
7. scheduler 실행 주기와 운영 경고가 되는 미마감 허용 시간

위 결정·현장 확인이 완료되지 않았어도 임의의 성공 상태나 보안 효과를 화면에 표시하지 않는다.
