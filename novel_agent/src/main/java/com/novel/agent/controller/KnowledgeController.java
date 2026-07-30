package com.novel.agent.controller;

import com.novel.agent.service.KnowledgeBatchProcessor;
import com.novel.agent.service.KnowledgeSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeBatchProcessor batchProcessor;
    private final KnowledgeSearchService searchService;

    /**
     * 触发批量处理所有未处理的 TXT 文件
     */
    @PostMapping("/process")
    public Map<String, Object> processAll() {
        KnowledgeBatchProcessor.ProcessResult result = batchProcessor.processAll();
        return Map.of(
                "success", true,
                "processedFiles", result.processedFiles,
                "storedSegments", result.storedSegments,
                "failedSegments", result.failedSegments,
                "skipped", result.skipped
        );
    }

    /**
     * 处理单本小说
     */
    @PostMapping("/process/{fileName}")
    public Map<String, Object> processFile(@PathVariable String fileName) {
        KnowledgeBatchProcessor.ProcessFileResult result = batchProcessor.processFile(
                java.nio.file.Paths.get("novels/" + fileName));
        return Map.of(
                "success", true,
                "totalSegments", result.totalSegments,
                "storedSegments", result.storedSegments,
                "failedSegments", result.failedSegments
        );
    }

    /**
     * 检索外部知识库
     */
    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK) {
        List<KnowledgeSearchService.KnowledgeRef> results = searchService.search(query, topK);
        return Map.of(
                "success", true,
                "query", query,
                "results", results
        );
    }

    /**
     * 获取外部知识参考（用于注入 Prompt）
     */
    @GetMapping("/prompt")
    public Map<String, Object> getKnowledgePrompt(@RequestParam String query) {
        String prompt = searchService.buildKnowledgePrompt(query);
        return Map.of(
                "success", true,
                "query", query,
                "prompt", prompt
        );
    }
}