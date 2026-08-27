package com.simon.MindCrew.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "mindcrew.web-search")
public class DocmindWebSearchProperties {

    /**
     * 是否启用公网搜索。
     */
    private boolean enabled = false;

    /**
     * 联网搜索源：tavily / baidu / serper。决定使用哪个 {@code WebSearchProvider}。
     */
    private String provider = "tavily";

    // ─── Tavily ───
    /**
     * Tavily 搜索 API 端点。
     */
    private String tavilyEndpoint = "https://api.tavily.com/search";

    /**
     * Tavily Bearer API Key。
     */
    private String apiKey;

    // ─── 百度千帆 AI 搜索 ───
    /**
     * 百度千帆 AI 搜索端点。
     */
    private String baiduEndpoint = "https://qianfan.baidubce.com/v2/ai_search";

    /**
     * 百度千帆平台 API Key（Bearer）。
     */
    private String baiduApiKey;

    /**
     * 百度搜索源标识（随千帆版本，默认最新网页搜索源）。
     */
    private String baiduSearchSource = "baidu_search_v2";

    // ─── Google Serper ───
    /**
     * Serper.dev API 端点（专门做 Google Search API 的服务，国内网络下可直连）。
     */
    private String serperEndpoint = "https://google.serper.dev/search";

    /**
     * Serper.dev API Key（请求头 X-API-KEY 鉴权）。
     * <p>注册地址：https://serper.dev  · 免费额度 2,500 次/月
     */
    private String serperApiKey;

    /**
     * HTTP 超时。
     */
    private Duration timeout = Duration.ofSeconds(10);
}
