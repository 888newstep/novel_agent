package com.novel.agent.controller;

import com.novel.agent.service.DataImportService;
import com.novel.agent.service.MilvusAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/import")
@RequiredArgsConstructor
@Slf4j
public class DataImportController {

    private final DataImportService dataImportService;
    private final MilvusAdminService milvusAdminService;

    @PostMapping("/training-data")
    public Map<String, Object> importTrainingData(
            @RequestParam(defaultValue = "E:\\AI新质力\\网文数据集\\novel_cn_token512_50k.json") String filePath) {

        if (dataImportService.isRunning()) {
            return Map.of(
                    "success", false,
                    "message", "导入任务正在运行中，请先查询进度",
                    "status", dataImportService.getImportStatus()
            );
        }

        CompletableFuture.runAsync(() -> {
            try {
                DataImportService.ImportResult result = dataImportService.importFromJson(filePath);
                System.out.println("==============================================");
                System.out.println("训练数据导入完成，结果: " + result);
                System.out.println("请调用 POST /api/admin/milvus/build-index 构建索引");
                System.out.println("然后调用 POST /api/admin/milvus/load 加载集合");
                System.out.println("==============================================");
            } catch (Exception e) {
                System.err.println("导入失败: " + e.getMessage());
            }
        });

        return Map.of(
                "success", true,
                "message", "导入任务已异步启动，请调用 /api/import/progress 或 /api/import/status 查看进度",
                "status", dataImportService.getImportStatus()
        );
    }

    @PostMapping("/training-data/{novelId}")
    public Map<String, Object> importTrainingDataForNovel(
            @PathVariable long novelId,
            @RequestParam String filePath) {
        if (novelId < 0) {
            return Map.of("success", false, "message", "novelId must be non-negative");
        }
        if (dataImportService.isRunning()) {
            return Map.of(
                    "success", false,
                    "message", "import task is already running",
                    "status", dataImportService.getImportStatus()
            );
        }

        CompletableFuture.runAsync(() -> {
            try {
                dataImportService.importFromJson(filePath, novelId);
            } catch (Exception ex) {
                log.error("isolated import failed, novelId={}, filePath={}", novelId, filePath, ex);
            }
        });

        return Map.of(
                "success", true,
                "novelId", novelId,
                "message", "isolated import task started",
                "status", dataImportService.getImportStatus()
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

        CompletableFuture.runAsync(() -> {
            milvusAdminService.flushAll();
            milvusAdminService.buildAllIndexesAsync();
            milvusAdminService.loadAllCollections();
        });

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
}
