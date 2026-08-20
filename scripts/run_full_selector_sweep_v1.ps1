param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [ValidateSet('all', 'manifest', 'sweep', 'summary', 'training', 'locked-test', 'final', 'tests',
        'sensitivity', 'sweep-proxy', 'external-summary')]
    [string]$Stage = 'all',
    [switch]$Rewrite,
    [string]$OnlySeries = '',
    [string]$OnlyRecipe = ''
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path -LiteralPath $ProjectRoot).Path
$runId = 'full_selector_sweep_v1'
$runRoot = Join-Path $project "library\benchmark\v2\runs\$runId"
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
$java = (Get-Command java).Source
$classpath = (Resolve-Path (Join-Path $project 'target\test-classes')).Path + ';' +
    (Resolve-Path (Join-Path $project 'target\classes')).Path + ';' +
    (Get-Content (Join-Path $project 'scripts\preview-cp.txt') -Raw).Trim()

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

function Get-SweepOptions {
    $options = @('-Xmx8g')
    if ($Rewrite) { $options += '-Dlogratio.rewrite=true' } else { $options += '-Dlogratio.rewrite=false' }
    if ($OnlySeries -ne '') { $options += "-Dlogratio.onlySeries=$OnlySeries" }
    if ($OnlyRecipe -ne '') { $options += "-Dlogratio.onlyRecipe=$OnlyRecipe" }
    return $options
}

function Invoke-Manifest {
    Invoke-LoggedJava 'stage1_manifest' @(
        '-Xmx2g', '-Dlogratio.manifestOnly=true',
        '-cp', $classpath, 'logratio.FullSelectorFactorialBenchmark', $project)
}

function Invoke-Sweep {
    Invoke-LoggedJava 'stage2_sweep' ((Get-SweepOptions) +
        @('-cp', $classpath, 'logratio.FullSelectorFactorialBenchmark', $project))
}

function Invoke-Summary {
    Invoke-LoggedJava 'stage3_summary' @(
        '-Xmx4g', '-cp', $classpath, 'logratio.FullSelectorSweepSummary', $project)
}

function Invoke-Training {
    Invoke-LoggedJava 'stage4_training' @(
        '-Xmx8g', '-cp', $classpath, 'logratio.FullSelectorTraining', $project)
}

function Invoke-LockedTest {
    Invoke-LoggedJava 'stage5_locked_test_build' @(
        '-Xmx8g', '-cp', $classpath, 'logratio.LockedTestSetBuilder', $project)
    Invoke-LoggedJava 'stage5_locked_test_compare' @(
        '-Xmx8g', '-Dlogratio.comparisonRoot=library/benchmark/v2/benchmarks/locked_test',
        '-cp', $classpath, 'logratio.SelectorComparisonBenchmark', $project)
    Invoke-LoggedJava 'stage5_locked_test_gates' @(
        '-Xmx4g', '-cp', $classpath, 'logratio.LockedTestReport', $project)
}

function Invoke-FinalComparison {
    Invoke-LoggedJava 'stage7_final_comparison' @(
        '-Xmx8g', '-cp', $classpath, 'logratio.SelectorComparisonBenchmark', $project)
}

function Invoke-Sensitivity {
    Invoke-LoggedJava 'sensitivity' @(
        '-Xmx8g', '-cp', $classpath, 'logratio.SensitivityBenchmark', $project)
}

function Invoke-SweepProxy {
    # Registers nothing: rescores the saved transforms of every factorial run, so it needs stage 2
    # artifacts present but never rewrites them.
    Invoke-LoggedJava 'sweep_proxy' @(
        '-Xmx8g', '-cp', $classpath, 'logratio.SweepProxyOracleBenchmark', $project)
}

function Invoke-ExternalSummary {
    # Joins our arms to the installed third-party engines. Reads saved shifts only; runs nothing.
    Invoke-LoggedJava 'external_summary_controlled' @(
        '-Xmx4g', '-cp', $classpath, 'logratio.ExternalComparisonSummary', $project)
    Invoke-LoggedJava 'external_summary_locked' @(
        '-Xmx4g', '-Dlogratio.comparisonRoot=library/benchmark/v2/benchmarks/locked_test',
        '-cp', $classpath, 'logratio.ExternalComparisonSummary', $project)
}

function Invoke-Tests {
    $maven = 'C:\Users\Owner\.m2\wrapper\dists\apache-maven-3.9.9\8e74001100ff70d6af083c5511fcc5ec49282d7017cde82c3698eee8fdf86698\bin\mvn.cmd'
    # REGRESSION GUARD: PowerShell can reinterpret unquoted -D properties passed to a .cmd file.
    & $maven '-Dmaven.buildNumber.skip=true' test
    if ($LASTEXITCODE -ne 0) { throw 'full test suite failed' }
    & $maven '-Dmaven.buildNumber.skip=true' '-DskipTests' package
    if ($LASTEXITCODE -ne 0) { throw 'plugin packaging failed' }
}

switch ($Stage) {
    'manifest' { Invoke-Manifest }
    'sweep' { Invoke-Sweep }
    'summary' { Invoke-Summary }
    'training' { Invoke-Training }
    'locked-test' { Invoke-LockedTest }
    'final' { Invoke-FinalComparison }
    'tests' { Invoke-Tests }
    'sensitivity' { Invoke-Sensitivity }
    'sweep-proxy' { Invoke-SweepProxy }
    'external-summary' { Invoke-ExternalSummary }
    'all' {
        Invoke-Manifest
        Invoke-Sweep
        Invoke-Summary
        Invoke-Training
        Invoke-LockedTest
        Invoke-FinalComparison
        Invoke-Sensitivity
        Invoke-SweepProxy
        Invoke-ExternalSummary
        Invoke-Tests
    }
}
