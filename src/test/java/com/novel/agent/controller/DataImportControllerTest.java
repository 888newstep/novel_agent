package com.novel.agent.controller;

import com.novel.agent.service.DataImportService;
import com.novel.agent.service.MilvusAdminService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataImportControllerTest {

    @Test
    void getProgressReturnsEnhancedStatusView() {
        DataImportService dataImportService = mock(DataImportService.class);
        MilvusAdminService milvusAdminService = mock(MilvusAdminService.class);
        DataImportController controller = new DataImportController(dataImportService, milvusAdminService);

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
        DataImportController controller = new DataImportController(dataImportService, milvusAdminService);

        Map<String, Object> status = Map.of(
                "running", true,
                "stage", "importing_lines",
                "processedRecords", 80L,
                "totalRecords", 500L
        );

        when(dataImportService.isRunning()).thenReturn(true);
        when(dataImportService.getImportStatus()).thenReturn(status);

        Map<String, Object> response = controller.importTrainingData("D:/dataset.jsonl");

        assertThat(response.get("success")).isEqualTo(false);
        assertThat(response.get("status")).isEqualTo(status);
    }
}