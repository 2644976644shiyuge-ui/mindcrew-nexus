# AI PPT API 接入说明

MindCrew 已将 PPT 生成封装为可切换 Provider。用户只需描述需求，系统内部负责创建任务、轮询、下载 PPTX 和失败回退。

## 阿里云 Qwen-Doc-Turbo

推荐作为国内企业默认服务商：

- AI PPT 服务商：`阿里云 Qwen-Doc-Turbo`
- API 地址：可留空，或填写业务空间的 `compatible-mode/v1` 地址
- 服务商 API Key：阿里云百炼 API Key
- 千问 PPT 模式：`general`
- 千问 PPT 模板：`internet_01`
- 生成超时：建议 `300`～`600` 秒

MindCrew 调用 `qwen-doc-turbo` 的流式 Chat Completions 接口，传入 `skill.type=ppt`，从流式 `content` 中取得最终 PPT 下载地址并下载 PPTX。

`general` 模式适合企业汇报并强调可编辑性；`creative` 模式视觉效果更丰富，但页面主要为图片型内容。

## Gamma 官方 API

后台配置：

- 启用 PPT Agent：开启
- AI PPT 服务商：`Gamma 官方 API`
- API 地址：留空或 `https://public-api.gamma.app`
- 服务商 API Key：Gamma API Key
- 服务商主题 ID：可选
- 生成超时：建议 `180`～`300` 秒

MindCrew 会调用 `POST /v1.0/generations`，轮询 `GET /v1.0/generations/{id}`，任务完成后下载 `exportUrl` 返回的 PPTX。

## 自定义直出 API

`POST ppt_generation.api-url`

请求头：

```text
Authorization: Bearer <ppt_generation.api-key>
Content-Type: application/json
```

请求体包含：

```json
{
  "title": "演示文稿标题",
  "prompt": "分页内容",
  "markdown": "分页内容",
  "planner": {
    "provider": "dashscope",
    "model": "qwen-plus",
    "baseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1",
    "apiKey": ""
  },
  "options": {
    "language": "zh-CN",
    "quality": "commercial",
    "generationMode": "auto",
    "visualStyle": "business",
    "audience": "管理层",
    "purpose": "经营汇报",
    "editable": true,
    "includeSpeakerNotes": true,
    "visualPolicy": "prefer-diagrams-charts-and-business-illustrations"
  },
  "branding": {
    "companyName": "公司名称",
    "deckStyle": "商务简洁",
    "primaryColor": "#315EFB",
    "accentColor": "#F59E0B"
  }
}
```

成功响应必须为：

```text
Content-Type: application/vnd.openxmlformats-officedocument.presentationml.presentation
```

响应体为 PPTX 二进制文件。外部服务失败时，MindCrew 可按配置自动回退到内置安全渲染器。

该协议适合自建 PPT Agent，也可以在外部增加一个轻量适配网关，将 AiPPT 等多步骤厂商 API 统一转换为一次请求返回 PPTX。

## 生产要求

- API Key 只允许保存在服务端配置。
- 开启失败自动回退，避免服务商故障阻断导出。
- 服务商必须返回真正可编辑的 PPTX，不能返回图片拼接文件。
- 上线前验证中文字体、图表、长文本、并发限流、超时和下载链接有效期。
