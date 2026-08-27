package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 钉钉机器人聊天记录 · 每条 @ 提问 + 机器人回答落一行
 */
@Data
@TableName("dingtalk_chat_log")
public class DingtalkChatLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long botId;

    /** 冗余机器人名，便于机器人删除后仍可查历史 */
    private String botName;

    /** 会话 ID / 标题 / 类型（1=单聊 2=群聊） */
    private String conversationId;
    private String conversationTitle;
    private String conversationType;

    /** 提问人昵称 + 企业内 staffId */
    private String senderNick;
    private String senderId;

    private String question;
    private String answer;

    /** 回答耗时（毫秒） */
    private Integer answerMs;

    /** 钉钉消息 ID（排查/去重用） */
    private String msgId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
