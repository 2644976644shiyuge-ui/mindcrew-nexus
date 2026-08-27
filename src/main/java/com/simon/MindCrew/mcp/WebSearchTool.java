package com.simon.MindCrew.mcp;

import com.simon.MindCrew.agent.AgentToolContext;
import com.simon.MindCrew.config.DocmindWebSearchProperties;
import com.simon.MindCrew.mcp.search.WebSearchProvider;
import com.simon.MindCrew.service.rag.RetrievedChunk;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Tool：互联网实时检索。
 *
 * <p>底层搜索源通过 {@link WebSearchProvider} 抽象，由 {@code mindcrew.web-search.provider}
 * 选择（tavily / baidu）。本类只负责：联网开关校验 + 选取 Provider + 回写召回上下文。
 */
@Slf4j
@Component
public class WebSearchTool {

    /** 工具注册名 */
    public static final String TOOL_NAME = "web_search";

    private final DocmindWebSearchProperties properties;
    /** name -> provider */
    private final Map<String, WebSearchProvider> providers = new LinkedHashMap<>();

    public WebSearchTool(DocmindWebSearchProperties properties, List<WebSearchProvider> providerBeans) {
        this.properties = properties;
        for (WebSearchProvider p : providerBeans) {
            providers.put(p.name().toLowerCase(), p);
        }
    }

    @PostConstruct
    void logConfig() {
        log.info("[WebSearchTool] 已注册搜索源: {} · 当前启用: {}", providers.keySet(), properties.getProvider());
    }

    /**
     * 互联网检索
     *
     * @param query      检索关键词
     * @param maxResults 最多返回结果数
     * @return 检索结果（来源标注为 WEB）
     */
    @Tool(description = "互联网实时检索：联网搜索获取最新网页标题、链接和摘要，适用于新闻、政策、时效性信息查询")
    public List<RetrievedChunk> webSearch(String query, int maxResults) {
        // 用户在对话框关闭了「联网」开关 → 本轮禁止联网，即使 LLM 想调也返回空
        if (AgentToolContext.isActive() && !AgentToolContext.get().isWebSearchAllowed()) {
            log.info("[WebSearchTool] 本轮联网已被用户关闭, skip query='{}'", query);
            return List.of();
        }
        if (!properties.isEnabled()) {
            log.info("[WebSearchTool] disabled, skip query='{}'", query);
            return List.of();
        }
        if (!StringUtils.hasText(query) || maxResults <= 0) {
            return List.of();
        }

        WebSearchProvider provider = resolveProvider();
        if (provider == null) {
            log.warn("[WebSearchTool] 无可用搜索源（provider={} 未配置，也无其它已配置源）, skip query='{}'",
                    properties.getProvider(), query);
            return List.of();
        }

        List<RetrievedChunk> results = provider.search(query, maxResults);

        if (AgentToolContext.isActive()) {
            AgentToolContext.get().addChunks(TOOL_NAME, results);
        }
        return results;
    }

    /**
     * 选搜索源：优先用配置指定且已配置好 key 的；
     * 若指定源未配置 key（如刚切到 baidu 但 key 没填）或配置项写错 → 回退到任一已配置好的源，避免联网直接哑火。
     */
    private WebSearchProvider resolveProvider() {
        String want = properties.getProvider() == null ? "" : properties.getProvider().trim().toLowerCase();
        WebSearchProvider wanted = providers.get(want);
        if (wanted != null && wanted.isConfigured()) {
            return wanted;
        }
        WebSearchProvider fallback = providers.values().stream()
                .filter(WebSearchProvider::isConfigured).findFirst().orElse(null);
        if (fallback != null && fallback != wanted) {
            log.warn("[WebSearchTool] 搜索源 {} 未就绪，回退到已配置的 {}", want, fallback.name());
        }
        return fallback;
    }
}
