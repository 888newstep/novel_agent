package com.novel.agent.controller;

import com.novel.agent.entity.Chapter;
import com.novel.agent.repository.ChapterRepository;
import com.novel.agent.service.AiTraceDetectionService;
import com.novel.agent.service.aitrace.AiTraceReport;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 痕迹自检端点。
 *
 * <p>两种入参形态二选一：</p>
 * <ul>
 *   <li>{@code {text}}：直接检测正文</li>
 *   <li>{@code {novelId, chapterId}}：按章节号定位章节后检测其 summary 与
 *       keyEvents 拼接文本（章节正文不入库，详见 docs/AI_TRACE_DETECTOR.md）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/novel")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Validated
public class AiTraceController {

    private final AiTraceDetectionService detectionService;
    private final ChapterRepository chapterRepository;

    @PostMapping("/ai-trace/check")
    public ResponseEntity<?> check(@Valid @RequestBody AiTraceCheckRequest request) {
        String text = request.getText();
        if (text == null || text.isBlank()) {
            Chapter chapter = chapterRepository
                    .findByNovelIdAndChapterNum(request.getNovelId(), request.getChapterId())
                    .orElse(null);
            if (chapter == null) {
                return ResponseEntity.status(404).body(Map.of(
                        "error", "CHAPTER_NOT_FOUND",
                        "message", "novelId=" + request.getNovelId()
                                + " chapterId=" + request.getChapterId() + " 不存在"));
            }
            text = joinChapterText(chapter);
        }
        return ResponseEntity.ok(detectionService.check(text));
    }

    /** 章节正文不入库，用 summary 与 keyEvents 拼接为待检测文本。 */
    private String joinChapterText(Chapter chapter) {
        StringBuilder builder = new StringBuilder();
        if (chapter.getSummary() != null) {
            builder.append(chapter.getSummary().trim()).append('\n');
        }
        if (chapter.getKeyEvents() != null) {
            builder.append(chapter.getKeyEvents().trim());
        }
        return builder.toString();
    }
}
