package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 邀请码：管理员可生成多个，支持使用次数上限与过期时间。
 */
@Data
@TableName("invite_code")
public class InviteCode {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;

    /** 最大可用次数，null=不限 */
    private Integer maxUses;

    private Integer usedCount;

    private Integer enabled;

    /** 邀请码过期时间，null=不过期 */
    private LocalDateTime expireTime;

    private String remark;

    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
