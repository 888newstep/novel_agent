package com.novel.agent.service.aitrace;

import java.util.ArrayList;
import java.util.List;

/**
 * 中文文本切分与统计工具。
 *
 * <p>统一切分口径，规则实现不得各自切分：</p>
 * <ul>
 *   <li>段落：按一个或多个（换行 + 可选空白）的空行切分，去除首尾空白，丢弃空白段。</li>
 *   <li>句子：按中英文句末标点（。！？!?…）切分；句末标点后跟收尾引号
 *       （”』」）时，引号并入前句。丢弃空白句。</li>
 *   <li>句长：句子去掉全部空白后的码点数。</li>
 *   <li>千字密度：命中次数 / 汉字符数 * 1000。</li>
 * </ul>
 */
public final class AiTraceTextUtils {

    private AiTraceTextUtils() {
    }

    public static List<String> splitParagraphs(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        for (String part : text.split("\\R\\s*\\R")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    public static List<String> splitSentences(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            current.appendCodePoint(codePoint);
            i += Character.charCount(codePoint);
            if (isSentenceEnd(codePoint)) {
                // 收尾引号并入前句
                while (i < text.length() && isClosingQuote(text.codePointAt(i))) {
                    current.appendCodePoint(text.codePointAt(i));
                    i += Character.charCount(text.codePointAt(i));
                }
                addIfNotBlank(result, current.toString());
                current.setLength(0);
            }
        }
        addIfNotBlank(result, current.toString());
        return result;
    }

    public static int hanCharCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                count++;
            }
            i += Character.charCount(codePoint);
        }
        return count;
    }

    /** 句子去掉全部空白后的码点数。 */
    public static int sentenceLength(String sentence) {
        if (sentence == null || sentence.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < sentence.length(); ) {
            int codePoint = sentence.codePointAt(i);
            if (!Character.isWhitespace(codePoint)) {
                count++;
            }
            i += Character.charCount(codePoint);
        }
        return count;
    }

    /** 每千字密度：hits / 汉字符数 * 1000；汉字符数为 0 时返回 0。 */
    public static double perThousandHanChars(int hits, int hanCharCount) {
        return hanCharCount == 0 ? 0.0 : hits * 1000.0 / hanCharCount;
    }

    /** 统计 phrase 在 text 中的出现次数（不重叠）。 */
    public static int countOccurrences(String text, String phrase) {
        if (text == null || phrase == null || phrase.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(phrase, index)) >= 0) {
            count++;
            index += phrase.length();
        }
        return count;
    }

    private static boolean isSentenceEnd(int codePoint) {
        return switch (codePoint) {
            case '。', '！', '？', '.', '!', '?', '…' -> true;
            default -> false;
        };
    }

    private static boolean isClosingQuote(int codePoint) {
        return switch (codePoint) {
            case '”', '』', '」' -> true;
            default -> false;
        };
    }

    private static void addIfNotBlank(List<String> list, String value) {
        String trimmed = value.trim();
        if (!trimmed.isEmpty()) {
            list.add(trimmed);
        }
    }
}
