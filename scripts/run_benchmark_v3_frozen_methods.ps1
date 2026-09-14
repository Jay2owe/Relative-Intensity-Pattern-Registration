param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [ValidateSet('development', 'integration', 'publication_test')]
    [string]$Split = 'publication_test',
    [ValidateSet('defaults', 'tuned', 'all')]
    [string]$View = 'all',
    [ValidateSet('all', 'internal', 'python', 'jnormcorre', 'moco', 'external')]
    [string]$Stage = 'all',
    [string]$OnlySource = '',
    [string]$OnlyCondition = '',
    [string]$OnlyMotion = '',
    [switch]$Rewrite,
    [switch]$Detach
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path -LiteralPath $ProjectRoot).Path
$protocol = Join-Path $project 'library\benchmark\v3\protocol'
$runRoot = Join-Path $project "library\benchmark\v3\runs\$Split"
$status = Join-Path $runRoot 'frozen_method_status.log'
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
    $stdout = Join-Path $runRoot "frozen_detached_$stamp.stdout.log"
    $stderr = Join-Path $runRoot "frozen_detached_$stamp.stderr.log"
    function Q([string]$Value) { return "'$($Value.Replace("'", "''"))'" }
    $command = "& $(Q $PSCommandPath) -ProjectRoot $(Q $project) -Split $(Q $Split)" +
        " -View $(Q $View) -Stage $(Q $Stage)"
    foreach ($pair in @(@('-OnlySource', $OnlySource), @('-OnlyCondition', $OnlyCondition),
            @('-OnlyMotion', $OnlyMotion))) {
        if ($pair[1]) { $command += " $($pair[0]) $(Q $pair[1])" }
    }
    if ($Rewrite) { $command += ' -Rewrite' }
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $process = Start-Process powershell.exe `
        -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $encoded) `
        -WorkingDirectory $project -WindowStyle Hidden -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr -PassThru
    Write-Status "DETACHED pid=$($process.Id) split=$Split view=$View stage=$Stage"
    return
}

if ($Split -eq 'publication_test' -and
        !(Test-Path -LiteralPath (Join-Path $protocol 'PROTOCOL_FROZEN.txt'))) {
    throw 'Publication-test execution is locked until protocol/PROTOCOL_FROZEN.txt exists.'
}

$winnersPath = Join-Path $protocol 'frozen_config\winners.csv'
$pythonGridPath = Join-Path $protocol 'python_tuning_grid.csv'
$jnormGridPath = Join-Path $protocol 'jnormcorre_tuning_grid.csv'
$mocoGridPath = Join-Path $protocol 'moco_tuning_grid.csv'
$winners = if (Test-Path -LiteralPath $winnersPath) { Import-Csv $winnersPath } else { @() }
if ($View -in @('tuned', 'all') -and $winners.Count -eq 0) {
    throw "Missing frozen winners: $winnersPath"
}

$python = Join-Path $env:LOCALAPPDATA 'LogRatioBenchmarkV3\venv\Scripts\python.exe'
$jnormPython = Join-Path $env:LOCALAPPDATA 'LogRatioBenchmarkV3\jnormcorre-venv\Scripts\python.exe'
$toolRoot = Join-Path $env:LOCALAPPDATA 'LogRatioBenchmarkV3\tools'
# REGRESSION GUARD: publication dependencies must survive Maven clean deleting target/.
$maven = Join-Path $toolRoot 'apache-maven-3.9.9\bin\mvn.cmd'
$jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { throw 'Set JAVA_HOME to the Java Development Kit folder.' }
$java = Join-Path $jdk 'bin\java.exe'
$classpathFile = Join-Path $toolRoot 'test-classpath.txt'
foreach ($required in @($python, $jnormPython, $maven, $java, $classpathFile,
        $pythonGridPath, $jnormGridPath, $mocoGridPath)) {
    if (!(Test-Path -LiteralPath $required)) { throw "Missing frozen-run dependency: $required" }
}

