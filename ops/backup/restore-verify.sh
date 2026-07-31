#!/usr/bin/env bash
# Restore one dump into an operator-provided empty isolation database and verify
# the minimum Attend schema. This script never drops or cleans a database.

set -euo pipefail
umask 077

for command_name in pg_restore psql; do
  if ! command -v "${command_name}" >/dev/null 2>&1; then
    printf '필수 명령을 찾을 수 없습니다: %s\n' "${command_name}" >&2
    exit 2
  fi
done

restore_database_url="${RESTORE_DATABASE_URL:-}"
dump_file="${RESTORE_DUMP_FILE:-}"
if [[ -z "${restore_database_url}" || -z "${dump_file}" ]]; then
  printf 'RESTORE_DATABASE_URL과 RESTORE_DUMP_FILE이 필요합니다.\n' >&2
  exit 2
fi
if [[ ! -f "${dump_file}" ]]; then
  printf '복원 파일을 찾을 수 없습니다: %s\n' "${dump_file}" >&2
  exit 2
fi

export PGDATABASE="${restore_database_url}"
relation_count="$(psql --no-psqlrc --tuples-only --quiet --set ON_ERROR_STOP=1 \
  --command "SELECT count(*) FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND c.relkind IN ('r','p','v','m','S');")"
relation_count="${relation_count//[[:space:]]/}"
if [[ "${relation_count}" != "0" ]]; then
  printf '복원 대상은 빈 격리 DB여야 합니다. 현재 사용자 relation 수: %s\n' \
    "${relation_count}" >&2
  exit 3
fi

pg_restore --exit-on-error --no-owner --no-acl --dbname="" \
  "${dump_file}"

verification="$(psql --no-psqlrc --tuples-only --quiet --set ON_ERROR_STOP=1 <<'SQL'
WITH required(name) AS (
    VALUES
      ('department'), ('member'), ('account'), ('device'),
      ('attendance_policy_version'), ('attendance_band'),
      ('attendance_day'), ('attendance_target'), ('attendance_record'),
      ('nfc_card'), ('nfc_card_assignment'), ('tag_event_log'), ('audit_log'),
      ('flyway_schema_history')
), missing AS (
    SELECT name FROM required
    WHERE pg_catalog.to_regclass('public.' || name) IS NULL
)
SELECT CASE WHEN EXISTS (SELECT 1 FROM missing)
            THEN 'FAIL:' || (SELECT pg_catalog.string_agg(name, ',') FROM missing)
            ELSE 'OK' END;
SQL
)"
verification="${verification//[[:space:]]/}"
if [[ "${verification}" != "OK" ]]; then
  printf '복원 스키마 검증 실패: %s\n' "${verification}" >&2
  exit 4
fi

printf 'restore_verification=OK\ndump_file=%s\n' "${dump_file}"
