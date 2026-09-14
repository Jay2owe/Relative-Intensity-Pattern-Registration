param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [ValidateSet('all', 'build', 'ours', 'estimators', 'turboreg', 'summary')]
    [string]$Stage = 'all',
    [switch]$Rewrite,
    [string]$OnlySeries = ''
)

# Stage 0 of docs/spline_estimator_plan.md: does TurboReg's advantage survive a brightness change?
# The gate is declared in docs/spline_estimator_stage0_gate.md and was written before this ran.
#
# The recordings go in their own tree. Every summary tool walks a root and aggregates whatever it
# finds, so putting GAIN_FADE beside CLEAN would blend the two tables and overwrite the CLEAN record.

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path -LiteralPath $ProjectRoot).Path
$runId = 'gain_fade_gate_v1'
$comparisonRoot = 'library/benchmark/v2/benchmarks/controlled_motion_gain_fade'
$runRoot = Join-Path $project "library\benchmark\v2\runs\$runId"
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
$java = (Get-Command java).Source
$classpath = (Resolve-Path (Join-Path $project 'target\test-classes')).Path + ';' +
    (Resolve-Path (Join-Path $project 'target\classes')).Path + ';' +
    (Get-Content (Join-Path $project 'scripts\preview-cp.txt') -Raw).Trim()
$rewriteOption = if ($Rewrite) { '-Dlogratio.rewrite=true' } else { '-Dlogratio.rewrite=false' }

# The builder refuses an unfiltered all-series run unless the balanced run is confirmed; the two
# comparison runners take the series filter alone and need no confirmation.
#
# REGRESSION GUARD: the [string[]] casts are load-bearing. PowerShell unwraps a one-element array
# returned from an if-block into a bare string, and "string + array" concatenates instead of
# appending, which silently fuses every java argument into one and yields "Usage: java".
[string[]]$buildSeriesOptions = if ($OnlySeries -ne '') {
    @("-Dlogratio.onlySeries=$OnlySeries")
} else {
    @('-Dlogratio.confirmBalancedRun=true')
}
[string[]]$seriesOptions = if ($OnlySeries -ne '') { @("-Dlogratio.onlySeries=$OnlySeries") } else { @() }

function Invoke-LoggedJava([string]$Name, [string[]]$JavaArguments) {
    $stdout = Join-Path $runRoot "$Name.stdout.log"
    $stderr = Join-Path $runRoot "$Name.stderr.log"
    "START $Name $(Get-Date -Format o)" | Add-Content (Join-Path $runRoot 'stage_status.log')
    # The exact invocation, so a run can be repeated from the record rather than from the script as
    # it happens to read later.
    Set-Content -Path (Join-Path $runRoot "$Name.command.log") -Encoding utf8 -Value (
        @($java) + $JavaArguments)
    & $java @JavaArguments 1> $stdout 2> $stderr
    if ($LASTEXITCODE -ne 0) {
        "FAILED $Name $(Get-Date -Format o) exit=$LASTEXITCODE" |
            Add-Content (Join-Path $runRoot 'stage_status.log')
        throw "$Name failed; inspect $stderr"
    }
    "COMPLETE $Name $(Get-Date -Format o)" | Add-Content (Join-Path $runRoot 'stage_status.log')
}

function Invoke-Build {
    # Writes 00_input_uncorrected.tif, README.txt and comparison.csv for each of the 80 recordings.
    # onlyMethod keeps one internal arm: the scored arms come from the two comparison runners below,
    # so the other nineteen would be 2.8 MB each of stack nobody reads.
    Invoke-LoggedJava 'stage1_build_gain_fade' ($buildSeriesOptions + @(
        '-Xmx8g', '-Dlogratio.onlyCondition=GAIN_FADE',
        "-Dlogratio.comparisonRoot=$comparisonRoot",
        '-Dlogratio.onlyMethod=01_log_ratio_tukey_standard_gradient',
        '-cp', $classpath, 'ripr.BenchmarkV2ComparisonStacks', $project))
}