function Java-Classpath {
    return "target\classes;target\test-classes;$((Get-Content $classpathFile -Raw).Trim())"
}

function Moco-Classpath {
    $root = Join-Path $project 'library\benchmark\v3\comparators\moco\source\03-18-2016_release'
    return "$(Java-Classpath);$(Join-Path $root 'moco_.jar');$(Join-Path $root 'jars\*')"
}

function Add-Optional([Collections.Generic.List[string]]$Arguments,
                      [string]$Name, [string]$Value) {
    if ($null -ne $Value -and $Value.Trim() -ne '') {
        $Arguments.Add($Name); $Arguments.Add($Value.Trim())
    }
}

function Common-PythonArguments([string]$Script, [string]$Output, [string]$Arm) {
    $arguments = [Collections.Generic.List[string]]::new()
    foreach ($value in @($Script, '--project', $project, '--split', $Split,
            '--output', $Output, '--preprocessing-arms', $Arm)) {
        $arguments.Add([string]$value)
    }
    Add-Optional $arguments '--only-source' $OnlySource
    Add-Optional $arguments '--only-condition' $OnlyCondition
    Add-Optional $arguments '--only-motion' $OnlyMotion
    return ,$arguments
}

function Invoke-Build {
    $env:JAVA_HOME = $jdk
    & $maven -q -DskipTests test-compile
    if ($LASTEXITCODE -ne 0) { throw "Maven test-compile failed: $LASTEXITCODE" }
}

function Invoke-Internal {
    $output = Join-Path $runRoot 'internal_results.csv'
    $options = @('-Dv3.preprocessingArms=native,common_normalized', '-Dv3.includeScheduler=true')
    if ($OnlySource) { $options += "-Dv3.onlySource=$OnlySource" }
    if ($OnlyCondition) { $options += "-Dv3.onlyCondition=$OnlyCondition" }
    if ($OnlyMotion) { $options += "-Dv3.onlyMotion=$OnlyMotion" }
    if ($Rewrite) { $options += '-Dv3.rewrite=true' }
    Write-Status "START internal split=$Split"
    & $java @options -cp (Java-Classpath) ripr.BenchmarkV3 internal $project $Split $output
    if ($LASTEXITCODE -ne 0) { throw "internal frozen run failed: $LASTEXITCODE" }
    Write-Status "COMPLETE internal split=$Split"
}

function Invoke-PythonDefaults {
    $output = Join-Path $runRoot 'python_default_results.csv'
    $methods = @('33_skimage_phase_cross_correlation', '34_opencv_phase_correlate',
        '35_opencv_ecc_translation', '36_simpleitk_meansquares',
        '37_simpleitk_correlation', '38_simpleitk_mattes_mi', '39_suite2p_rigid',
        '43_pystackreg_translation')
    $first = $true
    foreach ($method in $methods) {
        $arguments = Common-PythonArguments (Join-Path $project 'scripts\benchmark_v3_python.py') $output 'native,common_normalized'
        $arguments.Add('--methods'); $arguments.Add($method)
        if ($Rewrite -and $first) { $arguments.Add('--rewrite') }
        Write-Status "START python default method=$method"
        & $python @arguments
        if ($LASTEXITCODE -ne 0) { throw "Python default failed: $method" }
        $first = $false
        Write-Status "COMPLETE python default method=$method"
    }
}

