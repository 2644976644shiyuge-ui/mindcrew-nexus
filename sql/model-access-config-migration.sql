SET NAMES utf8mb4;
-- =====================================================================
-- 模型接入可视化配置 · 向 sys_ai_config 注入「向量/图片/重排」接入项
--   新分组 group_name='model'，在「AI 配置中心」以独立卡片展示，可视化切换。
--   对话仍在「大模型 Provider」页切换；这里补齐 embedding / vision / rerank。
--
-- 幂等：config_key 唯一，INSERT IGNORE 重复执行自动跳过（不删表、不改已有值）
-- =====================================================================

INSERT IGNORE INTO `sys_ai_config`
  (`config_key`,`config_value`,`value_type`,`group_name`,`label`,`description`,`default_value`,`min_value`,`max_value`)
VALUES
 ('embedding.base-url','','string','model','向量·接入地址',
   '留空=跟随对话 Provider / application.yml；本地填 OpenAI 兼容地址（如 http://host:9997，提供 /v1/embeddings）','',NULL,NULL),
 ('embedding.api-key','','string','model','向量·API Key',
   '留空=跟随对话 Provider / yml；本地服务通常可随意填','',NULL,NULL),
 ('embedding.model','text-embedding-v3','string','model','向量·模型名',
   '如 text-embedding-v3（云）/ bge-m3（本地）','text-embedding-v3',NULL,NULL),
 ('embedding.dimensions','1024','integer','model','向量·维度',
   '须与 Milvus 维度一致；更换向量模型后历史向量需重新灌库，否则检索错乱','1024','64','4096'),
 ('vision.base-url','','string','model','图片识别·接入地址',
   '留空=跟随对话 Provider / yml；本地填多模态服务地址（OpenAI 兼容 vision chat）','',NULL,NULL),
 ('vision.api-key','','string','model','图片识别·API Key',
   '留空=跟随对话 Provider / yml','',NULL,NULL),
 ('reranker.api-url','https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank','string','model','重排·接入地址',
   'DashScope 默认；本地 bge-reranker 填 http://host:port/v1/rerank','https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank',NULL,NULL),
 ('reranker.api-key','','string','model','重排·API Key',
   '留空=跟随 llm.api-key','',NULL,NULL),
 ('reranker.model','gte-rerank','string','model','重排·模型名',
   '如 gte-rerank（云）/ bge-reranker-v2-m3（本地）','gte-rerank',NULL,NULL),
 ('reranker.protocol','dashscope','string','model','重排·协议',
   'dashscope=阿里云专有格式；jina=本地 bge(Xinference/TEI/Cohere) 通用 /v1/rerank 格式','dashscope',NULL,NULL);
