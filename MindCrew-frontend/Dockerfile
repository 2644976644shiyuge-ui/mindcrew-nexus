# ═══════════════════════════════════════════════════════════════════
# 前端镜像 · Vite 构建 → Nginx 提供静态文件
# 最终镜像约 50MB
# ═══════════════════════════════════════════════════════════════════

# ─── 阶段 1 · Node 构建 ───
FROM node:20-alpine AS builder

WORKDIR /app

# 优先复制依赖描述，便于缓存
COPY package*.json ./
# 使用国内镜像加速（阿里云内网构建尤其需要）
RUN npm config set registry https://registry.npmmirror.com \
 && rm -f package-lock.json \
 && npm install --no-audit --no-fund

COPY . .
RUN npm run build


# ─── 阶段 2 · Nginx 静态托管 ───
FROM nginx:1.27-alpine

# 时区
RUN apk add --no-cache tzdata && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone

# 替换默认配置
RUN rm /etc/nginx/conf.d/default.conf

COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/mindcrew.conf

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q -O - http://127.0.0.1/ >/dev/null 2>&1 || exit 1

CMD ["nginx", "-g", "daemon off;"]
