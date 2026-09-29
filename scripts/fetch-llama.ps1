# CodeGuard AI - Download the pinned llama.cpp runtime
#
# Downloads the exact llama.cpp build this project was verified against and
# extracts it to .\tools\llama\b11226\.
#
# The runtime is NOT committed and is NOT downloaded at application startup.
#
# Usage (PowerShell):
#   .\scripts\fetch-llama.ps1
#
# Verified artifact (llama.cpp release b11226):
#   asset : llama-b11226-bin-win-cpu-x64.zip
#   size  : 18.27 MB
#   sha256: F0C4E2C65A07FFB47136D7703549912BEC908FA641A26D2C2D6AA01E7ED48E87

$ErrorActionPreference = 'Stop'

$Tag        = 'b11226'
$AssetName  = "llama-$Tag-bin-win-cpu-x64.zip"
$ExpectedSha = 'F0C4E2C65A07FFB47136D7703549912BEC908FA641A26D2C2D6AA01E7ED48E87'
$ExpectedSize = 19156332

$ToolsDir   = Join-Path $PSScriptRoot '..\tools\llama'
$ZipPath    = Join-Path $ToolsDir $AssetName
$ExtractDir = Join-Path $ToolsDir $Tag
$ServerExe  = Join-Path $ExtractDir 'llama-server.exe'

$Url = "https://github.com/ggml-org/llama.cpp/releases/download/$Tag/$AssetName"

if (Test-Path $ServerExe) {
    Write-Host "llama-server already present: $ServerExe"
    exit 0
}

if (-not (Test-Path $ToolsDir)) {
    New-Item -ItemType Directory -Path $ToolsDir -Force | Out-Null
}

if (Test-Path $ZipPath) {
    Remove-Item $ZipPath -Force
}

Write-Host "Downloading llama.cpp runtime"
Write-Host "  release: $Tag"
Write-Host "  asset  : $AssetName"
Write-Host "  source : $Url"
Write-Host ""

& curl.exe -L --retry 5 --retry-delay 5 -s -o $ZipPath $Url

if (-not (Test-Path $ZipPath)) {
    throw "Download failed: $ZipPath was not created."
}

$actualSize = (Get-Item $ZipPath).Length
Write-Host "Verifying checksum..."

$actualSha = (Get-FileHash $ZipPath -Algorithm SHA256).Hash

if ($actualSha -ne $ExpectedSha) {
    throw "SHA-256 mismatch for $AssetName`n  expected: $ExpectedSha`n  actual  : $actualSha"
}

Write-Host "  sha256: $actualSha  OK"
Write-Host "  size  : $actualSize bytes  OK"

Write-Host ""
Write-Host "Extracting to $ExtractDir ..."
Expand-Archive -Path $ZipPath -DestinationPath $ExtractDir -Force

if (-not (Test-Path $ServerExe)) {
    throw "Extraction completed but llama-server.exe was not found in $ExtractDir"
}

Write-Host ""
Write-Host "llama.cpp runtime ready: $ServerExe"
Write-Host ""
Write-Host "Download the demo model with:"
Write-Host "  .\scripts\fetch-model.ps1"
