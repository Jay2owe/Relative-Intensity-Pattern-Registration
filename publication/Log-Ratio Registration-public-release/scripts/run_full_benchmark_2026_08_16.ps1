param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [ValidateSet('all', 'variations', 'core-internal', 'core-external', 'summaries', 'audit')]
    [string]$Stage = 'all',
    [switch]$Rewrite,
    [int]$WaitForProcessId = 0
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path -LiteralPath $ProjectRoot).Path
$runId = 'full_benchmark_2026-08-16'
$runRoot = Join-Path $project "library\benchmark\v2\runs\$runId"
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
$java = (Get-Command java).Source
$classpath = (Resolve-Path (Join-Path $project 'target\test-classes')).Path + ';' +
    (Resolve-Path (Join-Path $project 'target\classes')).Path + ';' +
    (Get-Content (Join-Path $project 'scripts\preview-cp.txt') -Raw).Trim()
$rewriteOption = if ($Rewrite) { '-Dlogratio.rewrite=true' } else { '-Dlogratio.rewrite=false' }

if ($WaitForProcessId -gt 0) {
    Wait-Process -Id $WaitForProcessId -ErrorAction SilentlyContinue
}

function Invoke-LoggedJava([string]$Name, [string[]]$JavaArguments) {
    $stdout = Join-Path $runRoot "$Name.stdout.log"
    $stderr = Join-Path $runRoot "$Name.stderr.log"
    "START $Name $(Get-Date -Format o)" | Add-Content (Join-Path $runRoot 'stage_status.log')
    & $java @JavaArguments 1> $stdout 2> $stderr
    if ($LASTEXITCODE -ne 0) {
        "FAILED $Name $(Get-Date -Format o) exit=$LASTEXITCODE" |
            Add-Content (Join-Path $runRoot 'stage_status.log')
        throw "$Name failed; inspect $stderr"
    }
    "COMPLETE $Name $(Get-Date -Format o)" | Add-Content (Join-Path $runRoot 'stage_status.log')
}

function Invoke-Variations {
    Invoke-LoggedJava 'all_variations' @(
        '-Xmx8g', '-Dlogratio.onlyExperiment=both', $rewriteOption,
        '-cp', $classpath, 'logratio.FullLogRatioVariationBenchmark', $project)
}

function Invoke-InternalCore {
    Invoke-LoggedJava 'controlled_core_internal' @(
        '-Xmx8g', '-Dlogratio.confirmBalancedRun=true', '-Dlogratio.onlyCondition=CLEAN',
        '-cp', $classpath, 'logratio.BenchmarkV2ComparisonStacks', $project)
    Invoke-LoggedJava 'natural_core_internal' @(
        '-Xmx8g', '-Dlogratio.confirmBalancedRun=true',
        '-cp', $classpath, 'logratio.NativeSeriesComparisonStacks', $project)
}

