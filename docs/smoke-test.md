# Agent Knowledge Hub Smoke and Regression Test

## 1. Prerequisites

- Spring Boot service is running at `http://localhost:8081`
- Core dependencies are up (at least Redis + Milvus for ingest/qa)
- `curl.exe` is available in PATH

## 2. Unit Test Regression

Run the fast unit-test suite:

```powershell
mvn -q test
```

## 3. Testcontainers Integration Test

This project provides Redis-backed integration tests with Testcontainers.

Run integration tests:

```powershell
mvn -q -Pit verify
```

Notes:

- Integration tests use `redis:7.2-alpine` container.
- If Docker is unavailable, Testcontainers tests are skipped automatically.

## 4. End-to-End Regression Script

Script:

- `e2e-regression.ps1`

What it validates:

1. health check
2. upload (`/api/ingest/upload`)
3. task polling (`/api/ingest/tasks/{taskId}`)
4. qa (`/api/qa/ask`, reusing `ask.ps1`)
5. optional event replay (`/api/admin/events/{eventId}` + `/replay`)
6. metrics sampling (`/actuator/metrics`)

Example:

```powershell
.\e2e-regression.ps1 "C:\path\to\AQS.md" "AQS是什么？"
```

Replay-enabled example:

```powershell
.\e2e-regression.ps1 "C:\path\to\AQS.md" "AQS是什么？" "http://localhost:8081" "demo-session" 3 240 "evt-123"
```

Strict metrics example (fail when actuator metrics is unavailable):

```powershell
.\e2e-regression.ps1 "C:\path\to\AQS.md" "AQS是什么？" "http://localhost:8081" "demo-session" 3 240 "" -RequireActuatorMetrics
```
