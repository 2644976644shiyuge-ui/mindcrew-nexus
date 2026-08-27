package com.simon.MindCrew.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 全球获客数字员工 (Global Lead Hunter) 配置。
 *
 * <p>Serper 复用 mindcrew.web-search 的配置；Hunter 为可选增强：
 * 未配置 hunter-api-key 时，联系人发现降级为 Serper 网页邮箱抽取，邮箱验证降级为格式校验。
 */
@Data
@ConfigurationProperties(prefix = "mindcrew.lead-hunter")
public class LeadHunterProperties {

    /** Hunter.io API Key（可选）。域名搜人 + 邮箱验证都用它。 */
    private String hunterApiKey;

    /** Hunter.io 域名搜索端点 */
    private String hunterDomainEndpoint = "https://api.hunter.io/v2/domain-search";

    /** Hunter.io 邮箱验证端点 */
    private String hunterVerifierEndpoint = "https://api.hunter.io/v2/email-verifier";

    /** 单次任务目标线索数上限（保护 Serper 配额） */
    private int maxTargetCount = 200;

    /** 单次任务 Serper 搜索次数上限 */
    private int maxSerperCalls = 120;

    /** 两次 Serper 调用之间的间隔（毫秒） */
    private long serperIntervalMs = 400;

    /** 公司名称验证阶段，最多补充搜索的公司数 */
    private int maxEnrichmentCalls = 40;
}
