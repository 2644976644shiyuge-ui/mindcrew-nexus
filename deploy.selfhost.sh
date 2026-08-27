#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════
# MindCrew · 自建服务器一键部署（自建 MySQL + 阿里云 OSS）
#
# 与 deploy.sh（阿里云版）的区别：
#   - 数据库：容器内自建 MySQL（不依赖 RDS）
#   - 对象存储：仍用阿里云 OSS（STORAGE_TYPE=oss）
#   - compose：用 docker-compose.yml（全容器化），数据走 docker named volume
#   - 完全独立：不读 .env.prod、不碰 deploy.sh / docker-compose.prod.yml
#
# 用法（首次部署）:
#   1. cp .env.selfhost.example .env.selfhost
#   2. vi .env.selfhost          # 填 BAILIAN_API_KEY + OSS_* + MySQL 密码
#   3. ./deploy.selfhost.sh init
#
# 后续:
#   ./deploy.selfhost.sh update          # 重新构建 backend/frontend + 滚动重启
#   ./deploy.selfhost.sh logs backend    # 看日志
#   ./deploy.selfhost.sh status          # 容器状态
#   ./deploy.selfhost.sh restart backend # 重启单个服务
#   ./deploy.selfhost.sh stop            # 停止（数据保留）
#   ./deploy.selfhost.sh nuke            # 删全部容器 + 数据卷（不可逆，MySQL/Milvus 数据会丢）
# ═══════════════════════════════════════════════════════════════════

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_FILE="${PROJECT_DIR}/docker-compose.yml"
ENV_FILE="${PROJECT_DIR}/.env.selfhost"

cyan()  { printf "\033[36m%s\033[0m\n" "$*"; }
green() { printf "\033[32m%s\033[0m\n" "$*"; }
red()   { printf "\033[31m%s\033[0m\n" "$*" >&2; }

ensure_env() {
  if [ ! -f "$ENV_FILE" ]; then
    red "✗ 未找到 $ENV_FILE"
    red "  请先: cp .env.selfhost.example .env.selfhost && vi .env.selfhost"
    exit 1
  fi
}

install_docker() {
  if command -v docker >/dev/null 2>&1; then
    cyan "✓ Docker 已安装: $(docker --version)"
  else
    cyan "→ 安装 Docker"
    curl -fsSL https://get.docker.com | bash
    systemctl enable --now docker
    green "✓ Docker 安装完成"
  fi
  if ! docker compose version >/dev/null 2>&1; then
    red "✗ Docker Compose v2 不可用，请升级 Docker（>= 20.10.13）"
    exit 1
  fi
}

