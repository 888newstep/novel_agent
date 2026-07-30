package com.novel.agent.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.novel.agent.entity.KnowledgeSegment;
import com.novel.agent.entity.NovelFile;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.InsertReq;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBatchProcessor {

    private final MilvusClientV2 milvusClient;
    private final EmbeddingService embeddingService;
    private final NovelFileScanner fileScanner;
    private final NovelTxtParser txtParser;

    @Value("${knowledge.batch-size:10}")
    private int batchSize;

    private static final String COLLECTION_NAME = "knowledge_base";

    /**
     * 批量处理所有未处理的 TXT 文件
     */
    public ProcessResult processAll() {
        List<NovelFile> files = fileScanner.scanUnprocessed();
        ProcessResult result = new ProcessResult();

        for (NovelFile file : files) {
            if (file.isProcessed()) {
                result.skipped++;
                continue;
            }

            log.info("处理: {}", file.getFileName());
            ProcessFileResult fileResult = processFile(Paths.get(file.getFilePath()));

            result.totalSegments += fileResult.totalSegments;
            result.storedSegments += fileResult.storedSegments;
            result.failedSegments += fileResult.failedSegments;
            result.processedFiles++;

            fileScanner.markProcessed(file.getFileName());
        }

        log.info("处理完成: {} 本小说, {} 条存入 Milvus, {} 条失败, {} 条跳过",
                result.processedFiles, result.storedSegments, result.failedSegments, result.skipped);
        return result;
    }

    /**
     * 处理单本小说
     */
    public ProcessFileResult processFile(Path filePath) {
        ProcessFileResult result = new ProcessFileResult();

        // 1. 解析 TXT
        List<KnowledgeSegment> segments = txtParser.parse(filePath);
        result.totalSegments = segments.size();

        if (segments.isEmpty()) {
            return result;
        }

        // 2. 批量向量化并存入
        List<KnowledgeSegment> batch = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            batch.add(segments.get(i));

            if (batch.size() >= batchSize || i == segments.size() - 1) {
                try {
                    int stored = processBatch(batch);
                    result.storedSegments += stored;
                    result.failedSegments += (batch.size() - stored);
                } catch (Exception e) {
                    log.error("批次处理失败", e);
                    result.failedSegments += batch.size();
                }
                batch.clear();

                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }
        }

        log.info("[{}] 总段落: {}, 已存: {}, 失败: {}",
                filePath.getFileName(), result.totalSegments, result.storedSegments, result.failedSegments);
        return result;
    }

    /**
     * 处理一批段落：向量化 → 存入 Milvus
     */
    private int processBatch(List<KnowledgeSegment> segments) {
        // 1. 批量向量化
        List<String> texts = segments.stream()
                .map(KnowledgeSegment::getContent)
                .toList();

        List<List<Float>> embeddings = embeddingService.batchGenerateEmbedding(texts);

        // 2. 过滤失败
        List<List<Float>> validEmbeddings = new ArrayList<>();
        List<KnowledgeSegment> validSegments = new ArrayList<>();

        for (int i = 0; i < embeddings.size(); i++) {
            if (embeddings.get(i) != null) {
                validEmbeddings.add(embeddings.get(i));
                validSegments.add(segments.get(i));
            }
        }

        if (validSegments.isEmpty()) {
            return 0;
        }

        // 3. 存入 Milvus（行式格式）
        List<JsonObject> rows = new ArrayList<>();
        for (int i = 0; i < validSegments.size(); i++) {
            KnowledgeSegment seg = validSegments.get(i);
            List<Float> embedding = validEmbeddings.get(i);

            JsonObject row = new JsonObject();
            row.addProperty("content_hash", seg.getContentHash());
            row.addProperty("source", seg.getSource());
            row.addProperty("category", seg.getCategory());
            row.addProperty("chapter", seg.getChapter());
            row.addProperty("content_len", seg.getContentLength());

            JsonArray vecArray = new JsonArray();
            for (Float v : embedding) {
                vecArray.add(v);
            }
            row.add("embedding", vecArray);

            rows.add(row);
        }

        InsertReq insertReq = InsertReq.builder()
                .collectionName(COLLECTION_NAME)
                .data(rows)
                .build();

        milvusClient.insert(insertReq);

        return validSegments.size();
    }

    // =============================================
    // 结果类
    // =============================================

    public static class ProcessResult {
        public int processedFiles = 0;
        public int totalSegments = 0;
        public int storedSegments = 0;
        public int failedSegments = 0;
        public int skipped = 0;
    }

    public static class ProcessFileResult {
        public int totalSegments = 0;
        public int storedSegments = 0;
        public int failedSegments = 0;
    }
}