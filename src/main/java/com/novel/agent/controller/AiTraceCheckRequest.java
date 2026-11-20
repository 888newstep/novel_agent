package com.novel.agent.controller;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 痕迹检测请求：{@code text} 与 {@code novelId + chapterId} 二选一。
 */
@Data
public class AiTraceCheckRequest {

    /** 待检测正文（与章节引用二选一）。 */
    @Size(max = 50000, message = "text 超过 50000 字符上限")
    private String text;

    /** 章节引用模式：小说 ID（与 chapterId 搭配使用）。 */
    private Long novelId;

    /** 章节引用模式：章节号。 */
    private Integer chapterId;

    @AssertTrue(message = "text 与 novelId+chapterId 必须二选一：两者都传或都不传均不接受")
    public boolean isExactlyOneMode() {
        boolean hasText = text != null && !text.isBlank();
        boolean hasChapter = novelId != null && chapterId != null;
        boolean partialChapter = (novelId != null) != (chapterId != null);
        // 只允许其中一种模式：纯 text，或完整的 novelId + chapterId；残缺的章节模式拒绝
        return hasText != hasChapter && !partialChapter;
    }
}
