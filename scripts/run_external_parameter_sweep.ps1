param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [ValidateSet('development', 'locked', 'publication_development_translation',
        'publication_development_rigid', 'publication_final_translation',
        'publication_final_rigid', 'publication_final_gain_fade_translation',
        'publication_final_gain_fade_rigid', 'v3_development', 'v3_integration',
        'v3_publication_test')]
    [string]$Dataset = 'development',
    [string]$Config = 'all',
    [string]$OnlySeries = '',
    [string]$OnlyCondition = '',
    [string]$OnlyMotion = '',
    [string]$OnlyImageClass = '',
    [string]$OnlyMethod = '',
    [ValidateSet('native', 'common_normalized')]
    [string]$PreprocessingArm = 'native',
    [string]$RootOverride = '',
    [string]$RunRootOverride = '',
    [string]$ClassesOverride = '',
    [ValidateRange(2, 45)]
    [int]$MaxHeapGb = 45,
    [switch]$FullEngineRows,
    [switch]$Resume,
    [switch]$Rewrite
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path -LiteralPath $ProjectRoot).Path
$jamieRoot = Split-Path -Parent (Split-Path -Parent $project)
$fijiRoot = Join-Path $jamieRoot 'Fiji.app'
$fijiJava = Join-Path $fijiRoot 'java\win64\zulu11.88.17-ca-fx-jdk11.0.31-win_x64\bin\java.exe'
$launcherJar = Join-Path $fijiRoot 'jars\imagej-launcher-6.0.2.jar'
$script = Join-Path $project 'library\benchmark\run_external_full_no_dialog.groovy'
$runRoot = if ($RunRootOverride) {
    if ([IO.Path]::IsPathRooted($RunRootOverride)) {
        [IO.Path]::GetFullPath($RunRootOverride)
    } else {
        [IO.Path]::GetFullPath((Join-Path $project $RunRootOverride))
    }
} elseif ($Dataset.StartsWith('v3_')) {
    Join-Path $project 'library\benchmark\v3\runs\external_parameter_sweep_v1'
} else {
    Join-Path $project 'library\benchmark\v2\runs\external_parameter_sweep_v1'
}
$dataRoot = if ($RootOverride) {
    if ([IO.Path]::IsPathRooted($RootOverride)) {
        [IO.Path]::GetFullPath($RootOverride)
    } else {
        [IO.Path]::GetFullPath((Join-Path $project $RootOverride))
    }
} elseif ($Dataset -eq 'development') {
    Join-Path $project 'library\benchmark\v2\benchmarks\controlled_motion'
} elseif ($Dataset -eq 'locked') {
    Join-Path $project 'library\benchmark\v2\benchmarks\locked_test'
} else {
    $split = $Dataset.Substring(3)
    Join-Path $project "library\benchmark\v3\inputs\$split"
}
$outputRoot = Join-Path $runRoot $Dataset

foreach ($required in @($fijiJava, $launcherJar, $script, $dataRoot)) {
    if (!(Test-Path -LiteralPath $required)) { throw "Missing required path: $required" }
}
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null

$configs = @(
    'all_defaults',
    'all_defaults_replay',
    'turboreg_default_rigid',
    'turboreg_translation',
    'stabilizer_default',
    'stabilizer_pyramid_2',
    'stabilizer_pyramid_3',
    'stabilizer_pyramid_4',
    'stabilizer_pinned_first',
    'fast4d_peak_optimized_default',
    'fast4d_peak_centroid',
    'fast4d_peak_pixel',
    'correct3d_peaks_5_default',
    'correct3d_peaks_10',
    'sift_default',
    'sift_translation',
    'sift_epsilon_1',
    'sift_epsilon_3',
    'sift_epsilon_10',
    'sift_min_inliers_4',
    'sift_min_inliers_8',
    'sift_finer_octave',
    'descriptor_threshold_0_008',
    'descriptor_translation',
    'descriptor_threshold_0_03_default',
    'descriptor_threshold_0_1'
)
if ($Config -ne 'all') {
    if ($configs -notcontains $Config) { throw "Unknown config: $Config" }
    $configs = @($Config)
}

$nativeLibraries = @(
    (Join-Path $fijiRoot 'lib\win64'),
    (Join-Path $fijiRoot 'mm\win64'),
    (Join-Path $fijiRoot 'lib\fftw'),
    (Join-Path $fijiRoot 'lib\jcuda')
) -join ';'
$rewriteOption = if ($Rewrite) { '-Dripr.rewrite=true' } else { '-Dripr.rewrite=false' }
$fullEngineOption = if ($FullEngineRows) { '-Dripr.fullEngineRows=true' } else { '-Dripr.fullEngineRows=false' }
# Keep status bookkeeping beside each dataset's outputs. A single run-root log is
# prone to Dropbox/file-lock contention when independent benchmark arms overlap.
$status = Join-Path $outputRoot 'stage_status.log'

