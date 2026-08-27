package com.novel.agent.service;

import com.novel.agent.config.RetrievalProperties;
import com.novel.agent.entity.Chapter;
import com.novel.agent.entity.KeyEvent;
import com.novel.agent.repository.ChapterRepository;
import com.novel.agent.repository.KeyEventRepository;
import com.novel.agent.repository.RelationRepository;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.exception.ErrorCode;
import io.milvus.v2.exception.MilvusClientException;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.response.SearchResp;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MilvusSearchServiceTest {

    @Test
    void infersNextChapterAndKeepsNewestSummariesFirst() {
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

        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get())
                .when(embeddingService).withRequestCache(any());
        when(embeddingService.batchGenerateEmbedding(any())).thenAnswer(invocation -> {
            List<?> texts = invocation.getArgument(0, List.class);
            return texts.stream().map(ignored -> List.of(0.1f, 0.2f)).toList();
        });
        when(milvusClient.search(any(SearchReq.class))).thenReturn(response());
        when(chapterRepository.findByNovelIdOrderByChapterNumAsc(1L)).thenReturn(List.of(
                Chapter.builder().chapterNum(5).title("第五章").summary("旧线索").build(),
                Chapter.builder().chapterNum(7).title("第七章").summary("进入山门").build(),
                Chapter.builder().chapterNum(8).title("第八章").summary("试炼开始").build()
        ));
        when(keyEventRepository.findByNovelIdAndResolvedFalse(1L)).thenReturn(List.of());
        when(relationRepository.findByNovelId(1L)).thenReturn(List.of());

        MilvusSearchService.WritingMemory memory =
                service.buildWritingMemory(1L, "山门试炼", null);

        assertEquals(9, memory.getCurrentChapterNum());
        assertEquals(List.of(8, 7, 5), memory.getRecentChapters().stream()
                .map(item -> item.get("chapter_num"))
                .toList());
        assertEquals(List.of(1, 2, 4), memory.getRecentChapters().stream()
                .map(item -> item.get("chapterDistance"))
                .toList());
        verify(embeddingService).batchGenerateEmbedding(List.of(
                "山门试炼",
                "山门试炼 试炼",
                "山门试炼 " + retrievalProperties.getHints().getCurrentChapter(),
                "山门试炼 " + retrievalProperties.getHints().getCharacter(),
                "山门试炼 " + retrievalProperties.getHints().getItem(),
                "山门试炼 " + retrievalProperties.getHints().getFaction()
        ));
    }

    @Test
    void writingDefaultRankingImprovesContinuationTopOneAgainstRawVectorScore() {
        RetrievalProperties rawScoreProperties = new RetrievalProperties();
        rawScoreProperties.getRanking().setKeywordHitWeight(0D);
        rawScoreProperties.getRanking().setVariantHitWeight(0D);
        rawScoreProperties.getRanking().setPrimaryFieldKeywordHitWeight(0D);
        rawScoreProperties.getRanking().setPrimaryFieldExactMatchBonus(0D);
        rawScoreProperties.getRanking().setExactMatchBonus(0D);
        rawScoreProperties.getRanking().setRecencyMaxBoost(0D);

        List<Map<String, Object>> rawScoreResults = runContinuationSearch(rawScoreProperties);
        List<Map<String, Object>> writingDefaultResults = runContinuationSearch(new RetrievalProperties());

        assertEquals(8L, ((Number) rawScoreResults.get(0).get("chapter_num")).longValue());
        assertEquals(8L, ((Number) writingDefaultResults.get(0).get("chapter_num")).longValue());
        assertEquals("ritual before the battle", rawScoreResults.get(0).get("content"));
        assertEquals("dragon oath reveal", writingDefaultResults.get(0).get("content"));
        assertTrue(((String) writingDefaultResults.get(0).get("rankExplanation")).contains("exact_query_match"));
        assertTrue(((String) writingDefaultResults.get(0).get("rankExplanation")).contains("chapter_distance=2"));
    }

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
        @SuppressWarnings("unchecked")
        Map<String, Object> explanation = (Map<String, Object>) results.get(0).get("explanation");

        assertEquals("dragon oath", trace.get("query"));
        assertTrue(((List<?>) trace.get("matchedQueryVariants")).contains("dragon oath"));
        assertTrue(((Map<?, ?>) trace.get("scoreBreakdown")).containsKey("primaryFieldBoost"));
        assertTrue(((String) explanation.get("summary")).contains("chapter_distance=1"));
        assertTrue((Boolean) explanation.get("usedChapterBoost"));
        assertTrue(((Map<?, ?>) explanation.get("scoreBreakdown")).containsKey("finalScore"));
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
        SearchResp response = response(searchResult(1L, 0.91f, Map.of(
                "chapter_num", 10,
                "segment_type", "scene",
                "content", "dragon oath memory"
        )));
        when(milvusClient.search(any(SearchReq.class))).thenReturn(response);

        List<Map<String, Object>> results = service.searchSegments(1L, "dragon oath", 1, 10);

        assertEquals(1, results.size());
        verify(embeddingService).batchGenerateEmbedding(List.of("dragon oath"));
    }

    @Test
    void limitsLongQueryBeforeEmbeddingAndExpansion() {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        RetrievalProperties retrievalProperties = new RetrievalProperties();
        retrievalProperties.getSearch().setMaxQueryChars(32);
        MilvusSearchService service = new MilvusSearchService(
                milvusClient,
                embeddingService,
                mock(ChapterRepository.class),
                mock(KeyEventRepository.class),
                mock(RelationRepository.class),
                retrievalProperties
        );
        String longQuery = "abcdefghijklmnopqrstuvwxyz0123456789repeatedcontinuationdetails";
        String boundedQuery = longQuery.substring(0, 32);
        String currentChapterHint = retrievalProperties.getHints().getCurrentChapter();
        when(embeddingService.batchGenerateEmbedding(List.of(
                boundedQuery,
                boundedQuery + " " + currentChapterHint
        ))).thenReturn(List.of(List.of(0.1f, 0.2f), List.of(0.2f, 0.3f)));
        SearchResp resultResponse = response(searchResult(1L, 0.8f, Map.of(
                "chapter_num", 9,
                "segment_type", "scene",
                "content", boundedQuery
        )));
        when(milvusClient.search(any(SearchReq.class))).thenReturn(resultResponse, resultResponse);

        List<Map<String, Object>> results = service.searchSegments(1L, longQuery, 1, 10);

        verify(embeddingService).batchGenerateEmbedding(List.of(
                boundedQuery,
                boundedQuery + " " + currentChapterHint
        ));
        @SuppressWarnings("unchecked")
        Map<String, Object> trace = (Map<String, Object>) results.get(0).get("recallTrace");
        assertEquals(longQuery.length(), trace.get("originalQueryChars"));
        assertEquals(32, trace.get("queryChars"));
        assertEquals(true, trace.get("queryTruncated"));
    }

    @Test
    void limitsTotalEmbeddingCharsAcrossQueryVariants() {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        RetrievalProperties retrievalProperties = new RetrievalProperties();
        retrievalProperties.getSearch().setMaxQueryChars(32);
        retrievalProperties.getSearch().setMaxTotalQueryChars(40);
        MilvusSearchService service = new MilvusSearchService(
                milvusClient,
                embeddingService,
                mock(ChapterRepository.class),
                mock(KeyEventRepository.class),
                mock(RelationRepository.class),
                retrievalProperties
        );
        String longQuery = "abcdefghijklmnopqrstuvwxyz0123456789extra";
        String boundedQuery = longQuery.substring(0, 32);
        when(embeddingService.batchGenerateEmbedding(List.of(boundedQuery)))
                .thenReturn(List.of(List.of(0.1f, 0.2f)));
        when(milvusClient.search(any(SearchReq.class))).thenReturn(response(searchResult(1L, 0.8f, Map.of(
                "chapter_num", 9,
                "segment_type", "scene",
                "content", boundedQuery
        ))));

        List<Map<String, Object>> results = service.searchSegments(1L, longQuery, 1, 10);

        verify(embeddingService).batchGenerateEmbedding(List.of(boundedQuery));
        @SuppressWarnings("unchecked")
        Map<String, Object> trace = (Map<String, Object>) results.get(0).get("recallTrace");
        assertEquals(1, trace.get("queryVariantCount"));
        assertEquals(32, trace.get("queryVariantChars"));
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

        when(embeddingService.batchGenerateEmbedding(List.of("bai ye", "bai ye " + retrievalProperties.getHints().getCharacter())))
                .thenReturn(List.of(List.of(0.1f, 0.2f), List.of(0.2f, 0.3f)));

        SearchResp.SearchResult nameExact = searchResult(1L, 0.70f, Map.of(
                "mysql_char_id", 1L,
                "name", "bai ye",
                "char_text", "ordinary description"
        ));
        SearchResp.SearchResult bodyMatch = searchResult(2L, 0.95f, Map.of(
                "mysql_char_id", 2L,
                "name", "passerby",
                "char_text", "bai ye legend"
        ));
        SearchResp characterResponse = response(nameExact, bodyMatch);
        when(milvusClient.search(any(SearchReq.class))).thenReturn(characterResponse, response());

        List<Map<String, Object>> characterResults = service.searchCharacters(1L, "bai ye", 1);
        assertEquals(1, characterResults.size());
        assertEquals(1L, ((Number) characterResults.get(0).get("mysql_char_id")).longValue());
        assertTrue(((String) characterResults.get(0).get("rankExplanation")).contains("name_exact_match"));

        @SuppressWarnings("unchecked")
        Map<String, Object> characterTrace = (Map<String, Object>) characterResults.get(0).get("recallTrace");
        @SuppressWarnings("unchecked")
        Map<String, Object> characterExplanation = (Map<String, Object>) characterResults.get(0).get("explanation");
        assertEquals("name", ((Map<?, ?>) characterTrace.get("primaryFieldSignals")).get("field"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) characterTrace.get("primaryFieldSignals")).get("exactMatch"));
        assertEquals(Boolean.FALSE, characterExplanation.get("usedEventBoost"));

        when(keyEventRepository.findByNovelIdAndResolvedFalse(eq(2L))).thenReturn(List.of(
                KeyEvent.builder().id(10L).build()
        ));
        when(embeddingService.batchGenerateEmbedding(List.of("dragon oath", "dragon oath " + retrievalProperties.getHints().getEvent())))
                .thenReturn(List.of(List.of(0.3f, 0.4f), List.of(0.4f, 0.5f)));

        SearchResp.SearchResult unresolvedHook = searchResult(10L, 0.82f, Map.of(
                "mysql_event_id", 10L,
                "chapter_num", 8,
                "event_type", 0,
                "title", "dragon oath",
                "description", "hook remains unresolved"
        ));
        SearchResp.SearchResult resolvedTwist = searchResult(20L, 0.95f, Map.of(
                "mysql_event_id", 20L,
                "chapter_num", 9,
                "event_type", 1,
                "title", "dragon oath",
                "description", "twist already resolved"
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
        @SuppressWarnings("unchecked")
        Map<String, Object> eventExplanation = (Map<String, Object>) eventResults.get(0).get("explanation");
        assertTrue(((List<?>) eventTrace.get("eventSignals")).contains("unresolved_event_boost"));
        assertTrue(((Map<?, ?>) eventTrace.get("scoreBreakdown")).containsKey("eventBoost"));
        assertTrue((Boolean) eventExplanation.get("usedEventBoost"));
        assertTrue(((List<?>) eventExplanation.get("eventSignals")).contains("plot_hook_priority"));
    }

    @Test
    void returnsEmptyResultsWhenCollectionIsNotReady() {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        ChapterRepository chapterRepository = mock(ChapterRepository.class);
        KeyEventRepository keyEventRepository = mock(KeyEventRepository.class);
        RelationRepository relationRepository = mock(RelationRepository.class);
        MilvusSearchService service = new MilvusSearchService(
                milvusClient,
                embeddingService,
                chapterRepository,
                keyEventRepository,
                relationRepository,
                new RetrievalProperties()
        );

        when(embeddingService.batchGenerateEmbedding(any()))
                .thenReturn(List.of(List.of(0.1f, 0.2f)));
        when(milvusClient.search(any(SearchReq.class)))
                .thenThrow(new MilvusClientException(ErrorCode.SERVER_ERROR, "collection not loaded"));

        assertTrue(service.searchSegments(1L, "dragon oath", 1).isEmpty());
    }

    private static SearchResp.SearchResult searchResult(Long id, float score, Map<String, Object> entity) {
        SearchResp.SearchResult result = SearchResp.SearchResult.builder().build();
        result.setId(id);
        result.setScore(score);
        result.setEntity(entity);
        return result;
    }

    private static List<Map<String, Object>> runContinuationSearch(RetrievalProperties retrievalProperties) {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        ChapterRepository chapterRepository = mock(ChapterRepository.class);
        KeyEventRepository keyEventRepository = mock(KeyEventRepository.class);
        RelationRepository relationRepository = mock(RelationRepository.class);
        MilvusSearchService service = new MilvusSearchService(
                milvusClient,
                embeddingService,
                chapterRepository,
                keyEventRepository,
                relationRepository,
                retrievalProperties
        );

        when(embeddingService.batchGenerateEmbedding(any()))
                .thenReturn(List.of(List.of(0.1f, 0.2f), List.of(0.2f, 0.3f)));
        when(milvusClient.search(any(SearchReq.class))).thenReturn(
                response(
                        searchResult(1L, 0.70f, Map.of(
                                "chapter_num", 8,
                                "segment_type", "scene",
                                "content", "dragon oath reveal"
                        )),
                        searchResult(2L, 0.95f, Map.of(
                                "chapter_num", 8,
                                "segment_type", "scene",
                                "content", "ritual before the battle"
                        ))
                ),
                response(
                        searchResult(1L, 0.92f, Map.of(
                                "chapter_num", 8,
                                "segment_type", "scene",
                                "content", "dragon oath reveal"
                        )),
                        searchResult(3L, 0.99f, Map.of(
                                "chapter_num", 11,
                                "segment_type", "scene",
                                "content", "future twist"
                        ))
                )
        );

        return service.searchSegments(1L, "dragon oath", 1, 10);
    }

    private static SearchResp response(SearchResp.SearchResult... results) {
        SearchResp response = SearchResp.builder().build();
        response.setSearchResults(List.of(List.of(results)));
        return response;
    }
}
