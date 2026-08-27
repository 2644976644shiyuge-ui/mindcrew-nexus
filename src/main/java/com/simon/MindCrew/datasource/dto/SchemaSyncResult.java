package com.simon.MindCrew.datasource.dto;

import java.util.List;

/**
 * 一次表结构同步的结果：新增的表（默认未启用，待审核）+ 已失效被禁用的表。
 */
public record SchemaSyncResult(List<String> added, List<String> disabled) {
}
