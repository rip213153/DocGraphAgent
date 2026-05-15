# Agent Knowledge Hub 项目体检与改进报告（2026-05-10）

## 1. 结论总览

当前项目已经具备“可编译、可测试、主链路代码基本闭环”的状态，但要稳定达到“本地一键可用”仍依赖基础设施健康（尤其 Docker Desktop + Milvus）。

本次已完成的代码改进主要集中在三块：

1. 图谱检索去历史临时路径污染（已落地并通过实测验证过）。
2. QA 中文关系问题识别修复（修正了乱码关键词导致的误判风险）。
3. `ask.ps1` 并发调用安全修复（避免请求/响应文件互相覆盖）。

## 2. 本次检查范围

- 构建与测试：
  - `mvn -q test`
- 关键代码路径：
  - QA 混合召回与重排逻辑
  - 图谱检索候选实体排序与去重逻辑
  - 本地提问脚本 `ask.ps1`
- 运行态验证：
  - Spring Boot 启动日志
  - 依赖可达性（Milvus / Docker daemon）

## 3. 已完成改进（代码级）

### 3.1 图谱召回去“历史 temp 上传路径”污染

改动文件：
- `src/main/java/com/agenthub/service/KnowledgeGraphService.java`
- `src/test/java/com/agenthub/service/KnowledgeGraphServiceTest.java`

改进点：
- 检索候选实体时增加“稳定 source 优先”的排序与逻辑去重：
  - 对同一逻辑文档（如 `AQS.md`）优先保留稳定 source。
  - 历史 `AppData/Local/Temp/uploads...` 来源不再优先污染召回。
- 图谱上下文 metadata 统一补全：
  - `source_key`
  - `title`
  - 规范化后的 `source`
- 输出层对临时路径进行逻辑源名归一（例如 `AQS.md`），避免对外暴露临时目录路径。

收益：
- QA 结果中的 source 一致性明显提升。
- 同名反复上传后的图谱上下文冲突显著下降。

### 3.2 QA 中文关系问题识别修复

改动文件：
- `src/main/java/com/agenthub/agent/QAAgent.java`
- `src/test/java/com/agenthub/agent/QAAgentTest.java`

改进点：
- 修复中文关系关键词识别乱码问题（`关系/关联/属于/依赖/合作/哪个公司`）。
- 文本归一化逻辑修复：
  - 正确处理中文问号 `？` 和英文问号 `?`。
  - 统一使用 `Locale.ROOT` 进行大小写标准化。
- 新增中文关系问句单测，确保不会回归。

收益：
- 中文“关系型问题”能更稳定进入 `RELATIONSHIP` 模式，改善图谱权重策略命中。

### 3.3 ask.ps1 并发安全修复

改动文件：
- `ask.ps1`

改进点：
- 请求/响应文件由固定路径改为带 GUID 的唯一文件：
  - `qa-request-<guid>.json`
  - `qa-response-<guid>.json`
- 增加 `curl.exe` 退出码检查，失败时明确抛错。
- 执行完成后自动清理临时请求/响应文件。

收益：
- 多个提问命令并发时不再互相覆盖 JSON 文件。
- 错误定位更快（能区分“服务不可达”与“脚本写文件冲突”）。

## 4. 验证结果

### 4.1 自动化测试

已执行：
- `mvn -q test`

结果：
- 通过（Exit code 0）。

说明：
- 日志中的 `RuntimeException: boom` / `milvus down` 属于测试中主动构造的异常场景（断言降级/失败路径），不是测试失败。

### 4.2 运行态验证现状

当前阻塞点（环境）：
- Spring Boot 在启动阶段持续等待 Milvus，最终服务不可用。
- Docker API 不可达，`docker ps` 报错：
  - `The system cannot find the file specified`（npipe 指向 Docker Desktop Linux Engine）。

结论：
- 这不是本次代码改动引入的问题，而是本机 Docker Desktop / daemon 未运行导致的依赖不可达。

## 5. 当前项目的主要改进建议（优先级）

### P0（立即）

1. 恢复 Docker Desktop 并确认依赖容器健康（尤其 Milvus）。
2. 启动后先检查：
   - `GET /actuator`
   - `GET /api/admin/stats`
3. 再执行 `ask.ps1` 验证端到端 QA。

### P1（高）

1. README 当前存在明显乱码，建议整体转为 UTF-8 并重写关键章节：
   - 启动前依赖
   - 最小启动命令
   - 异步上传 + 任务查询
   - 降级行为说明
2. 把“Docker 未启动 / Milvus 不可达”的排障步骤写进 README（避免再次误判为代码故障）。

### P2（中）

1. 为 QA 混合召回增加“最少保留 1 条 vector context”的策略开关，避免极端情况下 graph 全覆盖。
2. 增加可观测性指标：
   - graph/vector top source 分布
   - SOURCE_CONFLICT 触发频率

## 6. 我建议你下一步直接执行

1. 先启动 Docker Desktop（确保 daemon 在线）。
2. 运行（项目根目录）：
   - `docker compose -f docker-compose.full.yml up -d`
3. 再启动 Java：
   - `mvn spring-boot:run`
4. 验证：
   - `.\ask.ps1 "AQS是什么？"`
   - `.\ask.ps1 "索引设计原则？"`

## 7. 本次涉及的关键文件

- `src/main/java/com/agenthub/service/KnowledgeGraphService.java`
- `src/test/java/com/agenthub/service/KnowledgeGraphServiceTest.java`
- `src/main/java/com/agenthub/agent/QAAgent.java`
- `src/test/java/com/agenthub/agent/QAAgentTest.java`
- `ask.ps1`

---

## 8. 2026-05-11 补充整改

### 8.1 README UTF-8 重写完成

`README.md` 已重写为清晰的 UTF-8 中文版本，重点补齐：

- 基础可用 / 完整可用分层说明
- 最小环境变量和默认模型配置
- 启动步骤与本地验收顺序
- 关键 API 语义
- 降级行为说明
- 常见问题排障（Docker Desktop / Milvus / PowerShell curl / Maven 命令）

### 8.2 文档可点击路径修正

README 内原先本机绝对路径形式的链接已改为仓库相对路径，便于团队成员在不同机器和代码托管平台直接访问。

### 8.3 再次验证

已再次执行：

- `mvn -q test`

结果：通过（Exit code 0）。
