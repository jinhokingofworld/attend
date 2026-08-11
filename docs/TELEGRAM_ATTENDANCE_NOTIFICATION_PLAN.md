# 출석 마감 Telegram 알림 구현 계획

## 1. 목적과 결정 사항

출석일이 실제로 마감되면, 해당 부서의 활성 관리자에게 Telegram 개인 채팅으로
마감 결과를 보낸다. 이 기능은 관리자가 지각·결석자를 빠르게 확인하고 필요한
후속 조치를 하도록 돕는 운영 알림이다.

확정된 기준은 다음과 같다.

- 출석 완료는 모든 교사가 태그한 시점이 아니라, 해당 출석 정책의 마지막 출석
  구간이 끝난 뒤 출석일을 `FINALIZED`로 전환한 시점이다. 결석자가 있으면 모든
  대상자의 태그 완료를 기다릴 수 없기 때문이다.
- 마감 기준 시각은 출석일 생성 시점에 `Asia/Seoul` 기준으로 고정한다. 새 정책을
  발행해도 기존 출석일은 변하지 않으며, 태깅 시작 전 관리자가 해당 날짜의 정책을
  명시적으로 교체할 때만 새 정책 기준으로 함께 갱신한다.
- 수신자는 출석일이 속한 부서에 대해 활성 `DEPARTMENT_ADMIN` 권한을 가지고,
  Telegram 개인 채팅을 직접 연결한 모든 활성 계정이다. 현재 데이터 모델에는
  대표 관리자 개념이 없으므로 임의의 한 명만 고르지 않는다.
- 메시지에는 지각·결석자의 이름을 넣고, 동명이인이 있으면 출생연도의 뒤 두
  자리로 구분한다. 예: `김진호 (99)`, `김진호 (97)`.
