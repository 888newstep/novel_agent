package com.novel.agent.controller;

import com.novel.agent.service.DataImportService;
import com.novel.agent.service.MilvusAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 训练数据导入控制器
 * 用于导入 novel_cn_token512_50k.json 到 Milvus
 * 支持断点续跑、进度查询、导入完成后建索引
 */
@RestController
@RequestMapping("/api/import")
@RequiredArgsConstructor
public class DataImportController {

    private final DataImportService dataImportService;
    private final MilvusAdminService milvusAdminService;

    /**
     * 启动 50k 训练数据导入（异步）
     * 默认路径：E:\AI新质力\网文数据集\novel_cn_token512_50k.json
     * 支持断点续跑：崩溃后重新调用即可从断点恢复
     */
    @PostMapping("/training-data")
    public Map<String, Object> importTrainingData(
            @RequestParam(defaultValue = "E:\\AI新质力\\网文数据集\\novel_cn_token512_50k.json") String filePath) {

        if (dataImportService.isRunning()) {
            return Map.of("success", false, "message", "导入任务正在运行中，请先查询进度");
        }

        CompletableFuture.runAsync(() -> {
            try {
                DataImportService.ImportResult result = dataImportService.importFromJson(filePath);

                // 数据全部写入完成后，询问是否建索引（由用户自行调用 /api/admin/milvus/build-index）
                System.out.println("==============================================");
                System.out.println("数据导入完成！结果: " + result);
                System.out.println("请调用 POST /api/admin/milvus/build-index 建索引");
                System.out.println("然后调用 POST /api/admin/milvus/load 加载集合");
                System.out.println("==============================================");
            } catch (Exception e) {
                System.err.println("导入失败: " + e.getMessage());
            }
        });

        return Map.of(
                "success", true,
                "message", "导入任务已异步启动，请调用 /api/import/progress 查看进度"
        );
    }

    /**
     * 查询导入进度
     */
    @GetMapping("/progress")
    public Map<String, Object> getProgress() {
        long progress = dataImportService.getProgress();
        long total = dataImportService.getTotal();
        boolean running = dataImportService.isRunning();

        String progressStr;
        if (total <= 0) {
            progressStr = "正在统计总行数...";
        } else {
            double pct = total > 0 ? (double) progress / total * 100 : 0;
            progressStr = String.format("%d / %d (%.1f%%)", progress, total, pct);
        }

        // 检查断点文件是否存在（存在表示有可恢复的断点）
        String checkpointHint = "";
        if (!running && progress > 0) {
            checkpointHint = "上次导入中断，已记录断点。重新调用 POST /api/import/training-data 即可续跑";
        }

        return Map.of(
                "running", running,
                "progress", progress,
                "total", total,
                "progressStr", progressStr,
                "checkpointHint", checkpointHint
        );
    }

    /**
     * 导入完成后，建索引 + 加载（一步到位）
     * 数据已写入 novel_segments，该集合已在 MilvusAdminService 管理列表中
     */
    @PostMapping("/finalize")
    public Map<String, Object> finalizeImport() {
        if (dataImportService.isRunning()) {
            return Map.of("success", false, "message", "导入任务仍在运行，请先等待完成");
        }

        CompletableFuture.runAsync(() -> {
            // 1. flush 所有数据落盘
            milvusAdminService.flushAll();

            // 2. 为所有集合建索引（novel_segments 在 COLLECTION_NAMES 列表中）
            milvusAdminService.buildAllIndexesAsync();

            // 3. 加载所有集合
            milvusAdminService.loadAllCollections();
        });

        return Map.of(
                "success", true,
                "message", "全量建索引+加载已异步启动，请通过日志或 Attu 监控进度"
        );
    }
}