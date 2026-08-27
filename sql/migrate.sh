#!/bin/bash
# ═══════════════════════════════════════════════════════════════════
# 增量迁移器 · 每次部署都跑（init 与 update 都调用）
#
# 只执行「幂等集」(无 DROP TABLE 的文件)：新表 IF NOT EXISTS + ADD COLUMN 判断列
# 是否已存在。对已上线、有数据的库安全重跑，永不丢数据；自动发现新 SQL，
# 无需再手工维护任何清单 —— 这是「缺字段」问题的根治。
#
# 两种连接模式（适配两套 docker 部署）：
#   MIGRATE_MODE=container  自建 MySQL 容器（docker-compose.yml / deploy.selfhost.sh）
#                           → docker exec 进 $MYSQL_CONTAINER
#   MIGRATE_MODE=rds        外部阿里云 RDS（docker-compose.prod.yml / deploy.sh）
#                           → 优先用宿主机 mysql 客户端；没有则用一次性 mysql:8.0 容器
#
# 环境变量（均有默认值）：
#   MIGRATE_MODE     container | rds        (默认 container)
#   MIGRATE_BASELINE no | auto | yes        (默认 no)
#                      no   = 只跑幂等增量（已上线的库、日常 update）
#                      auto = 库为空(无 sys_user 表)才先建基线，否则跳过（安全·适合 init）
#                      yes  = 强制先跑基线(含 DROP TABLE，会清表)·仅全新库
#   MYSQL_CONTAINER  容器名                  (默认 mindcrew-mysql)
#   MYSQL_HOST       RDS 地址                (rds 模式必填)
#   MYSQL_PORT       端口                    (默认 3306)
#   MYSQL_USER       账号                    (默认 root)
#   MYSQL_PASSWORD   密码                    (默认 root)
#   MYSQL_DATABASE   库名                    (默认 docmind)
#
# 用法：
#   ./sql/migrate.sh                                       # 容器模式·只跑增量
#   MIGRATE_BASELINE=auto ./sql/migrate.sh                 # 空库自动建基线，否则只增量
#   MIGRATE_MODE=rds MYSQL_HOST=xx MYSQL_PASSWORD=xx MIGRATE_BASELINE=auto ./sql/migrate.sh
# ═══════════════════════════════════════════════════════════════════

set -u

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/_sqlset.sh"

MIGRATE_MODE="${MIGRATE_MODE:-container}"
MIGRATE_BASELINE="${MIGRATE_BASELINE:-no}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-mindcrew-mysql}"
MYSQL_HOST="${MYSQL_HOST:-}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root}"
MYSQL_DATABASE="${MYSQL_DATABASE:-docmind}"

# 统一的 mysql 执行入口：从 stdin 读 SQL；额外参数透传给 mysql 客户端
mysql_exec() {
  case "$MIGRATE_MODE" in
    container)
      docker exec -i "$MYSQL_CONTAINER" mysql \
        -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" \
        --default-character-set=utf8mb4 "$@" "$MYSQL_DATABASE"
      ;;
    rds)
      if command -v mysql >/dev/null 2>&1; then
        mysql -h"$MYSQL_HOST" -P"$MYSQL_PORT" \
          -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" \
          --default-character-set=utf8mb4 "$@" "$MYSQL_DATABASE"
      else
        # 宿主机无 mysql 客户端 → 用一次性容器（共享宿主网络以解析 RDS 内网地址）
        docker run --rm -i --network host mysql:8.0 mysql \
          -h"$MYSQL_HOST" -P"$MYSQL_PORT" \
          -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" \
          --default-character-set=utf8mb4 "$@" "$MYSQL_DATABASE"
      fi
      ;;
    *)
      echo "✗ 未知 MIGRATE_MODE=$MIGRATE_MODE（应为 container 或 rds）" >&2
      exit 1
      ;;
  esac
}

