package com.simon.MindCrew.agent;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

@Component
public class SelectedDocumentScopeDecider {

    private static final Pattern DOCUMENT_REFERENCE_PATTERN = Pattern.compile(
            "这份文档|这个文档|该文档|这篇文档|这篇文章|该文章|这份材料|该材料|这份文件|该文件|这份报告|该报告|当前文档|选中的文档|这篇内容|本文档|本文");

    private static final Pattern SUMMARY_INTENT_PATTERN = Pattern.compile(
            "总结|概述|摘要|主要内容|核心内容|讲了什么|讲的什么|说了什么|内容概括|提炼要点|梳理重点|总结一下|概括一下");

    /** 枚举/清单意图：要"看全"，适合直读全部 */
    private static final Pattern ENUMERATION_INTENT_PATTERN = Pattern.compile(
            "有哪些|哪些文件|哪些文档|哪些内容|列出|列举|清单|目录|都有什么|包含哪些|有几个|有多少|全部文件|所有文件");

    /**
     * 判断是否走文档直读（全文读取）模式。
     * <p>合理逻辑：</p>
     * <ul>
     *   <li>"总结 / 概述 / 有哪些 / 这份文档…" 这类<b>需要看全</b>的意图 → 直读全部</li>
     *   <li>普通<b>具体问题</b>（即便选了知识库）→ 返回 false，走语义检索+rerank，
     *       在选中库内精准命中、给真实相关度分，而不是无脑把全库塞进上下文</li>
     * </ul>
     */
    public boolean shouldDirectRead(List<Long> kbIds, String question) {
        if (kbIds == null || kbIds.isEmpty() || question == null || question.isBlank()) {
            return false;
        }
        return DOCUMENT_REFERENCE_PATTERN.matcher(question).find()
                || SUMMARY_INTENT_PATTERN.matcher(question).find()
                || ENUMERATION_INTENT_PATTERN.matcher(question).find();
    }
}
