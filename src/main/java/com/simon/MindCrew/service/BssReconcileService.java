package com.simon.MindCrew.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.bssopenapi.model.v20171214.QueryInstanceBillRequest;
import com.aliyuncs.bssopenapi.model.v20171214.QueryInstanceBillResponse;
import com.aliyuncs.bssopenapi.model.v20171214.QueryBillOverviewRequest;
import com.aliyuncs.bssopenapi.model.v20171214.QueryBillOverviewResponse;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.profile.DefaultProfile;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.entity.UsageDaily;
import com.simon.MindCrew.entity.UsageReconcileDaily;
import com.simon.MindCrew.mapper.UsageDailyMapper;
import com.simon.MindCrew.mapper.UsageReconcileDailyMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 阿里云 BSS Open API 对账服务 · 任务 13.7
 *
 * 职责：
 *   1. 每天凌晨 3:30 拉昨天的真实账单（QueryInstanceBill）
 *   2. 跟 usage_daily 里我们自己算的求和对比
 *   3. 差异 >10% 写告警日志（后续可对接钉钉/飞书机器人）
 *
 * 设计取舍：
 *   - BSS 数据 T+1，不能拉今天
 *   - BSS 只能拿到「整个账户某产品」的总额，没法细到用户
 *     → 我们的「按用户拆账」依然依赖内部 usage_daily 算的
 *     → 对账只是用来校准 model_pricing 表的单价是否还准
 *   - 凭证未配置（BSS_ACCESS_KEY 为空）时所有方法安全无操作
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BssReconcileService {

    private final UsageReconcileDailyMapper reconcileMapper;
    private final UsageDailyMapper usageDailyMapper;

    @Value("${bss.enabled:false}")
    private boolean enabled;
    @Value("${bss.access-key:}")
    private String accessKey;
    @Value("${bss.secret-key:}")
    private String secretKey;
    @Value("${bss.region:cn-hangzhou}")
    private String region;
    @Value("${bss.product-codes:dashscope}")
    private String productCodesCsv;
    @Value("${bss.alert-threshold-pct:0.10}")
    private BigDecimal alertThreshold;

    private static final DateTimeFormatter BSS_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final Map<String, String> PRODUCT_NAME_ZH = Map.of(
            "dashscope", "百炼大模型",
            "oss", "对象存储 OSS",
            "ecs", "云服务器 ECS",
            "rds", "云数据库 RDS"
    );

    /**
     * 定时任务 · 每天凌晨 3:30 拉昨日账单（cron 可在 yml 覆盖）
     */
    @Scheduled(cron = "${bss.reconcile-cron:0 30 3 * * ?}")
    public void scheduledReconcile() {
        if (!enabled) {
            log.debug("[BSS] 未启用（BSS_ENABLED=false） · 跳过");
            return;
        }
        if (accessKey.isBlank() || secretKey.isBlank()) {
            log.warn("[BSS] AccessKey 未配置 · 跳过对账");
            return;
        }
        LocalDate yesterday = LocalDate.now().minusDays(1);
        try {
            reconcileDate(yesterday);
        } catch (Exception e) {
            log.error("[BSS] 对账失败 date={}", yesterday, e);
        }
    }

    /**
     * 手动触发对账（管理员）· 改用「账单概览 QueryBillOverview」按账期月度拉取。
     *   - 不依赖产品代码（之前写死 dashscope 拉不到 → 0 数据的根因）
     *   - 直接拿到与「费用概览」一致的真实金额 + 各产品分布，且支持当月
     * @param date 目标日期（取其所在账期月；可为当月）
     */
    public List<UsageReconcileDaily> reconcileDate(LocalDate date) {
        if (date == null) date = LocalDate.now();
        if (!enabled) {
            throw new IllegalStateException("BSS 对账未启用 · 检查环境变量 BSS_ENABLED=true");
        }
        if (accessKey.isBlank() || secretKey.isBlank()) {
            throw new IllegalStateException("BSS_ACCESS_KEY / BSS_SECRET_KEY 未配置");
        }

        IAcsClient client = buildClient();
        String cycle = date.format(BSS_MONTH);            // yyyy-MM
        LocalDate rowDate = date.withDayOfMonth(1);        // 一个账期月一组记录

        List<QueryBillOverviewResponse.Data.Item> items;
        try {
            QueryBillOverviewRequest req = new QueryBillOverviewRequest();
            req.setBillingCycle(cycle);
            // 不设 ProductCode / SubscriptionType → 返回该账期全部产品（含后付费百炼/DashScope）
            QueryBillOverviewResponse resp = client.getAcsResponse(req);
            items = (resp != null && resp.getData() != null && resp.getData().getItems() != null)
                    ? resp.getData().getItems() : java.util.List.of();
        } catch (ClientException e) {
            throw new IllegalStateException("拉取阿里云账单失败：" + e.getErrMsg()
                    + "（常见：RAM 子账号未授 AliyunBSSReadOnlyAccess，或需用主账号 AK）");
        }

        List<UsageReconcileDaily> results = new ArrayList<>();
        BigDecimal officialTotal = BigDecimal.ZERO;

        // 各产品明细行（仅展示官方金额，不参与差值/告警——our 传 null 视为"参考行"）
        BigDecimal llmOfficial = BigDecimal.ZERO;   // 大模型(百炼)真实账单
        for (QueryBillOverviewResponse.Data.Item it : items) {
            BigDecimal amt = it.getPretaxAmount() == null ? BigDecimal.ZERO
                    : BigDecimal.valueOf(it.getPretaxAmount());
            officialTotal = officialTotal.add(amt);
            String pcode = it.getProductCode() != null ? it.getProductCode() : "unknown";
            String pname = it.getProductName() != null ? it.getProductName() : pcode;
            if (isLlmProduct(pcode, pname)) llmOfficial = llmOfficial.add(amt);
            results.add(upsertRow(rowDate, pcode, pname,
                    amt.setScale(4, RoundingMode.HALF_UP), null, JSON.toJSONString(it)));
        }
        officialTotal = officialTotal.setScale(4, RoundingMode.HALF_UP);
        llmOfficial = llmOfficial.setScale(4, RoundingMode.HALF_UP);

        // 我们内部估算（该账期月合计）—— 只覆盖大模型 token 成本
        BigDecimal ourMonth = BigDecimal.ZERO;
        for (UsageDaily u : usageDailyMapper.selectList(new LambdaQueryWrapper<UsageDaily>()
                .ge(UsageDaily::getStatDate, rowDate)
                .le(UsageDaily::getStatDate, date.withDayOfMonth(date.lengthOfMonth())))) {
            if (u.getCostCny() != null) ourMonth = ourMonth.add(u.getCostCny());
        }
        ourMonth = ourMonth.setScale(4, RoundingMode.HALF_UP);

        // ⭐ 关键比较行：只比"大模型(百炼)"真实账单 vs 我们内部 token 估算
        results.add(0, upsertRow(rowDate, "__llm__", "大模型(百炼) · 真实账单 vs 内部估算",
                llmOfficial, ourMonth, null));
        // 账单总计：仅参考展示（含 ECS/OSS/RDS 等非大模型项，不比差值）
        results.add(1, upsertRow(rowDate, "__total__", "阿里云账单合计 · " + cycle + "（含其它云产品·仅参考）",
                officialTotal, null, null));

        log.info("[BSS] 对账完成 cycle={} 大模型官方=¥{} 我们估算=¥{} 账单总计=¥{} 产品数={}",
                cycle, llmOfficial.toPlainString(), ourMonth.toPlainString(),
                officialTotal.toPlainString(), items.size());
        return results;
    }

    /** 是否大模型(百炼/灵积/DashScope)产品 */
    private static boolean isLlmProduct(String code, String name) {
        String c = code == null ? "" : code.toLowerCase();
        String n = name == null ? "" : name;
        return c.equals("sfm") || c.equals("dashscope") || c.equals("bailian")
                || n.contains("百炼") || n.contains("大模型") || n.contains("灵积") || n.contains("DashScope");
    }

    /**
     * upsert 一行对账记录。
     *   our != null → 比较行：自动算 diff/告警。
     *   our == null → 参考行：只展示官方金额，diff/比例/告警全留空。
     */
    private UsageReconcileDaily upsertRow(LocalDate date, String productCode, String productName,
                                          BigDecimal official, BigDecimal our, String rawJson) {
        BigDecimal diff = null, diffPct = null;
        boolean alert = false;
        if (our != null) {
            diff = our.subtract(official).setScale(4, RoundingMode.HALF_UP);
            if (official.signum() == 0) {
                diffPct = our.signum() == 0 ? BigDecimal.ZERO : BigDecimal.ONE;
            } else {
                diffPct = diff.divide(official, 4, RoundingMode.HALF_UP);
            }
            alert = diffPct.abs().compareTo(alertThreshold) > 0;
        }

        UsageReconcileDaily existing = reconcileMapper.selectOne(new LambdaQueryWrapper<UsageReconcileDaily>()
                .eq(UsageReconcileDaily::getStatDate, date)
                .eq(UsageReconcileDaily::getProductCode, productCode));
        UsageReconcileDaily row = existing != null ? existing : new UsageReconcileDaily();
        row.setStatDate(date);
        row.setProductCode(productCode);
        row.setProductName(productName);
        row.setOfficialAmountCny(official);
        row.setOurCalcAmountCny(our);
        row.setDiffAmountCny(diff);
        row.setDiffPct(diffPct);
        row.setAlerted(alert ? 1 : 0);
        if (rawJson != null) row.setBssRawJson(rawJson);
        if (existing == null) reconcileMapper.insert(row); else reconcileMapper.updateById(row);
        return row;
    }

    /**
     * 查询历史对账记录（前端用）
     * 表不存在时返回空列表（友好降级 · 提示前端去跑 SQL）
     */
    public List<UsageReconcileDaily> listRecent(int days) {
        LocalDate from = LocalDate.now().minusDays(Math.max(1, Math.min(days, 90)));
        try {
            return reconcileMapper.selectList(new LambdaQueryWrapper<UsageReconcileDaily>()
                    .ge(UsageReconcileDaily::getStatDate, from)
                    .orderByDesc(UsageReconcileDaily::getStatDate)
                    .orderByAsc(UsageReconcileDaily::getProductCode));
        } catch (Exception e) {
            if (isTableMissing(e)) {
                log.warn("[BSS] 表 usage_reconcile_daily 不存在 · 请跑 sql/usage-reconcile-schema.sql · 返回空列表");
                return new ArrayList<>();
            }
            throw e;
        }
    }

    /**
     * 给前端的状态摘要
     * 表不存在时 tableReady=false · 前端可显示「先去跑 SQL」
     */
    public ReconcileStatus status() {
        ReconcileStatus s = new ReconcileStatus();
        s.enabled = enabled;
        s.credentialConfigured = !accessKey.isBlank() && !secretKey.isBlank();
        s.productCodes = Arrays.stream(productCodesCsv.split(","))
                .map(String::trim).filter(x -> !x.isEmpty()).toList();
        s.alertThresholdPct = alertThreshold;
        s.tableReady = true;
        // 最近一次对账时间
        try {
            UsageReconcileDaily latest = reconcileMapper.selectOne(
                    new LambdaQueryWrapper<UsageReconcileDaily>()
                            .orderByDesc(UsageReconcileDaily::getStatDate)
                            .orderByDesc(UsageReconcileDaily::getUpdateTime)
                            .last("LIMIT 1"));
            if (latest != null) {
                s.latestDate = latest.getStatDate().toString();
                s.latestUpdateTime = latest.getUpdateTime() == null ? null : latest.getUpdateTime().toString();
            }
        } catch (Exception e) {
            if (isTableMissing(e)) {
                log.warn("[BSS] 表 usage_reconcile_daily 不存在 · 请跑 sql/usage-reconcile-schema.sql");
                s.tableReady = false;
            } else {
                throw e;
            }
        }
        return s;
    }

    /** 识别 MySQL 的「表不存在」错误（避免硬绑死 SQLState） */
    private static boolean isTableMissing(Throwable e) {
        Throwable t = e;
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null && (msg.contains("doesn't exist") || msg.contains("usage_reconcile_daily"))) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private IAcsClient buildClient() {
        DefaultProfile profile = DefaultProfile.getProfile(region, accessKey, secretKey);
        return new DefaultAcsClient(profile);
    }

    @Data
    public static class ReconcileStatus {
        public boolean enabled;
        public boolean credentialConfigured;
        public boolean tableReady;          // ⭐ 是否已建表
        public List<String> productCodes;
        public BigDecimal alertThresholdPct;
        public String latestDate;
        public String latestUpdateTime;
    }
}
