package com.simon.MindCrew.mcp.search;

import com.simon.MindCrew.service.rag.RetrievedChunk;

import java.util.List;

/**
 * 联网搜索 Provider 抽象 —— 让底层搜索源（Tavily / 百度千帆 AI 搜索 / …）可插拔、可切换。
 *
 * <p>由 {@code mindcrew.web-search.provider} 配置项选择具体实现；
 * {@link com.simon.MindCrew.mcp.WebSearchTool} 持有全部 Provider 并按配置分发。
 */
public interface WebSearchProvider {

    /**
     * Provider 唯一标识，与配置项 {@code mindcrew.web-search.provider} 的取值对应（如 "tavily" / "baidu"）。
     */
    String name();

    /**
     * 是否已正确配置（如 apiKey 已注入）。未配置时上层会跳过本次联网，返回空。
     */
    boolean isConfigured();

    /**
     * 执行联网检索。
     *
     * @param query      检索词
     * @param maxResults 最多返回结果数
     * @return 检索结果（source 标注为 WEB）；失败 / 未配置时返回空列表，绝不抛出
     */
    List<RetrievedChunk> search(String query, int maxResults);
}
