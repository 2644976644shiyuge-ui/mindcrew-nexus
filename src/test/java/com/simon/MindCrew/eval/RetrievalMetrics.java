package com.simon.MindCrew.eval;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 召回评测指标（纯函数，无外部依赖，可在 CI 单测中验证）。
 *
 * <p>输入抽象为「每个排名位命中了哪些期望项」：
 * {@code perRank.get(i)} = 检索结果第 i 名（0-based）匹配到的期望项集合（空集=该位未命中任何期望）。
 * 由调用方负责把检索到的切片映射成「匹配了哪些期望项」（支持文档 id 精确匹配或文件名包含匹配）。
 *
 * <p>这样指标计算与「如何判定一条切片算命中」解耦，便于调参时只换检索、不动指标。
 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {}

    /** Hit@K：前 K 名里是否至少命中一个期望项。 */
    public static boolean hitAtK(List<Set<String>> perRank, int k) {
        return perRank.stream().limit(k).anyMatch(s -> s != null && !s.isEmpty());
    }

    /** Recall@K：前 K 名命中的「不同期望项」数 / 期望项总数。 */
    public static double recallAtK(List<Set<String>> perRank, int expectedCount, int k) {
        if (expectedCount <= 0) return 0.0;
        Set<String> hit = new HashSet<>();
        perRank.stream().limit(k).forEach(s -> { if (s != null) hit.addAll(s); });
        return (double) Math.min(hit.size(), expectedCount) / expectedCount;
    }

    /** Reciprocal Rank：首个命中期望项的排名倒数（1/rank），全程未命中为 0。MRR = 多条用例 RR 的均值。 */
    public static double reciprocalRank(List<Set<String>> perRank) {
        for (int i = 0; i < perRank.size(); i++) {
            Set<String> s = perRank.get(i);
            if (s != null && !s.isEmpty()) return 1.0 / (i + 1);
        }
        return 0.0;
    }
}
