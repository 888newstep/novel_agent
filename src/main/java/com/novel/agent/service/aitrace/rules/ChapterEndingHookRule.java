package com.novel.agent.service.aitrace.rules;

import com.novel.agent.config.AiTraceProperties;
import com.novel.agent.service.aitrace.AiTraceContext;
import com.novel.agent.service.aitrace.AiTraceDimension;
import com.novel.agent.service.aitrace.AiTraceEvidence;
import com.novel.agent.service.aitrace.AiTraceFinding;
import com.novel.agent.service.aitrace.AiTraceRule;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * R8 · 章末钩子模板（结构层）。
 *
 * <p>对章节末尾 2 段做 AI 常用收尾模板匹配：</p>
 * <ul>
 *   <li>悬念问句收尾：段末为问号且含「竟 / 难道 / 究竟」</li>
 *   <li>「就在这时」</li>
 *   <li>「不知道的是」</li>
 *   <li>「一切才刚开始」</li>
 *   <li>悬停收尾：段末为省略号或破折号</li>
 * </ul>
 *
 * <p>降误报：命中模板数达到 {@code ai-trace.ending-hook-min-match}（默认 2）
 * 才计分；单模板命中只记 evidence 不计分——真人也会用悬念收尾。</p>
 */
@Component
public class ChapterEndingHookRule implements AiTraceRule {

    private final AiTraceProperties properties;

    public ChapterEndingHookRule(AiTraceProperties properties) {
        this.properties = properties;
    }

    @Override
    public String id() {
        return "R8";
    }

    @Override
    public AiTraceDimension dimension() {
        return AiTraceDimension.STRUCTURAL;
    }

    @Override
    public AiTraceFinding evaluate(AiTraceContext context) {
        List<String> endings = context.chapterEndParagraphs();
        if (endings.isEmpty()) {
            return AiTraceFinding.skipped(id(), dimension(), "无可用段落，无法判定章末钩子");
        }

        List<String> matchedTemplates = new ArrayList<>();
        for (String paragraph : endings) {
            if (isSuspenseQuestion(paragraph)) {
                matchedTemplates.add("悬念问句收尾");
            }
            if (paragraph.contains("就在这时")) {
                matchedTemplates.add("就在这时");
            }
            if (paragraph.contains("不知道的是")) {
                matchedTemplates.add("不知道的是");
            }
            if (paragraph.contains("一切才刚开始")) {
                matchedTemplates.add("一切才刚开始");
            }
            if (endsWithHover(paragraph)) {
                matchedTemplates.add("省略号或破折号悬停收尾");
            }
        }

        int hits = matchedTemplates.size();
        int minMatch = properties.getEndingHookMinMatch();
        int rawScore = hits >= minMatch ? (int) Math.min(100, 50L * hits) : 0;
        List<AiTraceEvidence> evidence = hits > 0
                ? List.of(new AiTraceEvidence(-1, String.join("\n", endings), matchedTemplates))
                : List.of();
        String message = hits >= minMatch
                ? String.format("章末命中 %d 个钩子模板（阈值 %d）", hits, minMatch)
                : String.format("章末命中 %d 个钩子模板，低于计分阈值 %d，仅记录不计分", hits, minMatch);
        return new AiTraceFinding(id(), dimension(), hits, Double.NaN, rawScore,
                AiTraceFinding.severityOf(rawScore), evidence, message);
    }

    private boolean isSuspenseQuestion(String paragraph) {
        String trimmed = paragraph.stripTrailing();
        if (!trimmed.endsWith("？") && !trimmed.endsWith("?")) {
            return false;
        }
        return trimmed.contains("竟") || trimmed.contains("难道") || trimmed.contains("究竟");
    }

    private boolean endsWithHover(String paragraph) {
        String trimmed = paragraph.stripTrailing();
        return trimmed.endsWith("……") || trimmed.endsWith("…")
                || trimmed.endsWith("—") || trimmed.endsWith("──");
    }
}
