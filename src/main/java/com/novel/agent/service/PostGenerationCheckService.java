package com.novel.agent.service;

import com.novel.agent.config.AiTraceProperties;
import com.novel.agent.service.aitrace.AiTraceDictionary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 生成后质量检查（原 NovelController#buildPostGenerationCheck 的服务化迁移）。
 *
 * <p>历史遗留修复：禁用词从英文 suddenly 一组换成词典 banned 分组的中文词表
 * （突然、忽然、不禁、不由得、心中一凛），词表与检测服务共用同一份
 * {@code ai_trace_phrases_zh.txt}，避免两套词表漂移；任何一次命中即告警。</p>
 *
 * <p>输出契约保持不变：status / warningCount / warnings / metrics，
 * 既有测试与调用方无需改动。</p>
 */
@Slf4j
@Service
public class PostGenerationCheckService {

    private final List<String> bannedPhrases;

    public PostGenerationCheckService(AiTraceProperties properties, AiTraceDictionary dictionary) {
        List<String> configured = properties.getBannedPhrases();
        this.bannedPhrases = (configured == null || configured.isEmpty())
                ? List.copyOf(dictionary.group(AiTraceDictionary.GROUP_BANNED))
                : List.copyOf(configured);
        if (bannedPhrases.isEmpty()) {
            log.warn("AI trace banned phrases unavailable, post-generation banned-phrase check is disabled");
        }
    }

    public Map<String, Object> buildPostGenerationCheck(String topic, String generated) {
        String content = generated == null ? "" : generated.trim();
        List<String> warnings = new ArrayList<>();

        if (content.isEmpty()) {
            warnings.add("empty_output");
        }
        if (!content.isEmpty() && content.length() < 80) {
            warnings.add("content_too_short");
        }
        if (countOccurrences(content, "...") + countOccurrences(content, "……") >= 4) {
            warnings.add("excessive_ellipsis");
        }

        for (String phrase : bannedPhrases) {
            if (!phrase.isBlank() && content.contains(phrase)) {
                warnings.add("banned_phrase:" + phrase);
            }
        }

        if (hasRepeatedOpening(content)) {
            warnings.add("repeated_opening_pattern");
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("topic", topic);
        metrics.put("charCount", content.length());
        metrics.put("paragraphCount", countParagraphs(content));
        metrics.put("lineCount", content.isEmpty() ? 0 : content.split("\\R").length);
        metrics.put("sentenceCount", countSentences(content));

        Map<String, Object> check = new LinkedHashMap<>();
        check.put("status", warnings.isEmpty() ? "pass" : "warn");
        check.put("warningCount", warnings.size());
        check.put("warnings", warnings);
        check.put("metrics", metrics);
        return check;
    }

    private boolean hasRepeatedOpening(String content) {
        List<String> normalizedLines = Arrays.stream(content.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .map(line -> line.substring(0, Math.min(line.length(), 16)).toLowerCase(Locale.ROOT))
                .toList();
        Set<String> seen = new HashSet<>();
        for (String opening : normalizedLines) {
            if (!seen.add(opening)) {
                return true;
            }
        }
        return false;
    }

    private int countParagraphs(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        return (int) Arrays.stream(content.split("\\R\\s*\\R"))
                .map(String::trim)
                .filter(part -> !part.isBlank())
                .count();
    }

    private int countSentences(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        return (int) Arrays.stream(content.split("[.!?。！？]+"))
                .map(String::trim)
                .filter(part -> !part.isBlank())
                .count();
    }

    private int countOccurrences(String content, String target) {
        if (content == null || content.isEmpty() || target == null || target.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(target, index)) >= 0) {
            count++;
            index += target.length();
        }
        return count;
    }
}
