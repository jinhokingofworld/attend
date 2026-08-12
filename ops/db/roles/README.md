# Attend DB 역할 적용 순서

이 디렉터리의 SQL은 Flyway migration이 아니다. 역할은 PostgreSQL cluster
범위이고 비밀번호와 로그인 수명은 배포 환경이 관리하므로, V001~V016에 넣지
않는다.

1. PostgreSQL 역할 관리자 계정으로 `001_create_login_roles.sql`을 실행한다.
2. 대상 DB와 `public` schema 소유자로
   `002_prepare_database_for_migration.sql`을 실행한다.
3. 비밀 저장소에서 `migration_owner` 비밀번호를 별도로 설정한다.
4. `migration_owner`로 guarded `dbMigrate`를 실행한다.
5. V016 검증 후 객체 소유자로
   `003_grant_application_privileges.sql`을 실행한다.
6. `app_runtime`, `cutover_writer`, `legacy_writer`, `retention_worker`의 비밀번호는 필요한 시점에만
   별도로 발급한다. 웹 애플리케이션에는 `app_runtime`만, 분리된 retention container에는
   `retention_worker`만 주입한다.

`retention_worker`는 `public`의 고정 audit·tag event·Telegram webhook update purge 함수 3개에 대한
`EXECUTE`와 `public` schema `USAGE`만 가진다. 다른 모든 비시스템 schema의 ACL,
객체 소유권, Large Object 권한과 database·schema DDL 권한은 금지하며 worker는
실행 전에 이 경계를 전체 database catalog에서 fail-closed로 검사한다.

레거시 DB에서는 `migration_owner`가 승인된 네 테이블, 세 sequence와 두 enum의
소유자이거나 그 소유 역할의 구성원이어야 한다. guarded runner가 이 조건을
baseline 전에 검사하므로, 소유권이 불명확한 DB에는 version 0 history도 만들지
않는다.

`cutover_writer`와 `legacy_writer`는 상시 운영 계정이 아니다. 컷오버와 제한된
롤백 가능 시간이 끝나면 로그인 또는 비밀번호를 회수한다. 실제 비밀번호,
connection string, 백업 파일은 이 디렉터리에 저장하지 않는다.
