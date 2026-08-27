SET NAMES utf8mb4;
-- =====================================================================
-- 问答排行加速索引
--   排行按 role='user' + create_time 范围扫描 qa_message，
--   加复合索引让区间过滤走索引，避免全表扫。
-- 幂等：索引不存在时创建。
-- =====================================================================
SET @index_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qa_message' AND INDEX_NAME = 'idx_qa_message_role_time');
SET @ddl := IF(@index_exists = 0,
    'CREATE INDEX `idx_qa_message_role_time` ON `qa_message` (`role`, `create_time`)',
    'SELECT ''[skip] idx_qa_message_role_time 已存在'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 取答案时按会话查 assistant 回复，已有 conversation_id 索引则可跳过下一行
SET @index_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qa_message' AND INDEX_NAME = 'idx_qa_message_conv_role');
SET @ddl := IF(@index_exists = 0,
    'CREATE INDEX `idx_qa_message_conv_role` ON `qa_message` (`conversation_id`, `role`, `id`)',
    'SELECT ''[skip] idx_qa_message_conv_role 已存在'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