# 统一带上 --env-file 与 -f，避免误读默认 .env
dc() { docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"; }

# 从 .env 文件读单个变量（去掉行尾 CR/空白）；$2 为默认值
env_get() {
  local v
  v=$(grep -E "^$1=" "$ENV_FILE" 2>/dev/null | tail -1 | cut -d= -f2- | tr -d '\r' | sed 's/[[:space:]]*$//')
  [ -n "$v" ] && echo "$v" || echo "${2:-}"
}

MYSQL_CONTAINER_NAME="mindcrew-mysql"

# 等 MySQL 就绪 + 自动执行全部增量迁移（幂等·永不丢数据·自动发现新 SQL）
run_migrations() {
  local pass; pass="$(env_get MYSQL_ROOT_PASSWORD root)"
  cyan "→ 等待 MySQL 就绪..."
  local ok=no i
  for i in $(seq 1 60); do
    if docker exec "$MYSQL_CONTAINER_NAME" mysqladmin ping -uroot -p"$pass" --silent >/dev/null 2>&1; then
      ok=yes; break
    fi
    sleep 2
  done
  [ "$ok" = yes ] || { red "✗ MySQL 60s 内未就绪，跳过迁移（可稍后手动: ./sql/run.sh --migrate）"; return 1; }
  cyan "→ 执行数据库增量迁移（自建 MySQL）"
  MIGRATE_MODE=container MIGRATE_BASELINE=auto \
    MYSQL_CONTAINER="$MYSQL_CONTAINER_NAME" \
    MYSQL_USER=root MYSQL_PASSWORD="$pass" MYSQL_DATABASE=docmind \
    bash "$PROJECT_DIR/sql/migrate.sh"
}

cmd_init() {
  install_docker
  ensure_env
  cyan "→ 构建并启动全部服务（含自建 MySQL；首次约 5-10 分钟）"
  dc up -d --build
  run_migrations || { red "✗ 数据库迁移失败，部署未完成"; return 1; }
  green "\n✓ 部署完成"
  cmd_status
  cyan "\n→ 前端: http://$(curl -s ifconfig.me 2>/dev/null || hostname -I | awk '{print $1}'):8085"
  cyan "→ 后端: http://localhost:8090/actuator/health"
  cyan "→ 日志: ./deploy.selfhost.sh logs backend"
}

cmd_update() {
  ensure_env
  cyan "→ 重新构建 backend + frontend（保留 Maven/apt 缓存，源码变更仍会重新编译）"
  dc build --pull backend frontend
  cyan "→ 确保基础设施服务已启动"
  dc up -d mysql redis milvus-etcd minio milvus
  run_migrations || { red "✗ 数据库迁移失败，停止本次更新"; return 1; }
  cyan "→ 滚动重启后端"
  dc up -d --no-deps --force-recreate backend
  cyan "→ 重启前端网关（刷新后端 DNS）"
  dc up -d --no-deps --force-recreate frontend
  cyan "→ 当前运行镜像"
  docker inspect -f 'backend image={{.Image}} started={{.State.StartedAt}}' mindcrew-backend
  docker inspect -f 'frontend image={{.Image}} started={{.State.StartedAt}}' mindcrew-frontend
  green "✓ 更新完成"
  cmd_status
}

# 用现有镜像启动全部服务（含基础设施）· 不重建 · 开机/依赖掉线后用它一键拉起
cmd_up() {
  ensure_env
  cyan "→ 启动全部服务（用现有镜像，不重新构建）"
  dc up -d
  run_migrations || { red "✗ 数据库迁移失败，启动流程未完成"; return 1; }
  green "✓ 已启动"
  cmd_status
}

cmd_status() { cyan "\n── 容器状态 ──"; dc ps; }
cmd_logs()   { dc logs -f --tail=200 "${1:-backend}"; }
cmd_restart(){ dc restart "${1:-backend}"; green "✓ 已重启 ${1:-backend}"; }
cmd_stop()   { dc down; green "✓ 已停止所有容器（数据保留）"; }

cmd_nuke() {
  red "⚠ 即将删除全部容器 + 数据卷（含自建 MySQL / Milvus 向量库 / uploads）"
  read -p "确认输入 yes 继续: " ans
  [ "$ans" = "yes" ] || { cyan "已取消"; exit 0; }
  dc down -v --remove-orphans
  green "✓ 已清空"
}

case "${1:-help}" in
  init)     cmd_init ;;
  update)   cmd_update ;;
  up)       cmd_up ;;
  status)   cmd_status ;;
  logs)     cmd_logs "${2:-backend}" ;;
  restart)  cmd_restart "${2:-backend}" ;;
  stop)     cmd_stop ;;
  nuke)     cmd_nuke ;;
  *)
    cat <<EOF
MindCrew 自建部署脚本（自建 MySQL + 阿里云 OSS） · 用法：

  ./deploy.selfhost.sh init           首次部署（装 Docker + 构建 + 启动）
  ./deploy.selfhost.sh update         重新构建并滚动重启 backend/frontend
  ./deploy.selfhost.sh up             用现有镜像启动全部服务（开机/依赖掉线后一键拉起，不重建）
  ./deploy.selfhost.sh status         查看所有容器状态
  ./deploy.selfhost.sh logs [svc]     查看日志，默认 backend（可选 frontend/milvus/mysql/...）
  ./deploy.selfhost.sh restart [svc]  重启单个服务
  ./deploy.selfhost.sh stop           停止所有容器（数据保留）
  ./deploy.selfhost.sh nuke           清空全部容器 + 数据卷（不可逆）

部署前请先填写 .env.selfhost （复制 .env.selfhost.example）
EOF
    ;;
esac
