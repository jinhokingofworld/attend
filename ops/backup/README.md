# 백업·복원 시험

두 스크립트는 운영 웹의 pooled URL이 아니라 Neon **direct** PostgreSQL URL을
사용한다. URL은 `postgresql://...?...sslmode=require` 형식으로 환경변수에만
주입하고 명령행 인자로 넘기지 않는다. `sslmode=require`, `verify-ca`,
`verify-full` 중 하나를 명시하지 않으면 평문 fallback을 막기 위해 실행을 거부한다.
실행 로그에도 URL이나 비밀번호를 남기지 않는다.

```bash
BACKUP_DATABASE_URL='postgresql://...?sslmode=require' \
BACKUP_OUTPUT_DIR='/approved/off-host/path' \
BACKUP_STATUS_FILE='/var/lib/attend/backup-status/status.properties' \
BACKUP_STORAGE_TYPE='OBJECT_STORAGE' \
./ops/backup/backup.sh
```

`BACKUP_STATUS_FILE`을 지정하면 스크립트가 성공과 실패를 모두 Java properties
형식으로 원자적 교체하고, 운영 화면은 이 파일을 읽어 24시간 freshness를 판정한다.
`BACKUP_STORAGE_TYPE`은 `OFF_HOST_FILESYSTEM`, `OBJECT_STORAGE`,
`MANAGED_DATABASE_BACKUP` 중 하나만 허용한다. 상태 파일에는 경로, checksum,
접속 정보가 들어가지 않는다. 위 `/var/lib/attend/backup-status/status.properties`는
향후 운영 job을 구성할 때 사용할 예시 경로다. 현재 `compose.prod.yaml`은 backup이
연기된 상태라 이 경로를 mount하거나 `BACKUP_STATUS_FILE`을 설정하지 않으며, 운영
승인 뒤 read-only status mount와 환경변수를 함께 추가해야 한다.

복원 시험은 새로 만든 빈 격리 DB만 허용한다. 기존 relation이 하나라도 있으면
중단하며 `clean`, `DROP DATABASE`, 덮어쓰기를 수행하지 않는다.

```bash
RESTORE_DATABASE_URL='postgresql://...?sslmode=require' \
RESTORE_DUMP_FILE='/approved/off-host/path/attend-....dump' \
BACKUP_STATUS_FILE='/var/lib/attend/backup-status/status.properties' \
./ops/backup/restore-verify.sh
```

같은 `BACKUP_STATUS_FILE`을 지정하면 성공한 복원 시험 시각도 상태 파일에
원자적으로 반영된다. 백업 작업과 복원 시험은 같은 상태 파일을 동시에 갱신하지 않도록
서로 다른 운영 시간대에 실행한다. 스크립트도 `${BACKUP_STATUS_FILE}.lock` advisory
lock으로 전체 작업을 직렬화해 최근 성공·실패·복원 시각의 lost update와 겹친 작업의
누락을 막는다. 상태 파일을 사용하지 않는 백업도 output directory의
`.attend-backup.lock`으로 직렬화하며, 초 단위 실행이 겹쳐도 임의 suffix가 붙은 서로
다른 dump를 만든다. 운영 Linux는 `flock`, 개발 macOS는 `lockf`가 필요하다. 잠금은
process 종료·강제 종료·host 재부팅 시 OS가 자동 회수하므로 lock 파일 자체가 남아
있어도 다음 작업을 막지 않는다. 설정한 상태 파일의 계약이 손상된 경우 backup은
기존 메타데이터를 승계하지 않고 이번 결과로 교체하며, restore 시험은 DB 연결 전에
중단한다.

기본 checksum 경로는 `${RESTORE_DUMP_FILE}.sha256`이다. 다른 위치라면
`RESTORE_CHECKSUM_FILE`을 지정한다. checksum 파일이 없거나 SHA-256이 일치하지
않으면 DB 연결과 복원 전에 중단한다.

실제 교사 데이터 백업은 교회가 보유기간, 암호화된 저장 위치, 접근 담당자와 삭제
절차를 승인한 뒤에만 시작한다. 성공 출력의 파일명·SHA-256·시각과 복원 시험 결과를
운영 기록에 남기되 connection URL과 개인정보는 기록하지 않는다.

원칙은 **“백업하고 삭제”가 아니라 “삭제하되, 제한된 수명의 운영 백업에는 일시적으로
남을 수 있음”**이다. 따라서 backup이 도입되더라도 만료된 `audit_log`를 별도 장기
archive로 추출하지 않으며, backup 자체의 유한 보유기간과 삭제 절차를 별도로 집행한다.
복원본을 실제 서비스에 재투입하기 전에는 `retention_worker`의 fixed-cutoff one-shot을
먼저 실행해 2년을 초과한 audit 행을 정리한다. 현재 backup은 아직 구성되지 않았고,
audit retention worker와 backup lifecycle은 서로 다른 운영 경계다.
