#!/bin/bash
# ═══════════════════════════════════════════════════════════════════
# 手动跑 SQL 的安全入口 · 强制 utf8mb4，防中文乱码
# 文件清单全部「自动发现」(见 _sqlset.sh)，新增 SQL 无需改本脚本。
#
# 用法：
#   ./sql/run.sh voice-persona-schema.sql   # 跑单个文件
#   ./sql/run.sh --migrate                  # 只跑幂等增量(无DROP)·有数据的库安全·补缺字段
#   ./sql/run.sh --all                      # 全量重建(基线含DROP+幂等)·仅用于全新/可清空的库
#   ./sql/run.sh --check                    # 只查表 / 列状态，不改数据
#
# 环境变量（可选 · 默认值为开发环境）：
#   MYSQL_CONTAINER=mindcrew-mysql
#   MYSQL_USER=root
#   MYSQL_PASSWORD=root
#   MYSQL_DATABASE=docmind
# ═══════════════════════════════════════════════════════════════════

set -u

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/_sqlset.sh"

MYSQL_CONTAINER="${MYSQL_CONTAINER:-mindcrew-mysql}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root}"
MYSQL_DATABASE="${MYSQL_DATABASE:-docmind}"

# 通用 mysql 客户端调用（强制 utf8mb4 · 避免乱码）
mysql_run() {
  docker exec -i "$MYSQL_CONTAINER" mysql \
    -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" \
    --default-character-set=utf8mb4 \
    "$MYSQL_DATABASE" "$@"
}

run_one() {
  local file="$1" path="$SCRIPT_DIR/$1"
  if [ ! -f "$path" ]; then echo "❌ 文件不存在: $path"; exit 1; fi
  echo "▶ 执行 $file"
  mysql_run < "$path" 2>&1 | grep -v "Warning" || true
}

# 逐行从 stdin 读文件名并执行（缺文件跳过，错误不中断）
run_stream() {
  local f
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    [ -f "$SCRIPT_DIR/$f" ] || { echo "  [skip] $f"; continue; }
    echo "▶ $f"
    mysql_run < "$SCRIPT_DIR/$f" 2>&1 | grep -v "Warning" || true
  done
}

case "${1:-}" in
  --migrate)
    echo "════════════════════════════════════════════════════════"
    echo "  幂等增量 · $(idempotent_files "$SCRIPT_DIR" | wc -l | tr -d ' ') 个文件 × 2 趟（有数据的库安全重跑）"
    echo "════════════════════════════════════════════════════════"
    echo "── 第 1 趟 ──"; idempotent_files "$SCRIPT_DIR" | run_stream
    echo "── 第 2 趟 ──"; idempotent_files "$SCRIPT_DIR" | run_stream
    echo "  ✓ 完成"
    ;;
  --all)
    echo "════════════════════════════════════════════════════════"
    echo "  ⚠ 全量重建：基线 $(baseline_files "$SCRIPT_DIR" | wc -l | tr -d ' ') 个(含 DROP TABLE，会重建表/清数据)"
    echo "             + 幂等 $(idempotent_files "$SCRIPT_DIR" | wc -l | tr -d ' ') 个 × 2 趟"
    echo "  仅用于全新或可清空的库！现有生产库请改用 --migrate"
    echo "════════════════════════════════════════════════════════"
    read -p "确认输入 yes 继续: " ans
    [ "$ans" = "yes" ] || { echo "已取消"; exit 0; }
    echo "── 基线 ──";        baseline_files "$SCRIPT_DIR"   | run_stream
    echo "── 幂等·第1趟 ──";  idempotent_files "$SCRIPT_DIR" | run_stream
    echo "── 幂等·第2趟 ──";  idempotent_files "$SCRIPT_DIR" | run_stream
    echo "  ✓ 全部完成"
    ;;
  --check)
    echo "▶ 中文字段抽样（确认无乱码）:"
    mysql_run -e "
      SELECT '── voice_persona ──' AS section;
      SELECT id, name, voice_id FROM voice_persona LIMIT 6;
      SELECT '── kb_category ──' AS section;
      SELECT id, name FROM kb_category LIMIT 5;
      SELECT '── system_persona ──' AS section;
      SELECT id, name FROM system_persona LIMIT 5;
    "
    echo "▶ 数据库连接字符集:"
    mysql_run -e "SHOW VARIABLES LIKE 'character_set%';"
    ;;
  -h|--help|"")
    echo "用法："
    echo "  $0 <file.sql>    # 跑单个文件"
    echo "  $0 --migrate     # 幂等增量(无DROP)·有数据的库安全·补缺字段"
    echo "  $0 --all         # 全量重建(含DROP)·仅全新/可清空的库"
    echo "  $0 --check       # 验证中文/字符集"
    echo ""
    echo "环境变量（可选）：MYSQL_CONTAINER / MYSQL_USER / MYSQL_PASSWORD / MYSQL_DATABASE"
    ;;
  *)
    run_one "$1"
    ;;
esac
