# Novel Agent

面向网文小说写作场景的垂直 AI 应用，聚焦“知识沉淀、记忆检索、续写生成、成本控制、效果评估”这条完整链路。

项目当前优先级是把小说场景做深，而不是扩展为通用 Agent 平台。更泛化的 Agent、工具调用、多 Agent 和平台化能力统一放在 `C:\Users\xiaohongfu\IdeaProjects\newagent` 中体现。

## 核心能力

- 小说实体管理：小说、章节、人物、势力、道具、技能、事件、灵感等结构化存储。
- 写作检索：基于 `Milvus` 的向量召回，结合章节感知、事件过滤和写作记忆拼装。
- 续写生成：基于 `DeepSeek` 的章节生成接口，按场景拼接系统提示和检索结果。
- 成本治理：记录 Token 消耗，提供预算限制、明细查看和成本面板。
- RAG 评估：离线评估召回率、准确率、MRR、关键词覆盖率和延迟表现。

## 技术栈

- `Java 17`
- `Spring Boot 3.5`
- `Spring Web / WebFlux / Validation / JPA`
- `MySQL`
- `Milvus`
- `LangChain4j`
- `DeepSeek API`
- `spring-dotenv`

## 项目结构

```text
src/main/java/com/novel/agent
├─ config        配置与模型装配
├─ controller    HTTP 接口
├─ entity        小说领域实体
├─ exception     业务异常
├─ repository    JPA 仓储
├─ service       检索、生成、导入、评估、成本控制
└─ utils         辅助工具

src/main/resources
├─ application.yml         主配置
├─ rag_eval_dataset.json   RAG 评估数据集
└─ static/cost-dashboard.html

sql/init.sql               MySQL 初始化脚本
datachain.md               数据链路说明
NOVEL_AGENT_PLAN.md        当前项目计划清单
```

## 快速开始

### 1. 前置条件

- 安装 `JDK 17`
- 安装 `Maven 3.9+`
- 准备 `MySQL 8.x`
- 准备 `Milvus 2.x`
- 准备至少一个可用模型密钥：`DEEPSEEK_API_KEY`
- 准备 Embedding 服务：本地 `Ollama` 或 `SiliconFlow`

### 2. 配置环境变量

复制示例文件并填写实际值：

```powershell
Copy-Item .env.example .env
```

默认会读取这些环境变量：

- `MYSQL_USERNAME`
- `MYSQL_PASSWORD`
- `DEEPSEEK_API_KEY`
- `QIANWEN_API_KEY`
- `SILICONFLOW_API_KEY`
- `MILVUS_HOST`
- `MILVUS_PORT`

主要配置见 `src/main/resources/application.yml`。

### 3. 初始化数据库

先创建数据库：

```sql
CREATE DATABASE novel_agent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

然后执行：

```powershell
mysql -u root -p novel_agent < sql/init.sql
```

### 4. 编译并启动

```powershell
mvn clean compile
mvn spring-boot:run
```

默认端口：`8080`

## 核心接口

### 小说业务

- `POST /api/v1/novel`
- `GET /api/v1/novel`
- `GET /api/v1/novel/{novelId}`
- `POST /api/v1/novel/{novelId}/chapter`
- `GET /api/v1/novel/{novelId}/chapters`
- `POST /api/v1/novel/{novelId}/event`
- `GET /api/v1/novel/{novelId}/events/unresolved`
- `POST /api/v1/novel/{novelId}/character`
- `GET /api/v1/novel/{novelId}/characters`

### 检索与生成

- `POST /api/v1/novel/{novelId}/generate`
- `POST /api/v1/novel/{novelId}/search`
- `POST /api/v1/novel/{novelId}/search/hooks`
- `GET /api/v1/novel/{novelId}/memory`
- `GET /api/v1/novel/health`

### 外部知识导入

- `POST /api/import/training-data`
- `GET /api/import/progress`
- `POST /api/import/finalize`
- `POST /api/knowledge/process`
- `POST /api/knowledge/process/{fileName}`
- `GET /api/knowledge/search`
- `GET /api/knowledge/prompt`

### 接口示例

#### 检索
```http
POST /api/v1/novel/1/search?query=dragon%20oath&topK=3&currentChapterNum=10
```
```json
{
  "query": "dragon oath",
  "currentChapterNum": 10,
  "count": 1,
  "results": [
    {
      "chapter_num": 9,
      "content": "dragon oath reveal",
      "rankScore": 2.361,
      "rankExplanation": "base_score=0.92 | keyword_hits=2 | chapter_distance=1",
      "recallTrace": {
        "matchedQueryVariants": ["dragon oath", "dragon oath 当前章节"],
        "scoreBreakdown": {
          "baseScore": 0.92,
          "keywordBoost": 0.8,
          "variantBoost": 0.4,
          "chapterBoost": 0.24,
          "finalScore": 2.361
        }
      }
    }
  ]
}
```

#### 生成
```http
POST /api/v1/novel/1/generate?topic=dragon%20oath&style=悬疑&promptId=1A&currentChapterNum=10
```
```json
{
  "topic": "dragon oath",
  "style": "悬疑",
  "currentChapterNum": 10,
  "content": "...",
  "memoryCount": 12,
  "memory": {
    "currentChapterNum": 10,
    "recentChapters": 3,
    "segments": 5,
    "hooks": 2,
    "characters": 4,
    "items": 1,
    "factions": 1,
    "relations": 2,
    "total": 18
  },
  "promptChars": 2680
}
```

#### 记忆预览
```http
GET /api/v1/novel/1/memory?query=dragon%20oath&currentChapterNum=10
```
```json
{
  "query": "dragon oath",
  "currentChapterNum": 10,
  "memory": {
    "query": "dragon oath",
    "currentChapterNum": 10,
    "recentChapters": [],
    "segments": [],
    "hooks": [],
    "characters": [],
    "items": [],
    "factions": [],
    "relations": [],
    "totalCount": 0
  },
  "summary": {
    "currentChapterNum": 10,
    "recentChapters": 0,
    "segments": 0,
    "hooks": 0,
    "characters": 0,
    "items": 0,
    "factions": 0,
    "relations": 0,
    "total": 0
  }
}
```

### 成本控制与评估

- `GET /api/admin/cost/summary`
- `GET /api/admin/cost/records`
- `GET /api/admin/cost/settings`
- `PUT /api/admin/cost/settings`
- `DELETE /api/admin/cost/records`
- `GET /cost-panel`
- `POST /api/v1/novel/evaluate/segments`
- `GET /api/v1/novel/evaluate/report`
- `GET /api/v1/novel/evaluate/test-cases`

## 验证建议

最小验证顺序：

1. 调用 `GET /api/v1/novel/health` 检查服务启动。
2. 创建一个小说，再插入章节和人物。
3. 调用检索接口确认 `Milvus` 链路正常。
4. 调用生成接口确认模型链路正常。
5. 调用评估接口确认离线评估可运行。
6. 打开 `GET /cost-panel` 检查成本面板展示。

## 当前重点

当前项目优先继续推进这些工作：

- 修稳启动、导入、检索、生成这四条主链路。
- 提升小说场景下的记忆召回质量和一致性。
- 补最小测试基线和量化评估结果。
- 加强成本控制、日志和可观测性。

不建议在当前项目优先扩展通用 Agent 平台特性；这部分统一放在 `newagent` 中承载。

## 参考文档

- `datachain.md`：数据链路说明
- `NOVEL_AGENT_PLAN.md`：当前项目执行计划
