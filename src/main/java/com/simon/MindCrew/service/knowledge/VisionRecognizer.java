package com.simon.MindCrew.service.knowledge;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.List;

/**
 * 视觉识别器 · 基于通义千问 VL（qwen-vl-max）。
 *
 * 单次调用同时返回：
 *   1. OCR 文字提取（图片中的所有可识别文字）
 *   2. 视觉语义描述（这张图说的是什么）
 *
 * 走 OpenAI Compatible 协议复用现有 LLM 配置（DashScope）。
 * 切换到其他厂商（OpenAI gpt-4o / Claude 3.7）只需改 base-url + model。
 *
 * 配置 application.yml:
 *   llm:
 *     base-url: https://dashscope.aliyuncs.com/compatible-mode
 *     api-key: ${BAILIAN_API_KEY}
 *   vision:
 *     model: qwen-vl-max     # 或 qwen-vl-plus（便宜版）
 *     max-tokens: 2000
 *     timeout-seconds: 60
 */
@Slf4j
@Component
public class VisionRecognizer {

    @Value("${llm.base-url}")
    private String baseUrl;

    @Value("${llm.api-key}")
    private String apiKey;

    /** 视觉服务独立端点（离线指向本地 VL）；空=回退 llm.base-url（与对话同源） */
    @Value("${vision.base-url:}")
    private String visionBaseUrl;

    @Value("${vision.api-key:}")
    private String visionApiKey;

    @Value("${vision.model:qwen-vl-max}")
    private String model;

    /** 文档 OCR 独立端点；为空时回退 vision.*，避免影响图片描述和视频关键帧理解。 */
    @Value("${ocr.base-url:}")
    private String ocrBaseUrl;

    @Value("${ocr.api-key:}")
    private String ocrApiKey;

    @Value("${ocr.model:qwen3.5-ocr}")
    private String ocrModel;

    @org.springframework.beans.factory.annotation.Autowired
    private com.simon.MindCrew.config.AiConfigHolder aiConfigHolder;

    @Value("${vision.max-tokens:2000}")
    private int maxTokens;

    @Value("${ocr.max-tokens:8000}")
    private int ocrMaxTokens;

    private final RestTemplate restTemplate = new RestTemplate();

    private static final String SYSTEM_PROMPT = """
            你是一个图片识别助手。对收到的图片输出两段内容，用三个英文减号 --- 严格分隔：

            第一段 · OCR：完整提取图片中所有可见文字（中英文、数字、标点都要保留）。
            按从上到下、从左到右的视觉顺序排列。如果图片完全没有文字，写 "无文字"。

            ---

            第二段 · 描述：用 50-100 字客观描述这张图片是什么（截图/照片/图表/手写笔记/产品图/扫描件...），
            主要内容是什么，关键视觉元素有哪些。不要猜测和主观评价。

            禁止任何 emoji、装饰符号、营销话术。
            """;

    private static final String OCR_SYSTEM_PROMPT = """
            你是专业文档 OCR 引擎。请完整提取图片中所有可见文字，包括中英文、数字、标点、表格单元格和页眉页脚。
            保持从上到下、从左到右的阅读顺序，尽量保留段落和表格的换行结构。
            只输出识别出的原文，不要解释、总结、翻译或添加 Markdown 代码围栏；图片完全没有文字时输出“无文字”。
            """;

    /**
     * 识别一张图片。
     *
     * @param imageBytes 图片二进制
     * @param mimeType   MIME，如 image/jpeg / image/png
     * @return 识别结果，含 ocrText（提取的文字）和 description（视觉描述）
     */
    public VisionResult recognize(byte[] imageBytes, String mimeType) {
        String useBaseUrl = firstNonBlank(
                aiConfigHolder.getStringOrDefault("vision.base-url", visionBaseUrl), baseUrl);
        String useApiKey = firstNonBlank(
                aiConfigHolder.getStringOrDefault("vision.api-key", visionApiKey), apiKey);
        String useModel = aiConfigHolder.getStringOrDefault("vision.model", model);
        return recognizeInternal(imageBytes, mimeType, useBaseUrl, useApiKey, useModel,
                SYSTEM_PROMPT, maxTokens, false);
    }

    /**
     * 扫描 PDF 等纯文字提取场景使用独立 OCR 模型。若未配置 ocr 端点，地址和 Key
     * 自动回退到 vision 端点，但模型仍默认使用专用的 qwen3.5-ocr。
     */
    public VisionResult recognizeOcr(byte[] imageBytes, String mimeType) {
        String visionFallbackBaseUrl = firstNonBlank(
                aiConfigHolder.getStringOrDefault("vision.base-url", visionBaseUrl), baseUrl);
        String visionFallbackApiKey = firstNonBlank(
                aiConfigHolder.getStringOrDefault("vision.api-key", visionApiKey), apiKey);
        String useBaseUrl = firstNonBlank(
                aiConfigHolder.getStringOrDefault("ocr.base-url", ocrBaseUrl), visionFallbackBaseUrl);
        String useApiKey = firstNonBlank(
                aiConfigHolder.getStringOrDefault("ocr.api-key", ocrApiKey), visionFallbackApiKey);
        String useModel = aiConfigHolder.getStringOrDefault("ocr.model", ocrModel);
        return recognizeInternal(imageBytes, mimeType, useBaseUrl, useApiKey, useModel,
                OCR_SYSTEM_PROMPT, ocrMaxTokens, true);
    }

