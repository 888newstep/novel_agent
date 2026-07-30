package com.novel.agent.config;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * Milvus 配置
 * 阶段1：项目启动初始化 — 仅建空集合，不建索引、不加载
 * 索引和加载由 MilvusAdminService 在数据全部写入完成后统一执行
 */
@Slf4j
@Configuration
public class MilvusConfig {

    @Value("${milvus.host:49.234.187.76}")
    private String host;

    @Value("${milvus.port:19530}")
    private int port;

    @Bean
    public MilvusClientV2 milvusClient() {
        ConnectConfig config = ConnectConfig.builder()
                .uri("http://" + host + ":" + port)
                .build();
        MilvusClientV2 client = new MilvusClientV2(config);
        log.info("Milvus 连接成功: {}:{}", host, port);
        return client;
    }

    /**
     * 集合初始化器 — 启动时只建空集合，不建索引、不加载
     * 索引构建统一在数据全部写入后由 MilvusAdminService 执行
     */
    @Slf4j
    @Component
    @RequiredArgsConstructor
    public static class MilvusCollectionInitializer {

        private final MilvusClientV2 milvusClient;

        @Value("${milvus.collection.segments:novel_segments}")
        private String segmentsCollection;

        @Value("${milvus.collection.events:novel_events}")
        private String eventsCollection;

        @Value("${milvus.collection.characters:novel_characters}")
        private String charactersCollection;

        @Value("${milvus.collection.items:novel_items}")
        private String itemsCollection;

        @Value("${milvus.collection.faction_inspire:novel_faction_inspire}")
        private String factionInspireCollection;

        @PostConstruct
        public void init() {
            createCollectionIfNotExists(segmentsCollection, this::buildSegmentsSchema);
            createCollectionIfNotExists(eventsCollection, this::buildEventsSchema);
            createCollectionIfNotExists(charactersCollection, this::buildCharactersSchema);
            createCollectionIfNotExists(itemsCollection, this::buildItemsSchema);
            createCollectionIfNotExists(factionInspireCollection, this::buildFactionInspireSchema);
            log.info("Milvus 5个 Collections 空集合初始化完成（未建索引、未加载）");
        }

        private void createCollectionIfNotExists(String name, SchemaBuilder builder) {
            if (milvusClient.hasCollection(HasCollectionReq.builder().collectionName(name).build())) {
                log.info("Collection [{}] 已存在，跳过创建", name);
                return;
            }
            CreateCollectionReq.CollectionSchema schema = builder.build();
            milvusClient.createCollection(CreateCollectionReq.builder()
                    .collectionName(name)
                    .collectionSchema(schema)
                    .build());
            log.info("Collection [{}] 空集合创建成功", name);
        }

        @FunctionalInterface
        private interface SchemaBuilder {
            CreateCollectionReq.CollectionSchema build();
        }

        // =============================================
        // Collection 1: novel_segments 剧情片段库
        // 对应 MySQL chapters，字段：id, novel_id, chapter_num, segment_type, content, embedding, ts
        // =============================================
        private CreateCollectionReq.CollectionSchema buildSegmentsSchema() {
            CreateCollectionReq.CollectionSchema schema = milvusClient.createSchema();
            schema.addField(AddFieldReq.builder().fieldName("id").dataType(DataType.Int64).isPrimaryKey(true).autoID(true).description("自增主键").build());
            schema.addField(AddFieldReq.builder().fieldName("novel_id").dataType(DataType.Int64).description("小说ID，过滤字段").build());
            schema.addField(AddFieldReq.builder().fieldName("chapter_num").dataType(DataType.Int64).description("所属章节").build());
            schema.addField(AddFieldReq.builder().fieldName("segment_type").dataType(DataType.Int32).description("0叙事/1对话/2环境/3战斗/4灵感/5伏笔").build());
            schema.addField(AddFieldReq.builder().fieldName("content").dataType(DataType.VarChar).maxLength(8192).description("剧情片段原文").build());
            schema.addField(AddFieldReq.builder().fieldName("embedding").dataType(DataType.FloatVector).dimension(1024).description("bge-m3 向量").build());
            schema.addField(AddFieldReq.builder().fieldName("ts").dataType(DataType.Int64).description("写入时间戳").build());
            return schema;
        }

        // =============================================
        // Collection 2: novel_events 伏笔/关键事件库
        // 对应 MySQL key_events，字段：id, novel_id, mysql_event_id, chapter_num, event_type, title, description, embedding, ts
        // 注意：resolved 状态只在 MySQL 维护，Milvus 不存储
        // =============================================
        private CreateCollectionReq.CollectionSchema buildEventsSchema() {
            CreateCollectionReq.CollectionSchema schema = milvusClient.createSchema();
            schema.addField(AddFieldReq.builder().fieldName("id").dataType(DataType.Int64).isPrimaryKey(true).autoID(true).description("自增主键").build());
            schema.addField(AddFieldReq.builder().fieldName("novel_id").dataType(DataType.Int64).description("小说ID，过滤字段").build());
            schema.addField(AddFieldReq.builder().fieldName("mysql_event_id").dataType(DataType.Int64).description("对应 key_events.id，回查MySQL用").build());
            schema.addField(AddFieldReq.builder().fieldName("chapter_num").dataType(DataType.Int64).description("事件发生章节").build());
            schema.addField(AddFieldReq.builder().fieldName("event_type").dataType(DataType.Int32).description("0伏笔/1转折/2预兆/3高潮").build());
            schema.addField(AddFieldReq.builder().fieldName("title").dataType(DataType.VarChar).maxLength(500).description("事件标题").build());
            schema.addField(AddFieldReq.builder().fieldName("description").dataType(DataType.VarChar).maxLength(8192).description("事件描述").build());
            schema.addField(AddFieldReq.builder().fieldName("embedding").dataType(DataType.FloatVector).dimension(1024).description("bge-m3 向量").build());
            schema.addField(AddFieldReq.builder().fieldName("ts").dataType(DataType.Int64).description("写入时间戳").build());
            return schema;
        }