function Invoke-PythonTuned {
    $output = Join-Path $runRoot 'python_tuned_results.csv'
    $grid = @{}; foreach ($row in (Import-Csv $pythonGridPath)) { $grid[$row.config_id] = $row }
    $first = $true
    foreach ($winner in ($winners | Where-Object runner -eq 'python')) {
        $row = $grid[$winner.config_id]
        if ($null -eq $row) { throw "Missing Python grid row $($winner.config_id)" }
        $arguments = Common-PythonArguments (Join-Path $project 'scripts\benchmark_v3_python.py') $output $winner.preprocessing_arm
        foreach ($value in @('--methods', $winner.method_id, '--config-id', $winner.config_id)) {
            $arguments.Add([string]$value)
        }
        Add-Optional $arguments '--skimage-upsample' $row.skimage_upsample
        Add-Optional $arguments '--skimage-normalization' $row.skimage_normalization
        Add-Optional $arguments '--opencv-window' $row.opencv_window
        Add-Optional $arguments '--ecc-iterations' $row.ecc_iterations
        Add-Optional $arguments '--ecc-epsilon' $row.ecc_epsilon
        Add-Optional $arguments '--ecc-gaussian' $row.ecc_gaussian
        Add-Optional $arguments '--sitk-optimizer' $row.sitk_optimizer
        Add-Optional $arguments '--sitk-iterations' $row.sitk_iterations
        Add-Optional $arguments '--suite-maxregshift' $row.suite_maxregshift
        Add-Optional $arguments '--suite-smooth-sigma' $row.suite_smooth_sigma
        Add-Optional $arguments '--suite-smooth-sigma-time' $row.suite_smooth_sigma_time
        if ($Rewrite -and $first) { $arguments.Add('--rewrite') }
        Write-Status "START python tuned method=$($winner.method_id) arm=$($winner.preprocessing_arm) config=$($winner.config_id)"
        & $python @arguments
        if ($LASTEXITCODE -ne 0) { throw "Python tuned failed: $($winner.method_id)" }
        $first = $false
        Write-Status "COMPLETE python tuned method=$($winner.method_id) arm=$($winner.preprocessing_arm)"
    }
}

function Invoke-JNormDefaults {
    $output = Join-Path $runRoot 'jnormcorre_default_results.csv'
    $arguments = Common-PythonArguments (Join-Path $project 'scripts\benchmark_v3_jnormcorre.py') $output 'native,common_normalized'
    if ($Rewrite) { $arguments.Add('--rewrite') }
    & $jnormPython @arguments
    if ($LASTEXITCODE -ne 0) { throw 'JNormCorre default run failed' }
}

function Invoke-JNormTuned {
    $output = Join-Path $runRoot 'jnormcorre_tuned_results.csv'
    $grid = @{}; foreach ($row in (Import-Csv $jnormGridPath)) { $grid[$row.config_id] = $row }
    $first = $true
    foreach ($winner in ($winners | Where-Object runner -eq 'jnormcorre')) {
        $row = $grid[$winner.config_id]
        $arguments = Common-PythonArguments (Join-Path $project 'scripts\benchmark_v3_jnormcorre.py') $output $winner.preprocessing_arm
        foreach ($value in @('--config-id', $winner.config_id, '--max-shift', $row.max_shift,
                '--iterations', $row.iterations, '--frames-per-split', $row.frames_per_split)) {
            $arguments.Add([string]$value)
        }
        if ($Rewrite -and $first) { $arguments.Add('--rewrite') }
        & $jnormPython @arguments
        if ($LASTEXITCODE -ne 0) { throw "JNormCorre tuned run failed: $($winner.config_id)" }
        $first = $false
    }
}

function Invoke-MocoOne([string]$Output, [string]$Arm, [string]$Config,
                        [string]$Downsample, [string]$Window, [bool]$First) {
    $options = @("-Dmoco.preprocessingArms=$Arm", "-Dmoco.configId=$Config",
        "-Dmoco.downsample=$Downsample", "-Dmoco.windowFraction=$Window")
    if ($OnlySource) { $options += "-Dmoco.onlySource=$OnlySource" }
    if ($OnlyCondition) { $options += "-Dmoco.onlyCondition=$OnlyCondition" }
    if ($OnlyMotion) { $options += "-Dmoco.onlyMotion=$OnlyMotion" }
    if ($Rewrite -and $First) { $options += '-Dmoco.rewrite=true' }
    & $java @options -cp (Moco-Classpath) ripr.MocoBenchmarkV3 $project $Split $Output
    if ($LASTEXITCODE -ne 0) { throw "Moco run failed: $Config/$Arm" }
}

