#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BACKEND="${ROOT}/backend"
CLASSPATH_FILE="$(mktemp "${TMPDIR:-/tmp}/class-schedule-classpath.XXXXXX")"
trap 'rm -f "${CLASSPATH_FILE}"' EXIT

cd "${BACKEND}"
mvn -q -DskipTests compile
mvn -q -DincludeScope=runtime -Dmdep.outputFile="${CLASSPATH_FILE}" dependency:build-classpath
java -cp "target/classes:$(<"${CLASSPATH_FILE}")" \
  com.classschedule.importexport.ExternalTimetableConverter "$@"
