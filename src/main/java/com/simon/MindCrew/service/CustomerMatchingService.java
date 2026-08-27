package com.simon.MindCrew.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.config.AiConfigHolder;
import com.simon.MindCrew.config.DocmindWebSearchProperties;
import com.simon.MindCrew.entity.LeadHuntCompany;
import com.simon.MindCrew.entity.LeadHuntContact;
import com.simon.MindCrew.mapper.LeadHuntCompanyMapper;
import com.simon.MindCrew.mapper.LeadHuntContactMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Customer Matching Engine · 客户匹配引擎
 *
 * 输入公司名 → 查 lead_hunt_company 真实数据 + LLM 分析 →
 * 生成：业务能力 / 产品匹配 / 应用场景 / 竞品情报 / ZYCOO Fit Score / 销售策略 / AI 解释
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerMatchingService {

    private final LeadHuntCompanyMapper companyMapper;
    private final LeadHuntContactMapper contactMapper;
    private final AiConfigHolder aiConfigHolder;
    private final DocmindWebSearchProperties webSearchProps;
    private final RestTemplate webSearchRestTemplate;

    // ═══════════════ 数据结构 ═══════════════

    public static class AnalysisResult {
        public String companyName;
        public boolean found;           // 是否在客户库中找到
        public LeadHuntCompany company;  // 原始数据（found=true 时）
        public int contactCount;
        // LLM 生成
        public List<String> businessCapability = new ArrayList<>();
        public List<Map<String, Object>> productMatches = new ArrayList<>();
        public List<Map<String, Object>> applications = new ArrayList<>();
        public Map<String, Object> competitorIntel = new LinkedHashMap<>();
        public Map<String, Object> salesStrategy = new LinkedHashMap<>();
        public String whyExplanation = "";
        // 评分
        public int fitScore;
        public String grade;            // A+ / A / B / C
        public Map<String, Integer> scoreBreakdown = new LinkedHashMap<>();
    }

    public static class AnalyzeRequest {
        public String company;
    }

    // ═══════════════ 主流程 ═══════════════

    public AnalysisResult analyze(String query) {
        AnalysisResult r = new AnalysisResult();
        r.companyName = query;

        // 1. 查客户库
        List<LeadHuntCompany> matches = companyMapper.selectList(new LambdaQueryWrapper<LeadHuntCompany>()
                .eq(LeadHuntCompany::getDeleted, 0)
                .and(w -> w.like(LeadHuntCompany::getName, query)
                        .or().eq(LeadHuntCompany::getDomain, query.toLowerCase().replace("https://","").replace("http://","").split("/")[0])
                        .or().like(LeadHuntCompany::getWebsite, query))
                .last("LIMIT 5"));
        if (!matches.isEmpty()) {
            r.found = true;
            r.company = matches.get(0);
            r.contactCount = Math.toIntExact(contactMapper.selectCount(new LambdaQueryWrapper<LeadHuntContact>()
                    .eq(LeadHuntContact::getCompanyId, r.company.getId())
                    .eq(LeadHuntContact::getDeleted, 0)));
        }

        // 1b. 客户库没有 → 自动联网搜（用 Serper 抓公开信息当上下文）
        String webContext = null;
        if (!r.found) {
            webContext = serperSearchCompany(query);
        }

        // 2. 计算 Fit Score（确定性算法，不依赖 LLM）
        computeFitScore(r);

        // 3. LLM 分析（把已算好的 fitScore + 联网结果注入 prompt）
        callLlmAnalysis(r, webContext);

        return r;
    }

    // ═══════════════ 联网搜索兜底 ═══════════════

    /**
     * 用 Serper 搜公司基础信息 · 客户库里没有时启用
     *
     * 关键：同名公司消歧（Disambiguation）
     *   - 一次搜索可能返回同名多家公司的结果（如 "compass communication group" 同时有
     *     ccgspeech.com 言语治疗 + zoominfo AV 通信），需要按 domain 聚类打分
     *   - 按目标行业关键词密度选出最像 AV/PA/UC 集成商的那一组
     *   - 只把这一组结果给 LLM，避免被错误主流信息误导
     */
    private String serperSearchCompany(String query) {
        if (!StringUtils.hasText(webSearchProps.getSerperApiKey())) {
            log.info("[CustomerMatching] Serper key 未配置，跳过联网搜");
            return null;
        }
        try {
            // 第 1 步：通用搜索（5 条）
            List<JSONObject> generalResults = serperCall("\"" + query + "\" company about", 5);
            if (generalResults.isEmpty()) return null;
            // 第 2 步：行业定向搜索（追加 AV/PA/UC 关键词，找真正对得上的公司）
            List<JSONObject> industryResults = serperCall(
                    "\"" + query + "\" AV integrator OR telecom OR \"audio visual\" OR paging OR intercom OR distributor", 5);
            // 第 3 步：合并去重 + 按 domain 聚类打分
            Map<String, List<JSONObject>> clusters = new LinkedHashMap<>();
            for (JSONObject r : generalResults) clusters.computeIfAbsent(rootDomain(r), k -> new ArrayList<>()).add(r);
            for (JSONObject r : industryResults) clusters.computeIfAbsent(rootDomain(r), k -> new ArrayList<>()).add(r);
            // 第 4 步：按「行业命中 + 结果数量」选最佳 cluster
            String bestDomain = pickBestCluster(clusters);
            log.info("[CustomerMatching] 联网搜 {} → 通用{}条 + 行业{}条 → 聚类{} → 最佳 {}",
                    query, generalResults.size(), industryResults.size(), clusters.size(), bestDomain);
            if (bestDomain == null) {
                // 全部 cluster 行业命中都很低 → 退回用通用结果（LLM 自己判断）
                return formatResults(generalResults);
            }
            return formatResults(clusters.get(bestDomain));
        } catch (Exception e) {
            log.warn("[CustomerMatching] serper search failed for '{}': {}", query, e.getMessage());
            return null;
        }
    }

    private List<JSONObject> serperCall(String q, int num) {
        try {
            JSONObject body = new JSONObject();
            body.put("q", q);
            body.put("num", Math.max(1, Math.min(num, 10)));
            body.put("gl", "us");
            body.put("hl", "en");
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-API-KEY", webSearchProps.getSerperApiKey().trim());
            String resp = webSearchRestTemplate.postForObject(
                    webSearchProps.getSerperEndpoint(), new HttpEntity<>(body.toJSONString(), headers), String.class);
            if (resp == null) return Collections.emptyList();
            JSONObject root = JSON.parseObject(resp);
            com.alibaba.fastjson2.JSONArray organic = root.getJSONArray("organic");
            if (organic == null) return Collections.emptyList();
            List<JSONObject> out = new ArrayList<>();
            for (int i = 0; i < organic.size(); i++) out.add(organic.getJSONObject(i));
            return out;
        } catch (Exception e) {
            log.warn("[CustomerMatching] serper call failed q='{}': {}", q, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 提取链接的根域名（如 www.ccgspeech.com → ccgspeech.com）用于聚类 */
    private static String rootDomain(JSONObject item) {
        String link = item == null ? null : item.getString("link");
        if (link == null) return "_unknown_" + System.nanoTime();
        try {
            String host = java.net.URI.create(link).getHost();
            if (host == null) return link;
            // 去掉 www. 前缀，取最后两段
            if (host.startsWith("www.")) host = host.substring(4);
            String[] parts = host.split("\\.");
            if (parts.length <= 2) return host;
            return parts[parts.length - 2] + "." + parts[parts.length - 1];
        } catch (Exception e) { return link; }
    }

    /** ZYCOO 目标行业关键词 · 命中越多越像目标客户 */
    private static final String[] INDUSTRY_KEYWORDS = {
        "AV", "audio visual", "audio-visual", "paging", "intercom", "broadcast", "PA",
        "public address", "telecom", "integrator", "distributor", "reseller",
        "sound", "speaker", "communication", "phone", "UC", "unified",
        "voip", "sip", "network", "security", "life safety", "nurse call"
    };

    /** 选最佳 cluster：行业关键词命中数 × log(结果数) */
    private static String pickBestCluster(Map<String, List<JSONObject>> clusters) {
        String best = null;
        double bestScore = -1;
        for (Map.Entry<String, List<JSONObject>> e : clusters.entrySet()) {
            int hits = 0;
            for (JSONObject r : e.getValue()) {
                String text = ((r.getString("title") == null ? "" : r.getString("title")) + " " +
                        (r.getString("snippet") == null ? "" : r.getString("snippet"))).toLowerCase();
                for (String kw : INDUSTRY_KEYWORDS) {
                    if (text.contains(kw.toLowerCase())) { hits++; break; }
                }
            }
            // 行业命中 ≥2 且综合分高才算「命中行业」
            double score = hits >= 2 ? hits * Math.log(e.getValue().size() + 1) : -1;
            if (score > bestScore) { bestScore = score; best = e.getKey(); }
        }
        return bestScore < 0 ? null : best;
    }

    private String formatResults(List<JSONObject> results) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (JSONObject item : results) {
            if (++n > 5) break;
            String title = item.getString("title");
            String link = item.getString("link");
            String snippet = item.getString("snippet");
            sb.append("- [").append(title == null ? "" : title).append("](")
                    .append(link == null ? "" : link).append(")\n  ")
                    .append(snippet == null ? "" : snippet).append("\n");
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? null : out;
    }

    // ═══════════════ LLM 分析 ═══════════════

    private void callLlmAnalysis(AnalysisResult r, String webContext) {
        LeadHuntCompany c = r.company;
        String companyInfo;
        if (c != null) {
            companyInfo = String.format("公司名: %s\n官网: %s\n国家: %s\n行业: %s\n主营业务: %s\n客户类型: %s\n公司规模: %s\n在用竞品: %s",
                    c.getName(), c.getWebsite(), c.getCountry(), c.getIndustry(),
                    c.getMajorBusiness(), c.getCustomerType(), c.getCompanySize(), c.getCompetitor());
        } else if (webContext != null) {
            companyInfo = "公司名: " + r.companyName + "\n（未在客户库中找到，以下为联网搜索结果，请基于这些公开信息分析）\n\n" + webContext;
        } else {
            companyInfo = "公司名: " + r.companyName + "\n（未在客户库中找到，且联网搜索未返回结果）";
        }

        // Java 文本块 """ 不能在行尾直接接 + 表达式（会破坏语法），
        // 所以分两段：固定 prompt + 动态分数段
        String scoreBlock = String.format(
                "\n公司已通过算法算出 ZYCOO Fit Score = %d / 100（等级 %s）。" +
                "\n各维度得分（满分 30/20/15/15/10/10）：产品匹配=%d 行业=%d 规模=%d 地区=%d 竞品=%d 联系人=%d。" +
                "\n你的任务是解释这个分数（按各维度分析扣分/加分原因），不要重新编一个分数。\n",
                r.fitScore, r.grade,
                r.scoreBreakdown.get("产品匹配"),
                r.scoreBreakdown.get("行业匹配"),
                r.scoreBreakdown.get("公司规模"),
                r.scoreBreakdown.get("地区战略"),
                r.scoreBreakdown.get("竞品替换"),
                r.scoreBreakdown.get("联系人质量")
        );
        String system = """
                你是 ZYCOO（智科通信）的 AI 销售分析师。ZYCOO 是 IP 公共广播/对讲/统一通信设备制造商，
                核心产品线：IP Paging System (SH30/SQ10-T/SD2140)、IP Intercom、Unified Communication、Conference AV、Passive Speakers。
                目标客户：AV Integrator / Distributor / System Integrator / Dealer。
                重点行业：Education / Healthcare / Government / Enterprise / Hospitality。
                主要竞品：AtlasIED / Valcom / Bogen / CyberData / Algo / Zenitel。
                """ + scoreBlock + """
                基于给定公司信息，输出严格 JSON（不要多余文字）：
                {
                  "business_capability": ["该公司主要能力1", "能力2", ...],
                  "product_matches": [
                    {"product":"IP Paging System","score":95,"reasons":["原因1","原因2"],"models":["SH30","SQ10-T"]}
                  ],
                  "applications": [
                    {"name":"Education","stars":5,"reason":"理由","solution":"School Paging Solution"}
                  ],
                  "competitor_intel": {"brand":"AtlasIED","relation":"Installed/Partner/Similar","opportunity":5,"strategy":"替代策略"},
                  "sales_strategy": {
                    "first_contact_angle": "不要说'介绍产品'，要基于客户业务的具体切入角度（英文一句话）",
                    "email_subjects": ["邮件主题1","邮件主题2","邮件主题3"]
                  },
                  "why_explanation": "用中文解释为什么给这个 Fit Score（2-3 句，具体到行业/能力/竞品）"
                }
                """;
        String user = "公司信息：\n" + companyInfo;

        try {
            String raw = callLlmWithTimeout(system, user, 180, null);
            int s = raw.indexOf('{'), e = raw.lastIndexOf('}');
            if (s >= 0 && e > s) {
                JSONObject o = JSON.parseObject(raw.substring(s, e + 1));
                r.businessCapability = toStringList(o.getJSONArray("business_capability"), 8);
                r.productMatches = toMapList(o.getJSONArray("product_matches"));
                r.applications = toMapList(o.getJSONArray("applications"));
                r.competitorIntel = o.getJSONObject("competitor_intel") != null
                        ? new LinkedHashMap<>(o.getJSONObject("competitor_intel")) : new LinkedHashMap<>();
                r.salesStrategy = o.getJSONObject("sales_strategy") != null
                        ? new LinkedHashMap<>(o.getJSONObject("sales_strategy")) : new LinkedHashMap<>();
                r.whyExplanation = o.getString("why_explanation") != null ? o.getString("why_explanation") : "";
            }
        } catch (Exception ex) {
            log.warn("[CustomerMatching] LLM analysis failed: {}", ex.getMessage());
            r.whyExplanation = "（LLM 暂不可用，评分基于客户库真实数据计算）";
        }
    }

    // ═══════════════ ZYCOO Fit Score ═══════════════

    private void computeFitScore(AnalysisResult r) {
        LeadHuntCompany c = r.company;
        int product = 0, industry = 0, size = 0, region = 0, competitor = 0, contact = 0;

        // 产品匹配 30%
        if (!r.productMatches.isEmpty()) {
            double avg = r.productMatches.stream()
                    .mapToInt(m -> ((Number) m.getOrDefault("score", 0)).intValue())
                    .average().orElse(0);
            product = (int) (avg / 100.0 * 30);
        }

        // 行业匹配 20%
        String industryStr = c != null && c.getIndustry() != null ? c.getIndustry().toLowerCase() : "";
        String biz = c != null && c.getMajorBusiness() != null ? c.getMajorBusiness().toLowerCase() : "";
        String combined = industryStr + " " + biz;
        if (combined.contains("audio") || combined.contains("av") || combined.contains("communication")) industry += 10;
        if (combined.contains("education") || combined.contains("school")) industry += 5;
        if (combined.contains("healthcare") || combined.contains("hospital")) industry += 3;
        if (combined.contains("security") || combined.contains("intercom")) industry += 2;
        industry = Math.min(20, industry);

        // 公司规模 15%
        if (c != null && c.getCompanySize() != null) {
            String sz = c.getCompanySize();
            if (sz.contains("201") || sz.contains("500") || sz.contains("1000")) size = 15;
            else if (sz.contains("51") || sz.contains("200")) size = 12;
            else if (sz.contains("11") || sz.contains("50")) size = 8;
            else size = 5;
        }

        // 地区战略 15%
        String country = c != null && c.getCountry() != null ? c.getCountry().toLowerCase() : "";
        if (country.contains("united states")) region = 15;
        else if (country.contains("united kingdom") || country.contains("germany") || country.contains("australia") || country.contains("canada")) region = 12;
        else if (country.contains("uae") || country.contains("saudi") || country.contains("singapore")) region = 10;
        else region = 6;

        // 竞品替换 10%
        if (c != null && StringUtils.hasText(c.getCompetitor())) competitor = 10;

        // 联系人质量 10%
        if (r.contactCount >= 3) contact = 10;
        else if (r.contactCount >= 1) contact = 5;

        r.fitScore = Math.min(100, product + industry + size + region + competitor + contact);
        r.grade = r.fitScore >= 90 ? "A+" : r.fitScore >= 75 ? "A" : r.fitScore >= 60 ? "B" : "C";
        r.scoreBreakdown.put("产品匹配", product);
        r.scoreBreakdown.put("行业匹配", industry);
        r.scoreBreakdown.put("公司规模", size);
        r.scoreBreakdown.put("地区战略", region);
        r.scoreBreakdown.put("竞品替换", competitor);
        r.scoreBreakdown.put("联系人质量", contact);
    }

    // ═══════════════ 工具 ═══════════════

    private List<String> toStringList(com.alibaba.fastjson2.JSONArray arr, int max) {
        List<String> r = new ArrayList<>();
        if (arr == null) return r;
        for (int i = 0; i < arr.size() && i < max; i++) r.add(arr.getString(i));
        return r;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toMapList(com.alibaba.fastjson2.JSONArray arr) {
        List<Map<String, Object>> r = new ArrayList<>();
        if (arr == null) return r;
        for (int i = 0; i < arr.size(); i++) {
            JSONObject o = arr.getJSONObject(i);
            if (o != null) r.add(new LinkedHashMap<>(o));
        }
        return r;
    }

    private String callLlmWithTimeout(String system, String user, long timeoutSeconds, String model) {
        ChatClient client = ChatClient.builder(aiConfigHolder.getChatModel()).defaultSystem(system).build();
        var promptSpec = client.prompt().user(user);
        if (StringUtils.hasText(model)) {
            promptSpec = promptSpec.options(
                    org.springframework.ai.openai.OpenAiChatOptions.builder().model(model).build());
        }
        final var spec = promptSpec;
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> spec.call().content());
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            throw new RuntimeException("LLM 调用超时（" + timeoutSeconds + "s）");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("LLM 调用失败: " + cause.getMessage(), cause);
        }
    }
}
