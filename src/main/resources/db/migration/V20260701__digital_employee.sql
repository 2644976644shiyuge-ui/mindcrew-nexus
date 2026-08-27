-- 数字员工模块 · Phase 1 MVP

CREATE TABLE IF NOT EXISTS `digital_employee` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(128) NOT NULL COMMENT '智能体名称',
    `avatar` VARCHAR(512) DEFAULT NULL,
    `summary` VARCHAR(512) DEFAULT NULL COMMENT '简介',
    `system_prompt` MEDIUMTEXT COMMENT '智能体设定',
    `model_provider` VARCHAR(64) DEFAULT NULL COMMENT '模型提供方标识',
    `model_name` VARCHAR(128) DEFAULT NULL COMMENT '模型名',
    `feature_flags` JSON DEFAULT NULL COMMENT 'webSearch,memoryEnabled等',
    `scenario_config` JSON DEFAULT NULL COMMENT '场景可视化配置',
    `primary_scenario` VARCHAR(64) DEFAULT 'general_qa',
    `status` VARCHAR(32) NOT NULL DEFAULT 'draft' COMMENT 'draft/published/offline',
    `visibility` VARCHAR(32) NOT NULL DEFAULT 'restricted' COMMENT 'public/restricted',
    `kb_only_reply` TINYINT NOT NULL DEFAULT 0 COMMENT '仅知识库回答',
    `sort_order` INT NOT NULL DEFAULT 0,
    `created_by` BIGINT DEFAULT NULL,
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_de_status` (`status`),
    KEY `idx_de_created_by` (`created_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数字员工';

CREATE TABLE IF NOT EXISTS `digital_employee_knowledge` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `employee_id` BIGINT NOT NULL,
    `collection_id` BIGINT NOT NULL COMMENT '知识库集合 id',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_emp_col` (`employee_id`, `collection_id`),
    KEY `idx_dek_employee` (`employee_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `digital_employee_acl` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `employee_id` BIGINT NOT NULL,
    `principal_type` VARCHAR(32) NOT NULL COMMENT 'department/position/user',
    `principal_id` BIGINT NOT NULL,
    `permission` VARCHAR(16) NOT NULL DEFAULT 'use' COMMENT 'use/manage',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_dea_employee` (`employee_id`),
    KEY `idx_dea_principal` (`principal_type`, `principal_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `digital_employee_usage_daily` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `employee_id` BIGINT NOT NULL,
    `stat_date` DATE NOT NULL,
    `session_count` INT NOT NULL DEFAULT 0,
    `message_count` INT NOT NULL DEFAULT 0,
    `token_estimate` BIGINT NOT NULL DEFAULT 0,
    `active_user_count` INT NOT NULL DEFAULT 0,
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_emp_date` (`employee_id`, `stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 会话关联数字员工（若列已存在请跳过本句）
-- ALTER TABLE `qa_conversation` ADD COLUMN `digital_employee_id` BIGINT DEFAULT NULL COMMENT '数字员工 id' AFTER `source`;