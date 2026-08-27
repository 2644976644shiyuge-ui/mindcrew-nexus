#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════
# MindCrew · 远程一键部署（从你的电脑 → 客户 Linux 服务器，SSH 直连）
#
# 做的事：
#   1. rsync 把项目源码同步到客户服务器（自动排除 node_modules/target/dist/.git 等）
#   2. 不覆盖服务器上已有的 .env.selfhost（密钥按客户单独配，只同步 .example 模板）
#   3. 在服务器上跑 ./deploy.selfhost.sh <init|update>（在服务器本机构建，天然 amd64，无跨架构问题）
#
# 用法：
#   首次部署：  REMOTE=root@1.2.3.4 ./remote-deploy.sh init
#   日常更新：  REMOTE=root@1.2.3.4 ./remote-deploy.sh update
#   指定目录/端口：
#     REMOTE=ubuntu@host REMOTE_DIR=/opt/mindcrew SSH_PORT=2222 ./remote-deploy.sh init
#
# 首次部署前，先在服务器上配好密钥（脚本会提示）：
#   ssh root@1.2.3.4 'cd /opt/mindcrew && cp .env.selfhost.example .env.selfhost && vi .env.selfhost'
#   填：BAILIAN_API_KEY（阿里云百炼）、OSS_*（对象存储）、MYSQL_ROOT_PASSWORD
# ═══════════════════════════════════════════════════════════════════

set -euo pipefail

CMD="${1:-help}"
REMOTE="${REMOTE:-}"
REMOTE_DIR="${REMOTE_DIR:-/opt/mindcrew}"
SSH_PORT="${SSH_PORT:-22}"
LOCAL_DIR="$(cd "$(dirname "$0")" && pwd)"

red()   { printf "\033[31m%s\033[0m\n" "$*" >&2; }
green() { printf "\033[32m%s\033[0m\n" "$*"; }
cyan()  { printf "\033[36m%s\033[0m\n" "$*"; }

[ "$CMD" = "help" ] && {
  sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'
  exit 0
}
[ -z "$REMOTE" ] && { red "✗ 需要设置 REMOTE=user@host（例：REMOTE=root@1.2.3.4）"; exit 1; }
case "$CMD" in init|update) ;; *) red "✗ 命令只能是 init 或 update"; exit 1 ;; esac

SSH="ssh -p $SSH_PORT $REMOTE"

cyan "→ [1/4] 检查与客户服务器的 SSH 连通性（$REMOTE:$SSH_PORT）"
$SSH 'echo ok' >/dev/null || { red "✗ SSH 连不上，检查地址/端口/密钥"; exit 1; }

cyan "→ [2/4] 在服务器创建目录 $REMOTE_DIR"
$SSH "mkdir -p '$REMOTE_DIR'"

cyan "→ [3/4] rsync 同步源码到服务器（排除构建产物 / 不覆盖服务器上的 .env.selfhost）"
rsync -az --delete \
  -e "ssh -p $SSH_PORT" \
  --exclude '.git' \
  --exclude 'node_modules' \
  --exclude 'target' \
  --exclude 'dist' \
  --exclude '.idea' \
  --exclude '.DS_Store' \
  --exclude 'uploads' \
  --exclude '.env.selfhost' \
  "$LOCAL_DIR/" "$REMOTE:$REMOTE_DIR/"

# 首次部署：确认服务器上已配置 .env.selfhost，否则提示并中止
if [ "$CMD" = "init" ]; then
  if ! $SSH "test -f '$REMOTE_DIR/.env.selfhost'"; then
    red "✗ 服务器上还没有 $REMOTE_DIR/.env.selfhost"
    cyan "  请先在服务器配置密钥后重试："
    cyan "    ssh -p $SSH_PORT $REMOTE \"cd $REMOTE_DIR && cp .env.selfhost.example .env.selfhost && vi .env.selfhost\""
    cyan "  需要填：BAILIAN_API_KEY、OSS_*、MYSQL_ROOT_PASSWORD"
    exit 1
  fi
fi

cyan "→ [4/4] 在服务器上执行 ./deploy.selfhost.sh $CMD（首次 init 约 5-15 分钟，在服务器本机构建）"
$SSH "cd '$REMOTE_DIR' && chmod +x deploy.selfhost.sh sql/*.sh 2>/dev/null; ./deploy.selfhost.sh $CMD"

green "\n✓ 远程 $CMD 完成"
cyan "→ 前端: http://<客户服务器IP>:8085"
cyan "→ 后端健康: ssh -p $SSH_PORT $REMOTE 'curl -s localhost:8090/actuator/health'"
cyan "→ 看日志: ssh -p $SSH_PORT $REMOTE 'cd $REMOTE_DIR && ./deploy.selfhost.sh logs backend'"
