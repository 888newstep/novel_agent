package com.novel.agent.service;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexBuildState;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.request.ReleaseCollectionReq;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.index.request.DescribeIndexReq;
import io.milvus.v2.service.index.request.ListIndexesReq;
import io.milvus.v2.service.utility.request.CompactReq;
import io.milvus.v2.service.utility.request.FlushReq;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Milvus 管理服务
 * 负责：建索引、flush、compact、load/release、按 novel_id 批量删除、全量重建
 * 索引构建采用"数据全部写入后统一构建"策略，避免增量构建的精度损失
 */
@Slf4j
@Service
public class MilvusAdminService {

    private final MilvusClientV2 milvusClient;
    private final Executor localTaskExecutor;

    public MilvusAdminService(
            MilvusClientV2 milvusClient,
            @Qualifier("localTaskExecutor") Executor localTaskExecutor) {
        this.milvusClient = milvusClient;
        this.localTaskExecutor = localTaskExecutor;
    }

    /** 5 个业务集合名称 */
    private static final List<String> COLLECTION_NAMES = List.of(
            "novel_segments",
            "novel_events",
            "novel_characters",
            "novel_items",
            "novel_faction_inspire"
    );

    /** 外部知识库集合 */
    private static final String KNOWLEDGE_COLLECTION = "knowledge_base";

    // =============================================
    // 1. 索引管理
    // =============================================

    /**
     * 为指定集合创建 HNSW 索引
     * 参数：M=16, efConstruction=200, metric=COSINE
     */
    public void createIndex(String collectionName) {
        IndexParam indexParam = IndexParam.builder()
                .fieldName("embedding")
                .metricType(IndexParam.MetricType.COSINE)
                .indexType(IndexParam.IndexType.HNSW)
                .extraParams(Map.of("M", 16, "efConstruction", 200))
                .build();

        milvusClient.createIndex(CreateIndexReq.builder()
                .collectionName(collectionName)
                .indexParams(List.of(indexParam))
                .build());

        log.info("Collection [{}] HNSW 索引创建指令已发出", collectionName);
    }

    /**
     * 为所有 5 个业务集合创建索引（串行执行，避免 2 核 CPU 打满）
     */
    public void createIndexForAll() {
        for (String name : COLLECTION_NAMES) {
            createIndex(name);
            // 串行等待，避免云服务器 2 核 CPU 过载
            sleep(2000);
        }
        // 可选：外部知识库也建索引
        createIndex(KNOWLEDGE_COLLECTION);
        log.info("所有集合索引创建指令已发出");
    }

    /**
     * 异步构建索引（轮询监控进度）
     */
    public CompletableFuture<Void> buildIndexAsync(String collectionName) {
        return submitAsync("index build: " + collectionName, () -> {
            createIndex(collectionName);
            log.info("开始监控 [{}] 索引构建进度...", collectionName);
            pollIndexProgress(collectionName);
            log.info("Collection [{}] 索引构建完成", collectionName);
        });
    }

    /**
     * 异步串行构建所有集合索引
     */
    public CompletableFuture<Void> buildAllIndexesAsync() {
        return submitAsync("all Milvus index builds", this::buildAllIndexes);
    }

    private void buildAllIndexes() {
        for (String name : COLLECTION_NAMES) {
            createIndex(name);
            pollIndexProgress(name);
            sleep(3000);
        }
        createIndex(KNOWLEDGE_COLLECTION);
        pollIndexProgress(KNOWLEDGE_COLLECTION);
        log.info("全部集合索引构建完成");
    }

    /**
     * 轮询索引构建进度，直到完成（公开方法，供外部调用）
     */
    public void pollIndexProgress(String collectionName) {
        int maxRetries = 120;  // 最多等待 120 轮（约 20 分钟 @ 10s/轮）
        for (int i = 0; i < maxRetries; i++) {
            try {
                DescribeIndexReq req = DescribeIndexReq.builder()
                        .collectionName(collectionName)
                        .build();
                var resp = milvusClient.describeIndex(req);
                var indexDesc = resp.getIndexDescByFieldName("embedding");
                if (indexDesc == null) {
                    log.info("[{}] 索引描述信息尚未就绪，继续等待... ({}/{})", collectionName, i + 1, maxRetries);
                    sleep(10000);
                    continue;
                }
                IndexBuildState state = indexDesc.getIndexState();
                long progress = indexDesc.getTotalRows() > 0
                        ? indexDesc.getIndexedRows() * 100 / indexDesc.getTotalRows()
                        : 0;
                log.info("[{}] 索引状态: {} 进度: {}% ({}/{}) indexedRows={}/{}",
                        collectionName, state, progress, i + 1, maxRetries,
                        indexDesc.getIndexedRows(), indexDesc.getTotalRows());

                if (state == IndexBuildState.Finished) {
                    log.info("[{}] 索引构建完成", collectionName);
                    return;
                }
                if (state == IndexBuildState.Failed) {
                    log.error("[{}] 索引构建失败: {}", collectionName, indexDesc.getIndexFailedReason());
                    return;
                }
            } catch (Exception e) {
                log.warn("[{}] 查询索引状态异常: {}", collectionName, e.getMessage());
            }
            sleep(10000);
        }
        log.warn("[{}] 索引构建监控超时，请通过 Attu 手动确认状态", collectionName);
    }

    // =============================================
    // 2. Flush
    // =============================================

    /**
     * 刷新单个集合，落盘数据
     */
    public void flush(String collectionName) {
        milvusClient.flush(FlushReq.builder()
                .collectionNames(List.of(collectionName))
                .build());
        log.info("Collection [{}] flush 完成", collectionName);
    }

