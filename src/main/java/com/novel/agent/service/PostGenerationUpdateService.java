package com.novel.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.agent.entity.Chapter;
import com.novel.agent.entity.KeyEvent;
import com.novel.agent.repository.ChapterRepository;
import com.novel.agent.repository.KeyEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 续写完成后的后处理服务——Phase 2 核心。
 *
 * <p>串联四步闭环，让系统具备"写完一章还能接着写"的能力：</p>
 * <ol>
 *   <li>AI 结构化抽取：summary + 新伏笔 + 回收关键词</li>
 *   <li>持久化 Chapter 元信息（summary / word_count / key_events JSON）</li>
 *   <li>新伏笔入库 + 按关键词批量回收已解决伏笔</li>
 *   <li>切 segment → embedding → upsert 到 Milvus novel_segments 集合</li>
 * </ol>
 *
 * <p><b>容错设计</b>：任何一步失败都 try-catch 并记录日志，
 * 绝不能让后处理异常打断主流程的章节生成结果返回给用户。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostGenerationUpdateService {

    private final DeepSeekService deepSeekService;
    private final ChapterRepository chapterRepository;
    private final KeyEventRepository keyEventRepository;
    private final MilvusService milvusService;
    private final ObjectMapper objectMapper;

    /** 正文段落切分：与 NovelTxtParser 保持一致 */
    private static final int MIN_SEG_LEN = 200;
    private static final int MAX_SEG_LEN = 2000;

    private static final String EXTRACTION_SYSTEM_PROMPT = """
            你是一位专业中文网文编辑。请分析用户提供的续写章节文本，返回严格的 JSON，不要任何其他文字。
            
            JSON schema：
            {
              "chapterSummary": "1-2 句话概括本章核心事件（不超过60字）",
              "newForeshadowing": [
                {
                  "title": "伏笔/事件简短标题（10字内）",
                  "description": "简述（不超过30字）",
                  "involvedCharacters": ["涉及角色名字符串数组，可为空"],
                  "eventType": "foreshadowing|plot_twist|character_change|event"
                }
              ],
              "resolvedForeshadowingKeywords": ["本章中明确被回收/解决的伏笔关键词数组，可为空"]
            }
            
            注意：
            - 没有新伏笔就返回 "newForeshadowing": []
            - 没有回收伏笔就返回 "resolvedForeshadowingKeywords": []
            - eventType 四选一，不要自创值
            - 整段输出必须是合法 JSON
            """;

    /**
     * 主入口——续写完成后调用。
     *
     * @param novelId       小说 id
     * @param chapterNum    当前续写的章号
     * @param generatedText  DeepSeek 生成的完整章节正文
     * @return 后处理结果（即使有步骤失败也返回部分成功结果）
     */
    public PostGenerationResult postGenerate(Long novelId, Integer chapterNum, String generatedText) {
        PostGenerationResult result = new PostGenerationResult();
        int wordCount = generatedText == null ? 0 : generatedText.length();
        result.setWordCount(wordCount);

        // ① AI 结构化抽取
        Extraction extraction = extractWithAi(novelId, generatedText, result);

        // ② 持久化 Chapter 元信息
        persistChapter(novelId, chapterNum, wordCount, extraction, result);

        // ③ 伏笔入库 + 回收
        updateForeshadowing(novelId, chapterNum, extraction, result);

        // ④ Milvus segment upsert
        syncMilvusSegments(novelId, chapterNum, generatedText, result);

        log.info("Post-generation finished: novelId={}, chapterNum={}, summary={}, newForeshadowing={}, resolved={}, segments={}",
                novelId, chapterNum,
                result.isSummaryPersisted(),
                result.getNewForeshadowingCount(),
                result.getResolvedForeshadowingCount(),
                result.getSyncedSegmentCount());

        return result;
    }

    // ---------- ① AI 结构化抽取 ----------

    private Extraction extractWithAi(Long novelId, String generatedText, PostGenerationResult result) {
        try {
            String userPrompt = """
                    请分析以下续写章节：
                    
                    ---
                    %s
                    ---
                    """.formatted(truncateForAi(generatedText, 8000));

            String rawJson = deepSeekService.chat(novelId, EXTRACTION_SYSTEM_PROMPT, userPrompt);
            String cleaned = stripCodeFences(rawJson);
            Extraction extraction = objectMapper.readValue(cleaned, Extraction.class);
            result.setAiExtracted(true);
            return extraction;
        } catch (Exception e) {
            log.warn("AI 结构化抽取失败，走降级路径: {}", e.getMessage());
            result.setAiExtracted(false);
            // 降级：summary 取正文前 60 字，伏笔返回空列表
            Extraction fallback = new Extraction();
            fallback.chapterSummary = truncatePlain(generatedText, 60);
            fallback.newForeshadowing = new ArrayList<>();
            fallback.resolvedForeshadowingKeywords = new ArrayList<>();
            return fallback;
        }
    }

    // ---------- ② 持久化 Chapter ----------

    @Transactional
    public void persistChapter(Long novelId, Integer chapterNum, int wordCount,
                               Extraction extraction, PostGenerationResult result) {
        try {
            Chapter chapter = chapterRepository.findByNovelIdAndChapterNum(novelId, chapterNum)
                    .orElseGet(() -> {
                        Chapter c = new Chapter();
                        c.setNovelId(novelId);
                        c.setChapterNum(chapterNum);
                        return c;
                    });

            chapter.setTitle("第" + chapterNum + "章");
            chapter.setWordCount(wordCount);
            chapter.setSummary(extraction.chapterSummary);

            // key_events 存成 JSON 数组字符串
            if (extraction.newForeshadowing != null && !extraction.newForeshadowing.isEmpty()) {
                String keyEventsJson = objectMapper.writeValueAsString(extraction.newForeshadowing);
                chapter.setKeyEvents(keyEventsJson);
            }

            chapterRepository.save(chapter);
            result.setSummaryPersisted(true);
        } catch (Exception e) {
            log.warn("Chapter 持久化失败（不影响主流程）: {}", e.getMessage());
            result.setSummaryPersisted(false);
        }
    }

    // ---------- ③ 伏笔入库 + 回收 ----------

    @Transactional
    public void updateForeshadowing(Long novelId, Integer chapterNum,
                                    Extraction extraction, PostGenerationResult result) {
        int newCount = 0;
        int resolvedCount = 0;

        // 3a. 新伏笔入库
        if (extraction.newForeshadowing != null) {
            for (ForeshadowingItem item : extraction.newForeshadowing) {
                try {
                    KeyEvent ke = new KeyEvent();
                    ke.setNovelId(novelId);
                    ke.setChapterNum(chapterNum);
                    ke.setTitle(item.title);
                    ke.setDescription(item.description);
                    ke.setEventType(item.eventType == null ? "foreshadowing" : item.eventType);
                    ke.setResolved(false);
                    keyEventRepository.save(ke);
                    newCount++;
                } catch (Exception e) {
                    log.warn("新伏笔入库失败: title={}, err={}", item.title, e.getMessage());
                }
            }
        }

        // 3b. 按关键词批量回收旧伏笔
        if (extraction.resolvedForeshadowingKeywords != null
                && !extraction.resolvedForeshadowingKeywords.isEmpty()) {
            List<KeyEvent> unresolved = keyEventRepository.findByNovelIdAndResolvedFalse(novelId);
            for (KeyEvent ke : unresolved) {
                if (matchesAnyKeyword(ke, extraction.resolvedForeshadowingKeywords)) {
                    try {
                        ke.setResolved(true);
                        ke.setResolvedAt(chapterNum);
                        keyEventRepository.save(ke);
                        resolvedCount++;
                    } catch (Exception e) {
                        log.warn("伏笔回收失败: id={}, err={}", ke.getId(), e.getMessage());
                    }
                }
            }
        }

        result.setNewForeshadowingCount(newCount);
        result.setResolvedForeshadowingCount(resolvedCount);
    }

    // ---------- ④ Milvus segment upsert ----------

    public void syncMilvusSegments(Long novelId, Integer chapterNum,
                                   String generatedText, PostGenerationResult result) {
        try {
            List<Map<String, Object>> segments = splitIntoSegments(generatedText);
            if (segments.isEmpty()) {
                log.warn("Milvus 同步跳过：章节切分为空, chapterNum={}", chapterNum);
                result.setSyncedSegmentCount(0);
                return;
            }
            milvusService.insertChapterSegments(novelId, chapterNum, segments);
            result.setSyncedSegmentCount(segments.size());
        } catch (Exception e) {
            log.warn("Milvus segment 同步失败（不影响主流程）: {}", e.getMessage());
            result.setSyncedSegmentCount(0);
        }
    }

    // ---------- 内部工具 ----------

    /**
     * 按段落切分，与 NovelTxtParser 保持一致的长度规则。
     * 输入正文 → 输出 MilvusService.insertChapterSegments 能吃的 List<Map>.
     */
    private List<Map<String, Object>> splitIntoSegments(String text) {
        List<Map<String, Object>> segments = new ArrayList<>();
        if (text == null || text.isBlank()) return segments;

        String[] paragraphs = text.split("\\n");
        for (String para : paragraphs) {
            para = para.trim();
            if (para.isEmpty()) continue;
            if (para.length() < MIN_SEG_LEN) continue;
            if (para.length() > MAX_SEG_LEN) {
                para = para.substring(0, MAX_SEG_LEN);
            }
            Map<String, Object> seg = new LinkedHashMap<>();
            seg.put("content", para);
            seg.put("type", 1);  // 1 = 正文
            segments.add(seg);
        }
        return segments;
    }

    private boolean matchesAnyKeyword(KeyEvent ke, List<String> keywords) {
        String haystack = (ke.getTitle() == null ? "" : ke.getTitle())
                + " "
                + (ke.getDescription() == null ? "" : ke.getDescription());
        for (String kw : keywords) {
            if (kw != null && !kw.isBlank() && haystack.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private String truncateForAi(String text, int maxChars) {
        if (text == null) return "";
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    private String truncatePlain(String text, int maxChars) {
        if (text == null || text.isBlank()) return "(未生成摘要)";
        String clean = text.replaceAll("[\\s\\n]+", " ").trim();
        return clean.length() <= maxChars ? clean : clean.substring(0, maxChars) + "...";
    }

    private String stripCodeFences(String raw) {
        if (raw == null) return "";
        String r = raw.trim();
        if (r.startsWith("```")) {
            int firstNewline = r.indexOf('\n');
            if (firstNewline >= 0) r = r.substring(firstNewline + 1);
            if (r.endsWith("```")) r = r.substring(0, r.length() - 3);
        }
        return r.trim();
    }

    // ---------- 内部 DTO ----------

    public static class Extraction {
        public String chapterSummary;
        public List<ForeshadowingItem> newForeshadowing = new ArrayList<>();
        public List<String> resolvedForeshadowingKeywords = new ArrayList<>();
    }

    public static class ForeshadowingItem {
        public String title;
        public String description;
        public List<String> involvedCharacters = new ArrayList<>();
        public String eventType;
    }

    /** 后处理结果——NovelController 返回给前端，让用户看到持久化状态。 */
    public static class PostGenerationResult {
        private boolean aiExtracted;
        private boolean summaryPersisted;
        private int wordCount;
        private int newForeshadowingCount;
        private int resolvedForeshadowingCount;
        private int syncedSegmentCount;

        // Lombok 不用在 DTO 上，手动写 getter/setter
        public boolean isAiExtracted() { return aiExtracted; }
        public void setAiExtracted(boolean v) { this.aiExtracted = v; }
        public boolean isSummaryPersisted() { return summaryPersisted; }
        public void setSummaryPersisted(boolean v) { this.summaryPersisted = v; }
        public int getWordCount() { return wordCount; }
        public void setWordCount(int v) { this.wordCount = v; }
        public int getNewForeshadowingCount() { return newForeshadowingCount; }
        public void setNewForeshadowingCount(int v) { this.newForeshadowingCount = v; }
        public int getResolvedForeshadowingCount() { return resolvedForeshadowingCount; }
        public void setResolvedForeshadowingCount(int v) { this.resolvedForeshadowingCount = v; }
        public int getSyncedSegmentCount() { return syncedSegmentCount; }
        public void setSyncedSegmentCount(int v) { this.syncedSegmentCount = v; }
    }
}
