# CodeGuard AI - Start the local llama-server
#
# Starts the real local inference runtime that HttpLocalAiInferenceProvider
# talks to. No stub or canned provider is involved: the process below serves a
# real GGUF model over the OpenAI-compatible /v1/chat/completions endpoint.
#
# Usage (PowerShell):
#   .\scripts\start-llama.ps1
#
# Optional overrides:
#   -Model  path to a .gguf file   (default: ..\models\qwen2.5-coder-1.5b-instruct-q4_k_m.gguf)
#   -Port   port to bind            (default: 8081)
#   -Ctx    context size            (default: 2048)

param(
    [string]$Model = '',
    [int]$Port = 8081,
    [int]$Ctx = 2048
)

$ErrorActionPreference = 'Stop'

if (-not $Model) {
    $Model = Join-Path $PSScriptRoot '..\models\qwen2.5-coder-1.5b-instruct-q4_k_m.gguf'
}

if (-not (Test-Path $Model)) {
    throw "Model not found: $Model`nRun .\scripts\fetch-model.ps1 first."
}

# Locate llama-server.exe: prefer the local tools directory used by this repo.
$server = $null
$candidates = @(
    (Join-Path $PSScriptRoot '..\tools\llama\b11226\llama-server.exe'),
    (Get-Command llama-server -ErrorAction SilentlyContinue | ForEach-Object { $_.Source })
)

foreach ($candidate in $candidates) {
    if ($candidate -and (Test-Path $candidate)) {
        $server = $candidate
        break
    }
}

if (-not $server) {
    throw "llama-server.exe not found. Download the pinned release with .\scripts\fetch-llama.ps1"
}

Write-Host "Starting llama-server"
Write-Host "  binary : $server"
Write-Host "  model  : $Model"
Write-Host "  base   : http://127.0.0.1:$Port"
Write-Host ""

# --host 127.0.0.1 keeps inference on the loopback interface only, which
# matches AiProperties.validateLoopbackUrl on the backend side.
& $server `
    --model $Model `
    --alias (Split-Path -Leaf $Model) `
    --host 127.0.0.1 `
    --port $Port `
    --ctx-size $Ctx
