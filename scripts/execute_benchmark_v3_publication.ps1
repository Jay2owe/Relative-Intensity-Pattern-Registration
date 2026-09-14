param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [switch]$Detach
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path -LiteralPath $ProjectRoot).Path
$v3 = Join-Path $project 'library\benchmark\v3'
$runRoot = Join-Path $v3 'runs\publication_supervisor'
$status = Join-Path $runRoot 'supervisor_status.log'
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null

function Write-Status([string]$Message) {
    $line = "$(Get-Date -Format o) $Message"
    for ($attempt = 1; $attempt -le 40; $attempt++) {
        try { $line | Add-Content -LiteralPath $status; break }
        catch [IO.IOException] {
            if ($attempt -eq 40) { throw }
            Start-Sleep -Milliseconds 250
        }
    }
    Write-Host $line
}

if ($Detach) {
    $stamp = "{0}_{1}" -f (Get-Date).ToString('yyyyMMdd_HHmmss_fff'),
        ([Guid]::NewGuid().ToString('N').Substring(0, 8))
    $stdout = Join-Path $runRoot "supervisor_$stamp.stdout.log"
    $stderr = Join-Path $runRoot "supervisor_$stamp.stderr.log"
    $escapedScript = $PSCommandPath.Replace("'", "''")
    $escapedProject = $project.Replace("'", "''")
    $command = "& '$escapedScript' -ProjectRoot '$escapedProject'"
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $process = Start-Process powershell.exe `
        -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $encoded) `
        -WorkingDirectory $project -WindowStyle Hidden -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr -PassThru
    Write-Status "DETACHED supervisor_pid=$($process.Id) stdout=$stdout stderr=$stderr"
    return
}

Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class PublicationBenchmarkPowerState {
    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern uint SetThreadExecutionState(uint flags);
}
'@
$continuousPowerState = [uint32]::Parse('80000000', [Globalization.NumberStyles]::HexNumber)
$systemRequiredPowerState = [uint32]0x00000001

$python = Join-Path $env:LOCALAPPDATA 'LogRatioBenchmarkV3\venv\Scripts\python.exe'
$tuningRoot = Join-Path $v3 'runs\development\tuning'
$tuningActive = Join-Path $tuningRoot 'TUNING_ACTIVE.marker'
$tuningComplete = Join-Path $tuningRoot 'TUNING_COMPLETE.marker'
$freeze = Join-Path $v3 'protocol\PROTOCOL_FROZEN.txt'

function Invoke-Checked([string]$Label, [scriptblock]$Action) {
    Write-Status "START $Label"
    & $Action
    if ($LASTEXITCODE -ne 0) { throw "$Label exited $LASTEXITCODE" }
    Write-Status "COMPLETE $Label"
}

function Wait-Tuning {
    $attempt = 0
    while (!(Test-Path -LiteralPath $tuningComplete)) {
        if (Test-Path -LiteralPath $tuningActive) {
            Write-Status 'WAIT development tuning active'
            Start-Sleep -Seconds 30
            continue
        }
        $attempt++
        if ($attempt -gt 3) { throw 'development tuning failed three resumable attempts' }
        Write-Status "RESTART development tuning attempt=$attempt"
        & (Join-Path $project 'scripts\run_benchmark_v3_tuning.ps1') `
            -ProjectRoot $project -Stage all -Preprocessing both
        if ($LASTEXITCODE -ne 0) {
            Write-Status "development tuning attempt=$attempt exited=$LASTEXITCODE"
        }
    }
    Write-Status 'GATE development tuning complete'
}

function Invoke-FrozenRunWithRetries {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            Invoke-Checked "publication frozen methods attempt=$attempt" {
                & (Join-Path $project 'scripts\run_benchmark_v3_frozen_methods.ps1') `
                    -ProjectRoot $project -Split publication_test -View all -Stage all
            }
            return
        } catch {
            Write-Status "RETRY publication frozen methods attempt=$attempt error=$($_.Exception.Message)"
            if ($attempt -eq 3) { throw }
        }
    }
}

