package com.novel.agent.service;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.response.SearchResp;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeSearchServiceTest {

    @Test
    void searchesKnowledgeReferencesAndBuildsPrompt() {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        KnowledgeSearchService service = new KnowledgeSearchService(milvusClient, embeddingService);

        when(embeddingService.generateEmbedding("dragon clan")).thenReturn(List.of(0.1f, 0.2f));

        SearchResp.SearchResult result = SearchResp.SearchResult.builder().build();
        result.setId(1L);
        result.setScore(0.93f);
        result.setEntity(Map.of(
                "source", "legend.txt",
                "category", "character",
                "chapter", "chapter-3"
        ));

        SearchResp response = SearchResp.builder().build();
        response.setSearchResults(List.of(List.of(result)));
        when(milvusClient.search(any(SearchReq.class))).thenReturn(response);

        List<KnowledgeSearchService.KnowledgeRef> results = service.search("dragon clan", 5);
        assertEquals(1, results.size());
        assertEquals("legend.txt", results.get(0).getSource());
        assertEquals("character", results.get(0).getCategory());
        assertEquals("chapter-3", results.get(0).getChapter());

        String prompt = service.buildKnowledgePrompt("dragon clan");
        assertTrue(prompt.contains("legend.txt"));
        assertTrue(prompt.contains("character"));
        assertTrue(prompt.contains("chapter-3"));
    }
}

