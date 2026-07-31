# 백업·복원 시험

두 스크립트는 운영 웹의 pooled URL이 아니라 Neon **direct** PostgreSQL URL을
사용한다. URL은 `postgresql://...` 형식으로 환경변수에만 주입하고 명령행 인자로
넘기지 않는다. 실행 로그에도 URL이나 비밀번호를 남기지 않는다.

```bash
BACKUP_DATABASE_URL='postgresql://...' \
BACKUP_OUTPUT_DIR='/approved/off-host/path' \
./ops/backup/backup.sh
```

복원 시험은 새로 만든 빈 격리 DB만 허용한다. 기존 relation이 하나라도 있으면
중단하며 `clean`, `DROP DATABASE`, 덮어쓰기를 수행하지 않는다.

```bash
RESTORE_DATABASE_URL='postgresql://...' \
RESTORE_DUMP_FILE='/approved/off-host/path/attend-....dump' \
./ops/backup/restore-verify.sh
```

실제 교사 데이터 백업은 교회가 보유기간, 암호화된 저장 위치, 접근 담당자와 삭제
절차를 승인한 뒤에만 시작한다. 성공 출력의 파일명·SHA-256·시각과 복원 시험 결과를
운영 기록에 남기되 connection URL과 개인정보는 기록하지 않는다.
