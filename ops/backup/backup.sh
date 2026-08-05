#!/usr/bin/env bash
# Create an encrypted-transport PostgreSQL custom-format backup.
# The connection URL is read from the environment and is never placed in argv.

set -euo pipefail
umask 077

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/backup/status-contract.sh
source "${script_directory}/status-contract.sh"

backup_status_initialize || exit 2
backup_status_enter_lock "${script_directory}/backup.sh" "$@" || exit 2
backup_storage_type="${BACKUP_STORAGE_TYPE:-}"

previous_last_success_at=""
previous_storage_type=""
previous_last_restore_test_at=""
if backup_status_read_existing; then
  previous_last_success_at="${backup_status_existing_last_success_at}"
  previous_storage_type="${backup_status_existing_storage_type}"
  previous_last_restore_test_at="${backup_status_existing_last_restore_test_at}"
fi

record_failed_backup_status() {
  local exit_code=$?
  local observed_at
  trap - EXIT
  if [[ "${exit_code}" -ne 0 \
      && "${backup_status_configured}" == true ]]; then
    observed_at="$(backup_status_now)"
    if ! backup_status_write \
        FAILURE \
        "${observed_at}" \
        "${previous_last_success_at}" \
        "${previous_storage_type}" \
        "${previous_last_restore_test_at}"; then
      printf '백업 실패 상태 파일을 기록하지 못했습니다.\n' >&2
    fi
  fi
  exit "${exit_code}"
}
trap record_failed_backup_status EXIT

if [[ "${backup_status_configured}" == true ]] \
    && ! backup_status_storage_type_is_allowed "${backup_storage_type}"; then
  printf '%s\n' \
    'BACKUP_STORAGE_TYPE은 승인된 저장소 유형이어야 합니다.' >&2
  exit 2
fi

required_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    printf '필수 명령을 찾을 수 없습니다: %s\n' "$1" >&2
    exit 2
  fi
}

required_command pg_dump
if ! command -v sha256sum >/dev/null 2>&1 \
    && ! command -v shasum >/dev/null 2>&1; then
  printf 'SHA-256 명령(sha256sum 또는 shasum)을 찾을 수 없습니다.\n' >&2
  exit 2
fi

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
  (
    cd "${backup_output_dir}"
    sha256sum "$(basename "${final_dump}")"
  ) >"${checksum_file}"
else
  (
    cd "${backup_output_dir}"
    shasum -a 256 "$(basename "${final_dump}")"
  ) >"${checksum_file}"
fi

completed_at_iso="$(backup_status_now)"
backup_status_write \
  SUCCESS \
  "${completed_at_iso}" \
  "${completed_at_iso}" \
  "${backup_storage_type}" \
  "${previous_last_restore_test_at}"
trap - EXIT

printf 'backup_file=%s\nchecksum_file=%s\ncompleted_at=%s\n' \
  "${final_dump}" "${checksum_file}" "${timestamp}"
