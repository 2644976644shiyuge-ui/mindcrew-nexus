package com.simon.MindCrew.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.common.utils.AesCryptoUtils;
import com.simon.MindCrew.entity.ModelEndpoint;
import com.simon.MindCrew.mapper.ModelEndpointMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 模型端点配置服务。
 *
 * 每种 model_type 最多一个激活端点；setActive 会取消同类型的旧激活。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelEndpointService {

    private final ModelEndpointMapper mapper;
    private final AesCryptoUtils aesCrypto;

    /** 列出全部未删除端点 */
    public List<ModelEndpoint> listAll() {
        return mapper.selectList(
                new LambdaQueryWrapper<ModelEndpoint>()
                        .eq(ModelEndpoint::getDeleted, 0)
                        .orderByAsc(ModelEndpoint::getSortOrder));
    }

    /** 按类型列出 */
    public List<ModelEndpoint> listByType(String modelType) {
        return mapper.selectList(
                new LambdaQueryWrapper<ModelEndpoint>()
                        .eq(ModelEndpoint::getDeleted, 0)
                        .eq(ModelEndpoint::getModelType, modelType)
                        .orderByAsc(ModelEndpoint::getSortOrder));
    }

    /** 获取某类型的激活端点 */
    public ModelEndpoint getActive(String modelType) {
        return mapper.selectOne(
                new LambdaQueryWrapper<ModelEndpoint>()
                        .eq(ModelEndpoint::getDeleted, 0)
                        .eq(ModelEndpoint::getModelType, modelType)
                        .eq(ModelEndpoint::getIsActive, 1));
    }

    /** 获取某类型的激活端点（带兜底 — 取第一个 enabled 的） */
    public ModelEndpoint getActiveOrDefault(String modelType) {
        ModelEndpoint active = getActive(modelType);
        if (active != null) return active;
        return mapper.selectOne(
                new LambdaQueryWrapper<ModelEndpoint>()
                        .eq(ModelEndpoint::getDeleted, 0)
                        .eq(ModelEndpoint::getModelType, modelType)
                        .eq(ModelEndpoint::getEnabled, 1)
                        .last("LIMIT 1"));
    }

    public ModelEndpoint getById(Long id) {
        return mapper.selectOne(
                new LambdaQueryWrapper<ModelEndpoint>()
                        .eq(ModelEndpoint::getId, id)
                        .eq(ModelEndpoint::getDeleted, 0));
    }

    /** 解密 API Key */
    public String decryptKey(ModelEndpoint ep) {
        if (ep.getApiKeyEnc() == null || ep.getApiKeyEnc().isBlank()) return "";
        try {
            return aesCrypto.decrypt(ep.getApiKeyEnc());
        } catch (Exception e) {
            return "";
        }
    }

    @Transactional
    public Long create(ModelEndpoint ep, String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            ep.setApiKeyEnc(aesCrypto.encrypt(apiKey));
        }
        mapper.insert(ep);
        return ep.getId();
    }

    @Transactional
    public void update(ModelEndpoint ep, String apiKey) {
        if (apiKey != null) {
            if (apiKey.isBlank()) {
                ep.setApiKeyEnc("");
            } else {
                ep.setApiKeyEnc(aesCrypto.encrypt(apiKey));
            }
        }
        mapper.updateById(ep);
    }

    @Transactional
    public void setActive(Long id) {
        ModelEndpoint target = getById(id);
        if (target == null) return;
        // 取消同类型旧激活
        List<ModelEndpoint> sameType = listByType(target.getModelType());
        for (ModelEndpoint ep : sameType) {
            if (ep.getIsActive() == 1 && !ep.getId().equals(id)) {
                ep.setIsActive(0);
                mapper.updateById(ep);
            }
        }
        target.setIsActive(1);
        mapper.updateById(target);
    }

    @Transactional
    public void delete(Long id) {
        mapper.deleteById(id);
    }

    // ======================== 连通性测试 ========================

    /** 测试连通性。专用模型端点必须用真实协议探测，不能一律拼接 /v1/models。 */
    public TestResult testConnectivity(ModelEndpoint ep, String overrideKey) {
        try {
            String key = overrideKey != null ? overrideKey : decryptKey(ep);
            if (key == null || key.isBlank()) {
                if ("local".equals(ep.getProviderType())) {
                    key = "EMPTY";
                } else {
                    return new TestResult(false, "API Key 未设置");
                }
            }

            if ("reranker".equalsIgnoreCase(ep.getModelType())) {
                return testReranker(ep, key);
            }

            String listUrl = ep.getBaseUrl().replaceAll("/+$", "") + "/v1/models";
            RestClient client = RestClient.builder()
                    .defaultHeader("Authorization", "Bearer " + key)
                    .build();

            String resp = client.get().uri(listUrl)
                    .retrieve()
                    .toEntity(String.class)
                    .getBody();

            if (resp != null && resp.contains("\"id\"")) {
                boolean found = resp.toLowerCase().contains(ep.getModelName().toLowerCase());
                if (found) {
                    return new TestResult(true, "连通正常 · 模型 " + ep.getModelName() + " 在列表中");
                }
                return new TestResult(true, "连通正常 · 响应模型列表（未精确匹配到 " + ep.getModelName() + "）");
            }
            return new TestResult(true, "连通正常 · 服务响应成功");
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.length() > 200) msg = msg.substring(0, 200) + "...";
            return new TestResult(false, msg != null ? msg : "未知错误");
        }
    }

    /** 用两条极小候选执行一次真实重排，能及时发现模型下线、权限或请求协议错误。 */
    private TestResult testReranker(ModelEndpoint ep, String key) {
        String model = ep.getModelName() == null ? "" : ep.getModelName().trim();
        JSONObject body = new JSONObject();
        body.put("model", model);
        if (model.toLowerCase(java.util.Locale.ROOT).startsWith("qwen3-rerank")) {
            body.put("query", "什么是网络音箱");
            body.put("documents", List.of("网络音箱是一种 IP 音频终端", "今天的天气很好"));
            body.put("top_n", 1);
            body.put("instruct", "Given a web search query, retrieve relevant passages that answer the query.");
        } else {
            JSONObject input = new JSONObject();
            input.put("query", "什么是网络音箱");
            input.put("documents", List.of("网络音箱是一种 IP 音频终端", "今天的天气很好"));
            JSONObject parameters = new JSONObject();
            parameters.put("top_n", 1);
            parameters.put("return_documents", false);
            body.put("input", input);
            body.put("parameters", parameters);
        }

        RestClient client = RestClient.builder()
                .defaultHeader("Authorization", "Bearer " + key)
                .build();
        String response = client.post()
                .uri(ep.getBaseUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toJSONString())
                .retrieve()
                .body(String.class);
        JSONObject json = response == null ? null : JSON.parseObject(response);
        JSONArray results = json == null ? null : json.getJSONArray("results");
        if (results == null && json != null && json.getJSONObject("output") != null) {
            results = json.getJSONObject("output").getJSONArray("results");
        }
        if (results == null || results.isEmpty()) {
            return new TestResult(false, "服务响应成功，但未返回重排结果");
        }
        return new TestResult(true, "连通正常 · " + model + " 已完成真实重排");
    }

    public record TestResult(boolean success, String message) {}
}
