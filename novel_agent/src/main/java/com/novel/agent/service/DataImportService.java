package com.novel.agent.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.utility.request.FlushReq;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 训练数据导入服务 — 断点续跑
 *
 * 功能：
 * 1. 流式读取 JSON（Jackson 逐行解析，避免 OOM）
 * 2. 批量向量化 + 批量入库 Milvus
 * 3. 断点续跑：每批写入后记录偏移量，崩溃后可恢复
 * 4. 全部完成后统一 flush，由外部触发建索引
 *
 * 数据格式：novel_cn_token512_50k.json
 * {"instruction": "...", "input": "...", "output": "..."}
 *
 * 目标集合：novel_segments
 * 字段映射：每条件拆为2条segment，通过 chapter_num 关联
 *   - 上文(input)  → content="指令：{instruction}\n上文：{input}", segment_type=1(上文)
 *   - 续写(output) → content="指令：{instruction}\n续写：{output}", segment_type=2(续写)
 *   共享 chapter_num 保证上下文关联
 *   检索时可通过 chapter_num 获取对应的"另一半"
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataImportService {

    private final MilvusClientV2 milvusClient;
    private final EmbeddingService embeddingService;

    private static final String CHECKPOINT_SUFFIX = ".checkpoint";

    @Value("${milvus.collection.segments:novel_segments}")
    private String segmentsCollection;

    @Value("${milvus.write.batch-size:64}")
    private int batchSize;

    /** 当前进度（仅供查询） */
    private final AtomicLong currentProgress = new AtomicLong(0);
    /** 总条数（仅供查询） */
    private final AtomicLong totalCount = new AtomicLong(0);
    /** 是否正在运行 */
    private volatile boolean running = false;

    /**
     * 从 JSON 文件导入数据（断点续跑）
     *
     * @param jsonFilePath JSON 文件绝对路径
     * @return 导入结果
     */
    public ImportResult importFromJson(String jsonFilePath) {
        Path jsonPath = Paths.get(jsonFilePath);
        if (!Files.exists(jsonPath)) {
            throw new IllegalArgumentException("文件不存在: " + jsonFilePath);
        }

        running = true;
        currentProgress.set(0);
        totalCount.set(0);

        try {
            Path checkpointPath = getCheckpointPath(jsonPath);
            long skipRecords = readCheckpoint(checkpointPath);
            log.info("断点文件: {}，已处理 {} 条", checkpointPath, skipRecords);

            // 检测文件格式：JSON 数组 还是 JSON Lines
            boolean isArray = detectFormat(jsonPath);
            log.info("文件格式: {}", isArray ? "JSON数组" : "JSON Lines");

            // 预估总数
            long estimated = isArray ? estimateArraySize(jsonPath) : countLines(jsonPath);
            totalCount.set(estimated);
            log.info("预估总条数: {}", estimated);

            ImportResult result = isArray
                    ? doImportArray(jsonPath, checkpointPath, skipRecords)
                    : doImportLines(jsonPath, checkpointPath, skipRecords);

            Files.deleteIfExists(checkpointPath);
            log.info("导入完成，共处理 {} 条，成功 {} 条，失败 {} 条",
                    result.totalProcessed, result.successCount, result.failCount);

            return result;
        } catch (Exception e) {
            log.error("导入过程异常", e);
            throw new RuntimeException("导入失败: " + e.getMessage(), e);
        } finally {
            running = false;
        }
    }

    /**
     * 检测文件格式：读第一个非空白字符，[ 表示 JSON 数组，{ 表示 JSON Lines
     */
    private boolean detectFormat(Path jsonPath) throws IOException {
        try (Reader r = Files.newBufferedReader(jsonPath, StandardCharsets.UTF_8)) {
            int ch;
            while ((ch = r.read()) != -1) {
                if (!Character.isWhitespace(ch)) {
                    return ch == '[';
                }
            }
        }
        return false;
    }

    /**
     * 预估 JSON 数组元素个数（基于文件大小估算，不需要精确）
     */
    private long estimateArraySize(Path jsonPath) throws IOException {
        long fileSize = Files.size(jsonPath);
        // 575MB ≈ 50k 条训练数据，给出固定估算
        if (fileSize > 100_000_000) {
            return 50000;
        }
        return fileSize / 11500;
    }

    /**
     * JSON 数组格式导入（流式读取，不占内存）
     */
    private ImportResult doImportArray(Path jsonPath, Path checkpointPath, long skipRecords) throws IOException {
        ImportResult result = new ImportResult();
        long processed = 0;

        List<JsonObject> batch = new ArrayList<>();
        List<String> embedTexts = new ArrayList<>();
        int flushCounter = 0;

        try (JsonReader reader = new JsonReader(Files.newBufferedReader(jsonPath, StandardCharsets.UTF_8))) {
            reader.beginArray();

            // 跳过已处理的记录
            while (processed < skipRecords && reader.hasNext()) {
                reader.skipValue();
                processed++;
            }

            while (reader.hasNext()) {
                JsonObject json = readJsonObject(reader);
                processed++;

                try {
                    String instruction = getString(json, "instruction");
                    String input = getString(json, "input");
                    String output = getString(json, "output");

                    if (input.isEmpty() && output.isEmpty()) {
                        result.failCount++;
                        result.totalProcessed++;
                        continue;
                    }

                    long timestamp = System.currentTimeMillis() / 1000;
                    // 共享 chapter_num 关联上文和续写
                    long chapterNum = processed;

                    // 上文 (input) — segment_type=1, content 带 instruction 前缀
                    if (!input.isEmpty()) {
                        String inputContent = "指令：" + instruction + "\n上文：" + input.trim().replaceAll("\\s+", " ");
                        if (inputContent.length() > 450) {
                            inputContent = inputContent.substring(0, 450);
                        }
                        JsonObject inputRow = new JsonObject();
                        inputRow.addProperty("novel_id", 0L);
                        inputRow.addProperty("chapter_num", chapterNum);
                        inputRow.addProperty("segment_type", 1);
                        inputRow.addProperty("content", inputContent);
                        inputRow.addProperty("ts", timestamp);
                        batch.add(inputRow);
                        embedTexts.add(inputContent);
                    }

                    // 续写 (output) — segment_type=2, content 带 instruction 前缀
                    if (!output.isEmpty()) {
                        String outputContent = "指令：" + instruction + "\n续写：" + output.trim().replaceAll("\\s+", " ");
                        if (outputContent.length() > 450) {
                            outputContent = outputContent.substring(0, 450);
                        }
                        JsonObject outputRow = new JsonObject();
                        outputRow.addProperty("novel_id", 0L);
                        outputRow.addProperty("chapter_num", chapterNum);
                        outputRow.addProperty("segment_type", 2);
                        outputRow.addProperty("content", outputContent);
                        outputRow.addProperty("ts", timestamp);
                        batch.add(outputRow);
                        embedTexts.add(outputContent);
                    }

                    if (batch.size() >= batchSize) {
                        processBatch(batch, embedTexts, result);
                        flushCounter++;

                        if (flushCounter % 28 == 0) {
                            milvusClient.flush(FlushReq.builder()
                                    .collectionNames(List.of(segmentsCollection))
                                    .build());
                            log.info("自动 flush [{}]（第 {} 次，已处理 {} 条）", segmentsCollection, flushCounter / 28, processed);
                        }

                        writeCheckpoint(checkpointPath, processed);
                        currentProgress.set(processed);

                        batch.clear();
                        embedTexts.clear();
                    }
                } catch (Exception e) {
                    log.warn("第 {} 条解析失败，跳过: {}", processed, e.getMessage());
                    result.failCount++;
                    result.totalProcessed++;
                }
            }

            reader.endArray();

            if (!batch.isEmpty()) {
                processBatch(batch, embedTexts, result);
                writeCheckpoint(checkpointPath, processed);
                currentProgress.set(processed);
            }

            milvusClient.flush(FlushReq.builder()
                    .collectionNames(List.of(segmentsCollection))
                    .build());
            log.info("最终 flush [{}] 完成，共处理 {} 条", segmentsCollection, processed);
        }

        return result;
    }

    /**
     * 从 JsonReader 读取一个 JSON 对象，转为 Gson JsonObject
     */
    private JsonObject readJsonObject(JsonReader reader) throws IOException {
        JsonObject obj = new JsonObject();
        reader.beginObject();
        while (reader.peek() != JsonToken.END_OBJECT) {
            String name = reader.nextName();
            switch (reader.peek()) {
                case STRING:
                    obj.addProperty(name, reader.nextString());
                    break;
                case NUMBER:
                    obj.addProperty(name, reader.nextString());
                    break;
                case BOOLEAN:
                    obj.addProperty(name, reader.nextBoolean());
                    break;
                case NULL:
                    reader.nextNull();
                    obj.add(name, null);
                    break;
                default:
                    reader.skipValue();
                    break;
            }
        }
        reader.endObject();
        return obj;
    }

    /**
     * JSON Lines 格式导入（逐行解析，保留兼容性）
     */
    private ImportResult doImportLines(Path jsonPath, Path checkpointPath, long skipLines) throws IOException {
        ImportResult result = new ImportResult();
        long processed = skipLines;  // 已跳过的行数

        List<JsonObject> batch = new ArrayList<>();
        List<String> embedTexts = new ArrayList<>();
        int flushCounter = 0;

        try (BufferedReader reader = Files.newBufferedReader(jsonPath, StandardCharsets.UTF_8)) {
            String line;
            long lineNum = 0;

            // 跳过已处理的行
            while (lineNum < skipLines && (line = reader.readLine()) != null) {
                lineNum++;
            }

            // 继续读取剩余行
            while ((line = reader.readLine()) != null) {
                lineNum++;
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    JsonObject json = JsonParser.parseString(line).getAsJsonObject();
                    String instruction = getString(json, "instruction");
                    String input = getString(json, "input");
                    String output = getString(json, "output");

                    if (input.isEmpty() && output.isEmpty()) {
                        result.failCount++;
                        result.totalProcessed++;
                        continue;
                    }

                    long timestamp = System.currentTimeMillis() / 1000;
                    long chapterNum = processed;

                    // 上文 (input) — segment_type=1, content 带 instruction 前缀
                    if (!input.isEmpty()) {
                        String inputContent = "指令：" + instruction + "\n上文：" + input.trim().replaceAll("\\s+", " ");
                        if (inputContent.length() > 450) {
                            inputContent = inputContent.substring(0, 450);
                        }
                        JsonObject inputRow = new JsonObject();
                        inputRow.addProperty("novel_id", 0L);
                        inputRow.addProperty("chapter_num", chapterNum);
                        inputRow.addProperty("segment_type", 1);
                        inputRow.addProperty("content", inputContent);
                        inputRow.addProperty("ts", timestamp);
                        batch.add(inputRow);
                        embedTexts.add(inputContent);
                    }

                    // 续写 (output) — segment_type=2, content 带 instruction 前缀
                    if (!output.isEmpty()) {
                        String outputContent = "指令：" + instruction + "\n续写：" + output.trim().replaceAll("\\s+", " ");
                        if (outputContent.length() > 450) {
                            outputContent = outputContent.substring(0, 450);
                        }
                        JsonObject outputRow = new JsonObject();
                        outputRow.addProperty("novel_id", 0L);
                        outputRow.addProperty("chapter_num", chapterNum);
                        outputRow.addProperty("segment_type", 2);
                        outputRow.addProperty("content", outputContent);
                        outputRow.addProperty("ts", timestamp);
                        batch.add(outputRow);
                        embedTexts.add(outputContent);
                    }

                    // 达到 batch 大小，批量处理
                    if (batch.size() >= batchSize) {
                        processBatch(batch, embedTexts, result);
                        processed = lineNum;
                        flushCounter++;

                        // 每 28 批 flush 一次
                        if (flushCounter % 28 == 0) {
                            milvusClient.flush(FlushReq.builder()
                                    .collectionNames(List.of(segmentsCollection))
                                    .build());
                            log.info("自动 flush [{}]（第 {} 次）", segmentsCollection, flushCounter / 28);
                        }

                        // 写断点
                        writeCheckpoint(checkpointPath, processed);
                        currentProgress.set(processed);

                        // 清空批次
                        batch.clear();
                        embedTexts.clear();
                    }
                } catch (Exception e) {
                    log.warn("第 {} 行解析失败，跳过: {}", lineNum, e.getMessage());
                    result.failCount++;
                    result.totalProcessed++;
                }
            }

            // 处理剩余不足一批的数据
            if (!batch.isEmpty()) {
                processBatch(batch, embedTexts, result);
                processed = lineNum;
                writeCheckpoint(checkpointPath, processed);
                currentProgress.set(processed);
            }

            // 最后 flush 一次
            milvusClient.flush(FlushReq.builder()
                    .collectionNames(List.of(segmentsCollection))
                    .build());
            log.info("最终 flush [{}] 完成", segmentsCollection);
        }

        return result;
    }

    /**
     * 处理一批数据：批量向量化 → 批量插入 Milvus
     */
    private void processBatch(List<JsonObject> rows, List<String> embedTexts, ImportResult result) {
        try {
            // 1. 批量向量化
            List<List<Float>> embeddings = embeddingService.batchGenerateEmbedding(embedTexts);

            // 2. 填充向量并插入
            List<JsonObject> validRows = new ArrayList<>();
            for (int i = 0; i < embeddings.size(); i++) {
                if (embeddings.get(i) != null) {
                    JsonObject row = rows.get(i);
                    List<Float> vec = embeddings.get(i);
                    JsonArray vecArray = new JsonArray();
                    for (Float v : vec) {
                        vecArray.add(v);
                    }
                    row.add("embedding", vecArray);
                    validRows.add(row);
                } else {
                    result.failCount++;
                }
            }

            if (!validRows.isEmpty()) {
                milvusClient.insert(InsertReq.builder()
                        .collectionName(segmentsCollection)
                        .data(validRows)
                        .build());
                log.debug("批次插入 [{}] {} 条", segmentsCollection, validRows.size());
            }

            result.successCount += validRows.size();
            result.totalProcessed += rows.size();

            // 内存释放：每批成功后清空集合
            rows.clear();
            embedTexts.clear();
        } catch (Exception e) {
            log.error("批次处理失败（{} 条），放弃该批次", rows.size(), e);
            result.failCount += rows.size();
            result.totalProcessed += rows.size();
        }
    }

    // =============================================
    // 断点管理
    // =============================================

    /**
     * 获取断点文件路径（与 JSON 文件同目录，扩展名 .checkpoint）
     */
    private Path getCheckpointPath(Path jsonPath) {
        String fileName = jsonPath.getFileName().toString() + CHECKPOINT_SUFFIX;
        return jsonPath.resolveSibling(fileName);
    }

    /**
     * 读取断点（已处理行数），不存在则返回 0
     */
    private long readCheckpoint(Path checkpointPath) {
        if (!Files.exists(checkpointPath)) {
            return 0;
        }
        try {
            String content = Files.readString(checkpointPath, StandardCharsets.UTF_8).trim();
            return Long.parseLong(content);
        } catch (Exception e) {
            log.warn("读取断点文件失败，将从 0 开始: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 写入断点
     */
    private void writeCheckpoint(Path checkpointPath, long processed) {
        try {
            Files.writeString(checkpointPath, String.valueOf(processed), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("写入断点文件失败: {}", e.getMessage());
        }
    }

    /**
     * 统计文件行数（快速遍历，不加载到内存）
     */
    private long countLines(Path path) throws IOException {
        long count = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            while (reader.readLine() != null) {
                count++;
            }
        }
        return count;
    }

    // =============================================
    // 状态查询
    // =============================================

    /** 当前进度 */
    public long getProgress() {
        return currentProgress.get();
    }

    /** 总条数 */
    public long getTotal() {
        return totalCount.get();
    }

    /** 是否正在运行 */
    public boolean isRunning() {
        return running;
    }

    // =============================================
    // 辅助
    // =============================================

    private String getString(JsonObject json, String key) {
        if (json.has(key) && !json.get(key).isJsonNull()) {
            return json.get(key).getAsString();
        }
        return "";
    }

    // =============================================
    // 内部类
    // =============================================

    public static class ImportResult {
        /** 总处理条数 */
        public long totalProcessed = 0;
        /** 成功条数 */
        public long successCount = 0;
        /** 失败条数 */
        public long failCount = 0;

        @Override
        public String toString() {
            return String.format("总处理: %d, 成功: %d, 失败: %d", totalProcessed, successCount, failCount);
        }
    }
}