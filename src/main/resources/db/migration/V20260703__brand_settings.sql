INSERT INTO sys_setting (setting_key, setting_value) VALUES ('brand.system_name', 'MindCrew')
    ON DUPLICATE KEY UPDATE setting_key = setting_key;

INSERT INTO sys_setting (setting_key, setting_value) VALUES ('brand.logo_url', NULL)
    ON DUPLICATE KEY UPDATE setting_key = setting_key;
