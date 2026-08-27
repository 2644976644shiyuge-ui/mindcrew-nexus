#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════
# MindCrew · 阿里云 ECS 一键部署脚本
#
# 用法（首次部署）:
#   1. scp 整个工程到 ECS: scp -r MindCrew/ root@<ECS_IP>:/data/
#   2. ssh root@<ECS_IP>
#   3. cd /data/MindCrew
#   4. cp .env.prod.example .env.prod && vi .env.prod   # 填实际值
#   5. ./deploy.sh init           # 首次：装 Docker + 拉镜像 + 启动
#
# 后续更新（CI/CD 或手动）:
#   ./deploy.sh update             # 重新构建 backend/frontend + 滚动重启
#   ./deploy.sh logs backend       # 看后端日志
#   ./deploy.sh status             # 全部容器状态
#   ./deploy.sh restart backend    # 重启单个服务
#
# 不可恢复的操作（小心）:
#   ./deploy.sh nuke               # 删除全部容器 + 数据卷（Milvus 数据会丢）
# ═══════════════════════════════════════════════════════════════════

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_FILE="${PROJECT_DIR}/docker-compose.prod.yml"
ENV_FILE="${PROJECT_DIR}/.env.prod"

cyan()  { printf "\033[36m%s\033[0m\n" "$*"; }
green() { printf "\033[32m%s\033[0m\n" "$*"; }
red()   { printf "\033[31m%s\033[0m\n" "$*" >&2; }

ensure_env() {
  if [ ! -f "$ENV_FILE" ]; then
    red "✗ 未找到 $ENV_FILE"
    red "  请先: cp .env.prod.example .env.prod && vi .env.prod"
    exit 1
  fi
}

install_docker() {
  if command -v docker >/dev/null 2>&1; then
    cyan "✓ Docker 已安装: $(docker --version)"
  else
    cyan "→ 安装 Docker（阿里云 yum 源）"
    curl -fsSL https://get.docker.com | bash -s docker --mirror Aliyun
    systemctl enable --now docker
    green "✓ Docker 安装完成"
  fi
  if ! docker compose version >/dev/null 2>&1; then
    red "✗ Docker Compose v2 不可用，请升级 Docker（>= 20.10.13）"
    exit 1
  fi
}

prepare_dirs() {
  cyan "→ 创建持久化数据目录 /data/mindcrew/{etcd,milvus,milvus-minio,redis,uploads,logs}"
  mkdir -p /data/mindcrew/{etcd,milvus,milvus-minio,redis,uploads,logs}
}

# 从 .env.prod 读单个变量（去掉行尾 CR/空白）；$2 为默认值
env_get() {
  local v
  v=$(grep -E "^$1=" "$ENV_FILE" 2>/dev/null | tail -1 | cut -d= -f2- | tr -d '\r' | sed 's/[[:space:]]*$//')
  [ -n "$v" ] && echo "$v" || echo "${2:-}"
}

# 对外部 RDS 执行迁移（幂等·自动发现新 SQL·空库自动建基线，否则只补增量不动数据）
run_migrations() {
  local host user pass port
  host="$(env_get MYSQL_HOST)"; port="$(env_get MYSQL_PORT 3306)"
  user="$(env_get MYSQL_USER root)"; pass="$(env_get MYSQL_PASSWORD)"
  if [ -z "$host" ] || [ -z "$pass" ]; then
    red "✗ .env.prod 缺少 MYSQL_HOST / MYSQL_PASSWORD，跳过迁移"; return 1
  fi
  cyan "→ 对 RDS($host) 执行数据库迁移"
  MIGRATE_MODE=rds MIGRATE_BASELINE=auto \
    MYSQL_HOST="$host" MYSQL_PORT="$port" \
    MYSQL_USER="$user" MYSQL_PASSWORD="$pass" MYSQL_DATABASE=docmind \
    bash "$PROJECT_DIR/sql/migrate.sh"
}

cmd_init() {
  install_docker
  ensure_env
  prepare_dirs
  run_migrations || { red "✗ 数据库迁移失败，部署已停止"; return 1; }
  cyan "→ 构建并启动全部服务（首次约 5-10 分钟）"
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --build
  green "\n✓ 部署完成"
  cmd_status
  cyan "\n→ 前端: http://$(curl -s ifconfig.me 2>/dev/null || hostname -I | awk '{print $1}')"
  cyan "→ 后端: http://localhost:8080/actuator/health"
  cyan "→ 日志: ./deploy.sh logs backend"
}

cmd_update() {
  ensure_env
  run_migrations || { red "✗ 数据库迁移失败，更新已停止"; return 1; }
  cyan "→ 重新构建 backend + frontend"
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" build backend frontend
  cyan "→ 滚动重启"
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --no-deps backend frontend
  green "✓ 更新完成"
  cmd_status
}

cmd_status() {
  cyan "\n── 容器状态 ──"
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps
}

cmd_logs() {
  local svc="${1:-backend}"
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" logs -f --tail=200 "$svc"
}

cmd_restart() {
  local svc="${1:-backend}"
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" restart "$svc"
  green "✓ 已重启 $svc"
}

cmd_stop() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" down
  green "✓ 已停止所有容器（数据保留）"
}

cmd_nuke() {
  red "⚠ 即将删除全部容器 + 数据卷（含 Milvus 向量库 / uploads / logs）"
  read -p "确认输入 yes 继续: " ans
  [ "$ans" = "yes" ] || { cyan "已取消"; exit 0; }
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" down -v --remove-orphans
  rm -rf /data/mindcrew
  green "✓ 已清空"
}

case "${1:-help}" in
  init)     cmd_init ;;
  update)   cmd_update ;;
  status)   cmd_status ;;
  logs)     cmd_logs "${2:-backend}" ;;
  restart)  cmd_restart "${2:-backend}" ;;
  stop)     cmd_stop ;;
  nuke)     cmd_nuke ;;
  *)
    cat <<EOF
MindCrew 部署脚本 · 用法：

  ./deploy.sh init              首次部署（装 Docker + 构建 + 启动）
  ./deploy.sh update            重新构建并滚动重启 backend/frontend
  ./deploy.sh status            查看所有容器状态
  ./deploy.sh logs [svc]        查看日志，默认 backend（可选: frontend/milvus/...）
  ./deploy.sh restart [svc]     重启单个服务
  ./deploy.sh stop              停止所有容器（数据保留）
  ./deploy.sh nuke              清空全部容器 + 数据卷（不可逆）

部署前请先填写 .env.prod （复制 .env.prod.example）
EOF
    ;;
esac
