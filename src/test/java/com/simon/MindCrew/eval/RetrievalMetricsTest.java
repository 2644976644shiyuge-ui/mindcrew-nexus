package com.simon.MindCrew.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 召回指标的纯逻辑单测（无 Spring 上下文，随 {@code mvn test} 在 CI 运行）。
 * 保证调参时依赖的「打分尺子」本身是对的。
 */
class RetrievalMetricsTest {

    // 排名：[命中 d1] [未命中] [命中 d2]
    private static final List<Set<String>> RANKS = List.of(
            Set.of("d1"), Set.of(), Set.of("d2"));

    @Test
    void hitAtK_respectsCutoff() {
        assertTrue(RetrievalMetrics.hitAtK(RANKS, 1));      // 第1名即命中
        // 期望项只在第3名时，K=2 取不到
        List<Set<String>> late = List.of(Set.of(), Set.of(), Set.of("d1"));
        assertFalse(RetrievalMetrics.hitAtK(late, 2));
        assertTrue(RetrievalMetrics.hitAtK(late, 3));
    }

    @Test
    void recallAtK_countsDistinctExpected() {
        // 2 个期望项，前 3 名命中 d1、d2 → 1.0
        assertEquals(1.0, RetrievalMetrics.recallAtK(RANKS, 2, 3), 1e-9);
        // 前 2 名只命中 d1 → 0.5
        assertEquals(0.5, RetrievalMetrics.recallAtK(RANKS, 2, 2), 1e-9);
        // 期望数为 0 → 0，不抛异常
        assertEquals(0.0, RetrievalMetrics.recallAtK(RANKS, 0, 3), 1e-9);
    }

    @Test
    void reciprocalRank_isInverseOfFirstHitPosition() {
        assertEquals(1.0, RetrievalMetrics.reciprocalRank(RANKS), 1e-9);
        // 首个命中在第3名 → 1/3
        List<Set<String>> late = List.of(Set.of(), Set.of(), Set.of("d1"));
        assertEquals(1.0 / 3, RetrievalMetrics.reciprocalRank(late), 1e-9);
        // 全未命中 → 0
        assertEquals(0.0, RetrievalMetrics.reciprocalRank(List.of(Set.of(), Set.of())), 1e-9);
    }
}
