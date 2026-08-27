SET NAMES utf8mb4;
-- =====================================================================
-- 历史 usage_daily.cost_cny 回填
--
-- 背景：ModelPricing 实体早期有 `inputPricePer1k` 字段
-- MyBatis-Plus 驼峰转下划线对 "Per1k" 产生 "per1k"（漏 _），
-- 导致 pricingCache 加载失败 → cost_cny 一直写入 0。
-- 修复后（实体加 @TableField("input_price_per_1k") 显式列名）新数据已正常计费，
-- 但历史空 cost 仍需按 qwen-plus 单价回填。
--
-- 幂等：仅更新 cost_cny = 0 且 tokens > 0 的行（已有真实数据的行不动）。
-- 运行: mysql -uroot -p --default-character-set=utf8mb4 docmind < sql/usage-cost-backfill.sql
--   或: ./sql/run.sh usage-cost-backfill.sql
-- =====================================================================

UPDATE usage_daily ud
JOIN model_pricing mp ON mp.model_name = 'qwen-plus'
SET ud.cost_cny = ROUND(
      (mp.input_price_per_1k * ud.input_tokens
        + mp.output_price_per_1k * ud.output_tokens) / 1000,
      4)
WHERE (ud.input_tokens > 0 OR ud.output_tokens > 0)
  AND ud.cost_cny = 0;

-- 输出影响行数
SELECT ROW_COUNT() AS backfilled_rows;
