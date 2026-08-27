# MindCrew 完全离线部署方案

目标：在**完全断网**的内网环境运行 MindCrew，所有 AI 能力指向本地自建服务，零公网依赖。

本方案配套的代码改造已完成：所有 AI 端点都抽成了可配（环境变量 / `application.yml` / 「AI 配置」热切），
不再有写死的云地址；专有协议的能力（ASR/TTS）加了 OpenAI 兼容旁路或可配网关。

---

## 0. 一句话结论

- **配置首选「大模型 Provider」页面可视化操作**（管理员 → 大模型 Provider）：上半部 Provider 卡片管对话+向量，下半部模型端点卡片管 图片理解 / 视频理解 / 语音识别 / 语音合成 / 重排序 / 语音对话。每个端点选 **providerType（dashscope / openai_compatible / local）+ 填 baseUrl/模型名**，切换即时生效，**无需改环境变量、无需重启**。
- **核心链路（对话 / 知识库 RAG / 图片·PDF OCR / 向量 / 重排 / 音视频转写）→ 完全可离线**：在 Provider 页面把各端点 providerType 切到 `openai_compatible`/`local`、baseUrl 指向本地服务即可。
- **环境变量只作「启动兜底默认」**：页面没配端点时用 yml/env 的值。CI/无人值守可用 env 预置。
- **实时语音 WS / 声音克隆**走 DashScope 专有协议，仍由 env 控制（boot 注入），离线需兼容网关或关闭——不影响以上核心。
- 基础设施（MySQL / Milvus / MinIO / FFmpeg）本来就能本地部署。

---

## 1. 协议分类（为什么有的容易有的难）

| 能力 | 协议 | 离线难度 |
|---|---|---|
| 对话 LLM、Embedding、图片 OCR/视觉、视频抽帧识别、视频整体理解 | **OpenAI 兼容** `/v1/...` | ★ 改 base-url 即可 |
| 重排 Rerank | 有 `protocol` 开关（dashscope / openai·jina·cohere） | ★ 切 protocol + url |
| 音视频转写 ASR | DashScope 专有异步 **/ 已加 OpenAI 旁路** | ★★ 切 `asr.protocol=openai` |
| 实时语音识别、TTS 合成、声音克隆 | DashScope 专有 WebSocket | ★★★ 需兼容网关，或关闭 |

---

## 2. 本地模型栈选型（推荐）

全部容器化，跑在内网一台带 GPU 的机器即可。端口可自定，下面是示例。

| 能力 | 本地方案 | 暴露接口 | 示例端口 / 模型 |
|---|---|---|---|
| 对话 LLM | **vLLM** 或 Ollama | OpenAI `/v1/chat/completions` | `:8001` · Qwen2.5-7B/14B-Instruct |
| 视觉 / OCR / 视频抽帧 | **vLLM**（多模态） | OpenAI `/v1/chat/completions`（image_url） | `:8002` · Qwen2.5-VL-7B-Instruct |
| 视频整体理解（video_url） | 同上 VL（需支持 video_url，能力有限可降级为「抽帧+ASR」legacy 模式） | OpenAI 兼容 | `:8002` |
| Embedding | **TEI**(text-embeddings-inference) 或 Xinference | OpenAI `/v1/embeddings` | `:8003` · bge-large-zh-v1.5（**注意维度**，见 §4） |
| Rerank | **TEI** 或 Xinference | `/rerank`（jina/cohere 风格） | `:8004` · bge-reranker-v2-m3 |
| ASR 转写 | **faster-whisper-server** 或 FunASR | OpenAI `/v1/audio/transcriptions` | `:8000` · faster-whisper-large-v3 |
| 实时语音 / TTS（可选） | 需自建协议兼容 WS 网关（工作量大）；否则关闭 | — | — |

> 没有 GPU 也能跑：Ollama(CPU) + faster-whisper(CPU int8)，只是慢。

---

## 3. 配置方式（首选：Provider 页面可视化）

进入 **管理员 → 大模型 Provider** 页面，全程点选完成离线切换。优先级：**页面端点配置 > 环境变量 > 内置默认**。

### 3.A 可视化配置对照表（推荐）

