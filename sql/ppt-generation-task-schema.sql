-- 演示文稿后台生成任务 · 幂等建表
-- 由 sql/migrate.sh 自动发现，init / update 均会执行；不影响旧导出数据。
CREATE TABLE IF NOT EXISTS `ppt_generation_task` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL COMMENT '创建用户',
  `employee_id` BIGINT DEFAULT NULL COMMENT '来源数字员工',
  `conversation_id` BIGINT DEFAULT NULL COMMENT '来源数字员工会话',
  `parent_task_id` BIGINT DEFAULT NULL COMMENT '修改所基于的上一版本任务',
  `version_no` INT NOT NULL DEFAULT 1 COMMENT '同一演示文稿版本号',
  `operation_type` VARCHAR(24) NOT NULL DEFAULT 'create' COMMENT 'create/revise',
  `prompt` TEXT NOT NULL COMMENT '用户原始描述',
  `attachments` JSON DEFAULT NULL COMMENT '对话中上传的附件引用',
  `warnings` JSON DEFAULT NULL COMMENT '附件、知识库和预览处理提示',
  `title` VARCHAR(255) NOT NULL COMMENT '演示文稿标题',
  `page_count` INT NOT NULL DEFAULT 12 COMMENT '目标页数',
  `language` VARCHAR(32) NOT NULL DEFAULT 'zh-CN',
  `visual_style` VARCHAR(32) NOT NULL DEFAULT 'business',
  `audience` VARCHAR(255) DEFAULT NULL,
  `purpose` VARCHAR(255) DEFAULT NULL,
  `status` VARCHAR(24) NOT NULL DEFAULT 'queued' COMMENT 'queued/generating/completed/failed/canceled',
  `progress` INT NOT NULL DEFAULT 0,
  `stage` VARCHAR(128) DEFAULT NULL,
  `provider` VARCHAR(64) DEFAULT NULL,
  `provider_name` VARCHAR(128) DEFAULT NULL,
  `fallback_used` TINYINT NOT NULL DEFAULT 0,
  `object_name` VARCHAR(512) DEFAULT NULL,
  `preview_object_name` VARCHAR(512) DEFAULT NULL,
  `file_name` VARCHAR(255) DEFAULT NULL,
  `file_size` BIGINT DEFAULT NULL,
  `preview_file_size` BIGINT DEFAULT NULL,
  `error_message` VARCHAR(512) DEFAULT NULL,
  `user_message_id` BIGINT DEFAULT NULL,
  `assistant_message_id` BIGINT DEFAULT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `started_at` DATETIME DEFAULT NULL,
  `completed_at` DATETIME DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ppt_task_user_time` (`user_id`, `create_time`),
  KEY `idx_ppt_task_conversation` (`user_id`, `employee_id`, `conversation_id`, `create_time`),
  KEY `idx_ppt_task_status_time` (`status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='PPT 后台生成任务';

-- 兼容已经部署过旧版 ppt_generation_task 的环境。
SET @sql = IF(
  EXISTS(SELECT 1 FROM information_schema.TABLES
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ppt_generation_task')
  AND NOT EXISTS(SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ppt_generation_task'
                   AND COLUMN_NAME = 'employee_id'),
  'ALTER TABLE `ppt_generation_task` ADD COLUMN `employee_id` BIGINT NULL COMMENT ''来源数字员工'' AFTER `user_id`',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  NOT EXISTS(SELECT 1 FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ppt_generation_task'
               AND COLUMN_NAME = 'conversation_id'),
  'ALTER TABLE `ppt_generation_task` ADD COLUMN `conversation_id` BIGINT NULL COMMENT ''来源数字员工会话'' AFTER `employee_id`',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  NOT EXISTS(SELECT 1 FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ppt_generation_task'
               AND COLUMN_NAME = 'parent_task_id'),
  'ALTER TABLE `ppt_generation_task`
     ADD COLUMN `parent_task_id` BIGINT NULL COMMENT ''修改所基于的上一版本任务'' AFTER `conversation_id`,
     ADD COLUMN `version_no` INT NOT NULL DEFAULT 1 COMMENT ''同一演示文稿版本号'' AFTER `parent_task_id`,
     ADD COLUMN `operation_type` VARCHAR(24) NOT NULL DEFAULT ''create'' COMMENT ''create/revise'' AFTER `version_no`,
     ADD COLUMN `warnings` JSON NULL COMMENT ''附件、知识库和预览处理提示'' AFTER `attachments`,
     ADD COLUMN `preview_object_name` VARCHAR(512) NULL AFTER `object_name`,
     ADD COLUMN `preview_file_size` BIGINT NULL AFTER `file_size`,
     ADD COLUMN `user_message_id` BIGINT NULL AFTER `error_message`,
     ADD COLUMN `assistant_message_id` BIGINT NULL AFTER `user_message_id`',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  NOT EXISTS(SELECT 1 FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ppt_generation_task'
               AND COLUMN_NAME = 'attachments'),
  'ALTER TABLE `ppt_generation_task` ADD COLUMN `attachments` JSON NULL COMMENT ''对话中上传的附件引用'' AFTER `prompt`',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  NOT EXISTS(SELECT 1 FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ppt_generation_task'
               AND INDEX_NAME = 'idx_ppt_task_conversation'),
  'CREATE INDEX `idx_ppt_task_conversation` ON `ppt_generation_task` (`user_id`, `employee_id`, `conversation_id`, `create_time`)',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
