package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 技能包 · 提问时可选的"场景化能力"
 *   = 名称/图标 + 技能指令(角色/风格/输出) + 可选绑定知识库范围
 */
@Data
@TableName("skill_pack")
public class SkillPack {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 图标(emoji 或图标名) */
    private String icon;

    private String description;

    /** 技能指令：告诉 AI 这个技能干啥、怎么答、什么风格/格式 */
    private String instruction;

    /** 绑定的知识库 collection id 列表(JSON 数组)；空=全部知识库 */
    private String collectionIds;

    /** 启用：1=是 0=否 */
    private Integer enabled;

    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
