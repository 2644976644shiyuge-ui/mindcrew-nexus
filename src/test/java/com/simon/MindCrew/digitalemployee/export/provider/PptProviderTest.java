package com.simon.MindCrew.digitalemployee.export.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.MindCrew.digitalemployee.export.ExportBranding;
import com.simon.MindCrew.digitalemployee.export.PptGenerationService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PptProviderTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void gammaProviderCreatesPollsAndDownloadsPptx() throws Exception {
        byte[] pptx = fakePptx();
        AtomicInteger polls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1.0/generations", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("test-key", exchange.getRequestHeaders().getFirst("X-API-KEY"));
            respondJson(exchange, "{\"generationId\":\"gen-1\"}");
        });
        server.createContext("/v1.0/generations/gen-1", exchange -> {
            polls.incrementAndGet();
            String exportUrl = baseUrl() + "/files/result.pptx";
            respondJson(exchange, "{\"status\":\"completed\",\"exportUrl\":\"" + exportUrl + "\"}");
        });
        server.createContext("/files/result.pptx", exchange -> respondBytes(exchange, pptx));
        server.start();

        GammaPptProvider provider = new GammaPptProvider(new ObjectMapper());
        byte[] result = provider.generate(request(), config(baseUrl(), "test-key"));

        assertArrayEquals(pptx, result);
        assertEquals(1, polls.get());
    }

    @Test
    void directProviderReturnsBinaryPptx() throws Exception {
        byte[] pptx = fakePptx();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/generate", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("Bearer direct-key", exchange.getRequestHeaders().getFirst("Authorization"));
            respondBytes(exchange, pptx);
        });
        server.start();

        byte[] result = new DirectPptProvider().generate(
                request(), config(baseUrl() + "/generate", "direct-key"));

        assertArrayEquals(pptx, result);
    }

    @Test
    void qwenDocProviderReadsDownloadUrlFromSse() throws Exception {
        byte[] pptx = fakePptx();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/compatible-mode/v1/chat/completions", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("Bearer qwen-key", exchange.getRequestHeaders().getFirst("Authorization"));
            String url = baseUrl() + "/files/qwen-result.pptx?token=abc";
            String body = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"生成中\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"" + url + "\"}}]}\n\n"
                    + "data: [DONE]\n\n";
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            respondBytes(exchange, body.getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/files/qwen-result.pptx", exchange -> respondBytes(exchange, pptx));
        server.start();

        PptProviderConfig config = config(
                baseUrl() + "/compatible-mode/v1", "qwen-key");
        byte[] result = new QwenDocPptProvider(new ObjectMapper()).generate(request(), config);

        assertArrayEquals(pptx, result);
    }

    private PptProviderRequest request() {
        return new PptProviderRequest(
                "季度经营汇报",
                "## 核心结论\n- 收入同比增长 20%",
                "生成 8 页专业企业汇报",
                ExportBranding.empty("测试"),
                PptGenerationService.PptGenerationOptions.defaults());
    }

    private PptProviderConfig config(String apiUrl, String apiKey) {
        return new PptProviderConfig(
                apiUrl,
                apiKey,
                5,
                500,
                "",
                "general",
                "internet_01",
                "dashscope",
                "qwen-plus",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "");
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static byte[] fakePptx() {
        byte[] bytes = new byte[1024];
        bytes[0] = 0x50;
        bytes[1] = 0x4b;
        return bytes;
    }

    private static void respondJson(HttpExchange exchange, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        respondBytes(exchange, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respondBytes(HttpExchange exchange, byte[] body) throws IOException {
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
