#!/usr/bin/env bash
set -euo pipefail

# 排课系统 PostgreSQL 恢复脚本（pg_restore）
# 用法：BACKUP_FILE=/path/class_schedule_YYYYmmdd-HHMMSS.dump ./scripts/restore.sh
# 注意：恢复会覆盖目标数据库数据，仅应在停写维护窗口执行。

BACKUP_FILE="${BACKUP_FILE:-}"
if [[ -z "${BACKUP_FILE}" || ! -f "${BACKUP_FILE}" ]]; then
  echo "用法：BACKUP_FILE=/path/backup.dump ./scripts/restore.sh" >&2
  exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
POSTGRES_USER="${POSTGRES_USER:-class_schedule}"
POSTGRES_DB="${POSTGRES_DB:-class_schedule}"
POSTGRES_SERVICE="${POSTGRES_SERVICE:-postgres}"
COMPOSE_FILE="${COMPOSE_FILE:-${ROOT}/docker-compose.yml}"
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-}"
FILENAME="$(basename "${BACKUP_FILE}")"

compose=(docker compose -f "${COMPOSE_FILE}")
if [[ -n "${COMPOSE_PROJECT_NAME}" ]]; then
  compose+=(--project-name "${COMPOSE_PROJECT_NAME}")
fi
CONTAINER="$("${compose[@]}" ps -q "${POSTGRES_SERVICE}")"
if [[ -z "${CONTAINER}" ]]; then
  echo "未找到 PostgreSQL 服务容器: ${POSTGRES_SERVICE}" >&2
  exit 1
fi

docker cp "${BACKUP_FILE}" "${CONTAINER}:/var/lib/postgresql/${FILENAME}"
"${compose[@]}" exec -T "${POSTGRES_SERVICE}" pg_restore -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" \
  --clean --if-exists "/var/lib/postgresql/${FILENAME}"
"${compose[@]}" exec -T "${POSTGRES_SERVICE}" rm -f "/var/lib/postgresql/${FILENAME}"

echo "恢复完成：${BACKUP_FILE}"
printf 'SHA-256：%s\n' "$(shasum -a 256 "${BACKUP_FILE}" | cut -d ' ' -f1)"
echo "恢复后请验证：Flyway 版本、版本列表、assignment 数量、已发布版本不可变触发器状态。"
