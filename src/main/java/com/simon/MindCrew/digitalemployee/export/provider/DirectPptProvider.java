package com.simon.MindCrew.digitalemployee.export.provider;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class DirectPptProvider implements PptProvider {

    private static final MediaType PPTX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation");

    @Override
    public String type() {
        return "direct";
    }

    @Override
    public byte[] generate(PptProviderRequest request, PptProviderConfig config) {
        if (config.apiUrl() == null || config.apiUrl().isBlank()) {
            throw new IllegalStateException("未配置 PPT API 地址");
        }

        RestClient.RequestBodySpec httpRequest = PptProviderSupport.restClient(config.timeoutSeconds())
                .post()
                .uri(config.apiUrl().trim())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(PPTX);
        if (config.apiKey() != null && !config.apiKey().isBlank()) {
            httpRequest.header("Authorization", "Bearer " + config.apiKey().trim());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", request.markdown());
        body.put("userInstruction", empty(request.userInstruction()));
        body.put("title", request.title());
        body.put("markdown", request.markdown());
        body.put("format", "pptx");
        body.put("planner", Map.of(
                "provider", empty(config.plannerProvider()),
                "model", empty(config.plannerModel()),
                "baseUrl", empty(config.plannerBaseUrl()),
                "apiKey", empty(config.plannerApiKey())
        ));
        body.put("options", Map.of(
                "language", "zh-CN",
                "quality", "commercial",
                "generationMode", request.options().generationMode(),
                "visualStyle", request.options().visualStyle(),
                "audience", request.options().audience(),
                "purpose", request.options().purpose(),
                "editable", request.options().editable(),
                "includeSpeakerNotes", request.options().includeSpeakerNotes(),
                "visualPolicy", request.options().preferVisuals()
                        ? "prefer-diagrams-charts-and-business-illustrations"
                        : "content-first"
        ));
        body.put("branding", Map.of(
                "companyName", empty(request.branding().companyName()),
                "docIdPrefix", empty(request.branding().docIdPrefix()),
                "docNumber", empty(request.branding().docNumber()),
                "footerNote", empty(request.branding().footerNote()),
                "employeeName", empty(request.branding().employeeName()),
                "deckStyle", empty(request.branding().deckStyle()),
                "primaryColor", empty(request.branding().primaryColor()),
                "accentColor", empty(request.branding().accentColor())
        ));

        byte[] result = httpRequest.body(body).retrieve().body(byte[].class);
        if (!PptProviderSupport.isPptx(result)) {
            throw new IllegalStateException("PPT API 返回的不是有效 PPTX 文件");
        }
        return result;
    }

    private static String empty(String value) {
        return value == null ? "" : value;
    }
}
