# 项目架构图与运行流程图

这份文档基于当前 Java 版实现整理，目标是把系统的核心结构和三条主运行链路讲清楚：

- 文档上传入库链路
- Kafka 增量更新链路
- QA 问答链路

文档中的图使用 Mermaid 绘制，适合直接预览，也适合后续继续整理到 README、PPT 或答辩材料里。

## 1. 系统架构图

```mermaid
flowchart LR
    U["用户 / 前端 / 管理端"] --> API["Spring Boot API<br/>KnowledgeController"]
    CDC["Kafka CDC 事件<br/>doc-changes"] --> KUA["KnowledgeUpdateAgent"]

    API --> INGEST["IngestWorkflowService"]
    API --> QAWF["QaWorkflowService"]
    API --> REPLAY["EventReplayService"]

    subgraph WF["Workflow 基础设施"]
        WEC["WorkflowExecutionContext<br/>状态迁移 / 降级 / 重试统计"]
        WSR["WorkflowStepRunner<br/>统一重试"]
        EXEC["workflowExecutor<br/>Virtual Threads / Platform Threads"]
    end

    INGEST --> WEC
    INGEST --> WSR
    INGEST --> EXEC

    QAWF --> WEC
    QAWF --> WSR

    KUA --> EXEC

    subgraph AGENT["核心业务组件"]
        PARSER["DocParserAgent<br/>文档解析 + chunk 切分"]
        EXTRACT["KnowledgeExtractAgent<br/>LLM 知识抽取"]
        QAAGENT["QAAgent<br/>双路检索 + 混排 + 生成"]
        MEM["MemoryManager"]
    end

    INGEST --> PARSER
    INGEST --> EXTRACT

    QAWF --> MEM
    QAWF --> QAAGENT

    KUA --> PARSER
    KUA --> EXTRACT

    subgraph STORE["存储与检索"]
        VECTOR["VectorStoreService<br/>Milvus / InMemory"]
        GRAPH["KnowledgeGraphService<br/>Neo4j"]
        SNAP["DocumentSnapshotStore<br/>Redis"]
        EPS["EventProcessingStore<br/>Redis"]
        STM["RedisShortTermMemoryService"]
    end

    INGEST --> VECTOR
    INGEST --> SNAP
    INGEST --> GRAPH

    QAAGENT --> VECTOR
    QAAGENT --> GRAPH
    MEM --> STM

    KUA --> VECTOR
    KUA --> GRAPH
    KUA --> SNAP
    KUA --> EPS
    REPLAY --> EPS
    REPLAY --> KUA

    subgraph EXT["外部依赖"]
        OPENAI["OpenAI / Spring AI<br/>Chat + Embedding"]
        MILVUS["Milvus"]
        NEO4J["Neo4j"]
        REDIS["Redis"]
        KAFKA["Kafka"]
    end

    EXTRACT --> OPENAI
    QAAGENT --> OPENAI
    VECTOR --> MILVUS
    GRAPH --> NEO4J
    SNAP --> REDIS
    EPS --> REDIS
    STM --> REDIS
    CDC --> KAFKA
```

### 架构说明

- `KnowledgeController` 是同步 API 入口，主要承接文件上传、问答请求、统计查看和事件回放。
- `IngestWorkflowService` 负责文档上传后的入库编排。
- `KnowledgeUpdateAgent` 负责 Kafka CDC 事件驱动的增量更新。
- `QaWorkflowService + QAAgent` 负责问答状态编排、记忆加载、双路检索和答案生成。
- `WorkflowExecutionContext` 和 `WorkflowStepRunner` 是两条 workflow 共用的轻量基础设施。
- `workflowExecutor` 统一承接当前已经接入的 Virtual Threads 业务子任务。

## 2. 文件上传入库流程图

