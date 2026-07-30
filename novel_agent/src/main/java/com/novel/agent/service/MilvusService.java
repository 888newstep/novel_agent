package com.novel.agent.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Milvus 写入服务
 * 阶段2：日常连载增量写入
 * 支持5个集合的批量写入、flush控制、实体更新（删除+重插）
 * 数据全部写入完成后，由 MilvusAdminService 统一建索引
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusService {

    private final MilvusClientV2 milvusClient;
    private final EmbeddingService embeddingService;

    @Value("${milvus.write.batch-size:64}")
    private int batchSize;

    @Value("${milvus.write.flush-interval:8}")
    private int flushInterval;

    /** 批次计数器，用于 flush 控制 */
    private final AtomicInteger batchCounter = new AtomicInteger(0);

    // =============================================
    // 1. 文本拼接模板（文档规范）
    // =============================================

    /**
     * 剧情片段：章节{chapter_num}：{content原文片段}
     */
    public String buildSegmentText(Integer chapterNum, String content) {
        return String.format("章节%d：%s", chapterNum, content.trim().replaceAll("\\s+", " "));
    }

    /**
     * 伏笔/事件：事件类型：{type释义}，发生章节{chapter_num}，事件标题：{title}，详情：{description}
     */
    public String buildEventText(Integer eventType, Integer chapterNum, String title, String description) {
        String typeName = switch (eventType) {
            case 0 -> "伏笔";
            case 1 -> "转折";
            case 2 -> "预兆";
            case 3 -> "高潮";
            default -> "未知";
        };
        return String.format("事件类型：%s，发生章节%d，事件标题：%s，详情：%s",
                typeName, chapterNum, title, description.trim().replaceAll("\\s+", " "));
    }

    /**
     * 角色人设：角色姓名{name}，身份{identity}，灵根{element_main}，资质{talent}，性格{personality}，背景{backstory}，人物关系：{relations}
     */
    public String buildCharacterText(String name, String identity, String elementMain,
                                      String talent, String personality, String backstory, String relations) {
        return String.format("角色姓名%s，身份%s，灵根%s，资质%s，性格%s，背景%s，人物关系：%s",
                nullToEmpty(name), nullToEmpty(identity), nullToEmpty(elementMain),
                nullToEmpty(talent), nullToEmpty(personality), nullToEmpty(backstory),
                nullToEmpty(relations));
    }

    /**
     * 法宝 (item_type=0)：法宝{name}，品质{talent}，属性{element}，品阶{rank}，功能描述：{description}
     */
    public String buildArtifactText(String name, String talent, String element, String rank, String description) {
        return String.format("法宝%s，品质%s，属性%s，品阶%s，功能描述：%s",
                nullToEmpty(name), nullToEmpty(talent), nullToEmpty(element),
                nullToEmpty(rank), nullToEmpty(description));
    }

    /**
     * 功法 (item_type=1)：功法{name}，品级{talent}，属性{element}，品阶{rank}，修炼效果：{description}，当前修炼阶段{stage}
     */
    public String buildSkillText(String name, String talent, String element, String rank, String description, String stage) {
        return String.format("功法%s，品级%s，属性%s，品阶%s，修炼效果：%s，当前修炼阶段%s",
                nullToEmpty(name), nullToEmpty(talent), nullToEmpty(element),
                nullToEmpty(rank), nullToEmpty(description), nullToEmpty(stage));
    }

    /**
     * 势力 (source_type=0)：宗门{name}，势力等级{rank}，属性偏向{element}，定位与介绍：{description}
     */
    public String buildFactionText(String name, String rank, String element, String description) {
        return String.format("宗门%s，势力等级%s，属性偏向%s，定位与介绍：%s",
                nullToEmpty(name), nullToEmpty(rank), nullToEmpty(element), nullToEmpty(description));
    }

    /**
     * 灵感 (source_type=1)：灵感分类{category}，内容：{content}
     */
    public String buildInspirationText(String category, String content) {
        return String.format("灵感分类%s，内容：%s",
                nullToEmpty(category), nullToEmpty(content));
    }

    // =============================================
    // 2. 批量写入（核心方法）
    // =============================================

    /**
     * 写入一章节的剧情片段到 novel_segments
     * @param novelId 小说ID
     * @param chapterNum 章节号
     * @param segments 片段列表 [{"type": 0, "content": "..."}, ...]
     */
    public void insertChapterSegments(Long novelId, Integer chapterNum,
                                       List<Map<String, Object>> segments) {
        long timestamp = System.currentTimeMillis() / 1000;
        List<JsonObject> rows = new ArrayList<>();

        for (Map<String, Object> seg : segments) {
            String content = (String) seg.get("content");
            String embedText = buildSegmentText(chapterNum, content);
            List<Float> vector = embeddingService.generateEmbedding(embedText);

            JsonObject row = new JsonObject();
            row.addProperty("novel_id", novelId);
            row.addProperty("chapter_num", chapterNum);
            row.addProperty("segment_type", (Integer) seg.get("type"));
            row.addProperty("content", content);
            row.add("embedding", toJsonArray(vector));
            row.addProperty("ts", timestamp);
            rows.add(row);
        }

        batchInsert("novel_segments", rows);
        log.info("第 {} 章的 {} 个片段已存入 Milvus", chapterNum, segments.size());
    }

    /**
     * 写入关键事件/伏笔到 novel_events
     * resolved 状态只在 MySQL 维护，Milvus 不存储
     */
    public void insertKeyEvent(Long novelId, Long eventId, Integer chapterNum,
                                Integer eventType, String title, String description) {
        long timestamp = System.currentTimeMillis() / 1000;
        String embedText = buildEventText(eventType, chapterNum, title, description);
        List<Float> vector = embeddingService.generateEmbedding(embedText);

        JsonObject row = new JsonObject();
        row.addProperty("novel_id", novelId);
        row.addProperty("mysql_event_id", eventId);
        row.addProperty("chapter_num", chapterNum);
        row.addProperty("event_type", eventType);
        row.addProperty("title", title);
        row.addProperty("description", description);
        row.add("embedding", toJsonArray(vector));
        row.addProperty("ts", timestamp);

        batchInsert("novel_events", List.of(row));
        log.info("伏笔 [{}] 已存入 Milvus", title);
    }

    /**
     * 写入角色人设到 novel_characters
     */
    public void insertCharacter(Long novelId, Long charId, String name,
                                 String identity, String elementMain, String talent,
                                 String personality, String backstory, String relations) {
        long timestamp = System.currentTimeMillis() / 1000;
        String embedText = buildCharacterText(name, identity, elementMain, talent, personality, backstory, relations);
        List<Float> vector = embeddingService.generateEmbedding(embedText);

        JsonObject row = new JsonObject();
        row.addProperty("novel_id", novelId);
        row.addProperty("mysql_char_id", charId);
        row.addProperty("name", name);
        row.addProperty("char_text", embedText);
        row.add("embedding", toJsonArray(vector));
        row.addProperty("ts", timestamp);

        batchInsert("novel_characters", List.of(row));
        log.info("角色 [{}] 已存入 Milvus", name);
    }

    /**
     * 写入法宝/功法到 novel_items
     * @param itemType 0=法宝, 1=功法
     */
    public void insertItem(Long novelId, Long itemId, Integer itemType, String name,
                            String talent, String element, String rank,
                            String description, String stage) {
        long timestamp = System.currentTimeMillis() / 1000;
        String embedText;
        if (itemType == 0) {
            embedText = buildArtifactText(name, talent, element, rank, description);
        } else {
            embedText = buildSkillText(name, talent, element, rank, description, stage);
        }
        List<Float> vector = embeddingService.generateEmbedding(embedText);

        JsonObject row = new JsonObject();
        row.addProperty("novel_id", novelId);
        row.addProperty("mysql_item_id", itemId);
        row.addProperty("item_type", itemType);
        row.addProperty("name", name);
        row.addProperty("item_text", embedText);
        row.add("embedding", toJsonArray(vector));
        row.addProperty("ts", timestamp);

        batchInsert("novel_items", List.of(row));
        log.info("物品 [{}] (type={}) 已存入 Milvus", name, itemType);
    }

    /**
     * 写入势力/灵感到 novel_faction_inspire
     * @param sourceType 0=势力, 1=灵感
     * @param novelId 0=全局通用灵感
     */
    public void insertFactionOrInspiration(Long novelId, Long refId, Integer sourceType,
                                            String title, String content) {
        long timestamp = System.currentTimeMillis() / 1000;
        String embedText;
        if (sourceType == 0) {
            // 势力 — 需要额外参数简化处理，这里用 title 作为 name, content 作为描述
            embedText = buildFactionText(title, "", "", content);
        } else {
            embedText = buildInspirationText(title, content);
        }
        List<Float> vector = embeddingService.generateEmbedding(embedText);

        JsonObject row = new JsonObject();
        row.addProperty("novel_id", novelId);
        row.addProperty("mysql_ref_id", refId);
        row.addProperty("source_type", sourceType);
        row.addProperty("title", title);
        row.addProperty("content", content);
        row.add("embedding", toJsonArray(vector));
        row.addProperty("ts", timestamp);

        batchInsert("novel_faction_inspire", List.of(row));
        log.info("势力/灵感 [{}] (type={}) 已存入 Milvus", title, sourceType);
    }

    // =============================================
    // 3. 批量插入 + flush 控制
    // =============================================

    /**
     * 批量插入数据，每 flushInterval 批执行一次 flush
     */
    private void batchInsert(String collectionName, List<JsonObject> rows) {
        if (rows.isEmpty()) return;

        InsertReq insertReq = InsertReq.builder()
                .collectionName(collectionName)
                .data(rows)
                .build();

        milvusClient.insert(insertReq);
        log.debug("批次插入 [{}] {} 条", collectionName, rows.size());

        // flush 控制：每 flushInterval 批 flush 一次
        int count = batchCounter.incrementAndGet();
        if (count % flushInterval == 0) {
            milvusClient.flush(io.milvus.v2.service.utility.request.FlushReq.builder()
                    .collectionNames(List.of(collectionName))
                    .build());
            log.info("自动 flush [{}]（第 {} 批）", collectionName, count);
        }
    }

    /**
     * 重置批次计数器（全量重建前调用）
     */
    public void resetBatchCounter() {
        batchCounter.set(0);
    }

    // =============================================
    // 4. 实体更新（阶段3：删除旧向量 + 插入新向量）
    // =============================================

    /**
     * 更新实体：先按 novel_id + mysql_ref_id 删除旧向量，再插入新向量
     */
    public void deleteEntity(String collectionName, Long novelId, String refField, Long refId) {
        milvusClient.delete(DeleteReq.builder()
                .collectionName(collectionName)
                .filter(String.format("novel_id == %d && %s == %d", novelId, refField, refId))
                .build());
        log.info("已删除 [{}] novel_id={}, {}={}", collectionName, novelId, refField, refId);
    }

    // =============================================
    // 辅助
    // =============================================

    private JsonArray toJsonArray(List<Float> vector) {
        JsonArray arr = new JsonArray();
        for (Float v : vector) {
            arr.add(v);
        }
        return arr;
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}