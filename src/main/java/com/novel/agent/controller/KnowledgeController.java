package com.novel.agent.controller;

import com.novel.agent.service.KnowledgeBatchProcessor;
import com.novel.agent.service.KnowledgeSearchService;
import com.novel.agent.security.FileAccessPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeBatchProcessor batchProcessor;
    private final KnowledgeSearchService searchService;
    private final FileAccessPolicy fileAccessPolicy;

    @Value("${knowledge.novel-dir:novels/}")
    private String novelDir;

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
        Path safePath;
        try {
            safePath = fileAccessPolicy.requireAllowedFileName(novelDir, fileName);
        } catch (IllegalArgumentException exception) {
            log.warn("Rejected knowledge file path: {}", exception.getMessage());
            return Map.of("success", false, "message", "file path is not allowed");
        }
        log.info("Knowledge file processing requested, fileName={}", safePath.getFileName());
        KnowledgeBatchProcessor.ProcessFileResult result = batchProcessor.processFile(safePath);
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

