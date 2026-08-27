package com.simon.MindCrew.common;

import java.util.List;
import java.util.stream.Collectors;

/**
 * #3 · 功能权限目录
 *
 * 定义系统所有"可授权功能点"（与前端菜单/路由一一对应）。
 * 部门 / 职位通过 permissions 字段持有一组 key；用户有效权限按
 *   职位(若配置) → 部门(若配置) → 基线  解析；admin 系统角色永远全开。
 *
 * 仅控制前端菜单/路由可见性；后端管理类接口仍维持 admin 限制（轻量先行）。
 * 新增菜单时，这里和前端 MainLayout 的 featureKey 同步加一条即可。
 */
public final class FeatureCatalog {

    private FeatureCatalog() {}

    /** 单个功能点定义 */
    public record Feature(String key, String label, String group) {}

    /** 永远开启的功能：任何用户恒有（即使被配置为更少也保留），防止把人锁死在外面 */
    public static final List<String> ALWAYS_ON = List.of("chat", "profile");

    /**
     * 默认功能集：当用户的职位、部门都未配置权限时使用。
     * 等同改造前非 admin 用户能看到的菜单（智能问答/Agent调研/教练/语音通话/历史对话搜索），
     * 保证存量用户行为不变；管理员一旦给职位/部门配置了权限，即按配置生效。
     *
     * <p>v2：默认开放 首页/系统编排/知识库/数据大屏 给普通用户（首页在前端特判 path==='/'，
     * 不需要 key；其余三个 key 必须在这里声明才能让 hasFeature 通过）。
     */
    public static final List<String> DEFAULT_FEATURES =
            List.of("chat", "digital-employees", "crew", "coach", "voice-call", "conv-search",
                    "workflow", "collections", "dashboard", "profile");

    /** 全量功能目录（顺序即前端展示顺序） */
    public static final List<Feature> FEATURES = List.of(
            // 工作台
            new Feature("chat",            "智能问答",        "工作台"),
            new Feature("digital-employees", "数字员工",      "工作台"),
            new Feature("crew",            "Agent 调研",      "工作台"),
            new Feature("coach",           "教练模式",        "工作台"),
            new Feature("voice-call",      "语音通话",        "工作台"),
            new Feature("collections",     "知识库",          "工作台"),
            new Feature("knowledge",       "所有文档",        "工作台"),
            // 反馈与质量
            new Feature("feedback-review", "反馈审核",        "反馈与质量"),
            new Feature("golden-pair",     "经验库", "反馈与质量"),
            new Feature("conv-search",     "历史对话搜索",     "反馈与质量"),
            // 组织与权限
            new Feature("users",           "用户管理",        "组织与权限"),
            new Feature("org",             "组织与职位",       "组织与权限"),
            new Feature("brand-settings",  "品牌设置",        "组织与权限"),
            new Feature("coach-stats",     "教练学习统计",     "组织与权限"),
            // AI 模型配置
            new Feature("ai-config",       "AI 配置",         "AI 模型配置"),
            new Feature("persona",         "Soul 人格",       "AI 模型配置"),
            new Feature("llm-provider",    "大模型 Provider", "AI 模型配置"),
            // 合规与安全
            new Feature("audit-log",       "审计日志",        "合规与安全"),
            new Feature("pii-config",      "PII 脱敏",        "合规与安全"),
            // 运维与监控
            new Feature("mcp",             "MCP 控制台",      "运维与监控"),
            new Feature("dashboard",       "数据大屏",        "运维与监控"),
            new Feature("usage-reconcile", "账单对账",        "运维与监控"),
            new Feature("api-key",         "API Key 管理",   "运维与监控"),
            new Feature("dingtalk-bot",    "钉钉机器人",      "运维与监控")
    );

    /** 全部功能 key（admin 用） */
    public static List<String> allKeys() {
        return FEATURES.stream().map(Feature::key).collect(Collectors.toList());
    }

    /** key 是否合法 */
    public static boolean isValidKey(String key) {
        return FEATURES.stream().anyMatch(f -> f.key().equals(key));
    }
}
