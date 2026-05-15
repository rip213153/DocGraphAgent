# 集成测试与端到端回归自动化落地说明（2026-05-11）

## 1. 本次目标

将上一轮建议真正落地为可执行资产：

1. Testcontainers 集成测试通路（与单测解耦）
2. 一键端到端回归脚本（upload -> polling -> qa -> replay -> metrics）

## 2. 代码改动

## 2.1 Maven 测试分层

文件：

- `pom.xml`

新增内容：

- `testcontainers.version`、`skipITs` 属性
- Testcontainers 依赖：
  - `org.testcontainers:testcontainers`
  - `org.testcontainers:junit-jupiter`
- Surefire 排除 `*IT.java`（避免集成测试混入常规单测）
- Failsafe 执行 `*IT.java`
- 新增 `it` profile（`-Pit` 启用时执行集成测试）

使用方式：

- 常规单测：`mvn -q test`
- 含集成测试：`mvn -q -Pit verify`

## 2.2 Redis Testcontainers 集成测试

文件：

- `src/test/java/com/agenthub/integration/RedisStoresIT.java`

覆盖点：

1. `RedisIngestTaskStore`
   - 持久化任务快照
   - 重启恢复：`PROCESSING -> FAILED` 纠偏逻辑
2. `RedisEventProcessingStore`
   - 事件状态持久化（`RECEIVED`、`SUCCEEDED`、`FAILED`）
   - 最新 `timestamp/version` 持久化读取
   - `failureReason` 持久化

实现细节：

- 使用 `redis:7.2-alpine` 容器
- 使用 `@Testcontainers(disabledWithoutDocker = true)`，无 Docker 时自动 skip

## 2.3 端到端回归脚本

文件：

- `e2e-regression.ps1`

流程：

1. `GET /api/health`
2. `POST /api/ingest/upload`
3. 轮询 `GET /api/ingest/tasks/{taskId}` 直到 `SUCCEEDED/FAILED`
4. 调用 `ask.ps1` 发起 QA
5. 可选：`GET /api/admin/events/{eventId}` + `POST /replay`
6. 采样 `GET /actuator/metrics`

参数要点：

- `ReplayEventId`：传入则执行 replay 验证
- `-RequireReplay`：强制 replay（未提供 `ReplayEventId` 则失败）
- `-RequireActuatorMetrics`：强制 actuator metrics 可达（不可达则失败）

示例：

```powershell
.\e2e-regression.ps1 "C:\path\to\AQS.md" "AQS是什么？"
```

```powershell
.\e2e-regression.ps1 "C:\path\to\AQS.md" "AQS是什么？" "http://localhost:8081" "demo-session" 3 240 "evt-123"
```

## 2.4 Metrics 端点暴露

文件：

- `src/main/resources/application.yml`

新增：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
```

目的：

- 支持 e2e 脚本执行 metrics 采样
- 降低“脚本可跑但指标端点 404”的使用门槛

## 2.5 文档补充

文件：

- `docs/smoke-test.md`

补充内容：

- 单测命令
- Testcontainers 集成测试命令
- e2e 脚本用法与参数示例

## 3. 执行验证结果

## 3.1 单元测试回归

命令：

```powershell
mvn -q test
```

结果：

- 通过（退出码 `0`）

## 3.2 集成测试通路验证

命令：

```powershell
mvn -q -Pit verify
```

结果：

- 构建通过（退出码 `0`）
- Failsafe 报告：
  - `completed=3`
  - `errors=0`
  - `failures=0`
  - `skipped=3`

说明：

- 当前执行环境未检测到可用 Docker，因此 `RedisStoresIT` 3 个用例被自动跳过（符合 `disabledWithoutDocker=true` 设计）。
- 在本机 Docker Desktop 可用时，这 3 个测试会真实拉起 Redis 容器并执行断言。

## 4. 你现在可以直接用的命令

```powershell
mvn -q test
```

```powershell
mvn -q -Pit verify
```

```powershell
.\e2e-regression.ps1 "C:\path\to\your.md" "你的问题是什么？"
```

## 5. 后续建议

1. 在你本机 Docker Desktop 环境下再跑一次 `mvn -q -Pit verify`，确认 Redis 集成测试由 skipped 转为 executed。
2. 给 `e2e-regression.ps1` 增加 CI 入口（例如 GitHub Actions 的手动 workflow），用于回归验收。
3. 如果需要完全覆盖 replay 自动化，可新增“事件列表查询接口”或“测试专用事件注入接口”，避免手工传 `ReplayEventId`。

## 6. 本次实跑记录（2026-05-11）

执行过程摘要：

1. 初次启动 Java 失败，日志显示 `Milvus did not become ready after 12 startup attempts`。
2. 检查到 Docker daemon 未启动，启动 Docker Desktop 后执行：
   - `docker compose -f docker-compose.dev.yml up -d`
3. 待 `agenthub-milvus` healthy 后重启 Java 服务并通过 health 检查。
4. 执行脚本：

```powershell
powershell -ExecutionPolicy Bypass -File .\e2e-regression.ps1 "E:\code\agent-knowledge-hub\java-export\target\e2e-sample.md" "AQS是什么？" "http://localhost:8081" "e2e-session" 3 300 "" -RequireActuatorMetrics
```

脚本结果：

- `E2E regression succeeded`
- `taskId = ingest-944037d7-ef48-4944-8735-5ec45987bc7d`
- `taskStatus = SUCCEEDED`
- `chunksTotal/chunksProcessed = 1/1`
- `qaDegraded = true`（本次 QA 返回降级）
- `replay = SKIPPED`（未传 `ReplayEventId`）
- 采样到 metrics：
  - `agenthub.qa.degraded`
  - `agenthub.qa.top.contexts`
  - `agenthub.qa.vector.results`
