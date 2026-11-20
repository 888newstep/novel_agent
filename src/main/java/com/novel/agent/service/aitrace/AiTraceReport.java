package com.novel.agent.service.aitrace;

import java.util.List;
import java.util.Map;

/**
 * 检测报告（端点响应体）。
 *
 * @param overallScore     总分 0-100
 * @param riskLevel        low / medium / high（low &lt; 25 ≤ medium ≤ 50 &lt; high）
 * @param lowConfidence    样本不足（低于 ai-trace.min-char-count）或规则全部降级时为 true
 * @param dimensionScores  维度分；未实现的维度（如 statistical）直接缺席，不用 0 占位
 * @param findings         各规则明细
 * @param flaggedSentences 依据句级证据聚合的可疑句，按分数降序、截断到上限
 * @param rulesVersion     规则版本号
 * @param disclaimer       免责声明
 * @param charCount        总字符数
 * @param hanCharCount     汉字符数
 * @param sentenceCount    句子数
 */
public record AiTraceReport(
        int overallScore,
        String riskLevel,
        boolean lowConfidence,
        Map<String, Double> dimensionScores,
        List<AiTraceFinding> findings,
        List<AiTraceFlaggedSentence> flaggedSentences,
        String rulesVersion,
        String disclaimer,
        int charCount,
        int hanCharCount,
        int sentenceCount) {
}