```mermaid
flowchart TD
    A["POST /api/ingest/upload"] --> B["KnowledgeController"]
    B --> C["IngestWorkflowService"]
    C --> D["初始化状态机<br/>RECEIVED"]

    D --> E["DocParserAgent.parse"]
    E --> F["状态 -> PARSED"]

    F --> G["构建 chunkMap / chunk 稳定化"]
    G --> H["状态 -> CHUNKED"]

    H --> I["KnowledgeExtractAgent.extract"]
    I --> J["状态 -> EXTRACTED"]

    J --> K["并发启动"]
    K --> L["vectorStore.addChunks"]
    K --> M["snapshotStore.saveChunks"]

    L --> N["等待 vector 完成"]
    N --> O["状态 -> VECTOR_STORED"]

    M --> P["等待 snapshot 完成"]
    P --> Q["成功: 正常继续"]
    P --> R["失败: 标记 degraded<br/>SNAPSHOT_STORE_UNAVAILABLE"]

    Q --> S["状态 -> SNAPSHOT_STORED"]
    R --> S

    S --> T["writeKnowledgeGraph"]
    T --> U["成功: 写入实体 / 关系"]
    T --> V["失败: 标记 degraded<br/>GRAPH_UNAVAILABLE"]

    U --> W["状态 -> GRAPH_STORED"]
    V --> W

    W --> X["状态 -> COMPLETED"]
    X --> Y["返回结构化结果<br/>workflowState / degraded / retryAttempts"]
```

### 入库流程说明

- 文档上传后先进入入库 workflow，而不是直接顺序调用到底。
- `vectorStore` 和 `snapshotStore` 在执行上是并发启动，但状态顺序按业务优先级先后推进。
- `vectorStore` 是硬依赖，写失败会整体失败。
- `snapshotStore` 和 `knowledgeGraph` 当前允许降级继续，用于保留主链路可用性。
- 接口最终会返回 `workflowState`、`degraded`、`degradeReasons` 和 `retryAttempts` 等结构化字段。

## 3. Kafka 增量更新流程图

```mermaid
flowchart TD
    A["Kafka doc-changes"] --> B["KnowledgeUpdateAgent.handleCDCEvent"]
    B --> C["parseEventMessage<br/>构造 DocumentChangeEvent"]

    C --> D["shouldSkipEvent"]
    D --> D1{"重复 / 旧版本 / 旧时间戳?"}
    D1 -- "是" --> D2["跳过处理"]
    D1 -- "否" --> E["markReceived"]
    E --> F["markProcessing"]

    F --> G{"changeType"}
    G -- "created" --> H["handleCreate"]
    G -- "modified" --> I["handleModify"]
    G -- "deleted" --> J["handleDelete"]

    subgraph MODIFY["modified 处理核心"]
        I --> I1["loadSnapshotOrFallback"]
        I1 --> I2["重新 parse 当前文档"]
        I2 --> I3["ChunkDiff.of<br/>算出 changed / removed / unchanged"]
        I3 --> I4["生成 staleChunkIds"]

        I4 --> I5["并发删除"]
        I5 --> I6["vectorStore.deleteByChunkIds"]
        I5 --> I7["knowledgeGraph.deleteBySourceAndChunkIds"]

        I6 --> I8["重建 changedChunks 向量"]
        I7 --> I8
        I8 --> I9["对 changedChunks 重新抽取并 upsert 图谱"]
        I9 --> I10["persistSnapshot"]
    end

    H --> K["成功后更新版本 / 时间戳缓存"]
    I --> K
    J --> K

    K --> L["markSucceeded"]

    B --> M["异常处理"]
    M --> N["markFailed"]
    N --> O{"异常类型"}
    O -- "NonRetryable" --> P["不重试 / 可进 DLT"]
    O -- "Retryable" --> Q["Spring Kafka 重试"]
    Q --> R["超过次数 -> DLT"]
```

### 增量更新流程说明

