# CodeGuard AI - Capture GenieX NPU execution evidence
#
# The backend only reports qualcommNpuVerified=true when two independent pieces
# of evidence agree: a live GenieX server round trip, and a geniex-bench result
# that records the NPU compute unit. This script captures both and writes them
# to the artifact the backend reads.
#
# Run it on the machine that can see the GenieX server: a Snapdragon device
# itself, or the workstation running the SSH forward from
# scripts/start-geniex-tunnel.ps1.
#
# Usage (PowerShell):
#   .\scripts\verify-geniex-npu.ps1 -Model qualcommiq/Llama-3.1-8B-Instruct
#
# Optional overrides:
#   -ServerBaseUrl  loopback GenieX URL          (default: http://127.0.0.1:18181)
#   -OutputPath      artifact to write            (default: artifacts/qualcomm/geniex-bench.json)
#   -Device          compute unit, only if geniex-bench did not record one
#   -Chipset         chipset label for the record
#   -HostLabel       device name the run was captured on
#   -SkipHttpProbe   only run geniex-bench
#   -RequireCli      fail when the geniex launcher is not on PATH
#   -BenchArgs       extra arguments for geniex-bench (default: none)
#
# The script fails loudly. It never writes "device": "npu" on its own: the value
# must come from a real geniex-bench run, or from an explicit -Device assertion.

param(
    [string]$Model = '',
    [string]$ServerBaseUrl = 'http://127.0.0.1:18181',
    [string]$OutputPath = '',
    [string]$Device = '',
    [string]$Chipset = '',
    [string]$HostLabel = '',
    [switch]$SkipHttpProbe,
    [switch]$RequireCli,
    [string[]]$BenchArgs = @()
)

#
# Tolerance helpers. The geniex-bench schema is not published, so values are
# located by name wherever they appear in the document, and nothing is invented.
function Find-Value {
    param($Node, [string[]]$Names)

    if ($null -eq $Node) { return $null }

    if ($Node -is [System.Collections.IDictionary]) {

        foreach ($name in $Names) {
            foreach ($key in $Node.Keys) {
                if ([string]$key -eq $name -and $null -ne $Node[$key]) {
                    $text = [string]$Node[$key]
                    if ($text.Trim()) { return $text.Trim() }
                }
            }
        }

        foreach ($key in $Node.Keys) {
            $found = Find-Value -Node $Node[$key] -Names $Names
            if ($found) { return $found }
        }

        return $null
    }

    if (($Node -is [System.Collections.IEnumerable]) -and ($Node -isnot [string])) {

        foreach ($item in $Node) {
            $found = Find-Value -Node $item -Names $Names
            if ($found) { return $found }
        }
    }

    return $null
}

# Numeric leaves whose key looks like a performance measurement are copied
# verbatim into the artifact, keyed by their dotted JSON path.
function Add-Statistics {
    param($Node, [string]$Path, [System.Collections.IDictionary]$Into)

    $markers = @('token', 'tok', 'ttft', 'latency', 'throughput', 'time', 'ms', 'p50', 'p90', 'p99', 'tps', 'gen')

    if ($null -eq $Node -or $Into.Count -ge 40) { return }

    if ($Node -is [System.Collections.IDictionary]) {

        foreach ($key in $Node.Keys) {
            $childPath = if ($Path) { "$Path.$key" } else { "$key" }
            Add-Statistics -Node $Node[$key] -Path $childPath -Into $Into
        }

        return
    }

    if (($Node -is [System.Collections.IEnumerable]) -and ($Node -isnot [string])) {

        $index = 0

        foreach ($item in $Node) {
            Add-Statistics -Node $item -Path "$Path[$index]" -Into $Into
            $index++
        }

        return
    }

    if (($Node -is [ValueType]) -and ($Node -isnot [bool])) {

        $lower = $Path.ToLowerInvariant()

        foreach ($marker in $markers) {
            if ($lower.Contains($marker)) {
                $Into[$Path] = $Node
                return
            }
        }
    }
}

$ErrorActionPreference = 'Stop'

if (-not $OutputPath) {
    $OutputPath = Join-Path $PSScriptRoot '..\artifacts\qualcomm\geniex-bench.json'
}

