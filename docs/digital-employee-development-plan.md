# 数字员工功能开发计划

**项目**：MindCrew 企业智能知识中枢  
**文档性质**：产品 + 研发实施计划  
**参考 UI**：数字员工选择页（暗色卡片网格）、智能体可视化配置页（三栏表单）  
**更新日期**：2026-07-01  

---

## 一、目标与范围

### 1.1 业务目标

在 MindCrew 现有 **知识库、Agentic RAG、技能包、组织权限** 能力之上，建设 **「数字员工」** 模块：

| 角色 | 能力 |
|:-----|:-----|
| **管理员** | 创建/编辑智能体，可视化配置能力与知识范围，**发布** 到组织，**按部门/职位授权** 给员工使用 |
| **部门员工** | 在「我的数字员工」中看到被授权的智能体，进入对话，完成问答、方案撰写、合同辅助、招投标材料处理、PPT 大纲/内容生成等日常办公任务 |

### 1.2 与现有模块的关系

```
数字员工（Digital Employee）
  ├── 身份与设定：名称、头像、简介、系统 Prompt（可「AI 优化」）
  ├── 模型与能力：大模型、联网搜索、长期记忆、工具/MCP
  ├── 知识范围：绑定知识库 + 「仅从知识库回答」开关
  ├── 数据扩展：可选业务数据库（NL2SQL / 结构化查询，复用现有规划）
  ├── 场景模板：合同、PPT、招投标、通用办公等可视化编排
  ├── 发布与权限：草稿 / 已发布；公开（全员或市场）/ 受限（部门 ACL）
  └── 运行时：独立会话、用量统计、审计

复用：SkillPack（场景指令片段）、Soul（口吻）、Collection ACL、RAG 七步链路、文档解析与 MinIO
演进：SkillPack 可逐步收敛为「数字员工内置场景模板」或保持双轨（全局技能包 + 员工专属模板）
```

### 1.3 非目标（首期可不做或二期）

- 智能体市场对外开放（C 端）；首期仅 **企业内发布 + 部门授权**
- 多智能体 Crew 全自动编排为「一个数字员工」的默认形态（可作为 **高级模式** 二期接入）
- 本地进程型「本地数字员工」（参考截图中的 Gateway 形态）—— 首期统一为 **服务端托管智能体**

---

## 二、参考界面与信息架构

### 2.1 员工端：选择数字员工（参考图 1）

| 区域 | 要素 | 说明 |
|:-----|:-----|:-----|
| 顶栏 | 团队 / 控制台 / 设置 | 与现有 MindCrew 导航整合；「团队」= 数字员工列表 |
| 标题区 | 选择数字员工 | 副文案可强调「已授权给你的智能同事」 |
| 操作 | 搜索、控制台、创建数字员工 | 员工仅「搜索 + 进入」；**创建** 仅管理员可见 |
| 卡片网格 | 头像、名称、状态、类型标签 | 状态：运行中 / 已停用；类型：企业数字员工 |
| 卡片指标 | 会话数、Token/用量、最近活跃 | 数据来自 `digital_employee_session` 与用量表聚合 |
| 底栏 | 版本、模型、会话上下文占用 | 进入对话后展示，与现有 Chat 一致 |

**交互**：点击卡片 → 进入该数字员工专属对话页（预置 Prompt、知识库、场景工具）。

### 2.2 管理端：智能体可视化配置（参考图 2）

三栏布局建议映射为 **单页 Wizard 或 Tab**，字段与后端模型一一对应：

| 左栏（身份） | 中栏（能力） | 右栏（发布） |
|:-------------|:-------------|:-------------|
| 头像上传 | 大模型选择（Provider + 模型） | 权限：公开 / 私密（企业内） |
| 智能体名称 | 联网搜索开关 + 说明文案 | 授权：部门树多选、职位、指定用户 |
| 智能体简介 | 长期记忆开关 | 发布状态：草稿 / 已发布 |
| 智能体设定（System Prompt） | 知识库：多选 + 仅从 KB 回答 | 生效范围说明（继承 KB ACL 与员工授权交集） |
| **AI 优化** 按钮 | 知识源：URL、文档（走现有上传解析） | |
| | 数据库连接（二期 NL2SQL） | |
| | **场景能力包**（可视化，见第三节） | |

底栏：**关闭 | 保存（草稿）| 发布**。

---

## 三、企业办公场景与可视化配置

管理员不应只填一大段 Prompt，而应通过 **「场景模板 + 可编辑节点」** 拼装专业能力。

