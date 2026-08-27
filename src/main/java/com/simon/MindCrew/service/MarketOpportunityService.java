package com.simon.MindCrew.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.entity.LeadHuntCompany;
import com.simon.MindCrew.mapper.LeadHuntCompanyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Market Opportunity Map · 市场机会地图
 *
 * 基于 lead_hunt_company 真实数据聚合：客户密度 / 行业多样性 / 竞品替换机会，
 * 生成 0-100 机会指数 + S/A/B/C 等级。
 *
 * 评分权重（v1 真数据部分）：
 *   客户资源 30% + 行业需求 25% + 竞品替换 20% = 75 分满（v1）
 *   商业环境 15% + 历史转化 10% = 待 v2 接入 GDP/CRM 数据后补齐
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketOpportunityService {

    private final LeadHuntCompanyMapper companyMapper;

    /** 重点监控竞品品牌（来自 ZYCOO IP Audio 赛道） */
    private static final List<String> COMPETITOR_BRANDS = List.of(
            "AtlasIED", "Atlas IED", "Valcom", "Bogen", "CyberData", "Cyber Data",
            "Algo", "Zenitel", "Commend", "Hikvision", "TOA", "Bosch", "Barix"
    );

    /** 行业关键词 → ZYCOO 推荐产品（知识图谱映射 v1） */
    private static final Map<String, List<String>> INDUSTRY_PRODUCT_MAP = new LinkedHashMap<>();
    static {
        INDUSTRY_PRODUCT_MAP.put("education", List.of("IP Paging System", "IP Intercom"));
        INDUSTRY_PRODUCT_MAP.put("school", List.of("IP Paging System", "IP Intercom"));
        INDUSTRY_PRODUCT_MAP.put("healthcare", List.of("IP Paging System", "Emergency Communication"));
        INDUSTRY_PRODUCT_MAP.put("hospital", List.of("IP Paging System", "Emergency Communication"));
        INDUSTRY_PRODUCT_MAP.put("government", List.of("IP Intercom", "Emergency Communication"));
        INDUSTRY_PRODUCT_MAP.put("enterprise", List.of("Unified Communication", "Conference AV"));
        INDUSTRY_PRODUCT_MAP.put("hospitality", List.of("IP Paging System", "Passive Speakers"));
        INDUSTRY_PRODUCT_MAP.put("hotel", List.of("IP Paging System", "Passive Speakers"));
        INDUSTRY_PRODUCT_MAP.put("retail", List.of("IP Paging System", "Passive Speakers"));
        INDUSTRY_PRODUCT_MAP.put("transport", List.of("IP Paging System", "IP Intercom"));
        INDUSTRY_PRODUCT_MAP.put("industrial", List.of("IP Intercom", "Emergency Communication"));
    }

    // ═══════════════ 公共数据结构 ═══════════════

    public static class CountryOpportunity {
        public String country;
        public int score;
        public String grade;            // S / A / B / C
        public int companyCount;
        public Map<String, Integer> customerTypes = new LinkedHashMap<>();
        public List<Map<String, Object>> topIndustries = new ArrayList<>();
        public Map<String, Integer> competitors = new LinkedHashMap<>();
        public int competitorTotal;
        public List<String> recommendedProducts = new ArrayList<>();
    }

    /** 全局概览（地图着色 + 排行） */
    public List<CountryOpportunity> getOverview() {
        List<LeadHuntCompany> all = companyMapper.selectList(new LambdaQueryWrapper<LeadHuntCompany>()
                .eq(LeadHuntCompany::getDeleted, 0));
        Map<String, List<LeadHuntCompany>> byCountry = all.stream()
                .filter(c -> c.getCountry() != null && !c.getCountry().isBlank())
                .collect(Collectors.groupingBy(c -> c.getCountry().trim(), LinkedHashMap::new, Collectors.toList()));

        // 用于归一化
        int maxCount = byCountry.values().stream().mapToInt(List::size).max().orElse(1);

        List<CountryOpportunity> result = new ArrayList<>();
        for (var e : byCountry.entrySet()) {
            result.add(buildOpportunity(e.getKey(), e.getValue(), maxCount));
        }
        result.sort((a, b) -> Integer.compare(b.score, a.score));
        return result;
    }

    /** 单国家详情（点击地图后右侧面板） */
    public CountryOpportunity getDetail(String country) {
        List<LeadHuntCompany> list = companyMapper.selectList(new LambdaQueryWrapper<LeadHuntCompany>()
                .eq(LeadHuntCompany::getDeleted, 0)
                .eq(LeadHuntCompany::getCountry, country));
        if (list.isEmpty()) return null;
        return buildOpportunity(country, list, Math.max(1, list.size()));
    }

    // ═══════════════ 评分核心 ═══════════════

    private CountryOpportunity buildOpportunity(String country, List<LeadHuntCompany> list, int maxCount) {
        CountryOpportunity op = new CountryOpportunity();
        op.country = country;
        op.companyCount = list.size();

        // 1. 客户资源 30%（按密度归一化）
        Map<String, Long> typeCounts = list.stream()
                .filter(c -> c.getCustomerType() != null && !c.getCustomerType().isBlank())
                .collect(Collectors.groupingBy(c -> c.getCustomerType().trim(), Collectors.counting()));
        for (var e : typeCounts.entrySet()) op.customerTypes.put(e.getKey(), e.getValue().intValue());
        int typeDiversity = typeCounts.size();
        double customerScore = ((double) list.size() / maxCount) * 20 + Math.min(typeDiversity, 5) / 5.0 * 10; // 0-30

        // 2. 行业需求 25%（行业多样性 + 主营业务覆盖）
        Map<String, Long> industryCounts = list.stream()
                .filter(c -> c.getIndustry() != null && !c.getIndustry().isBlank())
                .map(c -> c.getIndustry().toLowerCase())
                .collect(Collectors.groupingBy(s -> s, Collectors.counting()));
        List<Map<String, Object>> topInd = industryCounts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(5)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.getKey());
                    m.put("count", e.getValue());
                    m.put("stars", e.getValue() >= 5 ? 5 : e.getValue() >= 3 ? 4 : e.getValue() >= 2 ? 3 : 2);
                    return m;
                })
                .collect(Collectors.toList());
        op.topIndustries = topInd;
        double industryScore = Math.min(industryCounts.size(), 8) / 8.0 * 15 + Math.min(topInd.size(), 5) / 5.0 * 10; // 0-25

        // 3. 竞品替换机会 20%
        for (LeadHuntCompany c : list) {
            String comp = c.getCompetitor();
            if (comp == null || comp.isBlank()) continue;
            String lower = comp.toLowerCase();
            for (String brand : COMPETITOR_BRANDS) {
                if (lower.contains(brand.toLowerCase())) {
                    // 归一化品牌名（去空格）
                    String key = brand.replace(" ", "");
                    op.competitors.merge(key, 1, Integer::sum);
                    op.competitorTotal++;
                }
            }
        }
        double competitorScore = Math.min(op.competitorTotal / 10.0, 1.0) * 20; // 0-20（每 10 家竞品满）

        // 4. 商业环境 15% + 5. 历史转化 10% —— v1 无数据源，记 0 留扩展位
        double envScore = 0;
        double historyScore = 0;

        op.score = (int) Math.round(customerScore + industryScore + competitorScore + envScore + historyScore);
        op.score = Math.min(100, op.score);
        op.grade = op.score >= 85 ? "S" : op.score >= 70 ? "A" : op.score >= 55 ? "B" : "C";

        // 推荐产品（按行业匹配）
        Set<String> products = new LinkedHashSet<>();
        for (String ind : industryCounts.keySet()) {
            for (var e : INDUSTRY_PRODUCT_MAP.entrySet()) {
                if (ind.contains(e.getKey())) products.addAll(e.getValue());
            }
        }
        if (products.isEmpty()) products.addAll(List.of("IP Paging System", "IP Intercom"));
        op.recommendedProducts = new ArrayList<>(products).subList(0, Math.min(3, products.size()));

        return op;
    }

    /** AI 行动建议（模板化，基于真实数据；v2 可换成 LLM 生成） */
    public List<String> getActionSuggestions(String country) {
        CountryOpportunity op = getDetail(country);
        if (op == null) return List.of();
        List<String> actions = new ArrayList<>();
        // 1. 开发头部客户类型
        if (!op.customerTypes.isEmpty()) {
            var top = op.customerTypes.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).orElse(null);
            if (top != null) actions.add("开发 " + Math.max(20, top.getValue() * 2) + " 家 " + top.getKey());
        }
        // 2. 竞品替换
        if (op.competitorTotal > 0) {
            var topComp = op.competitors.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).orElse(null);
            if (topComp != null) actions.add("重点联系安装 " + topComp.getKey() + " 的 " + topComp.getValue() + " 家客户（替换机会）");
        }
        // 3. 行业方案
        if (!op.topIndustries.isEmpty()) {
            String ind = (String) op.topIndustries.get(0).get("name");
            actions.add("推广 " + ind + " 行业解决方案");
        }
        // 4. 产品
        if (!op.recommendedProducts.isEmpty()) {
            actions.add("主推 " + String.join(" / ", op.recommendedProducts));
        }
        return actions;
    }
}
