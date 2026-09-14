param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [ValidateSet('bootstrap', 'generate', 'internal', 'python', 'jnormcorre', 'moco', 'external', 'all')]
    [string]$Stage = 'all',
    [ValidateSet('development', 'integration', 'publication_test')]
    [string]$Split = 'development',
    [string]$OnlySource = '',
    [string]$OnlyCondition = '',
    [string]$OnlyMotion = '',
    [string]$OnlyMethod = '',
    [string]$PythonMethods = '',
    [string]$ExternalConfigs = 'all_defaults',
    [ValidateSet('native', 'common_normalized', 'both')]
    [string]$Preprocessing = 'both',
    [ValidateSet('universal', 'dose', 'all')]
    [string]$ConditionPanel = 'universal',
    [int]$Replicates = 0,
    [switch]$IncludeScheduler,
    [switch]$Rewrite,
    [switch]$ResetManifest,
    [switch]$Detach
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path -LiteralPath $ProjectRoot).Path
$runRoot = Join-Path $project "library\benchmark\v3\runs\$Split"
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
$statusLog = Join-Path $runRoot 'orchestration.log'

function Write-Status([string]$Message) {
    $line = "$(Get-Date -Format o) $Message"
    for ($attempt = 1; $attempt -le 40; $attempt++) {
        try {
            $line | Add-Content -LiteralPath $statusLog
            break
        } catch [IO.IOException] {
            if ($attempt -eq 40) { throw }
            Start-Sleep -Milliseconds 250
        }
    }
    Write-Host $line
}

