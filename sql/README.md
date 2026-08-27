# SQL 迁移文件

所有数据库 Schema / 增量迁移 / 种子数据集中在此目录。

## 文件清单

| # | 文件 | 用途 |
|---|---|---|
| 1 | `docmind-init.sql` | **完整初始库 dump**（建库 + 用户/知识库/文档/Chunk/对话/AI 配置/MCP 全套主体表 + 种子数据）|
| 2 | `agent-crew-schema.sql` | Multi-Agent Crew 任务追踪表（`agent_task` / `agent_step`）|
| 3 | `agent-crew-fork-migration.sql` | 为 `agent_task` 增加 fork 关系字段，支持 Time-Travel 回放 |
| 4 | `persona-schema.sql` | Soul 人格表 `system_persona` + 5 个预置人格 |
| 5 | `llm-provider-schema.sql` | 跨厂商 LLM Provider 表 + 5 个预置 Provider 模板 |

## 全量初始化顺序（新机器）

```bash
# 在项目根目录（与 src/ 同级）执行
mysql -uroot -proot         < sql/docmind-init.sql            # 1
mysql -uroot -proot docmind < sql/agent-crew-schema.sql       # 2
mysql -uroot -proot docmind < sql/agent-crew-fork-migration.sql # 3
mysql -uroot -proot docmind < sql/persona-schema.sql          # 4
mysql -uroot -proot docmind < sql/llm-provider-schema.sql     # 5
```

> 增量迁移类的文件以后请按 `NNNN-描述.sql` 命名（如 `0006-add-xxx.sql`），保留时间顺序。
