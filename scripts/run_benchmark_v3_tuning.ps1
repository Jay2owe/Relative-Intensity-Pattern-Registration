param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [ValidateSet('all', 'internal', 'python', 'jnormcorre', 'moco', 'external')]
    [string]$Stage = 'all',
    [ValidateSet('native', 'common_normalized', 'both')]
    [string]$Preprocessing = 'both',
    [switch]$Rewrite,
    [switch]$Detach
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path -LiteralPath $ProjectRoot).Path
$protocol = Join-Path $project 'library\benchmark\v3\protocol'
$runRoot = Join-Path $project 'library\benchmark\v3\runs\development\tuning'
$externalRunRoot = Join-Path $project 'library\benchmark\v3\runs\external_parameter_sweep_v1'
$status = Join-Path $runRoot 'tuning_status.log'
$activeMarker = Join-Path $runRoot 'TUNING_ACTIVE.marker'
$completeMarker = Join-Path $runRoot 'TUNING_COMPLETE.marker'
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null

function Write-Status([string]$Message) {
    $line = "$(Get-Date -Format o) $Message"
    for ($attempt = 1; $attempt -le 40; $attempt++) {
        try {
            $line | Add-Content -LiteralPath $status
            break
        } catch [IO.IOException] {
            if ($attempt -eq 40) { throw }
            Start-Sleep -Milliseconds 250
        }
    }
    Write-Host $line
}

if ($Detach) {
    $stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
    $stdout = Join-Path $runRoot "detached_$stamp.stdout.log"
    $stderr = Join-Path $runRoot "detached_$stamp.stderr.log"
    $escapedScript = $PSCommandPath.Replace("'", "''")
    $escapedProject = $project.Replace("'", "''")
    $command = "& '$escapedScript' -ProjectRoot '$escapedProject' -Stage '$Stage' -Preprocessing '$Preprocessing'"
    if ($Rewrite) { $command += ' -Rewrite' }
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $process = Start-Process -FilePath 'powershell.exe' `
        -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $encoded) `
        -WorkingDirectory $project -WindowStyle Hidden -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr -PassThru
    Write-Status "DETACHED pid=$($process.Id) stdout=$stdout stderr=$stderr"
    return
}

$sources = (Import-Csv -LiteralPath (Join-Path $protocol 'tuning_subset.csv') |
    Select-Object -ExpandProperty source_id) -join ','
$conditions = 'U00,U02,U07,U10'
$motion = 'CURVED_OSCILLATING_DRIFT'
$arms = if ($Preprocessing -eq 'both') { 'native,common_normalized' } else { $Preprocessing }
$armList = $arms -split ','
$manifest = Join-Path $project 'library\benchmark\v3\inputs\development\inputs_manifest.csv'
$gridPath = Join-Path $protocol 'python_tuning_grid.csv'
$jnormGridPath = Join-Path $protocol 'jnormcorre_tuning_grid.csv'
$mocoGridPath = Join-Path $protocol 'moco_tuning_grid.csv'
$python = Join-Path $env:LOCALAPPDATA 'LogRatioBenchmarkV3\venv\Scripts\python.exe'
$jnormPython = Join-Path $env:LOCALAPPDATA 'LogRatioBenchmarkV3\jnormcorre-venv\Scripts\python.exe'
$toolRoot = Join-Path $env:LOCALAPPDATA 'LogRatioBenchmarkV3\tools'
# REGRESSION GUARD: target/ is disposable build output; benchmark tools must outlive clean builds.
$maven = Join-Path $toolRoot 'apache-maven-3.9.9\bin\mvn.cmd'
$jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { throw 'Set JAVA_HOME to the Java Development Kit folder.' }
$java = Join-Path $jdk 'bin\java.exe'
$classpathFile = Join-Path $toolRoot 'test-classpath.txt'
foreach ($required in @($manifest, $gridPath, $jnormGridPath, $mocoGridPath, $python, $jnormPython,
        $maven, $java, $classpathFile)) {
    if (!(Test-Path -LiteralPath $required)) { throw "Missing tuning dependency: $required" }
}

function Invoke-Build {
    $env:JAVA_HOME = $jdk
    & $maven -q -DskipTests test-compile
    if ($LASTEXITCODE -ne 0) { throw "Maven test-compile failed: $LASTEXITCODE" }
}

