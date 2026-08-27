package com.simon.MindCrew.eval;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.simon.MindCrew.service.rag.RetrievedChunk;
import com.simon.MindCrew.service.rag.CrossEncoderReranker;
import com.simon.MindCrew.service.rag.HybridRecallService;
import com.simon.MindCrew.service.rag.QueryRewriter;
import com.simon.MindCrew.service.rag.RRFFusion;
import com.simon.MindCrew.entity.KbKnowledgeBase;
import com.simon.MindCrew.mapper.KbKnowledgeBaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 召回回归评测（opt-in）。
 *
 * <p>用一组「问题 → 期望命中文档」的回归集，量化检索质量。调 rerank/分块/embedding 参数后重跑，
 * 看 Hit@K / Recall@K / MRR 是否真的变好，而不是凭感觉。
 *
 * <p><b>为什么默认不跑：</b>它依赖真实数据库 + 向量库 + 已入库的知识库，普通 {@code mvn test} 环境不具备，
 * 因此用环境变量 {@code RUN_RETRIEVAL_EVAL=true} 显式开启。
 *
 * <pre>
 *   # 1. 编辑回归集：src/test/resources/eval/retrieval-eval.json
 *   # 2. 指向已入库数据的环境跑：
 *   RUN_RETRIEVAL_EVAL=true mvn -Dtest=RetrievalRecallEvalTest test
 *   # 3. （可选）设质量下限，低于则用例失败，可纳入回归门禁：
 *   RUN_RETRIEVAL_EVAL=true RETRIEVAL_EVAL_MIN_HITRATE=0.8 mvn -Dtest=RetrievalRecallEvalTest test
 * </pre>
 *
 * <p>v2 真值项可组合限定文档 id、文件名片段与正文片段；还可用 {@code exclude}
 * 声明 Top-K 中绝不能出现的越权或过时来源。旧版字符串真值仍保持兼容。
 */
@Slf4j
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_RETRIEVAL_EVAL", matches = "true")
class RetrievalRecallEvalTest {

    private static final String EVAL_FILE = "eval/retrieval-eval.json";
    private static final int DEFAULT_TOP_K = 10;

    @Autowired
    private HybridRecallService hybridRecallService;
    @Autowired
    private QueryRewriter queryRewriter;
    @Autowired
    private RRFFusion rrfFusion;
    @Autowired
    private CrossEncoderReranker reranker;
    @Autowired
    private KbKnowledgeBaseMapper kbMapper;

    @Test
    void evaluateRecall() throws Exception {
        List<Case> cases = loadCases();
        assertTrue(!cases.isEmpty(),
                "没有启用的真实评测用例：请复制 " + EVAL_FILE + " 中的模板、替换真值并设置 enabled=true");

        double sumRecall = 0, sumRr = 0;
        int hitCount = 0;

        log.info("================= 召回评测开始 · {} 条用例 =================", cases.size());
        log.info(String.format("%-40s %6s %8s %6s", "question", "Hit@K", "Recall@K", "RR"));

        for (Case c : cases) {
            int k = c.topK > 0 ? c.topK : DEFAULT_TOP_K;
            List<Long> scope = c.kbIds != null ? c.kbIds : kbMapper.selectList(
                            new LambdaQueryWrapper<KbKnowledgeBase>().select(KbKnowledgeBase::getId))
                    .stream().map(KbKnowledgeBase::getId).toList();
            QueryRewriter.QueryPlan queryPlan = queryRewriter.plan(c.question, c.history);
            HybridRecallService.RecallResult recallResult = hybridRecallService.recall(
                    c.question, queryPlan.searchQueries(), scope, Math.max(20, k), Math.max(20, k));
            List<RetrievedChunk> fused = rrfFusion.fuse(
                    recallResult.vectorResults(), recallResult.bm25Results(), Math.max(30, k));
            List<RetrievedChunk> chunks = reranker.rerank(queryPlan.standaloneQuery(), fused, k);
            enrichSourceNames(chunks);

            // 每个排名位匹配到的期望项集合
            List<Set<String>> perRank = new ArrayList<>(chunks.size());
            for (RetrievedChunk ch : chunks) {
                perRank.add(matchedExpectations(ch, c.expect));
            }

            Set<String> excludedHits = new LinkedHashSet<>();
            for (RetrievedChunk ch : chunks) {
                excludedHits.addAll(matchedExpectations(ch, c.exclude));
            }
            assertTrue(excludedHits.isEmpty(), () -> String.format(
                    "评测用例 %s 召回了明确排除的来源: %s", c.displayName(), excludedHits));

            boolean hit = RetrievalMetrics.hitAtK(perRank, k);
            double recall = RetrievalMetrics.recallAtK(perRank, c.expect.size(), k);
            double rr = RetrievalMetrics.reciprocalRank(perRank);

            if (hit) hitCount++;
            sumRecall += recall;
            sumRr += rr;

            log.info(String.format("%-40s %6s %8.2f %6.2f",
                    truncate(c.displayName(), 38), hit ? "✓" : "✗", recall, rr));
        }

        int n = cases.size();
        double hitRate = (double) hitCount / n;
        double meanRecall = sumRecall / n;
        double mrr = sumRr / n;
        log.info("----------------------------------------------------------");
        log.info(String.format("汇总 · HitRate@K=%.3f  meanRecall@K=%.3f  MRR=%.3f  (n=%d)",
                hitRate, meanRecall, mrr, n));
        log.info("==========================================================");

        // 真实评测一旦开启就必须有质量门禁，避免只打印漂亮日志却让精度回归悄悄通过。
        double minHitRate = envDouble("RETRIEVAL_EVAL_MIN_HITRATE", 0.80d);
        double minRecall = envDouble("RETRIEVAL_EVAL_MIN_RECALL", 0.75d);
        double minMrr = envDouble("RETRIEVAL_EVAL_MIN_MRR", 0.65d);
        assertTrue(hitRate >= minHitRate,
                String.format("HitRate@K=%.3f 低于下限 %.3f", hitRate, minHitRate));
        assertTrue(meanRecall >= minRecall,
                String.format("meanRecall@K=%.3f 低于下限 %.3f", meanRecall, minRecall));
        assertTrue(mrr >= minMrr,
                String.format("MRR=%.3f 低于下限 %.3f", mrr, minMrr));
    }

