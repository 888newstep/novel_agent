package com.novel.agent.controller;

import com.novel.agent.entity.*;
import com.novel.agent.repository.*;
import com.novel.agent.service.DeepSeekService;
import com.novel.agent.service.MilvusAdminService;
import com.novel.agent.service.MilvusSearchService;
import com.novel.agent.service.MilvusService;
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
    private final DeepSeekService deepSeekService;
    private final MilvusService milvusService;
    private final MilvusSearchService milvusSearchService;
    private final MilvusAdminService milvusAdminService;

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
            @RequestParam(defaultValue = "仙侠") String style,
            @RequestParam(defaultValue = "1A") String promptId) {

        // 获取小说设定
        Novel novel = novelRepository.findById(novelId).orElse(null);
        String worldSetting = novel != null ? novel.getWorldSetting() : "";

        // 检索相关历史情节
        List<Map<String, Object>> context = milvusSearchService.searchSegments(novelId, topic, 3);

        // 检索未回收伏笔
        List<Map<String, Object>> hooks = milvusSearchService.searchUnresolvedEvents(novelId, topic, 3);

        // 构建系统提示词
        String systemPrompt = buildSystemPrompt(style, worldSetting, context, hooks, promptId);

        // 调用 DeepSeek 生成
        String userPrompt = String.format("请以%s的风格，写一篇关于《%s》的文章。", style, topic);
        String generated = deepSeekService.chat(systemPrompt, userPrompt);

        Map<String, Object> result = new HashMap<>();
        result.put("topic", topic);
        result.put("style", style);
        result.put("content", generated);
        result.put("contextCount", context.size());
        result.put("hookCount", hooks.size());
        return ResponseEntity.ok(result);
    }

    // =============================================
    // 向量检索
    // =============================================

    @PostMapping("/{novelId}/search")
    public ResponseEntity<Map<String, Object>> searchSegments(
            @PathVariable Long novelId,
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK) {
        List<Map<String, Object>> results = milvusSearchService.searchSegments(novelId, query, topK);
        Map<String, Object> response = new HashMap<>();
        response.put("query", query);
        response.put("results", results);
        response.put("count", results.size());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{novelId}/search/hooks")
    public ResponseEntity<Map<String, Object>> searchHooks(
            @PathVariable Long novelId,
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK) {
        List<Map<String, Object>> results = milvusSearchService.searchUnresolvedEvents(novelId, query, topK);
        Map<String, Object> response = new HashMap<>();
        response.put("query", query);
        response.put("results", results);
        response.put("count", results.size());
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
        milvusAdminService.buildAllIndexesAsync();
        return ResponseEntity.ok(Map.of("status", "索引构建已异步启动，请通过日志或 Attu 监控进度"));
    }

    /**
     * 加载所有集合到内存
     */
    @PostMapping("/admin/milvus/load")
    public ResponseEntity<Map<String, String>> loadAllCollections() {
        milvusAdminService.loadAllCollections();
        return ResponseEntity.ok(Map.of("status", "所有集合已加载到内存"));
    }

    /**
     * 释放所有集合
     */
    @PostMapping("/admin/milvus/release")
    public ResponseEntity<Map<String, String>> releaseAllCollections() {
        milvusAdminService.releaseCollection("novel_segments");
        milvusAdminService.releaseCollection("novel_events");
        milvusAdminService.releaseCollection("novel_characters");
        milvusAdminService.releaseCollection("novel_items");
        milvusAdminService.releaseCollection("novel_faction_inspire");
        return ResponseEntity.ok(Map.of("status", "所有集合已从内存释放"));
    }

    /**
     * flush 所有集合
     */
    @PostMapping("/admin/milvus/flush")
    public ResponseEntity<Map<String, String>> flushAll() {
        milvusAdminService.flushAll();
        return ResponseEntity.ok(Map.of("status", "所有集合 flush 完成"));
    }

    /**
     * compact 所有集合
     */
    @PostMapping("/admin/milvus/compact")
    public ResponseEntity<Map<String, String>> compactAll() {
        milvusAdminService.compactAll();
        return ResponseEntity.ok(Map.of("status", "所有集合 compact 指令已发出"));
    }

    /**
     * 清空某本小说所有向量（完本重建前调用）
     */
    @DeleteMapping("/admin/milvus/novel/{novelId}")
    public ResponseEntity<Map<String, String>> clearNovelVectors(@PathVariable Long novelId) {
        milvusAdminService.deleteByNovelIdAll(novelId);
        return ResponseEntity.ok(Map.of("status", "小说 " + novelId + " 的所有向量已清空"));
    }

    /**
     * 全量重建完整流程（清空 + 等待写入 + 建索引 + 加载）
     * 注意：调用此接口后，需外部写入新数据，然后调用 /admin/milvus/finalize 完成重建
     */
    @PostMapping("/admin/milvus/novel/{novelId}/rebuild")
    public ResponseEntity<Map<String, String>> rebuildNovel(@PathVariable Long novelId) {
        milvusAdminService.rebuildIndexForNovel(novelId);
        return ResponseEntity.ok(Map.of("status",
                "小说 " + novelId + " 旧向量已清空，请写入新数据后调用 /admin/milvus/finalize 完成建索引+加载"));
    }

    /**
     * 完成重建：flush → 建索引 → compact → load
     */
    @PostMapping("/admin/milvus/finalize")
    public ResponseEntity<Map<String, String>> finalizeRebuild() {
        milvusAdminService.finalizeRebuild();
        return ResponseEntity.ok(Map.of("status", "全量重建完成，所有集合已就绪"));
    }

    // =============================================
    // 辅助方法
    // =============================================

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

    private String buildSystemPrompt(String style, String worldSetting,
                                      List<Map<String, Object>> context,
                                      List<Map<String, Object>> hooks,
                                      String promptId) {
        StringBuilder sb = new StringBuilder();
        sb.append(SYSTEM_PROMPTS.getOrDefault(promptId, SYSTEM_PROMPTS.get("1A")));

        if (worldSetting != null && !worldSetting.isEmpty()) {
            sb.append("\n\n【小说世界观设定】\n").append(worldSetting);
        }

        if (!context.isEmpty()) {
            sb.append("\n\n【相关历史情节（供参考）】\n");
            for (int i = 0; i < context.size(); i++) {
                sb.append("[").append(i + 1).append("] ").append(context.get(i).get("content")).append("\n");
            }
        }

        if (!hooks.isEmpty()) {
            sb.append("\n\n【未回收伏笔（请考虑在创作中回收）】\n");
            for (int i = 0; i < hooks.size(); i++) {
                sb.append("[").append(i + 1).append("] ").append(hooks.get(i).get("title"))
                        .append("：").append(hooks.get(i).get("description")).append("\n");
            }
        }

        return sb.toString();
    }
}