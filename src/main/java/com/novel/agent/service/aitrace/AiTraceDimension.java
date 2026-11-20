package com.novel.agent.service.aitrace;

/**
 * AI 痕迹检测的四个维度。
 *
 * <p>P0 阶段只实现 LEXICAL、SYNTACTIC、STRUCTURAL 三个维度；
 * STATISTICAL（困惑度、AI 自对比 embedding）属于 P3，未接入时不会出现在检测结果的
 * dimensionScores 中，避免用 0 分伪装成「已检测且无问题」。</p>
 */
public enum AiTraceDimension {

    LEXICAL("lexical", "词汇层"),
    SYNTACTIC("syntactic", "句式层"),
    STRUCTURAL("structural", "结构层"),
    STATISTICAL("statistical", "统计层");

    private final String key;
    private final String label;

    AiTraceDimension(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }
}