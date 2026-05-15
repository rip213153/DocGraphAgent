param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$FilePath,

    [Parameter(Position = 1)]
    [string]$Question = "What is AQS?",

    [Parameter(Position = 2)]
    [string]$BaseUrl = "http://localhost:8081",

    [Parameter(Position = 3)]
    [string]$SessionId = "e2e-session",

    [Parameter(Position = 4)]
    [int]$PollIntervalSeconds = 3,

    [Parameter(Position = 5)]
    [int]$PollTimeoutSeconds = 240,

    [Parameter(Position = 6)]
    [string]$ReplayEventId = "",

    [switch]$RequireReplay,
    [switch]$RequireActuatorMetrics
)

$ErrorActionPreference = "Stop"

function Invoke-GetJson {
    param([Parameter(Mandatory = $true)][string]$Url)
    return Invoke-RestMethod -Method Get -Uri $Url
}

function Invoke-PostJson {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$Body
    )
    return Invoke-RestMethod -Method Post -Uri $Url -ContentType "application/json; charset=utf-8" -Body $Body
}

function Invoke-CurlUpload {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$UploadPath
    )
    $curlArgs = @(
        "-sS",
        "-X", "POST",
        $Url,
        "-F", "file=@$UploadPath"
    )
    $raw = & curl.exe @curlArgs
    if ($LASTEXITCODE -ne 0) {
        throw "curl.exe upload failed with exit code $LASTEXITCODE"
    }
    if ([string]::IsNullOrWhiteSpace($raw)) {
        throw "Upload response is empty."
    }
    return ($raw | ConvertFrom-Json)
}

function Poll-IngestTask {
    param(
        [Parameter(Mandatory = $true)][string]$BaseUrl,
        [Parameter(Mandatory = $true)][string]$TaskId,
        [Parameter(Mandatory = $true)][int]$PollIntervalSeconds,
        [Parameter(Mandatory = $true)][int]$PollTimeoutSeconds
    )

    $deadline = (Get-Date).AddSeconds($PollTimeoutSeconds)
    do {
        $task = Invoke-GetJson -Url "$BaseUrl/api/ingest/tasks/$TaskId"
        $status = [string]$task.status
        $stage = [string]$task.currentStage
        Write-Host ("Task={0} status={1} stage={2} chunks={3}/{4}" -f $TaskId, $status, $stage, $task.chunksProcessed, $task.chunksTotal)

        if ($status -eq "SUCCEEDED" -or $status -eq "FAILED") {
            return $task
        }

        Start-Sleep -Seconds $PollIntervalSeconds
    } while ((Get-Date) -lt $deadline)

    throw "Task polling timed out after $PollTimeoutSeconds seconds. taskId=$TaskId"
}

if (-not (Test-Path -LiteralPath $FilePath)) {
    throw "Input file not found: $FilePath"
}

$resolvedFilePath = (Resolve-Path -LiteralPath $FilePath).Path
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$askScript = Join-Path $scriptRoot "ask.ps1"
if (-not (Test-Path -LiteralPath $askScript)) {
    throw "ask.ps1 not found near script: $askScript"
}

Write-Host "[1/6] Health check"
$health = Invoke-GetJson -Url "$BaseUrl/api/health"
if ($health.status -ne "ok") {
    throw "Health check failed: status=$($health.status)"
}

Write-Host "[2/6] Upload document"
$upload = Invoke-CurlUpload -Url "$BaseUrl/api/ingest/upload" -UploadPath $resolvedFilePath
if ([string]::IsNullOrWhiteSpace([string]$upload.taskId)) {
    throw "Upload did not return taskId. response=$($upload | ConvertTo-Json -Depth 8)"
}
$taskId = [string]$upload.taskId

Write-Host "[3/6] Poll ingest task"
$taskResult = Poll-IngestTask -BaseUrl $BaseUrl -TaskId $taskId -PollIntervalSeconds $PollIntervalSeconds -PollTimeoutSeconds $PollTimeoutSeconds
if ([string]$taskResult.status -ne "SUCCEEDED") {
    $failure = [string]$taskResult.failureReason
    throw "Ingest task failed. taskId=$taskId reason=$failure"
}

Write-Host "[4/6] Ask QA"
$qaRaw = & $askScript $Question $SessionId $BaseUrl
if ($LASTEXITCODE -ne 0) {
    throw "ask.ps1 failed with exit code $LASTEXITCODE"
}
$qaResult = $qaRaw | ConvertFrom-Json
if ([string]::IsNullOrWhiteSpace([string]$qaResult.answer)) {
    throw "QA returned empty answer."
}
Write-Host ("QA confidence={0}, degraded={1}" -f $qaResult.confidence, $qaResult.degraded)

$eventStatus = "SKIPPED"
$replayStatus = "SKIPPED"
if (-not [string]::IsNullOrWhiteSpace($ReplayEventId)) {
    Write-Host "[5/6] Event detail + replay"
    $event = Invoke-GetJson -Url "$BaseUrl/api/admin/events/$ReplayEventId"
    if ([string]$event.eventId -ne $ReplayEventId) {
        throw "Event query mismatch. expected=$ReplayEventId actual=$($event.eventId)"
    }
    $eventStatus = [string]$event.status

    $replay = Invoke-PostJson -Url "$BaseUrl/api/admin/events/$ReplayEventId/replay" -Body "{}"
    if ([string]$replay.status -ne "replayed") {
        throw "Replay did not return replayed status. response=$($replay | ConvertTo-Json -Depth 8)"
    }
    $replayStatus = [string]$replay.status
} elseif ($RequireReplay) {
    throw "Replay is required but ReplayEventId was not provided."
} else {
    Write-Host "[5/6] Event replay skipped (ReplayEventId not provided)"
}

Write-Host "[6/6] Metrics sampling"
$metricNames = @()
try {
    $metricsIndex = Invoke-GetJson -Url "$BaseUrl/actuator/metrics"
    if ($metricsIndex.names) {
        $metricNames = @($metricsIndex.names | Where-Object { $_ -like "agenthub.*" } | Sort-Object)
    }

    if ($metricNames.Count -gt 0) {
        Write-Host ("Detected {0} agenthub metrics." -f $metricNames.Count)
    } else {
        Write-Warning "Actuator metrics endpoint is reachable, but no agenthub.* metrics were listed."
    }
} catch {
    if ($RequireActuatorMetrics) {
        throw "Actuator metrics endpoint is unavailable, but metrics are required. $_"
    }
    Write-Warning "Metrics sampling skipped: actuator endpoint unavailable."
}

$summary = [ordered]@{
    timestampUtc = [DateTime]::UtcNow.ToString("o")
    baseUrl = $BaseUrl
    filePath = $resolvedFilePath
    taskId = $taskId
    taskStatus = [string]$taskResult.status
    chunksTotal = [int]$taskResult.chunksTotal
    chunksProcessed = [int]$taskResult.chunksProcessed
    qaQuestion = $Question
    qaConfidence = $qaResult.confidence
    qaDegraded = $qaResult.degraded
    replayEventId = if ([string]::IsNullOrWhiteSpace($ReplayEventId)) { $null } else { $ReplayEventId }
    replayEventStatus = $eventStatus
    replayStatus = $replayStatus
    metricCount = $metricNames.Count
    metricNames = $metricNames
}

Write-Host ""
Write-Host "E2E regression succeeded."
$summary | ConvertTo-Json -Depth 10
