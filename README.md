# DocGraphAgent Java版

这是一个基于 Java 21 和 Spring Boot 构建的智能知识库系统，核心能力包括：

- 文档解析与切分
- 知识抽取与知识图谱写入
- 向量检索 + 图谱检索的混合问答
- 基于 Kafka 的增量更新
- Redis 短期记忆
- 轻量状态机工作流编排
- 基于 Java 21 Virtual Threads 的 I/O 型并发优化

## 一、项目定位

本项目更适合定义为一个**文档驱动的智能知识库问答系统**，而不是复杂的自治多 Agent 平台。

当前 Java 版重点解决的是：

- 文档入库链路打通
- Kafka 事件驱动的知识更新
- 知识图谱与向量检索融合问答
- 工作流状态、重试、降级的工程化表达
- Java 21 虚拟线程在业务子任务中的真实接入

## 二、技术栈

- Java 21
- Spring Boot 3
- Spring AI
- Spring Kafka
- Redis
- Milvus
- Neo4j
- Apache Tika
- Maven

## 三、核心能力

### 1. 文档入库工作流

文档入库链路支持：

- 解析源文档
- 文本切分为 chunk
- 对 chunk 执行知识抽取
- 向量写入
- 文档快照持久化
- 图谱实体与关系写入

当前入库工作流采用轻量状态机方式编排，状态包括：

- `RECEIVED`
- `PARSED`
- `CHUNKED`
- `EXTRACTED`
- `VECTOR_STORED`
- `SNAPSHOT_STORED`
- `GRAPH_STORED`
- `COMPLETED`

接口返回中已经结构化输出：

- `workflowState`
- `degraded`
- `degradeReasons`
- `retryAttempts`

### 2. Kafka 增量更新

系统支持基于 Kafka 的文档变更事件处理，覆盖：

- `created`
- `modified`
- `deleted`

在 `modified` 场景下，当前实现支持：

- 事件幂等校验
- 版本 / 时间戳顺序保护
- chunk diff 差异识别
- stale chunk 的向量删除与图谱清理
- 新版本快照保存，供后续 diff 使用

### 3. 混合问答

问答链路融合了三类上下文：

- Redis 短期记忆
- Milvus 向量检索结果
- Neo4j 图谱检索结果

当前 Java 版已实现：

- 关系型问题与描述型问题的轻量分类
- 双路检索的加权混排
- 单路失败时的降级兜底
- `QAResult` 中输出结构化降级信息

### 4. Java 21 Virtual Threads

本项目不是只在环境上使用 Java 21，而是把 Virtual Threads 真实接入到了业务执行路径中。

当前主要用于：

- chunk 级知识抽取的并发执行
- 入库阶段向量写入与快照写入的并行执行
- 文档修改时向量删除与图谱删除的并行执行

## 四、项目结构

```text
.
├─ pom.xml
├─ docker-compose.dev.yml
├─ docker-compose.full.yml
└─ src
   ├─ main
   │  ├─ java/com/agenthub
   │  │  ├─ agent
   │  │  ├─ config
   │  │  ├─ controller
   │  │  ├─ memory
   │  │  ├─ model
   │  │  ├─ service
   │  │  └─ workflow
   │  └─ resources/application.yml
   └─ test
```

## 五、本地依赖

### 1. 开发环境

启动 Redis 和 Milvus：

```bash
docker compose -f docker-compose.dev.yml up -d
```

适合做以下场景的本地开发与调试：

- 文档解析
- chunk 抽取
- 向量侧逻辑验证
- 部分问答链路验证

### 2. 完整环境

启动 Redis、Milvus、Neo4j、Zookeeper、Kafka：

```bash
docker compose -f docker-compose.full.yml up -d
```

适合联调以下完整链路：

- 文档入库
- 图谱写入
- Kafka 增量更新
- 图谱 + 向量混合问答

## 六、配置说明

核心配置文件：

- `src/main/resources/application.yml`

重点配置项包括：

- Redis 地址、端口、密码
- Neo4j URI 与账号密码
- Kafka 地址与 topic
- Milvus 地址、端口、collection
- 工作流重试策略
- 执行模式：`virtual` 或 `platform`
- chunk 抽取最大并发数

当前默认配置中比较关键的值有：

- 工作流最大尝试次数：`3`
- 重试间隔：`200ms`
- 执行模式：`virtual`
- 抽取最大并发：`8`

## 七、启动方式

### 1. 编译

```bash
mvn clean package
```

### 2. 启动服务

```bash
mvn spring-boot:run
```

默认端口：

- `8081`

## 八、接口说明

### 1. 上传文档

`POST /api/ingest/upload`

表单参数：

- `file`

返回结果中包含：

- chunk 数量
- 实体数量
- 关系数量
- 工作流状态
- 降级信息
- 重试次数

### 2. 提问问答

`POST /api/qa/ask`

请求示例：

```json
{
  "sessionId": "demo-session",
  "question": "Redis 和知识库系统之间是什么关系？"
}
```

### 3. 查看统计信息

`GET /api/admin/stats`

### 4. 查询事件状态

`GET /api/admin/events/{eventId}`

### 5. 重放失败事件

`POST /api/admin/events/{eventId}/replay`

## 九、测试

运行全部测试：

```bash
mvn test
```

当前测试覆盖的重点包括：

- 工作流状态迁移
- 降级处理
- 增量更新逻辑
- 图谱实体身份构造
- 短期记忆加载行为
- 执行模式切换
- Virtual Threads 基准路径

## 十、适合在简历中的表述

这个仓库当前最适合这样描述：

> 一个基于 Java 的智能知识库系统，支持文档入库、Kafka 增量更新、知识图谱与向量检索融合问答、轻量状态机工作流编排，以及 Java 21 Virtual Threads 的 I/O 型任务并发优化。

## 十一、当前边界

当前版本已经具备比较完整的主链路，但还不建议夸大为：

- 完整的 Spring StateMachine 工作流平台
- 完整自治式多 Agent 调度框架
- 完整生产级运维与补偿平台

更准确的说法是：

- 主链路已跑通
- 可靠性和状态表达已补一层
- 已具备从 demo 走向工程化版本的骨架

## 十二、后续建议

如果后续准备继续把这个仓库做成更完整的独立项目，建议下一步补：

- `.gitignore`
- `.env.example`
- curl 调用示例
- 架构图
- 更完整的 README 部署说明
- 编码乱码清理
