#!/usr/bin/env bash
#
# Attend M4 HTTP 장치 시험 도구.
# 장치 키는 process argument에 넣지 않고 Git에서 제외한 환경파일이나 숨김 입력으로만
# 받는다. 실제 Arduino 펌웨어를 대신하지 않으며 로컬·승인된 시험 서버에만 사용한다.

set -euo pipefail
umask 077

smoke_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
workspace_dir="$(cd -- "${smoke_dir}/.." && pwd)"
secret_file="${DEVICE_SMOKE_ENV_FILE:-${workspace_dir}/.device-smoke.env}"

if [[ -f "${secret_file}" ]]; then
  # shellcheck disable=SC1090
  source "${secret_file}"
fi

base_url="${DEVICE_BASE_URL:-http://localhost:8080}"
device_code="${DEVICE_CODE:-}"
device_key="${DEVICE_KEY:-}"
uid="${DEVICE_UID:-04A1B2C3}"
request_id="${DEVICE_REQUEST_ID:-smoke_$(date +%s)_${RANDOM}}"

if [[ -z "${device_code}" ]]; then
  read -r -p "장치 코드: " device_code
fi
if [[ -z "${device_key}" ]]; then
  read -r -s -p "장치 비밀키(화면에 표시되지 않음): " device_key
  printf '\n'
fi

if [[ -z "${device_code}" || -z "${device_key}" ]]; then
  printf '장치 코드와 비밀키가 모두 필요합니다.\n' >&2
  exit 2
fi

response_file="$(mktemp)"
header_file="$(mktemp)"
auth_file="$(mktemp)"
cleanup() {
  rm -f "${response_file}" "${header_file}" "${auth_file}"
}
trap cleanup EXIT

post() {
  local label="$1"
  local path="$2"
  local body="${3-}"
  local key="${4-${device_key}}"
  local status

  : >"${response_file}"
  : >"${header_file}"
  printf 'X-Device-Code: %s\nX-Device-Key: %s\n' \
    "${device_code}" "${key}" >"${auth_file}"
  if [[ -n "${body}" ]]; then
    status="$(curl --silent --show-error \
      --output "${response_file}" \
      --dump-header "${header_file}" \
      --write-out '%{http_code}' \
      --request POST \
      --header "@${auth_file}" \
      --header 'Content-Type: application/json; charset=UTF-8' \
      --data-binary "${body}" \
      "${base_url}${path}")"
  else
    status="$(curl --silent --show-error \
      --output "${response_file}" \
      --dump-header "${header_file}" \
      --write-out '%{http_code}' \
      --request POST \
      --header "@${auth_file}" \
      "${base_url}${path}")"
  fi

  printf '\n[%s] HTTP %s\n' "${label}" "${status}"
  grep -i '^Retry-After:' "${header_file}" || true
  tr -d '\r' <"${response_file}"
  printf '\n'
}

payload="$(printf '{"uid":"%s","requestId":"%s"}' "${uid}" "${request_id}")"
conflict_payload="$(printf '{"uid":"04FFFFFF","requestId":"%s"}' "${request_id}")"

post "credential test" "/api/v1/device/credential-tests"
post "new check-in" "/api/v1/device/check-ins" "${payload}"
post "same request replay" "/api/v1/device/check-ins" "${payload}"
post "same requestId with different UID" \
  "/api/v1/device/check-ins" "${conflict_payload}"
post "authentication failure" \
  "/api/v1/device/check-ins" "${payload}" "invalid-device-key"
oversized_payload="$(printf 'x%.0s' {1..1025})"
post "1025-byte body" "/api/v1/device/check-ins" "${oversized_payload}"

printf '\n[rate-limit burst] 아래 요청 중 하나는 HTTP 429와 Retry-After를 반환해야 합니다.\n'
for sequence in {1..11}; do
  burst_id="${request_id}_rate_${sequence}"
  burst_payload="$(printf '{"uid":"%s","requestId":"%s"}' "${uid}" "${burst_id}")"
  post "rate ${sequence}/11" "/api/v1/device/check-ins" "${burst_payload}"
done

printf '\n이 도구는 장치 활성화 상태를 변경하지 않습니다.\n'
printf 'credential test 뒤 관리 화면에서 활성화하고 check-in 결과를 다시 확인하세요.\n'
