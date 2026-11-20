# AI 痕迹自检器（AI Trace Detector）

> 版本：`aitrace-v1-p0` ｜ 阶段：P0 ｜ 状态：规则引擎 + 端点可用，**尚无召回/准确率评测数字**（P1 产出 40 段标注集与三连评测后才会出现）

## 一、定位与边界

面向中文网文的启发式 AI 痕迹自检工具：检测文本中 AI 常见的套话、句式均齐、转折词堆砌与模板化章末钩子，给出可解释的风险分与句级定位。

**诚实边界（必读）**：

- 本工具是启发式自检，**不是**任何平台（番茄/书旗等）AI 检测模型的复刻，不构成「过检保证」。
- 当前**没有**召回率、准确率、误报率数字；40 段标注集评测属 P1，出数前不得在任何对外材料中引用效果数字。
- 不适用于洗稿、伪装人写作规避平台标识义务等用途（《AI 生成合成内容标识办法》合规前提下使用）。

## 二、规则集（P0 四条）

| 规则 | 维度 | 判定 | 默认阈值 |
|---|---|---|---|
| R1 | lexical | 词典 cliche 分组词条密度 | 3.0 次/千字 |
| R3 | syntactic | 句长（去空白码点数）标准差 | < 6.0 触发；句数 < 8 不判定 |
| R5 | syntactic | 词典 transition 分组词条密度 | 2.0 次/千字 |
| R8 | structural | 末 2 段命中章末钩子模板数 | ≥ 2 才计分（降误报） |

R8 模板：悬念问句收尾（？结尾且含「竟/难道/究竟」）、「就在这时」、「不知道的是」、「一切才刚开始」、省略号/破折号悬停收尾。

**评分口径**：

- 规则原始分 0-100：密度规则 `min(100, density/threshold×50)`；R3 `min(100, (threshold−σ)/threshold×100)`；R8 `min(100, 50×命中模板数)`。
- 维度分 = 维度内规则原始分等权平均。
- 总分 = Σ(维度分×权重) / Σ(已计分维度权重)，权重 lexical 0.40 / syntactic 0.30 / structural 0.30（统计层 0.15 属 P3，未实现，其权重归一进已计分维度）。
- riskLevel：low < 25 ≤ medium ≤ 50 < high（25 与 50 归 medium）。
- `lowConfidence=true`：汉字符数 < `min-char-count`（200），或任一规则 UNKNOWN（词典降级 / 样本过短）。低置信结果的分数不构成可靠结论。
- `dimensionScores` 中未实现维度（statistical）**直接缺席**，不用 0 占位。

## 三、端点契约

`POST /api/v1/novel/ai-trace/check`

请求体二选一（`@AssertTrue` 校验，两者都传/都不传/残缺章节模式均 400）：

```json
{"text": "待检测正文，≤50000 字符"}
{"novelId": 2, "chapterId": 10}
```

章节模式说明：章节正文不入库，检测文本由 `summary + keyEvents` 拼接；章节不存在返回 404 `CHAPTER_NOT_FOUND`。

响应字段：`overallScore`、`riskLevel`、`lowConfidence`、`dimensionScores`、`findings`（含 ruleId/dimension/hitCount/density/rawScore/severity/evidence/message）、`flaggedSentences`（按分降序、上限 10）、`rulesVersion`、`disclaimer`、`charCount`、`hanCharCount`、`sentenceCount`。

**词典**：`src/main/resources/ai_trace_phrases_zh.txt`，UTF-8，`# group:` 分组（banned / cliche / transition），词条一行一个。加载失败时相关规则降级 UNKNOWN 并记 warn 日志，不影响应用启动。阈值等全部外置于 `application.yml` 的 `ai-trace.*`。

## 四、可用性证据

**P0 阶段（2026-11-20）**：完整应用启动依赖 Milvus 云端连接，而云服务器 49.234.187.76 于本次收尾时整台不可达（ping / SSH / 19530 全部失败），实时 curl 证据暂缺，**服务器恢复后补跑**：

```bash
curl -s -X POST http://localhost:8080/api/v1/novel/ai-trace/check \
  -H "Content-Type: application/json" \
  -d '{"text":"..."}'
```

**现有证据**：`AiTraceControllerTest`（MockMvc standaloneSetup，真实检测服务 + 真实词典，仅 HTTP 容器层为测试壳）验证：text 模式 200 且返回全部契约字段（overallScore / riskLevel / lowConfidence / dimensionScores / findings / flaggedSentences / rulesVersion=aitrace-v1-p0 / disclaimer / charCount / hanCharCount / sentenceCount）；章节模式 200；章节不存在 404；双模式 / 空请求 / 残缺章节模式 400。全量 78 个测试 0 失败 0 错误。

## 五、已知限制与遗留

- 句子切分口径：`。！？.!?…` + 收尾引号并入前句；无中文分词，密度按整串匹配，长词条跨句不计。
- R8 对「真人也用悬念收尾」的场景靠双模板阈值降误报，单模板只记录不计分。
- 历史遗留修复记录：原 `NovelController#buildPostGenerationCheck` 的英文禁用词（suddenly 等）对中文系统无效，已迁移至 `PostGenerationCheckService` 并改用词典 banned 中文词表（突然、忽然、不禁、不由得、心中一凛），逻辑与词表共存于 `ai_trace_phrases_zh.txt`。
- P1 计划：40 段标注集（人写 20 / AI 20）→ 三连只读评测 → 阈值调优（召回 ≥80%、误报 ≤10% 红线）→ 快照落 `docs/benchmarks/`。
