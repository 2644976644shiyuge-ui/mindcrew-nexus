package com.simon.MindCrew.service.rag;

import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 中文分词器
 * 使用 Lucene SmartChineseAnalyzer 对中文、英文、数字混合文本做统一切词。
 */
@Slf4j
@Component
public class ChineseTextTokenizer {

    private final SmartChineseAnalyzer analyzer = new SmartChineseAnalyzer();

    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        Set<String> orderedTokens = new LinkedHashSet<>();
        // SmartChineseAnalyzer 会把 SC15 拆成 sc + 15，精确型号在大语料候选池里很容易被常见数字淹没。
        // 先保留完整标识符，同时继续保留分析器产生的子词，兼顾精确召回与普通中文语义召回。
        ExactIdentifierExtractor.extract(text).stream()
                .map(token -> token.toLowerCase(Locale.ROOT))
                .forEach(orderedTokens::add);
        try (TokenStream tokenStream = analyzer.tokenStream("content", new StringReader(text))) {
            CharTermAttribute attr = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                String token = normalize(attr.toString());
                if (isUsefulToken(token)) {
                    orderedTokens.add(token);
                }
            }
            tokenStream.end();
        } catch (IOException e) {
            log.warn("[ChineseTextTokenizer] 分词失败，回退到简单切分: {}", e.getMessage());
            return fallbackTokenize(text);
        }
        return new ArrayList<>(orderedTokens);
    }

    private List<String> fallbackTokenize(String text) {
        Set<String> orderedTokens = new LinkedHashSet<>();
        ExactIdentifierExtractor.extract(text).stream()
                .map(token -> token.toLowerCase(Locale.ROOT))
                .forEach(orderedTokens::add);
        for (String token : text.split("[\\s，。！？；：、,.;:!?()（）\\[\\]【】]+")) {
            token = normalize(token);
            if (isUsefulToken(token)) {
                orderedTokens.add(token);
            }
        }
        return new ArrayList<>(orderedTokens);
    }

    private String normalize(String token) {
        return token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isUsefulToken(String token) {
        if (token.isBlank()) {
            return false;
        }
        if (token.length() >= 2) {
            return true;
        }
        return token.chars().allMatch(Character::isDigit);
    }
}
