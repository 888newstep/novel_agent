package com.novel.agent.config;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 外部知识库集合初始化
 * 仅建空集合，不建索引、不加载
 * 索引和加载由 MilvusAdminService 统一管理
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilvusKnowledgeConfig {

    private final MilvusClientV2 milvusClient;

    private static final String COLLECTION_NAME = "knowledge_base";

    @PostConstruct
    public void init() {
        if (milvusClient.hasCollection(HasCollectionReq.builder()
                .collectionName(COLLECTION_NAME).build())) {
            log.info("Collection [{}] 已存在，跳过创建", COLLECTION_NAME);
            return;
        }

        CreateCollectionReq.CollectionSchema schema = milvusClient.createSchema();
        schema.addField(AddFieldReq.builder()
                .fieldName("id").dataType(DataType.Int64).isPrimaryKey(true).autoID(true)
                .description("自增主键").build());
        schema.addField(AddFieldReq.builder()
                .fieldName("content_hash").dataType(DataType.VarChar).maxLength(64)
                .description("内容MD5哈希，用于去重").build());
        schema.addField(AddFieldReq.builder()
                .fieldName("source").dataType(DataType.VarChar).maxLength(200)
                .description("来源书名").build());
        schema.addField(AddFieldReq.builder()
                .fieldName("category").dataType(DataType.VarChar).maxLength(50)
                .description("分类：仙侠/都市/玄幻/科幻").build());
        schema.addField(AddFieldReq.builder()
                .fieldName("chapter").dataType(DataType.VarChar).maxLength(200)
                .description("章节名").build());
        schema.addField(AddFieldReq.builder()
                .fieldName("content_len").dataType(DataType.Int32)
                .description("内容长度").build());
        schema.addField(AddFieldReq.builder()
                .fieldName("embedding").dataType(DataType.FloatVector).dimension(1024)
                .description("bge-m3 向量").build());

        milvusClient.createCollection(CreateCollectionReq.builder()
                .collectionName(COLLECTION_NAME)
                .collectionSchema(schema)
                .description("外部网文知识库（仅向量和元数据，无原文）")
                .build());

        log.info("Collection [{}] 空集合创建成功（未建索引、未加载）", COLLECTION_NAME);
    }
}