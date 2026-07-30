package com.novel.agent.service;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeSearchService {

    private final MilvusClientV2 milvusClient;
    private final EmbeddingService embeddingService;

    private static final String COLLECTION_NAME = "knowledge_base";

    /**
     * 检索外部知识库，用于写作参考
     * 只返回来源和分类，不返回原文内容
     */
    public List<KnowledgeRef> search(String queryText, int topK) {
        List<Float> queryVector = embeddingService.generateEmbedding(queryText);

        SearchReq searchReq = SearchReq.builder()
                .collectionName(COLLECTION_NAME)
                .data(List.of(new FloatVec(queryVector)))
                .topK(topK)
                .outputFields(List.of("source", "category", "chapter", "content_len"))
                .build();

        SearchResp resp = milvusClient.search(searchReq);

        List<KnowledgeRef> results = new ArrayList<>();
        for (List<SearchResp.SearchResult> resultList : resp.getSearchResults()) {
            for (SearchResp.SearchResult result : resultList) {
                KnowledgeRef ref = KnowledgeRef.builder()
                        .source((String) result.getEntity().get("source"))
                        .category((String) result.getEntity().get("category"))
                        .chapter((String) result.getEntity().get("chapter"))
                        .similarity(result.getScore())
                        .build();
                results.add(ref);
            }
        }

        log.info("检索知识库 [{}] 返回 {} 条参考", queryText, results.size());
        return results;
    }

    /**
     * 构建注入 Prompt 的参考信息（不包含原文）
     */
    public String buildKnowledgePrompt(String queryText) {
        List<KnowledgeRef> refs = search(queryText, 3);

        if (refs.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("\n【外部知识参考】\n");
        sb.append("以下小说中有类似情节，可作为写作参考（请勿复制原文）：\n");

        for (KnowledgeRef ref : refs) {
            sb.append(String.format("  - 《%s》(%s) 中 %s\n",
                    ref.getSource(), ref.getCategory(), ref.getChapter()));
        }

        return sb.toString();
    }

    // =============================================

    @lombok.Builder
    @lombok.Data
    public static class KnowledgeRef {
        private String source;
        private String category;
        private String chapter;
        private double similarity;
    }
}