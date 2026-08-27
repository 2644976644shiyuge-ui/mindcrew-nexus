-- ============================================================
-- 系统编排 · 可配置工作流（独立功能，不改动任何现有表）
-- 仅管理员可用。三张表：模板定义 / 运行实例 / 节点运行记录
-- ============================================================

CREATE TABLE IF NOT EXISTS `workflow_definition` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `name`          VARCHAR(128) NOT NULL                COMMENT '工作流名称',
  `description`   VARCHAR(512)          DEFAULT NULL   COMMENT '描述',
  `graph_json`    LONGTEXT     NOT NULL                COMMENT '图定义：{nodes:[...], edges:[...]}',
  `owner_user_id` BIGINT                DEFAULT NULL   COMMENT '创建者',
  `enabled`       TINYINT      NOT NULL DEFAULT 1      COMMENT '1=启用 0=停用',
  `deleted`       TINYINT      NOT NULL DEFAULT 0,
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_owner` (`owner_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流模板定义';

CREATE TABLE IF NOT EXISTS `workflow_run` (
  `id`             BIGINT      NOT NULL AUTO_INCREMENT,
  `workflow_id`    BIGINT      NOT NULL                COMMENT '所属模板',
  `user_id`        BIGINT               DEFAULT NULL   COMMENT '发起人',
  `status`         VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/COMPLETED/FAILED',
  `variables_json` LONGTEXT             DEFAULT NULL   COMMENT '运行输入变量',
  `result_json`    LONGTEXT             DEFAULT NULL   COMMENT '运行结果（最终变量/输出）',
  `error_msg`      VARCHAR(1024)        DEFAULT NULL,
  `elapsed_ms`     BIGINT               DEFAULT 0,
  `start_time`     DATETIME             DEFAULT NULL,
  `end_time`       DATETIME             DEFAULT NULL,
  `create_time`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_wf` (`workflow_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流运行实例';

CREATE TABLE IF NOT EXISTS `workflow_node_run` (
  `id`           BIGINT      NOT NULL AUTO_INCREMENT,
  `run_id`       BIGINT      NOT NULL                COMMENT '所属运行实例',
  `node_id`      VARCHAR(64) NOT NULL                COMMENT '图中节点 id',
  `node_type`    VARCHAR(32) NOT NULL                COMMENT '节点类型',
  `seq`          INT         NOT NULL DEFAULT 0      COMMENT '执行顺序',
  `status`       VARCHAR(16) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/COMPLETED/FAILED/SKIPPED',
  `input`        LONGTEXT             DEFAULT NULL,
  `output`       LONGTEXT             DEFAULT NULL,
  `error_msg`    VARCHAR(1024)        DEFAULT NULL,
  `elapsed_ms`   BIGINT               DEFAULT 0,
  `create_time`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_run` (`run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流节点运行记录';
