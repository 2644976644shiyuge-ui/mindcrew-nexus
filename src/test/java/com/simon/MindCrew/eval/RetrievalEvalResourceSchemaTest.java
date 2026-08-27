package com.simon.MindCrew.eval;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalEvalResourceSchemaTest {

    @Test
    void bundledDatasetIsAValidDisabledTemplateRatherThanFakeGroundTruth() throws Exception {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("eval/retrieval-eval.json")) {
            assertNotNull(in);
            JSONObject root = JSON.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));

            assertEquals(2, root.getIntValue("schemaVersion"));
            assertNotNull(root.getJSONObject("dataset"));
            assertTrue(root.getJSONObject("defaults").getIntValue("topK") > 0);

            JSONArray cases = root.getJSONArray("cases");
            assertNotNull(cases);
            assertTrue(cases.size() >= 3);
            for (int i = 0; i < cases.size(); i++) {
                JSONObject evalCase = cases.getJSONObject(i);
                assertFalse(evalCase.getBooleanValue("enabled"),
                        "仓库内模板不得伪装成适用于所有部署的真实真值");
                assertFalse(evalCase.getString("id").isBlank());
                assertFalse(evalCase.getString("question").isBlank());
                assertFalse(evalCase.getJSONArray("expect").isEmpty());
            }
        }
    }
}
