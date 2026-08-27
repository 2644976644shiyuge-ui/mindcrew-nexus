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
