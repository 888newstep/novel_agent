package com.novel.agent.service.aitrace.rules;

import com.novel.agent.config.AiTraceProperties;
import com.novel.agent.service.aitrace.AiTraceContext;
import com.novel.agent.service.aitrace.AiTraceDictionary;
import com.novel.agent.service.aitrace.AiTraceDimension;
import com.novel.agent.service.aitrace.AiTraceEvidence;
import com.novel.agent.service.aitrace.AiTraceFinding;
import com.novel.agent.service.aitrace.AiTraceRule;
import com.novel.agent.service.aitrace.AiTraceTextUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * R5 · 转折词密度（句式层）。
 *
 * <p>统计词典 transition 分组词条的出现次数，换算为每千字密度；
 * 超过阈值 {@code ai-trace.transition-density-threshold}（默认 2.0 次/千字）
 * 时按比例计分。与 R1 分开成规则，便于后续独立调权。词典缺失时降级为 UNKNOWN。</p>
 */
@Component
public class TransitionDensityRule implements AiTraceRule {

    private final AiTraceProperties properties;
    private final List<String> phrases;

    public TransitionDensityRule(AiTraceProperties properties, AiTraceDictionary dictionary) {
        this.properties = properties;
        this.phrases = List.copyOf(dictionary.group(AiTraceDictionary.GROUP_TRANSITION));
    }

    @Override
    public String id() {
        return "R5";
    }

    @Override
    public AiTraceDimension dimension() {
        return AiTraceDimension.SYNTACTIC;
    }

    @Override
    public AiTraceFinding evaluate(AiTraceContext context) {
        if (phrases.isEmpty()) {
            return AiTraceFinding.skipped(id(), dimension(), "词典不可用（transition 分组为空），规则降级");
        }
        if (context.hanCharCount() == 0) {
            return AiTraceFinding.skipped(id(), dimension(), "无中文字符，密度无法计算");
        }

        int hits = 0;
        List<AiTraceEvidence> evidence = new ArrayList<>();
        List<String> sentences = context.sentences();
        for (int i = 0; i < sentences.size(); i++) {
            String sentence = sentences.get(i);
            List<String> matched = new ArrayList<>();
            for (String phrase : phrases) {
                int count = AiTraceTextUtils.countOccurrences(sentence, phrase);
                if (count > 0) {
                    hits += count;
                    matched.add(phrase + " x" + count);
                }
            }
            if (!matched.isEmpty()) {
                evidence.add(new AiTraceEvidence(i, sentence, matched));
            }
        }

        double density = context.perThousandHanChars(hits);
        double threshold = properties.getTransitionDensityThreshold();
        int rawScore = density <= 0 ? 0 : (int) Math.min(100, Math.round(density / threshold * 50));
        String message = String.format("转折词密度 %.2f 次/千字，阈值 %.2f", density, threshold);
        return new AiTraceFinding(id(), dimension(), hits, density, rawScore,
                AiTraceFinding.severityOf(rawScore), evidence, message);
    }
}
