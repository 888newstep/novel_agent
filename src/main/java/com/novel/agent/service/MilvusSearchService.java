package com.novel.agent.service;

import com.novel.agent.entity.Chapter;
import com.novel.agent.entity.KeyEvent;
import com.novel.agent.entity.Relation;
import com.novel.agent.repository.ChapterRepository;
import com.novel.agent.repository.KeyEventRepository;
import com.novel.agent.config.RetrievalProperties;
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

    private final MilvusClientV2 milvusClient;
    private final EmbeddingService embeddingService;
    private final ChapterRepository chapterRepository;
    private final KeyEventRepository keyEventRepository;
    private final RelationRepository relationRepository;
    private final RetrievalProperties retrievalProperties;

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
                retrievalProperties.getHints().getSegment(),
                "content",
                List.of("content"),
                true,
                currentChapterNum,
                true,
                retrievalProperties.getSearch().getPerChapterSegmentLimit()
        );
    }

    public List<Map<String, Object>> searchEvents(Long novelId, String queryText, int topK) {
        return searchEvents(novelId, queryText, topK, null);
    }

    public List<Map<String, Object>> searchEvents(Long novelId, String queryText, int topK, Integer currentChapterNum) {
        Set<Long> unresolvedIds = keyEventRepository.findByNovelIdAndResolvedFalse(novelId).stream()
                .map(KeyEvent::getId)
                .collect(Collectors.toSet());
        return searchEventsInternal(novelId, queryText, topK, currentChapterNum, unresolvedIds);
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

        return searchEventsInternal(novelId, queryText, Math.max(topK + 1, topK * 2), currentChapterNum, unresolvedIds).stream()
                .filter(item -> unresolvedIds.contains(asLong(item.get("mysql_event_id"))))
                .limit(topK)
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> searchEventsInternal(Long novelId,
                                                           String queryText,
                                                           int topK,
                                                           Integer currentChapterNum,
                                                           Set<Long> unresolvedIds) {
        List<Map<String, Object>> results = hybridSearch(
                "novel_events",
                "novel_id == " + novelId,
                List.of("mysql_event_id", "chapter_num", "event_type", "title", "description"),
                topK,
                queryText,
                retrievalProperties.getHints().getEvent(),
                "mysql_event_id",
                List.of("title", "description"),
                true,
                currentChapterNum,
                true,
                retrievalProperties.getSearch().getPerChapterEventLimit()
        );
        return prioritizeEventResults(results, unresolvedIds);
    }

    public List<Map<String, Object>> searchCharacters(Long novelId, String queryText, int topK) {
        return hybridSearch(
                "novel_characters",
                "novel_id == " + novelId,
                List.of("mysql_char_id", "name", "char_text"),
                topK,
                queryText,
                retrievalProperties.getHints().getCharacter(),
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
                retrievalProperties.getHints().getItem(),
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
                retrievalProperties.getHints().getFaction(),
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
        List<Map<String, Object>> recentChapters = loadRecentChapters(novelId, currentChapterNum, retrievalProperties.getMemory().getRecentChapterLimit());
        List<Map<String, Object>> segments = searchSegments(novelId, queryText, retrievalProperties.getMemory().getSegmentLimit() + 1, currentChapterNum);
        List<Map<String, Object>> hooks = searchUnresolvedEvents(novelId, queryText, retrievalProperties.getMemory().getHookLimit() + 1, currentChapterNum);
        List<Map<String, Object>> characters = searchCharacters(novelId, queryText, retrievalProperties.getMemory().getCharacterLimit());
        List<Map<String, Object>> items = searchItems(novelId, queryText, retrievalProperties.getMemory().getItemLimit() + 1, null);
        List<Map<String, Object>> factions = searchFactionOrInspiration(novelId, queryText, retrievalProperties.getMemory().getFactionLimit() + 1, 0);
        List<Map<String, Object>> relations = loadRelatedRelations(novelId, queryText, characters, retrievalProperties.getMemory().getRelationLimit());

        return WritingMemory.builder()
                .query(queryText)
                .currentChapterNum(currentChapterNum)
                .recentChapters(limitResults(recentChapters, retrievalProperties.getMemory().getRecentChapterLimit()))
                .segments(limitResults(segments, retrievalProperties.getMemory().getSegmentLimit()))
                .hooks(limitResults(hooks, retrievalProperties.getMemory().getHookLimit()))
                .characters(limitResults(characters, retrievalProperties.getMemory().getCharacterLimit()))
                .items(limitResults(items, retrievalProperties.getMemory().getItemLimit()))
                .factions(limitResults(factions, retrievalProperties.getMemory().getFactionLimit()))
                .relations(limitResults(relations, retrievalProperties.getMemory().getRelationLimit()))
                .totalCount(Math.min(recentChapters.size(), retrievalProperties.getMemory().getRecentChapterLimit())
                        + Math.min(segments.size(), retrievalProperties.getMemory().getSegmentLimit())
                        + Math.min(hooks.size(), retrievalProperties.getMemory().getHookLimit())
                        + Math.min(characters.size(), retrievalProperties.getMemory().getCharacterLimit())
                        + Math.min(items.size(), retrievalProperties.getMemory().getItemLimit())
                        + Math.min(factions.size(), retrievalProperties.getMemory().getFactionLimit())
                        + Math.min(relations.size(), retrievalProperties.getMemory().getRelationLimit()))
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
        int fetchK = Math.max(topK * retrievalProperties.getSearch().getDefaultFetchMultiplier(), topK + 2);
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

            double baseScore = asDouble(item.get("score"));
            double rankScore = baseScore;
            List<String> reasons = new ArrayList<>();
            reasons.add("base_score=" + round(baseScore));

            double keywordBoost = 0D;
            double variantBoost = 0D;
            double exactMatchBoost = 0D;
            double primaryFieldBoost = 0D;
            double recencyBoost = 0D;

            if (keywordHits > 0) {
                keywordBoost = keywordHits * retrievalProperties.getRanking().getKeywordHitWeight();
                rankScore += keywordBoost;
                reasons.add("keyword_hits=" + keywordHits);
            }
            if (!hitQueries.isEmpty()) {
                variantBoost = hitQueries.size() * retrievalProperties.getRanking().getVariantHitWeight();
                rankScore += variantBoost;
                reasons.add("variant_hits=" + hitQueries.size());
            }
            if (chapterBoost > 0) {
                rankScore += chapterBoost;
                reasons.add("chapter_boost=" + round(chapterBoost));
            }
            if (!normalizedQuery.isBlank() && combinedText.contains(normalizedQuery)) {
                exactMatchBoost = retrievalProperties.getRanking().getExactMatchBonus();
                rankScore += exactMatchBoost;
                reasons.add("exact_query_match");
            }
            Map<String, Object> primaryFieldSignals = new LinkedHashMap<>();
            primaryFieldBoost = applyPrimaryFieldSignals(collectionName, item, keywords, normalizedQuery, reasons, primaryFieldSignals);
            rankScore += primaryFieldBoost;
            if (recencyAware && currentChapterNum == null && maxChapterNum > 0) {
                recencyBoost = Math.min(retrievalProperties.getRanking().getRecencyMaxBoost(), chapterNum / (double) maxChapterNum * retrievalProperties.getRanking().getRecencyMaxBoost());
                if (recencyBoost > 0) {
                    rankScore += recencyBoost;
                    reasons.add("recency_boost=" + round(recencyBoost));
                }
            }
            if (chapterAware && currentChapterNum != null && chapterDistance >= 0) {
                reasons.add("chapter_distance=" + chapterDistance);
            }

            Map<String, Object> scoreBreakdown = new LinkedHashMap<>();
            scoreBreakdown.put("baseScore", round(baseScore));
            scoreBreakdown.put("keywordBoost", round(keywordBoost));
            scoreBreakdown.put("variantBoost", round(variantBoost));
            scoreBreakdown.put("chapterBoost", round(chapterBoost));
            scoreBreakdown.put("exactMatchBoost", round(exactMatchBoost));
            scoreBreakdown.put("primaryFieldBoost", round(primaryFieldBoost));
            scoreBreakdown.put("recencyBoost", round(recencyBoost));
            scoreBreakdown.put("finalScore", round(rankScore));

            Map<String, Object> recallTrace = new LinkedHashMap<>();
            recallTrace.put("query", queryText == null ? "" : queryText);
            recallTrace.put("currentChapterNum", currentChapterNum);
            recallTrace.put("chapterAware", chapterAware);
            recallTrace.put("matchedQueryVariants", List.copyOf(hitQueries));
            recallTrace.put("queryHits", hitQueries.size());
            recallTrace.put("keywordHits", keywordHits);
            recallTrace.put("rankingSignals", List.copyOf(reasons));
            if (chapterDistance >= 0) {
                recallTrace.put("chapterDistance", chapterDistance);
            }
            if (!primaryFieldSignals.isEmpty()) {
                recallTrace.put("primaryFieldSignals", primaryFieldSignals);
            }
            recallTrace.put("scoreBreakdown", scoreBreakdown);

            item.put("rankScore", round(rankScore));
            item.put("queryHits", hitQueries.size());
            item.put("keywordHits", keywordHits);
            if (currentChapterNum != null && chapterNum > 0) {
                item.put("chapterDistance", chapterDistance);
                item.put("chapterProximityBoost", round(chapterBoost));
            }
            item.put("matchReasons", List.copyOf(reasons));
            item.put("rankExplanation", String.join(" | ", reasons));
            item.put("recallTrace", recallTrace);
            item.put("explanation", buildExplanation(recallTrace, String.join(" | ", reasons)));
            item.remove("_hitQueries");
        }

        candidates.sort(rankComparator());

        List<Map<String, Object>> limited = perChapterLimit > 0
                ? limitByChapter(candidates, topK, perChapterLimit)
                : limitResults(candidates, topK);

        log.info("search [{}] query=[{}], chapter={}, variants={}, results={}, topScore={}",
                collectionName,
                queryText,
                currentChapterNum,
                queryVariants.size(),
                limited.size(),
                limited.isEmpty() ? 0D : asDouble(limited.get(0).get("rankScore")));
        return limited;
    }

    private Comparator<Map<String, Object>> rankComparator() {
        return Comparator
                .comparingDouble((Map<String, Object> item) -> asDouble(item.get("rankScore"))).reversed()
                .thenComparing(Comparator.comparingDouble((Map<String, Object> item) -> asDouble(item.get("score"))).reversed())
                .thenComparingInt(item -> {
                    int chapterDistance = asInt(item.get("chapterDistance"));
                    return chapterDistance < 0 ? Integer.MAX_VALUE : chapterDistance;
                })
                .thenComparing(Comparator.comparingLong((Map<String, Object> item) -> asLong(item.get("chapter_num"))).reversed());
    }

    private double applyPrimaryFieldSignals(String collectionName,
                                            Map<String, Object> item,
                                            List<String> keywords,
                                            String normalizedQuery,
                                            List<String> reasons,
                                            Map<String, Object> primaryFieldSignals) {
        String primaryField = resolvePrimaryField(collectionName);
        if (primaryField == null) {
            primaryFieldSignals.put("boost", 0D);
            return 0D;
        }

        String primaryText = safeLower(Objects.toString(item.get(primaryField), ""));
        primaryFieldSignals.put("field", primaryField);
        if (primaryText.isBlank()) {
            primaryFieldSignals.put("keywordHits", 0);
            primaryFieldSignals.put("exactMatch", false);
            primaryFieldSignals.put("boost", 0D);
            return 0D;
        }

        double boost = 0D;
        int primaryKeywordHits = countKeywordHits(primaryText, keywords);
        primaryFieldSignals.put("keywordHits", primaryKeywordHits);
        if (primaryKeywordHits > 0) {
            double keywordBoost = primaryKeywordHits * retrievalProperties.getRanking().getPrimaryFieldKeywordHitWeight();
            boost += keywordBoost;
            reasons.add(primaryField + "_keyword_hits=" + primaryKeywordHits);
        }

        boolean exactMatch = !normalizedQuery.isBlank() && primaryText.contains(normalizedQuery);
        primaryFieldSignals.put("exactMatch", exactMatch);
        if (exactMatch) {
            boost += retrievalProperties.getRanking().getPrimaryFieldExactMatchBonus();
            reasons.add(primaryField + "_exact_match");
        }

        primaryFieldSignals.put("boost", round(boost));

        return boost;
    }

    private List<Map<String, Object>> prioritizeEventResults(List<Map<String, Object>> results, Set<Long> unresolvedIds) {
        if (results.isEmpty()) {
            return results;
        }

        boolean hasUnresolvedIds = unresolvedIds != null && !unresolvedIds.isEmpty();
        List<Map<String, Object>> prioritized = new ArrayList<>();
        for (Map<String, Object> item : results) {
            Map<String, Object> copy = new LinkedHashMap<>(item);
            List<String> reasons = new ArrayList<>(asStringList(copy.get("matchReasons")));
            double rankScore = asDouble(copy.get("rankScore"));
            long eventId = asLong(copy.get("mysql_event_id"));
            double eventBoost = 0D;
            List<String> eventSignals = new ArrayList<>();

            if (hasUnresolvedIds && unresolvedIds.contains(eventId)) {
                eventBoost += retrievalProperties.getRanking().getUnresolvedEventBonus();
                reasons.add("unresolved_event_boost");
                eventSignals.add("unresolved_event_boost");
            }
            if (isPlotHookEvent(copy)) {
                eventBoost += retrievalProperties.getRanking().getPlotHookBonus();
                reasons.add("plot_hook_priority");
                eventSignals.add("plot_hook_priority");
            }

            rankScore += eventBoost;

            copy.put("rankScore", round(rankScore));
            copy.put("matchReasons", List.copyOf(reasons));
            copy.put("rankExplanation", String.join(" | ", reasons));
            Map<String, Object> recallTrace = ensureMap(copy, "recallTrace");
            recallTrace.put("rankingSignals", List.copyOf(reasons));
            recallTrace.put("eventSignals", List.copyOf(eventSignals));
            Map<String, Object> scoreBreakdown = ensureMap(recallTrace, "scoreBreakdown");
            scoreBreakdown.put("eventBoost", round(eventBoost));
            scoreBreakdown.put("finalScore", round(rankScore));
            copy.put("explanation", buildExplanation(recallTrace, String.join(" | ", reasons)));
            prioritized.add(copy);
        }

        prioritized.sort(rankComparator());
        return prioritized;
    }

    private String resolvePrimaryField(String collectionName) {
        return switch (collectionName) {
            case "novel_characters", "novel_items" -> "name";
            case "novel_events", "novel_faction_inspire" -> "title";
            default -> null;
        };
    }

    private boolean isPlotHookEvent(Map<String, Object> item) {
        if (asInt(item.get("event_type")) == 0) {
            return true;
        }

        String title = safeLower(Objects.toString(item.get("title"), ""));
        String description = safeLower(Objects.toString(item.get("description"), ""));
        return containsHookCue(title) || containsHookCue(description);
    }

    private boolean containsHookCue(String text) {
        return text.contains("hook")
                || text.contains("unresolved")
                || text.contains("suspense")
                || text.contains("clue")
                || text.contains("foreshadow");
    }

    private List<String> asStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).collect(Collectors.toList());
        }
        return List.of();
    }

    private Map<String, Object> searchParams() {
        return Map.of("ef_search", retrievalProperties.getSearch().getEfSearch());
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
                .searchParams(searchParams())
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
            variants.add(baseQuery + " " + retrievalProperties.getHints().getCurrentChapter());
        } else if (!baseQuery.isBlank() && expansionHint != null && !expansionHint.isBlank()) {
            variants.add(baseQuery + " " + expansionHint);
        }

        return variants.stream().limit(retrievalProperties.getSearch().getMaxQueryVariants()).collect(Collectors.toList());
    }

    private List<String> extractKeywords(String queryText) {
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }

        String[] tokens = queryText.toLowerCase(Locale.ROOT)
                .split("[\\s\\p{Punct}，。！？；：（）【】、-]+");

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

    @SuppressWarnings("unchecked")
    private Map<String, Object> ensureMap(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        Map<String, Object> created = new LinkedHashMap<>();
        parent.put(key, created);
        return created;
    }

    private Map<String, Object> buildExplanation(Map<String, Object> recallTrace, String rankExplanation) {
        Map<String, Object> explanation = new LinkedHashMap<>();
        explanation.put("summary", rankExplanation);
        explanation.put("rankingSignals", List.copyOf(asStringList(recallTrace.get("rankingSignals"))));
        explanation.put("matchedQueryVariants", List.copyOf(asStringList(recallTrace.get("matchedQueryVariants"))));
        explanation.put("keywordHits", asInt(recallTrace.get("keywordHits")));

        Object chapterDistance = recallTrace.get("chapterDistance");
        if (chapterDistance != null) {
            explanation.put("chapterDistance", asInt(chapterDistance));
        }

        Object primaryFieldSignals = recallTrace.get("primaryFieldSignals");
        if (primaryFieldSignals instanceof Map<?, ?> map) {
            explanation.put("primaryFieldSignals", new LinkedHashMap<>(castMap(map)));
        }

        List<String> eventSignals = asStringList(recallTrace.get("eventSignals"));
        if (!eventSignals.isEmpty()) {
            explanation.put("eventSignals", List.copyOf(eventSignals));
        }

        Map<String, Object> scoreBreakdown = new LinkedHashMap<>();
        Object rawScoreBreakdown = recallTrace.get("scoreBreakdown");
        if (rawScoreBreakdown instanceof Map<?, ?> map) {
            scoreBreakdown.putAll(castMap(map));
        }
        explanation.put("scoreBreakdown", scoreBreakdown);
        explanation.put("usedChapterBoost", asDouble(scoreBreakdown.get("chapterBoost")) > 0D);
        explanation.put("usedRecencyBoost", asDouble(scoreBreakdown.get("recencyBoost")) > 0D);
        explanation.put("usedEventBoost", asDouble(scoreBreakdown.get("eventBoost")) > 0D);
        return explanation;
    }

    private Map<String, Object> castMap(Map<?, ?> raw) {
        Map<String, Object> converted = new LinkedHashMap<>();
        raw.forEach((key, value) -> converted.put(String.valueOf(key), value));
        return converted;
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

    private int asInt(Object value) {
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
