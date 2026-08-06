# novel_agent 数据链路说明

本文档用于说明 `novel_agent` 项目的核心数据链路、检索方法、记忆构建方式、Token 成本控制链路，以及主要落点文件与方法，方便后续继续优化小说知识管理、当前章节近因记忆加权、性能与 Token 消耗控制。

---

## 1. 项目整体目标

项目当前的方向可以概括为 4 件事：

1. 保留既有数据结构，不大改实体与表结构。
2. 通过调整检索与记忆拼装逻辑，提升小说写作场景下的“记忆命中率”。
3. 按当前章节做近因记忆加权，优先召回与当前章节更接近的内容。
4. 降低无效检索、降低 Token 消耗、减少重复上下文，并提供成本控制面板。

换句话说，当前项目不是在重做存储层，而是在既有 MySQL + Milvus 结构上，重点优化“怎么取、怎么排、怎么送给模型”。

---

## 2. 总体数据链路

项目可以拆成 7 条主链路：

### 2.1 小说业务写入链路

`HTTP 请求 -> NovelController -> JPA Repository -> MySQL`

用途：

- 创建小说 `Novel`
- 创建章节 `Chapter`
- 创建事件 `KeyEvent`
- 创建角色 `NovelCharacter`
- 创建灵感 `Inspiration`
- 查询小说、章节、角色、未解决事件

主要入口：

- `controller/NovelController.java`
- `repository/NovelRepository.java`
- `repository/ChapterRepository.java`
- `repository/KeyEventRepository.java`
- `repository/CharacterRepository.java`
- `repository/InspirationRepository.java`

### 2.2 向量写入链路

`业务对象 / 文本 -> EmbeddingService -> MilvusService -> Milvus Collection`

用途：

- 章节片段向量化
- 事件向量化
- 角色向量化
- 物品 / 技能 / 功法等条目向量化
- 阵营 / 灵感向量化

主要入口：

- `service/EmbeddingService.java`
- `service/MilvusService.java`

### 2.3 写作检索与记忆构建链路

`写作主题 -> MilvusSearchService -> MySQL / Milvus -> WritingMemory -> Prompt`

用途：

- 为续写、生成章节、检索记忆提供上下文
- 结合向量召回、关键词命中、章节距离做排序
- 按当前章节过滤未来章节内容，避免穿帮

主要入口：

- `service/MilvusSearchService.java`
- `controller/NovelController.java`

### 2.4 AI 生成链路

`WritingMemory + 系统提示词 + 用户主题 -> DeepSeekService -> 模型输出`

用途：

- 生成章节内容
- 生成续写文本
- 将检索结果组织成 Prompt 后送入大模型

主要入口：

- `controller/NovelController.java`
- `service/DeepSeekService.java`
- `config/AiModelConfig.java`

### 2.5 Token / 成本控制链路

`AI / Embedding 请求 -> TokenCostService -> 统计 / 限额 / 面板`

用途：

- 估算输入、输出、Embedding Token
- 做单次请求、日预算、月预算限制
- 提供汇总、明细、设置修改能力
- 提供成本控制面板页面

主要入口：

- `service/TokenCostService.java`
- `controller/CostControlController.java`
- `controller/CostPanelController.java`
- `src/main/resources/static/cost-dashboard.html`

### 2.6 外部知识导入链路

`外部文本 / JSON -> 批处理 -> Embedding -> Milvus`

用途：

- 导入额外小说知识
- 导入训练样本或续写样本
- 批量切分、嵌入、写入向量库

主要入口：

- `service/KnowledgeBatchProcessor.java`
- `service/KnowledgeSearchService.java`
- `service/DataImportService.java`

### 2.7 检索评估链路

`测试集 -> MilvusSearchService -> 指标汇总`

用途：

- 评估召回率、准确率、MRR、延迟
- 检查当前检索策略是否真的改善写作记忆效果

主要入口：

- `service/RagEvaluationService.java`
- `controller/RagEvaluationController.java`

---

## 3. 核心存储层

### 3.1 MySQL：结构化事实层

MySQL 负责存放“确定性事实”。

典型实体包括：

- `Novel`：小说本体
- `Chapter`：章节内容
- `KeyEvent`：关键事件、伏笔、待解决事件
- `NovelCharacter`：角色
- `Artifact`：物品、法宝、道具等
- `Skill`：技能、功法、能力
- `Faction`：阵营、组织、势力
- `Inspiration`：灵感素材
- `Relation`：角色关系、阵营关系、对象关系

