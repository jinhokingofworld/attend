#!/bin/sh
# The retention image owns only this loop and a retention_worker credential.
# A failed one-shot exits so Docker's restart policy makes the failure visible
# and retries it; successful runs wait for the next minute. Each one-shot is
# transaction-bounded, so minute-level retries provide controlled catch-up.
set -eu

run_interval_seconds="${RETENTION_RUN_INTERVAL_SECONDS:-60}"
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

while :; do
  java -jar /app/retention.jar
  sleep "${run_interval_seconds}"
done
