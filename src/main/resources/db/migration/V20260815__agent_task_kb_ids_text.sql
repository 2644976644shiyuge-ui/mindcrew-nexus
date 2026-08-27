-- Agent 调研：kb_ids 字段从 varchar(200) 改成 TEXT
-- 原因：admin 用户可访问 246+ KB，JSON.toJSONString 拼接后超过 200 字符，导致 DataTruncation
-- 触发链路：POST /api/crew/tasks → CrewController.createTask → CrewOrchestrator.createTask → taskMapper.insert
-- 报错：MysqlDataTruncation: Data too long for column 'kb_ids' at row 1
ALTER TABLE agent_task MODIFY COLUMN kb_ids TEXT NULL COMMENT '检索知识库范围 JSON';
