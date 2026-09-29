# CodeGuard AI - Local GGUF model setup
#
# Downloads the pinned demo model used by the real local AI path:
#
#   React -> Spring Boot -> HttpLocalAiInferenceProvider
#         -> localhost llama-server -> this GGUF model
#
# The model is NOT committed to the repository and is NOT downloaded
# automatically at application startup. Run this script once, explicitly.
#
# Usage (PowerShell):
#   .\scripts\fetch-model.ps1
#
# The model is placed in .\models\ which is the default model directory
# expected by .\scripts\start-llama.ps1.

$ErrorActionPreference = 'Stop'

$ModelDir   = Join-Path $PSScriptRoot '..\models'
$ModelFile  = 'qwen2.5-coder-1.5b-instruct-q4_k_m.gguf'
$ModelPath  = Join-Path $ModelDir $ModelFile
$ModelUrl   = 'https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/' + $ModelFile

# Quantization of the pinned artifact (recorded for reproducibility).
$ModelQuant = 'Q4_K_M'

# SHA-256 of the pinned artifact, recorded on 2026-09-28 from the file this
# project was verified against. Upstream revisions can change this value, so a
# mismatch is reported as a warning rather than a hard failure.
$ExpectedSha = 'CC324AF070C2ECBFD324A30884D2F951A7FF756ABA85CB811A6EC436933BB046'
$TargetBytes = 1117320768

if (-not (Test-Path $ModelDir)) {
    New-Item -ItemType Directory -Path $ModelDir -Force | Out-Null
}

if (Test-Path $ModelPath) {
    $sizeMB = [math]::Round((Get-Item $ModelPath).Length / 1MB, 1)
    Write-Host "Model already present: $ModelPath ($sizeMB MB)"
    Write-Host "Delete it and re-run this script to force a fresh download."
    exit 0
}

Write-Host "Downloading CodeGuard AI demo model"
Write-Host "  file  : $ModelFile"
Write-Host "  quant : $ModelQuant"
Write-Host "  source: $ModelUrl"
Write-Host "  size  : approx 1040 MB"
Write-Host ""

# curl.exe is used because it resumes partial downloads and retries reliably.
$attempt = 0

while ($attempt -lt 5) {
    $attempt++

    $current = 0
    if (Test-Path $ModelPath) {
        $current = (Get-Item $ModelPath).Length
    }

    if ($current -ge $TargetBytes) {
        break
    }

    Write-Host "Attempt $attempt : have $([math]::Round($current / 1MB, 1)) MB"

    & curl.exe -L -C - --retry 5 --retry-delay 5 -s -o $ModelPath $ModelUrl

    Start-Sleep -Seconds 2
}

if (-not (Test-Path $ModelPath)) {
    throw "Model download failed: $ModelPath was not created."
}

$sizeMB = [math]::Round((Get-Item $ModelPath).Length / 1MB, 1)

if ((Get-Item $ModelPath).Length -lt $TargetBytes) {
    throw "Model download is incomplete ($sizeMB MB). Re-run this script to resume."
}

$actualSha = (Get-FileHash $ModelPath -Algorithm SHA256).Hash

Write-Host ""
Write-Host "  sha256: $actualSha"

if ($actualSha -ne $ExpectedSha) {
    Write-Host ""
    Write-Host "WARNING: SHA-256 does not match the value recorded on 2026-09-28."
    Write-Host "  expected: $ExpectedSha"
    Write-Host "  actual  : $actualSha"
    Write-Host "The upstream repository may have been updated. Verify the source"
    Write-Host "before trusting this artifact."
}

Write-Host ""
Write-Host "Model ready: $ModelPath ($sizeMB MB, $ModelQuant)"
Write-Host ""
Write-Host "Start the local inference server with:"
Write-Host "  .\scripts\start-llama.ps1"
