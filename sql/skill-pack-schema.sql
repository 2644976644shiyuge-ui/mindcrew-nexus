SET NAMES utf8mb4;
-- =====================================================================
-- 技能包 · 提问时可选的场景化能力
-- 运行: mysql ... docmind < sql/skill-pack-schema.sql （RDS 用 DMS 粘贴执行）
-- =====================================================================
CREATE TABLE IF NOT EXISTS `skill_pack` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `name`           VARCHAR(64)  NOT NULL COMMENT '技能名称',
    `icon`           VARCHAR(32)  NULL COMMENT '图标(emoji)',
    `description`    VARCHAR(255) NULL COMMENT '一句话简介',
    `instruction`    TEXT         NULL COMMENT '技能指令(角色/风格/输出规范)',
    `collection_ids` VARCHAR(512) NULL COMMENT '绑定知识库 id 列表 JSON；空=全部',
    `enabled`        TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    `sort_order`     INT          NOT NULL DEFAULT 0,
    `create_time`    DATETIME     NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`    DATETIME     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`        TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_enabled_sort` (`enabled`, `sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='技能包';

-- 预置几个助贷销售示例技能包（按需改）
INSERT INTO `skill_pack` (`name`, `icon`, `description`, `instruction`, `enabled`, `sort_order`) VALUES
('产品匹配顾问', '🎯', '按客户条件推荐最优产品+话术',
 '你是助贷产品匹配顾问。根据用户给出的客户条件(公积金/个税/有无房/网贷等)，严格依据知识库中的产品资料，推荐 2-3 款最匹配的产品，并按"①产品名 → ②匹配理由 → ③报单话术"的结构输出。只用资料中确有的产品，不编造。', 1, 1),
('话术生成器', '💬', '基于团队沉淀生成标准话术',
 '你是销售话术专家。根据用户描述的场景，基于知识库中的话术资料，输出一段可直接照读的标准话术，口语化、有说服力、合规。', 1, 2),
('异议处理', '🧩', '客户异议的应对话术',
 '你是异议处理专家。针对用户给出的客户异议(如"利率高""额度低")，给出 1-2 套应对话术和替代方案，依据知识库资料，不夸大不违规。', 1, 3),
('合规自检', '✅', '检查话术是否违规',
 '你是合规审查员。检查用户粘贴的话术是否存在违规/夸大/误导表述，逐条指出问题并给出合规改写建议，依据知识库中的合规要求。', 1, 4);
