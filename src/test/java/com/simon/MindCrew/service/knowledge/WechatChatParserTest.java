package com.simon.MindCrew.service.knowledge;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证微信聊天记录三种导出格式（TXT / CSV / HTML）的内容嗅探与解析。
 * 对应「收尾计划-2026-06-16」特性 4：确认上传后能被自动识别为微信记录。
 */
class WechatChatParserTest {

    private final WechatChatParser parser = new WechatChatParser();

    // ───────────────────── TXT（WechatExporter / 留痕风格）─────────────────────

    private static final String TXT_SAMPLE =
            "张三  2024-01-15 10:23:45\n" +
            "你好，咱们对一下这个客户的需求\n" +
            "\n" +
            "李四  2024-01-15 10:25:12\n" +
            "好的，客户主要关心交付周期\n" +
            "\n" +
            "张三  2024-01-15 10:26:00\n" +
            "周期我这边可以压到两周\n";

    @Test
    void detectsTxtWechatContent() {
        assertTrue(parser.looksLikeWechatContent(TXT_SAMPLE),
                "标准 TXT 导出（发送者+时间）应被识别为微信记录");
    }

    @Test
    void parsesTxtMessages() {
        WechatChatParser.ParseResult r =
                parser.parse(new ByteArrayInputStream(TXT_SAMPLE.getBytes(StandardCharsets.UTF_8)));
        assertEquals(3, r.totalMessages());
        assertTrue(r.allParticipants().contains("张三"));
        assertTrue(r.allParticipants().contains("李四"));
        assertEquals(1, r.sessions().size(), "间隔均小于 30 分钟，应聚为一个会话");
    }

    // ───────────────────── CSV（MemoTrace / PCWeChatTool 风格）─────────────────────

    private static final String CSV_SAMPLE =
            "sender,time,type,content\n" +
            "张三,2024-01-15 10:23:45,text,你好\n" +
            "李四,2024-01-15 10:25:12,text,在的\n" +
            "张三,2024-01-15 10:26:30,text,聊一下方案\n";

    @Test
    void detectsCsvWechatContent() {
        assertTrue(parser.looksLikeWechatContent(CSV_SAMPLE),
                "含 sender/time/content 表头的 CSV 应被识别为微信记录");
    }

    @Test
    void parsesCsvMessages() {
        WechatChatParser.ParseResult r =
                parser.parse(new ByteArrayInputStream(CSV_SAMPLE.getBytes(StandardCharsets.UTF_8)));
        assertEquals(3, r.totalMessages());
        assertTrue(r.allParticipants().contains("张三"));
        assertTrue(r.allParticipants().contains("李四"));
    }

    @Test
    void parsesCsvChineseHeader() {
        String csv =
                "发送者,时间,内容\n" +
                "王五,2024-02-01 09:00:00,早上好\n" +
                "赵六,2024-02-01 09:01:00,早\n";
        assertTrue(parser.looksLikeWechatContent(csv), "中文表头 CSV 也应被识别");
        WechatChatParser.ParseResult r =
                parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
        assertEquals(2, r.totalMessages());
    }

    // ───────────────────── HTML（导出工具结构化）─────────────────────

    private static final String HTML_SAMPLE =
            "<!doctype html><html><body>" +
            "<div class=\"message\"><span class=\"sender\">张三</span>" +
            "<span class=\"time\">2024-01-15 10:23:45</span>" +
            "<div class=\"content\">你好</div></div>" +
            "<div class=\"message\"><span class=\"sender\">李四</span>" +
            "<span class=\"time\">2024-01-15 10:25:12</span>" +
            "<div class=\"content\">在的，请讲</div></div>" +
            "</body></html>";

    @Test
    void detectsAndParsesHtmlWechat() {
        assertTrue(parser.looksLikeWechatContent(HTML_SAMPLE),
                "结构化 HTML 导出应被识别为微信记录");
        WechatChatParser.ParseResult r =
                parser.parse(new ByteArrayInputStream(HTML_SAMPLE.getBytes(StandardCharsets.UTF_8)));
        assertEquals(2, r.totalMessages());
        assertTrue(r.allParticipants().contains("张三"));
        assertTrue(r.allParticipants().contains("李四"));
    }

    // ───────────────────── 会话切分（间隔 ≥ 30 分钟开新会话）─────────────────────

    @Test
    void splitsSessionsByGap() {
        String txt =
                "张三  2024-01-15 10:00:00\n上午聊\n\n" +
                "李四  2024-01-15 10:01:00\n收到\n\n" +
                "张三  2024-01-15 14:00:00\n下午继续\n";
        WechatChatParser.ParseResult r =
                parser.parse(new ByteArrayInputStream(txt.getBytes(StandardCharsets.UTF_8)));
        assertEquals(3, r.totalMessages());
        assertEquals(2, r.sessions().size(), "上午与下午间隔 4 小时，应切成两个会话");
    }

    // ───────────────────── 负例：普通文档不应被误判 ─────────────────────

    @Test
    void doesNotMatchPlainDocument() {
        String doc = "# 产品需求文档\n\n本系统支持知识库检索与智能问答。\n" +
                "第一章 概述\n第二章 功能清单\n";
        assertFalse(parser.looksLikeWechatContent(doc),
                "普通文档（无发送者+时间格式）不应被误判为微信记录");
    }

    @Test
    void doesNotMatchBlank() {
        assertFalse(parser.looksLikeWechatContent(""));
        assertFalse(parser.looksLikeWechatContent(null));
    }

    @Test
    void supportedExtensionsCoverExportFormats() {
        assertTrue(WechatChatParser.supportedExtensions().containsAll(
                java.util.List.of("txt", "csv", "html", "htm")));
    }
}
