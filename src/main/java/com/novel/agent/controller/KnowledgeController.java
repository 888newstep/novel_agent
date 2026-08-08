package com.novel.agent.controller;

import com.novel.agent.service.KnowledgeBatchProcessor;
import com.novel.agent.service.KnowledgeSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeBatchProcessor batchProcessor;
    private final KnowledgeSearchService searchService;

    @PostMapping("/process")
    public Map<String, Object> processAll() {
        log.info("Knowledge batch processing requested");
        KnowledgeBatchProcessor.ProcessResult result = batchProcessor.processAll();
        return Map.of(
                "success", true,
                "processedFiles", result.processedFiles,
                "storedSegments", result.storedSegments,
                "failedSegments", result.failedSegments,
                "skipped", result.skipped
        );
    }

    @PostMapping("/process/{fileName}")
    public Map<String, Object> processFile(@PathVariable String fileName) {
        log.info("Knowledge file processing requested, fileName={}", fileName);
        KnowledgeBatchProcessor.ProcessFileResult result = batchProcessor.processFile(
                java.nio.file.Paths.get("novels/" + fileName));
        return Map.of(
                "success", true,
                "totalSegments", result.totalSegments,
                "storedSegments", result.storedSegments,
                "failedSegments", result.failedSegments
        );
    }

    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK) {
        log.info("Knowledge search requested, query={}, topK={}", query, topK);
        List<KnowledgeSearchService.KnowledgeRef> results = searchService.search(query, topK);
        return Map.of(
                "success", true,
                "query", query,
                "results", results
        );
    }

    @GetMapping("/prompt")
    public Map<String, Object> getKnowledgePrompt(@RequestParam String query) {
        log.info("Knowledge prompt requested, query={}", query);
        String prompt = searchService.buildKnowledgePrompt(query);
        return Map.of(
                "success", true,
                "query", query,
                "prompt", prompt
        );
    }
}

