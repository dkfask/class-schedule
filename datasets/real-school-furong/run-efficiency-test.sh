#!/usr/bin/env bash
# End-to-end scheduling efficiency probe for the real Furong-campus dataset.
#
# Runs the real product path: login -> import preview -> import confirm ->
# solve readiness -> enqueue solve job -> poll until the worker finishes.
# Requires the docker compose stack (api + worker + postgres) to be running.
#
# Usage: bash datasets/real-school-furong/run-efficiency-test.sh [term-code]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TERM_CODE="${1:-2026-FALL-FR}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
POLL_INTERVAL="${POLL_INTERVAL:-2}"
USERNAME="${APP_AUTH_BOOTSTRAP_USERNAME:-planner}"
PASSWORD="${APP_AUTH_BOOTSTRAP_PASSWORD:-change-this-in-local-env}"
WORKBOOK="${ROOT}/datasets/real-school-furong/MASTER_DATA-v1.xlsx"
COOKIE_JAR="$(mktemp "${TMPDIR:-/tmp}/furong-cookies.XXXXXX")"
trap 'rm -f "${COOKIE_JAR}"' EXIT

json_field() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

echo "== 1/6 login as ${USERNAME}"
CSRF="$(curl -s -c "${COOKIE_JAR}" "${BASE_URL}/api/auth/csrf")"
CSRF_HEADER="$(printf '%s' "${CSRF}" | json_field "['headerName']")"
CSRF_TOKEN="$(printf '%s' "${CSRF}" | json_field "['token']")"
curl -s -b "${COOKIE_JAR}" -c "${COOKIE_JAR}" \
  -H 'Content-Type: application/json' \
  -H "${CSRF_HEADER}: ${CSRF_TOKEN}" \
  -d "{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}" \
  "${BASE_URL}/api/auth/login" | json_field "['username']" >/dev/null
echo "   session established"

CSRF="$(curl -s -b "${COOKIE_JAR}" -c "${COOKIE_JAR}" "${BASE_URL}/api/auth/csrf")"
CSRF_HEADER="$(printf '%s' "${CSRF}" | json_field "['headerName']")"
CSRF_TOKEN="$(printf '%s' "${CSRF}" | json_field "['token']")"

echo "== 2/6 import preview (${WORKBOOK##*/})"
PREVIEW="$(curl -s -b "${COOKIE_JAR}" -c "${COOKIE_JAR}" \
  -H "${CSRF_HEADER}: ${CSRF_TOKEN}" \
  -F "file=@${WORKBOOK}" \
  "${BASE_URL}/api/imports/preview?termCode=${TERM_CODE}")"
printf '%s' "${PREVIEW}" > "${ROOT}/datasets/real-school-furong/import-preview.json"
BATCH_ID="$(printf '%s' "${PREVIEW}" | json_field "['batchId']")"
STATUS="$(printf '%s' "${PREVIEW}" | json_field "['status']")"
echo "   batchId=${BATCH_ID} status=${STATUS}"
if [ "${STATUS}" != "VALIDATED" ]; then
  printf '%s' "${PREVIEW}" | python3 -m json.tool | head -40
  exit 1
fi

echo "== 3/6 import confirm"
CONFIRM="$(curl -s -b "${COOKIE_JAR}" -c "${COOKIE_JAR}" \
  -H 'Content-Type: application/json' \
  -H "${CSRF_HEADER}: ${CSRF_TOKEN}" \
  -d "{\"batchId\":${BATCH_ID}}" \
  "${BASE_URL}/api/imports/confirm")"
printf '%s' "${CONFIRM}" | json_field "['status']"
echo "   importedRows=$(printf '%s' "${CONFIRM}" | json_field "['importedRows']")"

echo "== 4/6 solve readiness"
curl -s -b "${COOKIE_JAR}" "${BASE_URL}/api/solve-readiness?termCode=${TERM_CODE}" | python3 -m json.tool

echo "== 5/6 enqueue solve job"
SUBMIT="$(curl -s -b "${COOKIE_JAR}" -c "${COOKIE_JAR}" \
  -H 'Content-Type: application/json' \
  -H "${CSRF_HEADER}: ${CSRF_TOKEN}" \
  -d "{\"termCode\":\"${TERM_CODE}\",\"idempotencyKey\":\"furong-$(date +%s)\"}" \
  "${BASE_URL}/api/solve-jobs")"
JOB_ID="$(printf '%s' "${SUBMIT}" | json_field "['jobId']")"
VERSION_ID="$(printf '%s' "${SUBMIT}" | json_field "['versionId']")"
echo "   jobId=${JOB_ID} versionId=${VERSION_ID}"
START_EPOCH="$(python3 -c 'import time;print(time.time())')"

echo "== 6/6 poll solve job"
while true; do
  sleep "${POLL_INTERVAL}"
  DETAILS="$(curl -s -b "${COOKIE_JAR}" "${BASE_URL}/api/solve-jobs/${JOB_ID}")"
  JOB_STATUS="$(printf '%s' "${DETAILS}" | json_field "['jobStatus']")"
  ELAPSED="$(python3 -c "import time;print(round(time.time()-${START_EPOCH},1))")"
  echo "   t+${ELAPSED}s jobStatus=${JOB_STATUS} score=$(printf '%s' "${DETAILS}" | json_field "['score']")"
  case "${JOB_STATUS}" in
    COMPLETED|FAILED|CANCELLED)
      printf '%s' "${DETAILS}" > "${ROOT}/datasets/real-school-furong/last-solve-job.json"
      echo
      echo "final elapsed: ${ELAPSED}s"
      printf '%s' "${DETAILS}" | python3 -m json.tool
      exit 0
      ;;
  esac
  if python3 -c "import sys;sys.exit(0 if ${ELAPSED} > 900 else 1)"; then
    echo "timed out waiting for solve job" >&2
    exit 1
  fi
done
