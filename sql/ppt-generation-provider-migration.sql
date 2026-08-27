-- AI PPT 默认配置迁移
-- 新装和旧版默认统一使用 Qwen-Doc-Turbo；API Key 仍保持空值，由管理员填写。

INSERT IGNORE INTO `sys_ai_config`
  (`config_key`,`config_value`,`value_type`,`group_name`,`label`,`description`,`default_value`,`min_value`,`max_value`)
VALUES
 ('ppt_generation.enabled','true','string','ppt','启用 PPT Agent',
  '开启后使用配置的 AI PPT API；关闭时使用内置安全渲染器','true',NULL,NULL),
 ('ppt_generation.service-provider','qwen-doc','string','ppt','AI PPT 服务商',
  '推荐阿里云 Qwen-Doc-Turbo；也支持 Gamma 和自定义 PPT Agent','qwen-doc',NULL,NULL),
 ('ppt_generation.api-url','https://dashscope.aliyuncs.com/compatible-mode/v1','string','ppt','API 地址',
  '阿里云百炼 OpenAI 兼容接口地址','https://dashscope.aliyuncs.com/compatible-mode/v1',NULL,NULL),
 ('ppt_generation.api-key','','string','ppt','服务商 API Key',
  '填写阿里云百炼 API Key','',NULL,NULL),
 ('ppt_generation.qwen-mode','general','string','ppt','千问 PPT 模式',
  'general 为原生可编辑企业模板；creative 为视觉更丰富的图片型 PPT','general',NULL,NULL),
 ('ppt_generation.qwen-template-id','internet_01','string','ppt','千问 PPT 模板',
  '模板模式支持 internet_01、summary_01、thesis_01、news_01','internet_01',NULL,NULL),
 ('ppt_generation.timeout-seconds','600','integer','ppt','生成超时（秒）',
  '完整 PPT 生成允许的最长时间','600','30','900'),
 ('ppt_generation.poll-interval-ms','2000','integer','ppt','任务轮询间隔（毫秒）',
  '异步服务商任务状态查询间隔','2000','500','10000'),
 ('ppt_generation.fallback-on-error','true','string','ppt','失败自动回退',
  'AI PPT 服务失败时自动使用内置渲染器','true',NULL,NULL);

UPDATE `sys_ai_config`
SET `config_value` = CASE WHEN `config_value` IN ('false', '0', '') THEN 'true' ELSE `config_value` END,
    `default_value` = 'true'
WHERE `config_key` = 'ppt_generation.enabled';

UPDATE `sys_ai_config`
SET `config_value` = CASE WHEN `config_value` IN ('direct', '') THEN 'qwen-doc' ELSE `config_value` END,
    `default_value` = 'qwen-doc'
WHERE `config_key` = 'ppt_generation.service-provider';

UPDATE `sys_ai_config`
SET `config_value` = CASE
      WHEN `config_value` IS NULL OR `config_value` = ''
      THEN 'https://dashscope.aliyuncs.com/compatible-mode/v1'
      ELSE `config_value`
    END,
    `default_value` = 'https://dashscope.aliyuncs.com/compatible-mode/v1'
WHERE `config_key` = 'ppt_generation.api-url';

UPDATE `sys_ai_config`
SET `config_value` = CASE WHEN `config_value` IN ('120', '180') THEN '600' ELSE `config_value` END,
    `default_value` = '600',
    `min_value` = '30',
    `max_value` = '900'
WHERE `config_key` = 'ppt_generation.timeout-seconds';
