param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [int]$SupervisorPid = 0
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path -LiteralPath $ProjectRoot).Path
$runRoot = Join-Path $project 'library\benchmark\v3\runs\publication_supervisor'
$status = Join-Path $runRoot 'keep_awake_status.log'
$active = Join-Path $runRoot 'KEEP_AWAKE_ACTIVE.marker'
$complete = Join-Path $runRoot 'PUBLICATION_COMPLETE.marker'
$supervisorStatus = Join-Path $runRoot 'supervisor_status.log'
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null

Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class BenchmarkPowerState {
    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern uint SetThreadExecutionState(uint flags);
}
'@

$continuous = [uint32]::Parse('80000000', [Globalization.NumberStyles]::HexNumber)
$systemRequired = [uint32]0x00000001

function Write-Status([string]$Message) {
    "$(Get-Date -Format o) $Message" | Add-Content -LiteralPath $status
}

try {
    "pid=$PID`nsupervisor_pid=$SupervisorPid`nstarted=$(Get-Date -Format o)" |
        Set-Content -LiteralPath $active
    $result = [BenchmarkPowerState]::SetThreadExecutionState($continuous -bor $systemRequired)
    if ($result -eq 0) { throw "SetThreadExecutionState failed: $([Runtime.InteropServices.Marshal]::GetLastWin32Error())" }
    Write-Status "START pid=$PID supervisor_pid=$SupervisorPid execution_state=$result"

    while ($true) {
        if (Test-Path -LiteralPath $complete) {
            Write-Status 'STOP publication complete'
            break
        }
        if (Test-Path -LiteralPath $supervisorStatus) {
            $failed = Select-String -LiteralPath $supervisorStatus -SimpleMatch 'SUPERVISOR FAILED' -Quiet
            if ($failed) {
                Write-Status 'STOP publication supervisor failed'
                break
            }
        }
        if ($SupervisorPid -gt 0 -and !(Get-Process -Id $SupervisorPid -ErrorAction SilentlyContinue)) {
            Write-Status 'STOP publication supervisor process absent'
            break
        }
        $result = [BenchmarkPowerState]::SetThreadExecutionState($continuous -bor $systemRequired)
        if ($result -eq 0) { throw "SetThreadExecutionState refresh failed" }
        Start-Sleep -Seconds 30
    }
} catch {
    Write-Status "FAILED $($_.Exception.Message)"
    throw
} finally {
    [void][BenchmarkPowerState]::SetThreadExecutionState($continuous)
    Remove-Item -LiteralPath $active -ErrorAction SilentlyContinue
}