        // =============================================
        // Collection 3: novel_characters 角色人设库
        // 对应 MySQL characters + relations，字段：id, novel_id, mysql_char_id, name, char_text, embedding, ts
        // =============================================
        private CreateCollectionReq.CollectionSchema buildCharactersSchema() {
            CreateCollectionReq.CollectionSchema schema = milvusClient.createSchema();
            schema.addField(AddFieldReq.builder().fieldName("id").dataType(DataType.Int64).isPrimaryKey(true).autoID(true).description("自增主键").build());
            schema.addField(AddFieldReq.builder().fieldName("novel_id").dataType(DataType.Int64).description("小说ID，过滤字段").build());
            schema.addField(AddFieldReq.builder().fieldName("mysql_char_id").dataType(DataType.Int64).description("对应 characters.id，回查MySQL用").build());
            schema.addField(AddFieldReq.builder().fieldName("name").dataType(DataType.VarChar).maxLength(100).description("角色名称").build());
            schema.addField(AddFieldReq.builder().fieldName("char_text").dataType(DataType.VarChar).maxLength(8192).description("人设整合文本").build());
            schema.addField(AddFieldReq.builder().fieldName("embedding").dataType(DataType.FloatVector).dimension(1024).description("bge-m3 向量").build());
            schema.addField(AddFieldReq.builder().fieldName("ts").dataType(DataType.Int64).description("写入时间戳").build());
            return schema;
        }

        // =============================================
        // Collection 4: novel_items 法宝+功法合并库
        // 对应 artifacts + skills，item_type 区分：0=法宝(artifact), 1=功法(skill)
        // 字段：id, novel_id, mysql_item_id, item_type, name, item_text, embedding, ts
        // =============================================
        private CreateCollectionReq.CollectionSchema buildItemsSchema() {
            CreateCollectionReq.CollectionSchema schema = milvusClient.createSchema();
            schema.addField(AddFieldReq.builder().fieldName("id").dataType(DataType.Int64).isPrimaryKey(true).autoID(true).description("自增主键").build());
            schema.addField(AddFieldReq.builder().fieldName("novel_id").dataType(DataType.Int64).description("小说ID，过滤字段").build());
            schema.addField(AddFieldReq.builder().fieldName("mysql_item_id").dataType(DataType.Int64).description("对应 artifacts.id / skills.id，回查MySQL用").build());
            schema.addField(AddFieldReq.builder().fieldName("item_type").dataType(DataType.Int32).description("0=法宝 artifact, 1=功法 skill").build());
            schema.addField(AddFieldReq.builder().fieldName("name").dataType(DataType.VarChar).maxLength(100).description("法宝/功法名").build());
            schema.addField(AddFieldReq.builder().fieldName("item_text").dataType(DataType.VarChar).maxLength(8192).description("物品整合描述").build());
            schema.addField(AddFieldReq.builder().fieldName("embedding").dataType(DataType.FloatVector).dimension(1024).description("bge-m3 向量").build());
            schema.addField(AddFieldReq.builder().fieldName("ts").dataType(DataType.Int64).description("写入时间戳").build());
            return schema;
        }

        // =============================================
        // Collection 5: novel_faction_inspire 势力+灵感合并库
        // 对应 factions + inspirations，source_type 区分：0=宗门势力(faction), 1=写作灵感(inspiration)
        // 字段：id, novel_id, mysql_ref_id, source_type, title, content, embedding, ts
        // =============================================
        private CreateCollectionReq.CollectionSchema buildFactionInspireSchema() {
            CreateCollectionReq.CollectionSchema schema = milvusClient.createSchema();
            schema.addField(AddFieldReq.builder().fieldName("id").dataType(DataType.Int64).isPrimaryKey(true).autoID(true).description("自增主键").build());
            schema.addField(AddFieldReq.builder().fieldName("novel_id").dataType(DataType.Int64).description("小说ID，过滤字段；0=全局通用灵感").build());
            schema.addField(AddFieldReq.builder().fieldName("mysql_ref_id").dataType(DataType.Int64).description("对应 factions.id / inspirations.id，回查MySQL用").build());
            schema.addField(AddFieldReq.builder().fieldName("source_type").dataType(DataType.Int32).description("0=宗门势力 faction, 1=写作灵感 inspiration").build());
            schema.addField(AddFieldReq.builder().fieldName("title").dataType(DataType.VarChar).maxLength(200).description("势力名/灵感标题").build());
            schema.addField(AddFieldReq.builder().fieldName("content").dataType(DataType.VarChar).maxLength(8192).description("势力介绍/灵感完整内容").build());
            schema.addField(AddFieldReq.builder().fieldName("embedding").dataType(DataType.FloatVector).dimension(1024).description("bge-m3 向量").build());
            schema.addField(AddFieldReq.builder().fieldName("ts").dataType(DataType.Int64).description("写入时间戳").build());
            return schema;
        }
    }
}