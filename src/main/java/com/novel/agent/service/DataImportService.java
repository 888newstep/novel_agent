package com.novel.agent.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.utility.request.FlushReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataImportService {

    private static final String CHECKPOINT_SUFFIX = ".checkpoint";
    private static final int AUTO_FLUSH_BATCH_INTERVAL = 28;

    private final MilvusClientV2 milvusClient;
    private final EmbeddingService embeddingService;

    @Value("${milvus.collection.segments:novel_segments}")
    private String segmentsCollection;

    @Value("${milvus.write.batch-size:64}")
    private int batchSize;

    @Value("${milvus.import.max-retries:3}")
    private int maxRetries;

    @Value("${milvus.import.retry-backoff-ms:1000}")
    private long retryBackoffMs;

    private final AtomicLong currentProgress = new AtomicLong(0);
    private final AtomicLong totalCount = new AtomicLong(0);
    private volatile boolean running = false;
    private volatile Map<String, Object> importStatus = createIdleStatus();

    public ImportResult importFromJson(String jsonFilePath) {
        return importFromJson(jsonFilePath, 0L);
    }

    /**
     * Imports records for one novel while preserving the legacy novelId=0 path.
     */
    public ImportResult importFromJson(String jsonFilePath, long novelId) {
        if (jsonFilePath == null || jsonFilePath.isBlank()) {
            throw new IllegalArgumentException("import file path must not be blank");
        }
        if (novelId < 0) {
            throw new IllegalArgumentException("novelId must be non-negative");
        }

        Path jsonPath = Paths.get(jsonFilePath);
        if (!Files.exists(jsonPath)) {
            throw new IllegalArgumentException("文件不存在: " + jsonFilePath);
        }

        Path checkpointPath = getCheckpointPath(jsonPath, novelId);
        long startedAt = System.currentTimeMillis();
        running = true;
        currentProgress.set(0);
        totalCount.set(0);
        importStatus = createIdleStatus();
        updateStatus(Map.of(
                "running", true,
                "stage", "preparing",
                "filePath", jsonFilePath,
                "novelId", novelId,
                "checkpointPath", checkpointPath.toString(),
                "startedAt", System.currentTimeMillis(),
                "message", "preparing import task",
                "checkpointExists", Files.exists(checkpointPath)
        ));

        try {
            long skipRecords = readCheckpoint(checkpointPath);
            currentProgress.set(skipRecords);
            updateStatus(Map.of(
                    "stage", "checkpoint_loaded",
                    "resumeFromRecord", skipRecords,
                    "lastCheckpointRecord", skipRecords,
                    "processedRecords", skipRecords,
                    "checkpointExists", skipRecords > 0 || Files.exists(checkpointPath),
                    "message", skipRecords > 0 ? "resuming from saved checkpoint" : "starting from beginning"
            ));

            boolean isArray = detectFormat(jsonPath);
            String format = isArray ? "json_array" : "json_lines";
            updateStatus(Map.of(
                    "stage", "analyzing_file",
                    "format", format,
                    "message", "detected input format"
            ));

            long estimated = isArray ? estimateArraySize(jsonPath) : countLines(jsonPath);
            totalCount.set(estimated);
            updateStatus(Map.of(
                    "stage", "counting_records",
                    "totalRecords", estimated,
                    "processedRecords", skipRecords,
                    "message", "estimated total records"
            ));

            ImportResult result = isArray
                    ? doImportArray(jsonPath, checkpointPath, skipRecords, novelId)
                    : doImportLines(jsonPath, checkpointPath, skipRecords, novelId);

            Files.deleteIfExists(checkpointPath);
            long importedRecordCount = Math.max(0L, currentProgress.get() - skipRecords);
            long durationMs = elapsedMillis(startedAt);
            result.novelId = novelId;
            result.sourceRecordCount = estimated;
            result.importedRecordCount = importedRecordCount;
            result.durationMs = durationMs;
            result.recordsPerSecond = calculateRate(importedRecordCount, durationMs);
            result.segmentsPerSecond = calculateRate(result.successCount, durationMs);

            Map<String, Object> completedPatch = new LinkedHashMap<>();
            completedPatch.put("running", false);
            completedPatch.put("stage", "completed");
            completedPatch.put("finishedAt", System.currentTimeMillis());
            completedPatch.put("processedRecords", currentProgress.get());
            completedPatch.put("successCount", result.successCount);
            completedPatch.put("failCount", result.failCount);
            completedPatch.put("failureCount", result.failCount);
            completedPatch.put("lastCheckpointRecord", currentProgress.get());
            completedPatch.put("checkpointExists", false);
            completedPatch.put("novelId", novelId);
            completedPatch.put("sourceRecordCount", estimated);
            completedPatch.put("importedRecordCount", importedRecordCount);
            completedPatch.put("durationMs", durationMs);
            completedPatch.put("recordsPerSecond", result.recordsPerSecond);
            completedPatch.put("segmentsPerSecond", result.segmentsPerSecond);
            completedPatch.put("batchCount", (long) result.batchCount);
            completedPatch.put("flushCount", (long) result.flushCount);
            completedPatch.put("retryCount", result.retryCount);
            completedPatch.put("message", "import finished successfully");
            updateStatus(completedPatch);
            return result;
        } catch (Exception ex) {
            Map<String, Object> failedPatch = new LinkedHashMap<>();
            failedPatch.put("running", false);
            failedPatch.put("stage", "failed");
            failedPatch.put("finishedAt", System.currentTimeMillis());
            failedPatch.put("lastError", ex.getMessage() == null ? "unknown error" : ex.getMessage());
            failedPatch.put("checkpointExists", Files.exists(checkpointPath));
            failedPatch.put("novelId", novelId);
            failedPatch.put("durationMs", elapsedMillis(startedAt));
            failedPatch.put("message", "import failed");
            updateStatus(failedPatch);
            log.error("import failed", ex);
            throw new RuntimeException("导入失败: " + ex.getMessage(), ex);
        } finally {
            running = false;
            updateStatus(Map.of("running", false));
        }
    }

    private boolean detectFormat(Path jsonPath) throws IOException {
        try (Reader reader = Files.newBufferedReader(jsonPath, StandardCharsets.UTF_8)) {
            int ch;
            while ((ch = reader.read()) != -1) {
                if (!Character.isWhitespace(ch)) {
                    return ch == '[';
                }
            }
        }
        return false;
    }

    private long estimateArraySize(Path jsonPath) throws IOException {
        long fileSize = Files.size(jsonPath);
        if (fileSize > 100_000_000) {
            return 50_000;
        }
        return Math.max(1, fileSize / 11_500);
    }

    private ImportResult doImportArray(Path jsonPath,
                                       Path checkpointPath,
                                       long skipRecords,
                                       long novelId) throws IOException {
        ImportResult result = new ImportResult();
        long processed = 0;
        int batchCount = 0;
        int flushCount = 0;
        List<JsonObject> batch = new ArrayList<>();
        List<String> embedTexts = new ArrayList<>();

        updateStatus(Map.of(
                "stage", "importing_array",
                "processedRecords", skipRecords,
                "message", "streaming json array records"
        ));

        try (JsonReader reader = new JsonReader(Files.newBufferedReader(jsonPath, StandardCharsets.UTF_8))) {
            reader.beginArray();
            while (processed < skipRecords && reader.hasNext()) {
                reader.skipValue();
                processed++;
            }

            while (reader.hasNext()) {
                JsonObject json = readJsonObject(reader);
                processed++;
                try {
                    appendTrainingRows(batch, embedTexts, json, processed, novelId, result);
                    if (batch.size() >= batchSize) {
                        batchCount++;
                        processBatch(batch, embedTexts, result, processed, batchCount, flushCount, novelId, "importing_array");
                        if (batchCount % AUTO_FLUSH_BATCH_INTERVAL == 0) {
                            flushCount++;
                            flushSegments();
                            updateStatus(Map.of(
                                    "stage", "flushing",
                                    "flushCount", (long) flushCount,
                                    "message", "auto flush after batch window"
                            ));
                        }
                        checkpointProgress(checkpointPath, processed, result, batchCount, flushCount, "checkpoint_saved");
                    }
                } catch (IllegalStateException ex) {
                    throw ex;
                } catch (Exception ex) {
                    log.warn("array record {} parse failed: {}", processed, ex.getMessage());
                    result.failCount++;
                    result.totalProcessed++;
                    checkpointProgress(checkpointPath, processed, result, batchCount, flushCount, "record_skipped");
                }
            }
            reader.endArray();
        }

        if (!batch.isEmpty()) {
            batchCount++;
            processBatch(batch, embedTexts, result, processed, batchCount, flushCount, novelId, "importing_array");
            checkpointProgress(checkpointPath, processed, result, batchCount, flushCount, "checkpoint_saved");
        }

        flushCount++;
        flushSegments();
        updateStatus(Map.of(
                "stage", "final_flush",
                "flushCount", (long) flushCount,
                "processedRecords", processed,
                "successCount", result.successCount,
                "failCount", result.failCount,
                "batchCount", (long) batchCount,
                "retryCount", result.retryCount,
                "message", "final flush completed"
        ));
        result.batchCount = batchCount;
        result.flushCount = flushCount;
        return result;
    }

    private JsonObject readJsonObject(JsonReader reader) throws IOException {
        JsonObject obj = new JsonObject();
        reader.beginObject();
        while (reader.peek() != JsonToken.END_OBJECT) {
            String name = reader.nextName();
            switch (reader.peek()) {
                case STRING -> obj.addProperty(name, reader.nextString());
                case NUMBER -> obj.addProperty(name, reader.nextString());
                case BOOLEAN -> obj.addProperty(name, reader.nextBoolean());
                case NULL -> {
                    reader.nextNull();
                    obj.add(name, null);
                }
                default -> reader.skipValue();
            }
        }
        reader.endObject();
        return obj;
    }

    private ImportResult doImportLines(Path jsonPath,
                                       Path checkpointPath,
                                       long skipLines,
                                       long novelId) throws IOException {
        ImportResult result = new ImportResult();
        long processed = skipLines;
        long lineNum = 0;
        int batchCount = 0;
        int flushCount = 0;
        List<JsonObject> batch = new ArrayList<>();
        List<String> embedTexts = new ArrayList<>();

        updateStatus(Map.of(
                "stage", "importing_lines",
                "processedRecords", skipLines,
                "message", "streaming json lines records"
        ));

        try (BufferedReader reader = Files.newBufferedReader(jsonPath, StandardCharsets.UTF_8)) {
            String line;
            while (lineNum < skipLines && (line = reader.readLine()) != null) {
                lineNum++;
            }
            while ((line = reader.readLine()) != null) {
                lineNum++;
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    JsonObject json = JsonParser.parseString(trimmed).getAsJsonObject();
                    processed = lineNum;
                    appendTrainingRows(batch, embedTexts, json, processed, novelId, result);
                    if (batch.size() >= batchSize) {
                        batchCount++;
                        processBatch(batch, embedTexts, result, processed, batchCount, flushCount, novelId, "importing_lines");
                        if (batchCount % AUTO_FLUSH_BATCH_INTERVAL == 0) {
                            flushCount++;
                            flushSegments();
                            updateStatus(Map.of(
                                    "stage", "flushing",
                                    "flushCount", (long) flushCount,
                                    "message", "auto flush after batch window"
                            ));
                        }
                        checkpointProgress(checkpointPath, processed, result, batchCount, flushCount, "checkpoint_saved");
                    }
                } catch (IllegalStateException ex) {
                    throw ex;
                } catch (Exception ex) {
                    log.warn("line {} parse failed: {}", lineNum, ex.getMessage());
                    result.failCount++;
                    result.totalProcessed++;
                    processed = lineNum;
                    checkpointProgress(checkpointPath, processed, result, batchCount, flushCount, "record_skipped");
                }
            }
        }

        if (!batch.isEmpty()) {
            batchCount++;
            processBatch(batch, embedTexts, result, processed, batchCount, flushCount, novelId, "importing_lines");
            checkpointProgress(checkpointPath, processed, result, batchCount, flushCount, "checkpoint_saved");
        }

        flushCount++;
        flushSegments();
        updateStatus(Map.of(
                "stage", "final_flush",
                "flushCount", (long) flushCount,
                "processedRecords", processed,
                "successCount", result.successCount,
                "failCount", result.failCount,
                "batchCount", (long) batchCount,
                "retryCount", result.retryCount,
                "message", "final flush completed"
        ));
        result.batchCount = batchCount;
        result.flushCount = flushCount;
        return result;
    }

    private void appendTrainingRows(List<JsonObject> batch,
                                    List<String> embedTexts,
                                    JsonObject json,
                                    long chapterNum,
                                    long novelId,
                                    ImportResult result) {
        String instruction = getString(json, "instruction");
        String input = getString(json, "input");
        String output = getString(json, "output");
        if (input.isEmpty() && output.isEmpty()) {
            result.failCount++;
            result.totalProcessed++;
            return;
        }

        long timestamp = System.currentTimeMillis() / 1000;
        if (!input.isEmpty()) {
            String inputContent = normalizeContent("指令：" + instruction + "\n上文：" + input);
            batch.add(createSegmentRow(novelId, chapterNum, 1, timestamp, inputContent));
            embedTexts.add(inputContent);
        }
        if (!output.isEmpty()) {
            String outputContent = normalizeContent("指令：" + instruction + "\n续写：" + output);
            batch.add(createSegmentRow(novelId, chapterNum, 2, timestamp, outputContent));
            embedTexts.add(outputContent);
        }
    }

    private JsonObject createSegmentRow(long novelId,
                                        long chapterNum,
                                        int segmentType,
                                        long timestamp,
                                        String content) {
        JsonObject row = new JsonObject();
        row.addProperty("novel_id", novelId);
        row.addProperty("chapter_num", chapterNum);
        row.addProperty("segment_type", segmentType);
        row.addProperty("content", content);
        row.addProperty("ts", timestamp);
        return row;
    }

    private String normalizeContent(String content) {
        String normalized = content == null ? "" : content.trim().replaceAll("\\s+", " ");
        return normalized.length() > 450 ? normalized.substring(0, 450) : normalized;
    }

    private void processBatch(List<JsonObject> rows,
                              List<String> embedTexts,
                              ImportResult result,
                              long processed,
                              int batchCount,
                              int flushCount,
                              long novelId,
                              String sourceStage) {
        int safeMaxRetries = Math.max(1, maxRetries);
        BatchRange batchRange = extractChapterRange(rows);
        String retryRange = batchRange.displayValue();
        Exception lastException = null;

        for (int attempt = 1; attempt <= safeMaxRetries; attempt++) {
            try {
                if (attempt > 1) {
                    long retryCount = attempt - 1L;
                    result.retryCount++;
                    Map<String, Object> retryPatch = new LinkedHashMap<>();
                    retryPatch.put("stage", "retrying_batch");
                    retryPatch.put("processedRecords", processed);
                    retryPatch.put("batchCount", (long) batchCount);
                    retryPatch.put("flushCount", (long) flushCount);
                    retryPatch.put("currentBatchSize", rows.size());
                    retryPatch.put("successCount", result.successCount);
                    retryPatch.put("failCount", result.failCount);
                    retryPatch.put("retryCount", result.retryCount);
                    retryPatch.put("lastRetriedRange", retryRange);
                    retryPatch.put("lastRetryReason", lastException == null || lastException.getMessage() == null
                            ? "batch failed"
                            : lastException.getMessage());
                    retryPatch.put("message", "retrying failed batch after cleanup");
                    retryPatch.put("sourceStage", sourceStage);
                    updateStatus(retryPatch);

                    sleepBackoff(retryCount);
                    deleteSegmentRange(batchRange, novelId);
                }

                Map<String, Object> embeddingPatch = new LinkedHashMap<>();
                embeddingPatch.put("stage", "embedding_batch");
                embeddingPatch.put("processedRecords", processed);
                embeddingPatch.put("batchCount", (long) batchCount);
                embeddingPatch.put("flushCount", (long) flushCount);
                embeddingPatch.put("currentBatchSize", rows.size());
                embeddingPatch.put("successCount", result.successCount);
                embeddingPatch.put("failCount", result.failCount);
                embeddingPatch.put("retryCount", result.retryCount);
                embeddingPatch.put("message", attempt == 1
                        ? "generating embeddings for current batch"
                        : "regenerating embeddings for retried batch");
                embeddingPatch.put("sourceStage", sourceStage);
                updateStatus(embeddingPatch);

                List<List<Float>> embeddings = embeddingService.batchGenerateEmbedding(embedTexts);
                PreparedBatch preparedBatch = buildPreparedBatch(rows, embeddings);

                if (!preparedBatch.validRows().isEmpty()) {
                    Map<String, Object> insertingPatch = new LinkedHashMap<>();
                    insertingPatch.put("stage", "inserting_batch");
                    insertingPatch.put("processedRecords", processed);
                    insertingPatch.put("batchCount", (long) batchCount);
                    insertingPatch.put("flushCount", (long) flushCount);
                    insertingPatch.put("currentBatchSize", preparedBatch.validRows().size());
                    insertingPatch.put("successCount", result.successCount);
                    insertingPatch.put("failCount", result.failCount);
                    insertingPatch.put("retryCount", result.retryCount);
                    insertingPatch.put("message", "inserting batch into milvus");
                    insertingPatch.put("sourceStage", sourceStage);
                    updateStatus(insertingPatch);

                    milvusClient.insert(InsertReq.builder()
                            .collectionName(segmentsCollection)
                            .data(preparedBatch.validRows())
                            .build());
                }

                result.successCount += preparedBatch.validRows().size();
                result.failCount += preparedBatch.invalidCount();
                result.totalProcessed += rows.size();
                rows.clear();
                embedTexts.clear();
                return;
            } catch (Exception ex) {
                lastException = ex;
                if (attempt >= safeMaxRetries) {
                    break;
                }

                log.warn("batch processing attempt {} failed, range={}, rows={}: {}",
                        attempt,
                        retryRange,
                        rows.size(),
                        ex.getMessage());
            }
        }

        int failedRows = rows.size();
        result.failCount += failedRows;
        result.totalProcessed += failedRows;
        rows.clear();
        embedTexts.clear();

        Map<String, Object> failedPatch = new LinkedHashMap<>();
        failedPatch.put("stage", "batch_failed");
        failedPatch.put("processedRecords", processed);
        failedPatch.put("batchCount", (long) batchCount);
        failedPatch.put("flushCount", (long) flushCount);
        failedPatch.put("currentBatchSize", failedRows);
        failedPatch.put("successCount", result.successCount);
        failedPatch.put("failCount", result.failCount);
        failedPatch.put("retryCount", result.retryCount);
        failedPatch.put("lastRetriedRange", retryRange);
        failedPatch.put("lastRetryReason", lastException == null || lastException.getMessage() == null
                ? "batch failed"
                : lastException.getMessage());
        failedPatch.put("lastError", lastException == null || lastException.getMessage() == null
                ? "batch failed"
                : lastException.getMessage());
        failedPatch.put("message", "batch processing failed after retries");
        failedPatch.put("sourceStage", sourceStage);
        updateStatus(failedPatch);

        log.error("batch processing failed after {} attempts, range={}, rows={}",
                safeMaxRetries,
                retryRange,
                failedRows,
                lastException);
        throw new IllegalStateException("batch processing failed after retries", lastException);
    }

    private PreparedBatch buildPreparedBatch(List<JsonObject> rows, List<List<Float>> embeddings) {
        if (embeddings == null) {
            throw new IllegalStateException("embedding result is null");
        }

        List<JsonObject> validRows = new ArrayList<>();
        int invalidCount = 0;
        int upperBound = Math.min(rows.size(), embeddings.size());

        for (int i = 0; i < upperBound; i++) {
            List<Float> vector = embeddings.get(i);
            if (vector == null || vector.isEmpty()) {
                invalidCount++;
                continue;
            }
            JsonArray vectorArray = new JsonArray();
            for (Float value : vector) {
                vectorArray.add(value);
            }
            JsonObject row = rows.get(i);
            row.add("embedding", vectorArray);
            validRows.add(row);
        }

        invalidCount += rows.size() - upperBound;
        return new PreparedBatch(validRows, invalidCount);
    }

    private BatchRange extractChapterRange(List<JsonObject> rows) {
        if (rows == null || rows.isEmpty()) {
            return new BatchRange(-1L, -1L);
        }

        long minChapter = Long.MAX_VALUE;
        long maxChapter = Long.MIN_VALUE;
        for (JsonObject row : rows) {
            long chapterNum = row.has("chapter_num") && !row.get("chapter_num").isJsonNull()
                    ? row.get("chapter_num").getAsLong()
                    : -1L;
            minChapter = Math.min(minChapter, chapterNum);
            maxChapter = Math.max(maxChapter, chapterNum);
        }
        return new BatchRange(minChapter, maxChapter);
    }

    private void deleteSegmentRange(BatchRange batchRange, long novelId) {
        if (!batchRange.isValid()) {
            return;
        }
        milvusClient.delete(DeleteReq.builder()
                .collectionName(segmentsCollection)
                .filter(String.format("novel_id == %d && chapter_num >= %d && chapter_num <= %d",
                        novelId,
                        batchRange.fromChapter(),
                        batchRange.toChapter()))
                .build());
    }

    private void sleepBackoff(long retryCount) {
        long sleepMillis = Math.max(0L, retryBackoffMs) * Math.max(0L, retryCount);
        if (sleepMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("retry backoff interrupted", ex);
        }
    }

    private void checkpointProgress(Path checkpointPath,
                                    long processed,
                                    ImportResult result,
                                    int batchCount,
                                    int flushCount,
                                    String stage) {
        writeCheckpoint(checkpointPath, processed);
        currentProgress.set(processed);
        updateStatus(Map.of(
                "stage", stage,
                "processedRecords", processed,
                "successCount", result.successCount,
                "failCount", result.failCount,
                "batchCount", (long) batchCount,
                "flushCount", (long) flushCount,
                "lastCheckpointRecord", processed,
                "checkpointExists", true,
                "message", "checkpoint updated"
        ));
    }

    private void flushSegments() {
        milvusClient.flush(FlushReq.builder()
                .collectionNames(List.of(segmentsCollection))
                .build());
    }

    private Path getCheckpointPath(Path jsonPath, long novelId) {
        if (novelId == 0L) {
            return jsonPath.resolveSibling(jsonPath.getFileName().toString() + CHECKPOINT_SUFFIX);
        }
        return jsonPath.resolveSibling(jsonPath.getFileName().toString()
                + ".novel-" + novelId + CHECKPOINT_SUFFIX);
    }

    private long readCheckpoint(Path checkpointPath) {
        if (!Files.exists(checkpointPath)) {
            return 0L;
        }
        try {
            String content = Files.readString(checkpointPath, StandardCharsets.UTF_8).trim();
            return Long.parseLong(content);
        } catch (Exception ex) {
            log.warn("read checkpoint failed, fallback to 0: {}", ex.getMessage());
            return 0L;
        }
    }

    private void writeCheckpoint(Path checkpointPath, long processed) {
        try {
            Files.writeString(checkpointPath, String.valueOf(processed), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.warn("write checkpoint failed: {}", ex.getMessage());
        }
    }

    private long countLines(Path path) throws IOException {
        long count = 0L;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            while (reader.readLine() != null) {
                count++;
            }
        }
        return count;
    }

    public long getProgress() {
        return currentProgress.get();
    }

    public long getTotal() {
        return totalCount.get();
    }

    public boolean isRunning() {
        return running;
    }

    public Map<String, Object> getImportStatus() {
        return importStatus;
    }

    private synchronized void updateStatus(Map<String, Object> patch) {
        Map<String, Object> merged = new LinkedHashMap<>(importStatus);
        merged.putAll(patch);
        long processed = readLongValue(merged.get("processedRecords"));
        long total = readLongValue(merged.get("totalRecords"));
        double progressPct = total <= 0 ? 0D : Math.min(100D, processed * 100D / total);
        merged.put("progressPct", Math.round(progressPct * 10D) / 10D);
        merged.put("updatedAt", System.currentTimeMillis());
        importStatus = Collections.unmodifiableMap(merged);
    }

    private Map<String, Object> createIdleStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("running", false);
        status.put("stage", "idle");
        status.put("filePath", "");
        status.put("novelId", 0L);
        status.put("checkpointPath", "");
        status.put("startedAt", 0L);
        status.put("finishedAt", 0L);
        status.put("updatedAt", 0L);
        status.put("message", "idle");
        status.put("lastError", "");
        status.put("format", "unknown");
        status.put("processedRecords", 0L);
        status.put("totalRecords", 0L);
        status.put("successCount", 0L);
        status.put("failCount", 0L);
        status.put("batchCount", 0L);
        status.put("flushCount", 0L);
        status.put("resumeFromRecord", 0L);
        status.put("lastCheckpointRecord", 0L);
        status.put("checkpointExists", false);
        status.put("currentBatchSize", 0);
        status.put("retryCount", 0L);
        status.put("failureCount", 0L);
        status.put("sourceRecordCount", 0L);
        status.put("importedRecordCount", 0L);
        status.put("durationMs", 0L);
        status.put("recordsPerSecond", 0D);
        status.put("segmentsPerSecond", 0D);
        status.put("lastRetryReason", "");
        status.put("lastRetriedRange", "");
        status.put("progressPct", 0D);
        status.put("sourceStage", "");
        return Collections.unmodifiableMap(status);
    }

    private long readLongValue(Object value) {
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

    private long elapsedMillis(long startedAt) {
        return Math.max(1L, System.currentTimeMillis() - startedAt);
    }

    private double calculateRate(long count, long durationMs) {
        if (count <= 0 || durationMs <= 0) {
            return 0D;
        }
        return Math.round(count * 100_000D / durationMs) / 100D;
    }

    private String getString(JsonObject json, String key) {
        if (json.has(key) && !json.get(key).isJsonNull()) {
            return json.get(key).getAsString();
        }
        return "";
    }

    public static class ImportResult {
        public long novelId;
        public long sourceRecordCount;
        public long importedRecordCount;
        public long totalProcessed = 0;
        public long successCount = 0;
        public long failCount = 0;
        public int batchCount;
        public int flushCount;
        public long retryCount;
        public long durationMs;
        public double recordsPerSecond;
        public double segmentsPerSecond;

        @Override
        public String toString() {
            return String.format("total=%d, success=%d, fail=%d", totalProcessed, successCount, failCount);
        }
    }

    private record PreparedBatch(List<JsonObject> validRows, int invalidCount) {
    }

    private record BatchRange(long fromChapter, long toChapter) {

        private boolean isValid() {
            return fromChapter >= 0 && toChapter >= fromChapter;
        }

        private String displayValue() {
            if (!isValid()) {
                return "unknown";
            }
            return fromChapter == toChapter ? String.valueOf(fromChapter) : fromChapter + "-" + toChapter;
        }
    }
}
