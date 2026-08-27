package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统用户实体
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    @JsonIgnore
    private String password;

    private String nickname;

    private String avatar;

    /** 系统角色: admin / auditor / user */
    private String role;

    /** 部门 ID（关联 sys_department） · 任务 7 职位独立 KB */
    private Long departmentId;

    /** 职位 ID（关联 sys_position） · 任务 7 · 决定可访问哪些 KB */
    private Long positionId;

    /** 知识库授权模式：inherit（继承部门/职位 + 附加按用户授权）/ override（仅按用户单独设置） */
    private String kbScopeMode;

    /** 用户偏好 JSON 字段（领域、语言风格等） */
    private String preference;

    private Integer status;

    /** 用户来源：register（外部邀请码注册）/ admin（管理员创建）。空视为 admin（兼容历史数据）。 */
    private String source;

    /** 账号到期时间。null=永久有效；已过当前时间则禁止登录。外部注册默认 now+2 天。 */
    private LocalDateTime expireTime;

    private LocalDateTime lastLogin;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

    /**
     * #3 · 有效可用功能点（非持久化，登录/获取信息时计算填充）
     * 来源：职位(若配置) → 部门(若配置) → 基线；admin 系统角色为全部功能。
     */
    @TableField(exist = false)
    private java.util.List<String> permissions;
}
