#!/usr/bin/env bash
# Create an encrypted-transport PostgreSQL custom-format backup.
# The connection URL is read from the environment and is never placed in argv.

set -euo pipefail
umask 077

required_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    printf '필수 명령을 찾을 수 없습니다: %s\n' "$1" >&2
    exit 2
  fi
}

required_command pg_dump

backup_database_url="${BACKUP_DATABASE_URL:-}"
backup_output_dir="${BACKUP_OUTPUT_DIR:-}"
if [[ -z "${backup_database_url}" || -z "${backup_output_dir}" ]]; then
  printf 'BACKUP_DATABASE_URL과 BACKUP_OUTPUT_DIR가 필요합니다.\n' >&2
  exit 2
fi
if [[ "${backup_output_dir}" != /* || "${backup_output_dir}" == "/" ]]; then
  printf 'BACKUP_OUTPUT_DIR는 루트가 아닌 절대 경로여야 합니다.\n' >&2
  exit 2
fi

install -d -m 0700 "${backup_output_dir}"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
final_dump="${backup_output_dir}/attend-${timestamp}.dump"
temporary_dump="${backup_output_dir}/.attend-${timestamp}.dump.partial"
checksum_file="${final_dump}.sha256"

# libpq accepts a connection URI through PGDATABASE. This keeps credentials out
# of the process argument list, although the operator must still protect its env.
export PGDATABASE="${backup_database_url}"
pg_dump \
  --format=custom \
  --compress=9 \
  --no-owner \
  --no-acl \
  --file="${temporary_dump}"
mv "${temporary_dump}" "${final_dump}"

if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "${final_dump}" >"${checksum_file}"
else
  shasum -a 256 "${final_dump}" >"${checksum_file}"
fi

printf 'backup_file=%s\nchecksum_file=%s\ncompleted_at=%s\n' \
  "${final_dump}" "${checksum_file}" "${timestamp}"
