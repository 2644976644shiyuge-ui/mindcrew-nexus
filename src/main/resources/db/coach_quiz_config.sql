-- ============================================================
-- 教练模式 · 知识库出题题型配比
-- 给 knowledge_collection 增加 quiz_config 字段（JSON 文本）
-- 形如 {"single":4,"multiple":2,"judge":2,"short":2}（每种题型各出几道）
-- null / 未配置 → 后端用四种题型默认均衡。
--
-- 注：MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，本脚本只需执行一次；
--     若报 "Duplicate column name 'quiz_config'" 说明已加过，忽略即可。
-- ============================================================
ALTER TABLE `knowledge_collection`
    ADD COLUMN `quiz_config` TEXT NULL COMMENT '教练出题题型配比 JSON' AFTER `coach_rule`;