- Telegram은 HTML `ul`/`li` 목록을 지원하지 않으므로, 메시지에서는 `•` 문자로
  목록을 표현한다. [Telegram Bot API](https://core.telegram.org/bots/api)
- Telegram 호출 실패가 출석 마감 트랜잭션을 되돌리면 안 된다. DB outbox에 작업을
  저장한 뒤 별도 worker가 발송·재시도한다.

## 2. 범위와 비범위

### 포함

- 당일 마지막 출석 구간 종료 뒤 자동 마감
- 마감 시각 경과·미마감 출석일 catch-up 유지
- 관리자 본인의 Telegram 개인 채팅 연결·해제·시험 발송
- 마감 집계와 지각·결석 명단의 Telegram 발송
- 멱등 outbox, 재시도, 영구 실패 표시와 운영 집계
- Telegram 토큰·chat ID·민감 출석 정보를 보호하는 보안·로그 정책

### 제외

- Telegram 그룹·채널 발송
- SMS·이메일·카카오톡 등 다른 채널
- 정상 출석자 전체 명단 발송
- Telegram으로 출석 데이터 수정
- 대표 관리자 지정 기능
- Telegram 발송 성공을 "정확히 한 번" 보장하는 주장

Telegram `sendMessage`에는 호출자용 idempotency key가 없다. 전송 성공 뒤 응답을
저장하기 전에 프로세스가 중단되면 드물게 같은 메시지가 다시 전송될 수 있다.
따라서 이 설계의 보장은 **최소 한 번 발송, 드문 중복 가능**이다.

## 3. 마감 규칙 변경

기존 구현은 `attendance_date < today`인 `SCHEDULED` 날짜만 자동 마감했다. 이대로면
"당일 마감 알림"이 다음 날에 도착하므로 due-time 기준으로 교체했다.

### 3.1 고정 마감 시각

`attendance_day`에 다음 열을 추가한다.

```sql
finalization_due_at TIMESTAMPTZ NOT NULL
```

출석일을 만들 때 정책의 마지막 `attendance_band.upper_time`을 구해 다음과 같이
계산한다.

```text
attendance_date + 마지막 upper_time + 1µs, Asia/Seoul
```

`upper_time`은 포함 경계다. PostgreSQL의 마이크로초 정밀도에 맞춰 그 시각의 정확히
`1µs` 뒤를 `finalization_due_at`으로 저장한다. 따라서 상한과 같은 시각의 체크인은
허용하고, 저장된 due 시각부터 마감 대상이다. 후속 V014부터 스케줄러는 고정 주기
polling 대신 DB의 가장 이른 due·retry·활성 lease 만료 시각에 단일 task를
동적으로 예약한다.

### 3.2 스케줄러와 경합 처리

`selectPastScheduledDayIds(today)`는 다음 조건을 사용하는
`selectDueScheduledDayIds(now)`로 교체한다.

```text
status = SCHEDULED AND finalization_due_at <= now
```

기존 `FinalizeAttendanceDayService`의 잠금 순서와 멱등성은 유지한다.

V014는 claim version과 lease를 DB에 저장하고 최초 실패 뒤 1·2·4·8·16분에
다섯 번 재시도한다. 이전 lease의 늦은 실패 update는 현재 claim version과 달라
반영되지 않는다.

```text
department 잠금 → attendance_day 잠금 → 누락 대상 결석 생성
→ FINALIZED 전환 → 감사 로그 → notification outbox 생성 → commit
```

NFC 체크인과 마감이 동시에 실행돼도 둘 다 같은 `attendance_day` 행을 잠그므로,
마감 잠금 전에 커밋된 체크인은 결석으로 덮이지 않고, 마감 뒤 체크인은
`CHECK_IN_CLOSED`로 거부된다.

## 4. 데이터 모델과 Flyway migration

신규 migration `V012__add_attendance_finalization_and_telegram_notifications.sql`에
다음 변경을 넣는다.

### 4.1 출석일 마감 시각

- `attendance_day.finalization_due_at` 추가
- 기존 `SCHEDULED`·`FINALIZED` 날짜는 연결된 정책의 마지막 구간 시각을 사용해
  backfill
- backfill 후 `NOT NULL`과 due-time 조회 인덱스 추가

후속 `V013__align_attendance_finalization_precision.sql`은 `SCHEDULED`, `FINALIZED`,
`CANCELED` 전체 기존 날짜의 값을 고정 정책 마지막 상한 `+1µs`로 보정한다.

과거 데이터에 마지막 정책 구간이 없으면 migration을 진행하지 말고 운영자가
데이터를 정정해야 한다. 추정값을 넣으면 마감 이력이 신뢰할 수 없게 된다.

### 4.2 Telegram 연결 토큰

`telegram_link_token`:

| 열 | 용도 |
| --- | --- |
| `id` | 내부 식별자 |
| `account_id` | 연결할 계정 |
| `token_hash` | 원문 대신 HMAC-SHA-256 hash |
| `issued_at`, `expires_at` | 짧은 유효 기간 |
| `consumed_at`, `revoked_at` | 1회 사용 및 무효화 이력 |

- 원문 token은 DB·감사 로그·애플리케이션 로그에 저장하지 않는다.
- 계정당 소비·무효화되지 않은 연결 token은 하나만 둔다.
- token은 10분 후 만료되며, link 생성 시 이전 token은 무효화한다.

### 4.3 Telegram 개인 채팅 연결

`account_telegram_connection`:

| 열 | 용도 |
| --- | --- |
| `account_id` | 계정당 하나의 현재 연결 |
| `chat_id` | Telegram 개인 채팅 식별자 |
| `telegram_user_id` | 개인 사용자 식별자 |
| `linked_at`, `updated_at` | 연결 시각 |

- `account_id`와 `chat_id` 모두 유일하다.
- 연결 해제 시 행을 삭제한다. chat ID는 운영상 필요한 현재 개인정보이므로,
  불필요한 이력을 남기지 않는다.
- 감사 로그에는 연결·해제 여부만 기록하며 chat ID를 저장하지 않는다.

### 4.4 수신 webhook 중복 방지

`telegram_webhook_update`:

| 열 | 용도 |
| --- | --- |
| `update_id` | Telegram update의 유일 식별자 |
| `received_at` | 수신 시각 |

- payload 전문은 보관하지 않는다.
- `update_id`의 unique 제약으로 같은 webhook 재전송을 멱등 처리한다.
- retention worker에 짧은 보유기간의 정리 대상을 추가한다.

### 4.5 알림 outbox

`attendance_notification_outbox`:

| 열 | 용도 |
| --- | --- |
| `id` | 내부 식별자 |
| `notification_type` | `ATTENDANCE_DAY_FINALIZED` |
| `attendance_day_id`, `department_id`, `account_id` | 이벤트와 수신자 범위 |
| `attendance_date`, `department_name` | 마감 당시 표시값 |
| `target_count`, `present_count`, `late_count`, `absent_count` | 마감 당시 집계 |
| `late_members`, `absent_members` | 메시지용 최소 명단 snapshot JSONB |
| `status`, `attempt_count`, `next_attempt_at`, `lease_until` | 발송 상태와 재시도 |
| `telegram_message_id`, `sent_at`, `last_error_code` | 결과 추적 |

`(notification_type, attendance_day_id, account_id)`는 유일해야 한다. 같은 출석일의
마감을 여러 번 시도하거나 여러 scheduler가 실행돼도 계정당 outbox 행은 하나만
생긴다.

`late_members`, `absent_members`에는 이름과 필요한 경우의 출생연도 뒤 두 자리만
넣는다. 전화번호, 전체 생년월일, NFC UID, 비고, 정정 사유는 넣지 않는다.

## 5. 메시지 명단 규칙

명단은 마감 시점의 `attendance_record`를 기준으로 만들고 outbox에 snapshot한다.
따라서 발송이 지연되거나 이후 정정이 발생해도 "마감 완료" 메시지의 내용이
사후에 변하지 않는다.

### 5.1 이름 표기

1. 지각·결석자 전체에서 이름이 유일하면 이름만 표시한다.
2. 같은 이름이 둘 이상이면 그 이름을 가진 모든 대상자에 출생연도 뒤 두 자리를
   붙인다.
3. 출생연도가 없는 레거시 교사는 생년월일을 추정하지 않고 `ID {memberId}`를 쓴다.
4. 정렬은 상태별 이름 오름차순, 같은 이름이면 출생연도·내부 ID 오름차순으로
   고정한다.

예시:

```text
지각
• 김진호 (99)
• 이수민

결석
• 김진호 (97)
• 박서연
```

### 5.2 본문 형식

Telegram `parse_mode=HTML`에 의존하지 않고 일반 텍스트와 `•` 목록으로 만든다.
이름이나 부서명에 HTML 예약 문자가 있어도 메시지 형식이 깨지지 않는다.

```text
출석 마감 완료

부서: 유치부
날짜: 2026-08-07
대상: 24명 / 정상: 19명 / 지각: 2명 / 결석: 3명

지각
• 김진호 (99)
• 이수민

결석
• 김진호 (97)
• 박서연

상세 확인
https://example.com/admin/departments/3/attendance-days/81
```

지각 또는 결석자가 없으면 해당 섹션에는 `없음`을 표시한다. Telegram 메시지 길이
제한을 넘길 수 있는 대규모 명단은 처음 N명과 `외 M명`으로 축약하고, 전체 목록은
인증이 필요한 관리자 화면 링크에서 확인한다. N의 기본값과 최대 메시지 길이는
설정값으로 둔다.

## 6. Telegram 연결 흐름

관리자가 숫자 chat ID를 직접 입력하지 않도록 본인 연결 방식으로 제공한다.

```text
관리자 로그인
  → 알림 설정에서 연결 요청
  → 1회용 deep link 생성
  → Telegram에서 bot Start
  → webhook의 /start token 검증
  → 개인 chat ID와 계정 연결
  → 연결 완료 응답
```

deep link는 다음 형식이다.

```text
https://t.me/{botUsername}?start={oneTimeToken}
```

Telegram은 private chat의 `/start`에 deep-link parameter를 전달한다.
[Telegram deep linking](https://core.telegram.org/bots/features)

구현 시 다음을 강제한다.

- `/api/v1/telegram/webhook`은 공개 경로지만 Telegram webhook secret header를
  검증한다.
- `X-Telegram-Bot-Api-Secret-Token`이 없거나 다르면 즉시 거부한다.
- private chat이 아닌 update는 연결 요청으로 처리하지 않는다.
- 해당 token을 처음 소비한 update만 연결한다.
- bot token은 URL·로그·오류 응답에 절대 포함하지 않는다.
- 연결·해제는 로그인한 본인만 수행할 수 있다.

Telegram webhook은 HTTPS endpoint와 secret token header를 지원한다.
[Telegram Bot API](https://core.telegram.org/bots/api)

## 7. 발송 worker와 실패 정책

### 7.1 작업 claim

worker는 `PENDING` 또는 재시도 시각이 지난 `RETRY` 작업을 batch로 고른다.
`FOR UPDATE SKIP LOCKED`와 `lease_until`을 사용해 향후 다중 인스턴스가 되어도 같은
작업을 동시에 보내지 않는다. DB 잠금은 HTTP 호출 전에 해제한다.

### 7.2 발송 전 재검증

outbox가 생성된 뒤에도 다음을 다시 확인한다.

- account가 `ACTIVE`인지
- account가 해당 부서의 활성 `DEPARTMENT_ADMIN`인지
- 계정의 Telegram 개인 채팅 연결이 아직 존재하는지

어느 하나라도 아니면 메시지를 보내지 않고 `CANCELED`로 전환한다. 권한이 회수된
관리자에게 늦게 출석 정보를 보내는 것을 막기 위한 규칙이다.

### 7.3 결과 처리

| 결과 | 처리 |
| --- | --- |
| Telegram 2xx | `SENT`, `message_id`, `sent_at` 저장 |
| HTTP 429 | `retry_after`에 맞춰 `RETRY` |
| timeout·네트워크 오류·5xx | 지수 backoff와 jitter로 `RETRY` |
| 차단·유효하지 않은 chat 같은 영구 4xx | 연결 삭제, `DEAD` |
| 재시도 최대 횟수 초과 | `DEAD`와 운영 경고 |

Telegram API는 실패 응답에 `retry_after`를 포함할 수 있다.
[Telegram Bot API](https://core.telegram.org/bots/api)

Telegram 네트워크 오류는 출석일 `FINALIZED` 상태나 결석 기록을 롤백하지 않는다.
다만 outbox를 같은 트랜잭션에서 생성하므로 DB 수준에서 이벤트 누락 없이 다시
시도할 수 있다.

## 8. 관리자 Telegram 연동 프론트엔드 계획

관리자에게 chat ID를 입력하게 하거나 Telegram 계정 정보를 직접 요구하지 않는다.
로그인한 관리자가 본인 계정 화면에서 1회용 Telegram deep link를 만들고, Telegram
앱에서 bot을 시작해 연결하는 흐름으로 제한한다.

### 8.1 화면 위치와 권한

- 새 경로: `GET /admin/account/notifications`
- 새 쓰기 경로:
  - `POST /admin/account/notifications/telegram/link`
  - `POST /admin/account/notifications/telegram/disconnect`
  - `POST /admin/account/notifications/telegram/test`
- 세 경로 모두 인증된 본인 계정만 사용한다. path·form에서 `accountId`를 받지 않고,
  세션의 `AccountPrincipal.accountId()`를 유일한 계정 근거로 사용한다.
- 연결 생성·해제·시험 발송은 기존 `ADMIN_WRITE_ENABLED` gate를 적용한다.
- Telegram 기능이 비활성화된 배포에서는 메뉴를 숨기고, URL을 직접 열어도 설정이
  비활성화됐다는 안내만 보여 준다. 연결 정보나 bot 설정은 노출하지 않는다.

메뉴에는 기존 `비밀번호 변경`과 같은 본인 계정 작업으로 `알림 설정` 링크를 추가한다.
시스템 관리자 화면이나 부서 관리 화면에 Telegram chat ID를 편집하는 기능은 만들지
않는다.

### 8.2 화면 상태와 표시 내용

`admin/account-notifications.html`은 다음 세 상태를 명확히 구분한다.

| 상태 | 사용자에게 보이는 내용 | 가능한 동작 |
| --- | --- | --- |
| 기능 비활성 | "Telegram 알림 기능이 현재 비활성화되어 있습니다." | 없음 |
| 미연결 | 개인정보·전송 범위 안내 | `Telegram 연결` |
| 연결 대기 | bot deep link, 만료 시각, 새 link 생성 안내 | `Telegram 열기`, `새 link 생성`, `취소` |
| 연결됨 | 연결 완료 시각, 개인정보 안내 | `시험 메시지`, `연결 해제` |

연결됨 상태에서는 Telegram 사용자명, chat ID, bot token을 표시하지 않는다. 관리자에게
필요한 정보는 연결 여부와 연결 시각뿐이다.

### 8.3 연결 시작 화면 흐름

관리자가 `Telegram 연결`을 누르면 서버가 기존 활성 연결 token을 무효화하고 새
one-time token을 만든다. 응답은 PRG redirect로 연결 대기 상태 화면을 다시 표시한다.

화면에는 다음을 제공한다.

```text
Telegram 알림 연결

출석일 마감 시 지각·결석 명단을 포함한 알림을 Telegram 개인 채팅으로 받습니다.
전화번호, NFC 카드 번호, 출석 정정 사유는 전송하지 않습니다.

[Telegram에서 연결하기]
이 link는 10분 후 만료됩니다.

Telegram에서 "시작"을 누르면 이 페이지를 새로고침해 연결 상태를 확인하세요.
[연결 상태 새로고침] [새 link 생성] [취소]
```

- `Telegram에서 연결하기`는 `https://t.me/{botUsername}?start={token}`을 새 창으로
  연다. `target="_blank"`와 `rel="noopener noreferrer"`를 함께 사용한다.
- token은 화면 HTML에만 일시적으로 표시하고, redirect URL·query string·flash message·
  JavaScript log에 넣지 않는다.
- 화면이 새로고침되면 원문 token을 다시 표시하지 않는다. 대기 상태는 만료 시각만
  남기고, 새 link 생성으로 새 token을 만든다.
- 자동 polling은 기본으로 넣지 않는다. browser가 백그라운드에서 불필요하게 계정
  상태를 조회하지 않도록, 사용자가 명시적으로 새로고침한다. 필요성이 확인되면
  후속 범위에서 인증된 짧은 polling으로 추가한다.

Telegram의 private-chat deep link는 `/start` parameter를 전달한다.
[Telegram deep linking](https://core.telegram.org/bots/features)

### 8.4 연결 해제와 시험 발송 UX

연결 해제는 되돌릴 수 있지만 이후 새 알림을 받지 않는 보안상 중요한 동작이다.
별도 확인 화면이나 modal에서 다음을 명시한다.

```text
Telegram 연결을 해제할까요?
해제 후에는 출석 마감 알림을 받을 수 없습니다.
[해제] [취소]
```

`해제` POST가 성공하면 현재 chat 연결 행을 삭제하고, 대기 중이거나 재시도 예정인
해당 계정의 outbox는 `CANCELED`로 전환한다. 감사 로그에는 `TELEGRAM_DISCONNECTED`와
작업 계정만 남긴다.

`시험 메시지`는 연결된 본인에게만 다음과 같은 식별 가능한 비업무 메시지를 보낸다.

```text
[시험] 출석 알림 Telegram 연결이 정상입니다.
실제 출석 정보는 포함되지 않습니다.
```

시험 발송은 Telegram API 응답을 기다리는 동기 호출이 아니라 별도의
`TELEGRAM_CONNECTION_TEST` outbox 작업으로 만든다. 화면은 "시험 메시지 발송을
요청했습니다"까지만 알리고, 성공·실패 상태는 새로고침 시 표시한다. 이 방식은
Telegram 지연이 관리자 웹 요청을 붙잡지 않게 한다.

### 8.5 백엔드–프론트엔드 view model 계약

Controller는 template에 다음 최소 view model만 전달한다.

| 필드 | 용도 |
| --- | --- |
| `telegramEnabled` | 기능 사용 가능 여부 |
| `connectionState` | `DISABLED`, `UNLINKED`, `LINK_PENDING`, `LINKED` |
| `linkedAt` | 연결 완료 시각, `LINKED`에서만 |
| `linkUrl` | 이번 응답에서 새로 만든 token이 있을 때만 |
| `linkExpiresAt` | 대기 token 만료 시각 |
| `testRequestState` | 최근 시험 발송의 안전한 상태 요약 |
| `writeEnabled` | 기존 관리자 쓰기 gate 상태 |

`chatId`, `telegramUserId`, token hash, bot token, webhook secret, outbox payload 전문은
view model과 HTML 모두에서 제외한다.

POST 요청 성공·실패는 기존 관리자 화면 패턴과 맞춰 `RedirectAttributes`의 일반
success/error message로 전달한다. 오류 메시지에는 Telegram 원문 응답이나 URL을
넣지 않는다.

### 8.6 스타일·접근성·반응형 기준

- 기존 `admin.css`와 관리자 shell을 재사용하고, 독립 UI framework를 도입하지 않는다.
- 상태는 색만으로 구분하지 않는다. `연결됨`, `연결 대기`, `미연결`, `비활성`이라는
  텍스트와 상태 아이콘을 함께 쓴다.
- Telegram 열기 link, link 재생성, 해제, 시험 발송은 명확한 버튼 label을 사용한다.
- destructive action인 해제 버튼은 다른 action과 시각적으로 분리하고 확인 단계를 둔다.
- 만료 시각은 서버 기준 `Asia/Seoul` 시간과 절대 시각으로 표시한다.
- mobile 폭에서도 long deep link 자체를 본문에 노출하지 않고 버튼으로 유지한다.
- 새 창을 열 수 없거나 Telegram desktop/web client가 없는 경우에는 link 복사 버튼을
  제공하되, 복사 성공 여부를 화면에 알린다. 복사한 link를 support 로그에 전송하지
  않는다.

### 8.7 프론트엔드 테스트 계획

- 미인증 요청은 로그인으로 이동하고, 다른 계정의 연결 상태를 URL 조작으로 볼 수 없다.
- 기능 비활성, 미연결, 연결 대기, 연결됨 상태가 각각 올바르게 렌더링된다.
- `linkUrl`은 link를 막 생성한 응답에서만 렌더링되고, 새로고침 후 HTML·redirect URL에
  남지 않는다.
- Telegram 연결·재생성·해제·시험 발송 form에는 CSRF token이 포함된다.
- 해제 확인 화면에서 취소하면 연결 상태가 바뀌지 않는다.
- chat ID, Telegram 사용자 ID, token, bot token, webhook secret이 HTML·flash message·
  error response·로그에 노출되지 않는다.
- 키보드만으로 모든 action에 도달하고, 상태 텍스트가 색상 없이도 구분된다.
- 작은 화면에서 버튼·상태·개인정보 안내가 잘리지 않는다.

## 9. 애플리케이션 구성

### 8.1 신규 패키지

```text
notification/
  application/AttendanceFinalizationNotificationPublisher
  application/TelegramConnectionService
  application/TelegramNotificationDispatcher
  domain/NotificationStatus
  domain/AttendanceNotificationPayload
  infrastructure/mybatis/AttendanceNotificationMapper
  infrastructure/telegram/TelegramBotClient
  web/TelegramWebhookController
  scheduler/TelegramNotificationScheduler
```

`FinalizeAttendanceDayService`는 마감과 같은 트랜잭션에서
`AttendanceFinalizationNotificationPublisher`를 호출한다. Telegram HTTP client는
여기에서 직접 호출하지 않는다.

### 8.2 설정

기본값은 모두 비활성화한다.

```properties
attendance.telegram.enabled=${TELEGRAM_NOTIFICATIONS_ENABLED:false}
attendance.telegram.bot-token=${TELEGRAM_BOT_TOKEN:}
attendance.telegram.bot-username=${TELEGRAM_BOT_USERNAME:}
attendance.telegram.webhook-secret=${TELEGRAM_WEBHOOK_SECRET:}
attendance.telegram.link-token-pepper=${TELEGRAM_LINK_TOKEN_PEPPER:}
attendance.telegram.dispatch-fixed-delay-ms=30000
attendance.telegram.max-attempts=10
attendance.telegram.max-listed-members=30
```

운영 profile에서 `enabled=true`이면 token, bot username, webhook secret, 별도 link
token pepper가 모두 있어야 기동한다. `TELEGRAM_BOT_TOKEN`과
`TELEGRAM_LINK_TOKEN_PEPPER`는 서로 다른 충분한 길이의 무작위 값이어야 한다.

`TELEGRAM_NOTIFICATIONS_ENABLED=false`인 경우 Telegram 기능만 중지한다. 출석 당일
마감 자체는 Telegram의 가용성과 독립적으로 동작해야 한다.

### 8.3 화면과 운영 상태

본인 계정 화면 `/admin/account/notifications`:

- Telegram 연결 상태
- 연결 link 생성
- 연결 해제
- 시험 메시지 발송

시스템 운영 화면:

- `PENDING`, `RETRY`, `DEAD` outbox 수
- 최근 발송 성공·실패 시각
- 마지막 실패의 안전한 오류 코드

bot token, webhook secret, chat ID, 메시지 원문은 화면과 운영 로그에 표시하지 않는다.

## 10. 구현 순서

1. 문서와 테스트 계획에서 당일 마감 기준, 수신자, 명단 노출 규칙을 확정한다.
2. V012 migration과 migration test를 작성한다.
3. 출석일 생성 시 `finalization_due_at`을 고정하고, scheduler 조회를 due-time 기준으로
   변경한다.
4. 당일 마감·NFC 체크인 경합·과거 catch-up의 통합 테스트를 통과시킨다.
5. outbox mapper, 명단 snapshot 생성기, 동명이인 표기 formatter를 구현한다.
6. Telegram 설정 검증, HTTP client, dispatcher, 재시도·lease를 구현한다.
7. 관리자 연결 token, webhook, 연결·해제·시험 발송 화면을 구현한다. 이때
   `admin/account-notifications.html`, 본인 계정 Controller·view model, 관리자 shell
   메뉴, CSRF·PRG·접근성 테스트를 한 묶음으로 완료한다.
8. 운영 상태, 구조화 로그 마스킹, retention 작업을 구현한다.
9. 전체 문서·`.env.example`·compose 배포 설정을 갱신한다.
10. staging bot과 test chat으로 canary 검증 후 운영에서 기능을 단계적으로 연다.

## 11. 테스트와 완료 조건

### 마감과 outbox

- 마지막 구간 종료 전에는 당일 마감하지 않는다.
- 마지막 구간의 정확한 경계 시각에는 체크인이 허용된다.
- 경계 직후에는 마감되고 이후 체크인은 거부된다.
- 마감 시각이 지난 미마감 날짜도 다시 찾아 처리한다.
- 체크인과 마감의 동시 실행에서 기록이 유실·결석으로 덮어쓰기 되지 않는다.
- 반복·동시 마감에도 날짜 상태, 감사 로그, 관리자별 outbox가 중복되지 않는다.
- 활성·연결된 부서 관리자에게만 outbox가 생성된다.

### 명단과 개인정보

- 지각과 결석 명단이 마감 당시 결과와 일치한다.
- 동명이인만 출생연도 뒤 두 자리가 붙는다.
- 출생연도 없는 동명이인은 `ID {memberId}`로 구분된다.
- 정상 출석자, 전화번호, 전체 생년월일, NFC UID, 비고, 정정 사유가 메시지·payload·로그에
  포함되지 않는다.
- 대규모 명단은 정해진 상한에서 안전하게 축약되고 관리자 화면 링크가 포함된다.

### Telegram 연결과 발송

- webhook secret 오류와 private chat이 아닌 요청을 거부한다.
- 연결 token 만료·재사용·중복 webhook을 안전하게 처리한다.
- 200, 429, 영구 4xx, 5xx, timeout에 따른 상태·재시도 시각이 정확하다.
- 권한 회수·계정 비활성화·연결 해제 뒤에는 발송하지 않는다.
- Telegram 장애 중에도 출석일 마감은 완료된다.
- 운영 설정이 비활성화되면 Telegram worker·webhook 연결 기능이 동작하지 않는다.

### 관리자 연동 화면

- 본인 계정 화면에서만 연결·해제·시험 발송을 수행할 수 있다.
- 연결 대기 link는 1회 응답에서만 표시되고, 새로고침·browser history·서버 로그에
  남지 않는다.
- 기능 상태별 화면과 flash message가 명확하고 민감정보를 포함하지 않는다.
- 해제 시 이후 발송할 outbox가 취소되고, 시험 발송은 실제 출석 명단을 포함하지 않는다.
- CSRF, 키보드 탐색, 작은 화면 rendering을 자동·수동 검증한다.

## 12. 배포와 롤백

배포 순서는 다음과 같다.

```text
migration 적용
→ 앱 배포 (Telegram 기능 OFF)
→ Telegram bot webhook 설정
→ 관리자 1명 연결 및 시험 발송
→ Telegram 기능 ON
→ 부서 1곳 당일 마감 canary
→ 전체 관리자 연결 안내
```

Telegram 기능에 문제가 생기면 `TELEGRAM_NOTIFICATIONS_ENABLED=false`로 알림 worker와
연결 기능만 중지한다. 출석 기록과 당일 마감은 계속 유지한다. 미발송 outbox는
보존하므로, 기능을 다시 켤 때 재발송할지 폐기할지는 운영 화면에서 명시적으로
결정해야 한다.
