package com.novel.agent.config;

import com.novel.agent.service.MilvusAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Restores the load state of indexed Milvus collections after application startup.
 * Collections without an index remain managed by the import/finalize workflow.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilvusLifecycleConfig {

    private final MilvusAdminService milvusAdminService;

    @Value("${milvus.lifecycle.auto-load:true}")
    private boolean autoLoad;

    @EventListener(ApplicationReadyEvent.class)
    public void autoLoadIndexedCollections() {
        if (!autoLoad) {
            log.info("Milvus collection auto-load is disabled");
            return;
        }

        log.info("Auto-loading indexed Milvus collections");
        milvusAdminService.loadAllCollections();
    }
}
