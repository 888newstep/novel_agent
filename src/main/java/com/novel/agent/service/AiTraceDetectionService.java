package com.novel.agent.service;

import com.novel.agent.config.AiTraceProperties;
import com.novel.agent.service.aitrace.AiTraceContext;
import com.novel.agent.service.aitrace.AiTraceDimension;
import com.novel.agent.service.aitrace.AiTraceFinding;
import com.novel.agent.service.aitrace.AiTraceFlaggedSentence;
import com.novel.agent.service.aitrace.AiTraceReport;
import com.novel.agent.service.aitrace.AiTraceRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * AI 痕迹自检器：规则聚合与评分。
 *
 * <p>P0 权重：词汇层 0.40、句式层 0.30、结构层 0.30；统计层（P3）未实现，
 * 其权重在已实现的维度内重新归一，{@code dimensionScores} 中不出现
 * statistical 字段，避免用 0 占位伪装成「已检测且无问题」。</p>
 *
 * <p>同一维度内多条规则等权平均；维度内所有规则均 UNKNOWN 时该维度
 * 不计分。总体分是已计分维度的加权和（权重重新归一后计算）。</p>
 */
@Slf4j
@Service
public class AiTraceDetectionService {

    private static final Map<AiTraceDimension, Double> DIMENSION_WEIGHTS = Map.of(
            AiTraceDimension.LEXICAL, 0.40,
            AiTraceDimension.SYNTACTIC, 0.30,
            AiTraceDimension.STRUCTURAL, 0.30);

    private final AiTraceProperties properties;
    private final List<AiTraceRule> rules;

    public AiTraceDetectionService(AiTraceProperties properties, List<AiTraceRule> rules) {
        this.properties = properties;
        this.rules = List.copyOf(rules);
    }

    public AiTraceReport check(String rawText) {
        AiTraceContext context = AiTraceContext.of(rawText);
        boolean lowConfidence = context.hanCharCount() < properties.getMinCharCount();

        if (context.charCount() == 0) {
            return emptyReport(context);
        }

        List<AiTraceFinding> findings = rules.stream()
                .map(rule -> rule.evaluate(context))
                .toList();

        Map<String, Double> dimensionScores = aggregateDimensions(findings);
        // 诚实约束：任一规则无法判定（词典降级、样本过短）都视为检测覆盖不完整，
        // 标记低置信，避免用不完整的结果给出看似确定的结论。
        if (findings.stream().anyMatch(f -> f.severity() == AiTraceFinding.Severity.UNKNOWN)) {
            log.warn("AI trace check: some rules degraded to UNKNOWN, marking low confidence");
            lowConfidence = true;
        }

        int overallScore = weightedOverallScore(dimensionScores);
        if (dimensionScores.isEmpty()) {
            lowConfidence = true;
        }

        List<AiTraceFlaggedSentence> flagged = mergeFlaggedSentences(findings);
        return new AiTraceReport(
                overallScore,
                riskLevelOf(overallScore),
                lowConfidence,
                dimensionScores,
                findings,
                flagged,
                properties.getRulesVersion(),
                properties.getDisclaimer(),
                context.charCount(),
                context.hanCharCount(),
                context.sentences().size());
    }

    private AiTraceReport emptyReport(AiTraceContext context) {
        return new AiTraceReport(0, "low", true, Map.of(), List.of(), List.of(),
                properties.getRulesVersion(), properties.getDisclaimer(),
                0, 0, 0);
    }

    /**
     * 总分 = Σ(维度分 × 维度权重) / Σ(已计分维度权重)，权重重新归一。
     * 统计层未实现，不参与；某维度内规则全部 UNKNOWN 时该维度同样不参与。
     */
    private int weightedOverallScore(Map<String, Double> dimensionScores) {
        double weightedSum = 0;
        double weightSum = 0;
        for (AiTraceDimension dimension : DIMENSION_WEIGHTS.keySet()) {
            Double score = dimensionScores.get(dimension.key());
            if (score == null) {
                continue;
            }
            double weight = DIMENSION_WEIGHTS.get(dimension);
            weightedSum += score * weight;
            weightSum += weight;
        }
        return weightSum == 0 ? 0 : (int) Math.round(weightedSum / weightSum);
    }

    /**
     * 维度聚合：维度分 = 维度内非 UNKNOWN 规则原始分的等权平均；
     * 维度内全部 UNKNOWN 时该维度缺席。键按 LEXICAL/SYNTACTIC/STRUCTURAL 固定顺序。
     */
    private Map<String, Double> aggregateDimensions(List<AiTraceFinding> findings) {
        Map<AiTraceDimension, List<Integer>> byDimension = new LinkedHashMap<>();
        for (AiTraceFinding finding : findings) {
            if (finding.severity() == AiTraceFinding.Severity.UNKNOWN) {
                continue;
            }
            byDimension.computeIfAbsent(finding.dimension(), d -> new ArrayList<>()).add(finding.rawScore());
        }
        Map<String, Double> result = new TreeMap<>();
        for (Map.Entry<AiTraceDimension, List<Integer>> entry : byDimension.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            double score = entry.getValue().stream().mapToInt(Integer::intValue).average().orElse(0);
            result.put(entry.getKey().key(), score);
        }
        return result;
    }

    private String riskLevelOf(int score) {
        if (score < 25) {
            return "low";
        }
        if (score <= 50) {
            return "medium";
        }
        return "high";
    }

    /** 按句子序号聚合各规则的证据：句分取贡献规则最高原始分，按分降序、截断到上限。 */
    private List<AiTraceFlaggedSentence> mergeFlaggedSentences(List<AiTraceFinding> findings) {
        Map<Integer, AiTraceFlaggedSentence> byIndex = new LinkedHashMap<>();
        for (AiTraceFinding finding : findings) {
            if (finding.severity() == AiTraceFinding.Severity.UNKNOWN) {
                continue;
            }
            for (var evidence : finding.evidence()) {
                AiTraceFlaggedSentence existing = byIndex.get(evidence.sentenceIndex());
                if (existing == null) {
                    byIndex.put(evidence.sentenceIndex(), new AiTraceFlaggedSentence(
                            evidence.sentenceIndex(), evidence.text(), finding.rawScore(),
                            List.of(finding.ruleId()), evidence.matchedTerms()));
                } else if (finding.rawScore() > existing.score()) {
                    byIndex.put(evidence.sentenceIndex(), new AiTraceFlaggedSentence(
                            evidence.sentenceIndex(), existing.text(), finding.rawScore(),
                            append(existing.ruleIds(), finding.ruleId()),
                            mergeTerms(existing.matchedTerms(), evidence.matchedTerms())));
                } else {
                    byIndex.put(evidence.sentenceIndex(), new AiTraceFlaggedSentence(
                            evidence.sentenceIndex(), existing.text(), existing.score(),
                            append(existing.ruleIds(), finding.ruleId()),
                            mergeTerms(existing.matchedTerms(), evidence.matchedTerms())));
                }
            }
        }
        return byIndex.values().stream()
                .sorted(Comparator.comparingInt(AiTraceFlaggedSentence::score).reversed())
                .limit(properties.getMaxFlaggedSentences())
                .toList();
    }

    private List<String> append(List<String> base, String value) {
        List<String> merged = new ArrayList<>(base);
        merged.add(value);
        return merged;
    }

    private List<String> mergeTerms(List<String> base, List<String> extra) {
        if (extra.isEmpty()) {
            return base;
        }
        List<String> merged = new ArrayList<>(base);
        for (String term : extra) {
            if (!merged.contains(term)) {
                merged.add(term);
            }
        }
        return merged;
    }
}