function Invoke-Ours {
    # The six selector arms. ExternalComparisonSummary joins on this run's all_recordings.csv, and
    # arm 4 is the shipped default every ratio in the gate is taken against.
    Invoke-LoggedJava 'stage2_selector_arms' ($seriesOptions + @(
        '-Xmx8g', "-Dlogratio.comparisonRoot=$comparisonRoot", '-Dlogratio.noImages=true',
        $rewriteOption, '-cp', $classpath, 'ripr.SelectorComparisonBenchmark', $project))
}

function Invoke-Estimators {
    # The estimator axis: log-ratio fit against area correlation with everything else held still.
    # This is what separates the criterion from the image model.
    Invoke-LoggedJava 'stage3_pair_estimator_arms' ($seriesOptions + @(
        '-Xmx8g', "-Dlogratio.comparisonRoot=$comparisonRoot",
        $rewriteOption, '-cp', $classpath, 'ripr.PairEstimatorComparisonBenchmark', $project))
}

function Invoke-TurboReg {
    # TurboReg only. The gate needs one external engine and the other eleven cost about 45 minutes.
    #
    # REGRESSION GUARD: this stage runs on Fiji's bundled Java 11, which refuses any class compiled
    # for a newer runtime. A normal `mvn test-compile` here targets Java 8, but an incremental build
    # leaves whatever an earlier JDK 21 compile put in target/, and only the classes that happened to
    # change get rewritten. The symptom is UnsupportedClassVersionError naming an unrelated class.
    # Run `mvn clean test-compile` before this stage, not `mvn test-compile`.
    $fijiRoot = if ($env:FIJI_APP) { $env:FIJI_APP } else { throw 'Set FIJI_APP to the Fiji installation folder.' }
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
    $stdout = Join-Path $runRoot 'stage4_turboreg.stdout.log'
    $stderr = Join-Path $runRoot 'stage4_turboreg.stderr.log'
    "START stage4_turboreg $(Get-Date -Format o)" | Add-Content (Join-Path $runRoot 'stage_status.log')
    # mode=existing walks a recording tree already on disk and takes the movement truth from the
    # folder names, so it needs no manifest row and no condition switch of its own.
    $parameters = "project='$($project.Replace("'", "''"))',mode='existing',series='',root='$comparisonRoot'"
    $arguments = @(
        '-Xmx45g',
        '-Dpython.cachedir.skip=true',
        "-Dplugins.dir=$fijiRoot",
        '-Djava.awt.headless=true',
        '-Dapple.awt.UIElement=true',
        '-Dscijava.context.strict=false',
        '-Dlogratio.onlyExternalMethod=22_real_turboreg_translation_multilag_rcc',
        '-Dlogratio.noImages=true',
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
        # Fiji writes non-fatal plugin-discovery warnings to stderr. PowerShell 5 turns native stderr
        # into terminating ErrorRecords under Stop, so judge the process by its exit code.
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
        "FAILED stage4_turboreg $(Get-Date -Format o) exit=$fijiExitCode" |
            Add-Content (Join-Path $runRoot 'stage_status.log')
        throw "TurboReg gain-fade run failed; inspect $stderr"
    }
    "COMPLETE stage4_turboreg $(Get-Date -Format o)" |
        Add-Content (Join-Path $runRoot 'stage_status.log')
}

function Invoke-Summary {
    # Reads saved shifts only; runs nothing. Produces paired_against_default.csv, which is the file
    # the gate in docs/spline_estimator_stage0_gate.md is read from.
    Invoke-LoggedJava 'stage5_external_summary' @(
        '-Xmx4g', "-Dlogratio.comparisonRoot=$comparisonRoot",
        '-cp', $classpath, 'ripr.ExternalComparisonSummary', $project)
}

switch ($Stage) {
    'build' { Invoke-Build }
    'ours' { Invoke-Ours }
    'estimators' { Invoke-Estimators }
    'turboreg' { Invoke-TurboReg }
    'summary' { Invoke-Summary }
    'all' {
        Invoke-Build
        Invoke-Ours
        Invoke-Estimators
        Invoke-TurboReg
        Invoke-Summary
    }
}
