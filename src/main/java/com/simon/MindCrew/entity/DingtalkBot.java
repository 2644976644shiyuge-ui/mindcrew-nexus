package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 钉钉机器人配置 · 对应表 dingtalk_bot
 * 支持配置任意多个机器人，各自绑定知识库、各有专属回调 token。
 */
@Data
@TableName("dingtalk_bot")
public class DingtalkBot {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 钉钉应用 Client ID(AppKey) · Stream 模式连接用 */
    private String appKey;

    /** 回调路由 token（HTTP 模式用，唯一），回调地址 = /api/dingtalk/callback/{token} */
    private String token;

    /** 钉钉 AppSecret 密文 · 不下发前端 */
    @JsonIgnore
    private String appSecretEnc;

    /** 绑定的知识库 id */
    private Long collectionId;

    private Integer signatureVerify;

    private Integer enabled;

    private String description;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

    /** 非持久化：是否已配置 AppSecret（供前端展示，不暴露明文） */
    @TableField(exist = false)
    private Boolean hasSecret;
}
