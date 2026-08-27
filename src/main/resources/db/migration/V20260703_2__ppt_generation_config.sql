INSERT IGNORE INTO `sys_ai_config`
  (`config_key`,`config_value`,`value_type`,`group_name`,`label`,`description`,`default_value`,`min_value`,`max_value`)
VALUES
 ('ppt_generation.enabled','false','string','model','PPT生成·启用外部服务',
   'false=使用内置商业版PPT渲染；true=优先调用外部PPT模型/服务，失败可回退','false',NULL,NULL),
 ('ppt_generation.api-url','','string','model','PPT生成·服务地址',
   '外部PPT生成服务 HTTP 地址。协议：POST JSON {title, markdown, branding}，返回 pptx 二进制文件','',NULL,NULL),
 ('ppt_generation.api-key','','string','model','PPT生成·API Key',
   '调用外部PPT生成服务时通过 Authorization: Bearer 传递。内置渲染无需填写','',NULL,NULL),
 ('ppt_generation.timeout-seconds','120','integer','model','PPT生成·超时时间',
   '外部PPT生成服务请求超时时间，单位秒','120','10','600'),
 ('ppt_generation.fallback-on-error','true','string','model','PPT生成·失败回退',
   '外部PPT服务失败时是否自动回退到内置商业版渲染，商用建议开启','true',NULL,NULL);