function Java-Classpath {
    return "target\classes;target\test-classes;$((Get-Content $classpathFile -Raw).Trim())"
}

function Moco-Classpath {
    $root = Join-Path $project 'library\benchmark\v3\comparators\moco\source\03-18-2016_release'
    return "$(Java-Classpath);$(Join-Path $root 'moco_.jar');$(Join-Path $root 'jars\*')"
}

function Invoke-InternalTuning {
    $output = Join-Path $runRoot 'internal_tuning_results.csv'
    Write-Status "START internal output=$output"
    $options = @("-Dv3.preprocessingArms=$arms", "-Dv3.onlySource=$sources",
        "-Dv3.onlyCondition=$conditions", '-Dv3.includeScheduler=true')
    if ($Rewrite) { $options += '-Dv3.rewrite=true' }
    & $java @options -cp (Java-Classpath) ripr.BenchmarkV3 internal $project development $output
    if ($LASTEXITCODE -ne 0) { throw "Internal tuning failed: $LASTEXITCODE" }
    Write-Status 'COMPLETE internal'
}

function Add-OptionalArgument([Collections.Generic.List[string]]$Arguments,
                              [string]$Name, [string]$Value) {
    if ($null -ne $Value -and $Value.Trim() -ne '') {
        $Arguments.Add($Name)
        $Arguments.Add($Value.Trim())
    }
}

function Invoke-PythonTuning {
    $output = Join-Path $runRoot 'python_tuning_results.csv'
    $first = $true
    foreach ($row in (Import-Csv -LiteralPath $gridPath)) {
        Write-Status "START python config=$($row.config_id) method=$($row.method_id)"
        $arguments = [Collections.Generic.List[string]]::new()
        foreach ($value in @((Join-Path $project 'scripts\benchmark_v3_python.py'),
                '--project', $project, '--split', 'development', '--output', $output,
                '--methods', $row.method_id, '--config-id', $row.config_id,
                '--preprocessing-arms', $arms, '--only-source', $sources,
                '--only-condition', $conditions, '--only-motion', $motion)) {
            $arguments.Add([string]$value)
        }
        Add-OptionalArgument $arguments '--skimage-upsample' $row.skimage_upsample
        Add-OptionalArgument $arguments '--skimage-normalization' $row.skimage_normalization
        Add-OptionalArgument $arguments '--opencv-window' $row.opencv_window
        Add-OptionalArgument $arguments '--ecc-iterations' $row.ecc_iterations
        Add-OptionalArgument $arguments '--ecc-epsilon' $row.ecc_epsilon
        Add-OptionalArgument $arguments '--ecc-gaussian' $row.ecc_gaussian
        Add-OptionalArgument $arguments '--sitk-optimizer' $row.sitk_optimizer
        Add-OptionalArgument $arguments '--sitk-iterations' $row.sitk_iterations
        Add-OptionalArgument $arguments '--suite-maxregshift' $row.suite_maxregshift
        Add-OptionalArgument $arguments '--suite-smooth-sigma' $row.suite_smooth_sigma
        Add-OptionalArgument $arguments '--suite-smooth-sigma-time' $row.suite_smooth_sigma_time
        if ($Rewrite -and $first) { $arguments.Add('--rewrite') }
        & $python @arguments
        if ($LASTEXITCODE -ne 0) { throw "Python tuning failed for $($row.config_id): $LASTEXITCODE" }
        $first = $false
        Write-Status "COMPLETE python config=$($row.config_id)"
    }
}

function Invoke-JNormCorreTuning {
    $output = Join-Path $runRoot 'jnormcorre_tuning_results.csv'
    $first = $true
    foreach ($row in (Import-Csv -LiteralPath $jnormGridPath)) {
        Write-Status "START jnormcorre config=$($row.config_id)"
        $arguments = @((Join-Path $project 'scripts\benchmark_v3_jnormcorre.py'),
            '--project', $project, '--split', 'development', '--output', $output,
            '--config-id', $row.config_id, '--preprocessing-arms', $arms,
            '--only-source', $sources, '--only-condition', $conditions,
            '--only-motion', $motion, '--max-shift', $row.max_shift,
            '--iterations', $row.iterations, '--frames-per-split', $row.frames_per_split)
        if ($Rewrite -and $first) { $arguments += '--rewrite' }
        & $jnormPython @arguments
        if ($LASTEXITCODE -ne 0) { throw "JNormCorre tuning failed for $($row.config_id): $LASTEXITCODE" }
        $first = $false
        Write-Status "COMPLETE jnormcorre config=$($row.config_id)"
    }
}

