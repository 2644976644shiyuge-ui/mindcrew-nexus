# ═══════════════════════════════════════════════════════════════════
# 后端镜像 · 多阶段构建（构建 Maven → 运行 JRE）
# 镜像最终约 280MB（jre + jar + 必要依赖）
# ═══════════════════════════════════════════════════════════════════

# ─── 阶段 1 · Maven 构建（本地无 mvn 也能打镜像）───
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# Docker 内 Maven 走官方 Central，依赖缓存跨构建复用
COPY docker/maven-settings-docker.xml /root/.m2/settings.xml

# 优先复制 pom.xml；Maven 仓库通过 BuildKit cache 跨构建复用
COPY pom.xml ./

# 复制源码 + 配置
COPY src ./src

# 打 fat jar，跳过测试编译；实时输出依赖下载进度，便于定位网络问题
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B \
    -Dmaven.wagon.http.retryHandler.count=5 \
    -Dmaven.wagon.httpconnectionManager.ttlSeconds=120 \
    clean package -DskipTests -Dmaven.test.skip=true -s /root/.m2/settings.xml \
 && cp target/*.jar /build/app.jar


# ─── 阶段 2 · 运行镜像（仅 JRE 17 + 系统依赖）───
FROM eclipse-temurin:17-jre-jammy

# Ubuntu 官方源在中国大陆服务器上可能极慢。默认走腾讯云公网镜像，
# 其他环境可在 compose build.args 或 docker build --build-arg 中覆盖。
ARG UBUNTU_MIRROR=https://mirrors.cloud.tencent.com/ubuntu
ARG UBUNTU_PORTS_MIRROR=https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports

# 系统依赖：ffmpeg 用于视频切片、libreoffice 用于 office 转换、curl 健康检查、字体（中文）
# 注意：x86 走 archive.ubuntu.com / security.ubuntu.com；Apple Silicon(ARM64) 走 ports.ubuntu.com/ubuntu-ports，三者都要替换
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt/lists,sharing=locked \
    sed -i \
        -e "s|http://archive.ubuntu.com/ubuntu|${UBUNTU_MIRROR}|g" \
        -e "s|http://security.ubuntu.com/ubuntu|${UBUNTU_MIRROR}|g" \
        -e "s|http://ports.ubuntu.com/ubuntu-ports|${UBUNTU_PORTS_MIRROR}|g" \
        /etc/apt/sources.list \
 && apt-get -o Acquire::Retries=5 update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        ffmpeg \
        libreoffice \
        curl \
        fontconfig \
        fonts-noto-cjk \
        ca-certificates \
        tzdata \
 && true

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_OPTS="-Xms512m -Xmx2048m -XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -Djava.awt.headless=true" \
    SPRING_PROFILES_ACTIVE=prod \
    SERVER_PORT=8080

WORKDIR /app

# 从构建阶段取 jar
COPY --from=builder /build/app.jar app.jar

# 数据目录（容器内供本地上传/缓存使用 · 真正持久化走 volume mount）
RUN mkdir -p /app/uploads /app/logs

EXPOSE 8080

# 健康检查 · Spring Actuator
HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
