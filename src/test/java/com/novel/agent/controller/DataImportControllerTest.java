package com.novel.agent.controller;

import com.novel.agent.service.DataImportService;
import com.novel.agent.service.MilvusAdminService;
import com.novel.agent.security.FileAccessPolicy;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataImportControllerTest {

    @Test
    void getProgressReturnsEnhancedStatusView() {
        DataImportService dataImportService = mock(DataImportService.class);
        MilvusAdminService milvusAdminService = mock(MilvusAdminService.class);
        FileAccessPolicy fileAccessPolicy = mock(FileAccessPolicy.class);
        DataImportController controller = new DataImportController(dataImportService, milvusAdminService, fileAccessPolicy, Runnable::run);

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("running", true);
        status.put("stage", "embedding_batch");
        status.put("processedRecords", 120L);
        status.put("totalRecords", 300L);
        status.put("checkpointExists", true);
        status.put("batchCount", 4L);
        status.put("flushCount", 1L);
        status.put("message", "generating embeddings for current batch");

        when(dataImportService.getImportStatus()).thenReturn(status);

        Map<String, Object> response = controller.getProgress();

        assertThat(response.get("running")).isEqualTo(true);
        assertThat(response.get("stage")).isEqualTo("embedding_batch");
        assertThat(response.get("progress")).isEqualTo(120L);
        assertThat(response.get("total")).isEqualTo(300L);
        assertThat(response.get("progressStr")).isEqualTo("120 / 300 (40.0%)");
        assertThat(response.get("checkpointHint")).isEqualTo("导入运行中，checkpoint 会在批次成功后持续刷新");
    }

    @Test
    void importTrainingDataReturnsCurrentStatusWhenAlreadyRunning() {
        DataImportService dataImportService = mock(DataImportService.class);
        MilvusAdminService milvusAdminService = mock(MilvusAdminService.class);
        FileAccessPolicy fileAccessPolicy = mock(FileAccessPolicy.class);
        DataImportController controller = new DataImportController(dataImportService, milvusAdminService, fileAccessPolicy, Runnable::run);

        Map<String, Object> status = Map.of(
                "running", true,
                "stage", "importing_lines",
                "processedRecords", 80L,
                "totalRecords", 500L
        );

        when(dataImportService.tryAcquireImportSlot()).thenReturn(false);
        when(fileAccessPolicy.requireAllowedRegularFile("D:/dataset.jsonl")).thenReturn(Path.of("D:/dataset.jsonl"));
        when(dataImportService.getImportStatus()).thenReturn(status);

        Map<String, Object> response = controller.importTrainingData("D:/dataset.jsonl");

        assertThat(response.get("success")).isEqualTo(false);
        assertThat(response.get("status")).isEqualTo(status);
    }

    @Test
    void isolatedImportRejectsNegativeNovelId() {
        DataImportService dataImportService = mock(DataImportService.class);
        MilvusAdminService milvusAdminService = mock(MilvusAdminService.class);
        FileAccessPolicy fileAccessPolicy = mock(FileAccessPolicy.class);
        DataImportController controller = new DataImportController(dataImportService, milvusAdminService, fileAccessPolicy, Runnable::run);

        Map<String, Object> response = controller.importTrainingDataForNovel(-1L, "D:/dataset.jsonl");

        assertThat(response.get("success")).isEqualTo(false);
        assertThat(response.get("message")).isEqualTo("novelId must be non-negative");
    }

    @Test
    void isolatedImportAcquiresSlotBeforeSchedulingWork() {
        DataImportService dataImportService = mock(DataImportService.class);
        MilvusAdminService milvusAdminService = mock(MilvusAdminService.class);
        FileAccessPolicy fileAccessPolicy = mock(FileAccessPolicy.class);
        DataImportController controller = new DataImportController(dataImportService, milvusAdminService, fileAccessPolicy, Runnable::run);

        when(dataImportService.tryAcquireImportSlot()).thenReturn(true);
        when(fileAccessPolicy.requireAllowedRegularFile("D:/dataset.jsonl")).thenReturn(Path.of("D:/dataset.jsonl"));

        Map<String, Object> response = controller.importTrainingDataForNovel(7L, "D:/dataset.jsonl");

        assertThat(response).isNotNull();
        verify(dataImportService, timeout(1000))
                .importFromJsonAfterReservation(Path.of("D:/dataset.jsonl").toString(), 7L);
    }

    @Test
    void importReleasesReservationWhenLocalExecutorRejects() {
        DataImportService dataImportService = mock(DataImportService.class);
        MilvusAdminService milvusAdminService = mock(MilvusAdminService.class);
        FileAccessPolicy fileAccessPolicy = mock(FileAccessPolicy.class);
        DataImportController controller = new DataImportController(
                dataImportService,
                milvusAdminService,
                fileAccessPolicy,
                command -> {
                    throw new RejectedExecutionException("local task is busy");
                });

        when(dataImportService.tryAcquireImportSlot()).thenReturn(true);
        when(fileAccessPolicy.requireAllowedRegularFile("D:/dataset.jsonl"))
                .thenReturn(Path.of("D:/dataset.jsonl"));
        when(dataImportService.getImportStatus()).thenReturn(Map.of("running", false));

        Map<String, Object> response = controller.importTrainingDataForNovel(7L, "D:/dataset.jsonl");

        assertThat(response.get("success")).isEqualTo(false);
        verify(dataImportService).markImportScheduled(Path.of("D:/dataset.jsonl").toString(), 7L);
        verify(dataImportService).releaseReservedImportSlot();
    }

    @Test
    void finalizeDelegatesToOrderedMilvusRebuild() {
        DataImportService dataImportService = mock(DataImportService.class);
        MilvusAdminService milvusAdminService = mock(MilvusAdminService.class);
        FileAccessPolicy fileAccessPolicy = mock(FileAccessPolicy.class);
        DataImportController controller = new DataImportController(dataImportService, milvusAdminService, fileAccessPolicy, Runnable::run);

        when(dataImportService.isRunning()).thenReturn(false);

        Map<String, Object> response = controller.finalizeImport();

        assertThat(response).isNotNull();
        verify(milvusAdminService, timeout(1000)).finalizeRebuild();
    }
}
