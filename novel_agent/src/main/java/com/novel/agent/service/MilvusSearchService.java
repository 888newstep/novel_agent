package com.novel.agent.service;

import com.novel.agent.entity.Chapter;
import com.novel.agent.entity.KeyEvent;
import com.novel.agent.entity.Relation;
import com.novel.agent.repository.ChapterRepository;
import com.novel.agent.repository.KeyEventRepository;
import com.novel.agent.repository.RelationRepository;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusSearchService {

    private static final Map<String, Object> SEARCH_PARAMS = Map.of("ef_search", 64);
    private static final int DEFAULT_FETCH_MULTIPLIER = 2;
    private static final int MAX_QUERY_VARIANTS = 3;
    private static final int RECENT_CHAPTER_LIMIT = 3;
    private static final int MEMORY_SEGMENT_LIMIT = 3;
    private static final int MEMORY_HOOK_LIMIT = 2;
    private static final int MEMORY_CHARACTER_LIMIT = 3;
    private static final int MEMORY_ITEM_LIMIT = 1;
    private static final int MEMORY_FACTION_LIMIT = 1;
    private static final int MEMORY_RELATION_LIMIT = 2;
    private static final int PER_CHAPTER_SEGMENT_LIMIT = 1;
    private static final int PER_CHAPTER_EVENT_LIMIT = 1;

    private final MilvusClientV2 milvusClient;
    private final EmbeddingService embeddingService;
    private final ChapterRepository chapterRepository;
    private final KeyEventRepository keyEventRepository;
    private final RelationRepository relationRepository;

    public List<Map<String, Object>> searchSegments(Long novelId, String queryText, int topK) {
        return searchSegments(novelId, queryText, topK, null);
    }

    public List<Map<String, Object>> searchSegments(Long novelId, String queryText, int topK, Integer currentChapterNum) {
        return hybridSearch(
                "novel_segments",
                "novel_id in [0, " + novelId + "]",
                List.of("chapter_num", "segment_type", "content"),
                topK,
                queryText,
                "?? ?? ?? ??",
                "content",
                List.of("content"),
                true,
                currentChapterNum,
                true,
                PER_CHAPTER_SEGMENT_LIMIT
        );
    }

    public List<Map<String, Object>> searchEvents(Long novelId, String queryText, int topK) {
        return searchEvents(novelId, queryText, topK, null);
    }

    public List<Map<String, Object>> searchEvents(Long novelId, String queryText, int topK, Integer currentChapterNum) {
        return hybridSearch(
                "novel_events",
                "novel_id == " + novelId,
                List.of("mysql_event_id", "chapter_num", "event_type", "title", "description"),
                topK,
                queryText,
                "?? ?? ?? ??",
                "mysql_event_id",
                List.of("title", "description"),
                true,
                currentChapterNum,
                true,
                PER_CHAPTER_EVENT_LIMIT
        );
    }

    public List<Map<String, Object>> searchUnresolvedEvents(Long novelId, String queryText, int topK) {
        return searchUnresolvedEvents(novelId, queryText, topK, null);
    }

    public List<Map<String, Object>> searchUnresolvedEvents(Long novelId, String queryText, int topK, Integer currentChapterNum) {
        Set<Long> unresolvedIds = keyEventRepository.findByNovelIdAndResolvedFalse(novelId).stream()
                .map(KeyEvent::getId)
                .collect(Collectors.toSet());

        if (unresolvedIds.isEmpty()) {
            return List.of();
        }

        return searchEvents(novelId, queryText, Math.max(topK + 1, topK * 2), currentChapterNum).stream()
                .filter(item -> unresolvedIds.contains(asLong(item.get("mysql_event_id"))))
                .limit(topK)
                .collect(Collectors.toList());
    }

    public List<Map<String, Object>> searchCharacters(Long novelId, String queryText, int topK) {
        return hybridSearch(
                "novel_characters",
                "novel_id == " + novelId,
                List.of("mysql_char_id", "name", "char_text"),
                topK,
                queryText,
                "?? ?? ?? ??",
                "mysql_char_id",
                List.of("name", "char_text"),
                false,
                null,
                false,
                0
        );
    }

    public List<Map<String, Object>> searchItems(Long novelId, String queryText, int topK, Integer itemType) {
        String filter = "novel_id == " + novelId;
        if (itemType != null) {
            filter += " && item_type == " + itemType;
        }

        return hybridSearch(
                "novel_items",
                filter,
                List.of("mysql_item_id", "item_type", "name", "item_text"),
                topK,
                queryText,
                "?? ?? ?? ??",
                "mysql_item_id",
                List.of("name", "item_text"),
                false,
                null,
                false,
                0
        );
    }

    public List<Map<String, Object>> searchFactionOrInspiration(Long novelId, String queryText, int topK, Integer sourceType) {
        String filter = "novel_id == " + novelId;
        if (sourceType != null) {
            filter += " && source_type == " + sourceType;
        }

        return hybridSearch(
                "novel_faction_inspire",
                filter,
                List.of("mysql_ref_id", "source_type", "title", "content"),
                topK,
                queryText,
                "?? ?? ?? ??",
                "mysql_ref_id",
                List.of("title", "content"),
                false,
                null,
                false,
                0
        );
    }

    public WritingMemory buildWritingMemory(Long novelId, String queryText) {
        return buildWritingMemory(novelId, queryText, null);
    }

    public WritingMemory buildWritingMemory(Long novelId, String queryText, Integer currentChapterNum) {
        List<Map<String, Object>> recentChapters = loadRecentChapters(novelId, currentChapterNum, RECENT_CHAPTER_LIMIT);
        List<Map<String, Object>> segments = searchSegments(novelId, queryText, MEMORY_SEGMENT_LIMIT + 1, currentChapterNum);
        List<Map<String, Object>> hooks = searchUnresolvedEvents(novelId, queryText, MEMORY_HOOK_LIMIT + 1, currentChapterNum);
        List<Map<String, Object>> characters = searchCharacters(novelId, queryText, MEMORY_CHARACTER_LIMIT);
        List<Map<String, Object>> items = searchItems(novelId, queryText, MEMORY_ITEM_LIMIT + 1, null);
        List<Map<String, Object>> factions = searchFactionOrInspiration(novelId, queryText, MEMORY_FACTION_LIMIT + 1, 0);
        List<Map<String, Object>> relations = loadRelatedRelations(novelId, queryText, characters, MEMORY_RELATION_LIMIT);

        return WritingMemory.builder()
                .query(queryText)
                .currentChapterNum(currentChapterNum)
                .recentChapters(limitResults(recentChapters, RECENT_CHAPTER_LIMIT))
                .segments(limitResults(segments, MEMORY_SEGMENT_LIMIT))
                .hooks(limitResults(hooks, MEMORY_HOOK_LIMIT))
                .characters(limitResults(characters, MEMORY_CHARACTER_LIMIT))
                .items(limitResults(items, MEMORY_ITEM_LIMIT))
                .factions(limitResults(factions, MEMORY_FACTION_LIMIT))
                .relations(limitResults(relations, MEMORY_RELATION_LIMIT))
                .totalCount(Math.min(recentChapters.size(), RECENT_CHAPTER_LIMIT)
                        + Math.min(segments.size(), MEMORY_SEGMENT_LIMIT)
                        + Math.min(hooks.size(), MEMORY_HOOK_LIMIT)
                        + Math.min(characters.size(), MEMORY_CHARACTER_LIMIT)
                        + Math.min(items.size(), MEMORY_ITEM_LIMIT)
                        + Math.min(factions.size(), MEMORY_FACTION_LIMIT)
                        + Math.min(relations.size(), MEMORY_RELATION_LIMIT))
                .build();
    }

    private List<Map<String, Object>> hybridSearch(String collectionName,
                                                   String filter,
                                                   List<String> outputFields,
                                                   int topK,
                                                   String queryText,
                                                   String expansionHint,
                                                   String uniqueField,
                                                   List<String> textFields,
                                                   boolean recencyAware,
                                                   Integer currentChapterNum,
                                                   boolean chapterAware,
                                                   int perChapterLimit) {
        List<String> queryVariants = buildQueryVariants(queryText, expansionHint, currentChapterNum);
        List<List<Float>> queryVectors = embeddingService.batchGenerateEmbedding(queryVariants);
        int fetchK = Math.max(topK * DEFAULT_FETCH_MULTIPLIER, topK + 2);
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();

        for (int i = 0; i < queryVariants.size(); i++) {
            List<Float> queryVector = i < queryVectors.size() ? queryVectors.get(i) : null;
            if (queryVector == null || queryVector.isEmpty()) {
                continue;
            }

            List<Map<String, Object>> partial = executeVectorSearch(collectionName, filter, outputFields, queryVector, fetchK);
            for (Map<String, Object> item : partial) {
                String uniqueKey = buildUniqueKey(item, uniqueField, textFields);
                Map<String, Object> target = merged.computeIfAbsent(uniqueKey, ignored -> new LinkedHashMap<>(item));

                double currentScore = asDouble(item.get("score"));
                double existingScore = asDouble(target.get("score"));
                if (currentScore > existingScore) {
                    target.putAll(item);
                    target.put("score", currentScore);
                }

                @SuppressWarnings("unchecked")
                Set<String> hitQueries = (Set<String>) target.computeIfAbsent("_hitQueries", ignored -> new LinkedHashSet<String>());
                hitQueries.add(queryVariants.get(i));
            }
        }

        List<Map<String, Object>> candidates = new ArrayList<>(merged.values());
        if (chapterAware && currentChapterNum != null) {
            candidates = candidates.stream()
                    .filter(item -> !isFutureChapter(item, currentChapterNum))
                    .collect(Collectors.toList());
        }

        List<String> keywords = extractKeywords(queryText);
        long maxChapterNum = candidates.stream()
                .mapToLong(item -> asLong(item.get("chapter_num")))
                .max()
                .orElse(0L);
        String normalizedQuery = queryText == null ? "" : queryText.toLowerCase(Locale.ROOT);

        for (Map<String, Object> item : candidates) {
            String combinedText = buildCombinedText(item, textFields);
            int keywordHits = countKeywordHits(combinedText, keywords);
            @SuppressWarnings("unchecked")
            Set<String> hitQueries = (Set<String>) item.getOrDefault("_hitQueries", Set.of());

            long chapterNum = asLong(item.get("chapter_num"));
            double chapterBoost = computeChapterProximityBoost(chapterNum, currentChapterNum, chapterAware);
            int chapterDistance = getChapterDistance(chapterNum, currentChapterNum);

            double rankScore = asDouble(item.get("score"));
            rankScore += keywordHits * 0.10;
            rankScore += hitQueries.size() * 0.06;
            rankScore += chapterBoost;
            if (!normalizedQuery.isBlank() && combinedText.contains(normalizedQuery)) {
                rankScore += 0.16;
            }
            if (recencyAware && currentChapterNum == null && maxChapterNum > 0) {
                rankScore += Math.min(0.12, chapterNum / (double) maxChapterNum * 0.12);
            }

            item.put("rankScore", round(rankScore));
            item.put("queryHits", hitQueries.size());
            item.put("keywordHits", keywordHits);
            if (currentChapterNum != null && chapterNum > 0) {
                item.put("chapterDistance", chapterDistance);
                item.put("chapterProximityBoost", round(chapterBoost));
            }
            item.remove("_hitQueries");
        }

        candidates.sort(Comparator
                .comparingDouble((Map<String, Object> item) -> asDouble(item.get("rankScore"))).reversed()
                .thenComparingDouble(item -> asDouble(item.get("score"))).reversed());

        List<Map<String, Object>> limited = perChapterLimit > 0
                ? limitByChapter(candidates, topK, perChapterLimit)
                : limitResults(candidates, topK);

        log.info("search [{}] query=[{}], chapter={}, variants={}, results={}",
                collectionName, queryText, currentChapterNum, queryVariants.size(), limited.size());
        return limited;
    }

    private List<Map<String, Object>> executeVectorSearch(String collectionName,
                                                          String filter,
                                                          List<String> outputFields,
                                                          List<Float> queryVector,
                                                          int topK) {
        SearchReq searchReq = SearchReq.builder()
                .collectionName(collectionName)
                .data(List.of(new FloatVec(queryVector)))
                .filter(filter)
                .topK(topK)
                .outputFields(outputFields)
                .searchParams(SEARCH_PARAMS)
                .build();

        SearchResp resp = milvusClient.search(searchReq);
        List<Map<String, Object>> results = new ArrayList<>();
        for (List<SearchResp.SearchResult> resultList : resp.getSearchResults()) {
            for (SearchResp.SearchResult result : resultList) {
                Map<String, Object> item = new LinkedHashMap<>();
                for (String field : outputFields) {
                    Object val = result.getEntity().get(field);
                    if (val != null) {
                        item.put(field, val);
                    }
                }
                item.put("score", asDouble(result.getScore()));
                results.add(item);
            }
        }
        return results;
    }

    private List<String> buildQueryVariants(String queryText, String expansionHint, Integer currentChapterNum) {
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        String baseQuery = queryText == null ? "" : queryText.trim();
        if (!baseQuery.isBlank()) {
            variants.add(baseQuery);
        }

        List<String> keywords = extractKeywords(baseQuery);
        if (!keywords.isEmpty()) {
            String keywordQuery = String.join(" ", keywords);
            if (!keywordQuery.equals(baseQuery)) {
                variants.add(keywordQuery);
            }
        }

        if (!baseQuery.isBlank() && currentChapterNum != null) {
            variants.add(baseQuery + " ?" + currentChapterNum + "? ?? ??");
        } else if (!baseQuery.isBlank() && expansionHint != null && !expansionHint.isBlank()) {
            variants.add(baseQuery + " " + expansionHint);
        }

        return variants.stream().limit(MAX_QUERY_VARIANTS).collect(Collectors.toList());
    }

    private List<String> extractKeywords(String queryText) {
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }

        String[] tokens = queryText.toLowerCase(Locale.ROOT)
                .split("[\s,?????????()????????-]+");

        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        for (String token : tokens) {
            String trimmed = token.trim();
            if (trimmed.length() >= 2) {
                keywords.add(trimmed);
            }
            if (keywords.size() >= 5) {
                break;
            }
        }

        if (keywords.isEmpty() && queryText.trim().length() >= 2) {
            keywords.add(queryText.trim().toLowerCase(Locale.ROOT));
        }
        return new ArrayList<>(keywords);
    }

    private List<Map<String, Object>> loadRecentChapters(Long novelId, Integer currentChapterNum, int limit) {
        List<Chapter> chapters = chapterRepository.findByNovelIdOrderByChapterNumAsc(novelId).stream()
                .filter(chapter -> currentChapterNum == null || chapter.getChapterNum() <= currentChapterNum)
                .collect(Collectors.toList());
        if (chapters.isEmpty()) {
            return List.of();
        }

        int fromIndex = Math.max(0, chapters.size() - limit);
        return chapters.subList(fromIndex, chapters.size()).stream()
                .map(chapter -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("chapter_num", chapter.getChapterNum());
                    item.put("title", chapter.getTitle());
                    item.put("summary", chapter.getSummary());
                    item.put("key_events", chapter.getKeyEvents());
                    if (currentChapterNum != null) {
                        item.put("chapterDistance", Math.max(0, currentChapterNum - chapter.getChapterNum()));
                    }
                    return item;
                })
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> loadRelatedRelations(Long novelId,
                                                           String queryText,
                                                           List<Map<String, Object>> characters,
                                                           int limit) {
        LinkedHashSet<String> focusNames = new LinkedHashSet<>(extractKeywords(queryText));
        for (Map<String, Object> character : characters) {
            Object name = character.get("name");
            if (name != null) {
                focusNames.add(name.toString());
            }
        }

        if (focusNames.isEmpty()) {
            return List.of();
        }

        return relationRepository.findByNovelId(novelId).stream()
                .map(relation -> Map.entry(relation, relationHitScore(relation, focusNames, queryText)))
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<Relation, Integer>comparingByValue().reversed())
                .limit(limit)
                .map(entry -> toRelationMap(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
    }

    private int relationHitScore(Relation relation, Set<String> focusNames, String queryText) {
        int score = 0;
        String sourceName = safeLower(relation.getSourceName());
        String targetName = safeLower(relation.getTargetName());
        String description = safeLower(relation.getDescription());
        String baseQuery = safeLower(queryText);

        for (String name : focusNames) {
            String normalized = safeLower(name);
            if (!normalized.isBlank() && (sourceName.contains(normalized) || targetName.contains(normalized))) {
                score += 2;
            }
            if (!normalized.isBlank() && description.contains(normalized)) {
                score += 1;
            }
        }

        if (!baseQuery.isBlank() && (sourceName.contains(baseQuery) || targetName.contains(baseQuery) || description.contains(baseQuery))) {
            score += 2;
        }
        return score;
    }

    private Map<String, Object> toRelationMap(Relation relation, int hitScore) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("source_name", relation.getSourceName());
        item.put("target_name", relation.getTargetName());
        item.put("relation_type", relation.getRelationType());
        item.put("description", relation.getDescription());
        item.put("rankScore", hitScore);
        return item;
    }

    private List<Map<String, Object>> limitByChapter(List<Map<String, Object>> candidates, int limit, int perChapterLimit) {
        List<Map<String, Object>> results = new ArrayList<>();
        Map<Long, Integer> chapterCounter = new LinkedHashMap<>();
        for (Map<String, Object> item : candidates) {
            if (results.size() >= limit) {
                break;
            }
            long chapterNum = asLong(item.get("chapter_num"));
            if (chapterNum <= 0) {
                results.add(item);
                continue;
            }
            int used = chapterCounter.getOrDefault(chapterNum, 0);
            if (used >= perChapterLimit) {
                continue;
            }
            chapterCounter.put(chapterNum, used + 1);
            results.add(item);
        }
        return results;
    }

    private List<Map<String, Object>> limitResults(List<Map<String, Object>> items, int limit) {
        return items.stream().limit(limit).collect(Collectors.toList());
    }

    private boolean isFutureChapter(Map<String, Object> item, Integer currentChapterNum) {
        if (currentChapterNum == null) {
            return false;
        }
        long chapterNum = asLong(item.get("chapter_num"));
        return chapterNum > 0 && chapterNum > currentChapterNum;
    }

    private int getChapterDistance(long chapterNum, Integer currentChapterNum) {
        if (currentChapterNum == null || chapterNum <= 0) {
            return -1;
        }
        return Math.abs(currentChapterNum - (int) chapterNum);
    }

    private double computeChapterProximityBoost(long chapterNum, Integer currentChapterNum, boolean chapterAware) {
        if (!chapterAware || currentChapterNum == null || chapterNum <= 0) {
            return 0D;
        }
        int gap = currentChapterNum - (int) chapterNum;
        if (gap < 0) {
            return 0D;
        }
        return switch (gap) {
            case 0 -> 0.32D;
            case 1 -> 0.24D;
            case 2 -> 0.18D;
            case 3 -> 0.12D;
            case 4 -> 0.08D;
            case 5 -> 0.04D;
            default -> 0.01D;
        };
    }

    private String buildUniqueKey(Map<String, Object> item, String uniqueField, List<String> textFields) {
        Object uniqueValue = item.get(uniqueField);
        if (uniqueValue != null) {
            return uniqueField + ":" + uniqueValue;
        }
        return textFields.stream()
                .map(field -> Objects.toString(item.get(field), ""))
                .collect(Collectors.joining("|"));
    }

    private String buildCombinedText(Map<String, Object> item, Collection<String> fields) {
        return fields.stream()
                .map(field -> Objects.toString(item.get(field), ""))
                .collect(Collectors.joining(" "))
                .toLowerCase(Locale.ROOT);
    }

    private int countKeywordHits(String content, List<String> keywords) {
        int hits = 0;
        for (String keyword : keywords) {
            if (!keyword.isBlank() && content.contains(keyword.toLowerCase(Locale.ROOT))) {
                hits++;
            }
        }
        return hits;
    }

    private double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0D;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ex) {
            return 0D;
        }
    }

    private long asLong(Object value) {
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

    private double round(double value) {
        return Math.round(value * 1000D) / 1000D;
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    public void markEventResolved(Long eventId) {
        log.info("event {} resolved in MySQL; no Milvus update required", eventId);
    }

    @Data
    @Builder
    public static class WritingMemory {
        private String query;
        private Integer currentChapterNum;
        private List<Map<String, Object>> recentChapters;
        private List<Map<String, Object>> segments;
        private List<Map<String, Object>> hooks;
        private List<Map<String, Object>> characters;
        private List<Map<String, Object>> items;
        private List<Map<String, Object>> factions;
        private List<Map<String, Object>> relations;
        private int totalCount;
    }
}