作用：

- 作为业务主存储
- 保留完整原始信息
- 支撑章节顺序、事件状态、关系查询等强结构化逻辑
- 给向量库提供可回填的元信息来源

### 3.2 Milvus：语义检索层

Milvus 负责存放“可被语义召回的文本片段与对象描述”。

当前主要集合：

- `novel_segments`
- `novel_events`
- `novel_characters`
- `novel_items`
- `novel_faction_inspire`

作用：

- 提供向量语义检索
- 在写作场景中召回相关章节片段、事件、角色与设定
- 把结构化知识转换成可被模型消费的文本上下文

### 3.3 内存统计层

项目里还存在轻量内存态统计数据，用于成本控制与短期状态维护。

典型位置：

- `TokenCostService` 中的使用记录队列
- `EmbeddingService` 中的嵌入缓存
- 导入任务中的进度计数器

作用：

- 降低重复请求
- 记录最近调用成本
- 给面板展示即时统计数据

---

## 4. 配置链路

### 4.1 配置来源

主配置文件：

- `src/main/resources/application.yml`

主要配置内容：

- MySQL 数据源
- AI 模型提供商与模型名
- Embedding 提供商与维度
- 成本控制预算参数
- Milvus 连接与集合名
- 批处理写入参数
- 外部知识目录

### 4.2 配置对象映射

配置对象：

- `config/AiProperties.java`

它把配置映射为以下几组：

- `model`
- `embedding`
- `costControl`
- `deepseek`
- `qianwen`
- `local`
- `fallback`
- `pricing`

### 4.3 Bean 装配

装配入口：

- `config/AiModelConfig.java`

关键方法：

- `chatLanguageModel()`
- `streamingChatLanguageModel()`
- `embeddingModel()`

作用：

- 根据配置切换不同模型提供商
- 统一注入聊天模型与 Embedding 模型
- 避免业务层直接依赖不同供应商 SDK

---

## 5. 小说业务数据链路

### 5.1 NovelController 入口

核心控制器：

- `controller/NovelController.java`

主要方法：

- `createNovel()`
- `listNovels()`
- `getNovel()`
- `createChapter()`
- `listChapters()`
- `createEvent()`
- `getUnresolvedEvents()`
- `resolveEvent()`
- `addInspiration()`
- `createCharacter()`
- `listCharacters()`
- `generateChapter()`
- `searchSegments()`
- `buildAllIndexes()`
- `rebuildNovel()`

### 5.2 业务写入特点

业务写入优先进入 MySQL，再根据需要进入 Milvus。

原因：

- MySQL 适合保存业务主数据
- Milvus 适合做语义召回，不适合代替完整业务存储
- 这样可以保留“结构化事实”和“语义记忆”两层能力

### 5.3 重建索引链路

当已有小说数据需要补建向量索引时，会走重建流程。

大体路径：

`NovelController -> MilvusAdminService / MilvusService -> Milvus`

作用：

- 重新为已有小说构建向量数据
- 清理旧向量并重写
- 保证 MySQL 与 Milvus 内容一致

---

## 6. 向量写入链路

### 6.1 EmbeddingService

核心文件：

- `service/EmbeddingService.java`

关键方法：

- `init()`
- `preprocess(String text)`
- `generateEmbedding(String text)`
- `batchGenerateEmbedding(List<String> texts)`

当前已经具备的性能优化：

- 文本预处理，减少无意义空白差异
- LRU 风格缓存：`EMBEDDING_CACHE_SIZE = 512`
- 批量嵌入优先，单条回退补偿
- 与 `TokenCostService` 联动，记录 Embedding 开销

这部分直接对应“提高性能、降低 Token 消耗、减少冗余”的目标。

### 6.2 MilvusService

核心文件：

- `service/MilvusService.java`

关键方法：

- `insertChapterSegments(...)`
- `insertKeyEvent(...)`
- `insertCharacter(...)`
- `insertItem(...)`
- `insertFactionOrInspiration(...)`
- `batchInsert(...)`

作用：

- 把章节、事件、角色、设定等对象转换为向量行
- 统一封装 Milvus 插入逻辑
- 控制不同集合的字段结构和写入格式

### 6.3 DataImportService