function Invoke-MocoDefaults {
    Invoke-MocoOne (Join-Path $runRoot 'moco_default_results.csv') `
        'native,common_normalized' 'moco_ds1_w0.2_default' '1' '0.2' $true
}

function Invoke-MocoTuned {
    $output = Join-Path $runRoot 'moco_tuned_results.csv'
    $grid = @{}; foreach ($row in (Import-Csv $mocoGridPath)) { $grid[$row.config_id] = $row }
    $first = $true
    foreach ($winner in ($winners | Where-Object runner -eq 'moco')) {
        $row = $grid[$winner.config_id]
        Invoke-MocoOne $output $winner.preprocessing_arm $winner.config_id `
            $row.downsample $row.window_fraction $first
        $first = $false
    }
}

function Invoke-ExternalOne([string]$Config, [string]$Arm) {
    $parameters = @{
        ProjectRoot = $project; Dataset = "v3_$Split"; Config = $Config
        PreprocessingArm = $Arm
        RunRootOverride = 'library\benchmark\v3\runs\external_parameter_sweep_v1'
        FullEngineRows = $true; Resume = $true
    }
    if ($OnlySource) { $parameters['OnlySeries'] = $OnlySource }
    if ($OnlyCondition) { $parameters['OnlyCondition'] = $OnlyCondition }
    if ($OnlyMotion) { $parameters['OnlyMotion'] = $OnlyMotion }
    if ($Rewrite) { $parameters['Rewrite'] = $true }
    Write-Status "START external config=$Config arm=$Arm split=$Split"
    & (Join-Path $project 'scripts\run_external_parameter_sweep.ps1') @parameters
    if ($LASTEXITCODE -ne 0) { throw "External run failed: $Config/$Arm" }
    Write-Status "COMPLETE external config=$Config arm=$Arm split=$Split"
}

function Invoke-External {
    if ($View -in @('defaults', 'all')) {
        Invoke-ExternalOne 'all_defaults' 'native'
        Invoke-ExternalOne 'all_defaults' 'common_normalized'
    }
    if ($View -in @('tuned', 'all')) {
        $seen = @{}
        foreach ($winner in ($winners | Where-Object runner -eq 'external')) {
            $key = "$($winner.config_id)|$($winner.preprocessing_arm)"
            if ($seen.ContainsKey($key)) { continue }
            $seen[$key] = $true
            Invoke-ExternalOne $winner.config_id $winner.preprocessing_arm
        }
    }
}

Push-Location $project
try {
    Invoke-Build
    if ($Stage -in @('internal', 'all')) { Invoke-Internal }
    if ($Stage -in @('python', 'all')) {
        if ($View -in @('defaults', 'all')) { Invoke-PythonDefaults }
        if ($View -in @('tuned', 'all')) { Invoke-PythonTuned }
    }
    if ($Stage -in @('jnormcorre', 'all')) {
        if ($View -in @('defaults', 'all')) { Invoke-JNormDefaults }
        if ($View -in @('tuned', 'all')) { Invoke-JNormTuned }
    }
    if ($Stage -in @('moco', 'all')) {
        if ($View -in @('defaults', 'all')) { Invoke-MocoDefaults }
        if ($View -in @('tuned', 'all')) { Invoke-MocoTuned }
    }
    if ($Stage -in @('external', 'all')) { Invoke-External }
    Write-Status "DONE frozen methods split=$Split view=$View stage=$Stage"
} catch {
    Write-Status "FAILED frozen methods split=$Split view=$View stage=$Stage error=$($_.Exception.Message)"
    throw
} finally {
    Pop-Location
}
