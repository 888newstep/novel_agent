package com.novel.agent.controller;

import com.novel.agent.service.DataImportService;
import com.novel.agent.service.MilvusAdminService;
import com.novel.agent.security.FileAccessPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@RestController
@RequestMapping("/api/import")
@Slf4j
public class DataImportController {

    private final DataImportService dataImportService;
    private final MilvusAdminService milvusAdminService;
    private final FileAccessPolicy fileAccessPolicy;
    private final Executor localTaskExecutor;

    public DataImportController(
            DataImportService dataImportService,
            MilvusAdminService milvusAdminService,
            FileAccessPolicy fileAccessPolicy,
            @Qualifier("localTaskExecutor") Executor localTaskExecutor) {
        this.dataImportService = dataImportService;
        this.milvusAdminService = milvusAdminService;
        this.fileAccessPolicy = fileAccessPolicy;
        this.localTaskExecutor = localTaskExecutor;
    }

    @PostMapping("/training-data")
    public Map<String, Object> importTrainingData(
            @RequestParam(defaultValue = "E:\\AI新质力\\网文数据集\\novel_cn_token512_50k.json") String filePath) {

        String validatedFilePath = validateFilePath(filePath);
        if (validatedFilePath == null) {
            return Map.of("success", false, "message", "file path is not allowed");
        }
        if (!dataImportService.tryAcquireImportSlot()) {
            return importResponse(false, null, "导入任务正在运行中，请先查询进度");
        }
        return scheduleImport(
                validatedFilePath,
                0L,
                null,
                "training data import",
                "已有本地维护任务正在运行，请稍后重试",
                "导入任务已异步启动，请调用 /api/import/progress 或 /api/import/status 查看进度",
                () -> {
                    DataImportService.ImportResult result =
                            dataImportService.importFromJsonAfterReservation(validatedFilePath, 0L);
                    log.info("Training data import completed: {}. Call POST /api/v1/novel/admin/milvus/finalize to rebuild Milvus.", result);
                }
        );
    }

    @PostMapping("/training-data/{novelId}")
    public Map<String, Object> importTrainingDataForNovel(
            @PathVariable long novelId,
            @RequestParam String filePath) {
        if (novelId < 0) {
            return Map.of("success", false, "message", "novelId must be non-negative");
        }
        String validatedNovelFilePath = validateFilePath(filePath);
        if (validatedNovelFilePath == null) {
            return Map.of("success", false, "message", "file path is not allowed");
        }
        if (!dataImportService.tryAcquireImportSlot()) {
            return importResponse(false, novelId, "import task is already running");
        }
        return scheduleImport(
                validatedNovelFilePath,
                novelId,
                novelId,
                "isolated import, novelId=" + novelId,
                "已有本地维护任务正在运行，请稍后重试",
                "isolated import task started",
                () -> dataImportService.importFromJsonAfterReservation(validatedNovelFilePath, novelId)
        );
    }

    @GetMapping("/progress")
    public Map<String, Object> getProgress() {
        Map<String, Object> status = new LinkedHashMap<>(dataImportService.getImportStatus());
        long progress = readLong(status.get("processedRecords"));
        long total = readLong(status.get("totalRecords"));
        boolean running = Boolean.TRUE.equals(status.get("running"));

        String progressStr = total <= 0
                ? "正在统计总记录数..."
                : String.format("%d / %d (%.1f%%)", progress, total, total == 0 ? 0D : progress * 100D / total);

        String checkpointHint = "";
        if (Boolean.TRUE.equals(status.get("checkpointExists"))) {
            checkpointHint = running
                    ? "导入运行中，checkpoint 会在批次成功后持续刷新"
                    : "检测到可恢复 checkpoint，重新调用 POST /api/import/training-data 即可续跑";
        }

        status.put("progress", progress);
        status.put("total", total);
        status.put("progressStr", progressStr);
        status.put("checkpointHint", checkpointHint);
        return status;
    }

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        return getProgress();
    }

    @PostMapping("/finalize")
    public Map<String, Object> finalizeImport() {
        if (dataImportService.isRunning()) {
            return Map.of("success", false, "message", "导入任务仍在运行，请先等待完成");
        }

        if (!submitTask("Milvus finalize", milvusAdminService::finalizeRebuild)) {
            return Map.of(
                    "success", false,
                    "message", "已有本地维护任务正在运行，请稍后重试"
            );
        }

        return Map.of(
                "success", true,
                "message", "finalize 任务已异步启动，请关注 Milvus flush/build-index/load 日志"
        );
    }

    private long readLong(Object value) {
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

    private String validateFilePath(String filePath) {
        try {
            return fileAccessPolicy.requireAllowedRegularFile(filePath).toString();
        } catch (IllegalArgumentException exception) {
            log.warn("Rejected import file path: {}", exception.getMessage());
            return null;
        }
    }

    private Map<String, Object> scheduleImport(String filePath,
                                                long novelId,
                                                Long responseNovelId,
                                                String taskName,
                                                String rejectedMessage,
                                                String acceptedMessage,
                                                Runnable task) {
        dataImportService.markImportScheduled(filePath, novelId);
        if (!submitTask(taskName, task)) {
            dataImportService.releaseReservedImportSlot();
            return importResponse(false, responseNovelId, rejectedMessage);
        }
        return importResponse(true, responseNovelId, acceptedMessage);
    }

    private Map<String, Object> importResponse(boolean success,
                                               Long novelId,
                                               String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", success);
        if (novelId != null) {
            response.put("novelId", novelId);
        }
        response.put("message", message);
        response.put("status", dataImportService.getImportStatus());
        return response;
    }

    private boolean submitTask(String taskName, Runnable task) {
        try {
            localTaskExecutor.execute(() -> {
                try {
                    task.run();
                    log.info("Local task completed: {}", taskName);
                } catch (Exception ex) {
                    log.error("Local task failed: {}", taskName, ex);
                }
            });
            return true;
        } catch (RejectedExecutionException ex) {
            log.warn("Local task rejected because another task is running: {}", taskName);
            return false;
        }
    }
}
