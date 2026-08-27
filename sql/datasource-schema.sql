SET NAMES utf8mb4;
-- =====================================================================
-- 可视化数据源 · 外部业务库（CRM 等）NL2SQL 分析
--   data_source         · 外部数据库连接配置（密码 AES 加密存储）
--   data_source_table   · 暴露给 LLM 的表/列语义元数据（NL2SQL 准确性关键）
--   data_source_acl     · 数据源 × 用户/职位/部门 访问控制（镜像 kb_acl）
--   data_source_query_log · 每次自然语言问数据的审计
--
-- ⚠️ 幂等集：全部 CREATE TABLE IF NOT EXISTS、无 DROP TABLE，
--    会被 sql/_sqlset.sh 归为「幂等迁移」，每次 deploy（init/update）自动安全重跑，永不丢数据。
--    手动导入（首次）：mysql -uroot -p docmind < sql/datasource-schema.sql
-- =====================================================================

-- ─────────────────────────────────────────────
-- 1) 数据源连接配置
--    visibility: public · 所有人可问 / scoped · 按 ACL / private · 仅创建者
-- ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS `data_source` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `name`             VARCHAR(80)  NOT NULL                COMMENT '数据源显示名（如：CRM 客户库）',
    `db_type`          VARCHAR(20)  NOT NULL DEFAULT 'mysql' COMMENT '数据库类型 · 当前仅 mysql',
    `host`             VARCHAR(120) NOT NULL,
    `port`             INT          NOT NULL DEFAULT 3306,
    `db_name`          VARCHAR(80)  NOT NULL                COMMENT '库名',
    `username`         VARCHAR(80)  NOT NULL                COMMENT '建议使用只读账号',
    `password_enc`     VARCHAR(512) NULL                    COMMENT 'AES-GCM 加密后的密码',
    `jdbc_params`      VARCHAR(300) NULL                    COMMENT '附加 JDBC 参数，如 useSSL=false&serverTimezone=UTC',
    `description`      VARCHAR(300) NULL                    COMMENT '业务说明（给 LLM 的库级语义）',
    `visibility`       VARCHAR(20)  NOT NULL DEFAULT 'private'
        COMMENT 'public · 所有人 / scoped · 按 ACL / private · 仅创建者',
    `status`           VARCHAR(20)  NOT NULL DEFAULT 'enabled' COMMENT 'enabled / disabled',
    `last_test_status` VARCHAR(20)  NULL                    COMMENT '最近一次连接测试结果 ok / fail',
    `last_test_time`   DATETIME     NULL,
    `last_test_error`  VARCHAR(500) NULL,
    `user_id`          BIGINT       NULL                    COMMENT '创建者',
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`          TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='外部数据源连接配置';


-- ─────────────────────────────────────────────
-- 2) 暴露给 LLM 的表/列语义元数据
--    只有 enabled=1 的表才会进入 NL2SQL 的 schema 提示词
--    columns_json: [{"name":"customer_name","type":"varchar","businessName":"客户名","description":"..."}]
-- ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS `data_source_table` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `datasource_id` BIGINT       NOT NULL,
    `table_name`    VARCHAR(120) NOT NULL                COMMENT '物理表名',
    `business_name` VARCHAR(120) NULL                    COMMENT '业务中文名（如：客户表）',
    `description`   VARCHAR(500) NULL                    COMMENT '表用途说明（给 LLM）',
    `columns_json`  MEDIUMTEXT   NULL                    COMMENT '列语义元数据 JSON 数组',
    `enabled`       TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '是否暴露给 NL2SQL',
    `sort_order`    INT          NOT NULL DEFAULT 100,
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`       TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ds_table` (`datasource_id`, `table_name`),
    KEY `idx_ds` (`datasource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据源表/列语义元数据';


-- ─────────────────────────────────────────────
-- 3) 数据源 ACL（镜像 kb_acl · 用户 / 职位 / 部门 三选一）
--    permission: read · 可用自然语言查询该数据源
--    部门级支持向下继承（含所有子部门用户）· 业务层校验三选一
-- ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS `data_source_acl` (
    `id`            BIGINT      NOT NULL AUTO_INCREMENT,
    `datasource_id` BIGINT      NOT NULL,
    `position_id`   BIGINT      NULL                    COMMENT '职位级授权',
    `department_id` BIGINT      NULL                    COMMENT '部门级授权（含子部门）',
    `user_id`       BIGINT      NULL                    COMMENT '用户级授权',
    `permission`    VARCHAR(10) NOT NULL DEFAULT 'read' COMMENT 'read · 查询；admin · 配置/授权',
    `granted_by`    BIGINT      NULL,
    `create_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`       TINYINT(1)  NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_ds`   (`datasource_id`),
    KEY `idx_pos`  (`position_id`),
    KEY `idx_dept` (`department_id`),
    KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据源 × 用户/职位/部门 访问控制';


-- ─────────────────────────────────────────────
-- 4) NL2SQL 审计日志
-- ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS `data_source_query_log` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `datasource_id` BIGINT       NULL,
    `user_id`       BIGINT       NULL,
    `question`      VARCHAR(1000) NULL                  COMMENT '原始自然语言问题',
    `generated_sql` TEXT         NULL                    COMMENT 'LLM 生成并通过校验的 SQL',
    `row_count`     INT          NULL,
    `latency_ms`    INT          NULL,
    `status`        VARCHAR(20)  NULL                    COMMENT 'ok / blocked / error',
    `error_msg`     VARCHAR(1000) NULL,
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_ds`   (`datasource_id`),
    KEY `idx_user` (`user_id`),
    KEY `idx_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='自然语言查数据库审计日志';
