package com.simon.MindCrew.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * 本地静态资源映射：将 /uploads/** 路由到磁盘上的 upload.path 目录
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${upload.path:uploads}")
    private String uploadPath;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 将 /uploads/** 映射到本地磁盘目录，末尾必须加 /
        String location = "file:" + Paths.get(uploadPath).toAbsolutePath() + "/";
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location);
    }

    /**
     * 延长 SSE 异步请求超时到 10 分钟（默认 30 秒）。
     * 避免长流式回答（特别是 LLM-Driven 检索 + 联网 + SelfReflection 重试）被 AsyncRequestTimeoutException 切断。
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(TimeUnit.MINUTES.toMillis(10));
    }
}