核心文件：

- `service/DataImportService.java`

关键方法：

- `importFromJson(String jsonFilePath)`
- `detectFormat(...)`
- `doImportArray(...)`
- `doImportLines(...)`
- `processBatch(...)`
- `getProgress()`
- `getTotal()`
- `isRunning()`

说明：

- 支持数组 JSON 与 JSONL 两种导入形式
- 支持断点续导
- 支持批量嵌入后统一写入 `novel_segments`
- 导入时会维护进度与 flush 节奏

---

## 7. 写作检索 / 记忆链路

### 7.1 MilvusSearchService 的职责

核心文件：

- `service/MilvusSearchService.java`

它是当前项目最关键的“记忆策略层”。

主要公开方法：

- `searchSegments(...)`
- `searchEvents(...)`
- `searchUnresolvedEvents(...)`
- `searchCharacters(...)`
- `searchItems(...)`
- `searchFactionOrInspiration(...)`
- `buildWritingMemory(...)`
- `markEventResolved(...)`

### 7.2 buildWritingMemory 的组成

`buildWritingMemory(novelId, queryText, currentChapterNum)` 会拼装出写作记忆包 `WritingMemory`，主要包含：

- `recentChapters`
- `segments`
- `hooks`
- `characters`
- `items`
- `factions`
- `relations`
- `totalCount`

这说明项目当前已经不是“只查章节片段”，而是把小说写作所需的多种记忆来源组合成一份统一上下文。

### 7.3 近因记忆加权：按当前章节做召回优化

这是项目里最重要的一条检索优化链路。

入口参数：

- `currentChapterNum`

该参数会一路传入：

- `NovelController.generateChapter(...)`
- `NovelController.searchSegments(...)`
- `MilvusSearchService.searchSegments(...)`
- `MilvusSearchService.searchEvents(...)`
- `MilvusSearchService.searchUnresolvedEvents(...)`
- `MilvusSearchService.buildWritingMemory(...)`

核心处理逻辑：

1. 过滤未来章节内容，避免在当前章节检索到“后文剧透”。
2. 计算章节距离 `chapterDistance`。
3. 使用 `computeChapterProximityBoost(...)` 对近章节内容增加排序分。
4. 将近因加权结果写入 `rankScore`。

当前加权规则大致是：

- 当前章节：最高加权
- 前 1 章：次高加权
- 前 2～5 章：逐级衰减
- 更早章节：保留极小基础加成

这正是“按当前章节做近因记忆加权”的具体落点。

### 7.4 hybridSearch：混合检索核心

核心私有方法：

- `hybridSearch(...)`

它承担以下职责：

- 生成多组查询变体 `buildQueryVariants(...)`
- 批量生成查询向量 `batchGenerateEmbedding(...)`
- 对多个 query variant 分别检索
- 按唯一键合并去重
- 综合向量分、关键词命中数、命中 query 数、章节距离进行重排
- 最终限制返回数量，避免上下文膨胀

当前排名信号包括：

- 原始向量得分 `score`
- 关键词命中数 `keywordHits`
- 命中变体数 `queryHits`
- 章节邻近加权 `chapterProximityBoost`
- 章节距离 `chapterDistance`
- 非当前章节模式下的轻度 recency 加成

这部分是当前“提升记忆力”最关键的实现位置。

### 7.5 减少冗余的具体方式

当前实现已经体现出几种降低冗余的做法：

- 查询扩展后统一合并去重
- 对每章结果做限制 `perChapterLimit`
- 对最终返回条数做截断 `limitResults(...)`
- 把结果组织成 `WritingMemory`，而不是无限拼接原始召回文本

这几项直接帮助：

- 降低 Prompt 长度
- 降低重复语义段落出现概率
- 提高真正相关内容的占比

---

## 8. AI 生成链路

### 8.1 入口方法

主要入口：

- `NovelController.generateChapter(...)`

核心流程：

1. 根据 `novelId` 读取小说基础设定。
2. 根据 `topic + currentChapterNum` 构建 `WritingMemory`。
3. 组装系统提示词与用户提示词。
4. 调用 `DeepSeekService.chat(...)`。
5. 返回生成结果、记忆摘要、Prompt 长度等信息。

### 8.2 DeepSeekService

核心文件：

- `service/DeepSeekService.java`

关键方法：

