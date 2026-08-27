package com.simon.MindCrew.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步线程池配置 · 性能优化
 *
 * 文档处理（ASR / 视频理解 / PDF OCR / embedding）是重活、耗时长、且会打外部 API（DashScope 有限流），
 * 高并发还会同时渲染大图导致内存峰值过高（OOM 主因）。这里给文档处理单独配一个有界池：
 *   - 并发硬上限（core=max）：同一时刻最多处理 N 个文档，其余在队列里等，处理完一个再上一个；
 *   - 队列封顶：大批量导入时任务排队等待，不在内存无限堆积；
 *   - 满了用 CallerRuns 降级（不丢任务、自带背压）。
 *
 * 调优（环境变量，无需改代码）：
 *   DOC_PROCESS_CONCURRENCY    一次并发处理几个文档（内存紧张设 1；内存宽裕可调大）。默认 2。
 *   DOC_PROCESS_QUEUE_CAPACITY 等待队列容量（大批量导入可调大）。默认 5000。
 */
@Configuration
public class AsyncConfig {

    /** 同一时刻最多并发处理的文档数（core=max → 硬上限，不受队列长度影响） */
    @Value("${doc.process.concurrency:2}")
    private int docConcurrency;

    /** 处理等待队列容量：超出并发数的文档先排队，处理完一个再上一个 */
    @Value("${doc.process.queue-capacity:5000}")
    private int docQueueCapacity;

    @Value("${ppt.generation.concurrency:2}")
    private int pptGenerationConcurrency;

    @Value("${ppt.generation.queue-capacity:100}")
    private int pptGenerationQueueCapacity;

    /** 全球获客任务并发数；外部 API 调用为主，默认允许 4 个用户任务并行。 */
    @Value("${mindcrew.lead-hunter.task-concurrency:4}")
    private int leadHuntConcurrency;

    /** 全球获客等待队列容量；队列满后立即返回“系统繁忙”，不制造无限排队。 */
    @Value("${mindcrew.lead-hunter.task-queue-capacity:20}")
    private int leadHuntQueueCapacity;

    /** 获客 LLM 调用专用有界池，避免超时调用占满 JVM 公共线程池。 */
    @Value("${mindcrew.lead-hunter.llm-concurrency:4}")
    private int leadHuntLlmConcurrency;

    @Value("${mindcrew.lead-hunter.llm-queue-capacity:16}")
    private int leadHuntLlmQueueCapacity;

    /** 智能问答与数字员工共用的有界执行池，避免慢模型请求无限创建线程。 */
    @Value("${mindcrew.qa.concurrency:12}")
    private int qaConcurrency;

    @Value("${mindcrew.qa.queue-capacity:24}")
    private int qaQueueCapacity;

    /** 向量召回单独隔离；向量库/embedding 变慢时不应拖死本地 BM25。 */
    @Value("${mindcrew.rag.vector-recall-concurrency:12}")
    private int vectorRecallConcurrency;

    @Value("${mindcrew.rag.vector-recall-queue-capacity:96}")
    private int vectorRecallQueueCapacity;

    @Value("${mindcrew.rag.bm25-recall-concurrency:6}")
    private int bm25RecallConcurrency;

    @Value("${mindcrew.rag.bm25-recall-queue-capacity:64}")
    private int bm25RecallQueueCapacity;

    /** 文档处理专用线程池 · 用法：@Async("docProcessExecutor") */
    @Bean("docProcessExecutor")
    public Executor docProcessExecutor() {
        int concurrency = Math.max(1, docConcurrency);
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(concurrency);
        ex.setMaxPoolSize(concurrency);                 // 与 core 相等 → 并发硬上限为 concurrency
        ex.setQueueCapacity(Math.max(1, docQueueCapacity));
        ex.setThreadNamePrefix("doc-process-");
        // 队列满 → 在调用线程执行（背压），不丢任务、也不让队列无限涨
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(120);
        ex.initialize();
        return ex;
    }

    @Bean("qaExecutor")
    public ThreadPoolTaskExecutor qaExecutor() {
        int concurrency = Math.max(2, qaConcurrency);
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(concurrency);
        ex.setMaxPoolSize(concurrency);
        ex.setQueueCapacity(Math.max(1, qaQueueCapacity));
        ex.setThreadNamePrefix("qa-request-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(false);
        ex.setAwaitTerminationSeconds(20);
        ex.initialize();
        return ex;
    }

    /**
     * 向量/BM25 多查询召回专用有界池。队列满时让当前检索通道快速降级为空结果，
     * 而不是继续堆积已经失去时效的检索任务。
     */
    @Bean("vectorRecallExecutor")
    public ThreadPoolTaskExecutor vectorRecallExecutor() {
        return recallExecutor("rag-vector-", vectorRecallConcurrency, vectorRecallQueueCapacity);
    }

    @Bean("bm25RecallExecutor")
    public ThreadPoolTaskExecutor bm25RecallExecutor() {
        return recallExecutor("rag-bm25-", bm25RecallConcurrency, bm25RecallQueueCapacity);
    }

    private ThreadPoolTaskExecutor recallExecutor(String prefix, int requestedConcurrency, int queueCapacity) {
        int concurrency = Math.max(2, requestedConcurrency);
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(concurrency);
        ex.setMaxPoolSize(concurrency);
        ex.setQueueCapacity(Math.max(1, queueCapacity));
        ex.setThreadNamePrefix(prefix);
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(false);
        ex.setAwaitTerminationSeconds(10);
        ex.initialize();
        return ex;
    }

    /**
     * 全球获客（Lead Hunter）后台执行线程池 · 用法：@Async("leadHuntExecutor")
     * 一次猎单任务持续数分钟且重度调用外部 API（Serper/Hunter），独立线程池避免占用文档处理池。
     */
    @Bean("leadHuntExecutor")
    public ThreadPoolTaskExecutor leadHuntExecutor() {
        int concurrency = Math.max(1, leadHuntConcurrency);
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(concurrency);
        ex.setMaxPoolSize(concurrency);
        ex.setQueueCapacity(Math.max(1, leadHuntQueueCapacity));
        ex.setThreadNamePrefix("lead-hunt-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(false);
        ex.setAwaitTerminationSeconds(30);
        ex.initialize();
        return ex;
    }

    /**
     * 全球获客 LLM 调用专用线程池。底层 HTTP 即使不响应，也只会占用这个有界池，
     * 不会拖垮 CompletableFuture 公共池和其他业务。
     */
    @Bean("leadHuntLlmExecutor")
    public Executor leadHuntLlmExecutor() {
        int concurrency = Math.max(1, leadHuntLlmConcurrency);
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(concurrency);
        ex.setMaxPoolSize(concurrency);
        ex.setQueueCapacity(Math.max(1, leadHuntLlmQueueCapacity));
        ex.setThreadNamePrefix("lead-hunt-llm-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(false);
        ex.initialize();
        return ex;
    }

    /**
     * PPT 后台生成专用线程池。任务先持久化再入队，浏览器断开或切换页面不会中止。
     * 队列满时拒绝新调度，调用方会把任务标记失败，避免 HTTP 请求被拖回同步执行。
     */
    @Bean("pptGenerationExecutor")
    public Executor pptGenerationExecutor() {
        int concurrency = Math.max(1, pptGenerationConcurrency);
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(concurrency);
        ex.setMaxPoolSize(concurrency);
        ex.setQueueCapacity(Math.max(1, pptGenerationQueueCapacity));
        ex.setThreadNamePrefix("ppt-generation-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(false);
        ex.initialize();
        return ex;
    }
}
