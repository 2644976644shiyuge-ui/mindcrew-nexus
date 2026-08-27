-- ═══════════════════════════════════════════════════════════════
-- MindCrew · 全部建表/迁移合并文件（自动生成，请勿手改）
-- 顺序来源: sql/docker-init/00-init-all.sh 的 ORDER 数组
-- 用法: 在 RDS 上连接后直接整体运行；或 mysql ... < docmind-all-in-one.sql
-- 字符集: 每张表已显式 utf8mb4，无需关心库默认字符集
-- 生成时间: 2026-06-10 22:54:29
-- ═══════════════════════════════════════════════════════════════

CREATE DATABASE IF NOT EXISTS docmind DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE docmind;


-- ───────────────────────────────────────────────────────────────
-- [1/23] docmind-init.sql
-- ───────────────────────────────────────────────────────────────
SET NAMES utf8mb4;
CREATE DATABASE IF NOT EXISTS docmind;
use docmind;
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for kb_chunk
-- ----------------------------
DROP TABLE IF EXISTS `kb_chunk`;
CREATE TABLE `kb_chunk`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `kb_id` bigint NOT NULL COMMENT '所属知识库ID',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '切片文本内容',
  `chunk_index` int NULL DEFAULT NULL COMMENT '切片顺序索引',
  `metadata` json NULL COMMENT '元数据(页码、章节标题等)',
  `vector_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'Milvus中对应的向量ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_kb_id`(`kb_id` ASC) USING BTREE,
  FULLTEXT INDEX `ft_kb_chunk_content_ngram`(`content`) WITH PARSER `ngram`
) ENGINE = InnoDB AUTO_INCREMENT = 25 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '文档切片表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for kb_parent_chunk
-- ----------------------------
DROP TABLE IF EXISTS `kb_parent_chunk`;
CREATE TABLE `kb_parent_chunk` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '父切片ID',
  `kb_id` bigint NOT NULL COMMENT '所属知识库/文档ID',
  `parent_index` int NOT NULL COMMENT '父切片在文档中的顺序',
  `content` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '父切片正文',
  `chapter` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '章节路径',
  `page_start` int NULL COMMENT '起始页',
  `page_end` int NULL COMMENT '结束页',
  `metadata` json NULL COMMENT '父切片元数据',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_parent_kb_index` (`kb_id`, `parent_index`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库父切片（仅上下文还原）';

-- ----------------------------
-- Records of kb_chunk
-- ----------------------------
INSERT INTO `kb_chunk` VALUES (1, 4, '# DocMind 技术文档：Agentic RAG 与 MCP 能力说明\n\n## 文档目标\n\n本文档解释 DocMind 中 Agentic RAG 和 MCP 的职责划分、内部调用方式、对外暴露方式，以及它们在问答系统中的协作关系。\n\n## 一、什么是 Agentic RAG\n\n在 DocMind 中，Agentic RAG 指系统不再只是“拿到问题后固定检索再固定生成”，而是会根据问题类型动态决定：\n\n- 是否需要改写查询\n- 应该用哪些工具\n- 是否需要知识库检索\n- 是否需要关键词精查\n- 是否需要网页搜索\n- 是否需要读取或写入长期记忆\n\n这种方式让系统更像一个受约束的任务执行器，而不是单纯的模板流水线。\n\n## 二、DocMind 中的主要工具\n\n### 1. `doc_search`\n\n用途：\n\n- 执行语义向量检索\n- 适合概念解释、相似表达、模糊提问\n\n输入示例：', 0, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"example\"}', NULL, '2026-04-02 17:52:57');
INSERT INTO `kb_chunk` VALUES (2, 4, '- 查询词\n- topK\n- 可选的知识库范围\n\n### 2. `keyword_search`\n\n用途：\n\n- 执行关键词检索\n- 适合查编号、专有名词、配置项、固定短语\n\n### 3. `web_search`\n\n用途：\n\n- 补充外部网页信息\n- 适合实时更新、外部公告、版本变化、公共资料\n\n### 4. `recall_memory`\n\n用途：\n\n- 读取用户长期记忆\n- 适合追问、个性化回答、延续上下文\n\n### 5. `store_memory`\n\n用途：\n\n- 将用户明确表达的长期偏好写入 Redis\n- 例如角色、称呼、表达偏好、关注主题\n\n## 三、问题进入系统后的决策过程\n\n系统会先做问题路由。常见意图包括：\n\n- 普通知识查询\n- 精确检索\n- 实时信息查询\n- 复合问题\n- 追问问题\n\n不同意图会触发不同工具组合。例如：', 1, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"example\"}', NULL, '2026-04-02 17:52:57');
INSERT INTO `kb_chunk` VALUES (3, 4, '- 一般查询：`doc_search + keyword_search`\n- 实时问题：`doc_search + keyword_search + web_search`\n- 追问问题：`recall_memory + doc_search`\n\n## 四、内部调用与外部调用的区别\n\n### 内部调用\n\nDocMind 应用内部并不会通过 MCP 协议去回调自己，而是：\n\n- 直接注入工具对应的 Spring Bean\n- 在 `DocMindAgent` 中直接调用工具方法\n\n优点是：\n\n- 调用链更短\n- 性能更稳定\n- 更容易调试\n\n### 外部调用\n\n对外部 Agent、脚本或支持 MCP 的客户端，DocMind 会通过 MCP Server 对外暴露工具能力。外部客户端可以通过 HTTP/SSE 发现工具并调用。\n\n这意味着：', 2, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"procedure\"}', NULL, '2026-04-02 17:52:57');
INSERT INTO `kb_chunk` VALUES (4, 4, '- 内部是“本地函数调用”\n- 外部是“标准 MCP 工具调用”\n\n两者共用同一批工具实现，但走不同的接入路径。\n\n## 五、为什么要同时保留 Agentic RAG 和 MCP\n\n### Agentic RAG 解决内部问答编排问题\n\n它负责：\n\n- 怎么决定下一步动作\n- 怎么组织检索与生成\n- 怎么把多路来源合成最终答案\n\n### MCP 解决能力复用问题\n\n它负责：\n\n- 如何把工具能力开放给外部\n- 如何让其他 Agent 或自动化流程复用知识库能力\n- 如何实现工具发现、标准化输入输出和调用记录\n\n## 六、典型调用链示例\n\n### 场景一：技术规范问答\n\n问题：\n\n“权限系统里角色继承怎么配置？”\n\n可能链路：\n\n1. 判断为普通知识库查询\n2. 改写查询\n3. 调用 `doc_search`\n4. 调用 `keyword_search`\n5. 融合重排\n6. 输出带来源的答案', 3, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"procedure\"}', NULL, '2026-04-02 17:52:57');
INSERT INTO `kb_chunk` VALUES (5, 4, '### 场景二：版本变化查询\n\n问题：\n\n“最新发布里鉴权方式有变化吗？”\n\n可能链路：\n\n1. 判断为实时问题\n2. 调用 `doc_search`\n3. 调用 `keyword_search`\n4. 调用 `web_search`\n5. 统一编号来源\n6. 给出结论并附上网页链接\n\n### 场景三：追问\n\n问题：\n\n“继续说刚才那个权限模型的限制”\n\n可能链路：\n\n1. 判断为追问\n2. 调用 `recall_memory`\n3. 结合对话历史和知识库继续回答\n\n## 七、MCP 接入时需要注意的问题\n\n- `/mcp` 接口是否要求鉴权\n- 工具输入输出是否稳定\n- 工具异常是否会导致整个请求失败\n- 外部搜索是否已配置\n- 工具调用结果是否需要审计\n\n## 八、工程实践建议', 4, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"warning\"}', NULL, '2026-04-02 17:52:57');
INSERT INTO `kb_chunk` VALUES (6, 4, '- 内部业务优先直接复用工具 Bean，不要为内部场景绕一层 MCP\n- 外部集成统一走 MCP，减少私有协议\n- 工具命名保持稳定，避免外部客户端适配成本过高\n- 所有工具返回结构化结果，避免返回不可解析文本\n\n## 总结\n\n在 DocMind 中，Agentic RAG 是“如何编排问题求解过程”，MCP 是“如何开放工具能力给外部调用”。两者不是互相替代关系，而是内部编排与外部集成的两层能力。', 5, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"general\"}', NULL, '2026-04-02 17:52:57');
INSERT INTO `kb_chunk` VALUES (7, 5, '# DocMind 产品说明：用户操作手册\n\n## 文档目标\n\n本文档面向普通用户和管理员，说明如何使用 DocMind 完成知识库上传、问答、会话查看和结果解释。\n\n## 一、上传知识库文档\n\n### 操作步骤\n\n1. 进入知识库管理页面\n2. 点击上传文档\n3. 选择文件并填写名称、分类和描述\n4. 等待系统完成处理\n\n### 上传后会发生什么\n\n系统会依次执行：\n\n- 保存原始文件\n- 解析文本\n- 切片\n- 向量化\n- 写入检索索引\n\n只有当状态变为 `ready` 时，该文档才可用于问答。\n\n### 建议上传的文档类型\n\n- 产品说明\n- 接口文档\n- 部署手册\n- 操作流程\n- FAQ\n- 版本记录\n- 规范与制度文件\n\n## 二、发起问答\n\n### 基本方式\n\n在聊天页面直接输入问题，例如：', 0, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"procedure\"}', NULL, '2026-04-02 17:53:15');
INSERT INTO `kb_chunk` VALUES (8, 5, '- “部署前需要准备哪些配置？”\n- “权限模型有哪些核心对象？”\n- “这个功能的使用限制是什么？”\n\n### 限定知识库范围\n\n如果页面支持知识库选择器，建议在提问前选择明确的知识库范围。这样可以：\n\n- 提高命中率\n- 降低噪音结果\n- 让答案更聚焦\n\n## 三、理解问答结果\n\n### 1. 推理过程\n\n部分回答会显示系统执行步骤，例如：\n\n- 意图识别\n- 查询改写\n- 检索\n- 重排\n- 反思审查\n\n这些信息可以帮助用户判断系统为什么给出当前答案。\n\n### 2. 参考来源\n\n当回答下方显示来源面板时，通常包括：\n\n- 来源名称\n- 章节或页码\n- 摘要片段\n- 相关性分数\n\n如果来源是网页结果，还可能包含链接。\n\n### 3. 兜底回答\n\n若系统未检索到足够相关的内容，可能会返回兜底说明。此时应该理解为：\n\n- 当前知识库资料不足\n- 回答参考性较强\n- 更适合补充文档或重新提问', 1, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"procedure\"}', NULL, '2026-04-02 17:53:15');
INSERT INTO `kb_chunk` VALUES (9, 5, '## 四、会话管理\n\n### 新建对话\n\n适合在以下情况使用：\n\n- 问题主题变化较大\n- 不希望旧上下文影响新答案\n\n### 切换历史会话\n\n系统会保存历史记录，方便用户回看：\n\n- 问过什么\n- 系统怎么回答\n- 用过哪些来源\n\n### 删除会话\n\n如果某次对话只用于临时测试，可以删除，避免列表混乱。\n\n## 五、用户反馈\n\n对每条回答，用户可进行简单反馈，例如：\n\n- 有用\n- 无用\n\n反馈的意义在于：\n\n- 帮助管理员识别低质量回答\n- 为后续知识库优化提供依据\n\n## 六、提高问答质量的建议\n\n### 提问尽量具体\n\n比起问“这个怎么做”，更推荐问：\n\n- “在测试环境中如何配置 OAuth 登录？”\n- “导出接口的分页上限是多少？”\n\n### 包含对象和范围\n\n推荐在问题中带上：\n\n- 模块名\n- 场景名\n- 时间范围\n- 版本范围\n\n### 优先上传结构清晰的文档', 2, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"example\"}', NULL, '2026-04-02 17:53:15');
INSERT INTO `kb_chunk` VALUES (10, 5, '如果文档本身没有标题层级、章节混乱或内容重复，问答效果会明显下降。\n\n## 七、常见误区\n\n### 误区一：系统知道所有外部知识\n\n不是。系统优先依赖已导入的知识库；若网页检索未启用，则不会主动访问外部网络。\n\n### 误区二：系统回答越长越好\n\n不是。对知识库问答来说，最重要的是：\n\n- 回答相关\n- 来源明确\n- 不编造\n\n### 误区三：没有结果说明系统不可用\n\n很多时候只是：\n\n- 文档还没处理完成\n- 问题范围太大\n- 关键词过于模糊\n- 知识库本身缺资料\n\n## 总结\n\n把 DocMind 用好，关键不是“问得越多越好”，而是“文档沉淀得好、范围选得准、问题问得清”。它更像是团队知识的检索入口，而不是替代文档本身。', 3, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"general\"}', NULL, '2026-04-02 17:53:15');
INSERT INTO `kb_chunk` VALUES (11, 6, '# 企业文档：知识库治理规范\n\n## 文档目标\n\n本文档定义知识库治理的基本规范，帮助团队建立统一的命名、分类、评审、归档和责任机制，避免知识库逐渐演变成“文件堆积区”。\n\n## 一、为什么知识库需要治理\n\n知识库系统的效果高度依赖内容质量。如果文档混乱、重复、过期、无责任人，再强的检索能力也很难持续给出高质量答案。\n\n## 二、知识库命名建议\n\n推荐命名结构：\n\n- 主题 + 版本/日期 + 类型\n\n示例：\n\n- `权限系统-接口规范-v1.3`\n- `部署运行手册-生产环境-2026Q1`\n- `产品FAQ-支付模块-2026-03`\n\n不建议的命名：\n\n- `新文档`\n- `最终版`\n- `最新版2`\n- `资料整理`\n\n## 三、分类建议\n\n推荐从业务用途出发分类，而不是只按文件格式分类。\n\n常见分类：\n\n- 技术\n- 产品\n- 运维\n- 规范\n- 法务\n- 客服\n- 通用', 0, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"example\"}', NULL, '2026-04-02 17:53:32');
INSERT INTO `kb_chunk` VALUES (12, 6, '## 四、责任人机制\n\n每个知识库建议至少有以下角色之一：\n\n- 业务 owner\n- 文档维护人\n- 审核人\n\n责任不明确时，最容易出现的问题是：\n\n- 文档长期不更新\n- 旧资料无人清理\n- 用户不知道该信哪个版本\n\n## 五、评审机制\n\n建议重要知识文档在入库前至少通过一次内容检查，重点关注：\n\n- 是否为最终有效版本\n- 是否含冲突信息\n- 是否缺少标题层次\n- 是否适合被问答系统检索\n\n## 六、文档生命周期建议\n\n可参考以下状态：\n\n- 草稿\n- 待审核\n- 已发布\n- 已过期\n- 已归档\n\n对已过期内容，建议不要直接删除，可先归档并降低优先级。\n\n## 七、版本管理建议\n\n对频繁变化的资料，建议明确版本管理方式：\n\n- 版本号\n- 生效日期\n- 废弃日期\n- 变更摘要\n\n这类元信息对问答系统尤其重要，因为很多用户会问：\n\n- “最新版规则是什么？”\n- “旧版和新版有什么差异？”', 1, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"general\"}', NULL, '2026-04-02 17:53:32');
INSERT INTO `kb_chunk` VALUES (13, 6, '## 八、内容质量建议\n\n更适合入库的文档通常具备：\n\n- 清晰标题\n- 明确章节\n- 少量重复\n- 完整上下文\n- 术语统一\n\n不适合直接入库的资料包括：\n\n- 大量截图拼接文件\n- 纯会议记录\n- 未整理的聊天记录\n- 多版本内容混杂的草稿\n\n## 九、定期治理建议\n\n建议按月或按季度执行：\n\n- 热门问题分析\n- 低命中文档排查\n- 过期文档清理\n- 重复文档合并\n- 缺失知识补录\n\n## 十、总结\n\n知识库治理本质上是“把文档管理成可信知识资产”。对企业来说，系统只是载体，真正决定问答效果的，是知识是否规范、持续、可追责、可演进。', 2, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"general\"}', NULL, '2026-04-02 17:53:32');
INSERT INTO `kb_chunk` VALUES (14, 7, '# 企业文档：监控、告警与故障处置\n\n## 文档目标\n\n本文档用于说明知识库问答系统上线后应该重点关注哪些指标、如何设置告警，以及出现故障后如何做初步分级和处置。\n\n## 一、为什么需要监控和告警\n\n知识库问答系统的稳定性不仅取决于应用本身，还依赖多个外部组件：\n\n- 数据库\n- 缓存\n- 对象存储\n- 向量数据库\n- 大模型服务\n- 外部搜索服务\n\n任何一层异常，都可能表现为：\n\n- 文档处理失败\n- 问答超时\n- 结果为空\n- 来源缺失\n- 页面可用但答案质量显著下降\n\n## 二、建议重点监控的指标\n\n### 应用层\n\n- 接口成功率\n- SSE 建连成功率\n- 平均响应时间\n- P95/P99 响应时间\n\n### 检索层\n\n- 向量检索耗时\n- 关键词检索耗时\n- 重排耗时\n- 空结果比例\n\n### 模型层', 0, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"general\"}', NULL, '2026-04-02 17:55:23');
INSERT INTO `kb_chunk` VALUES (15, 7, '- Chat Model 调用成功率\n- Embedding 调用成功率\n- 外部搜索调用成功率\n\n### 数据层\n\n- MySQL 连接池使用率\n- Redis 连接与内存使用\n- Milvus 查询耗时\n- MinIO 上传失败率\n\n## 三、建议告警策略\n\n### P1 告警\n\n需要立即处理的场景：\n\n- 登录不可用\n- 问答主链不可用\n- 文档上传全部失败\n- 大量 5xx\n\n### P2 告警\n\n需要尽快处理但可短时观察的场景：\n\n- 问答耗时显著升高\n- 外部搜索大量失败\n- 向量检索耗时异常\n- 某些知识库处理任务连续失败\n\n### P3 告警\n\n用于趋势跟踪：\n\n- 来源缺失率上升\n- 兜底回答比例升高\n- 文档导入量异常下降\n\n## 四、常见故障分级建议\n\n### Sev-1\n\n用户核心功能不可用，影响广泛。\n\n示例：', 1, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"example\"}', NULL, '2026-04-02 17:55:23');
INSERT INTO `kb_chunk` VALUES (16, 7, '- 所有问答请求失败\n- 登录全部失败\n- 首页不可访问\n\n### Sev-2\n\n部分核心能力不可用，但可降级运行。\n\n示例：\n\n- 网页搜索不可用\n- 部分知识库无法检索\n- MCP 外部调用失败但内部问答正常\n\n### Sev-3\n\n局部问题或非核心异常。\n\n示例：\n\n- 导出功能异常\n- 某类文档处理失败\n- 某些统计页面数据不完整\n\n## 五、初步处置流程\n\n1. 确认告警是否真实\n2. 判断影响范围\n3. 检查最近发布和配置变更\n4. 检查外部依赖状态\n5. 决定降级还是回滚\n6. 记录处置过程\n\n## 六、典型故障排查路径\n\n### 问答变慢\n\n优先检查：\n\n- 模型调用耗时\n- 向量检索耗时\n- 是否出现大量重试\n- 是否有依赖组件 CPU/内存异常\n\n### 回答频繁无来源\n\n优先检查：', 2, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"procedure\"}', NULL, '2026-04-02 17:55:23');
INSERT INTO `kb_chunk` VALUES (17, 7, '- 知识库是否为空\n- 文档是否导入完成\n- 来源组装逻辑是否异常\n- Prompt 是否被错误裁剪\n\n### 文档导入失败\n\n优先检查：\n\n- 文件格式是否支持\n- 对象存储是否可写\n- 文本抽取是否报错\n- 向量数据库是否可写\n\n## 七、值班建议\n\n- 发布当日安排研发和运维同时在线\n- 核心时段开启重点指标盯盘\n- 对连续失败任务设置自动聚合告警，避免告警风暴\n\n## 总结\n\n监控和告警的目标不是“把所有异常都报警”，而是“在真正影响用户和业务之前尽早识别风险”。对 DocMind 这类多依赖系统，跨层指标和统一排障入口尤为重要。', 3, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"warning\"}', NULL, '2026-04-02 17:55:23');
INSERT INTO `kb_chunk` VALUES (18, 8, '# 企业文档：版本发布流程与上线检查\n\n## 文档目标\n\n本文档用于定义企业系统版本发布的推荐流程，涵盖需求冻结、测试验收、灰度上线、观察期和回滚条件。\n\n## 一、发布目标\n\n一个成熟的发布流程应同时满足：\n\n- 上线可控\n- 风险可回退\n- 责任明确\n- 信息同步\n\n## 二、角色分工\n\n### 产品负责人\n\n- 确认需求范围\n- 明确是否允许变更延期\n- 确认发布公告内容\n\n### 研发负责人\n\n- 确认代码合并范围\n- 确认部署包版本\n- 提供回滚方案\n\n### 测试负责人\n\n- 确认回归结果\n- 给出是否可发布结论\n\n### 运维或平台负责人\n\n- 执行部署\n- 观察上线指标\n- 触发回滚操作\n\n## 三、标准发布流程\n\n### 1. 冻结发布范围\n\n在发布前应明确：\n\n- 哪些功能进入本次发布\n- 哪些缺陷必须修复\n- 哪些需求延后\n\n### 2. 通过测试验收\n\n至少包括：', 0, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"procedure\"}', NULL, '2026-04-02 17:55:30');
INSERT INTO `kb_chunk` VALUES (19, 8, '- 核心功能回归\n- 关键接口验证\n- 问答主链验证\n- 来源展示验证\n- 关键配置验证\n\n### 3. 准备发布包和变更单\n\n发布前应准备：\n\n- 构建版本号\n- 变更说明\n- 配置变更项\n- 数据脚本\n- 回滚步骤\n\n### 4. 执行上线\n\n推荐顺序：\n\n1. 备份关键数据\n2. 执行配置变更\n3. 发布应用\n4. 做健康检查\n5. 做冒烟验证\n\n### 5. 观察期\n\n发布后应重点观察：\n\n- 错误率\n- 响应时间\n- 核心页面与接口\n- 问答成功率\n- 工具调用成功率\n\n## 四、灰度发布建议\n\n适用于：\n\n- 变更范围大\n- 风险未知\n- 涉及核心链路\n\n常见方式：\n\n- 指定用户灰度\n- 指定流量比例灰度\n- 指定租户灰度\n\n灰度阶段重点确认：\n\n- 新功能是否符合预期\n- 老功能是否出现回归\n- 指标是否稳定\n\n## 五、回滚触发条件\n\n建议提前定义清晰的回滚条件，例如：', 1, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"procedure\"}', NULL, '2026-04-02 17:55:30');
INSERT INTO `kb_chunk` VALUES (20, 8, '- 核心接口连续异常\n- 登录或鉴权失败\n- 问答主链不可用\n- 严重数据污染风险\n- 用户投诉集中爆发\n\n## 六、上线检查清单\n\n### 上线前\n\n- 版本号正确\n- 配置文件正确\n- 数据库脚本已审核\n- 回滚方案已确认\n- 责任人在线\n\n### 上线后\n\n- 首页可访问\n- 登录正常\n- 知识库查询正常\n- SSE 问答正常\n- MCP 工具可见\n\n## 七、发布说明建议内容\n\n每次发布说明至少包含：\n\n- 版本号\n- 发布时间\n- 变更摘要\n- 风险提示\n- 升级说明\n- 回滚说明\n- 已知限制\n\n## 八、总结\n\n一个好发布流程的关键不是“步骤很多”，而是“每一步都能降低不确定性”。对知识库问答系统而言，发布时尤其要关注检索链路、来源展示、配置正确性和工具可用性。', 2, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"procedure\"}', NULL, '2026-04-02 17:55:30');
INSERT INTO `kb_chunk` VALUES (21, 9, '# DocMind 产品说明：常见问题 FAQ\n\n## 1. 为什么上传文档后不能马上问答？\n\n因为系统需要先完成：\n\n- 文本抽取\n- 切片\n- 向量化\n- 检索索引构建\n\n只有知识库状态变成 `ready` 之后，文档才真正可检索。\n\n## 2. 为什么我明明上传了文档，却检索不到内容？\n\n可能原因包括：\n\n- 文档尚未处理完成\n- 文档内容提取失败\n- 问题过于模糊\n- 关键术语与文档表达差异过大\n- 没有选中正确的知识库范围\n\n## 3. 为什么答案里有“当前知识库结果不足”的提示？\n\n这表示系统判断：\n\n- 没有检索到足够相关的内容\n- 或者检索结果置信度偏低\n\n这是为了避免无依据回答，属于保护机制，不是故障。\n\n## 4. 为什么同一个问题有时回答不完全一致？\n\n常见原因有：\n\n- 检索候选结果排序发生变化\n- 模型生成存在自然波动\n- 会话上下文不同\n- 是否命中长期记忆不同', 0, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"general\"}', NULL, '2026-04-02 17:56:07');
INSERT INTO `kb_chunk` VALUES (22, 9, '如果希望更稳定，可以：\n\n- 指定知识库范围\n- 提问更精确\n- 新开会话避免历史干扰\n\n## 5. 网页搜索和知识库搜索有什么区别？\n\n知识库搜索：\n\n- 优先使用系统内已导入文档\n- 来源更可控\n- 更适合内部规范和沉淀资料\n\n网页搜索：\n\n- 适合补充外部最新信息\n- 依赖外部服务配置\n- 不一定总是启用\n\n## 6. 为什么有些回答没有来源？\n\n通常意味着：\n\n- 没有检索到可信来源\n- 当前回答是兜底说明\n- 知识库内容不足以支撑引用\n\n在正式使用中，应优先信任带来源编号和明确出处的回答。\n\n## 7. 怎样提高检索命中率？\n\n建议从以下方面优化：\n\n- 文档按主题拆分\n- 文件命名规范化\n- 保留章节标题\n- 提问时带关键对象和范围\n- 用更明确的术语替代模糊表达\n\n## 8. 支持哪些文档类型？\n\n常见支持类型包括：\n\n- PDF\n- DOCX\n- Markdown\n- TXT', 1, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"general\"}', NULL, '2026-04-02 17:56:07');
INSERT INTO `kb_chunk` VALUES (23, 9, '不同格式的解析质量可能存在差异，其中结构清晰的 Markdown 和 Word 文档通常更适合入库。\n\n## 9. 长期记忆会记录什么？\n\n系统只应记录用户明确表达、适合长期保留的信息，例如：\n\n- 角色\n- 称呼\n- 表达偏好\n- 关注主题\n\n不应把每一轮普通问题都写入长期记忆。\n\n## 10. MCP 能做什么？\n\nMCP 主要用于把 DocMind 的工具能力开放给外部客户端，包括：\n\n- 文档语义检索\n- 关键词检索\n- 网页检索\n- 长期记忆读取与写入\n\n这使得 DocMind 不只是一个页面产品，也可以作为外部 Agent 的知识能力底座。\n\n## 11. 系统适合哪些团队？\n\n比较适合：\n\n- 产品团队\n- 研发团队\n- 测试团队\n- 运营团队\n- 知识管理团队\n- 内部支持团队\n\n尤其适合文档多、资料散、重复答疑频繁的场景。\n\n## 12. 不适合哪些场景？', 2, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"example\"}', NULL, '2026-04-02 17:56:07');
INSERT INTO `kb_chunk` VALUES (24, 9, '不适合直接拿来替代：\n\n- 强事务系统\n- 审批流系统\n- 复杂协同编辑平台\n- 严格法律或财务自动决策系统\n\nDocMind 更适合做“知识检索和辅助理解”，而不是“自动决策代理”。\n\n## 13. 如何判断知识库内容是否值得补充？\n\n如果你频繁遇到以下情况，就应该补文档：\n\n- 同类问题反复被问\n- 系统经常给出兜底回答\n- 用户需要依赖口头说明才能继续工作\n- 不同成员对同一规则理解不一致\n\n## 14. 管理员最应该关注哪些指标？\n\n- 文档处理成功率\n- 问答响应时间\n- 来源覆盖率\n- 低质量回答反馈数\n- 热门问题分布\n- 高频知识空白点\n\n## 总结\n\nDocMind 的效果高度依赖知识库质量。系统本身可以增强检索和组织答案，但不能替代知识的持续沉淀。把常见问题、关键流程和核心规范及时文档化，才是发挥系统价值的前提。', 3, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"procedure\"}', NULL, '2026-04-02 17:56:07');

-- ----------------------------
-- Table structure for kb_knowledge_base
-- ----------------------------
DROP TABLE IF EXISTS `kb_knowledge_base`;
CREATE TABLE `kb_knowledge_base`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '知识库/文档名称',
  `category` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '分类(技术/法律/医疗/通用等)',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '描述',
  `file_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '原始文件存储路径(MinIO)',
  `file_type` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '文件类型: pdf/docx/md/txt',
  `file_size` bigint NULL DEFAULT NULL COMMENT '文件大小(字节)',
  `chunk_count` int NOT NULL DEFAULT 0 COMMENT '切片数量',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'uploading' COMMENT '状态: uploading/processing/ready/error',
  `error_msg` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '错误信息',
  `user_id` bigint NOT NULL COMMENT '创建者用户ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0正常 1删除',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_category`(`category` ASC) USING BTREE,
  INDEX `idx_user_id`(`user_id` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 10 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '知识库文档表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of kb_knowledge_base
-- ----------------------------
INSERT INTO `kb_knowledge_base` VALUES (1, 'kb-product-overview.md', 'tech', NULL, 'knowledge/aca7ee9b-50bf-4839-b7ed-97bd371f72d6.md', 'md', 3168, 4, 'ready', NULL, 1, '2026-03-31 21:06:26', '2026-03-31 21:06:26', 0);
INSERT INTO `kb_knowledge_base` VALUES (2, 'kb-acceptance-question-list.md', 'tech', NULL, 'knowledge/61d3ed11-3715-4667-89e7-1c60b70fe4f6.md', 'md', 4345, 6, 'ready', NULL, 1, '2026-03-31 21:07:00', '2026-03-31 21:07:00', 0);
INSERT INTO `kb_knowledge_base` VALUES (3, 'DocMind-智能知识库检索系统-PRD.docx', 'product', NULL, 'knowledge/d98c5ec3-0da1-4a8d-a334-7f51914f9e2a.docx', 'docx', 45293, 26, 'ready', NULL, 1, '2026-03-31 21:07:15', '2026-03-31 21:07:15', 0);
INSERT INTO `kb_knowledge_base` VALUES (4, 'kb-tech-agentic-rag-and-mcp.md', 'tech', NULL, 'knowledge/67d3eeba-d3be-4e7d-ae0d-c3c2c4f8524f.md', 'md', 4402, 6, 'ready', NULL, 1, '2026-04-02 17:52:57', '2026-04-02 17:52:57', 0);
INSERT INTO `kb_knowledge_base` VALUES (5, 'kb-product-user-guide.md', 'product', NULL, 'knowledge/c1dc2338-8865-4b12-a099-fee42859e970.md', 'md', 3559, 4, 'ready', NULL, 1, '2026-04-02 17:53:15', '2026-04-02 17:53:15', 0);
INSERT INTO `kb_knowledge_base` VALUES (6, 'kb-enterprise-knowledge-governance.md', 'training', NULL, 'knowledge/46e23701-9ed4-44d6-be81-2b4b1232b372.md', 'md', 2603, 3, 'ready', NULL, 1, '2026-04-02 17:53:32', '2026-04-02 17:53:32', 0);
INSERT INTO `kb_knowledge_base` VALUES (7, 'kb-enterprise-operations-and-alerting.md', 'legal', NULL, 'knowledge/70f543b1-d45a-4fe0-a892-7743392478c6.md', 'md', 3121, 4, 'ready', NULL, 1, '2026-04-02 17:55:23', '2026-04-02 17:55:23', 0);
INSERT INTO `kb_knowledge_base` VALUES (8, 'kb-enterprise-release-process.md', 'finance', NULL, 'knowledge/ef5b9721-e3dc-40f6-addf-1aad37d70a11.md', 'md', 2651, 3, 'ready', NULL, 1, '2026-04-02 17:55:30', '2026-04-02 17:55:30', 0);
INSERT INTO `kb_knowledge_base` VALUES (9, 'kb-product-faq.md', 'product', NULL, 'knowledge/a63ea3b7-304f-4a52-beb9-27a33682406a.md', 'md', 3743, 4, 'ready', NULL, 1, '2026-04-02 17:56:07', '2026-04-02 17:56:07', 0);

-- ----------------------------
-- Table structure for mcp_tool_registry
-- ----------------------------
DROP TABLE IF EXISTS `mcp_tool_registry`;
CREATE TABLE `mcp_tool_registry`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '工具名称',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '工具描述',
  `mode` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'embedded' COMMENT '模式: embedded/remote',
  `call_count` bigint NOT NULL DEFAULT 0 COMMENT '调用次数',
  `avg_latency_ms` int NOT NULL DEFAULT 0 COMMENT '平均延迟(ms)',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'active' COMMENT '状态: active/disabled',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_name`(`name` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 10 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'MCP工具注册表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of mcp_tool_registry
-- ----------------------------
INSERT INTO `mcp_tool_registry` VALUES (3, 'web_search', '联网搜索工具，获取实时互联网信息', 'remote', 0, 0, 'active', '2026-03-30 10:36:10', '2026-03-30 10:36:10');
INSERT INTO `mcp_tool_registry` VALUES (6, 'doc_search', '语义向量文档检索工具', 'embedded', 13, 421, 'active', '2026-03-30 15:16:55', '2026-04-02 17:43:19');
INSERT INTO `mcp_tool_registry` VALUES (7, 'keyword_search', '关键词BM25文档检索：根据关键词精确匹配从知识库中检索文档切片', 'embedded', 0, 0, 'active', '2026-04-02 16:20:04', '2026-04-02 16:20:04');
INSERT INTO `mcp_tool_registry` VALUES (8, 'recall_memory', '召回用户长期记忆：从Redis读取用户偏好等跨会话记忆', 'embedded', 0, 0, 'active', '2026-04-02 16:20:04', '2026-04-02 16:20:04');
INSERT INTO `mcp_tool_registry` VALUES (9, 'store_memory', '写入用户长期记忆：将用户明确表达的偏好持久化到Redis', 'embedded', 0, 0, 'active', '2026-04-02 16:20:04', '2026-04-02 16:20:04');

-- ----------------------------
-- Table structure for qa_conversation
-- ----------------------------
DROP TABLE IF EXISTS `qa_conversation`;
CREATE TABLE `qa_conversation`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint NOT NULL COMMENT '关联用户ID',
  `title` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '会话标题(首次提问自动生成)',
  `kb_ids` json NULL COMMENT '关联的知识库ID列表',
  `message_count` int NOT NULL DEFAULT 0 COMMENT '消息条数',
  `last_active` datetime NULL DEFAULT NULL COMMENT '最后活跃时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0正常 1删除',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user_id`(`user_id` ASC) USING BTREE,
  INDEX `idx_last_active`(`last_active` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 15 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '对话会话表' ROW_FORMAT = DYNAMIC;


-- ----------------------------
-- Table structure for qa_message
-- ----------------------------
DROP TABLE IF EXISTS `qa_message`;
CREATE TABLE `qa_message`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `conversation_id` bigint NOT NULL COMMENT '关联会话ID',
  `role` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '角色: user/assistant',
  `content` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '消息内容(支持Markdown)',
  `sources` json NULL COMMENT '文档来源(文档名、页码、片段)',
  `agent_trace` json NULL COMMENT 'ReAct推理链(思考→行动→观察)',
  `mcp_calls` json NULL COMMENT 'Tool调用记录',
  `reflection_log` json NULL COMMENT '自纠错审查日志',
  `feedback` tinyint NOT NULL DEFAULT 0 COMMENT '反馈: 1有用 -1无用 0未评',
  `tokens_used` int NULL DEFAULT NULL COMMENT 'Token消耗',
  `response_time` int NULL DEFAULT NULL COMMENT '响应时间(毫秒)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_conversation_id`(`conversation_id` ASC) USING BTREE,
  INDEX `idx_create_time`(`create_time` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 29 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '对话消息表' ROW_FORMAT = DYNAMIC;


-- ----------------------------
-- Table structure for sys_ai_config
-- ----------------------------
DROP TABLE IF EXISTS `sys_ai_config`;
CREATE TABLE `sys_ai_config`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `config_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '配置键(全局唯一)',
  `config_value` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '当前配置值',
  `value_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'string' COMMENT '值类型: string/integer/float',
  `group_name` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '分组: rag/llm/cache/safety',
  `label` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '前端展示名称',
  `description` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '配置说明',
  `default_value` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '出厂默认值',
  `min_value` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '最小值约束',
  `max_value` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '最大值约束',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_config_key`(`config_key` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 23 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI动态配置表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of sys_ai_config
-- ----------------------------
INSERT INTO `sys_ai_config` VALUES (1, 'rag.vector_top_k', '10', 'integer', 'rag', '向量召回 Top-K', '向量检索返回的最大文档数', '10', '1', '50', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (2, 'rag.bm25_top_k', '10', 'integer', 'rag', 'BM25 召回 Top-K', 'BM25 检索返回的最大文档数', '10', '1', '50', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (3, 'rag.rerank_top_n', '5', 'integer', 'rag', '重排序 Top-N', '重排序后保留的文档数', '5', '1', '20', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (4, 'rag.chunk_size', '512', 'integer', 'rag', '切片大小', '文档切片的最大字符数', '512', '128', '2048', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (5, 'rag.chunk_overlap', '64', 'integer', 'rag', '切片重叠', '相邻切片的重叠字符数', '64', '0', '512', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (6, 'llm.model', 'qwen-plus', 'string', 'llm', '模型名称', '当前使用的 LLM 模型名称', 'qwen-plus', NULL, NULL, '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (7, 'llm.temperature', '0.7', 'float', 'llm', 'Temperature', 'LLM 生成多样性参数（兼容旧配置）', '0.7', '0', '2', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (8, 'llm.chat_temperature', '0.7', 'float', 'llm', '对话模型 Temperature', '普通对话模型的温度参数', '0.7', '0', '2', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (9, 'llm.streaming_temperature', '0.7', 'float', 'llm', '流式模型 Temperature', 'SSE 流式对话模型的温度参数', '0.7', '0', '2', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (10, 'llm.timeout_seconds', '60', 'integer', 'llm', '请求超时(秒)', 'LLM 请求超时时间（秒）', '60', '10', '300', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (11, 'llm.max_tokens', '2048', 'integer', 'llm', '最大输出Token', 'LLM 单次最大输出 Token 数', '2048', '256', '8192', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (12, 'cache.enable', 'true', 'string', 'cache', '启用缓存', '是否启用 RAG 结果缓存', 'true', NULL, NULL, '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (13, 'cache.ttl_seconds', '3600', 'integer', 'cache', '缓存TTL(秒)', 'RAG 缓存有效期（秒）', '3600', '60', '86400', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (14, 'cache.ttl_hours', '1', 'integer', 'cache', '缓存TTL(小时)', 'RAG 缓存有效期（小时）', '1', '1', '720', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (15, 'cache.freq_threshold', '2', 'integer', 'cache', '缓存频次阈值', '同一问题达到该频次后才检查缓存', '2', '1', '100', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (16, 'safety.enable_guard', 'true', 'string', 'safety', '启用安全过滤', '是否开启内容安全审查', 'true', NULL, NULL, '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (17, 'safety.max_retries', '2', 'integer', 'safety', '最大自纠错次数', 'ReAct 自纠错最大重试轮数', '2', '0', '5', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (18, 'safety.confidence_threshold', '0.6', 'float', 'safety', '置信度阈值', '低于此分数触发兜底回答', '0.6', '0', '1', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (19, 'safety.fallback_msg', '抱歉，我暂时无法回答该问题，请联系管理员。', 'string', 'safety', '兜底话术', '无法回答时的默认提示语', '抱歉，我暂时无法回答该问题，请联系管理员。', NULL, NULL, '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (20, 'rag.rrf_top_n', '20', 'integer', 'rag', 'RRF融合 Top-N', 'RRF 融合后保留的候选文档数', '20', '5', '100', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (21, 'rag.rerank_top_k', '6', 'integer', 'rag', '重排序 Top-K', '重排序后最终保留的文档数', '5', '1', '20', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (22, 'rag.rrf_k_constant', '60', 'integer', 'rag', 'RRF K常数', 'RRF 算法平滑常数，标准值为60', '60', '1', '200', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);

-- ----------------------------
-- Table structure for sys_user
-- ----------------------------
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户名（唯一）',
  `password` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '密码(BCrypt加密)',
  `nickname` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '昵称',
  `avatar` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '头像URL',
  `role` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'user' COMMENT '角色: admin/user',
  `preference` json NULL COMMENT '用户偏好(领域、语言风格等)',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态: 0禁用 1正常',
  `last_login` datetime NULL DEFAULT NULL COMMENT '最后登录时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0正常 1删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_username`(`username` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 4 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '系统用户表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of sys_user
-- ----------------------------
INSERT INTO `sys_user` VALUES (1, 'admin', '$2a$10$yTs1cG5twvTjCp1uFzO20uFuC77vdAYNkqspfcMNr8owiTuBk77/u', '管理员', NULL, 'admin', NULL, 1, '2026-04-02 16:11:32', '2026-03-30 10:36:10', '2026-03-30 10:37:51', 0);
INSERT INTO `sys_user` VALUES (2, 'simon', '$2a$10$yTs1cG5twvTjCp1uFzO20uFuC77vdAYNkqspfcMNr8owiTuBk77/u', 'sss', NULL, 'user', NULL, 1, '2026-03-30 10:37:01', '2026-03-30 10:36:58', '2026-03-30 10:36:58', 0);
INSERT INTO `sys_user` VALUES (3, 'codex0331201014', '$2a$10$SJLJSE5uNRIT2BoWVnTwI.x0I7X/NCkwDrWUd0BotIi/2UTb8VNiO', 'Codex Smoke', NULL, 'user', NULL, 1, '2026-03-31 20:10:24', '2026-03-31 20:10:15', '2026-03-31 20:10:15', 0);

SET FOREIGN_KEY_CHECKS = 1;


-- ───────────────────────────────────────────────────────────────
-- [2/23] agent-crew-schema.sql
-- ───────────────────────────────────────────────────────────────
SET NAMES utf8mb4;
-- =====================================================================
-- DocMind Multi-Agent Research Crew — Schema
-- 运行: mysql -uroot -p docmind < sql/agent-crew-schema.sql
-- =====================================================================

USE docmind;
SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- agent_task: 多 Agent 协作任务主表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `agent_task`;
CREATE TABLE `agent_task` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`         BIGINT       NOT NULL                COMMENT '发起用户ID',
    `conversation_id` BIGINT       NULL                    COMMENT '关联会话ID（可空）',
    `query`           TEXT         NOT NULL                COMMENT '原始用户问题',
    `kb_ids`          VARCHAR(200) NULL                    COMMENT '检索知识库范围 JSON',
    `status`          VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                          COMMENT 'PENDING|PLANNING|RESEARCHING|WRITING|REVIEWING|REVISING|COMPLETED|FAILED',
    `current_role`    VARCHAR(20)  NULL                    COMMENT '当前活跃 Agent',
    `plan_json`       TEXT         NULL                    COMMENT 'Planner 输出（子任务列表）',
    `final_report`    LONGTEXT     NULL                    COMMENT '最终报告 Markdown',
    `review_score`    DECIMAL(3,2) NULL                    COMMENT 'Critic 评分 0~1',
    `revision_count`  INT          NOT NULL DEFAULT 0      COMMENT '已重写轮次',
    `total_steps`     INT          NOT NULL DEFAULT 0      COMMENT '总步骤数',
    `total_tokens`    INT          NOT NULL DEFAULT 0      COMMENT '总 token 估算',
    `elapsed_ms`      BIGINT       NOT NULL DEFAULT 0      COMMENT '总耗时(ms)',
    `error_msg`       TEXT         NULL                    COMMENT '失败原因',
    `start_time`      DATETIME     NULL                    COMMENT '开始时间',
    `end_time`        DATETIME     NULL                    COMMENT '结束时间',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`         TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    INDEX `idx_user_id`     (`user_id`),
    INDEX `idx_status`      (`status`),
    INDEX `idx_create_time` (`create_time` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Multi-Agent 协作任务主表';

-- ---------------------------------------------------------------------
-- agent_step: 每个 Agent 的执行步骤详情
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `agent_step`;
CREATE TABLE `agent_step` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `task_id`      BIGINT       NOT NULL                COMMENT '关联 agent_task.id',
    `step_index`   INT          NOT NULL                COMMENT '步骤序号（任务内自增）',
    `agent_role`   VARCHAR(20)  NOT NULL                COMMENT 'PLANNER|RESEARCHER|WRITER|CRITIC',
    `step_name`    VARCHAR(120) NOT NULL                COMMENT '步骤名称（任务分解/调研子主题/撰写报告/质量评审）',
    `subtask`      VARCHAR(500) NULL                    COMMENT 'Researcher 的子任务问题',
    `input`        TEXT         NULL                    COMMENT '输入摘要',
    `output`       LONGTEXT     NULL                    COMMENT '输出（JSON 或文本）',
    `status`       VARCHAR(20)  NOT NULL DEFAULT 'RUNNING'
                       COMMENT 'RUNNING|DONE|FAILED|SKIPPED',
    `elapsed_ms`   BIGINT       NOT NULL DEFAULT 0,
    `tokens`       INT          NOT NULL DEFAULT 0,
    `error_msg`    TEXT         NULL,
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_task_id`    (`task_id`),
    INDEX `idx_task_index` (`task_id`, `step_index`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Multi-Agent 步骤记录表（支持完整回放）';


-- ───────────────────────────────────────────────────────────────
-- [3/23] agent-crew-fork-migration.sql
-- ───────────────────────────────────────────────────────────────
SET NAMES utf8mb4;
-- =====================================================================
-- Time-Travel 调试支持：为 agent_task 增加 fork 关系字段
-- 运行: mysql -uroot -p <你的库名> < sql/agent-crew-fork-migration.sql
-- =====================================================================

ALTER TABLE `agent_task`
    ADD COLUMN `parent_task_id`     BIGINT      NULL COMMENT 'Fork 的原任务 ID（NULL 表示原始任务）' AFTER `conversation_id`,
    ADD COLUMN `forked_from_step`   INT         NULL COMMENT 'Fork 起点的步骤序号'                    AFTER `parent_task_id`,
    ADD COLUMN `fork_edit_summary`  VARCHAR(200) NULL COMMENT '用户在 Fork 时的编辑说明'              AFTER `forked_from_step`,
    ADD INDEX `idx_parent_task` (`parent_task_id`);


-- ───────────────────────────────────────────────────────────────
-- [4/23] kb-category-schema.sql
-- ───────────────────────────────────────────────────────────────
SET NAMES utf8mb4;
-- =====================================================================
-- AI 自动分类 · 字典表 + kb_knowledge_base 字段扩展
-- 运行: mysql -uroot -p docmind < sql/kb-category-schema.sql
-- =====================================================================

-- ─────────────────────────────────────────────
-- 分类字典表（管理员可维护）
-- 注：kb_knowledge_base.category 仍保留 varchar 字段（按 code 写入），
-- 不做强 FK 约束以减少迁移风险。
-- ─────────────────────────────────────────────
DROP TABLE IF EXISTS `kb_category`;
CREATE TABLE `kb_category` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `code`        VARCHAR(40)  NOT NULL COMMENT '英文 code，用于 kb_knowledge_base.category 字段',
    `name`        VARCHAR(40)  NOT NULL COMMENT '中文展示名',
    `parent_id`   BIGINT       NULL     COMMENT '父分类 ID（NULL 表示一级）',
    `description` VARCHAR(200) NULL     COMMENT 'LLM 分类提示用 — 越具体越准确',
    `icon`        VARCHAR(30)  NULL     COMMENT '前端图标 / emoji，可空',
    `color`       VARCHAR(20)  NULL     COMMENT '前端徽标色，hex',
    `sort_order`  INT          NOT NULL DEFAULT 100,
    `enabled`     TINYINT(1)   NOT NULL DEFAULT 1,
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`     TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='知识库文档分类字典';

INSERT INTO `kb_category` (`code`, `name`, `description`, `icon`, `color`, `sort_order`) VALUES
('hr',       '人事',  '招聘、入离职、考勤、薪资、绩效、员工手册',                      '👤', '#F472B6', 10),
('finance',  '财务',  '发票、报销、合同金额、预算、对账单、税务',                       '💰', '#F59E0B', 20),
('tech',     '技术',  '产品架构、接口文档、代码规范、运维手册、技术方案',               '⚙️', '#3D5AFE', 30),
('product',  '产品',  '需求文档、PRD、产品说明、用户手册、版本说明',                    '📦', '#0EA5E9', 40),
('legal',    '法务',  '合同、协议、合规、隐私政策、许可证、法律意见书',                 '⚖️', '#7C3AED', 50),
('training', '培训',  '员工培训资料、课程、考试题、新员工指引',                          '🎓', '#10B981', 60),
('customer', '客户',  '客户档案、销售记录、售后工单、客户反馈',                          '🤝', '#EC4899', 70),
('other',    '其他',  '未明确归类或跨类别的内容',                                       '📁', '#64748B', 999);

-- ─────────────────────────────────────────────
-- kb_knowledge_base 字段扩展
-- ─────────────────────────────────────────────
ALTER TABLE `kb_knowledge_base`
    ADD COLUMN `tags` JSON NULL COMMENT 'LLM 提取的标签数组，如 ["合同","2024","客户A"]'             AFTER `category`,
    ADD COLUMN `summary` TEXT NULL COMMENT 'LLM 生成的 100-200 字摘要'                              AFTER `tags`,
    ADD COLUMN `category_user_set` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否用户手动指定（1=锁定，AI 不再覆盖）' AFTER `summary`;

-- 历史数据：已存在的 category 视为系统初始值，category_user_set 默认 0 即可
-- （让 AI 可以在重新分类操作时覆盖那些占位值）


-- ───────────────────────────────────────────────────────────────
-- [5/23] persona-schema.sql
-- ───────────────────────────────────────────────────────────────
SET NAMES utf8mb4;
-- =====================================================================
-- Soul 文件系统 · 人格定义表
-- 运行: mysql -uroot -p docmind < sql/persona-schema.sql
-- =====================================================================

DROP TABLE IF EXISTS `system_persona`;
CREATE TABLE `system_persona` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `name`            VARCHAR(50)  NOT NULL COMMENT '人格名称',
    `description`     VARCHAR(200) NULL     COMMENT '人格简介',
    `system_prompt`   TEXT         NOT NULL COMMENT '完整的 system prompt 内容（不含反讨好附加段）',
    `temperature`     DECIMAL(3,2) NOT NULL DEFAULT 0.7 COMMENT '温度参数 0.0-2.0',
    `model_name`      VARCHAR(50)  NULL     COMMENT '推荐使用的模型（如 qwen-plus，可空表示用默认）',
    `anti_sycophancy` TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否追加反讨好型规则段（1=追加）',
    `is_default`      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否为系统默认人格（全局只能有一个）',
    `enabled`         TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    `sort_order`      INT          NOT NULL DEFAULT 0 COMMENT '排序权重',
    `created_by`      BIGINT       NULL     COMMENT '创建者用户ID（NULL 表示系统预置）',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`         TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`),
    INDEX `idx_default` (`is_default`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Soul 人格定义表';

-- ─────────────────────────────────────────────
-- 预置 5 个人格模板
-- ─────────────────────────────────────────────

INSERT INTO `system_persona`
    (`name`, `description`, `system_prompt`, `temperature`, `anti_sycophancy`, `is_default`, `sort_order`)
VALUES
(
    '严谨研究员',
    '客观、克制、像学术研究员一样回答。事实优先，资料不足直言不讳。',
'你是一个严谨的研究型助手，回答问题时遵循以下原则：

1. 仅基于提供的资料作答，不臆测、不外推
2. 资料中没有的内容，明确说明"资料未涉及"
3. 资料之间存在矛盾时，并列陈述并标明各自来源
4. 表述客观克制，不使用"显然""毫无疑问"这类绝对化措辞
5. 关键事实后用 [N] 标注引用来源
6. 输出结构化、层次清晰，必要时使用列表或表格',
    0.30, 1, 0, 10
),
(
    '友好客服',
    '主动、礼貌、温暖。适合面向终端用户的咨询场景。',
'你是一位耐心、友善的企业客服助手。

回答风格：
- 用平易近人的语言，避免过多专业术语
- 主动猜测用户可能还想了解的相关信息，给出延伸建议
- 不知道答案时，主动告知"我可以帮你找相关同事咨询"
- 礼貌但不过度卑微（不要每句"非常感谢您的提问"）

底线：不编造资料中没有的内容。',
    0.70, 1, 1, 20
),
(
    '教练',
    '不直接给答案，用提问引导用户自己思考。适合培训和学习场景。',
'你是一位教练型助手。你的目标不是直接给答案，而是引导用户自己思考。

工作方式：
- 收到用户问题后，先反问 1-2 个引导性问题，帮助用户澄清自己的目标
- 给出方向性建议而非具体步骤
- 用户给出回答后，肯定其中合理的部分，并指出可改进之处
- 当用户明确表示"直接给我答案"时，再切换为直接回答模式

避免：
- 不要打断用户思考
- 不要预设结论
- 不要居高临下',
    0.60, 1, 0, 30
),
(
    '反讨好导师',
    '强调真实性，敢于纠正用户错误，不无原则附和。',
'你是一位重视真实性、敢于直言的导师。

核心原则：
- 用户陈述事实有误时，礼貌但**坚定地**纠正，给出依据
- 用户决策方向有风险时，明确指出风险，不为了让用户开心就回避
- 不使用"很棒的问题""您说得太对了"这类无意义的恭维
- 用户提出明显错误的方案时，直接说"这个方案有以下三个问题"
- 当你不确定时，明确说"我不确定，需要进一步核实"

风格：
- 直接、有担当、有逻辑
- 表达尊重但不卑微
- 给出建设性意见，不只是说"不行"',
    0.50, 1, 0, 40
),
(
    '中性助手',
    '系统默认人格，平衡的回答风格。',
'你是 MindCrew 企业知识库的智能助手。

工作原则：
- 基于提供的资料客观回答用户问题
- 资料不足时如实说明，不编造
- 关键事实后用 [N] 标注引用来源
- 表述简洁、直接，避免冗余客套
- 用户问题不清楚时，主动询问以澄清',
    0.70, 1, 0, 50
);

-- 为了让"中性助手"做默认，可调整 is_default：
UPDATE `system_persona` SET `is_default` = 0;
UPDATE `system_persona` SET `is_default` = 1 WHERE `name` = '中性助手';


-- ───────────────────────────────────────────────────────────────
-- [6/23] llm-provider-schema.sql
-- ───────────────────────────────────────────────────────────────
SET NAMES utf8mb4;
-- =====================================================================
-- 跨厂商模型 Provider 配置表
-- 运行: mysql -uroot -p docmind < sql/llm-provider-schema.sql
-- =====================================================================

DROP TABLE IF EXISTS `llm_provider`;
CREATE TABLE `llm_provider` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `name`            VARCHAR(50)  NOT NULL COMMENT '展示名（DashScope / DeepSeek / OpenAI / Ollama 本地 等）',
    `provider_type`   VARCHAR(30)  NOT NULL DEFAULT 'openai_compatible'
                          COMMENT '协议类型：目前都用 openai_compatible',
    `base_url`        VARCHAR(200) NOT NULL COMMENT 'API base URL，如 https://dashscope.aliyuncs.com/compatible-mode',
    `api_key_enc`     VARCHAR(500) NULL     COMMENT 'API Key 加密存储（AES）；本地模型可空',
    `chat_model`      VARCHAR(80)  NULL     COMMENT '对话模型名（qwen-plus / gpt-4o / deepseek-chat 等）',
    `embedding_model` VARCHAR(80)  NULL     COMMENT 'embedding 模型名（text-embedding-v3 / bge-m3 等）',
    `embedding_dim`   INT          NULL     COMMENT '向量维度（1024/1536/...），不填用 chat 端默认',
    `temperature`     DECIMAL(3,2) NOT NULL DEFAULT 0.70 COMMENT '默认温度',
    `description`     VARCHAR(300) NULL     COMMENT '管理员备注（如"自部署，单机 8 卡 A100"）',
    `is_active`       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否为当前激活 provider（全局唯一）',
    `enabled`         TINYINT(1)   NOT NULL DEFAULT 1,
    `sort_order`      INT          NOT NULL DEFAULT 100,
    `last_test_at`    DATETIME     NULL     COMMENT '上次连通性测试时间',
    `last_test_ok`    TINYINT(1)   NULL     COMMENT '上次测试结果',
    `last_test_msg`   VARCHAR(500) NULL,
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`         TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='跨厂商 LLM Provider 配置';

-- ─────────────────────────────────────────────
-- 预置 5 个常见 Provider 模板（api_key 都是占位，需要管理员填）
-- ─────────────────────────────────────────────
INSERT INTO `llm_provider`
    (`name`, `provider_type`, `base_url`, `api_key_enc`, `chat_model`, `embedding_model`, `embedding_dim`, `temperature`, `description`, `is_active`, `sort_order`)
VALUES
(
    '阿里云百炼 · DashScope',
    'openai_compatible',
    'https://dashscope.aliyuncs.com/compatible-mode',
    '',
    'qwen-plus',
    'text-embedding-v3',
    1024,
    0.70,
    '国内首选：通义千问系列，中文最强，性价比高',
    1,                          -- 默认激活
    10
),
(
    'DeepSeek 官方',
    'openai_compatible',
    'https://api.deepseek.com',
    '',
    'deepseek-chat',
    NULL,                        -- DeepSeek 不提供 embedding
    NULL,
    0.70,
    'DeepSeek-V3 / R1，推理强，价格低；不提供 embedding（混搭其他厂商）',
    0,
    20
),
(
    'OpenAI 官方',
    'openai_compatible',
    'https://api.openai.com',
    '',
    'gpt-4o',
    'text-embedding-3-large',
    3072,
    0.70,
    '海外业务首选：GPT-4o / GPT-4o-mini，需要海外代理',
    0,
    30
),
(
    'Ollama 本地',
    'openai_compatible',
    'http://localhost:11434/v1',
    '',                          -- 本地无 key
    'qwen2.5:7b',
    'bge-m3',
    1024,
    0.70,
    '本地部署：单机 Ollama 服务，私有化场景；模型需 ollama pull',
    0,
    40
),
(
    'vLLM 自部署',
    'openai_compatible',
    'http://your-vllm-host:8000/v1',
    'EMPTY',                     -- vLLM 默认 key="EMPTY"
    'Qwen2.5-72B-Instruct',
    NULL,
    NULL,
    0.70,
    '高性能自部署：vLLM 推理引擎，OpenAI 协议；适合 70B+ 大模型',
    0,
    50
);


-- ───────────────────────────────────────────────────────────────
-- [7/23] feedback-golden-schema.sql
-- ───────────────────────────────────────────────────────────────
SET NAMES utf8mb4;
-- =====================================================================
-- 任务 6 · 校正反哺闭环
--   qa_feedback     · 用户对 AI 答复的评分 / 纠正
--   qa_golden_pair  · 审核后的标准问答对（Milvus 中有对应向量）
-- 运行: mysql -uroot -p docmind < sql/feedback-golden-schema.sql
-- =====================================================================

DROP TABLE IF EXISTS `qa_feedback`;
CREATE TABLE `qa_feedback` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT,
    `message_id`          BIGINT       NOT NULL                   COMMENT '关联 qa_message.id（AI 回答消息）',
    `conversation_id`     BIGINT       NOT NULL                   COMMENT '关联 qa_conversation.id',
    `user_id`             BIGINT       NOT NULL                   COMMENT '提交反馈的用户',
    `rating`              VARCHAR(10)  NOT NULL                   COMMENT 'up · 赞 / down · 踩',
    `comment`             VARCHAR(500) NULL                       COMMENT '用户简短评论',
    `correction_text`     TEXT         NULL                       COMMENT '用户/审核员提供的标准答案',
    `correction_sources`  JSON         NULL                       COMMENT '来源引用 JSON（可空）',
    `status`              VARCHAR(20)  NOT NULL DEFAULT 'pending' COMMENT 'pending · 待审核 / approved · 已收录 / rejected · 已驳回',
    `reviewer_id`         BIGINT       NULL                       COMMENT '审核员用户 ID',
    `reviewer_note`       VARCHAR(500) NULL                       COMMENT '审核备注',
    `reviewed_at`         DATETIME     NULL,
    `golden_pair_id`      BIGINT       NULL                       COMMENT '审核通过后生成的 golden pair id',
    `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`             TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_message`      (`message_id`),
    KEY `idx_status`       (`status`),
    KEY `idx_user`         (`user_id`),
    KEY `idx_conversation` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='用户对 AI 答复的反馈 / 校正';


DROP TABLE IF EXISTS `qa_golden_pair`;
CREATE TABLE `qa_golden_pair` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `question`          TEXT         NOT NULL                   COMMENT '标准问题（用户原问题）',
    `question_norm`     VARCHAR(500) NOT NULL                   COMMENT '归一化后的问题 · 大小写/标点统一，用于快速精确匹配',
    `standard_answer`   TEXT         NOT NULL                   COMMENT '审核员认可的标准答案',
    `sources_json`      JSON         NULL                       COMMENT '引用来源 JSON [{"name":"xx","kbId":1,"chunkIdx":3}]',
    `milvus_id`         VARCHAR(64)  NOT NULL                   COMMENT 'Milvus 中对应向量主键',
    `source_feedback_id` BIGINT      NULL                       COMMENT '来源 qa_feedback.id（说明这条 golden 是从哪条反馈来的）',
    `category`          VARCHAR(40)  NULL                       COMMENT '分类标签（关联 kb_category.code）',
    `tags`              JSON         NULL                       COMMENT '标签数组',
    `enabled`           TINYINT(1)   NOT NULL DEFAULT 1         COMMENT '是否启用（禁用时不参与命中）',
    `hit_count`         INT          NOT NULL DEFAULT 0         COMMENT '被命中次数',
    `last_hit_at`       DATETIME     NULL                       COMMENT '上次命中时间',
    `created_by`        BIGINT       NOT NULL                   COMMENT '创建人 user_id',
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`           TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_question_norm` (`question_norm`),
    KEY `idx_milvus`     (`milvus_id`),
    KEY `idx_enabled`    (`enabled`),
    KEY `idx_hit`        (`hit_count` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='人工校正的标准问答对（"AI 越用越准"的核心数据）';

-- 给 sys_user 加 auditor 角色支持（如果原表 role 已是 varchar 就只需要在代码层校验）
-- 现有表已使用 varchar role，不动表结构，仅约定 role IN ('admin','auditor','user')


-- ───────────────────────────────────────────────────────────────
-- [8/23] dept-position-acl-schema.sql
-- ───────────────────────────────────────────────────────────────
SET NAMES utf8mb4;
-- =====================================================================
-- 任务 7 · 职位独立知识库
--   sys_department  · 部门（树形组织架构）
--   sys_position    · 职位（业务角色，区别于 sys_user.role 系统角色）
--   sys_user        · 扩展字段 department_id + position_id
--   kb_acl          · 知识库 × 职位 的访问控制 (read/write/admin)
--   kb_knowledge_base · 扩展 visibility 字段
-- 运行: mysql -uroot -p docmind < sql/dept-position-acl-schema.sql
-- =====================================================================

-- ─────────────────────────────────────────────
-- 1) 部门表
-- ─────────────────────────────────────────────
DROP TABLE IF EXISTS `sys_department`;
CREATE TABLE `sys_department` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `name`        VARCHAR(60)  NOT NULL                COMMENT '部门名',
    `parent_id`   BIGINT       NULL                    COMMENT '父部门 ID（NULL 表示一级）',
    `description` VARCHAR(200) NULL,
    `permissions` TEXT NULL COMMENT '功能权限点 JSON 数组(NULL=继承基线) · #3',
    `sort_order`  INT          NOT NULL DEFAULT 100,
    `enabled`     TINYINT(1)   NOT NULL DEFAULT 1,
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`     TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_parent` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组织部门 · 树形';

-- 预置一级部门样例
INSERT INTO `sys_department` (`name`, `parent_id`, `sort_order`, `description`) VALUES
('总部',     NULL, 10, '公司总部 / 默认部门'),
('技术中心', NULL, 20, '产品研发与技术运维'),
('市场销售', NULL, 30, '市场推广与销售'),
('人事行政', NULL, 40, 'HR · 行政 · 法务'),
('财务',     NULL, 50, '财务部门');


-- ─────────────────────────────────────────────
-- 2) 职位表（业务角色，独立于 sys_user.role）
-- ─────────────────────────────────────────────
DROP TABLE IF EXISTS `sys_position`;
CREATE TABLE `sys_position` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `name`          VARCHAR(60)  NOT NULL                COMMENT '职位名',
    `code`          VARCHAR(40)  NOT NULL                COMMENT '英文 code · 唯一',
    `department_id` BIGINT       NULL                    COMMENT '默认所属部门（可空·跨部门职位）',
    `description`   VARCHAR(200) NULL                    COMMENT '职责说明',
    `permissions`   TEXT         NULL                    COMMENT '功能权限点 JSON 数组(NULL=继承部门/基线) · #3',
    `level`         INT          NOT NULL DEFAULT 1      COMMENT '职级 1-10',
    `sort_order`    INT          NOT NULL DEFAULT 100,
    `enabled`       TINYINT(1)   NOT NULL DEFAULT 1,
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`       TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`),
    KEY `idx_dept` (`department_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='职位 · 业务角色';

-- 预置 7 个典型职位
INSERT INTO `sys_position` (`name`, `code`, `department_id`, `description`, `level`, `sort_order`) VALUES
('CEO',          'ceo',          1, '首席执行官',         10, 10),
('技术总监',     'tech_lead',    2, '技术中心负责人',       8, 20),
('Java 工程师',  'java_dev',     2, '后端 Java 开发',       3, 30),
('前端工程师',   'frontend_dev', 2, '前端 / Web 开发',      3, 40),
('销售经理',     'sales_mgr',    3, '销售部主管',           6, 50),
('HR 经理',     'hr_mgr',       4, 'HR 行政',              6, 60),
('财务专员',     'finance_staff', 5, '财务核算 · 报销审批',  3, 70);


-- ─────────────────────────────────────────────
-- 3) sys_user 扩展（不修改已有字段）
--    用 IF NOT EXISTS 类逻辑保险一点 · 这里 MySQL 不支持 ADD COLUMN IF NOT EXISTS
--    所以执行前确认表里没有 department_id / position_id 字段
-- ─────────────────────────────────────────────
ALTER TABLE `sys_user`
    ADD COLUMN `department_id` BIGINT NULL COMMENT '部门 ID（关联 sys_department）' AFTER `role`,
    ADD COLUMN `position_id`   BIGINT NULL COMMENT '职位 ID（关联 sys_position）'   AFTER `department_id`;

-- 默认把 admin 用户绑定到总部 / CEO 职位，让超级管理员能访问所有 KB
UPDATE `sys_user` SET `department_id` = 1, `position_id` = 1 WHERE `username` = 'admin';


-- ─────────────────────────────────────────────
-- 4) 知识库 ACL 表
-- ─────────────────────────────────────────────
DROP TABLE IF EXISTS `kb_acl`;
CREATE TABLE `kb_acl` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT,
    `kb_id`       BIGINT      NOT NULL                  COMMENT '关联 kb_knowledge_base.id',
    `position_id` BIGINT      NOT NULL                  COMMENT '关联 sys_position.id',
    `permission`  VARCHAR(10) NOT NULL DEFAULT 'read'   COMMENT 'read · 检索；write · 上传；admin · 删除/授权',
    `granted_by`  BIGINT      NULL                      COMMENT '授权人 user_id',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`     TINYINT(1)  NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_kb_pos` (`kb_id`, `position_id`),
    KEY `idx_kb`  (`kb_id`),
    KEY `idx_pos` (`position_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库 × 职位 访问控制';


-- ─────────────────────────────────────────────
-- 5) kb_knowledge_base 增加 visibility 字段
--    public  · 所有人可读（兼容现有 KB 默认行为）
--    scoped  · 按 kb_acl 控制
--    private · 仅创建者可见
-- ─────────────────────────────────────────────
ALTER TABLE `kb_knowledge_base`
    ADD COLUMN `visibility` VARCHAR(20) NOT NULL DEFAULT 'public'
        COMMENT 'public · 所有人 / scoped · 按 ACL / private · 仅创建者'
        AFTER `category_user_set`;

-- 既有 KB 默认 public（不改变行为）· 客户后续按需收紧


-- ───────────────────────────────────────────────────────────────
-- [9/23] kb-acl-department-migration.sql
-- ───────────────────────────────────────────────────────────────
SET NAMES utf8mb4;
-- =====================================================================
-- 任务 7 补强 · kb_acl 增加部门级授权
--
-- 之前：一条 ACL 必须绑定 position_id（职位级）
-- 现在：position_id 或 department_id 二选一（业务约定 · 不在 DB 层强约束）
--   - 部门级支持向下继承（含所有子部门用户）
--   - 职位级精确到单一角色
--
-- 运行: mysql -uroot -p docmind < sql/kb-acl-department-migration.sql
-- =====================================================================

ALTER TABLE `kb_acl`
    ADD COLUMN `department_id` BIGINT NULL
        COMMENT '部门级授权 · NULL 表示用 position_id'
        AFTER `position_id`,
    -- 业务约束：position_id 和 department_id 不能同时为空，也不能同时非空
    -- 此约束放在应用层校验（DB CHECK 跨版本兼容性差）
    MODIFY COLUMN `position_id` BIGINT NULL COMMENT '职位级授权 · NULL 表示用 department_id';

-- 替换原唯一约束：之前是 (kb_id, position_id) UK；现在按 subject 类型区分
ALTER TABLE `kb_acl` DROP INDEX `uk_kb_pos`;

-- 新增双索引（不强 UK，应用层保证幂等）
ALTER TABLE `kb_acl`
    ADD KEY `idx_dept` (`department_id`);


-- ───────────────────────────────────────────────────────────────
-- [10/23] audit-pii-schema.sql
-- ───────────────────────────────────────────────────────────────
SET NAMES utf8mb4;
-- =====================================================================
-- 任务 12 · 审计日志 + PII 脱敏配置
--   audit_log     · 全量操作审计（合规必备）
--   pii_config    · PII 脱敏开关与策略配置（单行）
-- 运行: mysql -uroot -p docmind < sql/audit-pii-schema.sql
-- =====================================================================

DROP TABLE IF EXISTS `audit_log`;
CREATE TABLE `audit_log` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`       BIGINT       NULL                    COMMENT '操作人 user_id（系统自动 / 未登录场景为 NULL）',
    `username`      VARCHAR(60)  NULL                    COMMENT '操作人 username 冗余字段（用户后续被删也能查）',
    `action`        VARCHAR(60)  NOT NULL                COMMENT '动作 code · 如 user.login / kb.upload / kb.delete / acl.grant',
    `action_label`  VARCHAR(120) NULL                    COMMENT '动作中文描述',
    `target_type`   VARCHAR(40)  NULL                    COMMENT '目标类型 · 如 kb / user / api_key / golden_pair',
    `target_id`     VARCHAR(80)  NULL                    COMMENT '目标 ID',
    `target_name`   VARCHAR(200) NULL                    COMMENT '目标显示名 · 冗余便于日志可读',
    `status`        VARCHAR(20)  NOT NULL DEFAULT 'success' COMMENT 'success / failure',
    `detail_json`   JSON         NULL                    COMMENT '详细参数 / 响应（脱敏后）',
    `error_msg`     VARCHAR(500) NULL,
    `ip`            VARCHAR(60)  NULL,
    `user_agent`    VARCHAR(255) NULL,
    `latency_ms`    INT          NOT NULL DEFAULT 0,
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user`        (`user_id`, `created_at` DESC),
    KEY `idx_action`      (`action`,  `created_at` DESC),
    KEY `idx_target`      (`target_type`, `target_id`),
    KEY `idx_created_at`  (`created_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审计日志 · 合规追溯';


DROP TABLE IF EXISTS `pii_config`;
CREATE TABLE `pii_config` (
    `id`               BIGINT      NOT NULL AUTO_INCREMENT,
    `enabled`          TINYINT(1)  NOT NULL DEFAULT 1   COMMENT '全局总开关',
    `mask_phone`       TINYINT(1)  NOT NULL DEFAULT 1   COMMENT '手机号脱敏',
    `mask_id_card`     TINYINT(1)  NOT NULL DEFAULT 1   COMMENT '身份证号脱敏',
    `mask_bank_card`   TINYINT(1)  NOT NULL DEFAULT 1   COMMENT '银行卡号脱敏',
    `mask_email`       TINYINT(1)  NOT NULL DEFAULT 0   COMMENT '邮箱脱敏（默认关，可能影响联系信息检索）',
    `mask_address`     TINYINT(1)  NOT NULL DEFAULT 0   COMMENT '地址脱敏（误判率高，默认关）',
    `apply_on_upload`  TINYINT(1)  NOT NULL DEFAULT 0   COMMENT '上传文档时入库前脱敏（不可逆）· 默认关',
    `apply_on_response` TINYINT(1) NOT NULL DEFAULT 1   COMMENT '问答响应时脱敏（DB 不动）· 默认开',
    `apply_on_audit`   TINYINT(1)  NOT NULL DEFAULT 1   COMMENT '写审计日志前脱敏 detail_json',
    `updated_by`       BIGINT      NULL,
    `update_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='PII 脱敏全局配置 · 单行';

-- 默认配置（首次启用时插入）
INSERT INTO `pii_config` (`enabled`, `mask_phone`, `mask_id_card`, `mask_bank_card`,
                           `mask_email`, `mask_address`, `apply_on_upload`, `apply_on_response`, `apply_on_audit`)
VALUES (1, 1, 1, 1, 0, 0, 0, 1, 1);


-- ───────────────────────────────────────────────────────────────
-- [11/23] usage-stats-schema.sql
-- ───────────────────────────────────────────────────────────────
SET NAMES utf8mb4;
-- =====================================================================
-- 任务 13 · 用量统计与历史对话分析
--   model_pricing  · 模型计费配置（input/output 单价 · 手填，需定期对照阿里官方价更新）
--   usage_daily    · 用户每日用量聚合（一行 = 一个 user 一天）
-- 运行: mysql -uroot -p docmind < sql/usage-stats-schema.sql
--
-- 成本计算精度说明：
--   - Chat 类（qwen-plus/gpt-4o/deepseek/...）：用 LLM 返回的真实 token，准确
--   - Vision/Video/Embedding/TTS/ASR：按规则估算（字符数/秒数 × 单价），误差 20-50%
--   - 阿里实际账单是唯一权威来源 · 本表用于内部预算 / 趋势对比 / 用户配额
--   - 单价变更：直接 UPDATE model_pricing SET ... 后重启或调 refreshPricing()
-- =====================================================================

DROP TABLE IF EXISTS `model_pricing`;
CREATE TABLE `model_pricing` (
    `id`               BIGINT      NOT NULL AUTO_INCREMENT,
    `model_name`       VARCHAR(80) NOT NULL                COMMENT '模型名 · 如 qwen-plus / qwen-vl-max / text-embedding-v3',
    `category`         VARCHAR(20) NOT NULL                COMMENT 'chat · vision · embedding · asr · ocr',
    `input_price_per_1k`  DECIMAL(10,6) NOT NULL DEFAULT 0.000000  COMMENT '每 1K input token 价格（人民币元）',
    `output_price_per_1k` DECIMAL(10,6) NOT NULL DEFAULT 0.000000  COMMENT '每 1K output token 价格',
    `unit_price`       DECIMAL(10,6) NULL                  COMMENT '按调用次数计费的单价（如 OCR 一次 0.05 元）',
    `description`      VARCHAR(200) NULL,
    `enabled`          TINYINT(1)  NOT NULL DEFAULT 1,
    `create_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_model` (`model_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型计费配置';

-- 预置主流模型价格（2026 年 6 月阿里云百炼价格 · 单位：元/1K tokens）
INSERT INTO `model_pricing` (`model_name`, `category`, `input_price_per_1k`, `output_price_per_1k`, `description`) VALUES
('qwen-turbo',          'chat',      0.000300, 0.000600, '通义千问 Turbo · 最便宜'),
('qwen-plus',           'chat',      0.000800, 0.002000, '通义千问 Plus · 主用'),
('qwen-max',            'chat',      0.020000, 0.060000, '通义千问 Max · 最强'),
('qwen-vl-max',         'vision',    0.020000, 0.020000, '通义千问 VL · 图片识别'),
('qwen-vl-plus',        'vision',    0.008000, 0.008000, '通义千问 VL Plus · 便宜版'),
('text-embedding-v3',   'embedding', 0.000500, 0.000000, 'Embedding · 仅 input 计费'),
('deepseek-chat',       'chat',      0.000270, 0.001100, 'DeepSeek-V3'),
('gpt-4o',              'chat',      0.018000, 0.072000, 'OpenAI GPT-4o'),
('gpt-4o-mini',         'chat',      0.000540, 0.002160, 'OpenAI GPT-4o-mini');

-- 按次计费类（不分 input/output）
INSERT INTO `model_pricing` (`model_name`, `category`, `unit_price`, `description`) VALUES
('paraformer-v2',       'asr',       0.000200, 'ASR · 每秒 0.0002 元（按音频时长）'),
('gte-rerank',          'rerank',    0.000050, 'Rerank · 每次调用 0.00005 元');


DROP TABLE IF EXISTS `usage_daily`;
CREATE TABLE `usage_daily` (
    `id`              BIGINT      NOT NULL AUTO_INCREMENT,
    `user_id`         BIGINT      NOT NULL,
    `stat_date`       DATE        NOT NULL                COMMENT '统计日期 YYYY-MM-DD',
    `chat_count`      INT         NOT NULL DEFAULT 0      COMMENT '对话次数（assistant 消息数）',
    `input_tokens`    BIGINT      NOT NULL DEFAULT 0      COMMENT '当日总 input tokens',
    `output_tokens`   BIGINT      NOT NULL DEFAULT 0,
    `embedding_tokens` BIGINT     NOT NULL DEFAULT 0,
    `vision_calls`    INT         NOT NULL DEFAULT 0      COMMENT 'VL 图片识别次数',
    `asr_seconds`     INT         NOT NULL DEFAULT 0      COMMENT 'ASR 音频秒数累计',
    `cost_cny`        DECIMAL(12,4) NOT NULL DEFAULT 0.0000 COMMENT '当日累计成本（人民币）',
    `golden_hit_count` INT        NOT NULL DEFAULT 0      COMMENT '命中 Golden Pair 次数',
    `create_time`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_date` (`user_id`, `stat_date`),
    KEY `idx_date` (`stat_date` DESC),
    KEY `idx_cost` (`cost_cny` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户每日用量聚合';


-- qa_message 加 tokens / cost / model 字段（如果还没加）
-- 注意：ALTER TABLE 不能 IF NOT EXISTS，重复执行会报错，按需删除
ALTER TABLE `qa_message`
    ADD COLUMN `model_name`    VARCHAR(80) NULL COMMENT '本条消息用的模型' AFTER `feedback`,
    ADD COLUMN `input_tokens`  INT NOT NULL DEFAULT 0 AFTER `model_name`,
    ADD COLUMN `output_tokens` INT NOT NULL DEFAULT 0 AFTER `input_tokens`,
    ADD COLUMN `cost_cny`      DECIMAL(10,6) NOT NULL DEFAULT 0.000000 AFTER `output_tokens`,
    ADD COLUMN `latency_ms`    INT NOT NULL DEFAULT 0 AFTER `cost_cny`;


-- ───────────────────────────────────────────────────────────────
-- [12/23] api-key-schema.sql
-- ───────────────────────────────────────────────────────────────
SET NAMES utf8mb4;
-- =====================================================================
-- 任务 11 · API Key 对外开放（含 11.6 每 KB 独立 API）
--   api_key       · 对外 API key
--   api_call_log  · 调用日志（按 KB 维度可查）
-- 运行: mysql -uroot -p docmind < sql/api-key-schema.sql
-- =====================================================================

DROP TABLE IF EXISTS `api_key`;
CREATE TABLE `api_key` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `name`              VARCHAR(80)  NOT NULL                COMMENT '展示名（如"客户 X 接入"）',
    `key_prefix`        VARCHAR(20)  NOT NULL                COMMENT '前缀 mk_xxxxxxxx 用于列表展示',
    `key_hash`          VARCHAR(128) NOT NULL                COMMENT '完整 key 的 SHA-256（不存明文）',
    `allowed_kb_ids`    JSON         NOT NULL                COMMENT '可访问 KB id 数组 · 11.6 至少 1 个',
    `scope_type`        VARCHAR(20)  NOT NULL DEFAULT 'kb_scoped' COMMENT 'kb_scoped 每 KB · user_scoped 用户级',
    `monthly_quota`     INT          NOT NULL DEFAULT 10000  COMMENT '月调用次数上限',
    `rate_limit_qps`    INT          NOT NULL DEFAULT 10     COMMENT '每秒请求数（暂未启用 · 后续上 Redis 令牌桶）',
    `month_used`        INT          NOT NULL DEFAULT 0      COMMENT '当月已用次数',
    `month_key`         VARCHAR(7)   NULL                    COMMENT '统计月份 YYYY-MM，跨月自动归零',
    `total_calls`       BIGINT       NOT NULL DEFAULT 0      COMMENT '累计调用',
    `last_used_at`      DATETIME     NULL,
    `expire_at`         DATETIME     NULL                    COMMENT 'NULL 表示永不过期',
    `status`            VARCHAR(20)  NOT NULL DEFAULT 'active' COMMENT 'active / revoked / expired',
    `created_by`        BIGINT       NOT NULL,
    `description`       VARCHAR(300) NULL,
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`           TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_hash` (`key_hash`),
    KEY `idx_status` (`status`),
    KEY `idx_creator` (`created_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对外 API Key · 11.6 支持 per-KB 独立';


DROP TABLE IF EXISTS `api_call_log`;
CREATE TABLE `api_call_log` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `key_id`         BIGINT       NOT NULL                COMMENT '关联 api_key.id',
    `kb_id`          BIGINT       NULL                    COMMENT '11.6 · 该调用作用于哪个 KB（用于 KB 维度查日志）',
    `api`            VARCHAR(40)  NOT NULL                COMMENT '/v3/chat · /v3/search · /v3/upload',
    `question`       VARCHAR(500) NULL                    COMMENT '问题截前 500 字（避免日志爆炸）',
    `status_code`    INT          NOT NULL                COMMENT 'HTTP 状态码',
    `input_tokens`   INT          NOT NULL DEFAULT 0,
    `output_tokens`  INT          NOT NULL DEFAULT 0,
    `cost_cny`       DECIMAL(10,4) NOT NULL DEFAULT 0.0000 COMMENT '本次成本（人民币）',
    `latency_ms`     INT          NOT NULL DEFAULT 0,
    `ip`             VARCHAR(60)  NULL,
    `user_agent`     VARCHAR(255) NULL,
    `error_msg`      VARCHAR(500) NULL,
    `called_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_key`    (`key_id`,  `called_at` DESC),
    KEY `idx_kb`     (`kb_id`,   `called_at` DESC),
    KEY `idx_status` (`status_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对外 API 调用日志';


-- ───────────────────────────────────────────────────────────────
-- [13/23] coach-schema.sql
-- ───────────────────────────────────────────────────────────────
SET NAMES utf8mb4;
-- =====================================================================
-- 任务 9 · 教练模式
--   coach_session  · 一次练习会话（含 KB 范围/难度/进度/总分）
--   coach_question · 单道题（题干/类型/选项/标准答案/来源 chunk）
--   coach_answer   · 用户作答（含 LLM 评分/反馈/推荐复习章节）
-- 运行: mysql -uroot -p docmind < sql/coach-schema.sql
-- =====================================================================

DROP TABLE IF EXISTS `coach_answer`;
DROP TABLE IF EXISTS `coach_question`;
DROP TABLE IF EXISTS `coach_session`;

CREATE TABLE `coach_session` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`         BIGINT       NOT NULL,
    `kb_ids`          JSON         NULL                       COMMENT '本 session 的 KB 范围（数组，留空 = 全量可访问 KB）',
    `kb_scope_label`  VARCHAR(200) NULL                       COMMENT '范围摘要（前端展示用 · 如「合同审查 / 财务制度」）',
    `difficulty`      VARCHAR(10)  NOT NULL DEFAULT 'medium'  COMMENT 'easy · medium · hard',
    `question_total`  INT          NOT NULL DEFAULT 10        COMMENT '本 session 计划题数',
    `question_done`   INT          NOT NULL DEFAULT 0         COMMENT '已答题数',
    `correct_count`   INT          NOT NULL DEFAULT 0         COMMENT '答对题数（score≥80）',
    `total_score`     INT          NOT NULL DEFAULT 0         COMMENT '累计分（满分=question_done*100）',
    `status`          VARCHAR(20)  NOT NULL DEFAULT 'active'  COMMENT 'active · finished · abandoned',
    `start_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `end_at`          DATETIME     NULL,
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`         TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_user_time` (`user_id`, `start_at` DESC),
    KEY `idx_status`    (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='教练模式 · 练习会话';

CREATE TABLE `coach_question` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `session_id`         BIGINT       NOT NULL,
    `seq`                INT          NOT NULL                   COMMENT '该 session 内顺序号 1,2,3...',
    `question`           TEXT         NOT NULL,
    `question_type`      VARCHAR(20)  NOT NULL DEFAULT 'short_answer'  COMMENT 'short_answer · multiple_choice · true_false',
    `options`            JSON         NULL                       COMMENT '选择题选项（["A. xx","B. xx"]）',
    `expected_answer`    TEXT         NOT NULL                   COMMENT '标准答案',
    `explanation`        TEXT         NULL                       COMMENT '出题时附带的讲解',
    `source_chunk_id`    BIGINT       NULL                       COMMENT '题目来源 chunk id',
    `source_kb_id`       BIGINT       NULL                       COMMENT '题目来源 KB id',
    `source_kb_name`     VARCHAR(200) NULL                       COMMENT '来源 KB 名称（冗余 · 删 KB 后仍能显示）',
    `difficulty`         VARCHAR(10)  NOT NULL DEFAULT 'medium',
    `create_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_session_seq` (`session_id`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='教练模式 · 单道题';

CREATE TABLE `coach_answer` (
    `id`                  BIGINT      NOT NULL AUTO_INCREMENT,
    `question_id`         BIGINT      NOT NULL,
    `session_id`          BIGINT      NOT NULL,
    `user_id`             BIGINT      NOT NULL,
    `user_answer`         TEXT        NULL,
    `score`               INT         NOT NULL DEFAULT 0          COMMENT '0-100',
    `judgment`            VARCHAR(20) NOT NULL DEFAULT 'wrong'    COMMENT 'correct · partial · wrong',
    `feedback`            TEXT        NULL                        COMMENT 'LLM 反馈话术',
    `recommend_chunk_ids` JSON        NULL                        COMMENT '推荐复习的 chunk ids',
    `answer_at`           DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_question` (`question_id`),
    KEY `idx_session`      (`session_id`),
    KEY `idx_user_time`    (`user_id`, `answer_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='教练模式 · 用户作答记录';


-- ───────────────────────────────────────────────────────────────
-- [14/23] coach-source-quote-migration.sql
-- ───────────────────────────────────────────────────────────────
SET NAMES utf8mb4;
-- =====================================================================
-- 任务 9 教练模式 · 反幻觉强化迁移
-- 给 coach_question 加 source_quote 列，存放出题时引用的原文片段
-- 服务端会校验该片段必须是源 chunk 的真实子串，否则视为幻觉题，丢弃
-- 运行: mysql -uroot -p docmind < sql/coach-source-quote-migration.sql
--
-- 幂等：用 information_schema 判断是否已加列，避免 ERROR 1060 (Duplicate column)
-- =====================================================================

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME   = 'coach_question'
      AND COLUMN_NAME  = 'source_quote'
);

SET @ddl := IF(
    @col_exists = 0,
    'ALTER TABLE `coach_question` ADD COLUMN `source_quote` TEXT NULL COMMENT ''出题时引用的原文片段（反幻觉证据）'' AFTER `source_kb_name`',
    'SELECT ''[skip] source_quote 列已存在，无需迁移'' AS msg'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- ───────────────────────────────────────────────────────────────
-- [15/23] voice-persona-schema.sql
-- ───────────────────────────────────────────────────────────────
SET NAMES utf8mb4;
-- =====================================================================
-- 任务 14 · 实时语音对话
--   voice_persona · 音色配置（CosyVoice 预置音色 + 后续自定义复刻）
-- 运行: mysql -uroot -p docmind < sql/voice-persona-schema.sql
-- =====================================================================

DROP TABLE IF EXISTS `voice_persona`;
CREATE TABLE `voice_persona` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `name`         VARCHAR(80)  NOT NULL                COMMENT '展示名（如「龙小淳 · 知性女声」）',
    `voice_id`     VARCHAR(80)  NOT NULL                COMMENT '厂商音色 ID（如 longxiaochun_v2）',
    `provider`     VARCHAR(40)  NOT NULL DEFAULT 'cosyvoice' COMMENT 'cosyvoice / volcengine / minimax / custom',
    `model`        VARCHAR(80)  NOT NULL DEFAULT 'cosyvoice-v2' COMMENT 'TTS 模型版本',
    `gender`       VARCHAR(10)  NULL                    COMMENT 'male / female / child / neutral',
    `language`     VARCHAR(20)  NULL DEFAULT 'zh-CN'    COMMENT '主要语言',
    `description`  VARCHAR(200) NULL                    COMMENT '风格描述',
    `tags`         VARCHAR(200) NULL                    COMMENT '标签（逗号分隔）',
    `sample_rate`  INT          NOT NULL DEFAULT 22050  COMMENT '生成音频采样率',
    `owner_user_id` BIGINT      NULL                    COMMENT '自定义音色归属用户（预置为 NULL）',
    `is_default`   TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '系统默认音色（1 个）',
    `enabled`      TINYINT(1)   NOT NULL DEFAULT 1,
    `sort_order`   INT          NOT NULL DEFAULT 100,
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`      TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_voice` (`provider`, `voice_id`),
    KEY `idx_owner` (`owner_user_id`),
    KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='音色配置 · CosyVoice 预置 + 自定义复刻';

-- 预置 CosyVoice v2 常用音色（阿里云百炼官方）
INSERT INTO `voice_persona`
    (`name`, `voice_id`, `provider`, `model`, `gender`, `language`, `description`, `tags`, `is_default`, `sort_order`) VALUES
('龙小淳 · 知性女声',  'longxiaochun_v2', 'cosyvoice', 'cosyvoice-v2', 'female', 'zh-CN', '清晰知性，适合知识库讲解', '客服,讲解', 1, 10),
('龙小诚 · 沉稳男声',  'longxiaocheng_v2','cosyvoice', 'cosyvoice-v2', 'male',   'zh-CN', '沉稳低音，适合正式问答', '正式,助理', 0, 20),
('龙小白 · 活力女声',  'longxiaobai_v2', 'cosyvoice', 'cosyvoice-v2', 'female', 'zh-CN', '年轻活力，适合互动对话', '互动,年轻', 0, 30),
('龙华 · 温和男声',    'longhua_v2',     'cosyvoice', 'cosyvoice-v2', 'male',   'zh-CN', '温和友好，适合教练模式', '温和,教练', 0, 40),
('龙婉 · 温柔女声',    'longwan_v2',     'cosyvoice', 'cosyvoice-v2', 'female', 'zh-CN', '温柔自然，适合长时段陪伴', '温柔,陪伴', 0, 50),
('龙铁牛 · 大叔音',    'longlaotie_v2',  'cosyvoice', 'cosyvoice-v2', 'male',   'zh-CN', '北方腔大叔音，幽默接地气', '幽默,大叔', 0, 60);


-- ───────────────────────────────────────────────────────────────
-- [16/23] voice-clone-migration.sql
-- ───────────────────────────────────────────────────────────────
SET NAMES utf8mb4;
-- =====================================================================
-- 任务 14.1 · 自定义音色复刻
--   voice_persona 加 status / sample_object_name / error_message 字段
--   支持用户上传自己的录音 → DashScope 复刻 → 拿到 voice_id 入库
-- 运行: mysql -uroot -p docmind < sql/voice-clone-migration.sql
--
-- 幂等：用 information_schema 判断列是否已加
-- =====================================================================

-- ── status: cloning / ready / failed ───────────────────────────
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'voice_persona' AND COLUMN_NAME = 'status');
SET @ddl := IF(@col = 0,
    'ALTER TABLE `voice_persona` ADD COLUMN `status` VARCHAR(20) NOT NULL DEFAULT ''ready'' COMMENT ''复刻状态: cloning(复刻中) / ready(可用) / failed(失败)''',
    'SELECT ''[skip] voice_persona.status 已存在'' AS msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ── sample_object_name: 用户上传的样本音频对象名 ─────────────────
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'voice_persona' AND COLUMN_NAME = 'sample_object_name');
SET @ddl := IF(@col = 0,
    'ALTER TABLE `voice_persona` ADD COLUMN `sample_object_name` VARCHAR(500) NULL COMMENT ''用户上传的样本音频对象名 (OSS/MinIO)''',
    'SELECT ''[skip] sample_object_name 已存在'' AS msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ── error_message: 复刻失败原因 ────────────────────────────────
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'voice_persona' AND COLUMN_NAME = 'error_message');
SET @ddl := IF(@col = 0,
    'ALTER TABLE `voice_persona` ADD COLUMN `error_message` VARCHAR(500) NULL COMMENT ''失败原因（用户可见）''',
    'SELECT ''[skip] error_message 已存在'' AS msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ── 把已有 6 个预置音色的 provider 改为 'cosyvoice' 系列时全部 status=ready
UPDATE `voice_persona` SET `status` = 'ready' WHERE `status` IS NULL OR `status` = '';


-- ───────────────────────────────────────────────────────────────
-- [17/23] conversation-flag-migration.sql
-- ───────────────────────────────────────────────────────────────
SET NAMES utf8mb4;
-- =====================================================================
-- 任务 13.5 · qa_conversation 加敏感标记 4 列
--   is_flagged / flag_note / flagged_by / flagged_at
-- 主管 / 管理员对 KB 问答中"涉密 / 越权 / 失实"等对话做标记，用于后续审计
-- 运行: mysql -uroot -p docmind < sql/conversation-flag-migration.sql
--
-- 幂等：用 information_schema 判断每个列是否已加
-- =====================================================================

-- ── is_flagged ───────────────────────────────────────────
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME   = 'qa_conversation'
               AND COLUMN_NAME  = 'is_flagged');
SET @ddl := IF(@col = 0,
    'ALTER TABLE `qa_conversation` ADD COLUMN `is_flagged` TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否标记敏感（主管标）''',
    'SELECT ''[skip] is_flagged 已存在'' AS msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ── flag_note ───────────────────────────────────────────
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME   = 'qa_conversation'
               AND COLUMN_NAME  = 'flag_note');
SET @ddl := IF(@col = 0,
    'ALTER TABLE `qa_conversation` ADD COLUMN `flag_note` VARCHAR(500) NULL COMMENT ''标记备注''',
    'SELECT ''[skip] flag_note 已存在'' AS msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ── flagged_by ───────────────────────────────────────────
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME   = 'qa_conversation'
               AND COLUMN_NAME  = 'flagged_by');
SET @ddl := IF(@col = 0,
    'ALTER TABLE `qa_conversation` ADD COLUMN `flagged_by` BIGINT NULL COMMENT ''标记人 user_id''',
    'SELECT ''[skip] flagged_by 已存在'' AS msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ── flagged_at ───────────────────────────────────────────
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME   = 'qa_conversation'
               AND COLUMN_NAME  = 'flagged_at');
SET @ddl := IF(@col = 0,
    'ALTER TABLE `qa_conversation` ADD COLUMN `flagged_at` DATETIME NULL COMMENT ''标记时间''',
    'SELECT ''[skip] flagged_at 已存在'' AS msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ── 检索索引：按用户+时间倒序、按敏感筛选 ─────────────────────
SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME   = 'qa_conversation'
               AND INDEX_NAME   = 'idx_flagged');
SET @ddl := IF(@idx = 0,
    'CREATE INDEX `idx_flagged` ON `qa_conversation`(`is_flagged`, `flagged_at` DESC)',
    'SELECT ''[skip] idx_flagged 已存在'' AS msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ── qa_message.content 加全文索引，支撑关键词搜索 ────────────
-- 注意：MySQL 8.0 InnoDB 全文索引对中文需要 ngram parser
SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME   = 'qa_message'
               AND INDEX_NAME   = 'idx_content_ft');
SET @ddl := IF(@idx = 0,
    'ALTER TABLE `qa_message` ADD FULLTEXT INDEX `idx_content_ft`(`content`) WITH PARSER ngram',
    'SELECT ''[skip] idx_content_ft 已存在'' AS msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;


-- ───────────────────────────────────────────────────────────────
-- [18/23] retrieval-log-migration.sql
-- ───────────────────────────────────────────────────────────────
SET NAMES utf8mb4;
-- =====================================================================
-- qa_message · 加 retrieval_log 列
--
-- 用于持久化 RAG 检索日志（Query 改写 / 多路召回数 / RRF / 重排 / 命中状态）
-- 之前只在 SSE done 事件里发给前端，没存 DB 导致刷新或切会话后看不到
-- 运行: mysql -uroot -p docmind < sql/retrieval-log-migration.sql
--
-- 幂等：用 information_schema 判断是否已加列
-- =====================================================================

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME   = 'qa_message'
      AND COLUMN_NAME  = 'retrieval_log'
);

SET @ddl := IF(
    @col_exists = 0,
    'ALTER TABLE `qa_message` ADD COLUMN `retrieval_log` JSON NULL COMMENT ''RAG 检索过程日志（query 改写/召回数/重排/命中）'' AFTER `reflection_log`',
    'SELECT ''[skip] retrieval_log 列已存在，无需迁移'' AS msg'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- ───────────────────────────────────────────────────────────────
-- [19/23] usage-cost-backfill.sql
-- ───────────────────────────────────────────────────────────────
SET NAMES utf8mb4;
-- =====================================================================
-- 历史 usage_daily.cost_cny 回填
--
-- 背景：ModelPricing 实体早期有 `inputPricePer1k` 字段
-- MyBatis-Plus 驼峰转下划线对 "Per1k" 产生 "per1k"（漏 _），
-- 导致 pricingCache 加载失败 → cost_cny 一直写入 0。
-- 修复后（实体加 @TableField("input_price_per_1k") 显式列名）新数据已正常计费，
-- 但历史空 cost 仍需按 qwen-plus 单价回填。
--
-- 幂等：仅更新 cost_cny = 0 且 tokens > 0 的行（已有真实数据的行不动）。
-- 运行: mysql -uroot -p --default-character-set=utf8mb4 docmind < sql/usage-cost-backfill.sql
--   或: ./sql/run.sh usage-cost-backfill.sql
-- =====================================================================

UPDATE usage_daily ud
JOIN model_pricing mp ON mp.model_name = 'qwen-plus'
SET ud.cost_cny = ROUND(
      (mp.input_price_per_1k * ud.input_tokens
        + mp.output_price_per_1k * ud.output_tokens) / 1000,
      4)
WHERE (ud.input_tokens > 0 OR ud.output_tokens > 0)
  AND ud.cost_cny = 0;

-- 输出影响行数
SELECT ROW_COUNT() AS backfilled_rows;


-- ───────────────────────────────────────────────────────────────
-- [20/23] model-pricing-video-tts-migration.sql
-- ───────────────────────────────────────────────────────────────
SET NAMES utf8mb4;
-- ═══════════════════════════════════════════════════════════════════
-- model_pricing 补全 · 视频理解 + TTS + 语音复刻
-- 幂等：用 INSERT IGNORE，已存在的不重复
-- ═══════════════════════════════════════════════════════════════════

-- ① 视频理解模型（QwenVideoUnderstandingService 用）
--    qwen-vl-max-latest：阿里官方价格 input ¥0.02/1K token, output ¥0.02/1K token
--    qwen2.5-vl-7b-instruct：便宜版 input ¥0.002/1K, output ¥0.005/1K
INSERT IGNORE INTO `model_pricing`
  (`model_name`, `category`, `input_price_per_1k`, `output_price_per_1k`, `description`)
VALUES
  ('qwen-vl-max-latest',       'video',  0.020000, 0.020000, '通义千问 VL Max Latest · 视频原生理解'),
  ('qwen2.5-vl-7b-instruct',   'video',  0.002000, 0.005000, '通义千问 VL 7B · 视频便宜版');

-- ② TTS 合成（CosyVoice 系列）
--    cosyvoice-v2：阿里官方价格 ¥0.0002/字（按合成字符数计费）
--    cosyvoice-clone-v1：自定义音色，定价同 v2
--    用 unit_price（按次/按字单价）字段存
INSERT IGNORE INTO `model_pricing`
  (`model_name`, `category`, `unit_price`, `description`)
VALUES
  ('cosyvoice-v2',             'tts',    0.000200, 'TTS · 每字 0.0002 元（按合成字符数）'),
  ('cosyvoice-clone-v1',       'tts',    0.000200, 'TTS · 自定义音色 · 每字 0.0002 元');

-- ③ Voice Enrollment（音色复刻 · 一次性按调用计费）
INSERT IGNORE INTO `model_pricing`
  (`model_name`, `category`, `unit_price`, `description`)
VALUES
  ('voice-enrollment',         'voice-clone', 0.500000, '音色复刻 · 每次 0.5 元');


-- ───────────────────────────────────────────────────────────────
-- [21/23] usage-reconcile-schema.sql
-- ───────────────────────────────────────────────────────────────
SET NAMES utf8mb4;
-- =====================================================================
-- 任务 13.7 · 阿里云账单对账
--   usage_reconcile_daily · 每天一行 · 我们算的 vs 阿里官方账单
--   阿里 BSS Open API 数据 T+1，每天 3:30 拉昨日数据
-- 运行: mysql -uroot -p docmind < sql/usage-reconcile-schema.sql
-- =====================================================================

DROP TABLE IF EXISTS `usage_reconcile_daily`;
CREATE TABLE `usage_reconcile_daily` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT,
    `stat_date`           DATE         NOT NULL                  COMMENT '账单日期 YYYY-MM-DD',
    `product_code`        VARCHAR(40)  NOT NULL                  COMMENT '阿里产品码 · 如 dashscope / oss',
    `product_name`        VARCHAR(80)  NULL                      COMMENT '中文名（dashscope→百炼）',
    `official_amount_cny` DECIMAL(14,4) NOT NULL DEFAULT 0       COMMENT '阿里官方账单金额（人民币元）',
    `our_calc_amount_cny` DECIMAL(14,4) NOT NULL DEFAULT 0       COMMENT '我们内部按 token 算的金额',
    `diff_amount_cny`     DECIMAL(14,4) NOT NULL DEFAULT 0       COMMENT '差额 = 我们 - 官方',
    `diff_pct`            DECIMAL(8,4)  NOT NULL DEFAULT 0       COMMENT '差异百分比 = diff / official',
    `alerted`             TINYINT(1)   NOT NULL DEFAULT 0        COMMENT '是否已触发告警（>10% 自动 1）',
    `bss_raw_json`        TEXT         NULL                      COMMENT 'BSS 接口原始响应 · 排查用',
    `note`                VARCHAR(200) NULL                      COMMENT '备注（如手动调整、节日折扣等）',
    `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_date_product` (`stat_date`, `product_code`),
    KEY `idx_date` (`stat_date` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='阿里云账单对账（任务 13.7）';


-- ───────────────────────────────────────────────────────────────
-- [22/23] knowledge-collection-schema.sql
-- ───────────────────────────────────────────────────────────────
SET NAMES utf8mb4;
-- ═══════════════════════════════════════════════════════════════════
-- 任务 15 · 知识库架构重构（一次性把架构做对）
--   原来：1 个上传文件 = 1 条 kb_knowledge_base 记录 = 1 个"知识库"
--   现在：1 个 knowledge_collection = N 个文档（kb_knowledge_base）
--
--   kb_knowledge_base 加 collection_id 外键 · 自动迁移老数据
--   kb_acl 复用现有结构，新增 ref_type 字段同时支持 collection / document
-- ═══════════════════════════════════════════════════════════════════

-- ─────────────────────────────────────────────
-- 1) 知识库集合表（真正的"知识库"）
-- ─────────────────────────────────────────────
DROP TABLE IF EXISTS `knowledge_collection`;
CREATE TABLE `knowledge_collection` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `name`           VARCHAR(80)  NOT NULL                          COMMENT '知识库名 · 如 HR 制度库 / 产品手册库',
    `description`    VARCHAR(500) NULL                              COMMENT '描述',
    `icon`           VARCHAR(40)  NULL DEFAULT 'FolderOpened'       COMMENT 'Element Plus 图标名',
    `color`          VARCHAR(20)  NULL DEFAULT '#7C3AED'            COMMENT '主色（16 进制）',
    `category_code`  VARCHAR(50)  NULL                              COMMENT '业务分类（关联 kb_category.code · 可选）',
    `visibility`     VARCHAR(20)  NOT NULL DEFAULT 'public'         COMMENT 'public/scoped/private',
    `owner_user_id`  BIGINT       NULL                              COMMENT '创建人 · 系统级库可为 NULL',
    `doc_count`      INT          NOT NULL DEFAULT 0                COMMENT '缓存 · 文档数（异步更新）',
    `total_chunks`   INT          NOT NULL DEFAULT 0                COMMENT '缓存 · 切片总数',
    `is_system`      TINYINT(1)   NOT NULL DEFAULT 0                COMMENT '1=系统内置库（不可删）',
    `persona_id`     BIGINT       NULL     DEFAULT NULL             COMMENT '绑定的 Soul 人格 id（null=用全局默认人格）',
    `skill_pack_id`  BIGINT       NULL     DEFAULT NULL             COMMENT '绑定的技能包 id（null=不套用技能）',
    `coach_rule`     TEXT         NULL     DEFAULT NULL             COMMENT '教练模式·本知识库出题规则（null=默认）',
    `voice_ids`      VARCHAR(255) NULL     DEFAULT NULL             COMMENT '本知识库可用音色 voice_persona.id 列表（逗号分隔，多选）；空=不限制',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`        TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_owner`     (`owner_user_id`),
    KEY `idx_visibility`(`visibility`),
    KEY `idx_category`  (`category_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库集合 · 多文档容器';

-- ─────────────────────────────────────────────
-- 2) kb_knowledge_base 加 collection_id 外键（可空 · 兼容旧数据）
-- ─────────────────────────────────────────────
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name   = 'kb_knowledge_base'
      AND column_name  = 'collection_id'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE kb_knowledge_base
        ADD COLUMN collection_id BIGINT NULL COMMENT "所属知识库（NULL=未归档散文档）" AFTER category,
        ADD INDEX idx_collection (collection_id)',
    'SELECT "collection_id 列已存在 · 跳过 ADD" AS skip');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ─────────────────────────────────────────────
-- 3) kb_acl 加 ref_type 字段（同时支持库级 / 文档级 ACL）
-- ─────────────────────────────────────────────
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name   = 'kb_acl'
      AND column_name  = 'ref_type'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE kb_acl
        ADD COLUMN ref_type VARCHAR(20) NOT NULL DEFAULT "document" COMMENT "授权目标类型 · collection/document" AFTER kb_id',
    'SELECT "ref_type 列已存在 · 跳过 ADD" AS skip');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ─────────────────────────────────────────────
-- 4) 自动迁移老数据 · 按 category 自动建知识库 + 把文档归类
--    迁移规则：
--    - 已存在 collection_id 的文档不动
--    - 其余按 (owner_user_id, category) 分组建库
--    - 兜底库名："{用户名}的{分类}知识库" / "未分类知识库"
-- ─────────────────────────────────────────────

-- 4.1 每个 (user_id, category) 组合建一个知识库
INSERT INTO knowledge_collection (name, description, icon, color, category_code, visibility, owner_user_id, is_system, doc_count)
SELECT
    CONCAT(
        COALESCE((SELECT username FROM sys_user WHERE id = mkb.user_id), '系统'),
        ' 的 ',
        COALESCE(mkb.category, '默认'),
        ' 知识库'
    ) AS name,
    CONCAT('从已上传的 ', mkb.category, ' 类文档自动归集 · 迁移时间 ', NOW()) AS description,
    'FolderOpened' AS icon,
    '#7C3AED' AS color,
    mkb.category AS category_code,
    'public' AS visibility,
    mkb.user_id AS owner_user_id,
    0 AS is_system,
    COUNT(*) AS doc_count
FROM kb_knowledge_base mkb
WHERE mkb.deleted = 0
  AND mkb.collection_id IS NULL
GROUP BY mkb.user_id, mkb.category
ON DUPLICATE KEY UPDATE doc_count = VALUES(doc_count);

-- 4.2 把每个文档归到对应的库（用 (user_id, category) 匹配）
UPDATE kb_knowledge_base mkb
JOIN knowledge_collection kc
    ON kc.owner_user_id <=> mkb.user_id
   AND kc.category_code <=> mkb.category
SET mkb.collection_id = kc.id
WHERE mkb.deleted = 0
  AND mkb.collection_id IS NULL;

-- 4.3 修正 doc_count 缓存 + 计算切片数
UPDATE knowledge_collection kc
SET
    kc.doc_count = (
        SELECT COUNT(*) FROM kb_knowledge_base
        WHERE collection_id = kc.id AND deleted = 0
    ),
    kc.total_chunks = (
        SELECT IFNULL(SUM(chunk_count), 0) FROM kb_knowledge_base
        WHERE collection_id = kc.id AND deleted = 0
    );


-- ───────────────────────────────────────────────────────────────
-- [23/23] api-key-collection-migration.sql
-- ───────────────────────────────────────────────────────────────
SET NAMES utf8mb4;
-- ═══════════════════════════════════════════════════════════════════
-- 任务 15.1 · API Key 改绑「知识库（集合）」
--   原来：api_key.allowed_kb_ids 装的是 kb_knowledge_base.id（其实是文档 id）
--   现在：api_key.allowed_collection_ids 装的是 knowledge_collection.id（真知识库）
--   兼容：旧字段保留，service 优先用 collection_ids，没有时回退到 kb_ids
-- ═══════════════════════════════════════════════════════════════════

-- 1) 加新字段（幂等）
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name   = 'api_key'
      AND column_name  = 'allowed_collection_ids'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE api_key
        ADD COLUMN allowed_collection_ids JSON NULL COMMENT "可访问知识库 id 数组 · 任务 15 优先生效" AFTER allowed_kb_ids',
    'SELECT "allowed_collection_ids 列已存在 · 跳过 ADD" AS skip');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) api_call_log 加 collection_id（按库维度查日志）
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name   = 'api_call_log'
      AND column_name  = 'collection_id'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE api_call_log
        ADD COLUMN collection_id BIGINT NULL COMMENT "调用关联的知识库（任务 15）" AFTER kb_id,
        ADD INDEX idx_collection (collection_id, called_at DESC)',
    'SELECT "collection_id 列已存在 · 跳过 ADD" AS skip');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3) 自动迁移老 key · 把 allowed_kb_ids（文档 id）展开到对应的 collection_id
--    规则：对每条 key，把它绑定的文档列表 union 它们所在的 collection_id 列表
--    （JSON_ARRAYAGG 不支持 DISTINCT · 用子查询先去重再聚合）
UPDATE api_key ak
SET ak.allowed_collection_ids = (
    SELECT JSON_ARRAYAGG(cid) FROM (
        SELECT DISTINCT kb.collection_id AS cid
        FROM kb_knowledge_base kb
        WHERE kb.collection_id IS NOT NULL
          AND JSON_CONTAINS(ak.allowed_kb_ids, CAST(kb.id AS JSON))
    ) t
)
WHERE ak.allowed_collection_ids IS NULL
  AND ak.allowed_kb_ids IS NOT NULL;

-- ════════════════════════════════════════════════════════════════
-- 钉钉机器人多实例配置（可绑定多个机器人，各连各的知识库）
-- ════════════════════════════════════════════════════════════════
DROP TABLE IF EXISTS `dingtalk_bot`;
CREATE TABLE `dingtalk_bot` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `name`             VARCHAR(60)  NOT NULL                COMMENT '机器人名称',
    `token`            VARCHAR(64)  NOT NULL                COMMENT '回调路由 token（唯一）',
    `app_secret_enc`   TEXT         NULL                    COMMENT '钉钉 AppSecret 密文（验签用）',
    `collection_id`    BIGINT       NULL                    COMMENT '绑定的知识库 id',
    `signature_verify` TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '是否验签（调试期可关）',
    `enabled`          TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '是否启用',
    `description`      VARCHAR(200)  NULL,
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`          TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_token` (`token`),
    KEY `idx_collection` (`collection_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='钉钉机器人配置';

-- 父子切片增强字段放在种子数据之后添加，保持上方历史 INSERT VALUES 列数兼容。
ALTER TABLE `kb_chunk`
  ADD COLUMN `parent_chunk_id` BIGINT NULL COMMENT '所属父切片ID；NULL兼容历史普通切片' AFTER `chunk_index`,
  ADD INDEX `idx_kb_chunk_parent` (`parent_chunk_id`);
