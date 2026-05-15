# Agent Knowledge Hub Java

基于 Java 21 + Spring Boot 3 的知识库服务，支持文档异步入库、向量检索问答、知识图谱检索和 Kafka 增量更新。当前默认模型对齐 DashScope 兼容 OpenAI 接口。

## 可用分层

### 基础可用（建议先跑通）

只依赖以下组件：

- Redis
- Milvus（含 etcd + MinIO）
- DashScope 兼容模型接口

可用能力：

- `POST /api/ingest/upload` 异步上传入库
- `GET /api/ingest/tasks/{taskId}` 任务状态查询
- `POST /api/qa/ask` 向量检索问答
- 图谱不可用时自动降级运行

### 完整可用

在基础可用之外增加：

- Neo4j
- Kafka
- Zookeeper

额外能力：

- 图谱写入与图谱检索增强
- Kafka 驱动的文档增量更新
- 事件状态回放链路

## 环境要求

- Java 21
- Maven 3.9+
- Docker Desktop（必须已启动）

## 最小配置

复制 [`.env.example`](./.env.example) 并设置至少以下变量：

```bash
OPENAI_API_KEY=your-dashscope-key
OPENAI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL=qwen3.5-397b-a17b
SPRING_AI_OPENAI_EMBEDDING_OPTIONS_MODEL=text-embedding-v3
VECTOR_DIMENSION=1024
MILVUS_COLLECTION=knowledge_chunks_qwen1024
SPRING_KAFKA_LISTENER_AUTO_STARTUP=false
EXTRACT_MAX_CONCURRENCY=1
```

默认配置文件见 [`src/main/resources/application.yml`](./src/main/resources/application.yml)。

## 启动步骤

### 1. 启动基础依赖（推荐先用它验收）

```bash
docker compose -f docker-compose.dev.yml up -d
```

### 2. 启动完整依赖（需要图谱和增量链路时）

```bash
docker compose -f docker-compose.full.yml up -d
```

### 3. 启动应用

```bash
mvn spring-boot:run
```

默认端口：`8081`

## 验收顺序（本地）

1. 检查服务状态：

```bash
curl http://localhost:8081/actuator
```

2. 检查依赖就绪：

```bash
curl http://localhost:8081/api/admin/stats
```

看到 `vectorStore.status=ready` 再进行上传和问答。

3. 上传文档（PowerShell 建议用 `curl.exe`）：

```powershell
curl.exe -X POST "http://localhost:8081/api/ingest/upload" -F "file=@C:\path\to\AQS.md"
```

返回 `202 Accepted + taskId`。

4. 查询任务状态：

```bash
curl "http://localhost:8081/api/ingest/tasks/<taskId>"
```

状态最终应为 `SUCCEEDED`。

5. 问答验证：

```powershell
.\ask.ps1 "AQS是什么？"
```

## 核心接口

### 异步上传

- `POST /api/ingest/upload`
- 返回：`202 Accepted`，包含 `taskId`

### 任务查询

- `GET /api/ingest/tasks/{taskId}`
- 关键阶段：
  - `QUEUED`
  - `PROCESSING`
  - `PARSED`
  - `CHUNKED`
  - `EXTRACTING`
  - `EXTRACTED`
  - `VECTOR_STORED`
  - `SNAPSHOT_STORED`
  - `GRAPH_STORED`
  - `COMPLETED`
  - `FAILED`

### 问答

- `POST /api/qa/ask`
- 请求示例：

```json
{
  "sessionId": "demo-session",
  "question": "Milvus 在这个项目里负责什么？"
}
```

### 状态

- `GET /api/admin/stats`
- 重点字段：
  - `vectorStore.backend`
  - `vectorStore.collection`
  - `vectorStore.retrievalMode`
  - `vectorStore.status`
  - `knowledgeGraph.totalEntities`

## 降级行为

- Neo4j 不可用：上传和 QA 仍可运行，但返回 `degraded=true` 且包含 `GRAPH_UNAVAILABLE`。
- Snapshot 存储失败：主链路不中断，返回 `SNAPSHOT_STORE_UNAVAILABLE`。
- 向量存储不可用：上传任务失败（基础检索能力无法建立）。

## 常见问题排障

### 1) `docker ps` 报错无法连接 Docker API

典型报错：

`failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine`

处理步骤：

1. 启动 Docker Desktop。
2. 等待 Docker Engine 变为 Running。
3. 重新执行：

```bash
docker info
docker ps
```

### 2) Spring Boot 一直提示 Milvus not ready / deadline exceeded

典型日志：

`Milvus not ready on startup attempt x/12: DEADLINE_EXCEEDED`

处理步骤：

1. 先确认 Docker 可用（见问题 1）。
2. 检查 Milvus 相关容器状态：

```bash
docker ps --format "table {{.Names}}\t{{.Status}}"
```

3. 必要时重建依赖：

```bash
docker compose -f docker-compose.full.yml up -d
```

4. 再启动应用：

```bash
mvn spring-boot:run
```

### 3) PowerShell 下 `curl -X` 报参数错误

原因：PowerShell 中 `curl` 默认映射到 `Invoke-WebRequest`，不支持 `-X/-F` 的 cURL 参数风格。

处理：

- 始终使用 `curl.exe`：

```powershell
curl.exe -X POST "http://localhost:8081/api/ingest/upload" -F "file=@C:\path\to\doc.md"
```

### 4) Maven 报 `No plugin found for prefix 'springboot'`

原因：命令写成了 `springboot:run`（缺少中划线）。

正确命令：

```bash
mvn spring-boot:run
```

## 测试

编译：

```bash
mvn -q -DskipTests compile
```

测试：

```bash
mvn -q test
```

## 相关文档

- 系统架构（中文）：[`docs/architecture-zh.md`](./docs/architecture-zh.md)
- 体检与整改报告：[`docs/project-audit-and-improvements-2026-05-10.md`](./docs/project-audit-and-improvements-2026-05-10.md)