| 能力 | 在页面哪里配 | 关键设置（离线） |
|---|---|---|
| 对话 LLM | 上半部「Provider」卡片 → 新建/编辑并设为激活 | baseUrl=`http://llm:8001/v1`、chatModel=本地模型、apiKey 占位 |
| 向量 Embedding | 同上 Provider 卡片 | embeddingModel=本地、**embeddingDim=本地维度（见 §4）** |
| 图片理解 / OCR | 「图片理解」端点卡片 | providerType=`openai_compatible`、baseUrl=`http://vl:8002`、模型=Qwen2.5-VL |
| 视频理解 | 「视频理解」端点卡片 | 同上 VL（需支持 video_url） |
| 语音识别 ASR | 「语音识别」端点卡片 | providerType=`openai_compatible`/`local`（即走本地 Whisper 旁路）、baseUrl=`http://asr:8000`、模型=faster-whisper |
| 重排序 Rerank | 「重排序」端点卡片 | providerType=`openai_compatible`、baseUrl=本地 rerank 地址、模型=bge-reranker |
| 语音合成 TTS | 「语音合成」端点卡片 | baseUrl=本地 TTS WS 地址（专有协议，需兼容网关） |

> **providerType 就是协议开关**：选 `dashscope`=走阿里云专有格式；选 `openai_compatible`/`local`=走本地通用格式（ASR 自动改用 `/v1/audio/transcriptions` 旁路，rerank 改用通用 rerank 格式）。
> 端点 baseUrl 留空时，自动回退到下面的环境变量/`llm.*`。

### 3.B 环境变量（兜底 / 启动默认，可选）

无人值守或想用 env 预置时按下表 export。**留空的项会自动回退到 `llm.*`**，所以最少只配 `LLM_*` + Embedding 就能让对话/OCR 全离线。

#### 3.B.1 必配（核心链路）

| 环境变量 | 默认（云） | 离线设为 |
|---|---|---|
| `LLM_BASE_URL` | dashscope compatible-mode | `http://llm:8001/v1` |
| `BAILIAN_API_KEY` | （阿里云 Key） | 本地服务随意占位，如 `sk-local` |
| `LLM_CHAT_MODEL` | `qwen-plus` | 本地模型名，如 `Qwen2.5-14B-Instruct` |
| `EMBEDDING_MODEL` | `text-embedding-v3` | 本地，如 `bge-large-zh-v1.5` |
| `EMBEDDING_DIM` | `1024` | **必须等于本地 embedding 维度**（见 §4） |

#### 3.B.2 视觉 / 视频（不配则回退 llm.*；页面端点配置优先于此）

| 环境变量 | 离线设为 |
|---|---|
| `VISION_BASE_URL` / `VISION_API_KEY` / `VISION_MODEL` | `http://vl:8002/v1` / `sk-local` / `Qwen2.5-VL-7B-Instruct` |
| `VIDEO_BASE_URL` / `VIDEO_API_KEY` / `VIDEO_MODEL` | 同上 VL（或保持 legacy 抽帧模式） |

#### 3.B.3 重排 / ASR（推荐改用页面端点的 providerType；以下为 env 兜底）

| 环境变量 | 离线设为 |
|---|---|
| `RERANKER_PROTOCOL` | `openai`（或 `jina`/`cohere`，按本地服务） |
| `RERANKER_API_URL` | `http://rerank:8004/rerank` |
| `RERANKER_MODEL` | `bge-reranker-v2-m3` |
| `ASR_PROTOCOL` | `openai` |
| `ASR_BASE_URL` | `http://asr:8000` |
| `ASR_MODEL` | `Systran/faster-whisper-large-v3` |

> 页面端点把 reranker / asr 的 providerType 设成 `openai_compatible`/`local` 时，上面的 `*_PROTOCOL` 会被自动覆盖，无需再设 env。
> ASR 的 openai 旁路是**后端先下载音频再上传给本地 ASR**，所以音频 URL 只需后端可达（内网 MinIO 预签名即可），不像 DashScope 那样要公网。

#### 3.B.4 语音（实时 WS / 声音克隆仍走 env；离线建议关闭或指向兼容网关）

| 环境变量 | 说明 |
|---|---|
| `VOICE_REALTIME_WS_URL` | 实时语音/ASR WS；指向本地兼容网关，否则该功能不可用 |
| `TTS_WS_URL` | TTS 合成 WS；同上 |
| `VOICE_CLONE_API_URL` | 声音克隆；专有协议，离线一般关闭 |

