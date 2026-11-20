package com.novel.agent.service.aitrace;

import java.util.List;

/**
 * 单条规则的检测结果。
 *
 * @param ruleId        规则编号（R1/R3/R5/R8...）
 * @param dimension     所属维度
 * @param hitCount      命中次数（密度规则为词条命中总次数，模板规则为命中的模板个数）
 * @param density       每千字密度；非密度规则为 {@code null}（避免 JSON 里的非标准 NaN 值）
 * @param rawScore      归一化到 0-100 的原始分
 * @param severity      严重程度；规则无法判定时为 {@link Severity#UNKNOWN}
 * @param evidence      句级证据，可为空
 * @param message       人类可读的判定说明
 */
public record AiTraceFinding(
        String ruleId,
        AiTraceDimension dimension,
        int hitCount,
        Double density,
        int rawScore,
        Severity severity,
        List<AiTraceEvidence> evidence,
        String message) {

    public enum Severity {
        NONE, LOW, MEDIUM, HIGH, UNKNOWN
    }

    public AiTraceFinding {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public static AiTraceFinding skipped(String ruleId, AiTraceDimension dimension, String reason) {
        return new AiTraceFinding(ruleId, dimension, 0, Double.NaN, 0, Severity.UNKNOWN, List.of(), reason);
    }

    public static Severity severityOf(int rawScore) {
        if (rawScore <= 0) {
            return Severity.NONE;
        }
        if (rawScore < 25) {
            return Severity.LOW;
        }
        if (rawScore < 50) {
            return Severity.MEDIUM;
        }
        return Severity.HIGH;
    }
}
