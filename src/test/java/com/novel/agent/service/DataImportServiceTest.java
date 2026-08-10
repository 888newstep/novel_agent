package com.novel.agent.service;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.InsertResp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataImportServiceTest {

    @Test
    void retriesFailedBatchAfterCleanup(@TempDir Path tempDir) throws Exception {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        DataImportService service = new DataImportService(milvusClient, embeddingService);
        configure(service, 1, 2, 0L);

        Path dataset = tempDir.resolve("dataset.jsonl");
        Files.writeString(dataset, """
                {"instruction":"write","input":"scene","output":"continuation"}
                """);

        List<List<Float>> vectors = List.of(
                List.of(0.1f, 0.2f),
                List.of(0.3f, 0.4f)
        );
        when(embeddingService.batchGenerateEmbedding(anyList())).thenReturn(vectors);
        when(milvusClient.insert(any(InsertReq.class)))
                .thenThrow(new RuntimeException("insert failed once"))
                .thenReturn(mock(InsertResp.class));
        when(milvusClient.delete(any(DeleteReq.class))).thenReturn(mock(DeleteResp.class));

        DataImportService.ImportResult result = service.importFromJson(dataset.toString());
        Map<String, Object> status = service.getImportStatus();

        assertThat(result.successCount).isEqualTo(2L);
        assertThat(result.failCount).isEqualTo(0L);
        assertThat(result.totalProcessed).isEqualTo(2L);
        assertThat(result.novelId).isEqualTo(0L);
        assertThat(result.retryCount).isEqualTo(1L);
        assertThat(status.get("stage")).isEqualTo("completed");
        assertThat(status.get("novelId")).isEqualTo(0L);
        assertThat(status.get("retryCount")).isEqualTo(1L);
        assertThat(status.get("lastRetriedRange")).isEqualTo("1");
        assertThat(status.get("lastRetryReason")).isEqualTo("insert failed once");
        assertThat(status.get("checkpointExists")).isEqualTo(false);
        assertThat(Files.exists(dataset.resolveSibling("dataset.jsonl.checkpoint"))).isFalse();

        ArgumentCaptor<DeleteReq> deleteCaptor = ArgumentCaptor.forClass(DeleteReq.class);
        verify(milvusClient, times(2)).insert(any(InsertReq.class));
        verify(milvusClient).delete(deleteCaptor.capture());
        verify(embeddingService, times(2)).batchGenerateEmbedding(anyList());
        assertThat(deleteCaptor.getValue().getCollectionName()).isEqualTo("novel_segments");
        assertThat(deleteCaptor.getValue().getFilter()).isEqualTo("novel_id == 0 && chapter_num >= 1 && chapter_num <= 1");
    }

    @Test
    void failsImportWhenRetryBudgetIsExhausted(@TempDir Path tempDir) throws Exception {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        DataImportService service = new DataImportService(milvusClient, embeddingService);
        configure(service, 1, 2, 0L);

        Path dataset = tempDir.resolve("dataset.jsonl");
        Files.writeString(dataset, """
                {"instruction":"write","input":"scene","output":"continuation"}
                """);

        when(embeddingService.batchGenerateEmbedding(anyList())).thenReturn(List.of(
                List.of(0.1f, 0.2f),
                List.of(0.3f, 0.4f)
        ));
        when(milvusClient.insert(any(InsertReq.class))).thenThrow(new RuntimeException("insert still failing"));
        when(milvusClient.delete(any(DeleteReq.class))).thenReturn(mock(DeleteResp.class));

        assertThatThrownBy(() -> service.importFromJson(dataset.toString()))
                .isInstanceOf(RuntimeException.class);

        Map<String, Object> status = service.getImportStatus();
        assertThat(status.get("stage")).isEqualTo("failed");
        assertThat(status.get("retryCount")).isEqualTo(1L);
        assertThat(status.get("lastRetryReason")).isEqualTo("insert still failing");
        assertThat(status.get("checkpointExists")).isEqualTo(false);

        verify(milvusClient, times(2)).insert(any(InsertReq.class));
        verify(milvusClient, times(1)).delete(any(DeleteReq.class));
        verify(embeddingService, times(2)).batchGenerateEmbedding(anyList());
    }

    @Test
    void isolatesCustomNovelIdForRowsAndRetryCleanup(@TempDir Path tempDir) throws Exception {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        DataImportService service = new DataImportService(milvusClient, embeddingService);
        configure(service, 1, 2, 0L);

        Path dataset = tempDir.resolve("dataset.jsonl");
        Files.writeString(dataset, """
                {"instruction":"write","input":"scene","output":"continuation"}
                """);

        when(embeddingService.batchGenerateEmbedding(anyList())).thenReturn(List.of(
                List.of(0.1f, 0.2f),
                List.of(0.3f, 0.4f)
        ));
        when(milvusClient.insert(any(InsertReq.class)))
                .thenThrow(new RuntimeException("insert failed once"))
                .thenReturn(mock(InsertResp.class));
        when(milvusClient.delete(any(DeleteReq.class))).thenReturn(mock(DeleteResp.class));

        DataImportService.ImportResult result = service.importFromJson(dataset.toString(), 42L);
        Map<String, Object> status = service.getImportStatus();

        assertThat(result.novelId).isEqualTo(42L);
        assertThat(status.get("novelId")).isEqualTo(42L);
        assertThat(status.get("stage")).isEqualTo("completed");
        assertThat(Files.exists(dataset.resolveSibling("dataset.jsonl.checkpoint"))).isFalse();
        assertThat(Files.exists(dataset.resolveSibling("dataset.jsonl.novel-42.checkpoint"))).isFalse();

        ArgumentCaptor<DeleteReq> deleteCaptor = ArgumentCaptor.forClass(DeleteReq.class);
        ArgumentCaptor<InsertReq> insertCaptor = ArgumentCaptor.forClass(InsertReq.class);
        verify(milvusClient, times(2)).insert(insertCaptor.capture());
        verify(milvusClient).delete(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().getFilter())
                .isEqualTo("novel_id == 42 && chapter_num >= 1 && chapter_num <= 1");
        assertThat(String.valueOf(insertCaptor.getAllValues().get(1).getData()))
                .contains("\"novel_id\":42");
    }

    @Test
    void rejectsNegativeNovelId(@TempDir Path tempDir) throws Exception {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        DataImportService service = new DataImportService(milvusClient, embeddingService);
        Path dataset = tempDir.resolve("dataset.jsonl");
        Files.writeString(dataset, "{}");

        assertThatThrownBy(() -> service.importFromJson(dataset.toString(), -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("novelId");
    }

    private void configure(DataImportService service, int batchSize, int maxRetries, long retryBackoffMs) {
        ReflectionTestUtils.setField(service, "segmentsCollection", "novel_segments");
        ReflectionTestUtils.setField(service, "batchSize", batchSize);
        ReflectionTestUtils.setField(service, "maxRetries", maxRetries);
        ReflectionTestUtils.setField(service, "retryBackoffMs", retryBackoffMs);
    }
}
