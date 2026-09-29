# CodeGuard AI - Forward a remote GenieX server onto this machine's loopback
#
# A Snapdragon device (local, or in a Qualcomm Device Cloud session) runs
# "geniex serve <model> --connection-mode local", which listens on
# http://127.0.0.1:18181 *inside the device*. The GenieX API has no
# authentication, and the backend refuses any base URL that is not loopback
# (AiProperties.validateLoopbackUrl), so the port is forwarded to this machine
# rather than published: ssh -L keeps the server bound to the device loopback.
#
# Usage (PowerShell):
#   .\scripts\start-geniex-tunnel.ps1 -SshHost 203.0.113.10 -SshUser your-id
#   .\scripts\start-geniex-tunnel.ps1 -SshHost 127.0.0.1 -Foreground   # local device
#
# Optional overrides:
#   -Port          local port to bind            (default: 18181)
#   -RemotePort    GenieX port on the device      (default: 18181)
#   -IdentityFile  SSH private key                (default: ssh default)
#   -Foreground    keep ssh in this window instead of backgrounding it
#   -ExtraArgs     extra ssh arguments            (default: none)

param(
    [Parameter(Mandatory = $true)][string]$SshHost,
    [string]$SshUser = '',
    [int]$Port = 18181,
    [int]$RemotePort = 18181,
    [string]$IdentityFile = '',
    [string[]]$ExtraArgs = @(),
    [switch]$Foreground
)

$ErrorActionPreference = 'Stop'

if ($Port -lt 1024 -or $Port -gt 65535 -or $RemotePort -lt 1024 -or $RemotePort -gt 65535) {
    throw "Ports must be between 1024 and 65535. Got local=$Port remote=$RemotePort."
}

$ssh = Get-Command ssh -ErrorAction SilentlyContinue

if (-not $ssh) {
    throw "ssh was not found on PATH. Install an OpenSSH client, then re-run this script."
}

if ($IdentityFile -and -not (Test-Path $IdentityFile)) {
    throw "Identity file not found: $IdentityFile"
}

# A port that is already listening means either a previous tunnel or a local
# GenieX server. Continuing would silently verify against the wrong server.
$listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue

if ($listener) {
    throw "Port $Port is already in use on this machine. Stop the other process (or pass -Port) first."
}

$target = if ($SshUser) { "$SshUser@$SshHost" } else { $SshHost }

$forward = '{0}:127.0.0.1:{1}' -f $Port, $RemotePort

$sshArguments = @(
    '-N',
    # Fail fast instead of holding a dead tunnel open.
    '-o', 'ExitOnForwardFailure=yes',
    '-o', 'ServerAliveInterval=30',
    '-L', $forward
)

if ($IdentityFile) {
    $sshArguments += @('-i', $IdentityFile)
}

$sshArguments += $ExtraArgs
$sshArguments += $target

Write-Host "Forwarding the GenieX server on $SshHost"
Write-Host "  local  : http://127.0.0.1:$Port  (this machine)"
Write-Host "  remote : http://127.0.0.1:$RemotePort  (on $SshHost)"
Write-Host "  target : $target"
Write-Host ""

if ($Foreground) {
    & $ssh.Source $sshArguments
    exit $LASTEXITCODE
}

$process = Start-Process -FilePath $ssh.Source -ArgumentList $sshArguments -PassThru -WindowStyle Hidden

Write-Host "Started ssh (pid $($process.Id)). Checking that the forwarded server answers..."

$deadline = (Get-Date).AddSeconds(20)
$healthy = $false

while ((Get-Date) -lt $deadline) {

    if ($process.HasExited) {
        throw "ssh exited with code $($process.ExitCode) before the forward was usable. Check the SSH host, user and key."
    }

    try {
        $response = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 3 -UseBasicParsing
        if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) {
            $healthy = $true
            break
        }
    } catch {
        Start-Sleep -Seconds 1
    }
}

if (-not $healthy) {
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    throw "The tunnel is up but nothing answered on http://127.0.0.1:$Port/health. Start GenieX on $SshHost first: 'geniex serve <model> --connection-mode local'."
}

Write-Host "Tunnel is ready. GenieX is reachable on this machine at http://127.0.0.1:$Port"
Write-Host ""
Write-Host "Next:"
Write-Host "  1. .\scripts\verify-geniex-npu.ps1 -Model <served-model-id>"
Write-Host "  2. codeguard.qualcomm.server.enabled=true"
Write-Host "     codeguard.qualcomm.server.base-url=http://127.0.0.1:$Port"
Write-Host "     codeguard.qualcomm.server.model=<served-model-id>"
Write-Host "  3. Stop the tunnel with: Stop-Process -Id $($process.Id)"
