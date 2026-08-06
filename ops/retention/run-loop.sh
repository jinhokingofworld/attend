#!/bin/sh
# The retention image owns only this loop and a retention_worker credential.
# A failed one-shot exits so Docker's restart policy makes the failure visible
# and retries it. Full bounded runs are retried after a short pause until the
# CLI reports that the backlog is drained; only then is the normal interval used.
set -eu

run_interval_seconds="${RETENTION_RUN_INTERVAL_SECONDS:-60}"
catchup_interval_seconds="${RETENTION_CATCHUP_INTERVAL_SECONDS:-1}"
case "${run_interval_seconds}" in
  ''|*[!0-9]*)
    printf '%s\n' 'RETENTION_RUN_INTERVAL_SECONDS must be a positive integer' >&2
    exit 2
    ;;
esac

if [ "${run_interval_seconds}" -lt 60 ]; then
  printf '%s\n' 'RETENTION_RUN_INTERVAL_SECONDS must be at least 60 seconds' >&2
  exit 2
fi

case "${catchup_interval_seconds}" in
  ''|*[!0-9]*)
    printf '%s\n' 'RETENTION_CATCHUP_INTERVAL_SECONDS must be a positive integer' >&2
    exit 2
    ;;
esac
if [ "${catchup_interval_seconds}" -lt 1 ]; then
  printf '%s\n' 'RETENTION_CATCHUP_INTERVAL_SECONDS must be at least 1 second' >&2
  exit 2
fi

while :; do
  retention_output="$(java -jar /app/retention.jar)"
  printf '%s\n' "${retention_output}"
  case "${retention_output}" in
    *'catchup_pending=true'*)
      sleep "${catchup_interval_seconds}"
      ;;
    *'catchup_pending=false'*)
      sleep "${run_interval_seconds}"
      ;;
    *)
      printf '%s\n' 'retention worker returned an invalid status contract' >&2
      exit 1
      ;;
  esac
done