function Write-StageStatus([string]$Message) {
    # REGRESSION GUARD: a transient Dropbox lock on this bookkeeping file aborted valid benchmark runs.
    # The fix: retry status writes and warn after exhaustion; CSV/JVM failures remain hard errors.
    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        try {
            $Message | Add-Content -LiteralPath $status -ErrorAction Stop
            return
        } catch [System.IO.IOException] {
            Start-Sleep -Milliseconds 200
        }
    }
    Write-Warning "Could not update status log after retries: $Message"
}

function Filter-Token([string]$Name, [string]$Value) {
    if (!$Value) { return '' }
    $safe = $Value -replace '[^A-Za-z0-9_.-]', '_'
    if ($Name -ne 'filter' -and $safe.Length -le 48) { return $safe }
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        $digest = $algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value))
    } finally {
        $algorithm.Dispose()
    }
    $hex = -join ($digest | ForEach-Object { $_.ToString('x2') })
    return "$Name-$($hex.Substring(0, 12))"
}

foreach ($configId in $configs) {
    $hasFilter = $OnlyImageClass -or $OnlySeries -or $OnlyCondition -or $OnlyMotion -or $OnlyMethod
    $filterSpec = @($OnlyImageClass, $OnlySeries, $OnlyCondition, $OnlyMotion, $OnlyMethod) -join '|'
    $filterToken = if ($hasFilter) { Filter-Token 'filter' $filterSpec } else { '' }
    $suffix = @($configId, $PreprocessingArm, $filterToken) |
        Where-Object { $_ -ne '' } |
        ForEach-Object { $_ -replace '[^A-Za-z0-9_.-]', '_' }
    $stem = $suffix -join '__'
    $output = Join-Path $outputRoot "$stem.csv"
    $stdout = Join-Path $outputRoot "$stem.stdout.log"
    $stderr = Join-Path $outputRoot "$stem.stderr.log"
    if ($Resume -and (Test-Path -LiteralPath $output) -and (Test-Path -LiteralPath $status) -and
            (Select-String -LiteralPath $status -SimpleMatch "COMPLETE $Dataset $stem " -Quiet)) {
        Write-StageStatus "SKIP completed $Dataset $stem $(Get-Date -Format o)"
        continue
    }
    if ($Resume -and (Test-Path -LiteralPath $output)) {
        # REGRESSION GUARD: a stopped JVM leaves a valid-looking but incomplete CSV.
        # The fix: preserve it outside *.csv discovery, then replay the complete configuration.
        $resolvedRoot = [IO.Path]::GetFullPath($outputRoot).TrimEnd(
            [IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
        $resolvedOutput = [IO.Path]::GetFullPath($output)
        if (!$resolvedOutput.StartsWith($resolvedRoot, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to archive partial output outside run root: $resolvedOutput"
        }
        $partialRoot = Join-Path $outputRoot '_partial'
        New-Item -ItemType Directory -Path $partialRoot -Force | Out-Null
        $partial = Join-Path $partialRoot "$configId`_$(Get-Date -Format 'yyyyMMdd_HHmmss_fff').part"
        Move-Item -LiteralPath $resolvedOutput -Destination $partial
        Write-StageStatus "ARCHIVE partial $Dataset $stem path=$partial $(Get-Date -Format o)"
    }
    Write-StageStatus "START $Dataset $stem $(Get-Date -Format o) series=$OnlySeries condition=$OnlyCondition motion=$OnlyMotion"

    $parameters = @(
        "project='$($project.Replace("'", "''"))'",
        "mode='sweep'",
        "series='$($OnlySeries.Replace("'", "''"))'",
        "root='$($dataRoot.Replace("'", "''"))'",
        "output='$($output.Replace("'", "''"))'",
        "config='$configId'",
        "imageClass='$($OnlyImageClass.Replace("'", "''"))'",
        "condition='$($OnlyCondition.Replace("'", "''"))'",
        "motion='$($OnlyMotion.Replace("'", "''"))'",
        "method='$($OnlyMethod.Replace("'", "''"))'",
        "preprocessing='$PreprocessingArm'",
        "classes='$($ClassesOverride.Replace("'", "''"))'"
    ) -join ','
    $arguments = @(
        "-Xmx$($MaxHeapGb)g", $rewriteOption, $fullEngineOption,
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
        '--headless',
        '--run', $script, $parameters
    )
    Push-Location -LiteralPath $fijiRoot
    try {
        $previousErrorAction = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            & $fijiJava @arguments 1> $stdout 2> $stderr
            $exitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousErrorAction
        }
    } finally {
        Pop-Location
    }
    if ($exitCode -ne 0) {
        Write-StageStatus "FAILED $Dataset $stem $(Get-Date -Format o) exit=$exitCode"
        throw "$stem failed; inspect $stderr"
    }
    Write-StageStatus "COMPLETE $Dataset $stem $(Get-Date -Format o)"
}
