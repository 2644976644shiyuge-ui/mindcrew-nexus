#!/bin/bash
# ═══════════════════════════════════════════════════════════════════
# MySQL 容器「首次启动」自动执行（数据卷为空时）· 自动发现并导入全部 SQL
# 挂载位置: /docker-entrypoint-initdb.d/
# 注：MySQL 官方镜像仅在「数据目录为空」时执行本脚本一次；已有数据的容器
#     重启不会再跑这里 —— 增量迁移由宿主机 migrate.sh 在每次部署时补齐。
#
# 自动发现：不再手工维护文件清单。新增任意 sql/*.sql 都会被自动纳入。
#   · 基线集（含 DROP TABLE）：单趟按序执行（docmind-init 第一）
#   · 幂等集（无 DROP TABLE）：执行两趟，吸收文件间的先后依赖（全部幂等，安全）
# ═══════════════════════════════════════════════════════════════════

SQL_DIR="/sql-bundle"   # docker-compose 把整个 sql/ 目录挂到这里
# shellcheck disable=SC1091
source "$SQL_DIR/_sqlset.sh"

echo "[init] 开始初始化数据库 docmind（自动发现 SQL）..."

run_file() {
  local f="$1"
  if [ ! -f "$SQL_DIR/$f" ]; then
    echo "[init][SKIP] $f 不存在"
    return 0
  fi
  echo "[init] 执行 $f"
  mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 docmind < "$SQL_DIR/$f" || \
    echo "[init][WARN] $f 执行有告警/错误（如 Duplicate column 属幂等正常 · 继续）"
}

# ── 1) 基线集：建核心表 + 种子，单趟按序（DROP+CREATE 不可重复跑）──
echo "[init] ── 基线集（仅全新库一次）──"
while IFS= read -r f; do
  [ -n "$f" ] && run_file "$f"
done < <(baseline_files "$SQL_DIR")

# ── 2) 幂等集：新表(IF NOT EXISTS) + 增量列 · 跑两趟吸收先后依赖 ──
echo "[init] ── 幂等集 · 第 1 趟 ──"
while IFS= read -r f; do
  [ -n "$f" ] && run_file "$f"
done < <(idempotent_files "$SQL_DIR")

echo "[init] ── 幂等集 · 第 2 趟（补齐跨文件依赖）──"
while IFS= read -r f; do
  [ -n "$f" ] && run_file "$f"
done < <(idempotent_files "$SQL_DIR")

echo "[init] 数据库初始化完成 ✓"
