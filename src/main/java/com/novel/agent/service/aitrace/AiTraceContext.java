package com.novel.agent.service.aitrace;

import java.util.List;

/**
 * 检测上下文：原始文本及其一次性预计算的切分结果。
 *
 * <p>所有切分口径集中在 {@link AiTraceTextUtils}，规则只读本对象，
 * 避免各规则自行切分导致口径漂移。</p>
 */
public final class AiTraceContext {

    private final String rawText;
    private final List<String> paragraphs;
    private final List<String> sentences;
    private final int hanCharCount;
    private final int charCount;
    private final List<String> chapterEndParagraphs;

    private AiTraceContext(String rawText,
                           List<String> paragraphs,
                           List<String> sentences,
                           int hanCharCount,
                           int charCount,
                           List<String> chapterEndParagraphs) {
        this.rawText = rawText;
        this.paragraphs = paragraphs;
        this.sentences = sentences;
        this.hanCharCount = hanCharCount;
        this.charCount = charCount;
        this.chapterEndParagraphs = chapterEndParagraphs;
    }

    public static AiTraceContext of(String rawText) {
        String text = rawText == null ? "" : rawText;
        List<String> paragraphs = AiTraceTextUtils.splitParagraphs(text);
        List<String> sentences = AiTraceTextUtils.splitSentences(text);
        List<String> endings = paragraphs.size() <= 2
                ? paragraphs
                : paragraphs.subList(paragraphs.size() - 2, paragraphs.size());
        return new AiTraceContext(
                text,
                List.copyOf(paragraphs),
                List.copyOf(sentences),
                AiTraceTextUtils.hanCharCount(text),
                text.codePointCount(0, text.length()),
                List.copyOf(endings));
    }

    public String rawText() {
        return rawText;
    }

    public List<String> paragraphs() {
        return paragraphs;
    }

    public List<String> sentences() {
        return sentences;
    }

    public int hanCharCount() {
        return hanCharCount;
    }

    public int charCount() {
        return charCount;
    }

    /** 章节末尾的段落（用于 R8 章末钩子判定），最多 2 段。 */
    public List<String> chapterEndParagraphs() {
        return chapterEndParagraphs;
    }

    /** 每千字密度：hits / 汉字符数 * 1000。汉字符数为 0 时返回 0。 */
    public double perThousandHanChars(int hits) {
        return hanCharCount == 0 ? 0.0 : hits * 1000.0 / hanCharCount;
    }
}
