# DocGraphAgent Java

Java implementation of a document-centered knowledge base system with:

- document parsing and chunking
- knowledge extraction and knowledge graph writes
- vector retrieval plus graph retrieval QA
- Kafka-driven incremental updates
- Redis short-term memory
- lightweight workflow orchestration with retry and degradation handling
- Java 21 virtual-thread-backed workflow subtask execution

## Tech Stack

- Java 21
- Spring Boot 3
- Spring AI
- Spring Kafka
- Redis
- Milvus
- Neo4j
- Apache Tika
- Maven

## Core Capabilities

### 1. Document Ingest Workflow

The ingest pipeline parses source files into chunks, runs knowledge extraction, stores vectors, persists document snapshots, and writes graph entities and relations.

Workflow states:

- `RECEIVED`
- `PARSED`
- `CHUNKED`
- `EXTRACTED`
- `VECTOR_STORED`
- `SNAPSHOT_STORED`
- `GRAPH_STORED`
- `COMPLETED`

Structured workflow output includes:

- `workflowState`
- `degraded`
- `degradeReasons`
- `retryAttempts`

### 2. Incremental Update Pipeline

Kafka events drive `created`, `modified`, and `deleted` update paths.

For modified documents, the Java version supports:

- event idempotency checks
- version and timestamp based stale-event filtering
- chunk diff based selective rebuild
- vector deletion and graph cleanup for stale chunks
- snapshot persistence for next-round diffing

### 3. Hybrid QA

The QA path combines:

- vector retrieval from Milvus
- graph retrieval from Neo4j
- Redis short-term memory context

The current implementation also supports:

- lightweight question mode classification
- relationship vs descriptive weighting
- single-path degradation fallback
- structured degradation reasons in `QAResult`

### 4. Java 21 Virtual Threads

Virtual threads are wired into workflow subtask execution instead of only being declared at environment level.

Current usage includes:

- concurrent chunk extraction
- parallel vector store and snapshot store tasks during ingest
- parallel vector delete and graph delete tasks during modify events

## Project Structure

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

## Local Dependencies

### Minimal Dev Environment

Start Redis and Milvus:

```bash
docker compose -f docker-compose.dev.yml up -d
```

This is enough for local development of parsing, in-memory/vector-side behavior, and part of the QA path if you do not need Kafka and Neo4j together.

### Full Environment

Start Redis, Milvus, Neo4j, Zookeeper, and Kafka:

```bash
docker compose -f docker-compose.full.yml up -d
```

## Configuration

Main configuration file:

- `src/main/resources/application.yml`

Important settings include:

- Redis host, port, password
- Neo4j URI and credentials
- Kafka bootstrap servers and topic
- Milvus host, port, collection
- workflow retry policy
- execution mode: `virtual` or `platform`
- extract max concurrency

Useful defaults already included:

- workflow retry max attempts: `3`
- workflow retry delay: `200ms`
- execution mode: `virtual`
- extract max concurrency: `8`

## Run

### 1. Build

```bash
mvn clean package
```

### 2. Start Application

```bash
mvn spring-boot:run
```

Default server port:

- `8081`

## API Overview

### Upload Document

`POST /api/ingest/upload`

Form field:

- `file`

Returns ingest result including:

- chunk count
- entity count
- relation count
- workflow state
- degradation info
- retry attempts

### Ask Question

`POST /api/qa/ask`

Example body:

```json
{
  "sessionId": "demo-session",
  "question": "What is the relationship between Redis and the knowledge hub?"
}
```

### View Stats

`GET /api/admin/stats`

### Query Event State

`GET /api/admin/events/{eventId}`

### Replay Failed Event

`POST /api/admin/events/{eventId}/replay`

## Testing

Run all tests:

```bash
mvn test
```

The test suite covers:

- workflow state transitions
- degradation handling
- incremental update behavior
- knowledge graph identity behavior
- memory loading behavior
- execution mode switching
- virtual thread benchmark path

## Resume-Safe Positioning

This repository is best described as:

> A Java intelligent knowledge base system with document ingest, Kafka-driven incremental updates, hybrid vector-plus-graph QA, lightweight workflow orchestration, and Java 21 virtual-thread-backed I/O concurrency.

What it is not yet:

- a full Spring StateMachine based production workflow platform
- a full autonomous multi-agent orchestration framework
- a fully production-hardened operations platform with complete replay console and deep observability stack

## Notes

- Some historical source files still contain legacy encoding artifacts in comments or prompt strings. They do not affect the core runtime path, but they are good cleanup candidates for future refinement.
- If you plan to publish this as a standalone public repository, the next recommended step is to add:
  - `.gitignore`
  - environment variable example file
  - sample curl requests
  - architecture diagram
