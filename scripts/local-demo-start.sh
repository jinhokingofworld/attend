#!/usr/bin/env bash
# 로컬 앱과 DB를 기동한 뒤 날짜별 fixture 완료까지 동기적으로 기다린다.

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repository_dir="$(cd -- "${script_dir}/.." && pwd)"
compose_file="${repository_dir}/compose.local.yaml"
build_option="--build"
if [[ "${LOCAL_DEMO_BUILD:-true}" == "false" ]]; then
  build_option="--no-build"
fi

docker compose --env-file /dev/null -f "${compose_file}" \
  up "${build_option}" --detach --wait db app
docker compose --env-file /dev/null -f "${compose_file}" run --rm seed

printf '%s\n' \
  'Attend local demo is ready.' \
  'Admin: http://127.0.0.1:8080/' \
  'Health: http://127.0.0.1:8081/actuator/health'
