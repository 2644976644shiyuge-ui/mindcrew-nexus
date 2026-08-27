package com.simon.MindCrew.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(DocmindWebSearchProperties.class)
public class WebSearchConfig {

    @Bean
    public RestTemplate webSearchRestTemplate(RestTemplateBuilder builder,
                                              DocmindWebSearchProperties properties) {
        Duration timeout = properties.getTimeout() != null ? properties.getTimeout() : Duration.ofSeconds(15);
        // 关键修复：Serper 等外部搜索 API 的 WAF 会拒绝 Java 默认 HTTP 请求
        // （无 User-Agent / 默认 UA 被识别为爬虫），导致 TLS 握手被中断：
        // "Remote host terminated the handshake"
        // 通过 interceptor 给所有请求加浏览器 UA，绕过 WAF 拦截。
        return builder
                .setConnectTimeout(timeout)
                .setReadTimeout(timeout)
                .additionalInterceptors((request, body, execution) -> {
                    HttpHeaders headers = request.getHeaders();
                    if (!headers.containsKey(HttpHeaders.USER_AGENT)) {
                        headers.set(HttpHeaders.USER_AGENT,
                                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36 ZYCOO-Nexus/1.0");
                    }
                    return execution.execute(request, body);
                })
                .build();
    }
}