### 3.1 场景模板清单（首期 6 + 通用）

| 场景 ID | 名称 | 用户价值 | 可视化配置项（示例） |
|:--------|:-----|:---------|:---------------------|
| `general_qa` | 常规问答 | 制度、流程、产品知识 | 回答长度、是否强制引用来源、反问澄清 |
| `contract_draft` | 合同拟定 | 根据要点生成合同条款 | 合同类型、适用法律、必备条款 checklist、风险等级措辞 |
| `contract_review` | 合同审查 | 上传合同 → 风险点与修改建议 | 审查维度（付款/违约/知识产权/保密）、输出格式（批注列表/修订稿） |
| `ppt_authoring` | PPT 撰写 | 大纲 → 分页要点 → 可导出 | 页数范围、受众、风格（汇报/培训）、是否绑定企业 PPT 模板知识库 |
| `bid_parse` | 投标文件解析 | 解析招标文件/投标文件结构 | 关注章节（资质/技术/商务）、抽取字段映射表 |
| `bid_write` | 招标文件撰写 | 根据招标要点生成章节草稿 | 招标类型、评分项、格式规范文档绑定 |
| `doc_check` | 材料检查 | 清单核对、格式与必填项 | 检查清单（可来自知识库 Excel）、严重级别 |

每个场景模板对应：

1. **预设 System 片段**（可合并进最终 Prompt）  
2. **输出 Schema**（JSON / Markdown 章节标题），便于前端结构化展示与导出  
3. **推荐工具开关**：仅 RAG / RAG+联网 / 文档上传解析  
4. **推荐知识库标签**（创建向导时提示管理员挂载）

### 3.2 可视化编排（配置存储）

建议新增 JSON 字段 `scenario_config`（版本化 schema），示例结构：

```json
{
  "primaryScenario": "contract_review",
  "scenarios": ["contract_review", "general_qa"],
  "nodes": [
    { "id": "role", "type": "prompt_block", "title": "角色", "content": "..." },
    { "id": "output_format", "type": "output_template", "format": "markdown_sections", "sections": ["摘要", "风险点", "建议条款"] },
    { "id": "kb_only", "type": "toggle", "value": true }
  ],
  "aiOptimizedAt": "2026-07-01T10:00:00Z"
}
```

**AI 优化**：调用现有大模型，输入「名称 + 简介 + 选定场景」，输出优化后的 `智能体设定` 与 `scenario_config` 建议，管理员确认后写入（需审计日志）。

### 3.3 对话内能力（员工侧）

| 能力 | 实现路径 |
|:-----|:---------|
| 流式问答 + 溯源 | 复用 Agentic RAG，会话绑定 `employee_id` + 知识库 ID 列表 |
| 上传合同/标书解析 | 复用文档上传 → 切片 → 临时会话附件或短期 Collection |
| 出方案（结构化） | Prompt 中注入 `output_template` + 可选 Crew 单轮「写作 Agent」 |
| 出 PPT | 首期：生成 **Markdown 大纲 + 每页 speaker notes**；导出 `.md` / 对接 **python-pptx 或 Office 模板服务（二期）** |
| 检查报告 | 固定输出表格：检查项 / 结果 / 依据来源 |

---

## 四、权限与发布模型

### 4.1 生命周期

```
草稿 → 已发布 → 已下线（不可新开会话，历史可读）
```

- **保存**：仅管理员与创建者可改配置  
- **发布**：校验必填（名称、模型、至少一场景或 Prompt、授权范围）  
- **授权**：与知识库 ACL **求交集**——员工仅能使用「被授权的数字员工」且「该员工绑定的 KB 中自己有权限的部分」

### 4.2 授权维度（复用组织模块）

| 类型 | 说明 |
|:-----|:-----|
| 部门 | 支持上级部门继承（与 `CollectionAclService` 一致） |
| 职位 | 可选 |
| 用户 | 可选单独加人 |
| 公开（企业内） | 登录用户均可见卡片；仍受 KB ACL 约束 |

### 4.3 角色权限扩展

| 角色 | 数字员工权限 |
|:-----|:-------------|
| admin | 全部 CRUD、发布、授权、统计 |
| auditor | 只读配置、用量与审计 |
| user | 列表（授权过滤）、对话、导出本会话 |

---

## 五、数据模型（建议）

### 5.1 核心表

