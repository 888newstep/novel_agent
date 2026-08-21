package com.novel.agent.service;

import io.milvus.v2.client.MilvusClientV2;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DataImportSlotTest {

    @Test
    void importSlotCanBeAcquiredOnlyOnce() {
        DataImportService service = new DataImportService(
                mock(MilvusClientV2.class),
                mock(EmbeddingService.class)
        );

        assertTrue(service.tryAcquireImportSlot());
        assertFalse(service.tryAcquireImportSlot());
        assertTrue(service.isRunning());

        service.releaseReservedImportSlot();

        assertTrue(service.tryAcquireImportSlot());
    }
}
