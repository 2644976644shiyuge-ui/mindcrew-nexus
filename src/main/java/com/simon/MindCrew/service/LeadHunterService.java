package com.simon.MindCrew.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.config.AiConfigHolder;
import com.simon.MindCrew.config.DocmindWebSearchProperties;
import com.simon.MindCrew.config.LeadHunterProperties;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.ResultCode;
import com.simon.MindCrew.entity.*;
import com.simon.MindCrew.mapper.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 全球获客数字员工 (Global Lead Hunter) · 核心工作流。
 *
 * 11 步：知识库分析 → 用户条件 → ICP 生成 → 全球发现 → 公司验证 → 联系人寻找
 *       → 邮箱验证 → 历史去重 → 智能评分 → 结构化输出 → 完成。
 *
 * 外部依赖：
 *   - Serper（复用 web-search 配置）：公司发现 / 补全
 *   - Hunter.io（可选）：domain-search 找人 + email-verifier 验证
 *   - LLM（AiConfigHolder）：ICP 生成 / 行业分类
 *
 * 明确禁止（产品红线）：本服务只做"发现与验证"，绝不自动发邮件、绝不碰 LinkedIn/WhatsApp、绝不自动上传 CRM。
 */
@Slf4j
@Service
public class LeadHunterService {

    private final LeadHuntSessionMapper sessionMapper;
    private final LeadHuntCompanyMapper companyMapper;
    private final LeadHuntContactMapper contactMapper;
    private final KnowledgeCollectionMapper collectionMapper;
    private final KbKnowledgeBaseMapper kbMapper;
    private final AiConfigHolder aiConfigHolder;
    private final LeadHunterProperties props;
    private final DocmindWebSearchProperties webSearchProps;
    private final RestTemplate webSearchRestTemplate;
    private final ThreadPoolTaskExecutor leadHuntExecutor;
    private final Executor leadHuntLlmExecutor;

    public LeadHunterService(LeadHuntSessionMapper sessionMapper, LeadHuntCompanyMapper companyMapper,
                             LeadHuntContactMapper contactMapper, KnowledgeCollectionMapper collectionMapper,
                             KbKnowledgeBaseMapper kbMapper, AiConfigHolder aiConfigHolder,
                             LeadHunterProperties props, DocmindWebSearchProperties webSearchProps,
                             RestTemplate webSearchRestTemplate,
                             @Qualifier("leadHuntExecutor") ThreadPoolTaskExecutor leadHuntExecutor,
                             @Qualifier("leadHuntLlmExecutor") Executor leadHuntLlmExecutor) {
        this.sessionMapper = sessionMapper;
        this.companyMapper = companyMapper;
        this.contactMapper = contactMapper;
        this.collectionMapper = collectionMapper;
        this.kbMapper = kbMapper;
        this.aiConfigHolder = aiConfigHolder;
        this.props = props;
        this.webSearchProps = webSearchProps;
        this.webSearchRestTemplate = webSearchRestTemplate;
        this.leadHuntExecutor = leadHuntExecutor;
        this.leadHuntLlmExecutor = leadHuntLlmExecutor;
    }

    // ═══════════════ 进度模型 ═══════════════

    public static final String[] STEP_KEYS = {
            "kb_analysis", "conditions", "icp", "discovery", "verify",
            "contacts", "email_verify", "dedup", "scoring", "output", "done"
    };
    public static final String[] STEP_TITLES = {
            "分析知识库", "解析目标条件", "生成 ICP 客户画像", "全球客户发现", "公司验证与补全",
            "寻找关键联系人", "邮箱验证", "历史去重", "智能评分", "结构化输出", "生成搜索报告"
    };

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class StepState {
        private int index;
        private String key;
        private String title;
        private volatile String status = "pending";   // pending / running / done / skipped / failed
        private volatile String detail = "";
        private volatile String time = "";

        public StepState(int index, String key, String title) {
            this.index = index; this.key = key; this.title = title;
        }
    }

    @Data
    public static class LiveStats {
        private volatile int discovered;
        private volatile int verifiedCompanies;
        private volatile int contacts;
        private volatile int emailVerified;
        private volatile int duplicates;
        private volatile int rejected;
        private volatile int finalLeads;
    }

    public static class RunState {
        final List<StepState> steps = new ArrayList<>();
        final LiveStats stats = new LiveStats();
        volatile String status = "queued";
    }

    /** 内存进度（断电/重启后回退 DB 持久化的 stepLogs） */
    private final Map<Long, RunState> runStates = new ConcurrentHashMap<>();

    /** 排队顺序只用于向发起用户展示，不参与任务正确性。 */
    private final Queue<Long> queuedSessions = new ConcurrentLinkedQueue<>();

    /** 看门狗可中断正在执行的任务，避免失败任务继续占用工作线程。 */
    private final Map<Long, FutureTask<Void>> runningTasks = new ConcurrentHashMap<>();

    /** 所有并发任务共享 Serper 启动频率，防止并发后瞬时打爆第三方 API。 */
    private final Object serperRateLock = new Object();
    private long nextSerperStartNanos;

    /** LLM 连续超时时全局短时熔断，避免多用户同时占满获客专用线程池。 */
    private static final long LLM_BREAKER_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private final AtomicLong llmBackoffUntilMillis = new AtomicLong(0);

    @Data
    public static class StartRequest {
        private List<String> countries;
        private List<String> customerTypes;
        private List<String> products;
        private Integer targetCount;
    }

    // ═══════════════ 启动 ═══════════════

