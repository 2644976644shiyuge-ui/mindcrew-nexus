UPDATE `sys_ai_config`
SET `group_name` = 'ppt'
WHERE `config_key` LIKE 'ppt_generation.%';

INSERT IGNORE INTO `sys_ai_config`
  (`config_key`,`config_value`,`value_type`,`group_name`,`label`,`description`,`default_value`,`min_value`,`max_value`)
VALUES
 ('ppt_generation.service-provider','qwen-doc','string','ppt','AI PPT 服务商',
  '推荐阿里云 Qwen-Doc-Turbo；也支持 Gamma 和自定义 PPT Agent','qwen-doc',NULL,NULL),
 ('ppt_generation.theme-id','','string','ppt','服务商主题 ID',
  'Gamma Theme ID；留空时由服务商自动选择主题','',NULL,NULL),
 ('ppt_generation.qwen-mode','general','string','ppt','千问 PPT 模式',
  'general 为原生可编辑企业模板；creative 为视觉更丰富的图片型 PPT','general',NULL,NULL),
 ('ppt_generation.qwen-template-id','internet_01','string','ppt','千问 PPT 模板',
  '模板模式支持 internet_01、summary_01、thesis_01、news_01','internet_01',NULL,NULL),
 ('ppt_generation.planner-provider','dashscope','string','ppt','策划模型 Provider',
  '默认使用阿里云百炼 DashScope，也支持任意 OpenAI 兼容服务','dashscope',NULL,NULL),
 ('ppt_generation.model','qwen-plus','string','ppt','PPT 策划模型',
  '推荐 qwen-plus；重要汇报可选 qwen3.7-plus 或 qwen3.7-max','qwen-plus',NULL,NULL),
 ('ppt_generation.model-base-url','https://dashscope.aliyuncs.com/compatible-mode/v1','string','ppt','模型 Base URL',
  '千问 OpenAI 兼容地址；私有化部署可填写 vLLM/Ollama 兼容地址',
  'https://dashscope.aliyuncs.com/compatible-mode/v1',NULL,NULL),
 ('ppt_generation.model-api-key','','string','ppt','模型 API Key',
  'PPT Agent 调用策划模型使用的 API Key；留空时由 Agent 自身环境变量提供','',NULL,NULL),
 ('ppt_generation.poll-interval-ms','2000','integer','ppt','任务轮询间隔（毫秒）',
  '异步服务商任务状态查询间隔，建议 1500～5000 毫秒','2000','500','10000');

UPDATE `sys_ai_config`
SET `label` = '启用 PPT Agent',
    `description` = '开启后使用配置的 AI PPT API；关闭时使用内置安全渲染器',
    `config_value` = CASE WHEN `config_value` IN ('false', '0', '') THEN 'true' ELSE `config_value` END,
    `default_value` = 'true'
WHERE `config_key` = 'ppt_generation.enabled';

UPDATE `sys_ai_config`
SET `label` = 'API 地址',
    `description` = 'Qwen/Gamma 可留空使用默认地址；自定义直出需填写完整生成接口',
    `config_value` = CASE
      WHEN `config_value` IS NULL OR `config_value` = ''
      THEN 'https://dashscope.aliyuncs.com/compatible-mode/v1'
      ELSE `config_value`
    END,
    `default_value` = 'https://dashscope.aliyuncs.com/compatible-mode/v1'
WHERE `config_key` = 'ppt_generation.api-url';

UPDATE `sys_ai_config`
SET `description` = '推荐阿里云 Qwen-Doc-Turbo；也支持 Gamma 和自定义 PPT Agent',
    `config_value` = CASE WHEN `config_value` IN ('direct', '') THEN 'qwen-doc' ELSE `config_value` END,
    `default_value` = 'qwen-doc'
WHERE `config_key` = 'ppt_generation.service-provider';

UPDATE `sys_ai_config`
SET `label` = '服务商 API Key',
    `description` = '仅保存在服务端，用于调用 AI PPT 服务商'
WHERE `config_key` = 'ppt_generation.api-key';

UPDATE `sys_ai_config`
SET `config_key` = 'ppt_generation.planner-provider',
    `label` = '策划模型 Provider'
WHERE `config_key` = 'ppt_generation.provider'
  AND NOT EXISTS (
    SELECT 1 FROM (
      SELECT `config_key` FROM `sys_ai_config`
    ) AS existing_config
    WHERE existing_config.`config_key` = 'ppt_generation.planner-provider'
  );

UPDATE `sys_ai_config`
SET `config_value` = CASE WHEN `config_value` IN ('120', '180') THEN '600' ELSE `config_value` END,
    `default_value` = '600',
    `min_value` = '30',
    `max_value` = '900',
    `label` = '生成超时（秒）'
WHERE `config_key` = 'ppt_generation.timeout-seconds';
