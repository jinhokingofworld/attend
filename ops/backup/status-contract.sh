#!/usr/bin/env bash
# Shared writer for the non-sensitive backup status consumed by the web app.
# This file is sourced by backup.sh; it is not an operator entry point.

backup_status_file=""
backup_status_directory=""
backup_status_configured=false
backup_status_lock_file=""

backup_status_storage_type_is_allowed() {
  case "$1" in
    OFF_HOST_FILESYSTEM|OBJECT_STORAGE|MANAGED_DATABASE_BACKUP)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

backup_status_is_utc_instant() {
  [[ "$1" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]]
}

backup_status_initialize() {
  backup_status_file="${BACKUP_STATUS_FILE:-}"
  backup_status_directory=""
  backup_status_configured=false
  backup_status_lock_file=""
  if [[ -z "${backup_status_file}" ]]; then
    return 0
  fi
  if [[ "${backup_status_file}" != /* \
      || "${backup_status_file}" == "/" \
      || "${backup_status_file}" == *$'\n'* ]]; then
    printf 'BACKUP_STATUS_FILE은 안전한 절대 파일 경로여야 합니다.\n' >&2
    return 1
  fi

  backup_status_directory="${backup_status_file%/*}"
  if [[ -z "${backup_status_directory}" \
      || "${backup_status_directory}" == "/" ]]; then
    printf 'BACKUP_STATUS_FILE은 루트 바로 아래에 둘 수 없습니다.\n' >&2
    return 1
  fi
  if [[ -e "${backup_status_file}" && ! -f "${backup_status_file}" ]]; then
    printf 'BACKUP_STATUS_FILE은 일반 파일이어야 합니다.\n' >&2
    return 1
  fi

  install -d -m 0755 "${backup_status_directory}"
  backup_status_lock_file="${backup_status_file}.lock"
  backup_status_configured=true
}

# When status publishing is disabled, backups still need an advisory lock.
# The output directory is already an explicit operator-controlled boundary.
backup_status_use_output_lock() {
  local output_directory="$1"
  if [[ "${backup_status_configured}" == true ]]; then
    return 0
  fi
  if [[ "${output_directory}" != /* || "${output_directory}" == "/" ]]; then
    return 1
  fi
  backup_status_lock_file="${output_directory}/.attend-backup.lock"
}

# Accept only an option-free endpoint grammar. The URI is not passed to libpq;
# its constrained host, port and database components become separate PG*
# variables. That removes libpq option-name decoding and alias precedence from
# the trust boundary entirely.
backup_connection_requires_tls() {
  local endpoint_pattern
  local host
  local port
  local database
  endpoint_pattern='^postgres(ql)?://([A-Za-z0-9][A-Za-z0-9.-]*)(:([0-9]+))?/([A-Za-z0-9_][A-Za-z0-9_.-]*)$'
  [[ "$1" =~ ${endpoint_pattern} ]] || return 1
  host="${BASH_REMATCH[2]}"
  port="${BASH_REMATCH[4]:-5432}"
  database="${BASH_REMATCH[5]}"
  [[ "${port}" -ge 1 && "${port}" -le 65535 ]] || return 1
  backup_connection_host="${host}"
  backup_connection_port="${port}"
  backup_connection_database="${database}"
}

# The endpoint never reaches libpq as a connection string. Every connection
# field and both current/legacy TLS switches are overwritten immediately before
# invoking a PostgreSQL client.
backup_force_tls_environment() {
  local endpoint="$1"
  local username="$2"
  local password="$3"
  backup_connection_requires_tls "${endpoint}" || return 1
  unset PGSERVICE PGSERVICEFILE
  unset PGHOSTADDR
  export PGHOST="${backup_connection_host}"
  export PGPORT="${backup_connection_port}"
  export PGDATABASE="${backup_connection_database}"
  export PGUSER="${username}"
  export PGPASSWORD="${password}"
  export PGSSLMODE=require
  export PGREQUIRESSL=1
}

# Re-executes the complete job under an OS advisory lock. flock is provided by
# util-linux on the deployment host; macOS lockf keeps the same developer test
# path. Both release the lock automatically on exit, SIGKILL and host reboot.
backup_status_enter_lock() {
  local entrypoint="$1"
  shift
  if [[ -z "${backup_status_lock_file}" ]]; then
    return 0
  fi
  if [[ "${ATTEND_INTERNAL_STATUS_LOCK_FILE:-}" \
      == "${backup_status_lock_file}" ]]; then
    unset ATTEND_INTERNAL_STATUS_LOCK_FILE
    return 0
  fi
  if command -v flock >/dev/null 2>&1; then
    exec flock "${backup_status_lock_file}" \
      env ATTEND_INTERNAL_STATUS_LOCK_FILE="${backup_status_lock_file}" \
      "${entrypoint}" "$@"
  fi
  if command -v lockf >/dev/null 2>&1; then
    exec lockf "${backup_status_lock_file}" \
      env ATTEND_INTERNAL_STATUS_LOCK_FILE="${backup_status_lock_file}" \
      "${entrypoint}" "$@"
  fi
  printf '상태 파일 직렬화에 필요한 flock 또는 lockf를 찾을 수 없습니다.\n' >&2
  return 1
}

# Loads only status files produced by this script. Unknown keys are ignored, but
# duplicate or malformed contract fields make the previous metadata unusable.
backup_status_read_existing() {
  backup_status_existing_result=""
  backup_status_existing_observed_at=""
  backup_status_existing_last_success_at=""
  backup_status_existing_storage_type=""
  backup_status_existing_last_restore_test_at=""
  local version=""
  local seen_version=false
  local seen_result=false
  local seen_observed=false
  local seen_success=false
  local seen_storage=false
  local seen_restore=false
  local line key value

  [[ "${backup_status_configured}" == true \
      && -f "${backup_status_file}" ]] || return 1
  while IFS= read -r line || [[ -n "${line}" ]]; do
    [[ "${line}" == *=* ]] || continue
    key="${line%%=*}"
    value="${line#*=}"
    case "${key}" in
      version)
        [[ "${seen_version}" == false ]] || return 1
        seen_version=true
        version="${value}"
        ;;
      result)
        [[ "${seen_result}" == false ]] || return 1
        seen_result=true
        backup_status_existing_result="${value}"
        ;;
      observed-at)
        [[ "${seen_observed}" == false ]] || return 1
        seen_observed=true
        backup_status_existing_observed_at="${value}"
        ;;
      last-success-at)
        [[ "${seen_success}" == false ]] || return 1
        seen_success=true
        backup_status_existing_last_success_at="${value}"
        ;;
      storage-type)
        [[ "${seen_storage}" == false ]] || return 1
        seen_storage=true
        backup_status_existing_storage_type="${value}"
        ;;
      last-restore-test-at)
        [[ "${seen_restore}" == false ]] || return 1
        seen_restore=true
        backup_status_existing_last_restore_test_at="${value}"
        ;;
    esac
  done <"${backup_status_file}"

  [[ "${version}" == "1" ]] || return 1
  [[ "${backup_status_existing_result}" == "SUCCESS" \
      || "${backup_status_existing_result}" == "FAILURE" ]] || return 1
  backup_status_is_utc_instant \
    "${backup_status_existing_observed_at}" || return 1
  if [[ -n "${backup_status_existing_last_success_at}" ]]; then
    backup_status_is_utc_instant \
      "${backup_status_existing_last_success_at}" || return 1
    backup_status_storage_type_is_allowed \
      "${backup_status_existing_storage_type}" || return 1
  elif [[ "${backup_status_existing_result}" == "SUCCESS" ]]; then
    return 1
  fi
  if [[ -n "${backup_status_existing_last_restore_test_at}" ]]; then
    backup_status_is_utc_instant \
      "${backup_status_existing_last_restore_test_at}" || return 1
  fi
}

backup_status_write() {
  local result="$1"
  local observed_at="$2"
  local last_success_at="$3"
  local storage_type="$4"
  local last_restore_test_at="$5"
  local temporary_status_file

  [[ "${backup_status_configured}" == true ]] || return 0
  [[ "${result}" == "SUCCESS" || "${result}" == "FAILURE" ]] || return 1
  backup_status_is_utc_instant "${observed_at}" || return 1
  if [[ -n "${last_success_at}" ]]; then
    backup_status_is_utc_instant "${last_success_at}" || return 1
    backup_status_storage_type_is_allowed "${storage_type}" || return 1
  elif [[ "${result}" == "SUCCESS" ]]; then
    return 1
  fi
  if [[ -n "${last_restore_test_at}" ]]; then
    backup_status_is_utc_instant "${last_restore_test_at}" || return 1
  fi

  temporary_status_file="$(
    mktemp "${backup_status_directory}/.backup-status.XXXXXX"
  )"
  if ! {
    printf 'version=1\n'
    printf 'result=%s\n' "${result}"
    printf 'observed-at=%s\n' "${observed_at}"
    if [[ -n "${last_success_at}" ]]; then
      printf 'last-success-at=%s\n' "${last_success_at}"
      printf 'storage-type=%s\n' "${storage_type}"
    fi
    if [[ -n "${last_restore_test_at}" ]]; then
      printf 'last-restore-test-at=%s\n' "${last_restore_test_at}"
    fi
  } >"${temporary_status_file}"; then
    rm -f -- "${temporary_status_file}"
    return 1
  fi
  chmod 0644 "${temporary_status_file}"
  if ! mv -f -- "${temporary_status_file}" "${backup_status_file}"; then
    rm -f -- "${temporary_status_file}"
    return 1
  fi
}

backup_status_now() {
  date -u +%Y-%m-%dT%H:%M:%SZ
}
