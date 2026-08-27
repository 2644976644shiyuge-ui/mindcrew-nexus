package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 知识库 × 职位 访问控制
 * 对应表 kb_acl
 *
 * permission:
 *   read  · 检索 / 问答
 *   write · 上传 / 修改
 *   admin · 删除 / 授权他人
 */
@Data
@TableName("kb_acl")
public class KbAcl {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kbId;

    /** ⭐ 授权目标类型（任务 15）· collection · 库级 / document · 文档级（默认） */
    private String refType;

    /** 职位级授权 · 与 departmentId 二选一 */
    private Long positionId;

    /** 部门级授权 · 该部门下所有用户（含子部门）有权 */
    private Long departmentId;

    /** 用户级授权 · 直接授予某个用户（与 positionId/departmentId 互斥） */
    private Long userId;

    private String permission;
    private Long grantedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
