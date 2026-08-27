package com.simon.MindCrew.service.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChineseTextTokenizerTest {

    private final ChineseTextTokenizer tokenizer = new ChineseTextTokenizer();

    @Test
    void preservesCompactModelAlongsideAnalyzerSubtokens() {
        List<String> tokens = tokenizer.tokenize("分析sc15目前在美国市场的竞品");

        assertTrue(tokens.contains("sc15"), "完整型号必须保留，不能只剩 sc + 15: " + tokens);
        assertTrue(tokens.contains("美国"));
    }

    @Test
    void preservesHyphenatedSku() {
        assertTrue(tokenizer.tokenize("IPS-M1-D2W安装说明").contains("ips-m1-d2w"));
    }
}
