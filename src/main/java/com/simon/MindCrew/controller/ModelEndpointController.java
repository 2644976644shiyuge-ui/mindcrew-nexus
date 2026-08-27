package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.common.utils.AesCryptoUtils;
import com.simon.MindCrew.config.AiConfigHolder;
import com.simon.MindCrew.entity.ModelEndpoint;
import com.simon.MindCrew.service.ModelEndpointService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 模型端点配置管理 API
 *
 * 路由：/api/v2/model-endpoint
 *   GET    /list                    列出所有端点
 *   GET    /by-type/{modelType}     按类型列出
 *   GET    /active/{modelType}      某类型当前激活端点
 *   POST   /                        创建
 *   PUT    /{id}                    更新
 *   POST   /{id}/set-active         设为激活，自动热切换
 *   POST   /{id}/test               连通性测试
 *   POST   /test                    临时测试
 *   DELETE /{id}                    删除
 */
@RestController
@RequestMapping("/api/v2/model-endpoint")
@RequiredArgsConstructor
public class ModelEndpointController {

    private final ModelEndpointService service;
    private final AiConfigHolder aiConfigHolder;

    // ─────────────────────────────────────────────
    // 读取
    // ─────────────────────────────────────────────
    @GetMapping("/list")
    public Result<List<EndpointVO>> list() {
        List<ModelEndpoint> all = service.listAll();
        List<EndpointVO> out = new ArrayList<>();
        for (ModelEndpoint ep : all) out.add(EndpointVO.from(ep, service));
        return Result.success(out);
    }

    @GetMapping("/by-type/{modelType}")
    public Result<List<EndpointVO>> listByType(@PathVariable String modelType) {
        List<ModelEndpoint> all = service.listByType(modelType);
        List<EndpointVO> out = new ArrayList<>();
        for (ModelEndpoint ep : all) out.add(EndpointVO.from(ep, service));
        return Result.success(out);
    }

    @GetMapping("/active/{modelType}")
    public Result<EndpointVO> active(@PathVariable String modelType) {
        ModelEndpoint ep = service.getActiveOrDefault(modelType);
        return Result.success(ep == null ? null : EndpointVO.from(ep, service));
    }

    @GetMapping("/{id}")
    public Result<EndpointVO> getById(@PathVariable Long id) {
        ModelEndpoint ep = service.getById(id);
        return ep == null ? Result.error("端点不存在") : Result.success(EndpointVO.from(ep, service));
    }

    // ─────────────────────────────────────────────
    // 写入（管理员）
    // ─────────────────────────────────────────────
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Long> create(@RequestBody EndpointDTO dto) {
        ModelEndpoint ep = dto.toEntity();
        Long id = service.create(ep, dto.getApiKey());
        return Result.success(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> update(@PathVariable Long id, @RequestBody EndpointDTO dto) {
        ModelEndpoint ep = dto.toEntity();
        ep.setId(id);
        service.update(ep, dto.getApiKey());
        refreshByType(ep.getModelType());
        return Result.success();
    }

    @PostMapping("/{id}/set-active")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> setActive(@PathVariable Long id) {
        ModelEndpoint ep = service.getById(id);
        if (ep == null) return Result.error("端点不存在");
        service.setActive(id);
        refreshByType(ep.getModelType());
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.success();
    }

    // ─────────────────────────────────────────────
    // 连通性测试
    // ─────────────────────────────────────────────
    @PostMapping("/{id}/test")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Map<String, Object>> test(@PathVariable Long id, @RequestBody(required = false) TestDTO dto) {
        ModelEndpoint ep = service.getById(id);
        if (ep == null) return Result.error("端点不存在");
        String overrideKey = dto == null ? null : dto.getApiKey();
        var r = service.testConnectivity(ep, overrideKey);
        return Result.success(Map.of("success", r.success(), "message", r.message()));
    }

    @PostMapping("/test")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Map<String, Object>> testRaw(@RequestBody EndpointDTO dto) {
        ModelEndpoint tmp = dto.toEntity();
        var r = service.testConnectivity(tmp, dto.getApiKey());
        return Result.success(Map.of("success", r.success(), "message", r.message()));
    }

    // ─────────────────────────────────────────────
    // 热切换
    // ─────────────────────────────────────────────
    private void refreshByType(String modelType) {
        switch (modelType) {
            case "ocr", "vision", "video", "asr", "tts", "reranker", "voice_chat" ->
                    aiConfigHolder.refreshModelEndpoints();
        }
    }

    // ─────────────────────────────────────────────
    // DTO / VO
    // ─────────────────────────────────────────────
    @Data
    public static class EndpointDTO {
        private String name;
        private String modelType;
        private String providerType;
        private String baseUrl;
        private String apiKey;
        private String modelName;
        private String extraParams;
        private String description;
        private Integer enabled;
        private Integer sortOrder;

        public ModelEndpoint toEntity() {
            ModelEndpoint ep = new ModelEndpoint();
            ep.setName(name);
            ep.setModelType(modelType);
            ep.setProviderType(providerType == null ? "dashscope" : providerType);
            ep.setBaseUrl(baseUrl);
            ep.setModelName(modelName);
            ep.setExtraParams(extraParams);
            ep.setDescription(description);
            ep.setEnabled(enabled == null ? 1 : enabled);
            ep.setSortOrder(sortOrder == null ? 100 : sortOrder);
            return ep;
        }
    }

    @Data
    public static class TestDTO {
        private String apiKey;
    }

    @Data
    public static class EndpointVO {
        private Long id;
        private String name;
        private String modelType;
        private String providerType;
        private String baseUrl;
        private String apiKeyMasked;
        private boolean apiKeySet;
        private String modelName;
        private String extraParams;
        private String description;
        private Integer isActive;
        private Integer enabled;
        private Integer sortOrder;
        private String lastTestAt;
        private Integer lastTestOk;
        private String lastTestMsg;

        public static EndpointVO from(ModelEndpoint ep, ModelEndpointService svc) {
            EndpointVO v = new EndpointVO();
            v.setId(ep.getId());
            v.setName(ep.getName());
            v.setModelType(ep.getModelType());
            v.setProviderType(ep.getProviderType());
            v.setBaseUrl(ep.getBaseUrl());
            String decKey = svc.decryptKey(ep);
            v.setApiKeySet(decKey != null && !decKey.isBlank());
            v.setApiKeyMasked(AesCryptoUtils.mask(decKey));
            v.setModelName(ep.getModelName());
            v.setExtraParams(ep.getExtraParams());
            v.setDescription(ep.getDescription());
            v.setIsActive(ep.getIsActive());
            v.setEnabled(ep.getEnabled());
            v.setSortOrder(ep.getSortOrder());
            v.setLastTestAt(ep.getLastTestAt() == null ? null : ep.getLastTestAt().toString());
            v.setLastTestOk(ep.getLastTestOk());
            v.setLastTestMsg(ep.getLastTestMsg());
            return v;
        }
    }
}
