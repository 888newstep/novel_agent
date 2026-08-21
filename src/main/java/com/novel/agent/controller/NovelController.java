package com.novel.agent.controller;

import com.novel.agent.config.RetrievalProperties;
import com.novel.agent.entity.*;
import com.novel.agent.exception.CostLimitExceededException;
import com.novel.agent.repository.*;
import com.novel.agent.service.DeepSeekService;
import com.novel.agent.service.ChatPromptSupport;
import com.novel.agent.service.MilvusAdminService;
import com.novel.agent.service.MilvusSearchService;
import com.novel.agent.service.MilvusService;
import com.novel.agent.service.TokenCostService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

// 消除 java.lang.Character 与 entity.Character 的歧义
import com.novel.agent.entity.NovelCharacter;

@Slf4j
@RestController
@RequestMapping("/api/v1/novel")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class NovelController {

    private final NovelRepository novelRepository;
    private final ChapterRepository chapterRepository;
    private final KeyEventRepository keyEventRepository;
    private final InspirationRepository inspirationRepository;
    private final CharacterRepository characterRepository;
    private final ArtifactRepository artifactRepository;
    private final SkillRepository skillRepository;
    private final ItemLogRepository itemLogRepository;
    private final DeepSeekService deepSeekService;
    private final MilvusService milvusService;
    private final MilvusSearchService milvusSearchService;
    private final MilvusAdminService milvusAdminService;
    private final TokenCostService tokenCostService;
    private final RetrievalProperties retrievalProperties;

    // =============================================
    // 小说管理
    // =============================================

    @PostMapping
    public ResponseEntity<Novel> createNovel(@RequestBody Novel novel) {
        return ResponseEntity.ok(novelRepository.save(novel));
    }

    @GetMapping
    public ResponseEntity<List<Novel>> listNovels() {
        return ResponseEntity.ok(novelRepository.findAll());
    }

    @GetMapping("/{novelId}")
    public ResponseEntity<Novel> getNovel(@PathVariable Long novelId) {
        return novelRepository.findById(novelId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // =============================================
    // 章节管理
    // =============================================

    @PostMapping("/{novelId}/chapter")
    public ResponseEntity<Chapter> createChapter(@PathVariable Long novelId, @RequestBody Chapter chapter) {
        chapter.setNovelId(novelId);
        return ResponseEntity.ok(chapterRepository.save(chapter));
    }

    @GetMapping("/{novelId}/chapters")
    public ResponseEntity<List<Chapter>> listChapters(@PathVariable Long novelId) {
        return ResponseEntity.ok(chapterRepository.findByNovelIdOrderByChapterNumAsc(novelId));
    }

    // =============================================
    // AI 写作 - 生成章节内容
    // =============================================

    @PostMapping("/{novelId}/generate")
    public ResponseEntity<Map<String, Object>> generateChapter(
            @PathVariable Long novelId,
            @RequestParam String topic,
            @RequestParam(defaultValue = "??") String style,
            @RequestParam(defaultValue = "1A") String promptId,
            @RequestParam(required = false) Integer currentChapterNum) {

        Novel novel = novelRepository.findById(novelId).orElse(null);
        String worldSetting = novel != null ? novel.getWorldSetting() : "";
        MilvusSearchService.WritingMemory memory = milvusSearchService.buildWritingMemory(novelId, topic, currentChapterNum);

        String promptStyle = trimText(style, 60);
        String promptTopic = trimText(topic, 400);
        String userPrompt = String.format("请以%s风格续写本章，主题：%s。直接输出正文。", promptStyle, promptTopic);
        PromptAssembly promptAssembly = buildSystemPrompt(promptStyle, worldSetting, memory, promptId, userPrompt);
        String systemPrompt = promptAssembly.prompt();
        userPrompt = promptAssembly.userPrompt();
        TokenCostService.SettingsSnapshot settings = tokenCostService.getSettingsSnapshot();
        GenerationContext context = new GenerationContext(
                novelId,
                topic,
                style,
                currentChapterNum,
                promptId,
                worldSetting,
                memory,
                systemPrompt,
                userPrompt,
                promptAssembly
        );

        try {
            String generated = deepSeekService.chat(novelId, systemPrompt, userPrompt);
            return generationResponse(context, generated, false, null);
        } catch (CostLimitExceededException ex) {
            return degradedGenerationResponse(
                    context,
                    settings.isDegradeOnBudgetExceeded(),
                    "BUDGET_LIMIT",
                    "budget_limit",
                    ex
            );
        } catch (RuntimeException ex) {
            return degradedGenerationResponse(
                    context,
                    settings.isDegradeOnModelFailure(),
                    "MODEL_FAILURE",
                    "model_failure",
                    ex
            );
        }
    }

    // =============================================
    // 向量检索
    // =============================================

    @PostMapping("/{novelId}/search")
    public ResponseEntity<Map<String, Object>> searchSegments(
            @PathVariable Long novelId,
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK,
            @RequestParam(required = false) Integer currentChapterNum) {
        List<Map<String, Object>> results = milvusSearchService.searchSegments(novelId, query, topK, currentChapterNum);
        Map<String, Object> response = new HashMap<>();
        response.put("query", query);
        response.put("currentChapterNum", currentChapterNum);
        response.put("results", results);
        response.put("count", results.size());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{novelId}/search/hooks")
    public ResponseEntity<Map<String, Object>> searchHooks(
            @PathVariable Long novelId,
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK,
            @RequestParam(required = false) Integer currentChapterNum) {
        List<Map<String, Object>> results = milvusSearchService.searchUnresolvedEvents(novelId, query, topK, currentChapterNum);
        Map<String, Object> response = new HashMap<>();
        response.put("query", query);
        response.put("currentChapterNum", currentChapterNum);
        response.put("results", results);
        response.put("count", results.size());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{novelId}/memory")
    public ResponseEntity<Map<String, Object>> previewMemory(
            @PathVariable Long novelId,
            @RequestParam String query,
            @RequestParam(required = false) Integer currentChapterNum) {
        MilvusSearchService.WritingMemory memory = milvusSearchService.buildWritingMemory(novelId, query, currentChapterNum);
        Map<String, Object> response = new HashMap<>();
        response.put("query", query);
        response.put("currentChapterNum", currentChapterNum);
        response.put("memory", memory);
        response.put("summary", buildMemorySummary(memory));
        response.put("memoryLayers", buildMemoryLayers(memory));
        response.put("consistencyCheck", buildConsistencyCheck(novelId, memory, currentChapterNum));
        return ResponseEntity.ok(response);
    }

    // =============================================
    // 关键事件/伏笔管理
    // =============================================

    @PostMapping("/{novelId}/event")
    public ResponseEntity<KeyEvent> createEvent(@PathVariable Long novelId, @RequestBody KeyEvent event) {
        event.setNovelId(novelId);
        KeyEvent saved = keyEventRepository.save(event);

        // 同步写入 Milvus
        int eventTypeCode = switch (event.getEventType()) {
            case "plot_hook" -> 0;
            case "plot_twist" -> 1;
            case "foreshadowing" -> 2;
            case "climax" -> 3;
            default -> 0;
        };
        milvusService.insertKeyEvent(novelId, saved.getId(), event.getChapterNum(),
                eventTypeCode, event.getTitle(), event.getDescription());

        return ResponseEntity.ok(saved);
    }

    @GetMapping("/{novelId}/events/unresolved")
    public ResponseEntity<List<KeyEvent>> getUnresolvedEvents(@PathVariable Long novelId) {
        return ResponseEntity.ok(keyEventRepository.findByNovelIdAndResolvedFalse(novelId));
    }

    @PutMapping("/event/{eventId}/resolve")
    public ResponseEntity<Map<String, String>> resolveEvent(@PathVariable Long eventId) {
        keyEventRepository.findById(eventId).ifPresent(event -> {
            event.setResolved(true);
            keyEventRepository.save(event);
        });
        milvusSearchService.markEventResolved(eventId);
        return ResponseEntity.ok(Map.of("status", "resolved"));
    }

    // =============================================
    // 灵感库
    // =============================================

    @PostMapping("/inspiration")
    public ResponseEntity<Inspiration> addInspiration(@RequestBody Inspiration inspiration) {
        return ResponseEntity.ok(inspirationRepository.save(inspiration));
    }

    @GetMapping("/inspirations")
    public ResponseEntity<List<Inspiration>> listInspirations(
            @RequestParam(required = false) Long novelId,
            @RequestParam(required = false) String category) {
        if (novelId != null && category != null) {
            return ResponseEntity.ok(inspirationRepository.findByNovelIdAndCategory(novelId, category));
        } else if (category != null) {
            return ResponseEntity.ok(inspirationRepository.findByCategory(category));
        } else if (novelId == null) {
            return ResponseEntity.ok(inspirationRepository.findByNovelIdIsNull());
        }
        return ResponseEntity.ok(inspirationRepository.findAll());
    }

    // =============================================
    // 角色管理
    // =============================================

    @PostMapping("/{novelId}/character")
    public ResponseEntity<NovelCharacter> createCharacter(@PathVariable Long novelId, @RequestBody NovelCharacter character) {
        character.setNovelId(novelId);
        return ResponseEntity.ok(characterRepository.save(character));
    }

    @GetMapping("/{novelId}/characters")
    public ResponseEntity<List<NovelCharacter>> listCharacters(@PathVariable Long novelId) {
        return ResponseEntity.ok(characterRepository.findByNovelId(novelId));
    }

    // =============================================
    // 健康检查
    // =============================================

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    // =============================================
    // Milvus 管理（仅管理员使用）
    // =============================================

    /**
     * 为所有集合构建 HNSW 索引（数据全部写入后调用）
     */
    @PostMapping("/admin/milvus/build-index")
    public ResponseEntity<Map<String, String>> buildAllIndexes() {
        return adminResponse(
                () -> milvusAdminService.buildAllIndexesAsync(),
                "索引构建已异步启动，请通过日志或 Attu 监控进度");
    }

    /**
     * 加载所有集合到内存
     */
    @PostMapping("/admin/milvus/load")
    public ResponseEntity<Map<String, String>> loadAllCollections() {
        return adminResponse(milvusAdminService::loadAllCollections, "所有集合已加载到内存");
    }

    /**
     * 释放所有集合
     */
    @PostMapping("/admin/milvus/release")
    public ResponseEntity<Map<String, String>> releaseAllCollections() {
        return adminResponse(() -> {
            milvusAdminService.releaseCollection("novel_segments");
            milvusAdminService.releaseCollection("novel_events");
            milvusAdminService.releaseCollection("novel_characters");
            milvusAdminService.releaseCollection("novel_items");
            milvusAdminService.releaseCollection("novel_faction_inspire");
        }, "所有集合已从内存释放");
    }

    /**
     * flush 所有集合
     */
    @PostMapping("/admin/milvus/flush")
    public ResponseEntity<Map<String, String>> flushAll() {
        return adminResponse(milvusAdminService::flushAll, "所有集合 flush 完成");
    }

    /**
     * compact 所有集合
     */
    @PostMapping("/admin/milvus/compact")
    public ResponseEntity<Map<String, String>> compactAll() {
        return adminResponse(milvusAdminService::compactAll, "所有集合 compact 指令已发出");
    }

    /**
     * 清空某本小说所有向量（完本重建前调用）
     */
    @DeleteMapping("/admin/milvus/novel/{novelId}")
    public ResponseEntity<Map<String, String>> clearNovelVectors(@PathVariable Long novelId) {
        return adminResponse(
                () -> milvusAdminService.deleteByNovelIdAll(novelId),
                "小说 " + novelId + " 的所有向量已清空");
    }

    /**
     * 全量重建完整流程（清空 + 等待写入 + 建索引 + 加载）
     * 注意：调用此接口后，需外部写入新数据，然后调用 /admin/milvus/finalize 完成重建
     */
    @PostMapping("/admin/milvus/novel/{novelId}/rebuild")
    public ResponseEntity<Map<String, String>> rebuildNovel(@PathVariable Long novelId) {
        return adminResponse(
                () -> milvusAdminService.rebuildIndexForNovel(novelId),
                "小说 " + novelId + " 旧向量已清空，请写入新数据后调用 /admin/milvus/finalize 完成建索引+加载");
    }

    /**
     * 完成重建：flush → 建索引 → compact → load
     */
    @PostMapping("/admin/milvus/finalize")
    public ResponseEntity<Map<String, String>> finalizeRebuild() {
        return adminResponse(milvusAdminService::finalizeRebuild, "全量重建完成，所有集合已就绪");
    }

    // =============================================
    // 辅助方法
    // =============================================

    private ResponseEntity<Map<String, String>> adminResponse(Runnable action, String status) {
        action.run();
        return ResponseEntity.ok(Map.of("status", status));
    }

    private static final Map<String, String> SYSTEM_PROMPTS = new HashMap<>();
    static {
        SYSTEM_PROMPTS.put("1A", "你是一位仙侠小说作家，请用第三人称写仙侠故事。");
        SYSTEM_PROMPTS.put("1B", """
                你是一位笔名"青云子"的仙侠小说作家，深耕仙侠文学二十年。
                
                【世界观设定】
                - 修炼境界：炼气（九层）→ 筑基 → 金丹 → 元婴 → 化神 → 渡劫 → 大乘 → 飞升
                - 灵气分五行：金木水火土，相生相克
                - 宗门分五等：散修 < 下品宗门 < 中品宗门 < 上品圣地 < 不朽仙门
                
                【写作规范】
                - 第三人称有限视角，紧贴主角感知
                - 使用古风半白话文体，禁止现代网络用语
                - 每章需有明确的情绪弧线：铺垫 → 冲突 → 转折 → 余韵""");
        SYSTEM_PROMPTS.put("2A", """
                你是一位才华横溢的仙侠小说作家，想象力丰富，文笔优美。\
                请以你最擅长的方式创作仙侠故事，充分发挥你的创造力。""");
        SYSTEM_PROMPTS.put("2B", """
                你是一位仙侠小说作家，必须严格遵守以下创作铁律：
                
                【必须做到】
                - 主角必须有明确弱点，不允许全知全能
                - 每个出场人物必须有独立动机，不允许工具人
                - 战斗必须包含策略博弈，不允许纯力量碾压
                
                【绝对禁止】
                - 禁止使用"突然""居然""没想到""不愧是你"
                - 禁止龙傲天式越级秒杀
                - 禁止脸谱化反派
                - 禁止以"话说""且说""却说"开头""");
        SYSTEM_PROMPTS.put("3A", """
                你是一位硬核仙侠小说作家，擅长构建精密的修炼体系和热血战斗场面。
                
                【核心能力】
                - 修炼体系设计：每个境界有明确的突破条件、战力指标
                - 战斗描写：注重法术搭配、灵力消耗计算、战术博弈
                - 升级节奏：合理分配机缘、磨砺、突破的节奏
                
                【文风】
                - 节奏明快，短句为主，战斗段落用短促句式营造紧迫感""");
        SYSTEM_PROMPTS.put("3B", """
                你是一位文人气质的仙侠小说作家，追求"以仙写人，以剑写心"的境界。
                
                【核心追求】
                - 修仙即修心：每次境界突破都对应主角的心境蜕变
                - 以景写情：场景描写承载人物情绪
                - 道之争鸣：不同角色代表不同的"道"
                
                【文风】
                - 句式长短交错，留白处用短句，铺陈处用长句
                - 善用比喻和意象，将抽象情感化为具象画面""");
    }

    private PromptAssembly buildSystemPrompt(String style,
                                             String worldSetting,
                                             MilvusSearchService.WritingMemory memory,
                                             String promptId,
                                             String userPrompt) {
        int inputTokenBudget = Math.max(256, retrievalProperties.getMemory().getPromptTokenBudget());
        int wrapperTokenReserve = 16;
        int maxUserPromptTokens = Math.max(64, Math.min(400, inputTokenBudget / 4));
        String budgetedUserPrompt = trimToTokenBudget(userPrompt, maxUserPromptTokens);
        int userPromptTokens = tokenCostService.estimateTokens(budgetedUserPrompt);
        int systemTokenBudget = Math.max(1, inputTokenBudget - wrapperTokenReserve - userPromptTokens);
        PromptAccumulator prompt = new PromptAccumulator(systemTokenBudget);

        String basePrompt = SYSTEM_PROMPTS.getOrDefault(promptId, SYSTEM_PROMPTS.get("1A"));
        prompt.appendRequired(basePrompt);
        prompt.appendRequired("\n\n【续写规则】\n"
                + "- 服从已知设定，不编造冲突事实。\n"
                + "- 承接近期情节，避免重复复述。\n"
                + "- 自然推进未解决伏笔，不强行回收。\n"
                + "- 保持" + style + "风格。\n");

        if (memory.getCurrentChapterNum() != null) {
            prompt.appendOptional("\n【目标章节】第" + memory.getCurrentChapterNum() + "章\n");
        }
        if (worldSetting != null && !worldSetting.isBlank()) {
            String compactWorldSetting = trimText(worldSetting, 220);
            if (prompt.appendOptional("\n【世界设定】\n" + compactWorldSetting + "\n")) {
                prompt.registerContextFingerprint(compactWorldSetting);
            }
        }

        appendSection(prompt, "recentChapterContext", "近期章节", memory.getRecentChapters(), 2, item ->
                String.format("第%s章 %s：%s",
                        firstNonBlank(item.get("chapter_num"), "?"),
                        trimText(firstNonBlank(item.get("title"), "未命名章节"), 16),
                        trimText(firstNonBlank(item.get("summary"), item.get("key_events"), "无摘要"), 60)));
        appendSection(prompt, "unresolvedHooks", "未解决伏笔", memory.getHooks(), 2, item ->
                String.format("%s：%s",
                        trimText(firstNonBlank(item.get("title"), "未命名伏笔"), 16),
                        trimText(firstNonBlank(item.get("description"), ""), 56)));
        appendSection(prompt, "keyCharacters", "关键人物", memory.getCharacters(), 3, item ->
                String.format("%s：%s",
                        firstNonBlank(item.get("name"), "未知人物"),
                        trimText(firstNonBlank(item.get("char_text"), ""), 56)));
        appendSection(prompt, "sceneSegments", "相关片段", memory.getSegments(), 3, item ->
                trimText(firstNonBlank(item.get("content"), ""), 80));
        appendSection(prompt, "relations", "人物关系", memory.getRelations(), 2, item ->
                String.format("%s-%s：%s；%s",
                        firstNonBlank(item.get("source_name"), "未知"),
                        firstNonBlank(item.get("target_name"), "未知"),
                        firstNonBlank(item.get("relation_type"), "未知"),
                        trimText(firstNonBlank(item.get("description"), ""), 36)));
        appendSection(prompt, "items", "相关物品", memory.getItems(), 1, item ->
                String.format("%s：%s",
                        firstNonBlank(item.get("name"), "未知物品"),
                        trimText(firstNonBlank(item.get("item_text"), ""), 42)));
        appendSection(prompt, "factions", "相关势力", memory.getFactions(), 1, item ->
                String.format("%s：%s",
                        firstNonBlank(item.get("title"), "未知势力"),
                        trimText(firstNonBlank(item.get("content"), ""), 42)));

        return prompt.toAssembly(inputTokenBudget, budgetedUserPrompt, userPromptTokens);
    }

    private void appendSection(PromptAccumulator prompt,
                               String blockKey,
                               String title,
                               List<Map<String, Object>> items,
                               int maxItems,
                               java.util.function.Function<Map<String, Object>, String> formatter) {
        if (items == null || items.isEmpty() || maxItems <= 0) {
            return;
        }
        int accepted = 0;
        for (int i = 0; i < items.size(); i++) {
            String content = formatter.apply(items.get(i));
            if (content == null || content.isBlank()) {
                continue;
            }
            String prefix = accepted == 0 ? "\n【" + title + "】\n" : "";
            String formattedText = prefix + "[" + (accepted + 1) + "] " + content + "\n";
            if (accepted >= maxItems) {
                prompt.recordLimitDrop(formattedText);
            } else if (prompt.appendMemory(blockKey, i, content, formattedText)) {
                accepted++;
            }
        }
    }

    private String trimToTokenBudget(String text, int tokenBudget) {
        if (text == null || text.isBlank() || tokenBudget <= 0) {
            return "";
        }
        if (tokenCostService.estimateTokens(text) <= tokenBudget) {
            return text;
        }
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (tokenCostService.estimateTokens(text.substring(0, mid)) <= tokenBudget) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return text.substring(0, low).stripTrailing();
    }

    private String memoryFingerprint(String content) {
        return content.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。！？；：（）【】、]+", "");
    }

    private final class PromptAccumulator {
        private final int tokenBudget;
        private final StringBuilder text = new StringBuilder();
        private final Set<String> fingerprints = new LinkedHashSet<>();
        private final Map<String, List<Integer>> selectedIndexes = new LinkedHashMap<>();
        private int candidateMemoryTokens;
        private int selectedMemoryTokens;
        private int duplicateDroppedCount;
        private int budgetDroppedCount;
        private int limitDroppedCount;

        private PromptAccumulator(int tokenBudget) {
            this.tokenBudget = tokenBudget;
        }

        private void appendRequired(String value) {
            int remainingTokens = tokenBudget - tokenCostService.estimateTokens(text.toString());
            if (remainingTokens <= 0) {
                return;
            }
            text.append(trimToTokenBudget(value, remainingTokens));
        }

        private boolean appendOptional(String value) {
            if (value == null || value.isBlank()) {
                return false;
            }
            String candidate = text + value;
            if (tokenCostService.estimateTokens(candidate) > tokenBudget) {
                return false;
            }
            text.append(value);
            return true;
        }

        private boolean appendMemory(String blockKey, int sourceIndex, String content, String formattedText) {
            int formattedTokens = tokenCostService.estimateTokens(formattedText);
            candidateMemoryTokens += formattedTokens;
            String fingerprint = memoryFingerprint(content);
            boolean duplicate = fingerprints.stream().anyMatch(existing ->
                    existing.equals(fingerprint)
                            || (Math.min(existing.length(), fingerprint.length()) >= 16
                            && (existing.contains(fingerprint) || fingerprint.contains(existing))));
            if (fingerprint.isBlank() || duplicate) {
                duplicateDroppedCount++;
                return false;
            }
            if (!appendOptional(formattedText)) {
                budgetDroppedCount++;
                return false;
            }
            fingerprints.add(fingerprint);
            selectedIndexes.computeIfAbsent(blockKey, ignored -> new ArrayList<>()).add(sourceIndex);
            selectedMemoryTokens += formattedTokens;
            return true;
        }

        private void recordLimitDrop(String formattedText) {
            candidateMemoryTokens += tokenCostService.estimateTokens(formattedText);
            limitDroppedCount++;
        }

        private void registerContextFingerprint(String content) {
            String fingerprint = memoryFingerprint(content);
            if (!fingerprint.isBlank()) {
                fingerprints.add(fingerprint);
            }
        }

        private PromptAssembly toAssembly(int inputTokenBudget, String userPrompt, int userPromptTokens) {
            Map<String, List<Integer>> immutableIndexes = new LinkedHashMap<>();
            selectedIndexes.forEach((key, value) -> immutableIndexes.put(key, List.copyOf(value)));
            return new PromptAssembly(
                    text.toString(),
                    userPrompt,
                    inputTokenBudget,
                    tokenBudget,
                    userPromptTokens,
                    tokenCostService.estimateTokens(text.toString()),
                    Collections.unmodifiableMap(immutableIndexes),
                    candidateMemoryTokens,
                    selectedMemoryTokens,
                    duplicateDroppedCount,
                    budgetDroppedCount,
                    limitDroppedCount
            );
        }
    }

    private record PromptAssembly(String prompt,
                                  String userPrompt,
                                  int inputTokenBudget,
                                  int systemTokenBudget,
                                  int userPromptTokens,
                                  int systemPromptTokens,
                                  Map<String, List<Integer>> selectedIndexes,
                                  int candidateMemoryTokens,
                                  int selectedMemoryTokens,
                                  int duplicateDroppedCount,
                                  int budgetDroppedCount,
                                  int limitDroppedCount) {
    }

    private Map<String, Object> buildMemorySummary(MilvusSearchService.WritingMemory memory) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("currentChapterNum", memory.getCurrentChapterNum());
        summary.put("recentChapters", memory.getRecentChapters().size());
        summary.put("segments", memory.getSegments().size());
        summary.put("hooks", memory.getHooks().size());
        summary.put("characters", memory.getCharacters().size());
        summary.put("items", memory.getItems().size());
        summary.put("factions", memory.getFactions().size());
        summary.put("relations", memory.getRelations().size());
        summary.put("total", memory.getTotalCount());
        return summary;
    }

    private Map<String, Object> buildMemoryLayers(MilvusSearchService.WritingMemory memory) {
        Map<String, Object> layers = new LinkedHashMap<>();
        layers.put("recentChapterContext", buildLayer(memory.getRecentChapters().size(), collectValues(memory.getRecentChapters(), "chapter_num", 3)));
        layers.put("sceneSegments", buildLayer(memory.getSegments().size(), collectValues(memory.getSegments(), "segment_type", 3)));
        layers.put("keyCharacters", buildLayer(memory.getCharacters().size(), collectValues(memory.getCharacters(), "name", 3)));
        layers.put("unresolvedHooks", buildLayer(memory.getHooks().size(), collectValues(memory.getHooks(), "title", 3)));
        layers.put("worldFacts", buildLayer(memory.getItems().size() + memory.getFactions().size() + memory.getRelations().size(), collectWorldFacts(memory)));
        return layers;
    }

    private Map<String, Object> buildConsistencyCheck(Long novelId,
                                                      MilvusSearchService.WritingMemory memory,
                                                      Integer currentChapterNum) {
        List<String> warnings = new ArrayList<>();

        if (memory.getTotalCount() == 0) {
            warnings.add("no_memory_context");
        }
        if (memory.getRecentChapters().isEmpty() && memory.getSegments().isEmpty()) {
            warnings.add("missing_story_context");
        }
        if (memory.getCharacters().isEmpty()) {
            warnings.add("missing_character_memory");
        }

        collectFutureChapterWarnings(warnings, "recentChapters", memory.getRecentChapters(), currentChapterNum);
        collectFutureChapterWarnings(warnings, "segments", memory.getSegments(), currentChapterNum);
        collectFutureChapterWarnings(warnings, "hooks", memory.getHooks(), currentChapterNum);

        List<String> duplicateCharacters = collectDuplicateValues(memory.getCharacters(), "name");
        duplicateCharacters.forEach(name -> warnings.add("duplicate_character_context:" + name));

        List<String> duplicateHooks = collectDuplicateValues(memory.getHooks(), "title");
        duplicateHooks.forEach(title -> warnings.add("duplicate_hook_context:" + title));

        List<String> relationConflicts = collectRelationConflicts(memory.getRelations());
        relationConflicts.forEach(conflict -> warnings.add("relation_conflict:" + conflict));

        collectResolvedEventWarnings(warnings, novelId, memory.getHooks(), currentChapterNum);
        collectItemStatusWarnings(warnings, novelId, memory.getItems(), currentChapterNum);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("novelId", novelId);
        metrics.put("currentChapterNum", currentChapterNum);
        metrics.put("recentChapters", memory.getRecentChapters().size());
        metrics.put("segments", memory.getSegments().size());
        metrics.put("hooks", memory.getHooks().size());
        metrics.put("characters", memory.getCharacters().size());
        metrics.put("relations", memory.getRelations().size());
        metrics.put("duplicateCharacterCount", duplicateCharacters.size());
        metrics.put("duplicateHookCount", duplicateHooks.size());
        metrics.put("relationConflictCount", relationConflicts.size());
        metrics.put("resolvedEventReuseCount", countWarningsByPrefix(warnings, "resolved_event_reused:"));
        metrics.put("futureItemLeakCount", countWarningsByPrefix(warnings, "future_item_first_appear:"));
        metrics.put("itemStatusConflictCount", countWarningsByPrefix(warnings, "item_status_conflict:"));
        metrics.put("futureItemMutationCount", countWarningsByPrefix(warnings, "future_item_mutation:"));

        Map<String, Object> check = new LinkedHashMap<>();
        check.put("status", warnings.isEmpty() ? "pass" : "warn");
        check.put("warningCount", warnings.size());
        check.put("warnings", warnings);
        check.put("metrics", metrics);
        return check;
    }

    private void collectResolvedEventWarnings(List<String> warnings,
                                             Long novelId,
                                             List<Map<String, Object>> hooks,
                                             Integer currentChapterNum) {
        if (hooks == null || hooks.isEmpty()) {
            return;
        }
        Map<Long, KeyEvent> eventIndex = keyEventRepository.findByNovelIdOrderByChapterNumAsc(novelId).stream()
                .collect(java.util.stream.Collectors.toMap(KeyEvent::getId, event -> event, (left, right) -> left, LinkedHashMap::new));

        for (Map<String, Object> hook : hooks) {
            long eventId = parseLong(hook.get("mysql_event_id"));
            if (eventId <= 0) {
                continue;
            }
            KeyEvent event = eventIndex.get(eventId);
            if (event == null) {
                continue;
            }
            if (Boolean.TRUE.equals(event.getResolved())) {
                addUniqueWarning(warnings, "resolved_event_reused:" + firstNonBlank(event.getTitle(), String.valueOf(eventId)));
                continue;
            }
            if (currentChapterNum != null && event.getResolvedAt() != null && event.getResolvedAt() <= currentChapterNum) {
                addUniqueWarning(warnings, "resolved_event_reused:" + firstNonBlank(event.getTitle(), String.valueOf(eventId)));
            }
        }
    }

    private void collectItemStatusWarnings(List<String> warnings,
                                           Long novelId,
                                           List<Map<String, Object>> items,
                                           Integer currentChapterNum) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (Map<String, Object> item : items) {
            long itemId = parseLong(item.get("mysql_item_id"));
            int itemType = parseInt(item.get("item_type"));
            String itemName = firstNonBlank(item.get("name"), "item-" + itemId);
            if (itemId <= 0) {
                continue;
            }
            if (itemType == 0) {
                artifactRepository.findById(itemId).ifPresent(artifact -> inspectArtifactWarnings(warnings, novelId, artifact, currentChapterNum, itemName));
            } else if (itemType == 1) {
                skillRepository.findById(itemId).ifPresent(skill -> inspectSkillWarnings(warnings, novelId, skill, currentChapterNum, itemName));
            }
        }
    }

    private void inspectArtifactWarnings(List<String> warnings,
                                         Long novelId,
                                         Artifact artifact,
                                         Integer currentChapterNum,
                                         String itemName) {
        if (currentChapterNum != null && artifact.getFirstAppear() != null && artifact.getFirstAppear() > currentChapterNum) {
            addUniqueWarning(warnings, "future_item_first_appear:" + itemName);
        }
        String status = firstNonBlank(artifact.getStatus());
        if (!status.isBlank() && !"active".equalsIgnoreCase(status)) {
            addUniqueWarning(warnings, "item_status_conflict:" + itemName + ":" + status);
        }
        if (currentChapterNum != null && hasFutureItemMutation(novelId, "artifact", artifact.getId(), currentChapterNum)) {
            addUniqueWarning(warnings, "future_item_mutation:" + itemName);
        }
    }

    private void inspectSkillWarnings(List<String> warnings,
                                      Long novelId,
                                      Skill skill,
                                      Integer currentChapterNum,
                                      String itemName) {
        if (currentChapterNum != null && skill.getFirstAppear() != null && skill.getFirstAppear() > currentChapterNum) {
            addUniqueWarning(warnings, "future_item_first_appear:" + itemName);
        }
        String stage = firstNonBlank(skill.getStage());
        if (!stage.isBlank() && List.of("forbidden", "destroyed", "sealed").contains(stage.toLowerCase(Locale.ROOT))) {
            addUniqueWarning(warnings, "item_status_conflict:" + itemName + ":" + stage);
        }
        if (currentChapterNum != null && hasFutureItemMutation(novelId, "skill", skill.getId(), currentChapterNum)) {
            addUniqueWarning(warnings, "future_item_mutation:" + itemName);
        }
    }

    private boolean hasFutureItemMutation(Long novelId, String itemType, Long itemId, Integer currentChapterNum) {
        return itemLogRepository.findByNovelIdAndItemTypeAndItemId(novelId, itemType, itemId).stream()
                .anyMatch(log -> log.getChapterNum() != null && log.getChapterNum() > currentChapterNum);
    }

    private void addUniqueWarning(List<String> warnings, String warning) {
        if (!warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    private int countWarningsByPrefix(List<String> warnings, String prefix) {
        return (int) warnings.stream().filter(warning -> warning.startsWith(prefix)).count();
    }

    private long parseLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private int parseInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
    private Map<String, Object> buildGenerationTrace(String promptId,
                                                     String topic,
                                                     String style,
                                                     String worldSetting,
                                                     MilvusSearchService.WritingMemory memory,
                                                     String systemPrompt,
                                                     String userPrompt,
                                                     String generated,
                                                     PromptAssembly promptAssembly) {
        Map<String, Object> trace = new LinkedHashMap<>();
        Map<String, Object> selectedMemoryBlocks = buildPromptMemoryBlocks(memory, promptAssembly);
        trace.put("promptId", promptId);
        trace.put("topic", topic);
        trace.put("style", style);
        trace.put("selectedMemoryBlocks", selectedMemoryBlocks);
        trace.put("droppedCandidates", collectDroppedCandidates(selectedMemoryBlocks));
        trace.put("contextStats", buildContextStats(worldSetting, systemPrompt, userPrompt, generated));
        trace.put("promptBudget", buildPromptBudgetTrace(promptAssembly));
        trace.put("tokenCost", buildTokenCostTrace(systemPrompt, userPrompt, generated));
        return trace;
    }

    private Map<String, Object> buildPromptMemoryBlocks(MilvusSearchService.WritingMemory memory,
                                                        PromptAssembly promptAssembly) {
        Map<String, Object> blocks = new LinkedHashMap<>();
        blocks.put("recentChapterContext", buildPromptTraceBlock(memory.getRecentChapters(),
                promptAssembly.selectedIndexes().getOrDefault("recentChapterContext", List.of()), item ->
                String.format("chapter=%s %s", firstNonBlank(item.get("chapter_num")),
                        trimText(firstNonBlank(item.get("summary"), item.get("content"), item.get("title"), "context"), 48))));
        blocks.put("sceneSegments", buildPromptTraceBlock(memory.getSegments(),
                promptAssembly.selectedIndexes().getOrDefault("sceneSegments", List.of()), item ->
                trimText(firstNonBlank(item.get("content"), item.get("segment_type"), "segment"), 60)));
        blocks.put("unresolvedHooks", buildPromptTraceBlock(memory.getHooks(),
                promptAssembly.selectedIndexes().getOrDefault("unresolvedHooks", List.of()), item ->
                String.format("%s:%s", firstNonBlank(item.get("title"), "hook"),
                        trimText(firstNonBlank(item.get("description"), ""), 48))));
        blocks.put("keyCharacters", buildPromptTraceBlock(memory.getCharacters(),
                promptAssembly.selectedIndexes().getOrDefault("keyCharacters", List.of()), item ->
                String.format("%s:%s", firstNonBlank(item.get("name"), "character"),
                        trimText(firstNonBlank(item.get("char_text"), item.get("description"), ""), 48))));
        blocks.put("items", buildPromptTraceBlock(memory.getItems(),
                promptAssembly.selectedIndexes().getOrDefault("items", List.of()), item ->
                String.format("%s:%s", firstNonBlank(item.get("name"), "item"),
                        trimText(firstNonBlank(item.get("item_text"), item.get("description"), ""), 40))));
        blocks.put("factions", buildPromptTraceBlock(memory.getFactions(),
                promptAssembly.selectedIndexes().getOrDefault("factions", List.of()), item ->
                String.format("%s:%s", firstNonBlank(item.get("title"), "faction"),
                        trimText(firstNonBlank(item.get("content"), item.get("description"), ""), 40))));
        blocks.put("relations", buildPromptTraceBlock(memory.getRelations(),
                promptAssembly.selectedIndexes().getOrDefault("relations", List.of()), item ->
                String.format("%s-%s-%s", firstNonBlank(item.get("source_name"), "source"),
                        firstNonBlank(item.get("relation_type"), "relation"),
                        firstNonBlank(item.get("target_name"), "target"))));
        return blocks;
    }

    private Map<String, Object> buildPromptTraceBlock(List<Map<String, Object>> items,
                                                      List<Integer> selectedIndexes,
                                                      java.util.function.Function<Map<String, Object>, String> formatter) {
        int totalCount = items == null ? 0 : items.size();
        List<Integer> validIndexes = items == null ? List.of() : selectedIndexes.stream()
                .filter(index -> index >= 0 && index < items.size())
                .toList();
        int usedCount = validIndexes.size();
        List<String> samples = validIndexes.stream()
                .map(items::get)
                .map(formatter)
                .map(value -> trimText(firstNonBlank(value), 80))
                .toList();

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("retrievedCount", totalCount);
        block.put("usedInPrompt", usedCount);
        block.put("omittedCount", Math.max(totalCount - usedCount, 0));
        block.put("samples", samples);
        return block;
    }

    private Map<String, Object> buildPromptBudgetTrace(PromptAssembly promptAssembly) {
        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("inputTokenBudget", promptAssembly.inputTokenBudget());
        budget.put("systemTokenBudget", promptAssembly.systemTokenBudget());
        budget.put("estimatedSystemTokens", promptAssembly.systemPromptTokens());
        budget.put("estimatedUserTokens", promptAssembly.userPromptTokens());
        budget.put("estimatedInputTokens", promptAssembly.systemPromptTokens() + promptAssembly.userPromptTokens());
        budget.put("remainingInputTokens", Math.max(0,
                promptAssembly.inputTokenBudget() - promptAssembly.systemPromptTokens() - promptAssembly.userPromptTokens()));
        budget.put("candidateMemoryTokens", promptAssembly.candidateMemoryTokens());
        budget.put("selectedMemoryTokens", promptAssembly.selectedMemoryTokens());
        budget.put("savedMemoryTokens", Math.max(0,
                promptAssembly.candidateMemoryTokens() - promptAssembly.selectedMemoryTokens()));
        budget.put("duplicateDroppedCount", promptAssembly.duplicateDroppedCount());
        budget.put("budgetDroppedCount", promptAssembly.budgetDroppedCount());
        budget.put("limitDroppedCount", promptAssembly.limitDroppedCount());
        return budget;
    }

    private List<Map<String, Object>> collectDroppedCandidates(Map<String, Object> selectedMemoryBlocks) {
        List<Map<String, Object>> dropped = new ArrayList<>();
        for (Map.Entry<String, Object> entry : selectedMemoryBlocks.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> block)) {
                continue;
            }
            Object omittedValue = block.get("omittedCount");
            int omittedCount = omittedValue instanceof Number number ? number.intValue() : 0;
            if (omittedCount <= 0) {
                continue;
            }
            Map<String, Object> droppedItem = new LinkedHashMap<>();
            droppedItem.put("block", entry.getKey());
            droppedItem.put("omittedCount", omittedCount);
            dropped.add(droppedItem);
        }
        return dropped;
    }

    private Map<String, Object> buildContextStats(String worldSetting,
                                                  String systemPrompt,
                                                  String userPrompt,
                                                  String generated) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("worldSettingChars", worldSetting == null ? 0 : worldSetting.length());
        stats.put("systemPromptChars", systemPrompt == null ? 0 : systemPrompt.length());
        stats.put("userPromptChars", userPrompt == null ? 0 : userPrompt.length());
        stats.put("totalPromptChars", (systemPrompt == null ? 0 : systemPrompt.length()) + (userPrompt == null ? 0 : userPrompt.length()));
        stats.put("generatedChars", generated == null ? 0 : generated.length());
        return stats;
    }

    private Map<String, Object> buildTokenCostTrace(String systemPrompt,
                                                    String userPrompt,
                                                    String generated) {
        Map<String, Object> trace = new LinkedHashMap<>();
        TokenCostService.SettingsSnapshot settings = tokenCostService.getSettingsSnapshot();
        String fullPrompt = ChatPromptSupport.toEstimationText(systemPrompt, userPrompt);
        int estimatedInputTokens = tokenCostService.estimateTokens(fullPrompt);
        int reservedOutputTokens = settings == null ? 0 : Math.max(settings.getReservedCompletionTokens(), 0);

        trace.put("currency", settings == null ? "USD" : firstNonBlank(settings.getCurrency(), "USD"));
        trace.put("measurementMode", "token_cost_service_record + heuristic_fallback");
        trace.put("pricing", buildPricingTrace(settings));
        trace.put("preCallEstimate", buildTokenSnapshot(estimatedInputTokens, reservedOutputTokens, settings, "reservation_estimate"));

        TokenCostService.UsageRecord usageRecord = tokenCostService.getRecentRecords(5).stream()
                .filter(record -> "chat.generate".equals(record.getSource()))
                .findFirst()
                .orElse(null);

        if (usageRecord != null) {
            Map<String, Object> actualUsage = new LinkedHashMap<>();
            actualUsage.put("requestId", usageRecord.getRequestId());
            actualUsage.put("status", usageRecord.getStatus());
            actualUsage.put("provider", usageRecord.getProvider());
            actualUsage.put("model", usageRecord.getModel());
            actualUsage.put("inputTokens", usageRecord.getInputTokens());
            actualUsage.put("outputTokens", usageRecord.getOutputTokens());
            actualUsage.put("totalTokens", usageRecord.getTotalTokens());
            actualUsage.put("estimatedCostUsd", usageRecord.getEstimatedCostUsd());
            actualUsage.put("charCount", usageRecord.getCharCount());
            actualUsage.put("measurementMode", "recorded_by_token_cost_service");
            trace.put("postCallObservation", actualUsage);
        } else {
            int observedOutputTokens = tokenCostService.estimateTokens(generated == null ? "" : generated);
            trace.put("postCallObservation", buildTokenSnapshot(estimatedInputTokens, observedOutputTokens, settings, "heuristic_fallback"));
        }
        return trace;
    }

    private Map<String, Object> buildPricingTrace(TokenCostService.SettingsSnapshot settings) {
        Map<String, Object> pricing = new LinkedHashMap<>();
        if (settings == null) {
            pricing.put("inputPerMillionTokens", 0D);
            pricing.put("outputPerMillionTokens", 0D);
            pricing.put("reservedCompletionTokens", 0);
            return pricing;
        }
        pricing.put("inputPerMillionTokens", settings.getInputPerMillionTokens());
        pricing.put("outputPerMillionTokens", settings.getOutputPerMillionTokens());
        pricing.put("reservedCompletionTokens", settings.getReservedCompletionTokens());
        return pricing;
    }

    private Map<String, Object> buildTokenSnapshot(int inputTokens,
                                                   int outputTokens,
                                                   TokenCostService.SettingsSnapshot settings,
                                                   String mode) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("inputTokens", inputTokens);
        snapshot.put("outputTokens", outputTokens);
        snapshot.put("totalTokens", inputTokens + outputTokens);
        snapshot.put("estimatedCostUsd", roundCost(calculateChatCostUsd(settings, inputTokens, outputTokens)));
        snapshot.put("measurementMode", mode);
        return snapshot;
    }

    private double calculateChatCostUsd(TokenCostService.SettingsSnapshot settings,
                                        int inputTokens,
                                        int outputTokens) {
        if (settings == null) {
            return 0D;
        }
        return inputTokens / 1_000_000D * settings.getInputPerMillionTokens()
                + outputTokens / 1_000_000D * settings.getOutputPerMillionTokens();
    }

    private double roundCost(double value) {
        return Math.round(value * 10000D) / 10000D;
    }

    private ResponseEntity<Map<String, Object>> generationResponse(GenerationContext context,
                                                                    String generated,
                                                                    boolean degraded,
                                                                    Map<String, Object> degradationPolicy) {
        return ResponseEntity.ok(buildGenerationResponse(context, generated, degraded, degradationPolicy));
    }

    private ResponseEntity<Map<String, Object>> degradedGenerationResponse(GenerationContext context,
                                                                            boolean degradationEnabled,
                                                                            String trigger,
                                                                            String outlineMode,
                                                                            RuntimeException exception) {
        if (!degradationEnabled) {
            throw exception;
        }
        tokenCostService.recordDegradation(
                trigger,
                "outline_only_response",
                exception.getMessage(),
                context.novelId(),
                "controller",
                "outline",
                "chat.generate"
        );
        String degradedContent = buildDegradedOutline(
                context.topic(),
                context.style(),
                context.currentChapterNum(),
                context.memory(),
                outlineMode
        );
        return generationResponse(
                context,
                degradedContent,
                true,
                buildDegradationPolicy(trigger.toLowerCase(Locale.ROOT), "outline_only_response", exception.getMessage())
        );
    }

    private Map<String, Object> buildGenerationResponse(GenerationContext context,
                                                         String generated,
                                                         boolean degraded,
                                                         Map<String, Object> degradationPolicy) {
        Map<String, Object> result = new HashMap<>();
        result.put("topic", context.topic());
        result.put("style", context.style());
        result.put("currentChapterNum", context.currentChapterNum());
        result.put("content", generated);
        result.put("memoryCount", context.memory().getTotalCount());
        result.put("memory", buildMemorySummary(context.memory()));
        result.put("memoryLayers", buildMemoryLayers(context.memory()));
        result.put("consistencyCheck", buildConsistencyCheck(
                context.novelId(), context.memory(), context.currentChapterNum()));
        result.put("generationTrace", buildGenerationTrace(
                context.promptId(),
                context.topic(),
                context.style(),
                context.worldSetting(),
                context.memory(),
                context.systemPrompt(),
                context.userPrompt(),
                generated,
                context.promptAssembly()));
        result.put("postGenerationCheck", buildPostGenerationCheck(context.topic(), generated));
        result.put("promptChars", context.systemPrompt().length() + context.userPrompt().length());
        result.put("degraded", degraded);
        if (degradationPolicy != null) {
            result.put("degradationPolicy", degradationPolicy);
        }
        return result;
    }

    private record GenerationContext(Long novelId,
                                     String topic,
                                     String style,
                                     Integer currentChapterNum,
                                     String promptId,
                                     String worldSetting,
                                     MilvusSearchService.WritingMemory memory,
                                     String systemPrompt,
                                     String userPrompt,
                                     PromptAssembly promptAssembly) {
    }

    private Map<String, Object> buildDegradationPolicy(String trigger, String strategy, String reason) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("trigger", trigger);
        policy.put("strategy", strategy);
        policy.put("reason", reason == null ? "unknown" : reason);
        policy.put("fallbackOutput", "outline_only");
        return policy;
    }

    private String buildDegradedOutline(String topic,
                                        String style,
                                        Integer currentChapterNum,
                                        MilvusSearchService.WritingMemory memory,
                                        String mode) {
        List<String> anchors = new ArrayList<>();
        collectAnchorSamples(memory.getRecentChapters(), "chapter", anchors, "content");
        collectAnchorSamples(memory.getSegments(), "segment", anchors, "content");
        collectAnchorSamples(memory.getHooks(), "hook", anchors, "title");
        collectAnchorSamples(memory.getCharacters(), "character", anchors, "name");
        collectAnchorSamples(memory.getItems(), "item", anchors, "name");
        if (anchors.isEmpty()) {
            anchors.add("keep the current conflict focused on the topic");
        }

        StringBuilder builder = new StringBuilder();
        builder.append("[DEGRADED MODE: ").append(mode).append("]\n");
        builder.append("Topic: ").append(topic).append("\n");
        builder.append("Style: ").append(style).append("\n");
        builder.append("Current chapter: ").append(currentChapterNum == null ? "unknown" : currentChapterNum).append("\n");
        builder.append("Recommended outline:\n");
        for (int i = 0; i < Math.min(5, anchors.size()); i++) {
            builder.append(i + 1).append(". ").append(anchors.get(i)).append("\n");
        }
        builder.append("Closing goal: end the scene with a hook tied to the topic.");
        return builder.toString();
    }

    private void collectAnchorSamples(List<Map<String, Object>> blocks,
                                      String label,
                                      List<String> anchors,
                                      String field) {
        if (blocks == null) {
            return;
        }
        for (Map<String, Object> block : blocks) {
            if (block == null) {
                continue;
            }
            Object value = block.get(field);
            if (value == null) {
                continue;
            }
            String text = value.toString().trim();
            if (text.isEmpty()) {
                continue;
            }
            anchors.add(label + ": " + text);
            if (anchors.size() >= 8) {
                return;
            }
        }
    }

    private Map<String, Object> buildPostGenerationCheck(String topic, String generated) {
        String content = generated == null ? "" : generated.trim();
        List<String> warnings = new ArrayList<>();

        if (content.isEmpty()) {
            warnings.add("empty_output");
        }
        if (!content.isEmpty() && content.length() < 80) {
            warnings.add("content_too_short");
        }
        if (countOccurrences(content, "...") + countOccurrences(content, "……") >= 4) {
            warnings.add("excessive_ellipsis");
        }

        for (String phrase : List.of("suddenly", "all of a sudden", "without warning")) {
            if (content.toLowerCase(Locale.ROOT).contains(phrase)) {
                warnings.add("banned_phrase:" + phrase.replace(' ', '_'));
            }
        }

        if (hasRepeatedOpening(content)) {
            warnings.add("repeated_opening_pattern");
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("topic", topic);
        metrics.put("charCount", content.length());
        metrics.put("paragraphCount", countParagraphs(content));
        metrics.put("lineCount", content.isEmpty() ? 0 : content.split("\\R").length);
        metrics.put("sentenceCount", countSentences(content));

        Map<String, Object> check = new LinkedHashMap<>();
        check.put("status", warnings.isEmpty() ? "pass" : "warn");
        check.put("warningCount", warnings.size());
        check.put("warnings", warnings);
        check.put("metrics", metrics);
        return check;
    }

    private boolean hasRepeatedOpening(String content) {
        List<String> normalizedLines = Arrays.stream(content.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .map(line -> line.substring(0, Math.min(line.length(), 16)).toLowerCase(Locale.ROOT))
                .toList();
        Set<String> seen = new HashSet<>();
        for (String opening : normalizedLines) {
            if (!seen.add(opening)) {
                return true;
            }
        }
        return false;
    }

    private int countParagraphs(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        return (int) Arrays.stream(content.split("\\R\\s*\\R"))
                .map(String::trim)
                .filter(part -> !part.isBlank())
                .count();
    }

    private int countSentences(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        return (int) Arrays.stream(content.split("[.!?。！？]+"))
                .map(String::trim)
                .filter(part -> !part.isBlank())
                .count();
    }

    private int countOccurrences(String content, String target) {
        if (content == null || content.isEmpty() || target == null || target.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(target, index)) >= 0) {
            count++;
            index += target.length();
        }
        return count;
    }
    private Map<String, Object> buildLayer(int count, List<String> samples) {
        Map<String, Object> layer = new LinkedHashMap<>();
        layer.put("count", count);
        layer.put("samples", samples);
        return layer;
    }

    private List<String> collectValues(List<Map<String, Object>> items, String field, int limit) {
        return items.stream()
                .map(item -> firstNonBlank(item.get(field)))
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(limit)
                .toList();
    }

    private List<String> collectWorldFacts(MilvusSearchService.WritingMemory memory) {
        List<String> facts = new ArrayList<>();
        facts.addAll(collectValues(memory.getItems(), "name", 2));
        facts.addAll(collectValues(memory.getFactions(), "title", 2));
        facts.addAll(memory.getRelations().stream()
                .map(item -> firstNonBlank(item.get("source_name")) + "-" + firstNonBlank(item.get("relation_type")) + "-" + firstNonBlank(item.get("target_name")))
                .filter(value -> !value.isBlank() && !value.equals("--"))
                .distinct()
                .limit(2)
                .toList());
        return facts.stream().filter(value -> !value.isBlank()).limit(4).toList();
    }

    private void collectFutureChapterWarnings(List<String> warnings,
                                              String section,
                                              List<Map<String, Object>> items,
                                              Integer currentChapterNum) {
        if (currentChapterNum == null) {
            return;
        }
        boolean leaked = items.stream()
                .map(item -> item.get("chapter_num"))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .map(value -> {
                    try {
                        return Integer.parseInt(value);
                    } catch (NumberFormatException ex) {
                        return Integer.MIN_VALUE;
                    }
                })
                .anyMatch(chapterNum -> chapterNum > currentChapterNum);
        if (leaked) {
            warnings.add("future_chapter_leak:" + section);
        }
    }

    private List<String> collectDuplicateValues(List<Map<String, Object>> items, String field) {
        Map<String, Long> counts = items.stream()
                .map(item -> firstNonBlank(item.get(field)))
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.groupingBy(value -> value, LinkedHashMap::new, java.util.stream.Collectors.counting()));
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();
    }

    private List<String> collectRelationConflicts(List<Map<String, Object>> relations) {
        Map<String, Set<String>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> relation : relations) {
            String source = firstNonBlank(relation.get("source_name"));
            String target = firstNonBlank(relation.get("target_name"));
            String relationType = firstNonBlank(relation.get("relation_type"));
            if (source.isBlank() || target.isBlank() || relationType.isBlank()) {
                continue;
            }
            String pairKey = source + "->" + target;
            grouped.computeIfAbsent(pairKey, key -> new LinkedHashSet<>()).add(relationType);
        }
        return grouped.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(Map.Entry::getKey)
                .toList();
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            if (value != null) {
                String text = value.toString().trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return "";
    }

    private String trimText(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text == null ? "" : text;
        }
        return text.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}