- `init()`
- `chat(String systemPrompt, String userPrompt)`
- `chatDirect(String systemPrompt, String userPrompt, double temperature, int maxTokens)`

作用：

- 统一封装聊天调用
- 在请求前通过 `TokenCostService` 预留预算
- 在成功后记录真实消耗或估算消耗
- 在失败时记录失败状态

### 8.3 Token 节流点

AI 生成链路里的 Token 控制点主要有：

- 生成前估算输入 Token
- 预留输出 Token 上限
- 预算超限时在严格模式下直接拒绝
- 生成后记录真实或近似使用量

---

## 9. Token 消耗统计与成本控制面板

### 9.1 TokenCostService

核心文件：

- `service/TokenCostService.java`

关键能力：

- `estimateTokens(String text)`
- `estimateTokens(List<String> texts)`
- 预留请求配额
- 成功记账
- 失败记账
- 汇总日 / 月 Token 与费用
- 最近记录裁剪
- 设置快照与动态更新

主要内部概念：

- `UsageReservation`
- `UsageRecord`
- `DashboardSummary`
- `SettingsSnapshot`
- `SettingsUpdateRequest`

### 9.2 成本限制逻辑

成本控制会检查：

- 单次请求最大预计 Token
- 每日 Token 预算
- 每月 Token 预算
- 每日美元预算
- 每月美元预算

相关配置来源：

- `application.yml` 中的 `ai.cost-control`

当前已存在的重要配置：

- `enabled`
- `strict-mode`
- `recent-records`
- `max-estimated-tokens-per-request`
- `reserved-completion-tokens`
- `daily-token-budget`
- `monthly-token-budget`
- `daily-budget-usd`
- `monthly-budget-usd`
- `pricing`

### 9.3 成本控制接口

控制器：

- `controller/CostControlController.java`

接口：

- `GET /api/admin/cost/summary`
- `GET /api/admin/cost/records`
- `GET /api/admin/cost/settings`
- `PUT /api/admin/cost/settings`
- `DELETE /api/admin/cost/records`

### 9.4 成本控制面板

页面跳转控制器：

- `controller/CostPanelController.java`

页面文件：

- `src/main/resources/static/cost-dashboard.html`

链路为：

`/cost-panel -> CostPanelController -> forward:/cost-dashboard.html -> 前端调用 /api/admin/cost/*`

这部分就是“实现 Token 消耗统计的成本控制面板”的实际落点。

---

## 10. 外部知识链路

### 10.1 KnowledgeBatchProcessor

核心文件：

- `service/KnowledgeBatchProcessor.java`

关键方法：

- `processAll()`
- `processFile(Path filePath)`
- `processBatch(List<KnowledgeSegment> segments)`

作用：

- 批量处理知识文件
- 切段、转 Embedding、写入向量库
- 为知识检索提供原始语料

### 10.2 KnowledgeSearchService

核心文件：

- `service/KnowledgeSearchService.java`

关键方法：

- `search(String queryText, int topK)`
- `buildKnowledgePrompt(String queryText)`

作用：

- 检索外部知识
- 把外部知识拼接成可注入 Prompt 的参考文本

### 10.3 外部知识在项目中的价值

这条链路的意义是：

- 内部小说记忆不足时，可以补充项目外知识
- 可以把世界观资料、设定库、参考文本纳入统一检索框架
- 未来也可以与小说主记忆进行混合召回

---

## 11. RAG 评估链路

### 11.1 RagEvaluationService

核心文件：

- `service/RagEvaluationService.java`

关键方法：

- `init()`
- `evaluate(Long novelId, int topK)`
- `getLastReport()`
- `getTestCases()`

输出指标主要包括：

- `Recall@K`
- `Precision@K`
- `MRR`
- 平均延迟
- `P99` 延迟
- 关键词覆盖率

### 11.2 RagEvaluationController

核心文件：

- `controller/RagEvaluationController.java`

接口：

- `POST /api/v1/novel/evaluate/segments`
- `GET /api/v1/novel/evaluate/report`
- `GET /api/v1/novel/evaluate/test-cases`

### 11.3 评估链路意义

如果后续继续优化检索策略，例如：

- 改 query 扩展策略
- 改章节近因加权系数
- 改每类记忆数量配额
- 改 `perChapterLimit`
- 加 rerank

都应该通过这一条链路来验证效果，而不是只靠主观感觉。

---

