package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问答页音视频附件的异步转写记录。
 *
 * 文档类附件（pdf/word/...）走同步提取、不入此表；只有音频/视频上传后异步转写，
 * 转写文本供：① 问答时注入上下文；② 管理员在历史对话「加入知识库」时复用。
 */
@Data
@TableName("chat_attachment")
public class ChatAttachment {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对象存储 objectName（chat-attachment/uuid.ext） */
    private String objectName;

    private String originalName;

    /** 小写扩展名 */
    private String ext;

    /** audio / video */
    private String mediaType;

    /** transcribing / ready / failed */
    private String status;

    /** 转写/理解后的纯文本 */
    private String transcript;

    /** 转写文本字符数 */
    private Integer chars;

    private String errorMsg;

    private Long ownerUserId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
