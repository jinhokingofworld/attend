# 하드웨어 없는 로컬 장치 API 시험

이 구성은 Arduino가 준비되기 전에 두 부서의 HTTP 장치 계약을 검증한다. 실제
펌웨어, NFC 판독, TLS 인증서 검증과 현장 4회 파일럿을 대체하지 않는다.

## 1. 시작

Docker Desktop을 실행한 뒤 저장소 루트에서 다음 명령을 실행한다.

```bash
./scripts/local-demo-start.sh
docker compose --env-file /dev/null -f compose.local.yaml ps
```

`--env-file /dev/null`은 저장소의 운영 `.env`를 Compose 입력에서 명시적으로
제외한다. 앱과 DB는 로컬 데모 전용이다. 웹 API와 health는 각각
`http://127.0.0.1:8080`, `http://127.0.0.1:8081/actuator/health`에서만 접근할 수
있다. 운영 `.env`는 읽지 않으며 로컬 DB 포트는 host에 공개하지 않는다.

시작 스크립트는 앱 health 통과 후 seed SQL이 끝날 때까지 기다린다. seed는 고정
계정·장치를 중복 생성하지 않고 실행 당일의 출석 날짜와 대상자를 별도로 upsert한다.
따라서 날짜가 바뀌어도 volume을 지울 필요가 없다.
로컬 앱은 자동 마감 스케줄러도 활성화한다. 자정 이후 앱이 실행 중이거나 다시
기동되면 전날까지의 `SCHEDULED` 날짜를 catch-up하여 미기록 대상자는 `ABSENT`,
날짜는 `FINALIZED`로 저장한다. 개인 출석 통계는 이 공식 마감이 끝난 날짜만
분모에 포함한다.

## 2. 자동 HTTP 시험

최초 실행 직후 다음 스크립트로 두 부서의 신규 출석, 동일 요청 재전송,
`requestId` 충돌, 새 요청 재태깅과 부서 간 장치 경계를 검증한다.

```bash
./scripts/local-http-demo.sh
```

자동 시나리오의 기대 결과는 다음과 같다.

| 요청 | 기대 결과 |
|---|---|
| 같은 UID와 새 requestId로 재태깅 | `200 ALREADY_CHECKED_IN` |
| A 장치 code + B 장치 key | `401 DEVICE_UNAUTHORIZED`, 인증 실패 원인 비노출 |
| A 장치 code/key + B 부서 카드 UID | `409 NOT_DEPARTMENT_MEMBER`, `data = null`과 소유자 상세 비노출 |

HTTP 응답만으로 DB의 행 수와 최초 시각 불변을 직접 증명할 수는 없다. 따라서 같은 UID의
새 `requestId` 재태깅 뒤 `attendance_record`가 한 건이고 `checked_in_at`이 최초 값으로
유지되는지는 PostgreSQL Testcontainers 기반 `DeviceApiIntegrationTest`가 별도로 검증한다.

한 번 출석한 뒤 다시 실행하면 신규 출석이 아니라 `ALREADY_CHECKED_IN`이므로,
완전히 같은 최초 시나리오를 반복하려면 아래 초기화를 먼저 수행한다.

```bash
docker compose --env-file /dev/null -f compose.local.yaml down --volumes
./scripts/local-demo-start.sh
```

`down --volumes`는 `attend-local-demo`의 합성 로컬 DB만 삭제한다. 다른 Compose
프로젝트나 운영 DB에는 적용하지 않는다.

## 3. 관리자 웹

브라우저에서 `http://127.0.0.1:8080/`을 연다. 다음 계정은 loopback 데모에서만
사용하는 합성 계정이며 운영 환경에 복사하지 않는다.

| 역할 | 사용자명 | 비밀번호 | 로그인 후 이동 |
|---|---|---|---|
| 시스템 관리자 | `local-system-admin` | `local-system-admin-2026` | 시스템 관리 |
| 부서 관리자 | `local-department-admin` | `local-department-admin-2026` | 두 부서 작업 공간 선택 |

관리자 쓰기는 로컬 Compose에서만 활성화되어 교사·카드·정책·출석 날짜 form을
시험할 수 있다. 시스템 관리자는 부서 업무 권한을 자동으로 갖지 않으며, 부서
관리자는 시스템 관리 URL에 접근할 수 없다.

이 화면은 데모 전용 프런트가 아니라 운영 애플리케이션과 동일한 Thymeleaf 관리자
화면이다. `compose.local.yaml`만 합성 계정과 데이터를 주입한다. 운영에서는 시스템
관리자가 실제 부서와 관리자 계정을 생성하고 회원가입 초대 링크를 전달해야 한다.

두 역할의 로그인, 권한 분리, 핵심 관리자 페이지 렌더링을 브라우저 없이 실제 HTTP
세션으로 재검증하려면 다음 명령을 사용한다.

```bash
./scripts/local-admin-e2e.sh
```

레거시 화면의 교사 목록·등록·수정·상세, 생년월일 기반 나이, 지각·결석 통계,
오늘 출석과 최근 출석 이력은 부서 관리자 화면에 통합했다. 미등록 카드의 원본 UID는
화면에 표시하지 않고 카드 등록함의 이벤트에서 선택한 교사로 서버가 직접 연결한다.
레거시의 하드코딩 UID 출석 버튼은 운영 기능이 아니므로 이관하지 않는다.

장치 태깅을 관리자 화면의 버튼으로 흉내 내지 않는다. 장치 key를 브라우저에
노출하지 않기 위해 `local-http-demo.sh` 또는 Postman으로 태깅한 뒤 부서 대시보드와
출석 날짜 상세 화면에서 결과를 확인한다.

## 4. Postman

Postman에서 `postman/Attend-local-device-demo.postman_collection.json`을 import하고
컬렉션을 위에서 아래 순서로 실행한다. 포함된 장치 코드·key·UID는 이 loopback
데모에서만 쓰는 합성값이다. 실제 장치 key를 컬렉션 파일에 저장하지 않는다.
Credential test는 장치별 capacity 2, 20초당 1 token 정책이므로 컬렉션을 짧은
시간에 반복 실행하면 의도한 `429 RATE_LIMITED`가 발생할 수 있다.

주요 기대 결과는 다음과 같다.

| 요청 | 기대 결과 |
|---|---|
| INACTIVE 등록 장치 credential test | `200 CREDENTIAL_VALID` |
| 이미 ACTIVE인 장치 credential test | `409 CREDENTIAL_TEST_NOT_ALLOWED` |
| 최초 check-in | `201 CHECKED_IN` |
| 동일 payload replay | 최초 status와 body가 완전히 동일 |
| 같은 requestId, 다른 UID | `409 REQUEST_ID_CONFLICT` |
| 같은 교사, 새 requestId 재태깅 | `200 ALREADY_CHECKED_IN` |

## 5. 종료

데이터를 보존하고 중지만 하려면 `stop`, 합성 데이터까지 제거하려면
`down --volumes`를 사용한다.

```bash
docker compose --env-file /dev/null -f compose.local.yaml stop
```