function Invoke-ExternalCore {
    $fijiRoot = if ($env:RIPR_FIJI_ROOT) {
        (Resolve-Path -LiteralPath $env:RIPR_FIJI_ROOT).Path
    } else {
        throw 'Set RIPR_FIJI_ROOT to the local Fiji.app directory.'
    }
    $fijiJava = Join-Path $fijiRoot 'java\win64\zulu11.88.17-ca-fx-jdk11.0.31-win_x64\bin\java.exe'
    $launcherJar = Join-Path $fijiRoot 'jars\imagej-launcher-6.0.2.jar'
    if (!(Test-Path -LiteralPath $fijiJava)) { throw "missing Fiji Java: $fijiJava" }
    if (!(Test-Path -LiteralPath $launcherJar)) { throw "missing Fiji launcher: $launcherJar" }
    $script = Join-Path $project 'library\benchmark\run_external_full_no_dialog.groovy'
    $nativeLibraries = @(
        (Join-Path $fijiRoot 'lib\win64'),
        (Join-Path $fijiRoot 'mm\win64'),
        (Join-Path $fijiRoot 'lib\fftw'),
        (Join-Path $fijiRoot 'lib\jcuda')
    ) -join ';'
    foreach ($mode in @('controlled', 'natural')) {
        $stdout = Join-Path $runRoot "$mode`_core_external.stdout.log"
        $stderr = Join-Path $runRoot "$mode`_core_external.stderr.log"
        "START $mode`_core_external $(Get-Date -Format o)" |
            Add-Content (Join-Path $runRoot 'stage_status.log')
        $parameters = "project='$($project.Replace("'", "''"))',mode='$mode',series='',root=''"
        $arguments = @(
            '-Xmx45g',
            '-Dpython.cachedir.skip=true',
            "-Dplugins.dir=$fijiRoot",
            '-Djava.awt.headless=true',
            '-Dapple.awt.UIElement=true',
            '-Dscijava.context.strict=false',
            "-Dimagej.dir=$fijiRoot",
            "-Dij.dir=$fijiRoot",
            "-Dfiji.dir=$fijiRoot",
            '-Dfiji.defaultLibPath=bin/server/jvm.dll',
            "-Dfiji.executable=$(Join-Path $fijiRoot 'ImageJ-win64.exe')",
            "-Dij.executable=$(Join-Path $fijiRoot 'ImageJ-win64.exe')",
            "-Djava.library.path=$nativeLibraries",
            '-cp', $launcherJar,
            'net.imagej.launcher.ClassLauncher',
            '-ijjarpath', 'jars',
            '-ijjarpath', 'plugins',
            'net.imagej.Main',
            '--run', $script, $parameters
        )
        # REGRESSION GUARD: ImageJ-win64.exe starts javaw.exe and returns before Fiji finishes.
        # Run Fiji's console Java directly so exit status and logs describe the real benchmark.
        Push-Location -LiteralPath $fijiRoot
        try {
            # Fiji writes non-fatal plugin-discovery warnings to stderr. PowerShell 5 turns native
            # stderr into terminating ErrorRecords under Stop, so judge the process by its exit code.
            $previousErrorAction = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            try {
                & $fijiJava @arguments 1> $stdout 2> $stderr
                $fijiExitCode = $LASTEXITCODE
            } finally {
                $ErrorActionPreference = $previousErrorAction
            }
        } finally {
            Pop-Location
        }
        if ($fijiExitCode -ne 0) {
            "FAILED $mode`_core_external $(Get-Date -Format o) exit=$fijiExitCode" |
                Add-Content (Join-Path $runRoot 'stage_status.log')
            throw "$mode external Fiji benchmark failed; inspect $stderr"
        }
        "COMPLETE $mode`_core_external $(Get-Date -Format o)" |
            Add-Content (Join-Path $runRoot 'stage_status.log')
    }
}

function Invoke-Summaries {
    foreach ($motion in @(
        'CURVED_OSCILLATING_DRIFT',
        'STEADY_DIRECTIONAL_DRIFT',
        'SUBPIXEL_RANDOM_WALK',
        'INTERMITTENT_JUMPS')) {
        & (Join-Path $project 'library\benchmark\summarize_benchmark_v2.ps1') `
            -MotionProfile $motion -Condition 'CLEAN'
        # REGRESSION GUARD: PowerShell scripts do not set LASTEXITCODE; it can retain an unrelated
        # native program's old value. Use the immediately returned PowerShell success flag.
        if (-not $?) { throw "controlled $motion summary failed" }
    }
    & (Join-Path $project 'library\benchmark\summarize_controlled_profiles.ps1')
    if (-not $?) { throw 'controlled summary failed' }
    & (Join-Path $project 'library\benchmark\summarize_native_benchmark.ps1')
    if (-not $?) { throw 'natural summary failed' }
    & python (Join-Path $project 'library\benchmark\summarize_full_benchmark_2026_08_16.py')
    if ($LASTEXITCODE -ne 0) { throw 'full summary failed' }
}

function Invoke-Audit {
    & python (Join-Path $project 'library\benchmark\audit_full_benchmark_2026_08_16.py') --final
    if ($LASTEXITCODE -ne 0) { throw 'full artifact audit failed' }
    $maven = if ($env:RIPR_MAVEN_CMD) {
        $env:RIPR_MAVEN_CMD
    } else {
        (Get-Command mvn.cmd -ErrorAction Stop).Source
    }
    # REGRESSION GUARD: PowerShell can reinterpret unquoted -D properties passed to a .cmd file.
    & $maven '-Dmaven.buildNumber.skip=true' test
    if ($LASTEXITCODE -ne 0) { throw 'full test suite failed' }
    & $maven '-Dmaven.buildNumber.skip=true' '-DskipTests' package
    if ($LASTEXITCODE -ne 0) { throw 'plugin packaging failed' }
}

switch ($Stage) {
    'variations' { Invoke-Variations }
    'core-internal' { Invoke-InternalCore }
    'core-external' { Invoke-ExternalCore }
    'summaries' { Invoke-Summaries }
    'audit' { Invoke-Audit }
    'all' {
        Invoke-Variations
        Invoke-InternalCore
        Invoke-ExternalCore
        Invoke-Summaries
        Invoke-Audit
    }
}
