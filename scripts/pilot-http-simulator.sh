#!/usr/bin/env bash
# Run the same idempotency contract for two prepared departments before hardware
# arrives. This does not replace the M6 physical-device or four-session pilot.

set -euo pipefail
umask 077

for command_name in curl jq; do
  if ! command -v "${command_name}" >/dev/null 2>&1; then
    printf '필수 명령을 찾을 수 없습니다: %s\n' "${command_name}" >&2
    exit 2
  fi
done

department_a_env="${PILOT_DEPARTMENT_A_ENV:-}"
department_b_env="${PILOT_DEPARTMENT_B_ENV:-}"
if [[ -z "${department_a_env}" || -z "${department_b_env}" ]]; then
  printf 'PILOT_DEPARTMENT_A_ENV와 PILOT_DEPARTMENT_B_ENV가 필요합니다.\n' >&2
  exit 2
fi
if [[ "${department_a_env}" == "${department_b_env}" ]]; then
  printf '두 부서는 서로 다른 환경파일을 사용해야 합니다.\n' >&2
  exit 2
fi

run_department() {
  local label="$1"
  local env_file="$2"
  local response_file header_file auth_file replay_file
  if [[ ! -f "${env_file}" ]]; then
    printf '[%s] 환경파일을 찾을 수 없습니다.\n' "${label}" >&2
    return 1
  fi

  unset DEVICE_BASE_URL DEVICE_CODE DEVICE_KEY DEVICE_UID EXPECTED_FIRST_STATUS EXPECTED_FIRST_CODE
  # shellcheck disable=SC1090
  source "${env_file}"
  : "${DEVICE_BASE_URL:?DEVICE_BASE_URL is required}"
  : "${DEVICE_CODE:?DEVICE_CODE is required}"
  : "${DEVICE_KEY:?DEVICE_KEY is required}"
  : "${DEVICE_UID:?DEVICE_UID is required}"
  local expected_status="${EXPECTED_FIRST_STATUS:-201}"
  local expected_code="${EXPECTED_FIRST_CODE:-CHECKED_IN}"
  local request_id
  request_id="pilot_$(date +%s)_${RANDOM}_${label}"
  local payload conflict_payload status replay_status conflict_status
  payload="$(jq -cn --arg uid "${DEVICE_UID}" --arg id "${request_id}" \
    '{uid:$uid,requestId:$id}')"
  conflict_payload="$(jq -cn --arg id "${request_id}" \
    '{uid:"04FFFFFF",requestId:$id}')"

  response_file="$(mktemp)"
  replay_file="$(mktemp)"
  header_file="$(mktemp)"
  auth_file="$(mktemp)"
  printf 'X-Device-Code: %s\nX-Device-Key: %s\n' \
    "${DEVICE_CODE}" "${DEVICE_KEY}" >"${auth_file}"

  status="$(curl --silent --show-error --connect-timeout 3 --max-time 10 \
    --output "${response_file}" --dump-header "${header_file}" --write-out '%{http_code}' \
    --request POST --header "@${auth_file}" --header 'Content-Type: application/json' \
    --data-binary "${payload}" "${DEVICE_BASE_URL}/api/v1/device/check-ins")"
  if [[ "${status}" != "${expected_status}" \
        || "$(jq -r '.code // empty' "${response_file}")" != "${expected_code}" ]]; then
    printf '[%s] 최초 check-in 불일치: HTTP %s, code %s\n' "${label}" "${status}" \
      "$(jq -r '.code // "INVALID_JSON"' "${response_file}" 2>/dev/null || printf INVALID_JSON)" >&2
    rm -f "${response_file}" "${replay_file}" "${header_file}" "${auth_file}"
    return 1
  fi

  replay_status="$(curl --silent --show-error --connect-timeout 3 --max-time 10 \
    --output "${replay_file}" --write-out '%{http_code}' --request POST \
    --header "@${auth_file}" --header 'Content-Type: application/json' \
    --data-binary "${payload}" "${DEVICE_BASE_URL}/api/v1/device/check-ins")"
  if [[ "${replay_status}" != "${status}" ]] || ! cmp -s "${response_file}" "${replay_file}"; then
    printf '[%s] 동일 requestId canonical replay가 일치하지 않습니다.\n' "${label}" >&2
    rm -f "${response_file}" "${replay_file}" "${header_file}" "${auth_file}"
    return 1
  fi

  conflict_status="$(curl --silent --show-error --connect-timeout 3 --max-time 10 \
    --output "${replay_file}" --write-out '%{http_code}' --request POST \
    --header "@${auth_file}" --header 'Content-Type: application/json' \
    --data-binary "${conflict_payload}" "${DEVICE_BASE_URL}/api/v1/device/check-ins")"
  if [[ "${conflict_status}" != "409" \
        || "$(jq -r '.code // empty' "${replay_file}")" != "REQUEST_ID_CONFLICT" ]]; then
    printf '[%s] requestId 충돌 계약이 일치하지 않습니다.\n' "${label}" >&2
    rm -f "${response_file}" "${replay_file}" "${header_file}" "${auth_file}"
    return 1
  fi

  rm -f "${response_file}" "${replay_file}" "${header_file}" "${auth_file}"
  printf '[%s] PASS: 최초 기록, canonical replay, requestId 충돌\n' "${label}"
}

run_department "department-a" "${department_a_env}"
run_department "department-b" "${department_b_env}"
printf '2개 부서 HTTP simulator 계약 통과\n'
