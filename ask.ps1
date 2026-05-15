param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Question,

    [Parameter(Position = 1)]
    [string]$SessionId = "demo-session",

    [Parameter(Position = 2)]
    [string]$BaseUrl = "http://localhost:8081"
)

$ErrorActionPreference = "Stop"

$targetDir = Join-Path $PSScriptRoot "target"
if (-not (Test-Path $targetDir)) {
    New-Item -ItemType Directory -Path $targetDir | Out-Null
}

$requestId = [guid]::NewGuid().ToString("N")
$requestPath = Join-Path $targetDir ("qa-request-{0}.json" -f $requestId)
$responsePath = Join-Path $targetDir ("qa-response-{0}.json" -f $requestId)

$payload = @{
    sessionId = $SessionId
    question  = $Question
} | ConvertTo-Json -Compress

[System.IO.File]::WriteAllText(
    $requestPath,
    $payload,
    [System.Text.UTF8Encoding]::new($false)
)

$curlArgs = @(
    "-X", "POST",
    "$BaseUrl/api/qa/ask",
    "-H", "Content-Type: application/json; charset=utf-8",
    "--data-binary", "@$requestPath",
    "-o", $responsePath
)

& curl.exe @curlArgs

if ($LASTEXITCODE -ne 0) {
    throw "curl.exe failed with exit code $LASTEXITCODE"
}

try {
    Get-Content $responsePath -Encoding UTF8
} finally {
    Remove-Item -LiteralPath $requestPath, $responsePath -ErrorAction SilentlyContinue
}