> 注：TTS 合成（`TTS_WS_URL`）现在也可在「语音合成」端点卡片里配 baseUrl，页面优先。仅**实时语音 WS** 与**声音克隆**仍只读 env（boot 注入）。

#### 3.B.5 基础设施 & 联网搜索

| 环境变量 | 离线设为 |
|---|---|
| `STORAGE_TYPE` | `minio`（不要用 oss） |
| `MINIO_ENDPOINT` | `http://minio:9000` |
| `MILVUS_HOST` / `MILVUS_PORT` | 本地 Milvus |
| `MILVUS_DIMENSION` | **必须等于 `EMBEDDING_DIM`** |
| 数据库 `spring.datasource.url` | 内网 MySQL |
| `DOCMIND_WEB_SEARCH_ENABLED` | `false`（联网搜索是公网能力，断网必关） |

---

## 4. ⚠ 关键坑：Embedding 维度必须一致

`EMBEDDING_DIM`、`MILVUS_DIMENSION`、本地 embedding 模型输出维度**三者必须相等**，否则向量入库/检索直接报错或召回全乱。

- `text-embedding-v3` = 1024；`bge-large-zh-v1.5` = 1024（刚好一致，省事）。
- 若换 `bge-m3`(1024) 仍可；换其他维度的模型，要同步改这两个变量，**且换模型后历史向量需要重新嵌入**（旧维度数据作废）。

建议离线首选 **1024 维**的中文 embedding，避免重建知识库。

---

## 5. 基础设施本地化

| 组件 | 离线部署 |
|---|---|
| MySQL | 内网自建，导入项目 `sql/` 下所有 schema（含 `workflow-schema.sql`） |
| Milvus | 官方 docker-compose standalone，内网部署 |
| MinIO | 内网部署，`STORAGE_TYPE=minio` |
| FFmpeg | 后端镜像内置（`apt-get install ffmpeg`），`video.ffmpeg-path` 默认 `ffmpeg` |
| 模型权重 | **断网前**先在能上网的机器下载好 HuggingFace/ModelScope 权重，拷进内网挂载给 vLLM/TEI/whisper |

---

## 6. 断网前核对清单

- [ ] 本地模型服务全部起好，`curl http://.../v1/models` 能通
- [ ] 镜像、模型权重、Maven/npm 依赖全部拉到内网（构建用离线仓库或预构建镜像）
- [ ] `EMBEDDING_DIM == MILVUS_DIMENSION ==` 本地 embedding 维度
- [ ] `STORAGE_TYPE=minio`、`DOCMIND_WEB_SEARCH_ENABLED=false`
- [ ] 语音功能：要么接好兼容网关，要么在前端/配置中关闭入口
- [ ] 全部 `*_BASE_URL` 指向内网地址，无任何 `dashscope.aliyuncs.com` 残留

---

## 7. 验证（断网后逐项点一遍）

| 功能 | 验证动作 | 预期 |
|---|---|---|
| 对话 | 发一条消息 | 正常流式回复（打到本地 LLM） |
| 知识库导入 | 传一个 PDF | 解析 + 向量入库成功 |
| RAG 问答 | 基于知识库提问 | 召回 + 重排 + 回答正常 |
| 图片 OCR | 传一张带文字的图 | 提取出文字（打到本地 VL） |
| 音视频转写 | 传一段音频/视频 | 出转写文本（语音识别端点 providerType=openai_compatible） |
| 语音通话 | （若启用）发起通话 | 需兼容网关；否则应已关闭，不报错 |

> 切换方式：以上除联网搜索/实时语音外，都在 **管理员 → 大模型 Provider** 页面点选 providerType + baseUrl 即可，切完即时生效、无需重启。

---

## 8. 离线下的功能取舍小结

| 功能 | 离线状态 |
|---|---|
| 对话 / RAG / 知识库 / 图片·PDF·音视频文档摄入 | ✅ 完全可用 |
| 图片 OCR、视频抽帧识别 | ✅ 完全可用 |
| 视频「整体理解」 | ✅ 若本地 VL 支持 video_url；否则自动可走 legacy 抽帧+ASR |
| 联网搜索 | ❌ 公网能力，关闭 |
| 实时语音通话 / TTS / 声音克隆 | ⚠ 需自建协议兼容网关，否则关闭（不影响以上核心） |