if ($Detach) {
    $stamp = "{0}_{1}" -f (Get-Date).ToString('yyyyMMdd_HHmmss_fff'),
        ([Guid]::NewGuid().ToString('N').Substring(0, 8))
    $stdout = Join-Path $runRoot "detached_$stamp.stdout.log"
    $stderr = $stdout.Replace('.stdout.log', '.stderr.log')
    function Quote-CommandValue([string]$Value) { return "'$($Value.Replace("'", "''"))'" }
    $command = "& $(Quote-CommandValue $PSCommandPath) -ProjectRoot $(Quote-CommandValue $project)" +
        " -Stage $(Quote-CommandValue $Stage) -Split $(Quote-CommandValue $Split)" +
        " -ExternalConfigs $(Quote-CommandValue $ExternalConfigs)" +
        " -Preprocessing $(Quote-CommandValue $Preprocessing)" +
        " -ConditionPanel $(Quote-CommandValue $ConditionPanel)"
    foreach ($pair in @(@('-OnlySource', $OnlySource), @('-OnlyCondition', $OnlyCondition),
            @('-OnlyMotion', $OnlyMotion), @('-OnlyMethod', $OnlyMethod),
            @('-PythonMethods', $PythonMethods))) {
        if ($pair[1]) { $command += " $($pair[0]) $(Quote-CommandValue $pair[1])" }
    }
    if ($Replicates -gt 0) { $command += " -Replicates $Replicates" }
    if ($IncludeScheduler) { $command += ' -IncludeScheduler' }
    if ($Rewrite) { $command += ' -Rewrite' }
    if ($ResetManifest) { $command += ' -ResetManifest' }
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $process = Start-Process -FilePath 'powershell.exe' `
        -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $encoded) `
        -WorkingDirectory $project -WindowStyle Hidden -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr -PassThru
    Write-Status "DETACHED pid=$($process.Id) stage=$Stage split=$Split stdout=$stdout stderr=$stderr"
    return
}

if ($Split -eq 'publication_test') {
    $freeze = Join-Path $project 'library\benchmark\v3\protocol\PROTOCOL_FROZEN.txt'
    if (!(Test-Path -LiteralPath $freeze)) {
        throw 'Publication-test execution is locked until protocol/PROTOCOL_FROZEN.txt exists.'
    }
}

$toolRoot = Join-Path $env:LOCALAPPDATA 'LogRatioBenchmarkV3\tools'
# REGRESSION GUARD: Maven and the dependency classpath used to live below target/, so an
# unrelated Maven clean removed them between tuning and integration. Keep the toolchain durable.
$maven = Join-Path $toolRoot 'apache-maven-3.9.9\bin\mvn.cmd'
$jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { throw 'Set JAVA_HOME to the Java Development Kit folder.' }
$java = Join-Path $jdk 'bin\java.exe'
$python = Join-Path $env:LOCALAPPDATA 'LogRatioBenchmarkV3\venv\Scripts\python.exe'
$jnormPython = Join-Path $env:LOCALAPPDATA 'LogRatioBenchmarkV3\jnormcorre-venv\Scripts\python.exe'
$classpathFile = Join-Path $toolRoot 'test-classpath.txt'
foreach ($required in @($maven, $java, $python, $jnormPython, $classpathFile)) {
    if (!(Test-Path -LiteralPath $required)) { throw "Missing benchmark dependency: $required" }
}

function Invoke-Build {
    Write-Status 'START build and conformance'
    $env:JAVA_HOME = $jdk
    & $maven -q -DskipTests test-compile
    if ($LASTEXITCODE -ne 0) { throw "Maven test-compile failed: $LASTEXITCODE" }
    $classpath = "target\classes;target\test-classes;$((Get-Content $classpathFile -Raw).Trim())"
    & $java -cp $classpath ripr.BenchmarkV3 --self-test
    if ($LASTEXITCODE -ne 0) { throw 'BenchmarkV3 self-test failed' }
    & $java -cp $classpath ripr.ExternalPluginComparisonStacks --self-test
    if ($LASTEXITCODE -ne 0) { throw 'external adapter self-test failed' }
    $report = Join-Path $project 'library\benchmark\v3\comparators\python\conformance.csv'
    & $python (Join-Path $project 'scripts\benchmark_v3_python.py') --self-test `
        --conformance-report $report
    if ($LASTEXITCODE -ne 0) { throw 'Python comparator conformance failed' }
    $suiteReport = Join-Path $project 'library\benchmark\v3\comparators\python\conformance_suite2p.csv'
    & $python (Join-Path $project 'scripts\benchmark_v3_python.py') --self-test `
        --methods 39_suite2p_rigid --conformance-report $suiteReport
    if ($LASTEXITCODE -ne 0) { throw 'Suite2p conformance failed' }
    $jnormReport = Join-Path $project 'library\benchmark\v3\comparators\jnormcorre\conformance.csv'
    & $jnormPython (Join-Path $project 'scripts\benchmark_v3_jnormcorre.py') --self-test `
        --conformance-report $jnormReport
    if ($LASTEXITCODE -ne 0) { throw 'JNormCorre conformance failed' }
    $mocoReport = Join-Path $project 'library\benchmark\v3\comparators\moco\conformance.csv'
    & $java -cp (Moco-Classpath) ripr.MocoBenchmarkV3 --self-test $mocoReport
    if ($LASTEXITCODE -ne 0) { throw 'Moco conformance failed' }
    Write-Status 'COMPLETE build and conformance'
}

function Java-Classpath {
    return "target\classes;target\test-classes;$((Get-Content $classpathFile -Raw).Trim())"
}

function Moco-Classpath {
    $root = Join-Path $project 'library\benchmark\v3\comparators\moco\source\03-18-2016_release'
    return "$(Java-Classpath);$(Join-Path $root 'moco_.jar');$(Join-Path $root 'jars\*')"
}

function Invoke-Generate {
    Write-Status "START generate split=$Split panel=$ConditionPanel"
    $options = @("-Dv3.conditionPanel=$ConditionPanel")
    if ($OnlySource) { $options += "-Dv3.onlySource=$OnlySource" }
    if ($OnlyCondition) { $options += "-Dv3.onlyCondition=$OnlyCondition" }
    if ($OnlyMotion) { $options += "-Dv3.onlyMotion=$OnlyMotion" }
    if ($Replicates -gt 0) { $options += "-Dv3.replicates=$Replicates" }
    if ($Rewrite) { $options += '-Dv3.rewrite=true' }
    if ($ResetManifest) { $options += '-Dv3.resetManifest=true' }
    & $java @options -cp (Java-Classpath) ripr.BenchmarkV3 generate $project $Split
    if ($LASTEXITCODE -ne 0) { throw "generation failed: $LASTEXITCODE" }
    Write-Status "COMPLETE generate split=$Split"
}

function Invoke-Internal {
    $output = Join-Path $runRoot 'internal_results.csv'
    Write-Status "START internal split=$Split output=$output"
    $arms = if ($Preprocessing -eq 'both') { 'native,common_normalized' } else { $Preprocessing }
    $options = @("-Dv3.preprocessingArms=$arms")
    if ($OnlySource) { $options += "-Dv3.onlySource=$OnlySource" }
    if ($OnlyCondition) { $options += "-Dv3.onlyCondition=$OnlyCondition" }
    if ($OnlyMethod) { $options += "-Dv3.onlyMethod=$OnlyMethod" }
    if ($IncludeScheduler) { $options += '-Dv3.includeScheduler=true' }
    if ($Rewrite) { $options += '-Dv3.rewrite=true' }
    & $java @options -cp (Java-Classpath) ripr.BenchmarkV3 internal $project $Split $output
    if ($LASTEXITCODE -ne 0) { throw "internal runner failed: $LASTEXITCODE" }
    Write-Status "COMPLETE internal split=$Split output=$output"
}

function Invoke-PythonComparators {
    $output = Join-Path $runRoot 'python_results.csv'
    $allMethods = if ($PythonMethods) { $PythonMethods -split ',' } else {
        @('33_skimage_phase_cross_correlation', '34_opencv_phase_correlate',
          '35_opencv_ecc_translation', '36_simpleitk_meansquares',
          '37_simpleitk_correlation', '38_simpleitk_mattes_mi',
          '39_suite2p_rigid', '43_pystackreg_translation')
    }
    $arms = if ($Preprocessing -eq 'both') { 'native,common_normalized' } else { $Preprocessing }
    $first = $true
    foreach ($method in $allMethods) {
        Write-Status "START python split=$Split method=$method"
        $arguments = @((Join-Path $project 'scripts\benchmark_v3_python.py'), '--project', $project,
            '--split', $Split, '--output', $output, '--methods', $method,
            '--preprocessing-arms', $arms)
        if ($OnlySource) { $arguments += @('--only-source', $OnlySource) }
        if ($OnlyCondition) { $arguments += @('--only-condition', $OnlyCondition) }
        if ($OnlyMotion) { $arguments += @('--only-motion', $OnlyMotion) }
        if ($Rewrite -and $first) { $arguments += '--rewrite' }
        & $python @arguments
        if ($LASTEXITCODE -ne 0) { throw "Python method $method failed: $LASTEXITCODE" }
        $first = $false
        Write-Status "COMPLETE python split=$Split method=$method"
    }
}

function Invoke-ExternalComparators {
    $externalScript = Join-Path $project 'scripts\run_external_parameter_sweep.ps1'
    $manifest = Join-Path $project "library\benchmark\v3\inputs\$Split\inputs_manifest.csv"
    if (!(Test-Path -LiteralPath $manifest)) { throw "Missing input manifest: $manifest" }
    $sources = if ($OnlySource) { $OnlySource -split ',' } else {
        Import-Csv -LiteralPath $manifest | Select-Object -ExpandProperty series_id -Unique
    }
    $arms = if ($Preprocessing -eq 'both') { @('native', 'common_normalized') } else { @($Preprocessing) }
    foreach ($config in ($ExternalConfigs -split ',')) {
        foreach ($arm in $arms) {
            foreach ($source in $sources) {
                Write-Status "START external split=$Split config=$config arm=$arm source=$source"
                $parameters = @{
                    ProjectRoot = $project
                    Dataset = "v3_$Split"
                    Config = $config
                    OnlySeries = $source
                    OnlyCondition = $OnlyCondition
                    OnlyMotion = $OnlyMotion
                    PreprocessingArm = $arm
                    RunRootOverride = 'library\benchmark\v3\runs\external_parameter_sweep_v1'
                    FullEngineRows = $true
                    Resume = $true
                }
                if ($Rewrite) { $parameters['Rewrite'] = $true }
                & $externalScript @parameters
                if ($LASTEXITCODE -ne 0) { throw "external $config/$arm/$source failed" }
                Write-Status "COMPLETE external split=$Split config=$config arm=$arm source=$source"
            }
        }
    }
}

function Invoke-JNormCorre {
    $output = Join-Path $runRoot 'jnormcorre_results.csv'
    $arms = if ($Preprocessing -eq 'both') { 'native,common_normalized' } else { $Preprocessing }
    Write-Status "START jnormcorre split=$Split"
    $arguments = @((Join-Path $project 'scripts\benchmark_v3_jnormcorre.py'),
        '--project', $project, '--split', $Split, '--output', $output,
        '--preprocessing-arms', $arms)
    if ($OnlySource) { $arguments += @('--only-source', $OnlySource) }
    if ($OnlyCondition) { $arguments += @('--only-condition', $OnlyCondition) }
    if ($OnlyMotion) { $arguments += @('--only-motion', $OnlyMotion) }
    if ($Rewrite) { $arguments += '--rewrite' }
    & $jnormPython @arguments
    if ($LASTEXITCODE -ne 0) { throw "JNormCorre failed: $LASTEXITCODE" }
    Write-Status "COMPLETE jnormcorre split=$Split"
}

function Invoke-Moco {
    $output = Join-Path $runRoot 'moco_results.csv'
    $arms = if ($Preprocessing -eq 'both') { 'native,common_normalized' } else { $Preprocessing }
    $options = @("-Dmoco.preprocessingArms=$arms")
    if ($OnlySource) { $options += "-Dmoco.onlySource=$OnlySource" }
    if ($OnlyCondition) { $options += "-Dmoco.onlyCondition=$OnlyCondition" }
    if ($OnlyMotion) { $options += "-Dmoco.onlyMotion=$OnlyMotion" }
    if ($Rewrite) { $options += '-Dmoco.rewrite=true' }
    Write-Status "START moco split=$Split"
    & $java @options -cp (Moco-Classpath) ripr.MocoBenchmarkV3 $project $Split $output
    if ($LASTEXITCODE -ne 0) { throw "Moco failed: $LASTEXITCODE" }
    Write-Status "COMPLETE moco split=$Split"
}

Push-Location -LiteralPath $project
try {
    if ($Stage -in @('bootstrap', 'all')) { Invoke-Build }
    if ($Stage -in @('generate', 'all')) { Invoke-Generate }
    if ($Stage -in @('internal', 'all')) { Invoke-Internal }
    if ($Stage -in @('python', 'all')) { Invoke-PythonComparators }
    if ($Stage -in @('jnormcorre', 'all')) { Invoke-JNormCorre }
    if ($Stage -in @('moco', 'all')) { Invoke-Moco }
    if ($Stage -in @('external', 'all')) { Invoke-ExternalComparators }
    Write-Status "DONE stage=$Stage split=$Split"
} catch {
    Write-Status "FAILED stage=$Stage split=$Split error=$($_.Exception.Message)"
    throw
} finally {
    Pop-Location
}
