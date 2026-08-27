package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 教练规则（教练模式出题模板）· 独立管理，知识库设置里下拉选用
 *   = 名称/简介 + 题型配比(quizConfig JSON) + 附加出题规则(coachRule 文本)
 * 类似「技能包 SkillPack」，由管理员维护多个规则，知识库选其一。
 */
@Data
@TableName("coach_rule")
public class CoachRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    /** 题型配比 JSON，形如 {"single":4,"multiple":2,"judge":2,"short":2}；空=默认均衡 */
    private String quizConfig;

    /** 附加出题规则（角色/侧重/风格）· 注入出题提示 */
    private String coachRule;

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