    private VisionResult recognizeInternal(byte[] imageBytes, String mimeType,
                                            String useBaseUrl, String useApiKey, String useModel,
                                            String systemPrompt, int outputTokens, boolean ocrOnly) {
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String dataUrl = "data:" + mimeType + ";base64," + base64;

        JSONObject content1 = new JSONObject();
        content1.put("type", "text");
        content1.put("text", ocrOnly ? "请完整识别这张文档图片中的全部文字。" : "请按系统指令处理这张图片。");

        JSONObject imgUrl = new JSONObject();
        imgUrl.put("url", dataUrl);
        JSONObject content2 = new JSONObject();
        content2.put("type", "image_url");
        content2.put("image_url", imgUrl);

        JSONArray contents = new JSONArray();
        contents.add(content1);
        contents.add(content2);

        JSONObject systemMsg = new JSONObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", contents);

        JSONObject body = new JSONObject();
        body.put("model", useModel);
        body.put("max_tokens", outputTokens);
        body.put("messages", JSONArray.of(systemMsg, userMsg));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(useApiKey);

        String url = useBaseUrl.replaceAll("/+$", "") + "/v1/chat/completions";
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, org.springframework.http.HttpMethod.POST,
                    new HttpEntity<>(body.toJSONString(), headers), String.class);
            return parseResult(resp.getBody(), ocrOnly);
        } catch (Exception e) {
            log.error("[VisionRecognizer] model={} 调用失败: {}", useModel, e.getMessage());
            return new VisionResult("", (ocrOnly ? "OCR" : "图片识别") + "失败：" + e.getMessage(), false);
        }
    }

    private VisionResult parseResult(String body, boolean ocrOnly) {
        if (body == null || body.isBlank()) {
            return new VisionResult("", "", false);
        }
        try {
            JSONObject obj = JSON.parseObject(body);
            long tokens = 0L;
            JSONObject usage = obj.getJSONObject("usage");
            if (usage != null) {
                Long total = usage.getLong("total_tokens");
                tokens = (total != null && total > 0) ? total
                        : usage.getLongValue("prompt_tokens") + usage.getLongValue("completion_tokens");
            }
            String content = obj.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");
            if (content == null) return new VisionResult("", "", false, tokens);

            if (ocrOnly) {
                String ocr = stripMarkdownFence(content.trim());
                ocr = stripLeadingLabel(ocr, "OCR", "OCR：", "识别结果", "识别结果：");
                if ("无文字".equals(ocr)) ocr = "";
                return new VisionResult(ocr, "", true, tokens);
            }

            // 按 --- 拆两段
            String[] parts = content.split("(?m)^\\s*---\\s*$", 2);
            String ocr = parts.length >= 1 ? parts[0].trim() : "";
            String desc = parts.length >= 2 ? parts[1].trim() : "";

            // 清理可能的前缀
            ocr = stripLeadingLabel(ocr, "OCR", "OCR：", "第一段", "第一段·OCR：");
            desc = stripLeadingLabel(desc, "描述", "描述：", "第二段", "第二段·描述：");

            if ("无文字".equals(ocr)) ocr = "";

            return new VisionResult(ocr, desc, true, tokens);
        } catch (Exception e) {
            log.warn("[VisionRecognizer] 响应解析失败: {}", e.getMessage());
            return new VisionResult("", "识别响应解析失败", false);
        }
    }

    private static String stripMarkdownFence(String text) {
        if (text == null) return "";
        String value = text.trim();
        if (!value.startsWith("```")) return value;
        int firstLineEnd = value.indexOf('\n');
        if (firstLineEnd >= 0) value = value.substring(firstLineEnd + 1);
        if (value.endsWith("```")) value = value.substring(0, value.length() - 3);
        return value.trim();
    }

    /** 返回第一个非空白字符串（用于端点回退链） */
    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private String stripLeadingLabel(String text, String... labels) {
        if (text == null) return "";
        String t = text.trim();
        for (String label : labels) {
            if (t.startsWith(label)) {
                t = t.substring(label.length()).trim();
                if (t.startsWith("：") || t.startsWith(":")) t = t.substring(1).trim();
            }
        }
        return t;
    }

    /** 视觉识别结果 */
    public record VisionResult(String ocrText, String description, boolean success, long totalTokens) {
        /** 兼容旧的 3 参构造（token 默认 0） */
        public VisionResult(String ocrText, String description, boolean success) {
            this(ocrText, description, success, 0L);
        }
        /** 合并为入库用的单段文本，含 OCR + 描述，方便检索 */
        public String toIndexedText() {
            StringBuilder sb = new StringBuilder();
            if (description != null && !description.isBlank()) {
                sb.append("【图片描述】").append(description).append("\n\n");
            }
            if (ocrText != null && !ocrText.isBlank()) {
                sb.append("【图片文字】\n").append(ocrText);
            }
            return sb.toString().trim();
        }
    }

    /** 当前支持的图片格式 */
    public static List<String> supportedExtensions() {
        return List.of("jpg", "jpeg", "png", "webp", "bmp", "gif");
    }

    public static String mimeOf(String ext) {
        return switch (ext.toLowerCase()) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "gif" -> "image/gif";
            default -> "image/jpeg";
        };
    }
}