    private void enrichSourceNames(List<RetrievedChunk> chunks) {
        for (RetrievedChunk chunk : chunks) {
            if (chunk.getSourceName() != null || chunk.getKnowledgeBaseId() == null) continue;
            KbKnowledgeBase kb = kbMapper.selectById(chunk.getKnowledgeBaseId());
            if (kb != null) chunk.setSourceName(kb.getName());
        }
    }

    /** 该切片命中了哪些期望项（兼容旧字符串，并支持文档、文件名、正文的组合条件）。 */
    private Set<String> matchedExpectations(RetrievedChunk ch, List<Expectation> expects) {
        Set<String> matched = new LinkedHashSet<>();
        for (Expectation expectation : expects) {
            if (expectation.matches(ch)) matched.add(expectation.key());
        }
        return matched;
    }

    private List<Case> loadCases() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(EVAL_FILE)) {
            assertNotNull(in, "找不到回归集文件：" + EVAL_FILE);
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Object root = JSON.parse(json);
            JSONObject defaults = root instanceof JSONObject object
                    ? object.getJSONObject("defaults") : null;
            JSONArray arr = root instanceof JSONArray array
                    ? array
                    : root instanceof JSONObject object ? object.getJSONArray("cases") : null;
            assertNotNull(arr, "回归集格式错误：顶层应为数组，或包含 cases 数组的对象");
            List<Case> out = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o == null || (o.containsKey("enabled") && !o.getBooleanValue("enabled"))) continue;
                Case c = new Case();
                c.id = o.getString("id");
                c.question = o.getString("question");
                c.history = o.getString("history");
                int defaultTopK = defaults == null ? 0 : defaults.getIntValue("topK", 0);
                c.topK = o.containsKey("topK") ? o.getIntValue("topK", 0) : defaultTopK;
                c.expect = parseExpectations(o.getJSONArray("expect"), "expect");
                c.exclude = parseExpectations(o.getJSONArray("exclude"), "exclude");
                JSONArray ka = o.getJSONArray("kbIds");
                if (ka != null && !ka.isEmpty()) {
                    List<Long> kb = new ArrayList<>();
                    for (int j = 0; j < ka.size(); j++) kb.add(ka.getLong(j));
                    c.kbIds = kb;
                }
                if (c.question != null && !c.question.isBlank() && !c.expect.isEmpty()) {
                    out.add(c);
                }
            }
            return out;
        }
    }

    private List<Expectation> parseExpectations(JSONArray values, String prefix) {
        if (values == null || values.isEmpty()) return List.of();
        List<Expectation> out = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            Object raw = values.get(i);
            if (raw instanceof String legacy && !legacy.isBlank()) {
                out.add(Expectation.legacy(legacy.trim()));
                continue;
            }
            if (!(raw instanceof JSONObject object)) continue;
            Expectation expectation = new Expectation();
            expectation.id = object.getString("id");
            expectation.knowledgeBaseId = object.getLong("knowledgeBaseId");
            expectation.sourceNameContains = object.getString("sourceNameContains");
            expectation.contentContains = object.getString("contentContains");
            if (expectation.hasMatcher()) {
                if (expectation.id == null || expectation.id.isBlank()) {
                    expectation.id = prefix + "-" + (i + 1);
                }
                out.add(expectation);
            }
        }
        return out;
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private static double envDouble(String name, double defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : Double.parseDouble(value.trim());
    }

    /** 单条评测用例 */
    private static class Case {
        String id;
        String question;
        String history;
        List<Long> kbIds;          // null = 全库
        int topK;                  // 0 = 用默认
        List<Expectation> expect = List.of();
        List<Expectation> exclude = List.of();

        String displayName() {
            return id == null || id.isBlank() ? question : id + " · " + question;
        }
    }

    /**
     * 结构化真值项：设置的字段采用 AND 关系，可精确到“某文档中的某段正文”。
     * legacyValue 保留旧版字符串的“文档 ID 精确匹配或文件名包含匹配”语义。
     */
    private static class Expectation {
        String id;
        Long knowledgeBaseId;
        String sourceNameContains;
        String contentContains;
        String legacyValue;

        static Expectation legacy(String value) {
            Expectation expectation = new Expectation();
            expectation.id = value;
            expectation.legacyValue = value;
            return expectation;
        }

        String key() {
            return id;
        }

        boolean hasMatcher() {
            return knowledgeBaseId != null || notBlank(sourceNameContains) || notBlank(contentContains);
        }

        boolean matches(RetrievedChunk chunk) {
            if (legacyValue != null) {
                return legacyValue.equals(String.valueOf(chunk.getKnowledgeBaseId()))
                        || contains(chunk.getSourceName(), legacyValue);
            }
            return (knowledgeBaseId == null || knowledgeBaseId.equals(chunk.getKnowledgeBaseId()))
                    && (!notBlank(sourceNameContains) || contains(chunk.getSourceName(), sourceNameContains))
                    && (!notBlank(contentContains) || contains(chunk.getContent(), contentContains));
        }

        private static boolean contains(String text, String part) {
            return text != null && part != null && text.contains(part);
        }

        private static boolean notBlank(String value) {
            return value != null && !value.isBlank();
        }
    }
}