    public Long start(Long userId, StartRequest req) {
        if (req == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "请填写获客条件");
        }
        req.setCountries(normalizeRequestList(req.getCountries(), 10, 80));
        req.setCustomerTypes(normalizeRequestList(req.getCustomerTypes(), 12, 80));
        req.setProducts(normalizeRequestList(req.getProducts(), 20, 100));
        if (req.getCountries().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "请至少选择一个目标国家");
        }
        if (String.join(",", req.getCountries()).length() > 500
                || String.join(",", req.getCustomerTypes()).length() > 300
                || String.join(",", req.getProducts()).length() > 500) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "获客条件过长，请减少选项后重试");
        }
        int target = req.getTargetCount() == null ? 50 : Math.max(10,
                Math.min(req.getTargetCount(), props.getMaxTargetCount()));

        LeadHuntSession s = new LeadHuntSession();
        s.setUserId(userId);
        s.setCountries(String.join(",", req.getCountries()));
        s.setCustomerTypes(req.getCustomerTypes() == null ? "" : String.join(",", req.getCustomerTypes()));
        s.setProducts(req.getProducts() == null ? "" : String.join(",", req.getProducts()));
        s.setTargetCount(target);
        s.setStatus("queued");
        s.setCurrentStep(0);
        s.setProgress(0);
        RunState rs = newRunState();
        s.setStepLogs(JSON.toJSONString(rs.steps));
        s.setStatsJson(JSON.toJSONString(rs.stats));
        sessionMapper.insert(s);
        runStates.put(s.getId(), rs);

        runAsync(s.getId(), userId, req, target);
        return s.getId();
    }

    /**
     * 真正的异步执行入口（直接提交线程池，规避 @Async 自调用失效）。
     * 包裹一层 try-catch Throwable，确保任何异常都不会让线程静默死亡。
     */
    public void runAsync(Long sessionId, Long userId, StartRequest req, int target) {
        queuedSessions.add(sessionId);
        FutureTask<Void> task = new FutureTask<>(() -> {
            queuedSessions.remove(sessionId);
            RunState rs = runStates.computeIfAbsent(sessionId, id -> newRunState());
            rs.status = "running";
            updateSession(sessionId, s -> {
                s.setStatus("running");
                s.setErrorMsg(null);
            });
            try {
                execute(sessionId, userId, req, target);
            } catch (Throwable t) {
                log.error("[LeadHunter] session={} 线程异常退出", sessionId, t);
                try {
                    rs.status = "failed";
                    updateSession(sessionId, s -> {
                        s.setStatus("failed");
                        s.setErrorMsg(truncate("线程异常退出: " + t.getMessage(), 900));
                    });
                } catch (Exception ignored) {}
            } finally {
                queuedSessions.remove(sessionId);
                runningTasks.remove(sessionId);
                runStates.remove(sessionId);
            }
            return null;
        });
        runningTasks.put(sessionId, task);
        try {
            leadHuntExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            queuedSessions.remove(sessionId);
            runningTasks.remove(sessionId);
            runStates.remove(sessionId);
            updateSession(sessionId, s -> {
                s.setStatus("failed");
                s.setErrorMsg("全球获客并发队列已满，请稍后重试");
            });
            throw new BusinessException(ResultCode.ERROR.getCode(), "当前全球获客任务较多，请稍后重试");
        }
    }

    private RunState newRunState() {
        RunState rs = new RunState();
        for (int i = 0; i < STEP_KEYS.length; i++) {
            rs.steps.add(new StepState(i + 1, STEP_KEYS[i], STEP_TITLES[i]));
        }
        return rs;
    }

    /** 服务重启后旧 JVM 的后台线程已不存在，主动清理遗留状态，避免永久“运行中”。 */
    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedSessionsAfterRestart() {
        try {
            List<LeadHuntSession> interrupted = sessionMapper.selectList(
                    new LambdaQueryWrapper<LeadHuntSession>()
                            .in(LeadHuntSession::getStatus, List.of("queued", "running"))
                            .eq(LeadHuntSession::getDeleted, 0));
            for (LeadHuntSession s : interrupted) {
                updateSession(s.getId(), session -> {
                    session.setStatus("failed");
                    session.setErrorMsg("服务重启导致后台任务中断，请重新发起任务");
                });
            }
            if (!interrupted.isEmpty()) {
                log.warn("[LeadHunter] marked {} interrupted sessions failed after restart", interrupted.size());
            }
        } catch (Exception e) {
            log.warn("[LeadHunter] restart reconciliation failed: {}", e.getMessage());
        }
    }

    // ═══════════════ 看门狗 · 每 60s 扫描卡死任务 ═══════════════

    /**
     * 看门狗：每 60 秒扫描一次 status='running' 的 session，
     * 如果 update_time 超过 5 分钟没变化（说明线程已挂死），自动标记为 failed。
     * 防止 enrich LLM 超时后线程死亡 → 队列堵塞 → 后续任务永远排队。
     */
    @Scheduled(fixedDelay = 60_000)
    public void watchdogStuckSessions() {
        try {
            List<LeadHuntSession> running = sessionMapper.selectList(
                    new LambdaQueryWrapper<LeadHuntSession>()
                            .eq(LeadHuntSession::getStatus, "running")
                            .eq(LeadHuntSession::getDeleted, 0));
            if (running.isEmpty()) return;
            LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);
            for (LeadHuntSession s : running) {
                LocalDateTime upd = s.getUpdateTime();
                if (upd != null && upd.isBefore(cutoff)) {
                    log.warn("[LeadHunter watchdog] session={} stuck (>{}无更新), marking failed", s.getId(),
                            java.time.Duration.between(upd, LocalDateTime.now()).toMinutes());
                    RunState rs = runStates.get(s.getId());
                    if (rs != null) {
                        rs.status = "failed";
                        failCurrent(rs, s.getId(), "任务超过5分钟无进度更新");
                    }
                    FutureTask<Void> task = runningTasks.get(s.getId());
                    if (task != null) task.cancel(true);
                    updateSession(s.getId(), ses -> {
                        ses.setStatus("failed");
                        ses.setErrorMsg("看门狗检测到任务超过5分钟无进度更新，自动标记为失败（可能是 LLM 调用超时导致线程挂死）");
                    });
                }
            }
        } catch (Exception e) {
            log.warn("[LeadHunter watchdog] scan failed: {}", e.getMessage());
        }
    }

    void execute(Long sessionId, Long userId, StartRequest req, int target) {
        RunState rs = runStates.computeIfAbsent(sessionId, id -> newRunState());
        rs.status = "running";
        Quota quota = new Quota();
        try {
            // ── 1. 知识库分析 ──
            step(rs, sessionId, 1);
            String kbContext = buildKbContext();
            detail(rs, sessionId, 1, StringUtils.hasText(kbContext) ? "已读取知识库概况" : "知识库为空，使用默认产品画像");
            done(rs, sessionId, 1);

            // ── 2. 用户条件 ──
            step(rs, sessionId, 2);
            String condLine = "国家：" + String.join(" / ", req.getCountries())
                    + "；客户类型：" + (req.getCustomerTypes() == null || req.getCustomerTypes().isEmpty() ? "不限" : String.join(" / ", req.getCustomerTypes()))
                    + "；产品：" + (req.getProducts() == null || req.getProducts().isEmpty() ? "全产品线" : String.join(" / ", req.getProducts()))
                    + "；目标：" + target + " 条";
            detail(rs, sessionId, 2, condLine);
            done(rs, sessionId, 2);

            // ── 3. ICP 生成 ──
            step(rs, sessionId, 3);
            IcpResult icp = generateIcp(kbContext, req);
            persistIcp(sessionId, icp);
            detail(rs, sessionId, 3, icp.fallback
                    ? "LLM 暂不可用，已启用可靠默认 ICP 与搜索策略"
                    : "ICP 已生成，含 " + icp.queries.size() + " 组搜索策略");
            done(rs, sessionId, 3);

            // ── 4. 全球发现 ──
            step(rs, sessionId, 4);
            List<LeadHuntCompany> companies = discoverCompanies(sessionId, req, icp, target, rs, quota);
            insertCompanies(companies);   // 先入库拿 id，后续补全/联系人都靠 company_id 关联
            rs.stats.setDiscovered(companies.size());
            detail(rs, sessionId, 4, "发现 " + companies.size() + " 家候选公司");
            done(rs, sessionId, 4);

            // ── 5. 公司验证与补全 ──
            step(rs, sessionId, 5);
            enrichCompanies(companies, icp, rs, sessionId, quota, target, req.getCustomerTypes());
            long activeCompanies = companies.stream().filter(this::isActiveCompany).count();
            detail(rs, sessionId, 5, "联网补全行业/主营/地区/规模完成，可用公司 " + activeCompanies + " 家");
            done(rs, sessionId, 5);

            // ── 6. 联系人寻找 ──
            step(rs, sessionId, 6);
            List<LeadHuntContact> contacts = findContacts(sessionId, companies, target, rs, quota);
            rs.stats.setContacts(contacts.size());
            detail(rs, sessionId, 6, "找到 " + contacts.size() + " 个联系人（渠道："
                    + (props.getHunterApiKey() != null && !props.getHunterApiKey().isBlank() ? "Hunter + 网页" : "网页抽取（未配置 Hunter Key）") + "）");
            done(rs, sessionId, 6);

            // ── 7. 邮箱验证 ──
            step(rs, sessionId, 7);
            verifyEmails(contacts, rs, sessionId);
            int lowQualityRemoved = removeLowQualityInvalidContacts(contacts);
            rs.stats.setContacts(contacts.size());
            detail(rs, sessionId, 7, "验证完成：verified " + rs.stats.getEmailVerified() + " / " + contacts.size());
            if (lowQualityRemoved > 0) {
                detail(rs, sessionId, 7, "验证完成：verified " + rs.stats.getEmailVerified() + " / " + contacts.size()
                        + "，已过滤 " + lowQualityRemoved + " 个无姓名且无效的邮箱");
            }
            done(rs, sessionId, 7);

            // ── 8. 历史去重 ──
            step(rs, sessionId, 8);
            int[] dedupRes = dedup(sessionId, userId, companies, contacts);
            rs.stats.setDuplicates(dedupRes[0]);
            rs.stats.setRejected(rs.stats.getRejected() + dedupRes[1]);
            detail(rs, sessionId, 8, "历史重复公司 " + dedupRes[1] + " 家、重复联系人 " + dedupRes[0] + " 个已排除");
            done(rs, sessionId, 8);

            // ── 9. 智能评分 ──
            step(rs, sessionId, 9);
            scoring(companies, contacts, icp);
            int overTarget = trimToTarget(companies, contacts, target);
            rs.stats.setRejected(rs.stats.getRejected() + overTarget);
            detail(rs, sessionId, 9, "ICP 匹配分 + 联系人决策力分已计算"
                    + (overTarget > 0 ? "，已保留最高分的 " + target + " 家" : ""));
            done(rs, sessionId, 9);

            // ── 10. 结构化输出（更新公司 + 插入联系人） ──
            step(rs, sessionId, 10);
            saveAll(sessionId, companies, contacts);
            rs.stats.setFinalLeads((int) companies.stream().filter(c -> c.getDeleted() == null || c.getDeleted() == 0).count());
            detail(rs, sessionId, 10, "最终线索 " + rs.stats.getFinalLeads() + " 条已入库");
            done(rs, sessionId, 10);

            // ── 11. 完成 ──
            step(rs, sessionId, 11);
            detail(rs, sessionId, 11, "发现 " + rs.stats.getDiscovered() + " → 公司去重/剔除 " + rs.stats.getRejected()
                    + " → 最终 " + rs.stats.getFinalLeads()
                    + (rs.stats.getDuplicates() > 0 ? "；联系人去重 " + rs.stats.getDuplicates() : ""));
            done(rs, sessionId, 11);
            finish(rs, sessionId, "done", null);
            rs.status = "done";
            log.info("[LeadHunter] session={} done, final={}", sessionId, rs.stats.getFinalLeads());
        } catch (CancellationException e) {
            log.warn("[LeadHunter] session={} cancelled: {}", sessionId, e.getMessage());
        } catch (Exception e) {
            log.error("[LeadHunter] session={} failed", sessionId, e);
            if ("failed".equals(rs.status)) return;
            rs.status = "failed";
            failCurrent(rs, sessionId, e.getMessage());
            updateSession(sessionId, s -> {
                s.setStatus("failed");
                s.setErrorMsg(truncate(e.getMessage(), 900));
                s.setStepLogs(JSON.toJSONString(rs.steps));
                s.setStatsJson(JSON.toJSONString(rs.stats));
            });
        }
    }

    // ═══════════════ 各步骤实现 ═══════════════

    /** Step 1 · 汇总知识库概况（库名 + 描述 + 文档名样本） */
    private String buildKbContext() {
        StringBuilder sb = new StringBuilder();
        List<KnowledgeCollection> cols = collectionMapper.selectList(
                new LambdaQueryWrapper<KnowledgeCollection>().orderByDesc(KnowledgeCollection::getDocCount).last("LIMIT 20"));
        for (KnowledgeCollection c : cols) {
            sb.append("- 知识库《").append(c.getName()).append("》");
            if (StringUtils.hasText(c.getDescription())) sb.append("：").append(truncate(c.getDescription(), 100));
            sb.append("（").append(c.getDocCount() == null ? 0 : c.getDocCount()).append(" 篇文档）\n");
        }
        List<KbKnowledgeBase> docs = kbMapper.selectList(
                new LambdaQueryWrapper<KbKnowledgeBase>().select(KbKnowledgeBase::getName, KbKnowledgeBase::getSummary)
                        .last("LIMIT 40"));
        if (!docs.isEmpty()) {
            sb.append("文档样本：").append(docs.stream().map(KbKnowledgeBase::getName).filter(Objects::nonNull)
                    .limit(30).collect(Collectors.joining("、"))).append("\n");
        }
        return sb.toString();
    }

    /**
     * 带硬超时的 LLM 调用。
     *
     * <p>Spring AI 底层 OkHttp 未配 read timeout，HTTP/2 连接僵死会让调用方永久挂起
     * （实测 ICP/补全曾因此卡死）。这里用 CompletableFuture + 公共池强制兜底：
     * 超时后放弃结果、让工作流继续走降级路径（默认画像 / 不补全）。
     *
     * @param model 可选 · 覆盖默认聊天模型（批量分类用 qwen-plus，qwen3-max 思考模型太慢会超时）
     */
    private String callLlmWithTimeout(String system, String user, long timeoutSeconds, String model) {
        if (isLlmBackoffActive()) {
            throw new RuntimeException("LLM 暂时熔断中，走降级路径");
        }
        ChatClient client = ChatClient.builder(aiConfigHolder.getChatModel()).defaultSystem(system).build();
        var promptSpec = client.prompt().user(user);
        if (StringUtils.hasText(model)) {
            promptSpec = promptSpec.options(
                    org.springframework.ai.openai.OpenAiChatOptions.builder().model(model).build());
        }
        final var spec = promptSpec;
        CompletableFuture<String> future;
        try {
            future = CompletableFuture.supplyAsync(() -> spec.call().content(), leadHuntLlmExecutor);
        } catch (RejectedExecutionException rejected) {
            tripLlmBreaker();
            throw new RuntimeException("LLM 调用队列已满，走降级路径", rejected);
        }
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            tripLlmBreaker();
            throw new RuntimeException("LLM 调用超时（" + timeoutSeconds + "s），走降级路径");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("LLM 调用被中断", ie);
        } catch (Exception e) {
            tripLlmBreaker();
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("LLM 调用失败: " + cause.getMessage(), cause);
        }
    }

    private boolean isLlmBackoffActive() {
        return System.currentTimeMillis() < llmBackoffUntilMillis.get();
    }

    private void tripLlmBreaker() {
        long until = System.currentTimeMillis() + LLM_BREAKER_MILLIS;
        llmBackoffUntilMillis.accumulateAndGet(until, Math::max);
    }

    /** ICP 结果 */
    static class IcpResult {
        String summary = "";
        boolean fallback;
        List<String> queries = new ArrayList<>();
        List<String> positiveKeywords = new ArrayList<>();
        List<String> negativeKeywords = new ArrayList<>();
        List<String> competitors = new ArrayList<>();
    }

    /** Step 3 · LLM 生成 ICP + 搜索策略 */
    private IcpResult generateIcp(String kbContext, StartRequest req) {
        IcpResult r = new IcpResult();
        String system = """
                你是全球 B2B 获客专家。基于公司的知识库概况和目标条件，输出理想客户画像(ICP)与 Google 搜索策略。
                严格输出 JSON（不要多余文字）：
                {
                  "summary": "ICP 摘要（Markdown 要点，200 字内，含行业/规模/区域/决策角色）",
                  "queries": ["英文 Google 搜索词，每组面向一个国家×客户类型，10-20 组"],
                  "positive_keywords": ["行业/业务正面关键词(英文小写)"],
                  "negative_keywords": ["排除关键词(英文小写)，如 job, careers, amazon, alibaba"],
                  "competitors": ["该领域常见竞品品牌"]
                }
                """;
        String user = "知识库概况：\n" + (StringUtils.hasText(kbContext) ? kbContext : "（空，默认：IP 公共广播/对讲/统一通信设备制造商）")
                + "\n目标条件：国家/区域=" + (req.getCountries() == null ? "" :
                        req.getCountries().stream().map(this::searchScopeOf).collect(Collectors.joining(" | ")))
                + "；客户类型=" + (req.getCustomerTypes() == null ? "" : String.join(",", req.getCustomerTypes()))
                + "；产品=" + (req.getProducts() == null ? "" : String.join(",", req.getProducts()));
        try {
            String raw = callLlmWithTimeout(system, user, 45, null);
            int s = raw.indexOf('{'), e = raw.lastIndexOf('}');
            if (s >= 0 && e > s) {
                JSONObject o = JSON.parseObject(raw.substring(s, e + 1));
                r.summary = o.getString("summary");
                r.queries = toStringList(o.getJSONArray("queries"), 20);
                r.positiveKeywords = toStringList(o.getJSONArray("positive_keywords"), 30);
                r.negativeKeywords = toStringList(o.getJSONArray("negative_keywords"), 30);
                r.competitors = toStringList(o.getJSONArray("competitors"), 15);
            }
        } catch (Exception ex) {
            log.warn("[LeadHunter] ICP LLM failed, fallback default: {}", ex.getMessage());
            r.fallback = true;
            String types = req.getCustomerTypes().isEmpty() ? "Distributor / System Integrator"
                    : String.join(" / ", req.getCustomerTypes());
            r.summary = "（LLM 暂不可用，使用默认画像）面向 "
                    + String.join(" / ", req.getCountries()) + " 的 " + types
                    + "，关注商业音频、音视频与统一通信场景。";
        }
        if (r.queries.isEmpty()) {
            List<String> types = (req.getCustomerTypes() == null || req.getCustomerTypes().isEmpty())
                    ? List.of("distributor", "system integrator") : req.getCustomerTypes();
            for (String country : req.getCountries()) {
                for (String type : types) {
                    r.queries.add(type + " commercial audio visual communication equipment " + simpleSearchScopeOf(country));
                }
            }
        }
        if (r.negativeKeywords.isEmpty()) {
            r.negativeKeywords = List.of("job", "careers", "hiring", "amazon", "alibaba", "wikipedia", "youtube", "course", "training");
        }
        return r;
    }

    private record SearchPlan(String query, String country, String customerType) { }

    /** Step 4 · Serper 多组搜索发现候选公司 */
    private List<LeadHuntCompany> discoverCompanies(Long sessionId, StartRequest req, IcpResult icp,
                                                    int target, RunState rs, Quota quota) {
        Map<String, LeadHuntCompany> byDomain = new LinkedHashMap<>();
        int multiplier = 4;
        int needed = Math.min(target * multiplier, props.getMaxTargetCount() * multiplier);   // 多找一些，后面验证淘汰
        int successBefore = quota.searchSuccess;
        for (SearchPlan plan : buildSearchPlans(req, icp)) {
            if (byDomain.size() >= needed || quota.serper >= props.getMaxSerperCalls()) break;
            JSONObject root = serperSearch(plan.query(), 20, quota);
            if (root == null) continue;
            JSONArray organic = root.getJSONArray("organic");
            if (organic == null) continue;
            for (int i = 0; i < organic.size(); i++) {
                JSONObject item = organic.getJSONObject(i);
                if (item == null) continue;
                String link = item.getString("link");
                String title = item.getString("title");
                String snippet = item.getString("snippet");
                String domain = extractDomain(link);
                if (domain == null || isJunkDomain(domain, icp.negativeKeywords, plan.customerType())) continue;
                if (byDomain.containsKey(domain)) continue;
                LeadHuntCompany c = new LeadHuntCompany();
                c.setSessionId(sessionId);
                c.setDomain(domain);
                c.setWebsite("https://" + domain);
                c.setName(cleanCompanyName(title, domain));
                c.setSource(link);
                c.setSearchDate(LocalDate.now());
                c.setVerificationStatus("unverified");
                c.setDeleted(0);
                c.setCity(extractCity(snippet));
                c.setCountry(plan.country());
                c.setRegion(regionOf(plan.country()));
                // plan.customerType 只是检索意图，不等于已核实的公司类型；必须由官网证据重新判定。
                c.setCustomerType(null);
                byDomain.put(domain, c);
                if (byDomain.size() >= needed) break;
            }
            detail(rs, sessionId, 4, "已发现 " + byDomain.size() + " 家候选公司…");
        }
        if (quota.searchSuccess == successBefore) {
            String reason = StringUtils.hasText(quota.lastSearchError) ? quota.lastSearchError : "未配置可用的 Serper API Key";
            throw new BusinessException("公司搜索服务不可用：" + reason + "。请检查搜索 API 配置或稍后重试");
        }
        if (byDomain.isEmpty()) {
            throw new BusinessException("搜索已成功执行，但未发现符合条件的候选公司，请扩大国家或客户类型后重试");
        }
        return new ArrayList<>(byDomain.values());
    }

    /**
     * 可靠的简单查询优先，LLM 查询只作补充。Serper 免费账户会拒绝过长、
     * 带引号/括号/高级运算符的查询，所有查询在入队前统一规范化。
     */
    private List<SearchPlan> buildSearchPlans(StartRequest req, IcpResult icp) {
        LinkedHashMap<String, SearchPlan> plans = new LinkedHashMap<>();
        List<String> types = req.getCustomerTypes().isEmpty()
                ? List.of("distributor", "system integrator") : req.getCustomerTypes();
        for (String country : req.getCountries()) {
            String scope = simpleSearchScopeOf(country);
            for (String type : types) {
                for (String query : reliableQueriesForType(type, scope)) {
                    addSearchPlan(plans, query, country, type);
                }
            }
        }
        for (String query : icp.queries) {
            String country = inferCountryForQuery(query, req.getCountries());
            String type = types.size() == 1 ? types.get(0) : null;
            if (StringUtils.hasText(country)) addSearchPlan(plans, query, country, type);
        }
        return new ArrayList<>(plans.values());
    }

    private List<String> reliableQueriesForType(String type, String scope) {
        String normalizedType = type == null ? "" : type.toLowerCase(Locale.ROOT).trim();
        return switch (normalizedType) {
            case "end user" -> reliableEndUserQueries(scope);
            case "distributor" -> List.of("commercial audio equipment distributor " + scope);
            case "reseller" -> List.of("audio visual equipment reseller " + scope);
            case "system integrator" -> List.of("commercial AV system integrator " + scope);
            case "dealer" -> List.of("professional audio visual equipment dealer " + scope);
            case "project contractor" -> List.of("low voltage audio visual contractor " + scope);
            case "online shop" -> List.of("professional audio visual equipment online store " + scope);
            case "voip/cloud service provider" -> List.of("VoIP cloud communications service provider " + scope);
            case "consultancy" -> List.of("audio visual consulting firm " + scope);
            default -> List.of(type + " commercial audio visual communication equipment " + scope);
        };
    }

    private List<String> reliableEndUserQueries(String scope) {
        String normalized = scope == null ? "" : scope.toLowerCase(Locale.ROOT);
        if (normalized.contains("western united states")) {
            return List.of(
                    "California school district technology department audio visual",
                    "Washington university campus AV services",
                    "Oregon hospital facilities communication systems",
                    "Arizona municipal government emergency notification procurement",
                    "Colorado school district classroom technology",
                    "Utah university campus public address system");
        }
        if (normalized.contains("southern united states")) {
            return List.of(
                    "Texas school district technology department audio visual",
                    "Florida university campus AV services",
                    "Georgia hospital facilities communication systems",
                    "North Carolina municipal government emergency notification procurement",
                    "Virginia school district classroom technology");
        }
        if (normalized.contains("eastern united states")) {
            return List.of(
                    "New York school district technology department audio visual",
                    "New Jersey university campus AV services",
                    "Pennsylvania hospital facilities communication systems",
                    "Massachusetts municipal government emergency notification procurement",
                    "Illinois school district classroom technology",
                    "Ohio university campus public address system");
        }
        return List.of(
                "school district technology department audio visual " + scope,
                "university campus AV services " + scope,
                "hospital facilities communication systems " + scope,
                "municipal government emergency notification procurement " + scope);
    }

    private void addSearchPlan(Map<String, SearchPlan> plans, String query, String country, String customerType) {
        String normalized = normalizeSearchQuery(query);
        if (StringUtils.hasText(normalized)) {
            plans.putIfAbsent(normalized.toLowerCase(Locale.ROOT),
                    new SearchPlan(normalized, country, customerType));
        }
    }

    private String inferCountryForQuery(String query, List<String> countries) {
        if (countries.size() == 1) return countries.get(0);
        String lower = query == null ? "" : query.toLowerCase(Locale.ROOT);
        for (String country : countries) {
            String base = country.replaceFirst("(?i)\\s*-\\s*(east|south|west)$", "");
            if (lower.contains(country.toLowerCase(Locale.ROOT)) || lower.contains(base.toLowerCase(Locale.ROOT))) {
                return country;
            }
        }
        return null;
    }

    /** Step 5 · 公司信息补全（LLM 辅助 + 企业官网/公开搜索结果确定性补全） */
    private void enrichCompanies(List<LeadHuntCompany> companies, IcpResult icp, RunState rs, Long sessionId,
                                 Quota quota, int target, List<String> requestedCustomerTypes) {
        // 5.1 LLM 批量分类行业/主营业务（每批 20 家）
        for (int batchStart = 0; batchStart < companies.size(); batchStart += 10) {
            if (isLlmBackoffActive()) {
                detail(rs, sessionId, 5, "LLM 已熔断，跳过剩余分类批次并继续搜索验证");
                break;
            }
            List<LeadHuntCompany> batch = companies.subList(batchStart, Math.min(batchStart + 10, companies.size()));
            try {
                String input = batch.stream()
                        .map(c -> "{\"name\":\"" + esc(c.getName()) + "\",\"domain\":\"" + esc(c.getDomain()) + "\"}")
                        .collect(Collectors.joining(",", "[", "]"));
                String enrichSystem = """
                        你是 B2B 公司情报分析师。根据公司名和域名判断行业与主营业务。严格输出 JSON 数组，不要多余文字：
                        [{"name":"原样返回公司名","proper_name":"真实公司名（根据域名和标题推断，去掉页面标题噪声如产品型号/Home/Dealer/Find a Dealer 等；不确定就返回域名品牌名）",
                          "industry":"行业(英文)","major_business":"主营业务一句话(英文)",
                          "major_business_cn":"主营业务一句话(中文)","company_size":"规模估计如 11-50 或 unknown",
                          "customer_type":"Distributor/System Integrator/Contractor/End User/Retailer/Other"}]
                        """;
                String raw = callLlmWithTimeout(enrichSystem, input, 20, null);
                int s = raw.indexOf('['), e = raw.lastIndexOf(']');
                if (s < 0 || e <= s) continue;
                JSONArray arr = JSON.parseArray(raw.substring(s, e + 1));
                Map<String, JSONObject> byName = new HashMap<>();
                for (int i = 0; i < arr.size(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    if (o != null && o.getString("name") != null) byName.put(o.getString("name").toLowerCase(), o);
                }
                for (LeadHuntCompany c : batch) {
                    JSONObject o = byName.get(c.getName().toLowerCase());
                    if (o == null) continue;
                    // 优先用 LLM 修正后的真实公司名（去页面标题噪声）
                    String proper = o.getString("proper_name");
                    if (StringUtils.hasText(proper) && !proper.equalsIgnoreCase(c.getName())) {
                        c.setName(truncate(proper, 100));
                    }
                    if (!StringUtils.hasText(c.getIndustry())) c.setIndustry(truncate(o.getString("industry"), 190));
                    if (!StringUtils.hasText(c.getMajorBusiness())) c.setMajorBusiness(truncate(o.getString("major_business"), 480));
                    if (!StringUtils.hasText(c.getMajorBusinessCn())) c.setMajorBusinessCn(truncate(o.getString("major_business_cn"), 480));
                    // 规模必须来自明确的公开员工数，不能采用仅凭名称/域名的模型估计。
                    if (!StringUtils.hasText(c.getCustomerType())) c.setCustomerType(truncate(o.getString("customer_type"), 90));
                    c.setVerificationStatus("enriched");
                }
            } catch (Exception ex) {
                log.warn("[LeadHunter] enrich LLM batch failed: {}", ex.getMessage());
            }
            // 每批结束后更新进度，避免用户长时间看到 0%
            detail(rs, sessionId, 5, "enrichment 进度: " + Math.min(batchStart + 10, companies.size()) + "/" + companies.size());
        }
        // 5.2 对信息缺口大的候选公司检索官网、About/Services 与公开企业资料。
        // LLM 超时时，这条确定性路径仍会补齐行业、主营、规模、真实公司名和地址。
        List<LeadHuntCompany> needSearch = companies.stream()
                .filter(this::isActiveCompany)
                .filter(this::hasCompanyInfoGap)
                .limit(Math.min(Math.max(0, props.getMaxEnrichmentCalls() - quota.enrich), Math.max(10, target * 4L)))
                .collect(Collectors.toList());
        int verifyIndex = 0;
        for (LeadHuntCompany c : needSearch) {
            verifyIndex++;
            if (quota.serper >= props.getMaxSerperCalls()) break;
            quota.enrich++;
            detail(rs, sessionId, 5, "企业官网联网补全: " + verifyIndex + "/" + needSearch.size());
            String countryHint = StringUtils.hasText(c.getCountry()) ? simpleSearchScopeOf(c.getCountry())
                    : countryHintFromDomain(c.getDomain()).replace("\"", "");
            String q = c.getDomain() + " " + c.getName() + " company about services employees address " + countryHint;
            JSONObject root = serperSearch(q, 10, quota);
            if (root == null) continue;
            if (applyWebCompanyEvidence(c, root, icp)) {
                rs.stats.setVerifiedCompanies(rs.stats.getVerifiedCompanies() + 1);
            }
            detail(rs, sessionId, 5, "企业官网联网补全: " + verifyIndex + "/" + needSearch.size());
        }

        // 5.3 只依据明确的地址/业务证据做剔除；未知信息保留，避免误杀。
        int qualityRejected = 0;
        for (LeadHuntCompany c : companies) {
            if (!StringUtils.hasText(c.getCustomerType())) c.setCustomerType("Unknown");
            if (!isActiveCompany(c)) continue;
            String rejectReason = companyRejectReason(c, requestedCustomerTypes);
            if (rejectReason != null) {
                log.info("[LeadHunter] reject company domain={} reason={}", c.getDomain(), rejectReason);
                if (c.getId() != null) companyMapper.deleteById(c.getId());
                c.setDeleted(1);
                qualityRejected++;
            }
        }
        rs.stats.setRejected(rs.stats.getRejected() + qualityRejected);
        if (qualityRejected > 0) {
            detail(rs, sessionId, 5, "联网补全完成，已剔除 " + qualityRejected + " 家区域或客户类型不符的公司");
        }
    }

    /** Step 6 · 联系人寻找：Hunter 优先，降级网页抽取 */
    private List<LeadHuntContact> findContacts(Long sessionId, List<LeadHuntCompany> companies,
                                               int target, RunState rs, Quota quota) {
        List<LeadHuntContact> result = new ArrayList<>();
        boolean hunter = StringUtils.hasText(props.getHunterApiKey());
        // 优先给信息最全的公司找人（暂按发现顺序，评分在后面）
        int cap = Math.min(companies.size(), target);
        List<LeadHuntCompany> ordered = companies.stream().filter(this::isActiveCompany).limit(cap).collect(Collectors.toList());

        int processed = 0;
        for (LeadHuntCompany c : ordered) {
            processed++;
            detail(rs, sessionId, 6, "联系人搜索: " + processed + "/" + ordered.size() + "，已找到 " + result.size() + " 人");
            if (hunter) {
                List<LeadHuntContact> found = hunterDomainSearch(sessionId, c);
                if (!found.isEmpty()) {
                    result.addAll(found);
                    detail(rs, sessionId, 6, "联系人搜索: " + processed + "/" + ordered.size() + "，已找到 " + result.size() + " 人");
                    continue;
                }
            }
            // 网页抽取：搜索 "@domain" email
            if (quota.serper >= props.getMaxSerperCalls()) continue;
            JSONObject root = serperSearch(c.getDomain() + " contact email address", 10, quota);
            if (root == null) continue;
            Set<String> emails = extractEmails(mergeSnippets(root));
            int added = 0;
            for (String email : emails) {
                if (added >= 2) break;   // 每公司最多 2 个网页抽取联系人
                if (!emailBelongsToDomain(email, c.getDomain())) continue;
                LeadHuntContact ct = new LeadHuntContact();
                ct.setSessionId(sessionId);
                ct.setCompanyId(c.getId());
                ct.setCompanyName(c.getName());
                ct.setEmail(email.toLowerCase());
                ct.setEmailStatus("unverified");
                ct.setContactSource("web");
                ct.setDeleted(0);
                result.add(ct);
                added++;
            }
            detail(rs, sessionId, 6, "联系人搜索: " + processed + "/" + ordered.size() + "，已找到 " + result.size() + " 人");
        }
        // company_id 在 saveAll 时才真正生成 → 先用 domain 关联暂存
        return result;
    }

    /** Hunter domain-search */
    private List<LeadHuntContact> hunterDomainSearch(Long sessionId, LeadHuntCompany c) {
        List<LeadHuntContact> list = new ArrayList<>();
        // 防御性重试：Hunter 免费 API 间歇性返回空（429 / 网络抖动），最多重试 2 次
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                String url = props.getHunterDomainEndpoint() + "?domain=" + c.getDomain()
                        + "&limit=5&api_key=" + props.getHunterApiKey().trim();
                String body = webSearchRestTemplate.getForObject(URI.create(url), String.class);
                if (body == null) {
                    log.info("[LeadHunter] hunter attempt={} domain={} → empty body", attempt, c.getDomain());
                    continue;
                }
                JSONObject root = JSON.parseObject(body);
                JSONObject data = root.getJSONObject("data");
                if (data != null) applyHunterCompanyMetadata(c, data);
                JSONArray emails = data == null ? null : data.getJSONArray("emails");
                if (emails == null || emails.isEmpty()) {
                    log.info("[LeadHunter] hunter attempt={} domain={} → 0 emails (meta={})", attempt, c.getDomain(), root.getJSONObject("meta"));
                    break;  // HTTP 200 + 空结果是有效结果，不重试浪费配额
                }
                log.info("[LeadHunter] hunter attempt={} domain={} → {} emails", attempt, c.getDomain(), emails.size());
                for (int i = 0; i < emails.size() && list.size() < 3; i++) {
                    JSONObject o = emails.getJSONObject(i);
                    if (o == null) continue;
                    LeadHuntContact ct = new LeadHuntContact();
                    ct.setSessionId(sessionId);
                    ct.setCompanyId(c.getId());
                    ct.setCompanyName(c.getName());
                    String first = o.getString("first_name"), last = o.getString("last_name");
                    ct.setPersonName(((first == null ? "" : first) + " " + (last == null ? "" : last)).trim());
                    ct.setTitle(o.getString("position"));
                    String val = o.getString("value");
                    if (val == null) continue;
                    ct.setEmail(val.toLowerCase());
                    JSONObject verification = o.getJSONObject("verification");
                    String verificationStatus = verification == null ? null : verification.getString("status");
                    if ("valid".equalsIgnoreCase(verificationStatus)) ct.setEmailStatus("verified");
                    else if ("invalid".equalsIgnoreCase(verificationStatus)) ct.setEmailStatus("invalid");
                    else if ("accept-all".equalsIgnoreCase(verificationStatus)) ct.setEmailStatus("accept-all");
                    else ct.setEmailStatus("unverified");
                    ct.setPhone(o.getString("phone_number"));
                    ct.setContactSource("hunter");
                    ct.setDeleted(0);
                    list.add(ct);
                }
                break;  // 成功拿到结果，退出重试循环
            } catch (Exception e) {
                log.warn("[LeadHunter] hunter attempt={} domain={} failed: {}", attempt, c.getDomain(), e.getMessage());
                if (attempt < 2) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(300L * (attempt + 1));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        return list;
    }

    /** Step 7 · 邮箱验证（Hunter verifier 或格式校验） */
    private void verifyEmails(List<LeadHuntContact> contacts, RunState rs, Long sessionId) {
        boolean hunter = StringUtils.hasText(props.getHunterApiKey());
        int verified = 0;
        Pattern validPattern = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
        int processed = 0;
        for (LeadHuntContact ct : contacts) {
            processed++;
            if (processed == 1 || processed % 5 == 0 || processed == contacts.size()) {
                detail(rs, sessionId, 7, "邮箱验证: " + processed + "/" + contacts.size());
            }
            String email = ct.getEmail();
            if (email == null) { ct.setEmailStatus("invalid"); continue; }
            if (!validPattern.matcher(email).matches()) { ct.setEmailStatus("invalid"); continue; }
            if ("verified".equals(ct.getEmailStatus())) { verified++; continue; }
            if ("accept-all".equals(ct.getEmailStatus()) || "invalid".equals(ct.getEmailStatus())) continue;
            if (hunter) {
                try {
                    String url = props.getHunterVerifierEndpoint() + "?email=" + email
                            + "&api_key=" + props.getHunterApiKey().trim();
                    String body = webSearchRestTemplate.getForObject(URI.create(url), String.class);
                    if (body != null) {
                        JSONObject data = JSON.parseObject(body).getJSONObject("data");
                        if (data != null) {
                            String st = data.getString("status");   // valid / accept-all / invalid / undiscoverable
                            if ("valid".equals(st)) { ct.setEmailStatus("verified"); verified++; continue; }
                            if ("accept-all".equals(st)) { ct.setEmailStatus("accept-all"); continue; }
                            if ("invalid".equals(st)) { ct.setEmailStatus("invalid"); continue; }
                        }
                    }
                } catch (Exception ignored) { }
            }
            ct.setEmailStatus("unverified");
        }
        rs.stats.setEmailVerified(verified);
    }

    /** 无姓名、无电话且已确认无效的地址没有触达价值，不再污染结果表。 */
    private int removeLowQualityInvalidContacts(List<LeadHuntContact> contacts) {
        int before = contacts.size();
        contacts.removeIf(ct -> "invalid".equalsIgnoreCase(ct.getEmailStatus())
                && !StringUtils.hasText(ct.getPersonName()) && !StringUtils.hasText(ct.getPhone()));
        return before - contacts.size();
    }

    private boolean isRoleBlacklist(String email) {
        String local = email.substring(0, email.indexOf('@')).toLowerCase();
        return local.equals("postmaster") || local.equals("abuse") || local.equals("noreply")
                || local.equals("no-reply") || local.equals("webmaster");
    }

    /** Step 4 收尾 · 公司批量入库（拿到自增 id，供联系人/去重关联） */
    private void insertCompanies(List<LeadHuntCompany> companies) {
        Iterator<LeadHuntCompany> iterator = companies.iterator();
        while (iterator.hasNext()) {
            LeadHuntCompany c = iterator.next();
            try {
                companyMapper.insert(c);
            } catch (Exception e) {
                log.warn("[LeadHunter] insert company {} failed: {}", c.getDomain(), e.getMessage());
                iterator.remove();
            }
        }
        if (companies.isEmpty()) {
            throw new BusinessException("候选公司入库失败，未产生可用线索，请检查数据库后重试");
        }
    }

    /** Step 8 · 历史去重（跨会话域名 + 邮箱；同会话邮箱） */
    private int[] dedup(Long sessionId, Long userId, List<LeadHuntCompany> companies, List<LeadHuntContact> contacts) {
        int dupContacts = 0, rejectedCompanies = 0;
        // 公司：跨会话域名重复 → 逻辑删除
        for (LeadHuntCompany c : companies) {
            if (c.getDomain() != null && c.getId() != null
                    && companyMapper.countHistoryByDomain(sessionId, userId, c.getDomain()) > 0) {
                companyMapper.deleteById(c.getId());
                c.setDeleted(1);
                rejectedCompanies++;
            }
        }
        Set<Long> rejectedCompanyIds = companies.stream().filter(c -> c.getDeleted() != null && c.getDeleted() == 1)
                .map(LeadHuntCompany::getId).collect(Collectors.toSet());
        Set<String> sessionEmails = new HashSet<>(contactMapper.selectEmailsBySession(sessionId));
        Iterator<LeadHuntContact> it = contacts.iterator();
        while (it.hasNext()) {
            LeadHuntContact ct = it.next();
            if (ct.getCompanyId() != null && rejectedCompanyIds.contains(ct.getCompanyId())) { it.remove(); dupContacts++; continue; }
            if (ct.getEmail() == null) continue;
            if (sessionEmails.contains(ct.getEmail())) { it.remove(); dupContacts++; continue; }
            if (contactMapper.countHistoryByEmail(sessionId, userId, ct.getEmail()) > 0) { it.remove(); dupContacts++; continue; }
            sessionEmails.add(ct.getEmail());
        }
        return new int[]{dupContacts, rejectedCompanies};
    }

    /** Step 9 · 评分：ICP 匹配分 + 联系人决策力分 */
    private void scoring(List<LeadHuntCompany> companies, List<LeadHuntContact> contacts, IcpResult icp) {
        for (LeadHuntCompany c : companies) {
            int score = 40;
            String text = ((c.getIndustry() == null ? "" : c.getIndustry()) + " "
                    + (c.getMajorBusiness() == null ? "" : c.getMajorBusiness()) + " "
                    + (c.getName() == null ? "" : c.getName())).toLowerCase();
            for (String k : icp.positiveKeywords) {
                if (StringUtils.hasText(k) && text.contains(k.toLowerCase())) { score += 10; break; }
            }
            if (StringUtils.hasText(c.getCustomerType()) && !"unknown".equalsIgnoreCase(c.getCustomerType())) score += 15;
            if ("verified".equals(c.getVerificationStatus())) score += 10;
            else if ("enriched".equals(c.getVerificationStatus())) score += 5;
            if (StringUtils.hasText(c.getAddress()) || StringUtils.hasText(c.getCity())) score += 5;
            c.setIcpScore(Math.min(100, score));
        }
        Map<Long, Integer> companyScore = companies.stream()
                .collect(Collectors.toMap(LeadHuntCompany::getId, LeadHuntCompany::getIcpScore));
        for (LeadHuntContact ct : contacts) {
            int score = 30;
            String title = (ct.getTitle() == null ? "" : ct.getTitle()).toLowerCase();
            if (title.matches(".*\\b(owner|ceo|president|founder|co-founder|managing director|principal)\\b.*")) score = 90;
            else if (title.matches(".*\\b(vp|vice president|director)\\b.*")) score = 80;
            else if (title.matches(".*\\b(manager|purchasing|procurement|buyer|head of)\\b.*")) score = 70;
            else if (title.matches(".*\\b(engineer|technician|consultant|specialist)\\b.*")) score = 55;
            else if (title.contains("sales") || title.contains("marketing")) score = 35;
            if ("verified".equals(ct.getEmailStatus())) score = Math.min(100, score + 10);
            if ("hunter".equals(ct.getContactSource())) score = Math.min(100, score + 5);
            if (ct.getCompanyId() != null) {
                Integer cs = companyScore.get(ct.getCompanyId());
                if (cs != null) score = Math.min(100, score * (60 + cs) / 100);
            }
            ct.setContactScore(score);
        }
        companies.sort(Comparator.comparing(LeadHuntCompany::getIcpScore,
                Comparator.nullsLast(Comparator.reverseOrder())));
    }

    /** 发现阶段多找 3 倍候选，评分后必须收敛到用户设定的目标数。 */
    private int trimToTarget(List<LeadHuntCompany> companies, List<LeadHuntContact> contacts, int target) {
        int kept = 0;
        Set<Long> removedCompanyIds = new HashSet<>();
        for (LeadHuntCompany company : companies) {
            if (company.getDeleted() != null && company.getDeleted() == 1) continue;
            if (kept++ < target) continue;
            if (company.getId() != null) {
                companyMapper.deleteById(company.getId());
                removedCompanyIds.add(company.getId());
            }
            company.setDeleted(1);
        }
        if (!removedCompanyIds.isEmpty()) {
            contacts.removeIf(contact -> contact.getCompanyId() != null
                    && removedCompanyIds.contains(contact.getCompanyId()));
        }
        return removedCompanyIds.size();
    }

    /** Step 10 · 更新公司最终字段 + 联系人落库 */
    private void saveAll(Long sessionId, List<LeadHuntCompany> companies, List<LeadHuntContact> contacts) {
        Set<String> seenDomains = new HashSet<>();
        for (LeadHuntCompany c : companies) {
            if (!seenDomains.add(c.getDomain())) continue;   // 同会话同域名兜底只留第一条
            try {
                companyMapper.updateById(c);
            } catch (Exception e) {
                log.warn("[LeadHunter] update company {} failed: {}", c.getDomain(), e.getMessage());
            }
        }
        for (LeadHuntContact ct : contacts) {
            try {
                contactMapper.insert(ct);
            } catch (Exception e) {
                log.warn("[LeadHunter] insert contact {} failed: {}", ct.getEmail(), e.getMessage());
            }
        }
    }

    // ═══════════════ 查询 / 导出 ═══════════════

    @Data
    public static class LeadRow {
        private Long companyId;
        private Long contactId;
        private String country, region, company, person, title, email, emailStatus, phone, website,
                customerType, industry, majorBusiness, majorBusinessCn, city, state, address, zip;
        private Integer icpScore, contactScore;
        private String companySize, competitor, source, contactSource, verificationStatus, remarks;
        private String searchDate;
    }

    public Map<String, Object> getStatus(Long userId, Long sessionId) {
        LeadHuntSession s = requireOwnedSession(userId, sessionId);
        RunState rs = runStates.get(sessionId);
        Map<String, Object> out = new HashMap<>();
        out.put("session", s);
        if ("queued".equals(s.getStatus())) {
            int position = 1;
            for (Long queuedId : queuedSessions) {
                if (Objects.equals(queuedId, sessionId)) break;
                position++;
            }
            out.put("queuePosition", queuedSessions.contains(sessionId) ? position : 0);
        }
        if (rs != null) {
            out.put("steps", rs.steps);
            out.put("stats", rs.stats);
        } else {
            // 回退 DB 持久化日志
            try {
                List<StepState> steps = JSON.parseArray(s.getStepLogs(), StepState.class);
                out.put("steps", steps != null ? steps : List.of());
            } catch (Exception e) { out.put("steps", List.of()); }
            LiveStats st = new LiveStats();
            if (s.getStatsJson() != null) {
                try { st = JSON.parseObject(s.getStatsJson(), LiveStats.class); } catch (Exception ignored) {}
            }
            out.put("stats", st);
        }
        return out;
    }

    /** 结果列表（公司 LEFT JOIN 联系人展开，可筛选分页） */
    public Map<String, Object> getLeads(Long userId, Long sessionId, String keyword, String emailStatus,
                                        Integer minScore, Boolean onlyWithContact, int page, int size) {
        requireOwnedSession(userId, sessionId);
        return getLeadsInternal(sessionId, keyword, emailStatus, minScore, onlyWithContact, page, size);
    }

    private Map<String, Object> getLeadsInternal(Long sessionId, String keyword, String emailStatus,
                                                  Integer minScore, Boolean onlyWithContact, int page, int size) {
        List<LeadHuntCompany> companies = companyMapper.selectList(
                new LambdaQueryWrapper<LeadHuntCompany>()
                        .eq(LeadHuntCompany::getSessionId, sessionId)
                        .orderByDesc(LeadHuntCompany::getIcpScore));
        Map<Long, List<LeadHuntContact>> byCompany = contactMapper.selectList(
                        new LambdaQueryWrapper<LeadHuntContact>().eq(LeadHuntContact::getSessionId, sessionId)
                                .orderByDesc(LeadHuntContact::getContactScore))
                .stream().filter(c -> c.getCompanyId() != null)
                .collect(Collectors.groupingBy(LeadHuntContact::getCompanyId));

        List<LeadRow> rows = new ArrayList<>();
        for (LeadHuntCompany c : companies) {
            List<LeadHuntContact> cts = byCompany.getOrDefault(c.getId(), List.of());
            if (cts.isEmpty()) {
                if (Boolean.TRUE.equals(onlyWithContact)) continue;
                rows.add(toRow(c, null));
            } else {
                for (LeadHuntContact ct : cts) rows.add(toRow(c, ct));
            }
        }
        // 过滤
        if (StringUtils.hasText(keyword)) {
            String k = keyword.toLowerCase();
            rows = rows.stream().filter(r -> contains(r.getCompany(), k) || contains(r.getPerson(), k)
                    || contains(r.getEmail(), k) || contains(r.getTitle(), k) || contains(r.getIndustry(), k)
                    || contains(r.getCountry(), k) || contains(r.getCity(), k)).collect(Collectors.toList());
        }
        if (StringUtils.hasText(emailStatus)) {
            rows = rows.stream().filter(r -> emailStatus.equalsIgnoreCase(r.getEmailStatus())).collect(Collectors.toList());
        }
        if (minScore != null) {
            rows = rows.stream().filter(r -> r.getIcpScore() != null && r.getIcpScore() >= minScore).collect(Collectors.toList());
        }
        int total = rows.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(total, from + size);
        Map<String, Object> out = new HashMap<>();
        out.put("total", total);
        out.put("records", from < total ? rows.subList(from, to) : List.of());
        return out;
    }

    private boolean contains(String v, String k) { return v != null && v.toLowerCase().contains(k); }

    private LeadRow toRow(LeadHuntCompany c, LeadHuntContact ct) {
        LeadRow r = new LeadRow();
        r.setCompanyId(c.getId());
        r.setContactId(ct == null ? null : ct.getId());
        r.setCountry(c.getCountry()); r.setRegion(c.getRegion());
        r.setCompany(c.getName());
        r.setPerson(ct == null ? "" : ct.getPersonName());
        r.setTitle(ct == null ? "" : ct.getTitle());
        r.setEmail(ct == null ? "" : ct.getEmail());
        r.setEmailStatus(ct == null ? "" : ct.getEmailStatus());
        r.setPhone(ct == null ? "" : ct.getPhone());
        r.setWebsite(c.getWebsite());
        r.setCustomerType(c.getCustomerType()); r.setIndustry(c.getIndustry());
        r.setMajorBusiness(c.getMajorBusiness()); r.setMajorBusinessCn(c.getMajorBusinessCn());
        r.setCity(c.getCity()); r.setState(c.getState()); r.setAddress(c.getAddress()); r.setZip(c.getZip());
        r.setIcpScore(c.getIcpScore());
        r.setContactScore(ct == null ? null : ct.getContactScore());
        r.setCompanySize(c.getCompanySize()); r.setCompetitor(c.getCompetitor()); r.setSource(c.getSource());
        r.setContactSource(ct == null ? "" : ct.getContactSource());
        r.setVerificationStatus(c.getVerificationStatus());
        List<String> notes = new ArrayList<>();
        if (ct == null) notes.add("无联系人（建议手动开发）");
        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(c.getIndustry())) missing.add("Industry");
        if (!StringUtils.hasText(c.getMajorBusiness())) missing.add("Major Business");
        if (!StringUtils.hasText(c.getCompanySize())) missing.add("Company Size");
        if (!StringUtils.hasText(c.getAddress()) && !StringUtils.hasText(c.getCity())) missing.add("Address");
        if (!missing.isEmpty()) notes.add("未检索到可靠公开证据：" + String.join("、", missing));
        r.setRemarks(String.join("；", notes));
        r.setSearchDate(c.getSearchDate() == null ? null : c.getSearchDate().toString());
        return r;
    }

    /** 导出 26 列（Excel xlsx / CSV） */
    public byte[] export(Long userId, Long sessionId, String format) throws Exception {
        requireOwnedSession(userId, sessionId);
        Map<String, Object> leads = getLeadsInternal(sessionId, null, null, null, false, 1, 100000);
        @SuppressWarnings("unchecked")
        List<LeadRow> rows = (List<LeadRow>) leads.get("records");
        String[] headers = {"Country", "Region", "Company", "Person", "Title", "Email", "Email Status",
                "Phone", "Website", "Customer Type", "Industry", "Major Business", "Major Business CN",
                "City", "State", "Address", "Zip", "ICP Score", "Contact Score", "Company Size",
                "Competitor", "Source", "Contact Source", "Verification Status", "Remarks", "Search Date"};
        if ("csv".equalsIgnoreCase(format)) {
            StringBuilder sb = new StringBuilder("\uFEFF");   // BOM 防 Excel 乱码
            sb.append(String.join(",", headers)).append("\n");
            for (LeadRow r : rows) {
                String[] vals = {r.getCountry(), r.getRegion(), r.getCompany(), r.getPerson(), r.getTitle(),
                        r.getEmail(), r.getEmailStatus(), r.getPhone(), r.getWebsite(), r.getCustomerType(),
                        r.getIndustry(), r.getMajorBusiness(), r.getMajorBusinessCn(), r.getCity(), r.getState(),
                        r.getAddress(), r.getZip(), num(r.getIcpScore()), num(r.getContactScore()),
                        r.getCompanySize(), r.getCompetitor(), r.getSource(), r.getContactSource(),
                        r.getVerificationStatus(), r.getRemarks(), r.getSearchDate()};
                sb.append(Arrays.stream(vals).map(this::csvEscape).collect(Collectors.joining(","))).append("\n");
            }
            return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        // xlsx（POI SXSSF 流式写，防大结果集 OOM）
        try (org.apache.poi.xssf.streaming.SXSSFWorkbook wb = new org.apache.poi.xssf.streaming.SXSSFWorkbook(100);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("Leads");
            org.apache.poi.ss.usermodel.Font bold = wb.createFont();
            bold.setBold(true);
            org.apache.poi.ss.usermodel.CellStyle hs = wb.createCellStyle();
            hs.setFont(bold);
            org.apache.poi.ss.usermodel.Row head = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                org.apache.poi.ss.usermodel.Cell cell = head.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(hs);
            }
            int rowIdx = 1;
            for (LeadRow r : rows) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(rowIdx++);
                String[] vals = {r.getCountry(), r.getRegion(), r.getCompany(), r.getPerson(), r.getTitle(),
                        r.getEmail(), r.getEmailStatus(), r.getPhone(), r.getWebsite(), r.getCustomerType(),
                        r.getIndustry(), r.getMajorBusiness(), r.getMajorBusinessCn(), r.getCity(), r.getState(),
                        r.getAddress(), r.getZip(), num(r.getIcpScore()), num(r.getContactScore()),
                        r.getCompanySize(), r.getCompetitor(), r.getSource(), r.getContactSource(),
                        r.getVerificationStatus(), r.getRemarks(), r.getSearchDate()};
                for (int i = 0; i < vals.length; i++) {
                    String v = vals[i] == null ? "" : vals[i];
                    if (i == 17 || i == 18) {   // 分数列写数字
                        try { row.createCell(i).setCellValue(Integer.parseInt(v)); continue; }
                        catch (NumberFormatException ignored) { }
                    }
                    row.createCell(i).setCellValue(v);
                }
            }
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    private String num(Integer v) { return v == null ? "" : String.valueOf(v); }

    private String csvEscape(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    public List<LeadHuntSession> listSessions(Long userId) {
        return sessionMapper.selectList(new LambdaQueryWrapper<LeadHuntSession>()
                .eq(LeadHuntSession::getUserId, userId)
                .orderByDesc(LeadHuntSession::getId).last("LIMIT 50"));
    }

    /** 国家维度线索分布：只聚合当前用户，可选限定当前任务。 */
    public List<Map<String, Object>> getCountryStats(Long userId, Long sessionId) {
        if (sessionId != null) requireOwnedSession(userId, sessionId);
        return companyMapper.countByCountry(userId, sessionId);
    }

    /** 用户主动取消排队或运行中任务，及时释放有界队列容量。 */
    public void cancel(Long userId, Long sessionId) {
        LeadHuntSession session = requireOwnedSession(userId, sessionId);
        if (!List.of("queued", "running").contains(session.getStatus())) return;
        RunState rs = runStates.get(sessionId);
        if (rs != null) {
            rs.status = "cancelled";
            rs.steps.stream().filter(s -> "running".equals(s.getStatus())).findFirst().ifPresent(s -> {
                s.setStatus("skipped");
                s.setDetail("用户已取消任务");
            });
        }
        queuedSessions.remove(sessionId);
        FutureTask<Void> task = runningTasks.remove(sessionId);
        if (task != null) task.cancel(true);
        updateSession(sessionId, s -> {
            s.setStatus("cancelled");
            s.setErrorMsg("用户已取消任务");
            if (rs != null) {
                s.setStepLogs(JSON.toJSONString(rs.steps));
                s.setStatsJson(JSON.toJSONString(rs.stats));
            }
        });
        runStates.remove(sessionId);
    }

    private LeadHuntSession requireOwnedSession(Long userId, Long sessionId) {
        LeadHuntSession session = sessionMapper.selectById(sessionId);
        if (session == null || !Objects.equals(session.getUserId(), userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "任务不存在或无权访问");
        }
        return session;
    }

    // ═══════════════ 工具方法 ═══════════════

    static class Quota {
        int serper;
        int enrich;
        int searchSuccess;
        int searchFailures;
        String lastSearchError;
    }

    private JSONObject serperSearch(String q, int num, Quota quota) {
        if (!StringUtils.hasText(webSearchProps.getSerperApiKey())) {
            log.warn("[LeadHunter] Serper key 未配置，跳过搜索");
            quota.searchFailures++;
            quota.lastSearchError = "Serper API Key 未配置";
            return null;
        }
        if (!awaitSerperSlot()) {
            quota.searchFailures++;
            quota.lastSearchError = "搜索任务已中断";
            return null;
        }
        quota.serper++;
        String normalizedQuery = normalizeSearchQuery(q);
        if (!StringUtils.hasText(normalizedQuery)) {
            quota.searchFailures++;
            quota.lastSearchError = "搜索词为空";
            return null;
        }
        try {
            JSONObject body = new JSONObject();
            body.put("q", normalizedQuery);
            body.put("num", Math.max(1, Math.min(num, 20)));
            body.put("hl", "en");
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-API-KEY", webSearchProps.getSerperApiKey().trim());
            String resp = webSearchRestTemplate.postForObject(
                    webSearchProps.getSerperEndpoint(), new HttpEntity<>(body.toJSONString(), headers), String.class);
            if (!StringUtils.hasText(resp)) {
                quota.searchFailures++;
                quota.lastSearchError = "搜索服务返回空响应";
                return null;
            }
            JSONObject root = JSON.parseObject(resp);
            if (StringUtils.hasText(root.getString("message")) && root.getJSONArray("organic") == null) {
                quota.searchFailures++;
                quota.lastSearchError = truncate(root.getString("message"), 180);
                return null;
            }
            quota.searchSuccess++;
            return root;
        } catch (Exception e) {
            quota.searchFailures++;
            quota.lastSearchError = searchErrorMessage(e);
            log.warn("[LeadHunter] serper failed q='{}': {}", normalizedQuery, quota.lastSearchError);
            return null;
        }
    }

    private String searchErrorMessage(Exception error) {
        if (error instanceof RestClientResponseException responseError) {
            try {
                JSONObject body = JSON.parseObject(responseError.getResponseBodyAsString());
                String message = body.getString("message");
                if (StringUtils.hasText(message)) return truncate(message, 180);
            } catch (Exception ignored) { }
            return "HTTP " + responseError.getStatusCode().value();
        }
        return truncate(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), 180);
    }

    /**
     * 跨任务限速：并发任务可以同时处理 LLM/数据库，但 Serper 请求起始时间保持最小间隔。
     * 这样既消除单线程排队，也不会因并发放大第三方 API 的 429/超时。
     */
    private boolean awaitSerperSlot() {
        long intervalNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, props.getSerperIntervalMs()));
        synchronized (serperRateLock) {
            long waitNanos = Math.max(0, nextSerperStartNanos - System.nanoTime());
            if (waitNanos > 0) {
                try {
                    TimeUnit.NANOSECONDS.sleep(waitNanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            nextSerperStartNanos = System.nanoTime() + intervalNanos;
            return true;
        }
    }

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private Set<String> extractEmails(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        Matcher m = EMAIL_PATTERN.matcher(text);
        while (m.find()) {
            String e = m.group().toLowerCase();
            String local = e.substring(0, e.indexOf('@'));
            String domain = e.substring(e.indexOf('@') + 1);
            if (local.equals("noreply") || local.equals("no-reply") || local.equals("postmaster")
                    || local.equals("abuse") || local.contains("example") || local.equals("test")) continue;
            if (domain.endsWith(".png") || domain.endsWith(".jpg") || domain.endsWith(".webp")
                    || domain.endsWith(".js") || domain.endsWith(".css")) continue;
            out.add(e);
        }
        return out;
    }

    private boolean emailBelongsToDomain(String email, String companyDomain) {
        if (!StringUtils.hasText(email) || !StringUtils.hasText(companyDomain) || !email.contains("@")) return false;
        String emailDomain = email.substring(email.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);
        String domain = companyDomain.toLowerCase(Locale.ROOT);
        return emailDomain.equals(domain) || emailDomain.endsWith("." + domain);
    }

    private boolean isActiveCompany(LeadHuntCompany company) {
        return company != null && (company.getDeleted() == null || company.getDeleted() == 0);
    }

    private boolean hasCompanyInfoGap(LeadHuntCompany company) {
        return !StringUtils.hasText(company.getIndustry())
                || !StringUtils.hasText(company.getMajorBusiness())
                || !StringUtils.hasText(company.getMajorBusinessCn())
                || !StringUtils.hasText(company.getCompanySize())
                || !StringUtils.hasText(company.getAddress())
                || !StringUtils.hasText(company.getState())
                || !StringUtils.hasText(company.getName())
                || "unknown".equalsIgnoreCase(company.getCustomerType());
    }

    private record WebResult(String title, String link, String snippet, int rank) { }

    /**
     * 只把企业官网结果与明确引用该域名的企业资料合并，避免把列表文章中其他公司的信息串入。
     * 返回 true 表示找到了同域官网证据。
     */
    private boolean applyWebCompanyEvidence(LeadHuntCompany company, JSONObject root, IcpResult icp) {
        JSONArray organic = root.getJSONArray("organic");
        if (organic == null || organic.isEmpty()) return false;
        List<WebResult> official = new ArrayList<>();
        List<String> corroborating = new ArrayList<>();
        for (int i = 0; i < organic.size() && i < 10; i++) {
            JSONObject item = organic.getJSONObject(i);
            if (item == null) continue;
            String link = item.getString("link");
            String title = item.getString("title");
            String snippet = item.getString("snippet");
            String resultDomain = extractDomain(link);
            if (sameCompanyDomain(resultDomain, company.getDomain())) {
                official.add(new WebResult(title, link, snippet, officialResultRank(link)));
                if (StringUtils.hasText(snippet)) corroborating.add(snippet);
            } else if (StringUtils.hasText(snippet) && StringUtils.hasText(company.getDomain())
                    && snippet.toLowerCase(Locale.ROOT).contains(company.getDomain().toLowerCase(Locale.ROOT))) {
                corroborating.add((title == null ? "" : title + ". ") + snippet);
            }
        }
        if (official.isEmpty()) return false;
        official.sort(Comparator.comparingInt(WebResult::rank));
        WebResult primary = official.get(0);
        String evidence = String.join(" ", corroborating);
        String properName = properCompanyName(primary.title(), company.getDomain(), evidence);
        if (StringUtils.hasText(properName)) company.setName(truncate(properName, 100));
        if (StringUtils.hasText(primary.link())) company.setSource(primary.link());
        String industry = classifyIndustry(evidence);
        if (StringUtils.hasText(industry)) company.setIndustry(industry);
        String business = bestBusinessSummary(official);
        if (StringUtils.hasText(business)) company.setMajorBusiness(truncate(business, 480));
        if (StringUtils.hasText(industry)) company.setMajorBusinessCn(chineseBusinessSummary(industry));
        String companySize = extractCompanySize(evidence);
        if (StringUtils.hasText(companySize)) company.setCompanySize(companySize);
        String detectedType = classifyCustomerType(evidence);
        if (StringUtils.hasText(detectedType)) company.setCustomerType(detectedType);

        String addr = extractAddress(evidence);
        String city = extractCity(evidence);
        String state = extractState(evidence);
        String zip = extractZip(evidence);
        String normalizedState = normalizeUsState(state, evidence);
        if (normalizedState == null) normalizedState = inferUsStateFromDomain(company.getDomain());
        if (normalizedState != null) state = normalizedState;
        if (!StringUtils.hasText(company.getAddress()) && StringUtils.hasText(addr)) company.setAddress(addr);
        if (!StringUtils.hasText(company.getCity()) && StringUtils.hasText(city)) company.setCity(city);
        if (!StringUtils.hasText(company.getState()) && StringUtils.hasText(state)) company.setState(state);
        if (!StringUtils.hasText(company.getZip()) && StringUtils.hasText(zip)) company.setZip(zip);
        String competitor = matchCompetitor(evidence, icp.competitors);
        if (StringUtils.hasText(competitor)) company.setCompetitor(competitor);
        company.setVerificationStatus("verified");
        return true;
    }

    private void applyHunterCompanyMetadata(LeadHuntCompany company, JSONObject data) {
        String organization = data.getString("organization");
        if (StringUtils.hasText(organization)) company.setName(truncate(organization.trim(), 100));
        if (!StringUtils.hasText(company.getIndustry()) && StringUtils.hasText(data.getString("industry"))) {
            company.setIndustry(truncate(data.getString("industry"), 190));
        }
        if (!StringUtils.hasText(company.getMajorBusiness()) && StringUtils.hasText(data.getString("description"))) {
            company.setMajorBusiness(truncate(data.getString("description"), 480));
        }
        if (!StringUtils.hasText(company.getCompanySize())) {
            String size = extractCompanySize(String.valueOf(data.get("headcount")) + " employees");
            if (StringUtils.hasText(size)) company.setCompanySize(size);
        }
        String state = data.getString("state");
        String city = data.getString("city");
        String zip = data.getString("postal_code");
        String street = data.getString("street");
        if (addressesMatchCountry(street, city, state, zip, company.getCountry(), company.getDomain())) {
            if (!StringUtils.hasText(company.getState()) && StringUtils.hasText(state)) company.setState(state);
            if (!StringUtils.hasText(company.getCity()) && StringUtils.hasText(city)) company.setCity(city);
            if (!StringUtils.hasText(company.getZip()) && StringUtils.hasText(zip)) company.setZip(zip);
            if (!StringUtils.hasText(company.getAddress()) && StringUtils.hasText(street)) company.setAddress(street);
        }
        if (!"verified".equals(company.getVerificationStatus())) company.setVerificationStatus("enriched");
    }

    private static boolean sameCompanyDomain(String candidate, String companyDomain) {
        if (!StringUtils.hasText(candidate) || !StringUtils.hasText(companyDomain)) return false;
        String a = candidate.toLowerCase(Locale.ROOT);
        String b = companyDomain.toLowerCase(Locale.ROOT);
        return a.equals(b) || a.endsWith("." + b) || b.endsWith("." + a);
    }

    private int officialResultRank(String link) {
        if (!StringUtils.hasText(link)) return 9;
        try {
            String path = Optional.ofNullable(URI.create(link).getPath()).orElse("").toLowerCase(Locale.ROOT);
            if (path.isBlank() || "/".equals(path)) return 0;
            if (path.contains("about") || path.contains("company")) return 1;
            if (path.contains("service") || path.contains("solution") || path.contains("product")) return 2;
            if (path.contains("contact")) return 3;
            if (path.contains("blog") || path.contains("news") || path.contains("career")) return 8;
        } catch (Exception ignored) { }
        return 5;
    }

    private String properCompanyName(String title, String domain, String evidence) {
        if (!StringUtils.hasText(title)) return null;
        String candidate = title.split("\\|")[0].split(" - ")[0].split(" – ")[0].split(":")[0].trim();
        String lower = candidate.toLowerCase(Locale.ROOT);
        String fromEvidence = organizationNameFromEvidence(evidence);
        if (StringUtils.hasText(fromEvidence) && !candidate.contains(" ")) return fromEvidence;
        if (candidate.length() < 2 || candidate.length() > 100 || lower.startsWith("home")
                || lower.startsWith("about") || lower.startsWith("contact") || lower.startsWith("services") || lower.startsWith("solutions")
                || lower.startsWith("technology") || lower.startsWith("emergency") || lower.startsWith("classroom services")
                || lower.startsWith("ready") || lower.startsWith("managed ") || lower.startsWith("commercial ") || lower.startsWith("audio visual")) {
            if (StringUtils.hasText(fromEvidence)) return fromEvidence;
            if ("ein.az.gov".equalsIgnoreCase(domain)) return "Arizona Emergency Information Network";
            return cleanCompanyName(null, domain);
        }
        return candidate;
    }

    private String organizationNameFromEvidence(String evidence) {
        if (!StringUtils.hasText(evidence)) return null;
        Matcher publicOrg = Pattern.compile("\\b((?:City|Town) of [A-Z][A-Za-z.'-]+(?: [A-Z][A-Za-z.'-]+){0,3}|"
                + "[A-Z][A-Za-z.'-]+(?: [A-Z][A-Za-z.'-]+){0,5} (?:Unified )?School District|"
                + "[A-Z][A-Za-z.'-]+(?: [A-Z][A-Za-z.'-]+){0,4} County|"
                + "University of [A-Z][A-Za-z.'-]+(?: [A-Z][A-Za-z.'-]+){0,3}|"
                + "[A-Z][A-Za-z.'-]+(?: [A-Z][A-Za-z.'-]+){0,4} (?:University|Commission|Authority))\\b")
                .matcher(evidence);
        return publicOrg.find() ? publicOrg.group(1).trim() : null;
    }

    private String bestBusinessSummary(List<WebResult> official) {
        return official.stream()
                .filter(r -> StringUtils.hasText(r.snippet()))
                .filter(r -> r.rank() <= 5)
                .sorted(Comparator.comparingInt(WebResult::rank)
                        .thenComparing((WebResult r) -> r.snippet().length(), Comparator.reverseOrder()))
                .map(WebResult::snippet)
                .map(String::trim)
                .filter(s -> s.length() >= 35)
                .findFirst().orElse(null);
    }

    static String classifyIndustry(String text) {
        if (!StringUtils.hasText(text)) return null;
        String t = text.toLowerCase(Locale.ROOT);
        if (containsAny(t, "school district", "university", "college", "education", "classroom", "campus")) return "Education";
        if (containsAny(t, "hospital", "healthcare", "medical center", "patient care")) return "Healthcare";
        if (containsAny(t, "municipal", "government agency", "public sector", "city government", "city of ",
                " county department", "emergency management", "environmental regulation", "state agency")) return "Government";
        if (containsAny(t, "audio visual", "audiovisual", "av integration", "a/v integration", "pro av", "video conferencing")) return "Professional Audio Visual";
        if (containsAny(t, "managed it", "information technology", "cybersecurity", "it support")) return "Information Technology & Services";
        if (containsAny(t, "voip", "telecommunications", "unified communications", "cloud communications")) return "Telecommunications";
        if (containsAny(t, "security integration", "surveillance", "access control")) return "Security Systems";
        if (containsAny(t, "low voltage", "electrical contractor", "electrical services")) return "Electrical & Low Voltage";
        if (containsAny(t, "distributor", "wholesale", "distribution company", "global distribution",
                "electrical distribution", "supply chain solutions")) return "Wholesale Distribution";
        if (containsAny(t, "manufacturer", "manufacturing")) return "Manufacturing";
        if (containsAny(t, "retail store", "online store", "e-commerce")) return "Retail";
        if (containsAny(t, "news", "magazine", "publication", "editorial")) return "Media & Publishing";
        return null;
    }

    static String classifyCustomerType(String text) {
        if (!StringUtils.hasText(text)) return null;
        String t = text.toLowerCase(Locale.ROOT);
        if (containsAny(t, "system integrator", "systems integrator", " integrator", "av integration", "a/v integration", "integration company",
                "audiovisual solutions", "systems we install", "design and install", "design, install", "design, build",
                "installation company", "life safety systems market leader", "full-service av provider",
                "digital workplace services provider")) return "System Integrator";
        if (containsAny(t, "project contractor", "general contractor", "low voltage contractor", "electrical contractor")) return "Project Contractor";
        if (containsAny(t, "authorized distributor", "distributor", "wholesale", "distribution company",
                "global distribution", "electrical distribution", "supply chain solutions")) return "Distributor";
        if (containsAny(t, "reseller", "value-added reseller", "var partner")) return "Reseller";
        if (containsAny(t, "global marketplace", "equipment marketplace", "selling new and used", "buy and sell equipment")) return "Reseller";
        if (containsAny(t, "online store", "e-commerce", "shop online")) return "Online Shop";
        if (containsAny(t, "voip provider", "cloud service provider", "managed service provider")) return "VOIP/Cloud Service Provider";
        if (containsAny(t, "consulting firm", "consultancy", "av consultant")) return "Consultancy";
        if (containsAny(t, "manufacturer", "manufactures", "software platform", "ai-powered communication", "saas platform",
                "nurse call systems for hospitals", "hospital communication systems", "solutions for hospitals")) return "Other";
        if (containsAny(t, "dealer")) return "Dealer";
        if (containsAny(t, "news", "magazine", "publication", "editorial", "journalism", "news coverage", "bbc news")) return "Other";
        if (containsAny(t, "school district", "university", "college", "hospital", "healthcare system",
                "municipal", "government agency", "city of ", " county department", "emergency management",
                "state agency", "public library", "corporate campus")) return "End User";
        return null;
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }

    static String extractCompanySize(String text) {
        if (!StringUtils.hasText(text) || "null employees".equalsIgnoreCase(text.trim())) return null;
        Matcher range = Pattern.compile("(?i)\\b(\\d{1,6})\\s*(?:-|–|—|to)\\s*(\\d{1,6})\\s*(?:employees|employee|people|staff|headcount)\\b").matcher(text);
        if (range.find()) return range.group(1) + "-" + range.group(2);
        Matcher prefix = Pattern.compile("(?i)\\b(?:employees|employee|people|staff|headcount)\\s*[:=]?\\s*(\\d{1,6})(?:\\.\\d+)?\\b").matcher(text);
        if (prefix.find()) return prefix.group(1);
        Matcher exact = Pattern.compile("(?i)\\b(\\d{1,6})\\s*\\+?\\s*(?:employees|employee|people|staff|headcount)\\b").matcher(text);
        if (exact.find()) return exact.group(1) + (exact.group().contains("+") ? "+" : "");
        return null;
    }

    private String chineseBusinessSummary(String industry) {
        if (!StringUtils.hasText(industry)) return null;
        return switch (industry) {
            case "Education" -> "教育机构及校园场景服务。";
            case "Healthcare" -> "医疗机构及相关设施服务。";
            case "Government" -> "政府及公共部门场景服务。";
            case "Professional Audio Visual" -> "专业音视频系统集成与相关技术服务。";
            case "Information Technology & Services" -> "企业信息技术、托管与技术支持服务。";
            case "Telecommunications" -> "通信、VoIP 与统一通信相关服务。";
            case "Security Systems" -> "安防、监控与门禁系统集成服务。";
            case "Electrical & Low Voltage" -> "电气及弱电系统工程服务。";
            case "Wholesale Distribution" -> "专业设备批发与渠道分销。";
            case "Manufacturing" -> "相关设备与解决方案制造。";
            case "Retail" -> "相关设备零售与在线销售。";
            case "Media & Publishing" -> "行业媒体与内容出版。";
            default -> null;
        };
    }

    private String companyRejectReason(LeadHuntCompany company, List<String> requestedCustomerTypes) {
        if (isSpecificUsRegion(company.getCountry())
                && normalizeUsState(company.getState(), String.join(" ", nullToEmpty(company.getAddress()),
                nullToEmpty(company.getCity()), nullToEmpty(company.getState()))) == null) {
            return "target-region-unverified";
        }
        if (!addressesMatchCountry(company.getAddress(), company.getCity(), company.getState(), company.getZip(),
                company.getCountry(), company.getDomain())) return "target-region-mismatch";
        if (requestedCustomerTypes != null && !requestedCustomerTypes.isEmpty()) {
            String actual = normalizeCustomerType(company.getCustomerType());
            if (!StringUtils.hasText(actual) || "unknown".equals(actual)) return "customer-type-unverified";
            boolean requested = requestedCustomerTypes.stream()
                    .map(LeadHunterService::normalizeCustomerType)
                    .anyMatch(actual::equals);
            if (!requested) return "customer-type-mismatch";
        }
        return null;
    }

    private static String normalizeCustomerType(String value) {
        if (!StringUtils.hasText(value)) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static boolean isSpecificUsRegion(String country) {
        if (!StringUtils.hasText(country)) return false;
        String value = country.toLowerCase(Locale.ROOT).trim();
        return value.endsWith("- west") || value.endsWith("- south") || value.endsWith("- east");
    }

    private String extractDomain(String url) {
        if (url == null) return null;
        try {
            URI u = URI.create(url.trim());
            String host = u.getHost();
            if (host == null) return null;
            host = host.toLowerCase();
            if (host.startsWith("www.")) host = host.substring(4);
            return host;
        } catch (Exception e) { return null; }
    }

    private static final Set<String> JUNK = Set.of(
            "linkedin.com", "facebook.com", "twitter.com", "x.com", "youtube.com", "instagram.com",
            "wikipedia.org", "amazon.com", "alibaba.com", "made-in-china.com", "globalsources.com",
            "yelp.com", "reddit.com", "glassdoor.com", "indeed.com", "zoominfo.com", "dnb.com",
            "crunchbase.com", "medium.com", "quora.com", "pinterest.com", "tiktok.com", "github.com",
            "apple.com", "google.com", "microsoft.com", "bing.com", "baidu.com",
            "capterra.com", "g2.com", "fandom.com", "trustpilot.com", "thomasnet.com",
            "indiamart.com", "tradekey.com", "ec21.com", "justdial.com", "cnet.com",
            "canada.ca", "ouvert.canada.ca", "crtc.gc.ca", "avnetwork.com", "globalspec.com",
            "commercialintegrator.com", "itsupplychain.com", "bbc.com", "wbaltv.com",
            "universitybusiness.com", "pmc.ncbi.nlm.nih.gov", "ncbi.nlm.nih.gov", "secure.sos.state.or.us");

    private boolean isJunkDomain(String domain, List<String> negativeKeywords, String customerType) {
        String d = domain.toLowerCase();
        // End User 检索中，政府/学校官网本身就是目标客户，不应被当成噪音。
        boolean endUserSearch = "end user".equalsIgnoreCase(customerType);
        if (d.endsWith(".gov") || d.endsWith(".mil") || d.endsWith(".gc.ca")
                || d.endsWith(".canada.ca") || d.endsWith(".gov.uk") || d.endsWith(".gov.au")) {
            if (!endUserSearch) return true;
        }
        if (JUNK.contains(domain)) return true;
        for (String k : negativeKeywords) {
            if (StringUtils.hasText(k) && d.contains(k.toLowerCase().replace(" ", ""))) return true;
        }
        return false;
    }

    private String cleanCompanyName(String title, String domain) {
        String root = domain.split("\\.")[0];
        String pretty = Character.toUpperCase(root.charAt(0)) + root.substring(1);
        if (!StringUtils.hasText(title)) return pretty;
        String name = title.split("\\|")[0].split(" - ")[0].split(" – ")[0].split(" · ")[0].trim();
        if (name.isEmpty()) return pretty;
        String lower = name.toLowerCase();
        // 页面标题像产品页/文章页/导航页（型号数字、Guide/Best/Top/Home/Dealer 等词）时，改用域名作品牌名
        if (name.matches(".*\\d{3,}.*") || name.contains(":")
                || lower.contains("guide") || lower.contains("buyer") || lower.contains("buyers")
                || lower.startsWith("best ") || lower.startsWith("top ") || lower.startsWith("the ")
                || lower.contains("home") || lower.contains("dealer") || lower.contains("reseller")
                || lower.contains("review") || lower.contains("pricing") || lower.contains("price")
                || lower.contains("choosing") || lower.contains("how to") || lower.contains("what is")
                || lower.contains("find a ") || lower.contains("network") || lower.contains("supplier")
                || lower.equals("paging systems") || lower.equals("public address systems")
                || lower.contains("solutions") && name.length() > 30) {
            return pretty;
        }
        if (name.length() > 100) name = name.substring(0, 100);
        return name;
    }

    private static final Map<String, String> REGION_MAP = new LinkedHashMap<>();
    /** 美国三大区 → 搜索用州全称扩展（入库/展示保留区域名，仅检索时展开提升 Google 命中精度） */
    private static final Map<String, String> US_REGION_EXPANSION = new LinkedHashMap<>();
    static {
        for (String c : new String[]{"united states", "united states - east", "united states - south",
                "united states - west", "canada", "mexico"}) REGION_MAP.put(c, "North America");
        US_REGION_EXPANSION.put("united states - east",
                "United States East & Midwest region (New York, New Jersey, Massachusetts, Pennsylvania, Connecticut, Rhode Island, New Hampshire, Vermont, Maine, Delaware, Maryland, Washington DC, Illinois, Ohio, Michigan, Indiana, Wisconsin, Minnesota, Iowa, Missouri, North Dakota, South Dakota, Nebraska, Kansas)");
        US_REGION_EXPANSION.put("united states - south",
                "United States Southern region (Florida, Georgia, North Carolina, South Carolina, Alabama, Tennessee, Virginia, West Virginia, Kentucky, Mississippi, Louisiana, Arkansas, Oklahoma, Texas)");
        US_REGION_EXPANSION.put("united states - west",
                "United States Western region (California, Washington, Oregon, Nevada, Arizona, Colorado, New Mexico, Utah, Idaho, Montana, Wyoming, Alaska, Hawaii)");
        for (String c : new String[]{"brazil", "argentina", "chile", "colombia", "peru"}) REGION_MAP.put(c, "Latin America");
        for (String c : new String[]{"united kingdom", "uk", "germany", "france", "netherlands", "spain", "italy",
                "belgium", "sweden", "norway", "denmark", "finland", "poland", "switzerland", "austria",
                "portugal", "ireland", "czech republic", "romania", "greece"}) REGION_MAP.put(c, "Europe");
        for (String c : new String[]{"uae", "united arab emirates", "saudi arabia", "qatar", "kuwait", "israel",
                "turkey", "egypt", "south africa", "nigeria", "kenya"}) REGION_MAP.put(c, "Middle East & Africa");
        for (String c : new String[]{"australia", "new zealand"}) REGION_MAP.put(c, "Oceania");
        for (String c : new String[]{"japan", "south korea", "singapore", "india", "indonesia", "malaysia",
                "thailand", "vietnam", "philippines", "taiwan", "hong kong", "china"}) REGION_MAP.put(c, "Asia Pacific");
    }

    private String regionOf(String country) {
        if (country == null) return "";
        return REGION_MAP.getOrDefault(country.toLowerCase(), "");
    }

    /** 检索范围：美国三大区展开为州全称，其他国家原样返回 */
    private String searchScopeOf(String country) {
        if (country == null) return "";
        return US_REGION_EXPANSION.getOrDefault(country.toLowerCase().trim(), country);
    }

    /** Serper 检索使用短区域名，避免免费账户拒绝带长州名列表的 query pattern。 */
    private String simpleSearchScopeOf(String country) {
        if (country == null) return "";
        return switch (country.toLowerCase(Locale.ROOT).trim()) {
            case "united states - east" -> "Eastern United States";
            case "united states - south" -> "Southern United States";
            case "united states - west" -> "Western United States";
            default -> country;
        };
    }

    /**
     * Serper 免费账户不接受部分 Google 高级语法。保留有意义的词，移除引号、
     * 括号、高级操作符并限制长度，避免“Query pattern not allowed”。
     */
    static String normalizeSearchQuery(String query) {
        if (query == null) return "";
        String normalized = query
                .replaceAll("(?i)\\b(?:site|inurl|intitle|filetype|related|cache):\\S+", " ")
                .replaceAll("[\\\"“”'`()\\[\\]{}]", " ")
                .replaceAll("(?i)\\b(?:AND|OR)\\b", " ")
                .replaceAll("[|&]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.length() <= 180) return normalized;
        String shortened = normalized.substring(0, 180);
        int lastSpace = shortened.lastIndexOf(' ');
        return (lastSpace >= 120 ? shortened.substring(0, lastSpace) : shortened).trim();
    }

    /** 域名 TLD 兜底推断国家/区域，用于地址补全查询时强制约束（避免同名公司跨国家串号） */
    private static final Map<String, String> DOMAIN_COUNTRY = Map.ofEntries(
            Map.entry(".co.uk", "United Kingdom"), Map.entry(".uk", "United Kingdom"),
            Map.entry(".ca", "Canada"),
            Map.entry(".com.au", "Australia"), Map.entry(".net.au", "Australia"), Map.entry(".org.au", "Australia"),
            Map.entry(".co.nz", "New Zealand"), Map.entry(".nz", "New Zealand"),
            Map.entry(".de", "Germany"), Map.entry(".fr", "France"), Map.entry(".es", "Spain"),
            Map.entry(".it", "Italy"), Map.entry(".nl", "Netherlands"), Map.entry(".se", "Sweden"),
            Map.entry(".pl", "Poland"), Map.entry(".ie", "Ireland"), Map.entry(".be", "Belgium"),
            Map.entry(".jp", "Japan"), Map.entry(".kr", "South Korea"), Map.entry(".sg", "Singapore"),
            Map.entry(".in", "India"), Map.entry(".id", "Indonesia"), Map.entry(".my", "Malaysia"),
            Map.entry(".th", "Thailand"), Map.entry(".vn", "Vietnam"), Map.entry(".ph", "Philippines"),
            Map.entry(".ae", "UAE"), Map.entry(".sa", "Saudi Arabia"), Map.entry(".tr", "Turkey"),
            Map.entry(".il", "Israel"), Map.entry(".za", "South Africa"),
            Map.entry(".mx", "Mexico"), Map.entry(".br", "Brazil"),
            Map.entry(".com.cn", "China"), Map.entry(".cn", "China"), Map.entry(".hk", "Hong Kong")
    );
    private String countryHintFromDomain(String domain) {
        if (domain == null) return "";
        String d = domain.toLowerCase();
        for (var e : DOMAIN_COUNTRY.entrySet()) {
            if (d.endsWith(e.getKey())) return "\"" + e.getValue() + "\"";
        }
        return "";
    }

    /**
     * 校验抽取出的地址字段是否与公司国家一致：若发现冲突（UK 公司出现 US 州缩写或 US 5 位 zip 等），
     * 调用方应丢弃该组地址。任一字段为空视为未知，通过校验（保守）。
     */
    static final Set<String> US_STATES = Set.of("AL","AK","AZ","AR","CA","CO","CT","DE","FL","GA","HI","ID",
            "IL","IN","IA","KS","KY","LA","ME","MD","MA","MI","MN","MS","MO","MT","NE","NV","NH","NJ",
            "NM","NY","NC","ND","OH","OK","OR","PA","RI","SC","SD","TN","TX","UT","VT","VA","WA","WV","WI","WY","DC");
    static final Set<String> US_EAST_STATES = Set.of("NY","NJ","MA","PA","CT","RI","NH","VT","ME","DE","MD","DC",
            "IL","OH","MI","IN","WI","MN","IA","MO","ND","SD","NE","KS");
    static final Set<String> US_SOUTH_STATES = Set.of("FL","GA","NC","SC","AL","TN","VA","WV","KY","MS","LA","AR","OK","TX");
    static final Set<String> US_WEST_STATES = Set.of("CA","WA","OR","NV","AZ","CO","NM","UT","ID","MT","WY","AK","HI");
    private static final Map<String, String> US_STATE_NAMES = Map.ofEntries(
            Map.entry("alabama", "AL"), Map.entry("alaska", "AK"), Map.entry("arizona", "AZ"), Map.entry("arkansas", "AR"),
            Map.entry("california", "CA"), Map.entry("colorado", "CO"), Map.entry("connecticut", "CT"), Map.entry("delaware", "DE"),
            Map.entry("florida", "FL"), Map.entry("georgia", "GA"), Map.entry("hawaii", "HI"), Map.entry("idaho", "ID"),
            Map.entry("illinois", "IL"), Map.entry("indiana", "IN"), Map.entry("iowa", "IA"), Map.entry("kansas", "KS"),
            Map.entry("kentucky", "KY"), Map.entry("louisiana", "LA"), Map.entry("maine", "ME"), Map.entry("maryland", "MD"),
            Map.entry("massachusetts", "MA"), Map.entry("michigan", "MI"), Map.entry("minnesota", "MN"), Map.entry("mississippi", "MS"),
            Map.entry("missouri", "MO"), Map.entry("montana", "MT"), Map.entry("nebraska", "NE"), Map.entry("nevada", "NV"),
            Map.entry("new hampshire", "NH"), Map.entry("new jersey", "NJ"), Map.entry("new mexico", "NM"), Map.entry("new york", "NY"),
            Map.entry("north carolina", "NC"), Map.entry("north dakota", "ND"), Map.entry("ohio", "OH"), Map.entry("oklahoma", "OK"),
            Map.entry("oregon", "OR"), Map.entry("pennsylvania", "PA"), Map.entry("rhode island", "RI"), Map.entry("south carolina", "SC"),
            Map.entry("south dakota", "SD"), Map.entry("tennessee", "TN"), Map.entry("texas", "TX"), Map.entry("utah", "UT"),
            Map.entry("vermont", "VT"), Map.entry("virginia", "VA"), Map.entry("washington", "WA"), Map.entry("west virginia", "WV"),
            Map.entry("wisconsin", "WI"), Map.entry("wyoming", "WY"), Map.entry("district of columbia", "DC"));

    static boolean addressesMatchCountry(String addr, String city, String state, String zip, String country, String domain) {
        if (country == null && (domain == null || domain.isEmpty())) return true;
        boolean isUS = country != null && (country.toLowerCase().contains("united states") || country.equalsIgnoreCase("US") || country.equalsIgnoreCase("USA"));
        // 兜底：域名 TLD
        if ((country == null || country.isEmpty()) && domain != null) {
            String d = domain.toLowerCase();
            isUS = d.endsWith(".us");
        }
        String stateCode = normalizeUsState(state, String.join(" ", nullToEmpty(addr), nullToEmpty(city), nullToEmpty(state)));
        if (isUS) {
            if (stateCode == null || country == null) return true;
            String target = country.toLowerCase(Locale.ROOT).trim();
            if (target.endsWith("- west")) return US_WEST_STATES.contains(stateCode);
            if (target.endsWith("- south")) return US_SOUTH_STATES.contains(stateCode);
            if (target.endsWith("- east")) return US_EAST_STATES.contains(stateCode);
            return true;
        }
        // 非美国目标出现明确州名/州缩写时，判为跨国串号；单独 5 位邮编不能作为依据，许多国家也使用 5 位邮编。
        if (stateCode != null) return false;
        return true;
    }

    private static String normalizeUsState(String state, String evidence) {
        if (StringUtils.hasText(state)) {
            String code = state.trim().toUpperCase(Locale.ROOT);
            if (US_STATES.contains(code)) return code;
            String byName = US_STATE_NAMES.get(state.trim().toLowerCase(Locale.ROOT));
            if (byName != null) return byName;
        }
        String lower = nullToEmpty(evidence).toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : US_STATE_NAMES.entrySet()) {
            String name = Pattern.quote(entry.getKey());
            if (Pattern.compile("(?:,\\s*|\\b)" + name + "(?:,?\\s+\\d{5}\\b|\\s*,\\s*(?:united states|us)\\b)")
                    .matcher(lower).find()) return entry.getValue();
        }
        return null;
    }

    private static String inferUsStateFromDomain(String domain) {
        if (!StringUtils.hasText(domain)) return null;
        String d = domain.toLowerCase(Locale.ROOT);
        String[] labels = d.split("\\.");
        for (String label : labels) {
            String upper = label.toUpperCase(Locale.ROOT);
            if (label.length() == 2 && US_STATES.contains(upper)) return upper;
        }
        if (d.endsWith(".gov") && labels.length >= 2) {
            String root = labels[labels.length - 2];
            if (root.length() > 2) {
                String suffix = root.substring(root.length() - 2).toUpperCase(Locale.ROOT);
                if (US_STATES.contains(suffix)) return suffix;
            }
        }
        if (d.equals("washington.edu") || d.endsWith(".washington.edu")) return "WA";
        return null;
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    private String mergeSnippets(JSONObject root) {
        StringBuilder sb = new StringBuilder();
        JSONArray organic = root.getJSONArray("organic");
        if (organic != null) {
            for (int i = 0; i < organic.size() && i < 5; i++) {
                JSONObject o = organic.getJSONObject(i);
                if (o == null) continue;
                if (o.getString("snippet") != null) sb.append(o.getString("snippet")).append(" ");
            }
        }
        JSONObject kg = root.getJSONObject("knowledgeGraph");
        if (kg != null && kg.getString("description") != null) sb.append(kg.getString("description"));
        return sb.toString();
    }

    private String extractCity(String text) {
        // 粗提取：City, ST 或 City, State 模式
        if (text == null) return null;
        Matcher m = Pattern.compile("\\b([A-Z][a-zA-Z]+(?: [A-Z][a-zA-Z]+)?),\\s*([A-Z]{2})\\b").matcher(text);
        while (m.find()) {
            if (!US_STATES.contains(m.group(2))) continue;
            String candidate = m.group(1).trim();
            String lower = candidate.toLowerCase(Locale.ROOT);
            if (lower.matches(".*\\b(street|avenue|road|boulevard|drive|lane|court|way|building|department|services|response)$")) continue;
            return candidate;
        }
        return null;
    }

    private String extractState(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("\\b[A-Z][a-zA-Z]+(?: [A-Z][a-zA-Z]+)?,\\s*([A-Z]{2})\\b").matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private String extractZip(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("\\b\\d{5}(?:-\\d{4})?\\b").matcher(text);
        return m.find() ? m.group() : null;
    }

    private String extractAddress(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("\\b\\d{1,5}\\s+[A-Z][A-Za-z]+(?:\\s+[A-Z][A-Za-z]+){0,3}\\s+(?:St|Street|Ave|Avenue|Rd|Road|Blvd|Boulevard|Dr|Drive|Way|Court|Ln|Lane)\\b[.,]?").matcher(text);
        return m.find() ? m.group().trim() : null;
    }

    private String matchCompetitor(String text, List<String> competitors) {
        if (text == null) return null;
        for (String c : competitors) {
            if (StringUtils.hasText(c) && text.toLowerCase().contains(c.toLowerCase())) return c;
        }
        return null;
    }

    private List<String> toStringList(JSONArray arr, int limit) {
        List<String> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.size() && out.size() < limit; i++) {
            String v = arr.getString(i);
            if (StringUtils.hasText(v)) out.add(v.trim());
        }
        return out;
    }

    private String esc(String s) { return s == null ? "" : s.replace("\"", "'").replace("\n", " "); }

    private static List<String> normalizeRequestList(List<String> values, int maxItems, int maxLength) {
        if (values == null) return new ArrayList<>();
        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(v -> v.length() <= maxLength ? v : v.substring(0, maxLength))
                .distinct()
                .limit(maxItems)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ═══════════════ 进度落库 ═══════════════

    private void step(RunState rs, Long sessionId, int idx) {
        ensureTaskActive(rs);
        StepState st = rs.steps.get(idx - 1);
        st.setStatus("running");
        st.setTime(LocalDateTime.now().withNano(0).toString());
        persistProgress(rs, sessionId, idx);
    }

    private void detail(RunState rs, Long sessionId, int idx, String detail) {
        ensureTaskActive(rs);
        StepState st = rs.steps.get(idx - 1);
        st.setDetail(detail == null ? "" : detail);
        persistProgress(rs, sessionId, idx);
    }

    private void done(RunState rs, Long sessionId, int idx) {
        ensureTaskActive(rs);
        StepState st = rs.steps.get(idx - 1);
        if ("running".equals(st.getStatus())) st.setStatus("done");
        persistProgress(rs, sessionId, idx);
    }

    private void failCurrent(RunState rs, Long sessionId, String msg) {
        rs.steps.stream().filter(s -> "running".equals(s.getStatus())).findFirst().ifPresent(s -> {
            s.setStatus("failed");
            s.setDetail(truncate(msg, 200));
        });
    }

    private void persistProgress(RunState rs, Long sessionId, int currentStep) {
        int progress = (int) Math.round(currentStep * 100.0 / STEP_KEYS.length);
        updateSession(sessionId, s -> {
            s.setCurrentStep(currentStep);
            s.setProgress(Math.min(99, progress));
            s.setStepLogs(JSON.toJSONString(rs.steps));
            s.setStatsJson(JSON.toJSONString(rs.stats));
        });
    }

    private void finish(RunState rs, Long sessionId, String status, String errMsg) {
        ensureTaskActive(rs);
        updateSession(sessionId, s -> {
            s.setStatus(status);
            s.setProgress(100);
            s.setStepLogs(JSON.toJSONString(rs.steps));
            s.setStatsJson(JSON.toJSONString(rs.stats));
            if (errMsg != null) s.setErrorMsg(truncate(errMsg, 900));
        });
    }

    private void ensureTaskActive(RunState rs) {
        if (rs == null || !"running".equals(rs.status) || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("任务已终止");
        }
    }

    private void persistIcp(Long sessionId, IcpResult icp) {
        updateSession(sessionId, s -> s.setIcpSummary(icp.summary));
    }

    private void updateSession(Long sessionId, java.util.function.Consumer<LeadHuntSession> updater) {
        try {
            LeadHuntSession s = sessionMapper.selectById(sessionId);
            if (s == null) return;
            updater.accept(s);
            // strictUpdateFill 不会覆盖从 DB 读出的非空时间；显式刷新作为看门狗心跳。
            s.setUpdateTime(LocalDateTime.now());
            sessionMapper.updateById(s);
        } catch (Exception e) {
            log.warn("[LeadHunter] persist session {} failed: {}", sessionId, e.getMessage());
        }
    }
}
