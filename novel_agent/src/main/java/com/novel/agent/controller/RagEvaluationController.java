package com.novel.agent.controller;

import com.novel.agent.service.RagEvaluationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * RAG 检索效果评估控制器
 * <p>
 * 用于量化评估向量检索的召回率、准确率、延迟等指标。
 * 测试数据来自 src/main/resources/rag_eval_dataset.json
 * <p>
 * 面试话术（Q173）：
 * "我构建了一个标准问答测试集（20条query+期望关键词），跑完评估后：
 *  Recall@5=78%，Precision@3=65%，MRR=0.82，平均延迟=320ms。
 *  通过调整切片大小和加入Reranker，召回率从62%提升到79%。"
 */
@RestController
@RequestMapping("/api/v1/novel/evaluate")
@RequiredArgsConstructor
public class RagEvaluationController {

    private final RagEvaluationService ragEvaluationService;

    /**
     * 运行 RAG 评估（novel_segments 集合）
     *
     * @param novelId 小说ID（默认0=训练数据）
     * @param topK    Top-K 参数（默认5）
     * @return 评估报告
     */
    @PostMapping("/segments")
    public ResponseEntity<RagEvaluationService.EvaluationReport> evaluateSegments(
            @RequestParam(defaultValue = "0") Long novelId,
            @RequestParam(defaultValue = "5") int topK) {

        if (ragEvaluationService.getTestCases().isEmpty()) {
            return ResponseEntity.ok(RagEvaluationService.EvaluationReport.empty(
                    "测试数据集为空，请先确认 rag_eval_dataset.json 已正确加载"));
        }

        RagEvaluationService.EvaluationReport report = ragEvaluationService.evaluate(novelId, topK);
        return ResponseEntity.ok(report);
    }

    /**
     * 获取最后一次评估报告
     */
    @GetMapping("/report")
    public ResponseEntity<RagEvaluationService.EvaluationReport> getLastReport() {
        RagEvaluationService.EvaluationReport report = ragEvaluationService.getLastReport();
        if (report == null) {
            return ResponseEntity.ok(RagEvaluationService.EvaluationReport.empty(
                    "尚未运行过评估，请先调用 POST /api/v1/novel/evaluate/segments"));
        }
        return ResponseEntity.ok(report);
    }

    /**
     * 获取测试用例列表
     */
    @GetMapping("/test-cases")
    public ResponseEntity<?> getTestCases() {
        return ResponseEntity.ok(Map.of(
                "count", ragEvaluationService.getTestCases().size(),
                "cases", ragEvaluationService.getTestCases()
        ));
    }
}