- Kafka 消息进入后，第一步不是直接更新，而是先做事件解析和跳过判断。
- `shouldSkipEvent` 同时承担幂等和顺序保护：
  - `eventId` 负责挡重复事件
  - `version` 负责主顺序保护
  - `timestamp` 在缺版本号时兜底
- `modified` 不是整篇删重建，而是基于 snapshot 做 chunk diff，只更新受影响的 chunk。
- 旧向量和旧图谱边会先按 `staleChunkIds` 清理，再写入新的 chunk 数据。
- 当前链路已具备失败状态、DLT 和 replay 能力，但还不是跨多存储事务级一致性闭环。

## 4. QA 问答流程图

```mermaid
flowchart TD
    A["POST /api/qa/ask"] --> B["KnowledgeController"]
    B --> C["QaWorkflowService"]
    C --> D["QUESTION_RECEIVED"]

    D --> E{"有 sessionId?"}
    E -- "否" --> F["跳过 memory 加载"]
    E -- "是" --> G["MemoryManager.loadShortTermContextResult"]

    G --> H{"memory 可用?"}
    H -- "是" --> I["加载 short-term context"]
    H -- "否" --> J["标记 degraded<br/>MEMORY_UNAVAILABLE"]

    F --> K["MEMORY_LOADED"]
    I --> K
    J --> K

    K --> L["QAAgent.answer"]
    L --> M["detectQuestionMode<br/>RELATIONSHIP / DESCRIPTIVE"]

    M --> N["vectorStore.search"]
    M --> O["knowledgeGraph.searchByQuestion"]

    N --> P["hybridRerank"]
    O --> P

    P --> Q["RETRIEVAL_COMPLETED"]
    Q --> R["buildContextText<br/>[MEMORY] [VECTOR_CONTEXTS] [GRAPH_CONTEXTS] [CONFLICT_HINTS]"]
    R --> S["LLM generateAnswer"]
    S --> T["ANSWER_GENERATED"]

    T --> U{"有 sessionId?"}
    U -- "否" --> V["跳过回写"]
    U -- "是" --> W["appendUserMessage / appendAssistantMessage"]

    V --> X["COMPLETED"]
    W --> X
    X --> Y["返回 QAResult<br/>workflowState / degraded / degradeReasons / contexts"]
```

### QA 流程说明

- QA workflow 和入库 workflow 共用基础设施，但有自己独立的状态语义。
- `memory` 加载和回写被明确放在不同阶段，避免把当前轮问题提前写入 prompt 上下文。
- `QAAgent` 里会同时走 vector 和 graph 两路检索，再按问题类型做轻量混排。
- 单路失败当前允许降级继续，只要另一条路还有结果。
- 最终返回的不只是答案，还包括 `workflowState`、`degraded`、`degradeReasons`、`contexts` 等结构化结果。

## 5. 一页总览图

如果只想快速看系统主路径，可以看下面这张总览图：

```mermaid
flowchart LR
    A["文件上传"] --> B["入库 Workflow"]
    C["Kafka CDC"] --> D["增量更新 Workflow"]
    E["用户提问"] --> F["QA Workflow"]

    B --> G["Milvus 向量"]
    B --> H["Redis 快照"]
    B --> I["Neo4j 图谱"]

    D --> G
    D --> H
    D --> I
    D --> J["Redis 事件状态"]

    F --> G
    F --> I
    F --> K["Redis 短期记忆"]
    F --> L["LLM Answer"]

    J --> M["Replay / 排障"]
```

### 总结

当前项目可以概括成一套围绕文档知识入库、增量更新和双路检索问答构建的工程化知识服务：

- 上传链路负责把文档解析、抽取并落到向量、快照和图谱。
- Kafka 链路负责用事件驱动方式做幂等、顺序保护和局部更新。
- QA 链路负责加载短期记忆、做双路检索和结构化降级回答。
- 轻量状态机和统一重试设施负责把这些链路从“能跑”提升到“可解释、可监控、可扩展”。