function Invoke-MocoTuning {
    $output = Join-Path $runRoot 'moco_tuning_results.csv'
    $first = $true
    foreach ($row in (Import-Csv -LiteralPath $mocoGridPath)) {
        Write-Status "START moco config=$($row.config_id)"
        $options = @("-Dmoco.configId=$($row.config_id)",
            "-Dmoco.downsample=$($row.downsample)",
            "-Dmoco.windowFraction=$($row.window_fraction)",
            "-Dmoco.preprocessingArms=$arms", "-Dmoco.onlySource=$sources",
            "-Dmoco.onlyCondition=$conditions", "-Dmoco.onlyMotion=$motion")
        if ($Rewrite -and $first) { $options += '-Dmoco.rewrite=true' }
        & $java @options -cp (Moco-Classpath) ripr.MocoBenchmarkV3 $project development $output
        if ($LASTEXITCODE -ne 0) { throw "Moco tuning failed for $($row.config_id): $LASTEXITCODE" }
        $first = $false
        Write-Status "COMPLETE moco config=$($row.config_id)"
    }
}

function Wait-ForLegacyExternalRun {
    while ($true) {
        $active = @(Get-CimInstance Win32_Process | Where-Object {
            $_.ProcessId -ne $PID -and
            ($_.CommandLine -like '*external_parameter_sweep_v1*' -or
             $_.CommandLine -like '*run_frozen_external_winners*') -and
            $_.CommandLine -notlike '*run_benchmark_v3_tuning*'
        })
        if ($active.Count -eq 0) { return }
        Write-Status "WAIT legacy external processes=$($active.Count)"
        Start-Sleep -Seconds 15
    }
}

function Invoke-ExternalTuning {
    Wait-ForLegacyExternalRun
    $runner = Join-Path $project 'scripts\run_external_parameter_sweep.ps1'
    foreach ($arm in $armList) {
        Write-Status "START external arm=$arm configs=all"
        $parameters = @{
            ProjectRoot = $project
            Dataset = 'v3_development'
            Config = 'all'
            # REGRESSION GUARD: scoring only the frozen subset is insufficient if Fiji runs the full tree.
            # The fix: forward the identical five-source/one-motion subset used by every other runner.
            OnlySeries = $sources
            OnlyCondition = $conditions
            OnlyMotion = $motion
            PreprocessingArm = $arm
            RunRootOverride = 'library\benchmark\v3\runs\external_parameter_sweep_v1'
            FullEngineRows = $true
            Resume = $true
        }
        if ($Rewrite) { $parameters['Rewrite'] = $true }
        & $runner @parameters
        if ($LASTEXITCODE -ne 0) { throw "External tuning failed for arm=$arm" }
        Write-Status "COMPLETE external arm=$arm"
    }
}

Push-Location -LiteralPath $project
try {
    Remove-Item -LiteralPath $completeMarker -ErrorAction SilentlyContinue
    "pid=$PID`nstarted=$(Get-Date -Format o)" | Set-Content -LiteralPath $activeMarker
    # Runtime is a predeclared final tie-breaker, so do not overlap tuning with the
    # legacy Fiji sweep that was already in flight when this protocol was launched.
    Wait-ForLegacyExternalRun
    Invoke-Build
    if ($Stage -in @('all', 'internal')) { Invoke-InternalTuning }
    if ($Stage -in @('all', 'python')) { Invoke-PythonTuning }
    if ($Stage -in @('all', 'jnormcorre')) { Invoke-JNormCorreTuning }
    if ($Stage -in @('all', 'moco')) { Invoke-MocoTuning }
    if ($Stage -in @('all', 'external')) { Invoke-ExternalTuning }
    Write-Status "DONE stage=$Stage preprocessing=$Preprocessing"
    if ($Stage -eq 'all') {
        "completed=$(Get-Date -Format o)" | Set-Content -LiteralPath $completeMarker
    }
} catch {
    Write-Status "FAILED stage=$Stage error=$($_.Exception.Message)"
    throw
} finally {
    Remove-Item -LiteralPath $activeMarker -ErrorAction SilentlyContinue
    Pop-Location
}
