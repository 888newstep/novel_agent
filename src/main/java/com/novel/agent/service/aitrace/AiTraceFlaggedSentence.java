package com.novel.agent.service.aitrace;

import java.util.List;

/**
 * 可疑句：由多个规则的句级证据聚合而成。
 *
 * @param sentenceIndex 句子在全文句子序列中的序号（章末段落证据为 -1）
 * @param text          句子原文
 * @param score         贡献规则中的最高原始分
 * @param ruleIds       触发该句的规则编号
 * @param matchedTerms  命中的词或模板说明
 */
public record AiTraceFlaggedSentence(
        int sentenceIndex,
        String text,
        int score,
        List<String> ruleIds,
        List<String> matchedTerms) {

    public AiTraceFlaggedSentence {
        ruleIds = ruleIds == null ? List.of() : List.copyOf(ruleIds);
        matchedTerms = matchedTerms == null ? List.of() : List.copyOf(matchedTerms);
    }
}