# The backend only talks to loopback, so this script must not introduce a
# different rule for the evidence it collects.
$uri = [Uri]$ServerBaseUrl

if ($uri.Host -ne '127.0.0.1' -and $uri.Host -ne 'localhost') {
    throw "ServerBaseUrl must target 127.0.0.1 or localhost. Got '$ServerBaseUrl'. Forward a remote device with scripts/start-geniex-tunnel.ps1."
}

$geniex = Get-Command geniex -ErrorAction SilentlyContinue
$geniexBench = Get-Command geniex-bench -ErrorAction SilentlyContinue

if ($RequireCli -and -not $geniex) {
    throw "geniex was not found on PATH and -RequireCli was requested."
}

Write-Host "Collecting GenieX evidence"
Write-Host "  server  : $ServerBaseUrl"
Write-Host "  artifact: $OutputPath"
Write-Host "  geniex  : $(if ($geniex) { $geniex.Source } else { 'not on PATH (fine for a forwarded device)' })"
Write-Host ""

$http = $null
$rawBenchPath = $null

if (-not $SkipHttpProbe) {

    Write-Host "1/3 Probing the GenieX server"

    try {
        $health = Invoke-RestMethod -Uri "$ServerBaseUrl/health" -TimeoutSec 10
    } catch {
        throw "The GenieX server did not answer at $ServerBaseUrl/health: $($_.Exception.Message)`nStart it with 'geniex serve <model> --connection-mode local', or forward it with scripts/start-geniex-tunnel.ps1."
    }

    $models = Invoke-RestMethod -Uri "$ServerBaseUrl/v1/models" -TimeoutSec 15
    $served = @($models.data | ForEach-Object { $_.id } | Where-Object { $_ })

    if ($served.Count -eq 0) {
        throw "$ServerBaseUrl reported no model identifiers, so the served model cannot be confirmed."
    }

    if (-not $Model) {
        $Model = $served[0]
        Write-Host "     using the first served model: $Model"
    }

    $match = $served | Where-Object {
        $_ -eq $Model -or ($_ -like "$Model*")
    } | Select-Object -First 1

    if (-not $match) {
        throw "The server is not serving '$Model'. Served identifiers: $($served -join ', ')"
    }

    $request = @{
        model       = $Model
        messages    = @(@{ role = 'user'; content = 'Reply with exactly one word and nothing else.' })
        max_tokens  = 16
        temperature = 0
    } | ConvertTo-Json -Depth 5

    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $completion = Invoke-RestMethod `
        -Uri "$ServerBaseUrl/v1/chat/completions" `
        -Method Post `
        -ContentType 'application/json' `
        -Body $request `
        -TimeoutSec 120
    $watch.Stop()

    $content = [string]$completion.choices[0].message.content

    if (-not $content.Trim()) {
        throw "The chat completion for '$Model' returned empty content, so no inference evidence was produced."
    }

    $tokens = 0
    if ($completion.usage) {
        $tokens = [int]$completion.usage.completion_tokens
    }

    $http = @{
        model      = $Model
        latencyMs  = [int]$watch.ElapsedMilliseconds
        tokens     = $tokens
        tokensPerSecond = if ($watch.ElapsedMilliseconds -gt 0) { [math]::Round($tokens / ($watch.ElapsedMilliseconds / 1000.0), 2) } else { 0 }
    }

    Write-Host "     completion returned content in $($http.latencyMs) ms"
} else {
    Write-Host "1/3 Skipping the server probe (-SkipHttpProbe)"
}

Write-Host "2/3 Running geniex-bench"

$bench = $null
$rawBenchPath = $null
$benchModel = ''

if ($geniexBench) {

    $rawPath = Join-Path ([System.IO.Path]::GetTempPath()) ("geniex-bench-" + [guid]::NewGuid().ToString('N') + ".json")
    $benchArguments = @('--output-json', $rawPath) + $BenchArgs

    if ($Model) {
        $benchArguments += @($Model)
    }

    Write-Host "     geniex-bench $($benchArguments -join ' ')"

    $consoleOutput = (& $geniexBench.Source @benchArguments 2>&1 | Out-String)

    if (Test-Path $rawPath) {
        $rawBenchPath = $rawPath
        $raw = Get-Content -Raw $rawPath
    } else {
        $raw = $consoleOutput
        $rawBenchPath = Join-Path ([System.IO.Path]::GetTempPath()) ("geniex-bench-" + [guid]::NewGuid().ToString('N') + ".raw.txt")
        [System.IO.File]::WriteAllText($rawBenchPath, $raw)
    }

    try {
        $bench = $raw | ConvertFrom-Json
        Write-Host "     raw benchmark output kept at $rawBenchPath"
    } catch {
        Write-Warning "geniex-bench did not produce parsable JSON. The raw output was kept at $rawBenchPath so its schema can be added to GenieXBenchArtifactReader."
    }
} else {
    Write-Warning "geniex-bench was not found on PATH. The compute unit cannot be proven without it, so pass -Device only if you observed the run yourself."
}

Write-Host "3/3 Writing the evidence artifact"

$benchDevice = Find-Value -Node $bench -Names @('device', 'compute_unit', 'computeUnit', 'target_device')
$benchPlugin = Find-Value -Node $bench -Names @('plugin', 'runtime', 'engine', 'backend')
$benchChipset = Find-Value -Node $bench -Names @('chipset', 'soc', 'chip', 'target_platform')
$benchHost = Find-Value -Node $bench -Names @('host', 'hostname', 'machine', 'node')
$benchModel = Find-Value -Node $bench -Names @('model', 'model_id', 'modelId', 'model_name')

$deviceValue = $benchDevice
$deviceSource = 'geniex-bench'

if (-not $deviceValue) {
    $deviceValue = $Device
    $deviceSource = if ($deviceValue) { 'operator-assertion' } else { 'unavailable' }
}

$statistics = [ordered]@{}

if ($null -ne $bench) {
    Add-Statistics -Node $bench -Path '' -Into $statistics
}

if ($null -ne $http) {
    $statistics['http.latency_ms'] = $http.latencyMs
    $statistics['http.completion_tokens'] = $http.tokens
    $statistics['http.tokens_per_second'] = $http.tokensPerSecond
}

$artifact = [ordered]@{
    schema        = 'codeguard.geniex-bench/1'
    captured_at   = [DateTime]::UtcNow.ToString('o')
    device        = $deviceValue
    device_source = $deviceSource
    plugin        = $benchPlugin
    model         = if ($benchModel) { $benchModel } else { $Model }
    chipset       = if ($benchChipset) { $benchChipset } else { $Chipset }
    host          = if ($benchHost) { $benchHost } else { $HostLabel }
    statistics    = $statistics
}

if ($rawBenchPath) {
    $artifact['raw_bench_output'] = $rawBenchPath
}

$outputDirectory = Split-Path -Parent $OutputPath

if ($outputDirectory) {
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
}

$json = $artifact | ConvertTo-Json -Depth 8

# UTF-8 without a byte order mark: the backend reads this file with
# Files.readString, and a leading BOM is not valid JSON content.
[System.IO.File]::WriteAllText($OutputPath, $json, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ""
Write-Host "Wrote $OutputPath"
Write-Host "  device  : $deviceValue (source: $deviceSource)"
Write-Host "  model   : $($artifact['model'])"
Write-Host "  chipset : $($artifact['chipset'])"

if (-not $deviceValue) {
    throw "Evidence was written, but the compute unit is unknown. The backend will refuse this artifact with 'artifact-device-missing'. Re-run this script on the Snapdragon device where geniex-bench is available, and pass -Device only after observing the run yourself."
}

Write-Host ""
Write-Host "Next:"
Write-Host "  codeguard.qualcomm.server.enabled: true"
Write-Host "  codeguard.qualcomm.server.base-url: $ServerBaseUrl"
Write-Host "  codeguard.qualcomm.server.model: $Model"
Write-Host "  codeguard.qualcomm.verification.artifact-path: $OutputPath"
Write-Host "  then GET /api/runtime and check qualcommVerificationTier and qualcommNpuVerified"