    /**
     * 刷新所有集合
     */
    public void flushAll() {
        forEachCollection(this::flush);
        log.info("所有集合 flush 完成");
    }

    // =============================================
    // 3. Load / Release 集合
    // =============================================

    /**
     * 加载集合到内存，启用检索
     */
    public void loadCollection(String collectionName) {
        milvusClient.loadCollection(LoadCollectionReq.builder()
                .collectionName(collectionName)
                .build());
        log.info("Collection [{}] 已加载到内存", collectionName);
    }

    /**
     * 加载所有集合
     */
    public void loadAllCollections() {
        forEachCollection(this::loadCollectionIfReady);
        log.info("所有集合已加载到内存");
    }

    /**
     * 释放集合，释放内存
     */
    private void loadCollectionIfReady(String collectionName) {
        try {
            List<String> indexNames = milvusClient.listIndexes(ListIndexesReq.builder()
                    .collectionName(collectionName)
                    .fieldName("embedding")
                    .build());
            if (indexNames == null || indexNames.isEmpty()) {
                log.info("Milvus collection [{}] has no embedding index; skip load", collectionName);
                return;
            }
            var indexDesc = milvusClient.describeIndex(DescribeIndexReq.builder()
                    .collectionName(collectionName)
                    .fieldName("embedding")
                    .indexName(indexNames.get(0))
                    .build())
                    .getIndexDescByFieldName("embedding");
            if (indexDesc == null) {
                log.info("Milvus collection [{}] has no embedding index; skip load", collectionName);
                return;
            }
            if (indexDesc.getIndexState() != IndexBuildState.Finished) {
                log.info("Milvus collection [{}] index state is {}; skip load",
                        collectionName, indexDesc.getIndexState());
                return;
            }
            loadCollection(collectionName);
        } catch (Exception e) {
            log.warn("Milvus collection [{}] load failed; continue with other collections: {}",
                    collectionName, e.getMessage());
        }
    }

    public void releaseCollection(String collectionName) {
        milvusClient.releaseCollection(ReleaseCollectionReq.builder()
                .collectionName(collectionName)
                .build());
        log.info("Collection [{}] 已从内存释放", collectionName);
    }

    // =============================================
    // 4. Compact 碎片整理
    // =============================================

    /**
     * 对集合执行 compact，合并分片
     */
    public void compact(String collectionName) {
        milvusClient.compact(CompactReq.builder()
                .collectionName(collectionName)
                .build());
        log.info("Collection [{}] compact 指令已发出", collectionName);
    }

    /**
     * 对所有集合执行 compact
     */
    public void compactAll() {
        forEachCollection(this::compact);
        log.info("所有集合 compact 指令已发出");
    }

    // =============================================
    // 5. 按 novel_id 批量删除
    // =============================================

    /**
     * 按 novel_id 删除指定集合中所有向量
     */
    private void deleteSafely(io.milvus.v2.service.vector.request.DeleteReq request) {
        try {
            milvusClient.delete(request);
        } catch (Exception e) {
            log.warn("Milvus delete skipped because collection is not ready: {}", e.getMessage());
        }
    }

    public void deleteByNovelId(String collectionName, Long novelId) {
        deleteSafely(io.milvus.v2.service.vector.request.DeleteReq.builder()
                .collectionName(collectionName)
                .filter("novel_id == " + novelId)
                .build());
        log.info("Collection [{}] 中 novel_id={} 的数据已删除", collectionName, novelId);
    }

    /**
     * 在所有集合中按 novel_id 批量删除
     */
    public void deleteByNovelIdAll(Long novelId) {
        for (String name : COLLECTION_NAMES) {
            deleteByNovelId(name, novelId);
        }
        log.info("所有集合中 novel_id={} 的数据已删除", novelId);
    }

    // =============================================
    // 6. 全量重建流程（阶段 4）
    // =============================================

    /**
     * 单本小说完本全量向量重建
     * 步骤：清空旧向量 → flush → 建索引 → load
     * 注意：数据重新写入由业务层调用 MilvusWriteService 完成
     */
    public void rebuildIndexForNovel(Long novelId) {
        log.info("===== 开始小说 [{}] 全量重建流程 =====", novelId);

        // 1. 清空旧向量
        deleteByNovelIdAll(novelId);

        // 2. flush 确保删除生效
        flushAll();

        log.info("小说 [{}] 旧向量已清空，等待业务层写入新数据...", novelId);
    }

    /**
     * 数据写入完成后执行：建索引 + 加载
     * 由业务层在数据写入完成后主动调用
     */
    public void finalizeRebuild() {
        log.info("===== 开始最终建索引 + 加载流程 =====");

        // 1. 全局 flush
        flushAll();

        // 2. 串行构建索引
        buildAllIndexes();

        // 3. compact 碎片整理
        compactAll();

        // 4. 加载全部集合
        loadAllCollections();

        log.info("===== 全量重建完成，所有集合已就绪 =====");
    }

    // =============================================
    // 辅助
    // =============================================

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void forEachCollection(Consumer<String> action) {
        COLLECTION_NAMES.forEach(action);
        action.accept(KNOWLEDGE_COLLECTION);
    }

    private CompletableFuture<Void> submitAsync(String taskName, Runnable task) {
        try {
            return CompletableFuture.runAsync(task, localTaskExecutor)
                    .whenComplete((ignored, throwable) -> {
                        if (throwable == null) {
                            log.info("Milvus local task completed: {}", taskName);
                        } else {
                            log.error("Milvus local task failed: {}", taskName, throwable);
                        }
                    });
        } catch (RejectedExecutionException ex) {
            log.warn("Milvus local task rejected because another task is running: {}", taskName);
            return CompletableFuture.failedFuture(ex);
        }
    }
}
