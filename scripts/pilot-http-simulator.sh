#!/usr/bin/env bash
# Run idempotency and department-isolation contracts for two prepared departments
# before hardware arrives. This does not replace the M6 physical-device pilot.

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
  local payload conflict_payload retag_payload
  local status replay_status conflict_status retag_status
  payload="$(jq -cn --arg uid "${DEVICE_UID}" --arg id "${request_id}" \
    '{uid:$uid,requestId:$id}')"
  conflict_payload="$(jq -cn --arg id "${request_id}" \
    '{uid:"04FFFFFF",requestId:$id}')"
  retag_payload="$(jq -cn --arg uid "${DEVICE_UID}" \
    --arg id "${request_id}_retag" '{uid:$uid,requestId:$id}')"

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

  retag_status="$(curl --silent --show-error --connect-timeout 3 --max-time 10 \
    --output "${replay_file}" --write-out '%{http_code}' --request POST \
    --header "@${auth_file}" --header 'Content-Type: application/json' \
    --data-binary "${retag_payload}" "${DEVICE_BASE_URL}/api/v1/device/check-ins")"
  if [[ "${retag_status}" != "200" \
        || "$(jq -r '.code // empty' "${replay_file}")" != "ALREADY_CHECKED_IN" ]]; then
    printf '[%s] 새 requestId 재태깅이 최초 출석을 유지하지 못했습니다.\n' \
      "${label}" >&2
    rm -f "${response_file}" "${replay_file}" "${header_file}" "${auth_file}"
    return 1
  fi

  rm -f "${response_file}" "${replay_file}" "${header_file}" "${auth_file}"
  printf '[%s] PASS: 최초 기록, canonical replay, requestId 충돌, 재태깅\n' \
    "${label}"
}

run_isolation_checks() {
  local first_env="$1"
  local second_env="$2"
  local base_url_a code_a key_a uid_a
  local base_url_b code_b key_b uid_b
  local response_file auth_file payload request_id status

  if [[ ! -f "${first_env}" || ! -f "${second_env}" ]]; then
    printf '[isolation] 두 부서 환경파일을 모두 찾을 수 없습니다.\n' >&2
    return 1
  fi

  unset DEVICE_BASE_URL DEVICE_CODE DEVICE_KEY DEVICE_UID
  # shellcheck disable=SC1090
  source "${first_env}"
  : "${DEVICE_BASE_URL:?DEVICE_BASE_URL is required}"
  : "${DEVICE_CODE:?DEVICE_CODE is required}"
  : "${DEVICE_KEY:?DEVICE_KEY is required}"
  : "${DEVICE_UID:?DEVICE_UID is required}"
  base_url_a="${DEVICE_BASE_URL}"
  code_a="${DEVICE_CODE}"
  key_a="${DEVICE_KEY}"
  uid_a="${DEVICE_UID}"

  unset DEVICE_BASE_URL DEVICE_CODE DEVICE_KEY DEVICE_UID
  # shellcheck disable=SC1090
  source "${second_env}"
  : "${DEVICE_BASE_URL:?DEVICE_BASE_URL is required}"
  : "${DEVICE_CODE:?DEVICE_CODE is required}"
  : "${DEVICE_KEY:?DEVICE_KEY is required}"
  : "${DEVICE_UID:?DEVICE_UID is required}"
  base_url_b="${DEVICE_BASE_URL}"
  code_b="${DEVICE_CODE}"
  key_b="${DEVICE_KEY}"
  uid_b="${DEVICE_UID}"

  if [[ "${base_url_a}" != "${base_url_b}" ]]; then
    printf '[isolation] 두 부서가 같은 API 서버를 사용해야 합니다.\n' >&2
    return 1
  fi
  if [[ "${code_a}" == "${code_b}" || "${key_a}" == "${key_b}" \
        || "${uid_a}" == "${uid_b}" ]]; then
    printf '[isolation] 장치 code, key와 카드 UID는 부서마다 달라야 합니다.\n' >&2
    return 1
  fi

  response_file="$(mktemp)"
  auth_file="$(mktemp)"
  request_id="mixed_auth_$(date +%s)_${RANDOM}"
  payload="$(jq -cn --arg uid "${uid_a}" --arg id "${request_id}" \
    '{uid:$uid,requestId:$id}')"
  printf 'X-Device-Code: %s\nX-Device-Key: %s\n' \
    "${code_a}" "${key_b}" >"${auth_file}"

  status="$(curl --silent --show-error --connect-timeout 3 --max-time 10 \
    --output "${response_file}" --write-out '%{http_code}' --request POST \
    --header "@${auth_file}" --header 'Content-Type: application/json' \
    --data-binary "${payload}" "${base_url_a}/api/v1/device/check-ins")"
  if [[ "${status}" != "401" ]] || ! jq -e '
      .success == false
      and .code == "DEVICE_UNAUTHORIZED"
      and .message == "장치 인증에 실패했습니다."
      and .requestId == null
      and .data == null
      and (keys == ["code", "data", "message", "requestId", "serverTime", "success"])
    ' "${response_file}" >/dev/null; then
    printf '[isolation] A 장치 code와 B 장치 key 조합이 동일한 401로 거부되지 않았습니다.\n' \
      >&2
    rm -f "${response_file}" "${auth_file}"
    return 1
  fi

  request_id="cross_department_$(date +%s)_${RANDOM}"
  payload="$(jq -cn --arg uid "${uid_b}" --arg id "${request_id}" \
    '{uid:$uid,requestId:$id}')"
  printf 'X-Device-Code: %s\nX-Device-Key: %s\n' \
    "${code_a}" "${key_a}" >"${auth_file}"
  status="$(curl --silent --show-error --connect-timeout 3 --max-time 10 \
    --output "${response_file}" --write-out '%{http_code}' --request POST \
    --header "@${auth_file}" --header 'Content-Type: application/json' \
    --data-binary "${payload}" "${base_url_a}/api/v1/device/check-ins")"
  if [[ "${status}" != "409" ]] || ! jq -e --arg id "${request_id}" '
      .success == false
      and .code == "NOT_DEPARTMENT_MEMBER"
      and .message == "이 부서에 유효한 소속이 없습니다."
      and .requestId == $id
      and .data == null
      and (keys == ["code", "data", "message", "requestId", "serverTime", "success"])
    ' "${response_file}" >/dev/null; then
    printf '[isolation] A 부서 장치의 B 부서 카드 요청이 제한 응답으로 끝나지 않았습니다.\n' \
      >&2
    rm -f "${response_file}" "${auth_file}"
    return 1
  fi

  rm -f "${response_file}" "${auth_file}"
  printf '[isolation] PASS: code/key 교차 인증 401, 다른 부서 카드 정보 비노출\n'
}

run_department "department-a" "${department_a_env}"
run_department "department-b" "${department_b_env}"
run_isolation_checks "${department_a_env}" "${department_b_env}"
printf '2개 부서 HTTP simulator 멱등성·격리 계약 통과\n'
