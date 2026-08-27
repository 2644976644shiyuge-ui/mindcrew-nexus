package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库集合（真正的"知识库"· 任务 15）
 *
 * 旧设计：一个上传文件 = 一条 KbKnowledgeBase = 一个"知识库"
 * 新设计：一个 KnowledgeCollection = N 个文档（KbKnowledgeBase）
 *
 * 关系：
 *   KnowledgeCollection 1 ── N KbKnowledgeBase (collection_id 外键)
 *   KnowledgeCollection 1 ── N KbAcl (ref_type='collection')
 */
@Data
@TableName("knowledge_collection")
public class KnowledgeCollection {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String description;
    /** Element Plus 图标名，如 FolderOpened / Lightning / Reading */
    private String icon;
    /** 主题色 · 16 进制 */
    private String color;
    /** 业务分类 code · 可选关联 kb_category */
    private String categoryCode;

    /** public · 所有人 / scoped · 按 kb_acl(ref_type=collection) / private · 仅创建者 */
    private String visibility;

    private Long ownerUserId;
    /** 缓存 · 库内文档数 */
    private Integer docCount;
    /** 缓存 · 库内切片总数 */
    private Integer totalChunks;
    /** 系统内置库（不可删，迁移自动建的不算系统库）*/
    private Integer isSystem;

    /**
     * 绑定的 Soul 人格 id（null=用全局默认人格）。
     * 单选该知识库对话时生效；多选知识库时回退全局默认。
     * updateStrategy=IGNORED：允许置空（清除绑定回到默认）。
     */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private Long personaId;

    /**
     * 绑定的技能包 id（null=不套用技能）。
     * 单选该知识库对话时生效；多选知识库时不套用。
     * updateStrategy=IGNORED：允许置空（清除绑定）。
     */
    @TableField(value = "skill_pack_id", updateStrategy = FieldStrategy.IGNORED)
    private Long skillPackId;

    /**
     * 教练模式·绑定的教练规则 id（coach_rule.id）。优先级高于下方内联 coach_rule/quiz_config。
     * 单选该知识库练习时生效；多选 / 未绑定 → 用默认出题逻辑。
     * updateStrategy=IGNORED：允许置空（清除绑定）。
     */
    @TableField(value = "coach_rule_id", updateStrategy = FieldStrategy.IGNORED)
    private Long coachRuleId;

    /**
     * 教练模式·本知识库的出题规则（null=用默认出题逻辑）。
     * 旧版内联规则，保留向后兼容；新版优先用 coachRuleId 绑定的教练规则。
     * 单选该知识库练习时注入出题提示，管理员可随时更新。
     * updateStrategy=IGNORED：允许置空（清除规则）。
     */
    @TableField(value = "coach_rule", updateStrategy = FieldStrategy.IGNORED)
    private String coachRule;

    /**
     * 教练模式·本知识库的出题题型配比（JSON，null=用默认均衡配比）。
     * 形如 {"single":4,"multiple":2,"judge":2,"short":2}，值为该题型出几道。
     * 单选该知识库练习时按此配比强制分配题型；多选 / 未配置 → 默认均衡。
     * updateStrategy=IGNORED：允许置空（清除回到默认）。
     */
    @TableField(value = "quiz_config", updateStrategy = FieldStrategy.IGNORED)
    private String quizConfig;

    /**
     * 本知识库可用音色 id 列表（voice_persona.id 逗号分隔，可多选）。
     * 单选该知识库打电话时，音色下拉只显示这些音色并默认选中第一个；空=不限制（全部音色）。
     * updateStrategy=IGNORED：允许置空（清除配置回到不限制）。
     */
    @TableField(value = "voice_ids", updateStrategy = FieldStrategy.IGNORED)
    private String voiceIds;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
