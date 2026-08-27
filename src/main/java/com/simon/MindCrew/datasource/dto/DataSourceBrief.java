package com.simon.MindCrew.datasource.dto;

/**
 * 数据源精简信息 · 供「智能问答」里的数据源选择器使用。
 * 只暴露 id/名称/说明，不含 host/账号/密码等敏感连接信息。
 */
public record DataSourceBrief(Long id, String name, String description) {
}
