SET NAMES utf8mb4;
-- =====================================================================
-- 任务 13.7 · 阿里云账单对账
--   usage_reconcile_daily · 每天一行 · 我们算的 vs 阿里官方账单
--   阿里 BSS Open API 数据 T+1，每天 3:30 拉昨日数据
-- 运行: mysql -uroot -p docmind < sql/usage-reconcile-schema.sql
-- =====================================================================

DROP TABLE IF EXISTS `usage_reconcile_daily`;
CREATE TABLE `usage_reconcile_daily` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT,
    `stat_date`           DATE         NOT NULL                  COMMENT '账单日期 YYYY-MM-DD',
    `product_code`        VARCHAR(40)  NOT NULL                  COMMENT '阿里产品码 · 如 dashscope / oss',
    `product_name`        VARCHAR(80)  NULL                      COMMENT '中文名（dashscope→百炼）',
    `official_amount_cny` DECIMAL(14,4) NOT NULL DEFAULT 0       COMMENT '阿里官方账单金额（人民币元）',
    `our_calc_amount_cny` DECIMAL(14,4) NOT NULL DEFAULT 0       COMMENT '我们内部按 token 算的金额',
    `diff_amount_cny`     DECIMAL(14,4) NOT NULL DEFAULT 0       COMMENT '差额 = 我们 - 官方',
    `diff_pct`            DECIMAL(8,4)  NOT NULL DEFAULT 0       COMMENT '差异百分比 = diff / official',
    `alerted`             TINYINT(1)   NOT NULL DEFAULT 0        COMMENT '是否已触发告警（>10% 自动 1）',
    `bss_raw_json`        TEXT         NULL                      COMMENT 'BSS 接口原始响应 · 排查用',
    `note`                VARCHAR(200) NULL                      COMMENT '备注（如手动调整、节日折扣等）',
    `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_date_product` (`stat_date`, `product_code`),
    KEY `idx_date` (`stat_date` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='阿里云账单对账（任务 13.7）';