## 12. 当前项目中“提高记忆力”的关键实现点

如果目标是“保留原数据结构，但优化检索方法让小说更会记”，当前最关键的改造位如下：

### 12.1 第一优先级：MilvusSearchService

原因：

- 它决定查什么
- 它决定如何合并结果
- 它决定如何排序
- 它决定最终送给模型多少上下文

重点关注方法：

- `buildWritingMemory(...)`
- `hybridSearch(...)`
- `buildQueryVariants(...)`
- `loadRecentChapters(...)`
- `computeChapterProximityBoost(...)`
- `limitByChapter(...)`

### 12.2 第二优先级：NovelController.generateChapter

原因：

- 它决定把哪些记忆真正送进 Prompt
- 它是“检索结果 -> 模型输入”的最后一站

重点关注：

- `currentChapterNum` 是否总是正确传入
- `buildMemorySummary(...)` 是否摘要得足够精简
- Prompt 是否存在重复片段拼接

### 12.3 第三优先级：EmbeddingService

原因：

- 它影响向量质量与吞吐性能
- 它直接影响 Token 和网络调用成本

重点关注：

- 文本预处理是否稳定
- 批量嵌入是否尽量命中
- 缓存是否足够有效

### 12.4 第四优先级：TokenCostService

原因：

- 它决定能否把检索优化转化为真实成本下降
- 可以量化每次改动是否减少 Token 消耗

---

## 13. 简化数据流图

### 13.1 写作生成主链路

`topic`
-> `NovelController.generateChapter(...)`
-> `MilvusSearchService.buildWritingMemory(...)`
-> `searchSegments / searchUnresolvedEvents / searchCharacters / searchItems / searchFactionOrInspiration`
-> `WritingMemory`
-> `DeepSeekService.chat(...)`
-> `TokenCostService` 记录成本
-> 返回生成结果

### 13.2 章节近因检索链路

`currentChapterNum`
-> `MilvusSearchService`
-> 过滤未来章节
-> 计算 `chapterDistance`
-> 计算 `chapterProximityBoost`
-> 合并进 `rankScore`
-> 返回更贴近当前章节的记忆

### 13.3 成本控制链路

`Chat / Embedding 请求`
-> `TokenCostService.reserve...`
-> 预算检查
-> 执行请求
-> `recordChatSuccess / recordEmbeddingSuccess / recordFailure`
-> `CostControlController`
-> `cost-dashboard.html`

---

## 14. 快速定位索引

如果后面继续做优化，建议优先看这些文件：

- 记忆构建：`src/main/java/com/novel/agent/service/MilvusSearchService.java`
- 生成入口：`src/main/java/com/novel/agent/controller/NovelController.java`
- 向量生成：`src/main/java/com/novel/agent/service/EmbeddingService.java`
- 向量写入：`src/main/java/com/novel/agent/service/MilvusService.java`
- 成本控制：`src/main/java/com/novel/agent/service/TokenCostService.java`
- 成本接口：`src/main/java/com/novel/agent/controller/CostControlController.java`
- 成本页面：`src/main/resources/static/cost-dashboard.html`
- 外部知识：`src/main/java/com/novel/agent/service/KnowledgeBatchProcessor.java`
- 检索评估：`src/main/java/com/novel/agent/service/RagEvaluationService.java`
- 配置入口：`src/main/resources/application.yml`

---

## 15. 结论

当前项目的数据结构已经具备“小说知识管理 + 语义检索 + AI 写作”的基础能力，真正影响效果的核心不在表结构，而在检索策略与上下文拼装策略。

项目里已经有几条非常重要的优化方向落地：

- 保持 MySQL 负责事实存储，Milvus 负责语义记忆
- 用 `buildWritingMemory(...)` 统一构建写作上下文
- 用 `currentChapterNum` 做近因记忆加权
- 用 `hybridSearch(...)` 做多变体召回、去重、重排
- 用 `EmbeddingService` 的缓存与批处理减少重复开销
- 用 `TokenCostService` 和成本面板量化 Token 消耗

如果后续继续提升“记忆力”，最值得继续深挖的就是：

1. `MilvusSearchService` 的排序权重。
2. `WritingMemory` 各类记忆的配额分配。
3. Prompt 摘要压缩策略。
4. Token 成本统计与阈值联动策略。

这 4 个点决定了项目后续的效果上限。
