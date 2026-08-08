package com.novel.agent.service;

import com.novel.agent.config.RetrievalProperties;
import com.novel.agent.entity.KeyEvent;
import com.novel.agent.repository.ChapterRepository;
import com.novel.agent.repository.KeyEventRepository;
import com.novel.agent.repository.RelationRepository;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.response.SearchResp;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MilvusSearchServiceTest {

    @Test
    void ranksRelevantChapterAndExplainsWhyItWasReturned() {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        ChapterRepository chapterRepository = mock(ChapterRepository.class);
        KeyEventRepository keyEventRepository = mock(KeyEventRepository.class);
        RelationRepository relationRepository = mock(RelationRepository.class);
        RetrievalProperties retrievalProperties = new RetrievalProperties();
        MilvusSearchService service = new MilvusSearchService(
                milvusClient,
                embeddingService,
                chapterRepository,
                keyEventRepository,
                relationRepository,
                retrievalProperties
        );

        String currentChapterHint = retrievalProperties.getHints().getCurrentChapter();
        when(embeddingService.batchGenerateEmbedding(List.of("dragon oath", "dragon oath " + currentChapterHint)))
                .thenReturn(List.of(List.of(0.1f, 0.2f), List.of(0.2f, 0.3f)));

        SearchResp.SearchResult firstVariantA = searchResult(1L, 0.70f, Map.of(
                "chapter_num", 9,
                "segment_type", "scene",
                "content", "dragon oath reveal"
        ));
        SearchResp.SearchResult firstVariantB = searchResult(2L, 0.95f, Map.of(
                "chapter_num", 5,
                "segment_type", "scene",
                "content", "ritual before the battle"
        ));
        SearchResp.SearchResult secondVariantA = searchResult(1L, 0.92f, Map.of(
                "chapter_num", 9,
                "segment_type", "scene",
                "content", "dragon oath reveal"
        ));
        SearchResp.SearchResult futureChapter = searchResult(3L, 0.99f, Map.of(
                "chapter_num", 11,
                "segment_type", "scene",
                "content", "future twist"
        ));

        SearchResp firstResponse = response(firstVariantA, firstVariantB);
        SearchResp secondResponse = response(secondVariantA, futureChapter);
        when(milvusClient.search(any(SearchReq.class))).thenReturn(firstResponse, secondResponse);

        List<Map<String, Object>> results = service.searchSegments(1L, "dragon oath", 2, 10);

        assertEquals(2, results.size());
        assertEquals(9L, ((Number) results.get(0).get("chapter_num")).longValue());
        assertEquals(5L, ((Number) results.get(1).get("chapter_num")).longValue());
        assertEquals(2, ((Number) results.get(0).get("queryHits")).intValue());
        assertEquals(2, ((Number) results.get(0).get("keywordHits")).intValue());
        assertTrue(((List<?>) results.get(0).get("matchReasons")).contains("exact_query_match"));
        assertTrue(((String) results.get(0).get("rankExplanation")).contains("chapter_distance=1"));
        @SuppressWarnings("unchecked")
        Map<String, Object> trace = (Map<String, Object>) results.get(0).get("recallTrace");
        assertEquals("dragon oath", trace.get("query"));
        assertTrue(((List<?>) trace.get("matchedQueryVariants")).contains("dragon oath"));
        assertTrue(((Map<?, ?>) trace.get("scoreBreakdown")).containsKey("primaryFieldBoost"));
        assertTrue(results.stream().noneMatch(item -> ((Number) item.get("chapter_num")).intValue() == 11));
    }

    @Test
    void honorsConfiguredVariantLimit() {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        ChapterRepository chapterRepository = mock(ChapterRepository.class);
        KeyEventRepository keyEventRepository = mock(KeyEventRepository.class);
        RelationRepository relationRepository = mock(RelationRepository.class);
        RetrievalProperties retrievalProperties = new RetrievalProperties();
        retrievalProperties.getSearch().setMaxQueryVariants(1);
        MilvusSearchService service = new MilvusSearchService(
                milvusClient,
                embeddingService,
                chapterRepository,
                keyEventRepository,
                relationRepository,
                retrievalProperties
        );

        when(embeddingService.batchGenerateEmbedding(List.of("dragon oath")))
                .thenReturn(List.of(List.of(0.1f, 0.2f)));

        SearchResp.SearchResult result = searchResult(1L, 0.88f, Map.of(
                "chapter_num", 2,
                "segment_type", "scene",
                "content", "dragon oath reveal"
        ));

        SearchResp response = response(result);
        when(milvusClient.search(any(SearchReq.class))).thenReturn(response);

        List<Map<String, Object>> results = service.searchSegments(1L, "dragon oath", 1, 10);

        assertEquals(1, results.size());
        verify(embeddingService).batchGenerateEmbedding(List.of("dragon oath"));
    }

    @Test
    void prioritizesCharacterNameMatchesAndEventHooks() {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        ChapterRepository chapterRepository = mock(ChapterRepository.class);
        KeyEventRepository keyEventRepository = mock(KeyEventRepository.class);
        RelationRepository relationRepository = mock(RelationRepository.class);
        RetrievalProperties retrievalProperties = new RetrievalProperties();
        retrievalProperties.getSearch().setPerChapterEventLimit(2);
        MilvusSearchService service = new MilvusSearchService(
                milvusClient,
                embeddingService,
                chapterRepository,
                keyEventRepository,
                relationRepository,
                retrievalProperties
        );

        when(embeddingService.batchGenerateEmbedding(List.of("白夜", "白夜 人物 关系 设定")))
                .thenReturn(List.of(List.of(0.1f, 0.2f), List.of(0.2f, 0.3f)));

        SearchResp.SearchResult nameExact = searchResult(1L, 0.70f, Map.of(
                "mysql_char_id", 1L,
                "name", "白夜",
                "char_text", "普通描述"
        ));
        SearchResp.SearchResult bodyMatch = searchResult(2L, 0.95f, Map.of(
                "mysql_char_id", 2L,
                "name", "路人",
                "char_text", "白夜 传说"
        ));
        SearchResp characterResponse = response(nameExact, bodyMatch);
        when(milvusClient.search(any(SearchReq.class))).thenReturn(characterResponse, response());

        List<Map<String, Object>> characterResults = service.searchCharacters(1L, "白夜", 1);
        assertEquals(1, characterResults.size());
        assertEquals(1L, ((Number) characterResults.get(0).get("mysql_char_id")).longValue());
        assertTrue(((String) characterResults.get(0).get("rankExplanation")).contains("name_exact_match"));
        @SuppressWarnings("unchecked")
        Map<String, Object> characterTrace = (Map<String, Object>) characterResults.get(0).get("recallTrace");
        assertEquals("name", ((Map<?, ?>) characterTrace.get("primaryFieldSignals")).get("field"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) characterTrace.get("primaryFieldSignals")).get("exactMatch"));

        when(keyEventRepository.findByNovelIdAndResolvedFalse(eq(2L))).thenReturn(List.of(
                KeyEvent.builder().id(10L).build()
        ));
        when(embeddingService.batchGenerateEmbedding(List.of("dragon oath", "dragon oath 事件 伏笔 设定")))
                .thenReturn(List.of(List.of(0.3f, 0.4f), List.of(0.4f, 0.5f)));

        SearchResp.SearchResult unresolvedHook = searchResult(10L, 0.82f, Map.of(
                "mysql_event_id", 10L,
                "chapter_num", 8,
                "event_type", 0,
                "title", "dragon oath",
                "description", "伏笔尚未揭开"
        ));
        SearchResp.SearchResult resolvedTwist = searchResult(20L, 0.95f, Map.of(
                "mysql_event_id", 20L,
                "chapter_num", 9,
                "event_type", 1,
                "title", "dragon oath",
                "description", "已经解决"
        ));
        SearchResp eventFirstResponse = response(unresolvedHook, resolvedTwist);
        SearchResp eventSecondResponse = response();
        when(milvusClient.search(any(SearchReq.class))).thenReturn(eventFirstResponse, eventSecondResponse);

        List<Map<String, Object>> eventResults = service.searchEvents(2L, "dragon oath", 2);
        assertEquals(2, eventResults.size());
        assertEquals(10L, ((Number) eventResults.get(0).get("mysql_event_id")).longValue());
        assertEquals(20L, ((Number) eventResults.get(1).get("mysql_event_id")).longValue());
        assertTrue(((String) eventResults.get(0).get("rankExplanation")).contains("unresolved_event_boost"));
        assertTrue(((String) eventResults.get(0).get("rankExplanation")).contains("plot_hook_priority"));
        @SuppressWarnings("unchecked")
        Map<String, Object> eventTrace = (Map<String, Object>) eventResults.get(0).get("recallTrace");
        assertTrue(((List<?>) eventTrace.get("eventSignals")).contains("unresolved_event_boost"));
        assertTrue(((Map<?, ?>) eventTrace.get("scoreBreakdown")).containsKey("eventBoost"));
    }

    private static SearchResp.SearchResult searchResult(Long id, float score, Map<String, Object> entity) {
        SearchResp.SearchResult result = SearchResp.SearchResult.builder().build();
        result.setId(id);
        result.setScore(score);
        result.setEntity(entity);
        return result;
    }

    private static SearchResp response(SearchResp.SearchResult... results) {
        SearchResp response = SearchResp.builder().build();
        response.setSearchResults(List.of(List.of(results)));
        return response;
    }
}