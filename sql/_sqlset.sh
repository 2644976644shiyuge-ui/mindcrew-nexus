#!/bin/bash
# ═══════════════════════════════════════════════════════════════════
# SQL 文件集合 · 自动发现 + 排序（单一事实来源）
#
# 被 docker-init/00-init-all.sh（容器内首启）与 migrate.sh（宿主机每次部署）
# 共同 source，保证两条路径用同一套发现与排序逻辑，永不再手工维护文件清单。
#
# 分类（基于文件内容，不依赖人工清单）：
#   · 基线集 baseline   = 含 `DROP TABLE` 的文件（建表会先 DROP，重跑会丢数据）
#                         → 只在「全新空库」首启时跑一次
#   · 幂等集 idempotent = 不含 `DROP TABLE` 的文件（ADD COLUMN IF / information_schema 判断
#                         / CREATE TABLE IF NOT EXISTS）→ 每次部署都可安全重跑
#
# 排序规则（表间无 FOREIGN KEY，顺序自由，仅保证语义清晰 + 增量稳定）：
#   baseline:   docmind-init.sql 永远第一（核心表 + 种子）→ 其余 *-schema.sql 按名排序
#   idempotent: *-schema.sql（IF NOT EXISTS 新表）先 → 其余（migration/index/backfill）后
#
# 始终排除：docmind-all-in-one.sql（整合包，不参与逐文件执行，避免重复/DROP）
# ═══════════════════════════════════════════════════════════════════

# 列出目录下所有参与执行的 .sql（排除整合包），仅文件名
_all_sql() {
  local dir="$1"
  ( cd "$dir" && ls -1 *.sql 2>/dev/null | grep -v '^docmind-all-in-one\.sql$' )
}

# 某文件是否为基线（含「真正的」DROP TABLE 语句）
# 先剔除以 -- 或 # 开头的注释行，避免注释里写到 "DROP TABLE" 被误判为基线。
_is_baseline() {
  grep -vE '^[[:space:]]*(--|#)' "$1" | grep -qiE 'DROP[[:space:]]+TABLE'
}

# 基线集（含 DROP TABLE）· docmind-init.sql 第一，其余 schema 按名排序
baseline_files() {
  local dir="$1" f
  # docmind-init.sql 必须最先（若存在且确为基线）
  if [ -f "$dir/docmind-init.sql" ] && _is_baseline "$dir/docmind-init.sql"; then
    echo "docmind-init.sql"
  fi
  for f in $(_all_sql "$dir" | sort); do
    [ "$f" = "docmind-init.sql" ] && continue
    if _is_baseline "$dir/$f"; then echo "$f"; fi
  done
}

# 幂等集（不含 DROP TABLE）· 先 *-schema.sql，再其余，组内按名排序
idempotent_files() {
  local dir="$1" f
  # 先：幂等的 *-schema.sql（IF NOT EXISTS 新表）
  for f in $(_all_sql "$dir" | grep -E '\-schema\.sql$' | sort); do
    if ! _is_baseline "$dir/$f"; then echo "$f"; fi
  done
  # 后：migration / index / backfill 等增量
  for f in $(_all_sql "$dir" | grep -vE '\-schema\.sql$' | sort); do
    [ "$f" = "docmind-init.sql" ] && continue
    if ! _is_baseline "$dir/$f"; then echo "$f"; fi
  done
}
