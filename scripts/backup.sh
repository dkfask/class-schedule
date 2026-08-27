#!/usr/bin/env bash
set -euo pipefail

# 排课系统 PostgreSQL 备份脚本（pg_dump）
# 用法：BACKUP_DIR=/path ./scripts/backup.sh
# 环境变量：POSTGRES_USER（默认 class_schedule）、POSTGRES_DB（默认 class_schedule）、BACKUP_DIR（默认 ./backups）

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
POSTGRES_USER="${POSTGRES_USER:-class_schedule}"
POSTGRES_DB="${POSTGRES_DB:-class_schedule}"
POSTGRES_SERVICE="${POSTGRES_SERVICE:-postgres}"
COMPOSE_FILE="${COMPOSE_FILE:-${ROOT}/docker-compose.yml}"
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-}"
BACKUP_DIR="${BACKUP_DIR:-${ROOT}/backups}"
STAMP="$(date +%Y%m%d-%H%M%S)"
FILE="${BACKUP_DIR}/class_schedule_${STAMP}.dump"

compose=(docker compose -f "${COMPOSE_FILE}")
if [[ -n "${COMPOSE_PROJECT_NAME}" ]]; then
  compose+=(--project-name "${COMPOSE_PROJECT_NAME}")
fi
CONTAINER="$("${compose[@]}" ps -q "${POSTGRES_SERVICE}")"
if [[ -z "${CONTAINER}" ]]; then
  echo "未找到 PostgreSQL 服务容器: ${POSTGRES_SERVICE}" >&2
  exit 1
fi

mkdir -p "${BACKUP_DIR}"
"${compose[@]}" exec -T "${POSTGRES_SERVICE}" \
  pg_dump -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -Fc -f "/var/lib/postgresql/backup-${STAMP}.dump"
docker cp "${CONTAINER}:/var/lib/postgresql/backup-${STAMP}.dump" "${FILE}"
"${compose[@]}" exec -T "${POSTGRES_SERVICE}" \
  rm -f "/var/lib/postgresql/backup-${STAMP}.dump"

printf '备份完成：%s\n' "${FILE}"
printf 'SHA-256：%s\n' "$(shasum -a 256 "${FILE}" | cut -d ' ' -f1)"
echo "保留策略：按需保留最近 N 份，建议异地加密存储。"