# 库是否为空（核心表 sys_user 不存在视为空）
db_is_empty() {
  local n
  n=$(echo "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sys_user';" \
        | mysql_exec -N 2>/dev/null | tr -d '[:space:]')
  [ "$n" = "0" ] || [ -z "$n" ]
}

# 连通性自检
preflight() {
  if [ "$MIGRATE_MODE" = "container" ]; then
    if ! docker ps --format '{{.Names}}' | grep -qx "$MYSQL_CONTAINER"; then
      echo "✗ 找不到运行中的容器 $MYSQL_CONTAINER" >&2; exit 1
    fi
  elif [ "$MIGRATE_MODE" = "rds" ] && [ -z "$MYSQL_HOST" ]; then
    echo "✗ RDS 模式需要 MYSQL_HOST" >&2; exit 1
  fi
  if ! echo "SELECT 1;" | mysql_exec >/dev/null 2>&1; then
    echo "✗ 数据库连接失败（mode=${MIGRATE_MODE} host=${MYSQL_HOST:-$MYSQL_CONTAINER} db=${MYSQL_DATABASE}）" >&2
    exit 1
  fi
}

MIGRATION_FAILURES=0

run_file() {
  local f="$1" strict="${2:-no}" path="$SCRIPT_DIR/$f"
  [ -f "$path" ] || { echo "  [skip] $f 不存在"; return 0; }
  echo "  ▶ $f"
  if ! mysql_exec < "$path" 2>/tmp/mig_err.$$; then
    local error_message
    error_message="$(tr '\n' ' ' </tmp/mig_err.$$ | sed 's/  */ /g')"
    if [ "$strict" = "yes" ]; then
      echo "    [error] $f: $error_message"
      MIGRATION_FAILURES=$((MIGRATION_FAILURES + 1))
    else
      echo "    [retry] $f 首趟未完成，将在第 2 趟重试: $error_message"
    fi
  fi
  rm -f /tmp/mig_err.$$
}

echo "════════════════════════════════════════════════════════"
echo "  增量迁移 · mode=$MIGRATE_MODE  db=$MYSQL_DATABASE"
echo "════════════════════════════════════════════════════════"
preflight

# ── 可选：基线（建核心表）· 仅空库或显式要求时 ──
need_baseline=no
case "$MIGRATE_BASELINE" in
  yes)  need_baseline=yes ;;
  auto) if db_is_empty; then need_baseline=yes; echo "检测到空库 → 先建基线"; else echo "库已存在数据 → 跳过基线，仅跑增量"; fi ;;
  no)   : ;;
  *)    echo "✗ 未知 MIGRATE_BASELINE=$MIGRATE_BASELINE" >&2; exit 1 ;;
esac
if [ "$need_baseline" = "yes" ]; then
  echo "── 基线（含 DROP TABLE · 单趟）──"
  while IFS= read -r f; do [ -n "$f" ] && run_file "$f" yes; done < <(baseline_files "$SCRIPT_DIR")
fi

IDEM_COUNT=$(idempotent_files "$SCRIPT_DIR" | wc -l | tr -d ' ')
echo "发现幂等迁移文件 ${IDEM_COUNT} 个，执行两趟（吸收先后依赖）"

echo "── 第 1 趟 ──"
while IFS= read -r f; do [ -n "$f" ] && run_file "$f" no; done < <(idempotent_files "$SCRIPT_DIR")
echo "── 第 2 趟 ──"
while IFS= read -r f; do [ -n "$f" ] && run_file "$f" yes; done < <(idempotent_files "$SCRIPT_DIR")

echo "════════════════════════════════════════════════════════"
if [ "$MIGRATION_FAILURES" -gt 0 ]; then
  echo "  ✗ 迁移失败：第 2 趟仍有 ${MIGRATION_FAILURES} 个文件执行失败"
  echo "════════════════════════════════════════════════════════"
  exit 1
fi
echo "  ✓ 迁移完成（共 ${IDEM_COUNT} 个文件 × 2 趟，全部成功）"
echo "════════════════════════════════════════════════════════"
