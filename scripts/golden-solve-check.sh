#!/usr/bin/env bash
# Golden-dataset solve acceptance: import -> confirm -> solve -> poll.
# Requires the compose stack (api + worker + postgres) and the golden terms to exist.
set -euo pipefail

J=$(mktemp); trap 'rm -f "$J"' EXIT
CS=$(curl -s -c "$J" http://localhost:8080/api/auth/csrf)
HN=$(echo "$CS" | python3 -c "import json,sys;print(json.load(sys.stdin)['headerName'])")
TK=$(echo "$CS" | python3 -c "import json,sys;print(json.load(sys.stdin)['token'])")
curl -s -b "$J" -c "$J" -H "Content-Type: application/json" -H "$HN: $TK" \
  -d "{\"username\":\"planner\",\"password\":\"$APP_AUTH_BOOTSTRAP_PASSWORD\"}" \
  http://localhost:8080/api/auth/login > /dev/null

run() {
  DS=$1; TERM=$2
  PREVIEW=$(curl -s -b "$J" -H "$HN: $TK" -F "file=@datasets/$DS/MASTER_DATA-v1.xlsx" \
    "http://localhost:8080/api/imports/preview?termCode=$TERM")
  BATCH=$(echo "$PREVIEW" | python3 -c "import json,sys;print(json.load(sys.stdin)['batchId'])")
  curl -s -b "$J" -H "Content-Type: application/json" -H "$HN: $TK" \
    -d "{\"batchId\":$BATCH}" http://localhost:8080/api/imports/confirm > /dev/null
  SUBMIT=$(curl -s -b "$J" -H "Content-Type: application/json" -H "$HN: $TK" \
    -d "{\"termCode\":\"$TERM\",\"idempotencyKey\":\"gold-$DS-$(date +%s)\"}" \
    http://localhost:8080/api/solve-jobs)
  JOB=$(echo "$SUBMIT" | python3 -c "import json,sys;print(json.load(sys.stdin)['jobId'])")
  START=$(python3 -c "import time;print(time.time())")
  while :; do
    sleep 3
    D=$(curl -s -b "$J" "http://localhost:8080/api/solve-jobs/$JOB")
    ST=$(echo "$D" | python3 -c "import json,sys;print(json.load(sys.stdin)['jobStatus'])")
    EL=$(python3 -c "import time;print(round(time.time()-$START,1))")
    case "$ST" in
      COMPLETED|FAILED|CANCELLED)
        echo "$DS: $ST t=${EL}s $(echo "$D" | python3 -c "import json,sys;d=json.load(sys.stdin);print('score',d.get('score'),'version',d.get('versionId'))")"
        break;;
    esac
    if python3 -c "import sys;sys.exit(0 if $EL > 240 else 1)"; then echo "$DS: TIMEOUT 240s"; break; fi
  done
}

run small-feasible GOLD-SMALL-FEASIBLE
run small-infeasible GOLD-SMALL-INFEASIBLE
run over-constrained GOLD-OVER-CONSTRAINED
run k12-sample GOLD-K12-SAMPLE
