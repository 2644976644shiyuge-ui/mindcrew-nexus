# 第三方开源组件与许可声明（THIRD-PARTY NOTICES）

本产品使用了以下第三方开源组件。各组件版权归其原作者所有，按其各自许可证使用与分发。
本清单为**尽力而为的人工梳理**，非法律意见；正式商业交付前建议用工具生成权威 SBOM
（后端 `license-maven-plugin`，前端 `license-checker`，或 `syft`）并由法务复核。

> 来源：`pom.xml`、`MindCrew-frontend/package.json`、`docker-compose.yml`、`Dockerfile`（2026-06-20 核对）。

---

## 1. 后端依赖（Java / Maven）

| 许可证 | 组件 |
|--------|------|
| **Apache-2.0** | Spring Boot（web / security / data-redis / validation / aop / actuator / websocket / test）、Spring AI（openai / mcp-server-webmvc）、MyBatis-Plus、Alibaba FastJSON2、JJWT、Apache Lucene（core / analyzers-common / analyzers-smartcn）、Apache PDFBox、Apache POI（ooxml / scratchpad）、Apache Commons（lang3 / pool2）、OpenCSV、Milvus SDK、MinIO Client、OkHttp、阿里云 SDK（oss / core / bssopenapi / app-stream-client） |
| **MIT** | Lombok、jsoup |
| **MulanPSL-2.0** | Hutool |
| **GPL-2.0 + FOSS Exception** | MySQL Connector/J（`mysql-connector-j`） |

## 2. 前端依赖（JavaScript / npm）

| 许可证 | 组件 |
|--------|------|
| **MIT** | Vue、Vue Router、Pinia、Element Plus（含 icons-vue）、Vue Flow（core / background / controls）、VueUse、Axios、marked、vue-countup-v3、Vite、vue-tsc、@vitejs/plugin-vue、npm-run-all2、@vue/tsconfig、@types/node、@tsconfig/node24、vite-plugin-vue-devtools |
| **Apache-2.0** | Apache ECharts、docx-preview、SheetJS（`xlsx`，社区版）、TypeScript |
| **BSD-3-Clause** | highlight.js |

## 3. 运行时 / 基础设施（容器镜像内，均以**独立程序 / 服务**方式调用）

| 组件 | 许可证 | 说明 |
|------|--------|------|
| Eclipse Temurin（OpenJDK 17） | GPL-2.0 **+ Classpath Exception** | 标准 JRE，运行时不传染业务代码 |
| **MySQL Community Server** | **GPL-2.0** | 经 JDBC（网络）访问，属独立程序；**若随交付物再分发 MySQL 二进制，需遵守 GPL** |
| Redis 7 | BSD-3-Clause | 7.0/7.2 仍为 BSD（注意 7.4+ 改为 RSALv2/SSPL，勿误升） |
| Milvus / etcd | Apache-2.0 | 向量库及其依赖 |
| **MinIO Server** | **AGPL-3.0** ⚠️ | 自建对象存储；AGPL 对「分发 / 对外提供服务」有较强义务。**生产用阿里云 OSS 可规避** |
| nginx | BSD-2-Clause | 前端静态托管 |
| **FFmpeg** | **LGPL-2.1+ / GPL**（视构建） ⚠️ | 音视频处理，独立进程调用 |
| **LibreOffice** | **MPL-2.0 / LGPL-3.0** ⚠️ | Office 转换，独立进程调用 |
| Node.js | MIT | 仅前端构建期使用 |

---

## 4. 需要重点关注的 Copyleft 组件（商业再分发前确认）

下列组件均以**独立进程 / 网络服务**方式被调用，通常**不会把 copyleft 传染到你的应用代码**；
但如果把它们的**二进制打入交付物再分发**，需遵守各自许可：

- **MySQL（GPL-2.0）**：建议交付时不内置 MySQL 二进制，由买方自备（自建或云 RDS）。
- **MinIO Server（AGPL-3.0）**：最敏感。生产改用**阿里云 OSS** 即可完全规避；如必须自建，须满足 AGPL。
- **FFmpeg（LGPL/GPL）、LibreOffice（MPL/LGPL）**：作为系统工具独立调用，保留其版权与许可声明即可。

> 应用层依赖（第 1、2 节）以 Apache-2.0 / MIT / BSD 为主，**允许商业使用与再分发，义务主要是「保留版权与许可声明」**——即保留本 NOTICE 文件。

---

*免责声明：本文件为技术梳理，不构成法律意见。商业出售源码前请生成权威 SBOM 并咨询法务确认合规与再授权范围。*
