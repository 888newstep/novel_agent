package com.novel.agent.service;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Milvus 检索服务（阶段5：Agent 素材检索标准流程）
 * 所有检索强制携带 novel_id 过滤 + ef_search=64 检索参数
 * 伏笔检索仅返回 mysql_event_id，resolved 状态由 MySQL 过滤
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusSearchService {

    private final MilvusClientV2 milvusClient;
    private final EmbeddingService embeddingService;

    /** 检索参数：ef_search=64 */
    private static final Map<String, Object> SEARCH_PARAMS = Map.of("ef_search", 64);

    // =============================================
    // 1. 剧情片段检索
    // =============================================

    /**
     * 检索相关历史情节（novel_segments）
     * 同时检索该小说的具体情节（novel_id）和全局训练数据（novel_id=0）
     * @param novelId 小说ID（强制过滤）
     * @param queryText 查询文本
     * @param topK 返回条数
     */
    public List<Map<String, Object>> searchSegments(Long novelId, String queryText, int topK) {
        List<Float> queryVector = embeddingService.generateEmbedding(queryText);

        SearchReq searchReq = SearchReq.builder()
                .collectionName("novel_segments")
                .data(List.of(new FloatVec(queryVector)))
                .filter("novel_id in [0, " + novelId + "]")
                .topK(topK)
                .outputFields(List.of("chapter_num", "segment_type", "content"))
                .searchParams(SEARCH_PARAMS)
                .build();

        return executeSearch(searchReq, List.of("chapter_num", "segment_type", "content"));
    }

    // =============================================
    // 2. 伏笔/关键事件检索
    // =============================================

    /**
     * 检索伏笔/关键事件（novel_events）
     * 仅返回 mysql_event_id，resolved 状态由 MySQL 过滤
     * @param novelId 小说ID
     * @param queryText 查询文本
     * @param topK 返回条数
     */
    public List<Map<String, Object>> searchEvents(Long novelId, String queryText, int topK) {
        List<Float> queryVector = embeddingService.generateEmbedding(queryText);

        SearchReq searchReq = SearchReq.builder()
                .collectionName("novel_events")
                .data(List.of(new FloatVec(queryVector)))
                .filter("novel_id == " + novelId)
                .topK(topK)
                .outputFields(List.of("mysql_event_id", "chapter_num", "event_type", "title", "description"))
                .searchParams(SEARCH_PARAMS)
                .build();

        return executeSearch(searchReq, List.of("mysql_event_id", "chapter_num", "event_type", "title", "description"));
    }

    /**
     * 检索未回收伏笔（兼容旧接口，名称保留）
     * 注意：resolved 状态不在 Milvus 中，此方法返回所有匹配事件
     * 调用方需自行到 MySQL 过滤 resolved=0
     */
    public List<Map<String, Object>> searchUnresolvedEvents(Long novelId, String queryText, int topK) {
        return searchEvents(novelId, queryText, topK);
    }

    // =============================================
    // 3. 角色人设检索
    // =============================================

    /**
     * 检索角色人设（novel_characters）
     */
    public List<Map<String, Object>> searchCharacters(Long novelId, String queryText, int topK) {
        List<Float> queryVector = embeddingService.generateEmbedding(queryText);

        SearchReq searchReq = SearchReq.builder()
                .collectionName("novel_characters")
                .data(List.of(new FloatVec(queryVector)))
                .filter("novel_id == " + novelId)
                .topK(topK)
                .outputFields(List.of("mysql_char_id", "name", "char_text"))
                .searchParams(SEARCH_PARAMS)
                .build();

        return executeSearch(searchReq, List.of("mysql_char_id", "name", "char_text"));
    }

    // =============================================
    // 4. 法宝/功法检索
    // =============================================

    /**
     * 检索法宝/功法（novel_items）
     * @param itemType 可选：null=全部, 0=法宝, 1=功法
     */
    public List<Map<String, Object>> searchItems(Long novelId, String queryText, int topK, Integer itemType) {
        List<Float> queryVector = embeddingService.generateEmbedding(queryText);

        String filter = "novel_id == " + novelId;
        if (itemType != null) {
            filter += " && item_type == " + itemType;
        }

        SearchReq searchReq = SearchReq.builder()
                .collectionName("novel_items")
                .data(List.of(new FloatVec(queryVector)))
                .filter(filter)
                .topK(topK)
                .outputFields(List.of("mysql_item_id", "item_type", "name", "item_text"))
                .searchParams(SEARCH_PARAMS)
                .build();

        return executeSearch(searchReq, List.of("mysql_item_id", "item_type", "name", "item_text"));
    }

    // =============================================
    // 5. 势力/灵感检索
    // =============================================

    /**
     * 检索势力/灵感（novel_faction_inspire）
     * @param sourceType 可选：null=全部, 0=势力, 1=灵感
     * @param novelId 0=检索全局通用灵感，非0=绑定小说
     */
    public List<Map<String, Object>> searchFactionOrInspiration(Long novelId, String queryText, int topK, Integer sourceType) {
        List<Float> queryVector = embeddingService.generateEmbedding(queryText);

        String filter = "novel_id == " + novelId;
        if (sourceType != null) {
            filter += " && source_type == " + sourceType;
        }

        SearchReq searchReq = SearchReq.builder()
                .collectionName("novel_faction_inspire")
                .data(List.of(new FloatVec(queryVector)))
                .filter(filter)
                .topK(topK)
                .outputFields(List.of("mysql_ref_id", "source_type", "title", "content"))
                .searchParams(SEARCH_PARAMS)
                .build();

        return executeSearch(searchReq, List.of("mysql_ref_id", "source_type", "title", "content"));
    }

    // =============================================
    // 通用检索执行
    // =============================================

    /**
     * 执行检索并提取结果
     */
    private List<Map<String, Object>> executeSearch(SearchReq searchReq, List<String> fields) {
        SearchResp resp = milvusClient.search(searchReq);

        List<Map<String, Object>> results = new ArrayList<>();
        for (List<SearchResp.SearchResult> resultList : resp.getSearchResults()) {
            for (SearchResp.SearchResult result : resultList) {
                Map<String, Object> item = new HashMap<>();
                for (String field : fields) {
                    Object val = result.getEntity().get(field);
                    if (val != null) {
                        item.put(field, val);
                    }
                }
                item.put("score", result.getScore());
                results.add(item);
            }
        }

        log.info("检索 [{}] 返回 {} 条结果", searchReq.getCollectionName(), results.size());
        return results;
    }

    /**
     * 标记伏笔为已回收（仅 MySQL 操作，保留兼容方法）
     */
    public void markEventResolved(Long eventId) {
        log.info("伏笔 {} 已回收（请在 MySQL 中 update resolved=1）", eventId);
    }
}