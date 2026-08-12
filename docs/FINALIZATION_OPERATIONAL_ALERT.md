# 자동 마감 재시도 소진 운영 알림

## 범위

최초 마감 실행과 1·2·4·8·16분의 다섯 retry가 모두 실패하면 총 6회 실패다.
마지막 실패 transaction은 `FINALIZATION_RETRY_EXHAUSTED` 이벤트를
`finalization_operational_event`에 저장한다. 수동 재마감 API·UI와 복구 완료
이벤트는 후속 변경 범위다.

## 전달 정보

운영 Telegram 메시지는 다음 정보만 포함한다.

- 부서 이름과 ID
- 출석일과 출석일 ID
- 최초·최종 실패 시각
- 총 시도 횟수
- 예외 class에서 만든 80자 이하 안전 오류 코드
- 운영 이벤트 추적 키와 시스템 관리자 운영 화면 링크

교사 명단, 출석 상태, 계정명, 전화번호, Telegram 사용자·chat ID, 예외 메시지와
stack trace는 DB snapshot과 Telegram 문자에 포함하지 않는다. 원인 분석을 위한
Throwable은 접근 통제와 retention이 적용되는 서버 운영 로그에 기록될 수 있다.

## 별도 운영 Bot 설정

출석 관리자용 Bot과 다른 Bot을 BotFather에서 만들고 개발자 개인 채팅 또는 전용
비공개 그룹에 추가한다. 운영자가 먼저 Bot에 메시지를 보낸 뒤 Telegram Bot API로
chat ID를 확인한다. Bot token과 chat ID는 저장소 밖 배포 secret에 보관한다.

```properties
OPERATIONS_TELEGRAM_ENABLED=true
OPERATIONS_TELEGRAM_BOT_TOKEN=<BotFather가 발급한 운영 Bot token>
OPERATIONS_TELEGRAM_CHAT_ID=<운영자 개인 또는 비공개 그룹 chat ID>
```

운영 profile에서 자동 마감을 활성화하면 운영 Telegram도 반드시 활성화해야 한다.
token이나 chat ID가 없으면 애플리케이션은 기동을 거부한다. 실제 비밀값은 HTML,
Actuator, 로그, Git과 Docker image에 포함하지 않는다.

## 전달 신뢰성

- 사건 키는 `(event_type, attendance_day_id, incident_claim_version)`이며 중복 INSERT를 막는다.
- 6회차 실패 상태와 `PENDING` outbox는 하나의 DB transaction으로 commit한다.
- commit 직후 `AFTER_COMMIT` listener가 전용 단일-thread executor에 즉시 전송을
  제출한다. Telegram 네트워크 호출은 자동 마감 scheduler thread에서 실행하지 않는다.
- 애플리케이션 시작 시 만료 lease와 ready outbox를 복구하고, 이후 60초마다 같은
  전달 복구를 실행한다. 이 polling은 이미 생성된 운영 문자의 전달만 담당하며,
  놓친 출석일 마감을 찾는 finalization reconciliation이 아니다.
- worker는 2분 lease와 delivery claim version으로 여러 인스턴스의 결과를 fencing한다.
- 성공한 Telegram HTTP 응답의 양수 message ID를 저장한 뒤 `SENT`로 전환한다.
- 429는 Telegram `retry_after`, 그 밖의 timeout·4xx·5xx·설정 오류는 30초부터
  최대 1시간의 capped backoff로 계속 재시도한다.
- 운영 Bot 설정 오류 때문에 사건을 `DEAD`로 버리지 않는다. 설정을 수정하면 저장된
  `PENDING`·`RETRY` 이벤트가 자동으로 다시 전달된다.
- 운영 Bot은 outbound 전용이므로 webhook과 연결 token을 사용하지 않는다.

Telegram은 idempotency key를 지원하지 않는다. Telegram 전송 성공 직후 `SENT` 갱신
전에 프로세스가 중단되면 lease 복구 뒤 같은 문자가 다시 전송될 수 있다. 따라서 이
outbox는 exactly-once가 아니라 at-least-once 전달이며, 메시지의
`finalization-event:{id}` 추적 키로 드문 중복을 식별한다.

V016 이전에는 최초 실패 시각을 따로 저장하지 않았다. upgrade 시 이미 실패 중이거나
6회 소진된 행은 알 수 있는 마지막 실패 시각을 최초 시각으로도 보정하며, 시각 자체가
없던 V015-valid 행은 migration transaction 시각을 사용한다. 기존 6회 소진 사건은
`PENDING` outbox로 backfill되어 업그레이드된 앱의 startup 복구에서 발송된다.

Telegram 플랫폼 전체 장애는 출석 관리자 Bot과 운영 Bot에 동시에 영향을 줄 수 있다.
이 위험을 제거하려면 후속으로 Sentry·PagerDuty 등 다른 provider를 같은 outbox의
추가 sender로 연결해야 한다.
