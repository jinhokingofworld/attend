# M5 운영 준비와 M6 파일럿 실행서

> 문서 상태: 배포·실기기 시험 전 실행 기준
>
> 현재 판정: M5 구현 산출물 준비, M6 실물·현장 증거 미수집

이 문서는 실제 배포를 승인하지 않는다. 운영 DB 분류, 개인정보 보유기간, 백업
저장소, 도메인과 담당자가 승인된 뒤에만 명령을 실행한다. `.env`, 장치 key,
교사 UID·연락처를 증적이나 이슈에 첨부하지 않는다.

## 1. 배포 전 필수 결정

| 결정 | 현재 상태 | 미결정 시 조치 |
|---|---|---|
| 공개 DNS hostname과 ACME 담당 이메일 | 미정 | Caddy 기동 금지 |
| Caddy 전용 32-byte 이상 독립 proxy token | 미정 | 공개 app 기동 금지 |
| Neon pooled runtime / direct migration·backup 자격증명 | 미정 | DB 작업 금지 |
| 운영 DB 분류와 이관 승인 | 미정 | guarded migration 금지 |
| 백업 보유기간·off-host 저장소·접근 담당자·삭제 절차 | 미정 | 실제 데이터 백업 금지 |
| 파일럿 부서 2개, 각 5~20명, 일정 4회 | 미정 | M6 완료 판정 금지 |

## 2. M5 사전 검증

1. 고정 commit에서 `./gradlew test`를 통과시킨다.
2. `docker compose -f compose.prod.yaml config --no-env-resolution --quiet`로 누락
   변수와 구문을 확인한다. `config` 전체 출력은 환경값을 렌더링할 수 있으므로 운영
   terminal·CI log에 출력하지 않는다. 이 runtime 파일은 migration 관리자 변수를
   요구하지 않는다. Migration은 별도 `compose.migration.yaml`과 별도 secret
   source로만 실행한다.
3. 빌드 후 image digest를 배포 기록에 고정한다. `latest`나 재빌드한 동일 tag를
   운영 근거로 사용하지 않는다.
4. 운영 DB는 `ops/db/roles` 순서로 준비한 뒤 고정 image tag에서
   `docker compose -f compose.migration.yaml run --rm migration`을 한 번 실행해 V008까지
   적용한다. 이 컨테이너에만 Neon direct URL과 migration 계정을 주입한다.
5. runtime 계정에 DDL, `TEMP`, 레거시 DML 권한이 없는지 기존 DB 권한 검사를
   다시 수행한다.
6. `ADMIN_WRITE_ENABLED=false`, `DEVICE_API_ENABLED=false`,
   `ATTENDANCE_SCHEDULER_ENABLED=false`로 최초 기동한다.
7. host 내부에서 `127.0.0.1:8081/actuator/health`가 `UP`, 공개 hostname의
   `/actuator/health`는 도달 불가인지 확인한다.
8. Caddy는 외부 `X-Forwarded-For`와 내부 token header를 upstream에서 덮어쓴다.
   앱은 token이 일치하는 단일 IP만 rate-limit source로 사용하며, app port를 host에
   publish하지 않는다.
9. 관리자 운영 화면에 버전·시작 시각·세 flag·V008 상태가 표시되고 URL·비밀값이
   없는지 확인한다.

## 3. 백업과 복원

`ops/backup/backup.sh`는 하루 1회와 날짜 마감 직후 실행한다. 결과의 dump,
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
