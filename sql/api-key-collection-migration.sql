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