| 表名 | 用途 |
|:-----|:-----|
| `digital_employee` | 主实体：名称、头像、简介、system_prompt、model_config_json、feature_flags、scenario_config、status、visibility、created_by |
| `digital_employee_knowledge` | 多对多：employee_id, collection_id, kb_only_reply |
| `digital_employee_acl` | 授权：employee_id, principal_type(dept/position/user), principal_id, permission(use/manage) |
| `digital_employee_session` | 会话：user_id, employee_id, title, last_active_at |
| `digital_employee_message` | 消息：可复用现有 `chat_message` 并增加 `employee_id`，或独立表保持一致性 |
| `digital_employee_usage_daily` | 聚合：会话数、token、活跃用户（供卡片展示） |

### 5.2 与 SkillPack 整合策略

- **方案 A（推荐）**：数字员工 **内嵌** `skill_instruction` 字段；全局 SkillPack 作为「导入模板」  
- **方案 B**：`digital_employee.skill_pack_id` 外键，简单但多场景组合弱  

首期采用 **方案 A + 场景模板表 `scenario_template`（只读种子数据）**。

---

## 六、API 设计概要

### 6.1 管理端 `/api/admin/digital-employees`

| 方法 | 路径 | 说明 |
|:-----|:-----|:-----|
| GET | `/` | 分页列表 + 状态筛选 |
| POST | `/` | 创建草稿 |
| GET | `/{id}` | 详情（含 KB、ACL、场景配置） |
| PUT | `/{id}` | 更新 |
| POST | `/{id}/publish` | 发布 |
| POST | `/{id}/unpublish` | 下线 |
| POST | `/{id}/optimize-prompt` | AI 优化设定 |
| GET | `/scenario-templates` | 场景模板列表 |
| PUT | `/{id}/acl` | 部门/职位/用户授权 |

### 6.2 员工端 `/api/digital-employees`

| 方法 | 路径 | 说明 |
|:-----|:-----|:-----|
| GET | `/mine` | 当前用户可见列表（含卡片统计） |
| GET | `/{id}` | 详情（不含管理字段） |
| POST | `/{id}/sessions` | 新建会话 |
| GET | `/{id}/sessions` | 历史会话 |
| POST | `/{id}/chat` | SSE 流式对话（body: sessionId, message, attachments?） |
| POST | `/{id}/export` | 导出方案/PPT 大纲（Markdown / 二期 PPTX） |

### 6.3 对话链路改造点

在现有 RAG Chat 入口增加上下文：

- `DigitalEmployeeRuntimeContext`：合并 system_prompt、scenario 输出模板、feature flags（web/memory）、collectionIds  
- 权限：`DigitalEmployeeAclService.canUse(userId, employeeId)` + `CollectionAclService` 过滤 KB  

---

## 七、前端开发计划

### 7.1 路由与页面

| 路径 | 页面 | 角色 |
|:-----|:-----|:-----|
| `/digital-employees` | 选择数字员工（暗色网格，可配置主题） | user+ |
| `/digital-employees/:id/chat` | 对话页（复用 Chat 组件 + 员工顶栏） | user+ |
| `/admin/digital-employees` | 列表管理 | admin |
| `/admin/digital-employees/create` | 三栏可视化配置 | admin |
| `/admin/digital-employees/:id/edit` | 同创建页 | admin |

### 7.2 组件拆分

- `DigitalEmployeeCard`：指标、状态、标签  
- `DigitalEmployeeEditor`：左中右三栏 + 底栏操作  
- `ScenarioTemplatePicker`：场景多选 + 各场景动态表单  
- `KnowledgeBindingPanel`：复用知识库选择器 + URL/文档上传  
- `AclDepartmentTree`：复用组织树  
- `EmployeeChatHeader`：简介、场景切换（若员工配置了多场景）  

### 7.3 与现有前端整合

- 菜单：在「AI 工具」或一级「数字员工」  
- 权限：`meta.roles` / 后端菜单接口增加 `digital_employee`  
- 流式：复用现有 SSE 客户端与来源展示组件  

---

## 八、后端开发计划

### 8.1 模块包结构建议

```
com.mindcrew.digitalemployee
  ├── controller (admin / user)
  ├── service (CRUD, publish, runtime, stats)
  ├── acl (DigitalEmployeeAclService)
  ├── runtime (DigitalEmployeeChatFacade → 现有 RAG Pipeline)
  ├── scenario (ScenarioTemplateRegistry, PromptComposer)
  └── dto / entity / mapper
```

### 8.2 关键实现任务

1. Flyway/SQL 迁移脚本与实体  
2. ACL 与列表过滤（「我的数字员工」）  
3. 发布校验与状态机  
4. Chat Facade 注入员工上下文  
5. 附件会话：招标/合同文件短期解析  
6. `optimize-prompt` 调用与审计  
7. 日聚合任务写 `digital_employee_usage_daily`  

