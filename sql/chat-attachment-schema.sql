-- 问答页音视频附件 · 异步转写记录
-- 文档类附件（pdf/word/...）仍走同步提取，不入此表；只有音频/视频走异步转写并在此登记。
-- 转写文本供：① 问答时注入上下文；② 管理员「加入知识库」时复用。
CREATE TABLE IF NOT EXISTS chat_attachment (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    object_name   VARCHAR(512)  NOT NULL COMMENT '对象存储 objectName（chat-attachment/uuid.ext）',
    original_name VARCHAR(512)            COMMENT '原始文件名',
    ext           VARCHAR(32)            COMMENT '小写扩展名',
    media_type    VARCHAR(16)            COMMENT 'audio / video',
    status        VARCHAR(16)   NOT NULL DEFAULT 'transcribing' COMMENT 'transcribing / ready / failed',
    transcript    LONGTEXT               COMMENT '转写/理解后的纯文本',
    chars         INT           NOT NULL DEFAULT 0 COMMENT '转写文本字符数',
    error_msg     VARCHAR(1024)          COMMENT '失败原因',
    owner_user_id BIGINT                 COMMENT '上传用户',
    create_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_object_name (object_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问答页音视频附件转写记录';