Push-Location $project
try {
    $powerState = [PublicationBenchmarkPowerState]::SetThreadExecutionState(
        $continuousPowerState -bor $systemRequiredPowerState)
    if ($powerState -eq 0) { throw 'could not prevent automatic sleep during publication execution' }
    Write-Status "SUPERVISOR START pid=$PID"
    Wait-Tuning

    Invoke-Checked 'freeze development configurations' {
        & $python (Join-Path $project 'scripts\freeze_benchmark_v3_configs.py') --project $project
    }

    # Finish the pilot external audit after its already-running locked jobs and before opening v3.
    Invoke-Checked 'finalize v2 external sweep audit' {
        & $python (Join-Path $project 'scripts\summarize_external_parameter_sweep.py') --stage final
    }

    # Repeat all technical routes on five unreported format-check sources. Performance is discarded.
    Invoke-Checked 'integration internal routes' {
        & (Join-Path $project 'scripts\run_benchmark_v3.ps1') -ProjectRoot $project `
            -Stage internal -Split integration -OnlyCondition U00 `
            -OnlyMotion CURVED_OSCILLATING_DRIFT -IncludeScheduler
    }
    Invoke-Checked 'integration Python routes' {
        & (Join-Path $project 'scripts\run_benchmark_v3.ps1') -ProjectRoot $project `
            -Stage python -Split integration -OnlyCondition U00 -OnlyMotion CURVED_OSCILLATING_DRIFT
    }
    Invoke-Checked 'integration JNormCorre route' {
        & (Join-Path $project 'scripts\run_benchmark_v3.ps1') -ProjectRoot $project `
            -Stage jnormcorre -Split integration -OnlyCondition U00 -OnlyMotion CURVED_OSCILLATING_DRIFT
    }
    Invoke-Checked 'integration Moco route' {
        & (Join-Path $project 'scripts\run_benchmark_v3.ps1') -ProjectRoot $project `
            -Stage moco -Split integration -OnlyCondition U00 -OnlyMotion CURVED_OSCILLATING_DRIFT
    }
    Invoke-Checked 'integration external routes' {
        & (Join-Path $project 'scripts\run_benchmark_v3_frozen_methods.ps1') `
            -ProjectRoot $project -Split integration -View defaults -Stage external `
            -OnlyCondition U00 -OnlyMotion CURVED_OSCILLATING_DRIFT
    }
    Invoke-Checked 'final conformance replay' {
        & (Join-Path $project 'scripts\run_benchmark_v3.ps1') -ProjectRoot $project `
            -Stage bootstrap -Split integration
    }

    if (!(Test-Path -LiteralPath $freeze)) {
        Invoke-Checked 'freeze publication protocol' {
            & $python (Join-Path $project 'scripts\freeze_benchmark_v3_protocol.py') --project $project
        }
    } else {
        Write-Status 'SKIP protocol already frozen'
    }

    $manifest = Join-Path $v3 'inputs\publication_test\inputs_manifest.csv'
    if (!(Test-Path -LiteralPath $manifest)) {
        $free = (Get-PSDrive -Name ([IO.Path]::GetPathRoot($project).Substring(0, 1))).Free
        if ($free -lt 300GB) { throw "publication generation requires at least 300 GB free; found $free" }
        Invoke-Checked 'generate publication universal panel' {
            & (Join-Path $project 'scripts\run_benchmark_v3.ps1') -ProjectRoot $project `
                -Stage generate -Split publication_test -ConditionPanel universal -ResetManifest
        }
        Invoke-Checked 'generate publication dose panel' {
            & (Join-Path $project 'scripts\run_benchmark_v3.ps1') -ProjectRoot $project `
                -Stage generate -Split publication_test -ConditionPanel dose
        }
    } else {
        Write-Status 'SKIP publication inputs already generated'
    }
    $count = (Import-Csv -LiteralPath $manifest).Count
    if ($count -ne 4920) { throw "expected 4920 publication recordings, found $count" }
    Write-Status "GATE publication input manifest rows=$count"

    Invoke-FrozenRunWithRetries
    Invoke-Checked 'publication analysis and figures' {
        & $python (Join-Path $project 'scripts\analyze_benchmark_v3.py') --project $project
    }
    "completed=$(Get-Date -Format o)" | Set-Content -LiteralPath (Join-Path $runRoot 'PUBLICATION_COMPLETE.marker')
    Write-Status 'PUBLICATION BENCHMARK COMPLETE'
} catch {
    Write-Status "SUPERVISOR FAILED error=$($_.Exception.Message)"
    throw
} finally {
    [void][PublicationBenchmarkPowerState]::SetThreadExecutionState($continuousPowerState)
    Pop-Location
}
