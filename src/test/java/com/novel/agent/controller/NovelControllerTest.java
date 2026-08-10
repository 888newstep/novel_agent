package com.novel.agent.controller;

import com.novel.agent.entity.Artifact;
import com.novel.agent.entity.ItemLog;
import com.novel.agent.entity.KeyEvent;
import com.novel.agent.entity.Novel;
import com.novel.agent.repository.ArtifactRepository;
import com.novel.agent.repository.ChapterRepository;
import com.novel.agent.repository.CharacterRepository;
import com.novel.agent.repository.InspirationRepository;
import com.novel.agent.repository.ItemLogRepository;
import com.novel.agent.repository.KeyEventRepository;
import com.novel.agent.repository.NovelRepository;
import com.novel.agent.repository.SkillRepository;
import com.novel.agent.service.DeepSeekService;
import com.novel.agent.service.MilvusAdminService;
import com.novel.agent.service.MilvusSearchService;
import com.novel.agent.service.MilvusService;
import com.novel.agent.service.TokenCostService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NovelControllerTest {

    @Test
    void previewMemoryReturnsLayersAndConsistencyWarnings() {
        NovelController controller = new NovelController(
                mock(NovelRepository.class),
                mock(ChapterRepository.class),
                mock(KeyEventRepository.class),
                mock(InspirationRepository.class),
                mock(CharacterRepository.class),
                mock(ArtifactRepository.class),
                mock(SkillRepository.class),
                mock(ItemLogRepository.class),
                mock(DeepSeekService.class),
                mock(MilvusService.class),
                mock(MilvusSearchService.class),
                mock(MilvusAdminService.class),
                mock(TokenCostService.class)
        );

        MilvusSearchService milvusSearchService = extractSearchService(controller);
        MilvusSearchService.WritingMemory memory = MilvusSearchService.WritingMemory.builder()
                .query("trial arc")
                .currentChapterNum(10)
                .recentChapters(List.of(
                        mapOf("chapter_num", 8, "content", "past recap"),
                        mapOf("chapter_num", 12, "content", "future leak")
                ))
                .segments(List.of(
                        mapOf("chapter_num", 9, "segment_type", "battle", "content", "mountain gate battle")
                ))
                .hooks(List.of(
                        mapOf("chapter_num", 10, "title", "destiny clue"),
                        mapOf("chapter_num", 10, "title", "destiny clue")
                ))
                .characters(List.of(
                        mapOf("name", "Lin Zhou"),
                        mapOf("name", "Lin Zhou")
                ))
                .items(List.of(mapOf("name", "Black Iron Token")))
                .factions(List.of(mapOf("title", "Heaven Pavilion")))
                .relations(List.of(
                        mapOf("source_name", "Lin Zhou", "relation_type", "ally", "target_name", "Su Li"),
                        mapOf("source_name", "Lin Zhou", "relation_type", "enemy", "target_name", "Su Li")
                ))
                .totalCount(10)
                .build();

        when(milvusSearchService.buildWritingMemory(1L, "trial arc", 10)).thenReturn(memory);

        ResponseEntity<Map<String, Object>> response = controller.previewMemory(1L, "trial arc", 10);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).containsKeys("memoryLayers", "consistencyCheck");

        @SuppressWarnings("unchecked")
        Map<String, Object> memoryLayers = (Map<String, Object>) response.getBody().get("memoryLayers");
        @SuppressWarnings("unchecked")
        Map<String, Object> keyCharacters = (Map<String, Object>) memoryLayers.get("keyCharacters");
        assertThat(keyCharacters.get("count")).isEqualTo(2);
        assertThat(keyCharacters.get("samples")).isEqualTo(List.of("Lin Zhou"));

        @SuppressWarnings("unchecked")
        Map<String, Object> consistencyCheck = (Map<String, Object>) response.getBody().get("consistencyCheck");
        assertThat(consistencyCheck.get("status")).isEqualTo("warn");
        assertThat(consistencyCheck.get("warningCount")).isEqualTo(4);
        assertThat((List<String>) consistencyCheck.get("warnings"))
                .contains("future_chapter_leak:recentChapters")
                .contains("duplicate_character_context:Lin Zhou")
                .contains("duplicate_hook_context:destiny clue")
                .contains("relation_conflict:Lin Zhou->Su Li");
    }

    @Test
    void generateChapterIncludesTraceTokenUsageAndAdvancedConsistencyWarnings() {
        NovelRepository novelRepository = mock(NovelRepository.class);
        KeyEventRepository keyEventRepository = mock(KeyEventRepository.class);
        ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
        SkillRepository skillRepository = mock(SkillRepository.class);
        ItemLogRepository itemLogRepository = mock(ItemLogRepository.class);
        DeepSeekService deepSeekService = mock(DeepSeekService.class);
        MilvusSearchService milvusSearchService = mock(MilvusSearchService.class);
        TokenCostService tokenCostService = mock(TokenCostService.class);

        NovelController controller = new NovelController(
                novelRepository,
                mock(ChapterRepository.class),
                keyEventRepository,
                mock(InspirationRepository.class),
                mock(CharacterRepository.class),
                artifactRepository,
                skillRepository,
                itemLogRepository,
                deepSeekService,
                mock(MilvusService.class),
                milvusSearchService,
                mock(MilvusAdminService.class),
                tokenCostService
        );

        Novel novel = Novel.builder().id(2L).worldSetting("cultivation dynasty").build();
        MilvusSearchService.WritingMemory memory = MilvusSearchService.WritingMemory.builder()
                .query("trial arc")
                .currentChapterNum(10)
                .recentChapters(List.of(
                        mapOf("chapter_num", 8, "summary", "mentor warning"),
                        mapOf("chapter_num", 9, "summary", "arrival at sect")
                ))
                .segments(List.of(
                        mapOf("chapter_num", 10, "segment_type", "dialogue", "content", "segment one"),
                        mapOf("chapter_num", 10, "segment_type", "battle", "content", "segment two"),
                        mapOf("chapter_num", 10, "segment_type", "battle", "content", "segment three"),
                        mapOf("chapter_num", 10, "segment_type", "emotion", "content", "segment four")
                ))
                .hooks(List.of(
                        mapOf("mysql_event_id", 501L, "chapter_num", 10, "title", "resolved oath", "description", "old unresolved oath"),
                        mapOf("chapter_num", 10, "title", "hook two", "description", "second hook"),
                        mapOf("chapter_num", 10, "title", "hook three", "description", "third hook")
                ))
                .characters(List.of(
                        mapOf("name", "Lin Zhou"),
                        mapOf("name", "Su Li"),
                        mapOf("name", "Elder Mo"),
                        mapOf("name", "Guard Han")
                ))
                .items(List.of(
                        mapOf("mysql_item_id", 301L, "item_type", 0, "name", "Trial Token", "item_text", "entry pass"),
                        mapOf("mysql_item_id", 302L, "item_type", 0, "name", "Future Seal", "item_text", "sealed relic")
                ))
                .factions(List.of(mapOf("title", "Azure Peak", "content", "outer mountain faction")))
                .relations(List.of(
                        mapOf("source_name", "Lin Zhou", "relation_type", "ally", "target_name", "Su Li"),
                        mapOf("source_name", "Lin Zhou", "relation_type", "mentor", "target_name", "Elder Mo"),
                        mapOf("source_name", "Guard Han", "relation_type", "rival", "target_name", "Lin Zhou")
                ))
                .totalCount(17)
                .build();

        TokenCostService.SettingsSnapshot settings = TokenCostService.SettingsSnapshot.builder()
                .enabled(true)
                .strictMode(false)
                .recentRecords(100)
                .maxEstimatedTokensPerRequest(12000)
                .reservedCompletionTokens(1200)
                .dailyTokenBudget(300000)
                .monthlyTokenBudget(5000000)
                .dailyBudgetUsd(5.0)
                .monthlyBudgetUsd(100.0)
                .currency("USD")
                .inputPerMillionTokens(1.0)
                .outputPerMillionTokens(2.0)
                .embeddingPerMillionTokens(0.5)
                .build();

        TokenCostService.UsageRecord usageRecord = TokenCostService.UsageRecord.builder()
                .requestId("req-1")
                .status("SUCCESS")
                .provider("deepseek")
                .model("deepseek-chat")
                .source("chat.generate")
                .inputTokens(320)
                .outputTokens(180)
                .totalTokens(500)
                .estimatedCostUsd(0.0007)
                .charCount(1400)
                .build();

        KeyEvent resolvedEvent = KeyEvent.builder()
                .id(501L)
                .novelId(2L)
                .chapterNum(6)
                .eventType("foreshadowing")
                .title("resolved oath")
                .description("already settled")
                .resolved(true)
                .resolvedAt(9)
                .build();

        Artifact activeArtifactWithFutureMutation = Artifact.builder()
                .id(301L)
                .novelId(2L)
                .name("Trial Token")
                .status("active")
                .firstAppear(3)
                .build();

        Artifact inactiveFutureArtifact = Artifact.builder()
                .id(302L)
                .novelId(2L)
                .name("Future Seal")
                .status("destroyed")
                .firstAppear(12)
                .build();

        ItemLog futureMutation = ItemLog.builder()
                .novelId(2L)
                .itemType("artifact")
                .itemId(301L)
                .chapterNum(13)
                .action("destroyed")
                .build();

        when(novelRepository.findById(2L)).thenReturn(Optional.of(novel));
        when(milvusSearchService.buildWritingMemory(2L, "trial arc", 10)).thenReturn(memory);
        when(deepSeekService.chat(eq(2L), anyString(), anyString())).thenReturn("generated chapter content");
        when(tokenCostService.getSettingsSnapshot()).thenReturn(settings);
        when(tokenCostService.estimateTokens(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0, String.class);
            return Math.max(1, text.length() / 4);
        });
        when(tokenCostService.getRecentRecords(5)).thenReturn(List.of(usageRecord));
        when(keyEventRepository.findByNovelIdOrderByChapterNumAsc(2L)).thenReturn(List.of(resolvedEvent));
        when(artifactRepository.findById(301L)).thenReturn(Optional.of(activeArtifactWithFutureMutation));
        when(artifactRepository.findById(302L)).thenReturn(Optional.of(inactiveFutureArtifact));
        when(skillRepository.findById(301L)).thenReturn(Optional.empty());
        when(skillRepository.findById(302L)).thenReturn(Optional.empty());
        when(itemLogRepository.findByNovelIdAndItemTypeAndItemId(2L, "artifact", 301L)).thenReturn(List.of(futureMutation));
        when(itemLogRepository.findByNovelIdAndItemTypeAndItemId(2L, "artifact", 302L)).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.generateChapter(2L, "trial arc", "hot-blooded", "1A", 10);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).containsKeys("content", "memoryLayers", "consistencyCheck", "generationTrace", "postGenerationCheck");

        @SuppressWarnings("unchecked")
        Map<String, Object> generationTrace = (Map<String, Object>) response.getBody().get("generationTrace");
        @SuppressWarnings("unchecked")
        Map<String, Object> selectedBlocks = (Map<String, Object>) generationTrace.get("selectedMemoryBlocks");
        @SuppressWarnings("unchecked")
        Map<String, Object> sceneSegments = (Map<String, Object>) selectedBlocks.get("sceneSegments");
        assertThat(sceneSegments.get("usedInPrompt")).isEqualTo(3);
        assertThat(sceneSegments.get("omittedCount")).isEqualTo(1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> droppedCandidates = (List<Map<String, Object>>) generationTrace.get("droppedCandidates");
        assertThat(droppedCandidates)
                .anySatisfy(item -> {
                    assertThat(item.get("block")).isEqualTo("sceneSegments");
                    assertThat(item.get("omittedCount")).isEqualTo(1);
                })
                .anySatisfy(item -> {
                    assertThat(item.get("block")).isEqualTo("unresolvedHooks");
                    assertThat(item.get("omittedCount")).isEqualTo(1);
                });

        @SuppressWarnings("unchecked")
        Map<String, Object> tokenCost = (Map<String, Object>) generationTrace.get("tokenCost");
        @SuppressWarnings("unchecked")
        Map<String, Object> preCallEstimate = (Map<String, Object>) tokenCost.get("preCallEstimate");
        @SuppressWarnings("unchecked")
        Map<String, Object> postCallObservation = (Map<String, Object>) tokenCost.get("postCallObservation");
        assertThat(preCallEstimate.get("outputTokens")).isEqualTo(1200);
        assertThat(postCallObservation.get("provider")).isEqualTo("deepseek");
        assertThat(postCallObservation.get("totalTokens")).isEqualTo(500);

        @SuppressWarnings("unchecked")
        Map<String, Object> postGenerationCheck = (Map<String, Object>) response.getBody().get("postGenerationCheck");
        assertThat(postGenerationCheck.get("status")).isEqualTo("warn");
        assertThat((List<String>) postGenerationCheck.get("warnings")).contains("content_too_short");

        @SuppressWarnings("unchecked")
        Map<String, Object> consistencyCheck = (Map<String, Object>) response.getBody().get("consistencyCheck");
        assertThat(consistencyCheck.get("status")).isEqualTo("warn");
        assertThat((List<String>) consistencyCheck.get("warnings"))
                .contains("resolved_event_reused:resolved oath")
                .contains("future_item_mutation:Trial Token")
                .contains("future_item_first_appear:Future Seal")
                .contains("item_status_conflict:Future Seal:destroyed");

        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) consistencyCheck.get("metrics");
        assertThat(metrics.get("resolvedEventReuseCount")).isEqualTo(1);
        assertThat(metrics.get("futureItemLeakCount")).isEqualTo(1);
        assertThat(metrics.get("itemStatusConflictCount")).isEqualTo(1);
        assertThat(metrics.get("futureItemMutationCount")).isEqualTo(1);

        when(deepSeekService.chat(eq(2L), anyString(), anyString())).thenReturn(
                "Lin Zhou entered the silent corridor and stopped beside the broken stone gate. " +
                        "He remembered the mentor warning, confirmed that the trial token was still active, " +
                        "and left the unresolved oath as the scene hook for the next chapter."
        );

        ResponseEntity<Map<String, Object>> cleanResponse = controller.generateChapter(
                2L, "trial arc", "hot-blooded", "1A", 10
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> cleanPostGenerationCheck =
                (Map<String, Object>) cleanResponse.getBody().get("postGenerationCheck");
        assertThat(cleanPostGenerationCheck.get("status")).isEqualTo("pass");
        assertThat(cleanPostGenerationCheck.get("warningCount")).isEqualTo(0);
    }


    @Test
    void generateChapterFallsBackToOutlineWhenBudgetIsBlocked() {
        NovelRepository novelRepository = mock(NovelRepository.class);
        ChapterRepository chapterRepository = mock(ChapterRepository.class);
        KeyEventRepository keyEventRepository = mock(KeyEventRepository.class);
        InspirationRepository inspirationRepository = mock(InspirationRepository.class);
        CharacterRepository characterRepository = mock(CharacterRepository.class);
        ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
        SkillRepository skillRepository = mock(SkillRepository.class);
        ItemLogRepository itemLogRepository = mock(ItemLogRepository.class);
        DeepSeekService deepSeekService = mock(DeepSeekService.class);
        MilvusService milvusService = mock(MilvusService.class);
        MilvusSearchService milvusSearchService = mock(MilvusSearchService.class);
        MilvusAdminService milvusAdminService = mock(MilvusAdminService.class);
        TokenCostService tokenCostService = mock(TokenCostService.class);

        NovelController controller = new NovelController(
                novelRepository,
                chapterRepository,
                keyEventRepository,
                inspirationRepository,
                characterRepository,
                artifactRepository,
                skillRepository,
                itemLogRepository,
                deepSeekService,
                milvusService,
                milvusSearchService,
                milvusAdminService,
                tokenCostService
        );

        Novel novel = Novel.builder().id(3L).title("Budget Trial").worldSetting("sects and ruins").build();
        MilvusSearchService.WritingMemory memory = MilvusSearchService.WritingMemory.builder()
                .query("budget arc")
                .currentChapterNum(7)
                .recentChapters(List.of())
                .segments(List.of(mapOf("content", "ruins ambush")))
                .hooks(List.of(mapOf("title", "hidden key")))
                .characters(List.of(mapOf("name", "Lin Yue")))
                .items(List.of())
                .factions(List.of())
                .relations(List.of())
                .totalCount(3)
                .build();
        TokenCostService.SettingsSnapshot settings = TokenCostService.SettingsSnapshot.builder()
                .enabled(true)
                .strictMode(true)
                .degradeOnBudgetExceeded(true)
                .degradeOnModelFailure(true)
                .build();

        when(novelRepository.findById(3L)).thenReturn(Optional.of(novel));
        when(milvusSearchService.buildWritingMemory(3L, "budget arc", 7)).thenReturn(memory);
        when(deepSeekService.chat(eq(3L), anyString(), anyString())).thenThrow(new com.novel.agent.exception.CostLimitExceededException("daily token budget exceeded"));
        when(tokenCostService.getSettingsSnapshot()).thenReturn(settings);
        when(tokenCostService.estimateTokens(anyString())).thenReturn(100);
        when(tokenCostService.getRecentRecords(5)).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.generateChapter(3L, "budget arc", "steady", "1A", 7);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().get("degraded")).isEqualTo(true);
        assertThat(response.getBody()).containsKey("degradationPolicy");
        assertThat(response.getBody().get("content").toString()).contains("[DEGRADED MODE: budget_limit]");

        @SuppressWarnings("unchecked")
        Map<String, Object> degradationPolicy = (Map<String, Object>) response.getBody().get("degradationPolicy");
        assertThat(degradationPolicy.get("trigger")).isEqualTo("budget_limit");
        assertThat(degradationPolicy.get("strategy")).isEqualTo("outline_only_response");
    }

    private static MilvusSearchService extractSearchService(NovelController controller) {
        try {
            var field = NovelController.class.getDeclaredField("milvusSearchService");
            field.setAccessible(true);
            return (MilvusSearchService) field.get(controller);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            map.put(String.valueOf(values[i]), values[i + 1]);
        }
        return map;
    }
}
