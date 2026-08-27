package com.simon.MindCrew.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 全球获客配置注册
 */
@Configuration
@EnableConfigurationProperties(LeadHunterProperties.class)
public class LeadHunterConfig {
}
