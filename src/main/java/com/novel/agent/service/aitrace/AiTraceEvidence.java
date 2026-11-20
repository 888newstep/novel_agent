package com.novel.agent.service.aitrace;

import java.util.List;

/**
 * 规则命中的句级证据：句子序号、原文、命中的词或模板。
 */
public record AiTraceEvidence(int sentenceIndex, String text, List<String> matchedTerms) {

    public AiTraceEvidence {
        matchedTerms = matchedTerms == null ? List.of() : List.copyOf(matchedTerms);
    }
}
