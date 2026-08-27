SET NAMES utf8mb4;
-- =====================================================================
-- 教练规则 · 独立管理的出题模板（题型配比 + 附加规则），知识库下拉选用
-- 运行: mysql ... docmind < sql/coach-rule-schema.sql （RDS 用 DMS 粘贴执行）
-- =====================================================================
CREATE TABLE IF NOT EXISTS `coach_rule` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `name`         VARCHAR(64)  NOT NULL COMMENT '教练规则名称',
    `description`  VARCHAR(255) NULL COMMENT '一句话简介',
    `quiz_config`  VARCHAR(255) NULL COMMENT '题型配比 JSON，如 {"single":4,"multiple":2,"judge":2,"short":2}；空=默认均衡',
    `coach_rule`   TEXT         NULL COMMENT '附加出题规则（角色/侧重/风格）',
    `enabled`      TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    `sort_order`   INT          NOT NULL DEFAULT 0,
    `create_time`  DATETIME     NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_enabled_sort` (`enabled`, `sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教练规则';

INSERT INTO `coach_rule` (`name`, `description`, `quiz_config`, `coach_rule`, `enabled`, `sort_order`)
SELECT '标准均衡卷', '四种题型均衡，适合通用考核', '{"single":4,"multiple":2,"judge":2,"short":2}',
       '题干贴合知识库原文，难度适中，避免脑筋急转弯。', 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `coach_rule` WHERE `name` = '标准均衡卷' AND `deleted` = 0);

INSERT INTO `coach_rule` (`name`, `description`, `quiz_config`, `coach_rule`, `enabled`, `sort_order`)
SELECT '重单选快测', '以单选为主，快速检验记忆', '{"single":8,"judge":2}',
       '聚焦核心知识点，题干简短直接，适合碎片化快速练习。', 1, 2
WHERE NOT EXISTS (SELECT 1 FROM `coach_rule` WHERE `name` = '重单选快测' AND `deleted` = 0);

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_collection' AND COLUMN_NAME = 'coach_rule_id'
);
SET @ddl := IF(
    @column_exists = 0,
    'ALTER TABLE `knowledge_collection` ADD COLUMN `coach_rule_id` BIGINT NULL DEFAULT NULL COMMENT ''教练模式·绑定的教练规则 id（null=默认出题）''',
    'SELECT ''[skip] coach_rule_id 已存在'' AS msg'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
