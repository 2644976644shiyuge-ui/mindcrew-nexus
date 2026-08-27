package com.simon.MindCrew.service.rag;

import com.simon.MindCrew.config.AiConfigHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * RAG 链路第3步：RRF（Reciprocal Rank Fusion）融合
 * 将向量检索和 BM25 检索的两路结果融合去重
 *
 * RRF 公式：score(d) = Σ 1 / (k + rank(d))
 * k 值由 AI 配置中心动态控制（默认 60）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RRFFusion {

    private final AiConfigHolder aiConfigHolder;

    /**
     * 融合两路检索结果
     * @param vectorResults 向量检索结果（按相关性降序）
     * @param bm25Results   BM25 检索结果（按相关性降序）
     * @param topN          融合后保留的最大数量
     */
    public List<RetrievedChunk> fuse(List<RetrievedChunk> vectorResults,
                                      List<RetrievedChunk> bm25Results,
                                      int topN) {
        // Milvus 与 MySQL 的 chunk id 不同，不能只按 id；但只看内容前 50 字又会把
        // 不同文档中相同页眉/模板开头误判成同一片。使用“KB + 完整规范化内容”稳定对齐两路。
        Map<String, RetrievedChunk> chunkMap = new LinkedHashMap<>();
        Map<String, Double> rrfScores = new HashMap<>();

        // 计算向量检索路的 RRF 贡献
        for (int rank = 0; rank < vectorResults.size(); rank++) {
            RetrievedChunk chunk = vectorResults.get(rank);
            if (chunk == null || chunk.getContent() == null || chunk.getContent().isBlank()) continue;
            String key = chunkKey(chunk);
            double rrfScore = 1.0 / (aiConfigHolder.getInt("rag.rrf_k_constant") + rank + 1);

            chunkMap.putIfAbsent(key, chunk);
            rrfScores.merge(key, rrfScore, Double::sum);
        }

        // 计算 BM25 路的 RRF 贡献
        for (int rank = 0; rank < bm25Results.size(); rank++) {
            RetrievedChunk chunk = bm25Results.get(rank);
            if (chunk == null || chunk.getContent() == null || chunk.getContent().isBlank()) continue;
            String key = chunkKey(chunk);
            double rrfScore = 1.0 / (aiConfigHolder.getInt("rag.rrf_k_constant") + rank + 1);

            if (!chunkMap.containsKey(key)) {
                chunkMap.put(key, chunk);
            } else {
                // 标记为混合来源
                chunkMap.get(key).setSource(RetrievedChunk.Source.HYBRID);
            }
            rrfScores.merge(key, rrfScore, Double::sum);
        }

        // 按 RRF 分数降序排序
        List<Map.Entry<String, Double>> sortedEntries = new ArrayList<>(rrfScores.entrySet());
        sortedEntries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        // 构建最终结果
        List<RetrievedChunk> fused = new ArrayList<>();
        for (int i = 0; i < Math.min(sortedEntries.size(), topN); i++) {
            String key = sortedEntries.get(i).getKey();
            RetrievedChunk chunk = chunkMap.get(key);
            chunk.setScore((float) (double) sortedEntries.get(i).getValue());
            chunk.setRrfRank(i + 1);
            fused.add(chunk);
        }

        log.info("RRF融合: 向量路={}, BM25路={}, 融合后={}",
                vectorResults.size(), bm25Results.size(), fused.size());
        return fused;
    }

    /**
     * 融合任意多路检索结果（GraphRAG 第三路开启时用）。
     * 与两路 fuse 同一套 RRF 口径；null 路自动跳过。原两路 fuse 保持不变，
     * 保证图谱开关关闭时检索行为与现状完全一致。
     */
    public List<RetrievedChunk> fuseMany(List<List<RetrievedChunk>> channels, int topN) {
        Map<String, RetrievedChunk> chunkMap = new LinkedHashMap<>();
        Map<String, Double> rrfScores = new HashMap<>();
        int k = aiConfigHolder.getInt("rag.rrf_k_constant");

        int channelCount = 0;
        for (List<RetrievedChunk> channel : channels) {
            if (channel == null || channel.isEmpty()) continue;
            channelCount++;
            for (int rank = 0; rank < channel.size(); rank++) {
                RetrievedChunk chunk = channel.get(rank);
                if (chunk.getContent() == null) continue;
                String key = chunkKey(chunk);
                double rrfScore = 1.0 / (k + rank + 1);
                if (chunkMap.putIfAbsent(key, chunk) != null) {
                    // 多路命中同一片 → 标记混合来源
                    chunkMap.get(key).setSource(RetrievedChunk.Source.HYBRID);
                }
                rrfScores.merge(key, rrfScore, Double::sum);
            }
        }

        List<Map.Entry<String, Double>> sortedEntries = new ArrayList<>(rrfScores.entrySet());
        sortedEntries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        List<RetrievedChunk> fused = new ArrayList<>();
        for (int i = 0; i < Math.min(sortedEntries.size(), topN); i++) {
            String key = sortedEntries.get(i).getKey();
            RetrievedChunk chunk = chunkMap.get(key);
            chunk.setScore((float) (double) sortedEntries.get(i).getValue());
            chunk.setRrfRank(i + 1);
            fused.add(chunk);
        }

        log.info("RRF多路融合: {} 路 → 融合后={}", channelCount, fused.size());
        return fused;
    }

    static String chunkKey(RetrievedChunk chunk) {
        String content = chunk.getContent() == null ? "" : chunk.getContent()
                .replaceAll("\\s+", " ")
                .trim();
        return String.valueOf(chunk.getKnowledgeBaseId()) + "|" + content;
    }
}