---

## 九、分阶段里程碑

### Phase 0：需求与设计（1 周）

- [ ] 评审本文档与字段定稿  
- [ ] UI 原型（Figma）：列表页 + 配置页 + 对话页  
- [ ] 场景模板 6 份的 Prompt 与输出格式样例  

### Phase 1：MVP（3～4 周）

- [ ] 数据表 + 管理端 CRUD + 发布 + 部门授权  
- [ ] 员工端列表 + 进入对话（通用问答 + 单场景）  
- [ ] 绑定多知识库 + 仅从 KB 回答  
- [ ] 联网 / 长期记忆开关生效  
- [ ] 卡片基础统计（会话数、最近活跃）  

**验收**：管理员发布「法务助手」授权法务部；员工可见、可问答、回答带溯源。

### Phase 2：场景化与可视化（3 周）

- [ ] 场景模板选择 + 动态配置表单  
- [ ] 合同拟定/审查、招投标解析、PPT 大纲输出  
- [ ] AI 优化 Prompt  
- [ ] 对话内导出 Markdown 方案  

**验收**：上传一份合同样例，输出结构化风险点；选择 PPT 场景生成 10 页大纲。

### Phase 3：体验与运营（2 周）

- [ ] 暗色主题列表页打磨（对齐参考图 1）  
- [ ] 用量统计、审计日志、管理员控制台  
- [ ] 反馈点赞点踩关联到员工会话  

### Phase 4：增强（按需）

- [ ] PPTX 文件生成服务  
- [ ] 数据库 / NL2SQL 绑定（与 `商用级优化方案-RAG-NL2SQL-可视化` 对齐）  
- [ ] 数字员工绑定 Crew 调研一键生成长篇报告  
- [ ] 钉钉机器人路由到指定数字员工  

---

## 十、非功能需求

| 项 | 要求 |
|:---|:-----|
| 安全 | 所有列表按 ACL 过滤；对话仅访问授权 KB；操作写审计日志 |
| 性能 | 列表页统计异步聚合；对话与现网 RAG 同 SLO |
| 可配置 | 模型、温度、RAG 参数仍走 AI 配置中心，员工级可覆盖模型选择 |
| 兼容 | 不破坏现有 SkillPack / 普通 Chat；员工会话单独 type 标识 |

---

## 十一、测试要点

- 未授权用户不可见、不可 chat（403）  
- 发布下线后会话行为符合预期  
- KB 仅部分有权限时，检索范围正确、无越权切片  
- 「仅从知识库回答」在来源不足时拒答或明确提示  
- 各场景输出格式符合模板（契约测试 + 人工抽检）  
- AI 优化前后配置版本可追溯  

---

## 十二、风险与依赖

| 风险 | 缓解 |
|:-----|:-----|
| SkillPack 与数字员工能力重叠 | 明确产品话术：SkillPack=对话内快捷技能；数字员工=发布实体+授权 |
| PPT 真文件生成复杂 | 首期 Markdown，二期独立微服务 |
| 招投标解析准确率 | 强依赖文档解析质量 + 场景 Prompt；提供「人工校对」导出结构 |
| 长合同上下文超限 | 分片检索 + 附件摘要预处理 |

**依赖**：组织部门数据完整、Milvus/MySQL 正常、至少一个可用 LLM Provider。

---

## 十三、附录：与参考截图字段对照

| 截图字段 | 本方案落点 |
|:---------|:-----------|
| 选择数字员工 / 搜索 | `GET /api/digital-employees/mine?q=` |
| 运行中 / 本地数字员工 | `status=published` / 标签 `hosted` |
| 会话数、Token、时长 | `digital_employee_usage_daily` |
| 智能体名称 / 简介 / 设定 | `digital_employee` 表 + AI 优化接口 |
| 大模型选择 | `model_config_json` + Provider 表 |
| 联网搜索 / 长期记忆 | `feature_flags` → RAG 工具路由 |
| 知识库 + 只从知识库回复 | `digital_employee_knowledge` |
| URL / PDF 列表 | 走文档上传与 `digital_employee` 关联文档或 KB |
| 数据库 | Phase 4 NL2SQL |
| 公开 / 私密 | `visibility` + `digital_employee_acl` |
| 保存 / 发布 | 状态机 + 校验器 |

---

*文档维护：产品/研发评审后更新 Phase 勾选与工期。*