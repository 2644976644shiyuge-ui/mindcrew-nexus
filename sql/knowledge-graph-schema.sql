-- ============================================================
-- 知识图谱（Knowledge Graph）· 实体节点 + 关系边
-- ------------------------------------------------------------
-- 设计要点：
--   1) 抽取以「单文档(kb_id)」为粒度落库 —— 每篇文档抽出的实体/关系各占若干行，
--      带上 kb_id + collection_id 双键。这样删除/重处理某文档时只需 DELETE WHERE kb_id=?，
--      不必维护跨文档的引用计数，干净可回滚。
--   2) 展示以「知识库(collection_id)」为粒度聚合 —— 读取时在 Java 侧按 (name,type) 归并
--      同名实体、按 (source,target,relation) 归并同义边（文档量级小，聚合安全）。
--   3) 边用「实体名」而非 node_id 关联，避免跨文档 id 对齐问题，聚合即 GROUP BY name。
-- ============================================================

CREATE TABLE IF NOT EXISTS `kb_graph_node` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `kb_id`         BIGINT       NOT NULL                COMMENT '来源文档ID · kb_knowledge_base.id',
    `collection_id` BIGINT       NULL                    COMMENT '所属知识库(集合)ID · 散文档为 NULL',
    `name`          VARCHAR(255) NOT NULL                COMMENT '实体名称',
    `type`          VARCHAR(64)  NOT NULL DEFAULT '概念'  COMMENT '实体类型 · 人物/组织/地点/概念/事件/产品/时间/其它',
    `description`   VARCHAR(512) NULL                    COMMENT '实体简述（LLM 生成）',
    `weight`        INT          NOT NULL DEFAULT 1      COMMENT '权重/提及次数 · 聚合时累加',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_node_kb`         (`kb_id`),
    KEY `idx_node_collection` (`collection_id`),
    KEY `idx_node_name`       (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识图谱-实体节点';

CREATE TABLE IF NOT EXISTS `kb_graph_edge` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `kb_id`         BIGINT       NOT NULL                COMMENT '来源文档ID · kb_knowledge_base.id',
    `collection_id` BIGINT       NULL                    COMMENT '所属知识库(集合)ID · 散文档为 NULL',
    `source_name`   VARCHAR(255) NOT NULL                COMMENT '源实体名称',
    `target_name`   VARCHAR(255) NOT NULL                COMMENT '目标实体名称',
    `relation`      VARCHAR(128) NOT NULL                COMMENT '关系标签 · 如「属于/包含/影响/合作」',
    `description`   VARCHAR(512) NULL                    COMMENT '关系简述（LLM 生成）',
    `weight`        INT          NOT NULL DEFAULT 1      COMMENT '权重 · 聚合时累加',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_edge_kb`         (`kb_id`),
    KEY `idx_edge_collection` (`collection_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识图谱-关系边';
