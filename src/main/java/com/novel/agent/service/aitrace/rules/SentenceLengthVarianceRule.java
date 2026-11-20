package com.novel.agent.service.aitrace.rules;

import com.novel.agent.config.AiTraceProperties;
import com.novel.agent.service.aitrace.AiTraceContext;
import com.novel.agent.service.aitrace.AiTraceDimension;
import com.novel.agent.service.aitrace.AiTraceFinding;
import com.novel.agent.service.aitrace.AiTraceRule;
import com.novel.agent.service.aitrace.AiTraceTextUtils;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * R3 · 句长标准差（句式层）。
 *
 * <p>AI 生成的文本往往句长高度均齐。计算全文句子长度（去空白后的码点数）
 * 的标准差，低于阈值 {@code ai-trace.sentence-length-sigma-threshold}
 * （默认 6.0）时按比例计分；句数少于 {@code ai-trace.min-sentences-for-variance}
 * （默认 8）时不判定，返回 UNKNOWN，避免短文本误报。</p>
 */
@Component
public class SentenceLengthVarianceRule implements AiTraceRule {

    private final AiTraceProperties properties;

    public SentenceLengthVarianceRule(AiTraceProperties properties) {
        this.properties = properties;
    }

    @Override
    public String id() {
        return "R3";
    }

    @Override
    public AiTraceDimension dimension() {
        return AiTraceDimension.SYNTACTIC;
    }

    @Override
    public AiTraceFinding evaluate(AiTraceContext context) {
        List<String> sentences = context.sentences();
        if (sentences.size() < properties.getMinSentencesForVariance()) {
            return AiTraceFinding.skipped(id(), dimension(),
                    "句数 " + sentences.size() + " 低于最低判定句数 " + properties.getMinSentencesForVariance());
        }

        double mean = sentences.stream()
                .mapToInt(AiTraceTextUtils::sentenceLength)
                .average()
                .orElse(0);
        double variance = sentences.stream()
                .mapToInt(AiTraceTextUtils::sentenceLength)
                .mapToDouble(len -> (len - mean) * (len - mean))
                .sum() / sentences.size();
        double sigma = Math.sqrt(variance);

        double threshold = properties.getSentenceLengthSigmaThreshold();
        int rawScore = sigma < threshold
                ? (int) Math.min(100, Math.round((threshold - sigma) / threshold * 100))
                : 0;
        String message = String.format("句长标准差 %.2f（均值 %.1f，%d 句），阈值 %.2f",
                sigma, mean, sentences.size(), threshold);
        return new AiTraceFinding(id(), dimension(), sigma < threshold ? 1 : 0, null, rawScore,
                AiTraceFinding.severityOf(rawScore), List.of(), message);
    }
}
