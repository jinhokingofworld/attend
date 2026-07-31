#!/usr/bin/env bash
# 로컬 Compose fixture의 두 부서를 실제 HTTP로 검증한다.
# 고정 key는 loopback 전용 합성 데이터이며 운영 환경에서 절대 사용하지 않는다.

set -euo pipefail
umask 077

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
temporary_dir="$(mktemp -d)"
cleanup() {
  rm -rf "${temporary_dir}"
}
trap cleanup EXIT

base_url="${LOCAL_DEMO_BASE_URL:-http://127.0.0.1:8080}"
department_a_env="${temporary_dir}/department-a.env"
department_b_env="${temporary_dir}/department-b.env"

printf '%s\n' \
  "DEVICE_BASE_URL=${base_url}" \
  'DEVICE_CODE=local-device-a' \
  'DEVICE_KEY=local-demo-device-key-not-for-production' \
  'DEVICE_UID=04A1B2C3' \
  'EXPECTED_FIRST_STATUS=201' \
  'EXPECTED_FIRST_CODE=CHECKED_IN' >"${department_a_env}"

printf '%s\n' \
  "DEVICE_BASE_URL=${base_url}" \
  'DEVICE_CODE=local-device-b' \
  'DEVICE_KEY=local-demo-device-key-b-not-for-production' \
  'DEVICE_UID=04D4E5F6' \
  'EXPECTED_FIRST_STATUS=201' \
  'EXPECTED_FIRST_CODE=CHECKED_IN' >"${department_b_env}"

PILOT_DEPARTMENT_A_ENV="${department_a_env}" \
PILOT_DEPARTMENT_B_ENV="${department_b_env}" \
  "${script_dir}/pilot-http-simulator.sh"
