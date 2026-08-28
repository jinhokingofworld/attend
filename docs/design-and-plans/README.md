# 설계·구현 계획 기록

이 폴더는 Attend를 만들 때 사용한 요구사항, 설계 의도와 구현 계획을 보관한다.
완료된 계획과 당시의 버전 번호·운영 가정도 포함하므로 현재 실행 절차나 스키마의
최종 기준으로 사용하지 않는다.

현재 구현의 기준은 다음과 같다.

- 업무 동작: `src/main/java`와 `src/test/java`
- DB 스키마: `src/main/resources/db/migration`
- 장치 HTTP 계약: [device-api.yaml](../device-api.yaml)
- 배포 절차: [ARM64 배포 가이드](../ARM64_DOCKER_CLOUDFLARE_ARDUINO_DEPLOYMENT_GUIDE.md)

## 설계 의도와 명세

| 문서 | 기록한 내용 |
|---|---|
| [PROJECT_DEFINITION.md](./PROJECT_DEFINITION.md) | 문제 정의, 범위와 인수 기준 |
| [ARCHITECTURE.md](./ARCHITECTURE.md) | 모듈·보안·트랜잭션·운영 구조 |
| [DATABASE_DESIGN.md](./DATABASE_DESIGN.md) | 데이터 모델과 무결성 설계 |
| [SECURITY_MATRIX.md](./SECURITY_MATRIX.md) | 역할·부서·기능별 보안 경계 |
| [ADMIN_UI_SPEC.md](./ADMIN_UI_SPEC.md) | 관리자 웹 정보 구조와 화면 계약 |

## 구현·검증 계획

| 문서 | 기록한 내용 |
|---|---|
| [IMPLEMENTATION_PLAN.md](./IMPLEMENTATION_PLAN.md) | MVP 단계별 구현 순서 |
| [MIGRATION_PLAN.md](./MIGRATION_PLAN.md) | DB 전환·컷오버·롤백 계획 |
| [TEST_PLAN.md](./TEST_PLAN.md) | 위험 기반 테스트와 인수 시나리오 |
| [M5_M6_OPERATIONS_RUNBOOK.md](./M5_M6_OPERATIONS_RUNBOOK.md) | 초기 운영 준비·파일럿 계획 |
| [TELEGRAM_ATTENDANCE_NOTIFICATION_PLAN.md](./TELEGRAM_ATTENDANCE_NOTIFICATION_PLAN.md) | 일반 출석 Telegram 알림 설계 |
| [FINALIZATION_OPERATIONAL_ALERT.md](./FINALIZATION_OPERATIONAL_ALERT.md) | 자동 마감 재시도 소진 알림 설계 |
| [lateruleplan.md](./lateruleplan.md) | 반복 출석 정책 전환 계획 |

