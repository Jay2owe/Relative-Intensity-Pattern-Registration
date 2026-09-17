param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [ValidateSet('development', 'locked')]
    [string]$Dataset = 'development',
    [string]$Config = 'all',
    [string]$OnlySeries = '',
    [string]$OnlyImageClass = '',
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
$runRoot = Join-Path $project 'library\benchmark\v2\runs\external_parameter_sweep_v1'
$dataRoot = if ($Dataset -eq 'development') {
    Join-Path $project 'library\benchmark\v2\benchmarks\controlled_motion'
} else {
    Join-Path $project 'library\benchmark\v2\benchmarks\locked_test'
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
    'sift_epsilon_1',
    'sift_epsilon_3',
    'sift_epsilon_10',
    'sift_min_inliers_4',
    'sift_min_inliers_8',
    'sift_finer_octave',
    'descriptor_threshold_0_008',
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
$rewriteOption = if ($Rewrite) { '-Dlogratio.rewrite=true' } else { '-Dlogratio.rewrite=false' }
$fullEngineOption = if ($FullEngineRows) { '-Dlogratio.fullEngineRows=true' } else { '-Dlogratio.fullEngineRows=false' }
$status = Join-Path $runRoot 'stage_status.log'

foreach ($configId in $configs) {
    $suffix = @($configId, $OnlyImageClass, $OnlySeries) |
        Where-Object { $_ -ne '' } |
        ForEach-Object { $_ -replace '[^A-Za-z0-9_.-]', '_' }
    $stem = $suffix -join '__'
    $output = Join-Path $outputRoot "$stem.csv"
    $stdout = Join-Path $outputRoot "$stem.stdout.log"
    $stderr = Join-Path $outputRoot "$stem.stderr.log"
    if ($Resume -and (Test-Path -LiteralPath $output) -and
            (Select-String -LiteralPath $status -SimpleMatch "COMPLETE $Dataset $stem " -Quiet)) {
        "SKIP completed $Dataset $stem $(Get-Date -Format o)" | Add-Content -LiteralPath $status
        continue
    }
    "START $Dataset $stem $(Get-Date -Format o)" | Add-Content -LiteralPath $status

    $parameters = @(
        "project='$($project.Replace("'", "''"))'",
        "mode='sweep'",
        "series='$($OnlySeries.Replace("'", "''"))'",
        "root='$($dataRoot.Replace("'", "''"))'",
        "output='$($output.Replace("'", "''"))'",
        "config='$configId'",
        "imageClass='$($OnlyImageClass.Replace("'", "''"))'"
    ) -join ','
    $arguments = @(
        '-Xmx45g', $rewriteOption, $fullEngineOption,
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
        "FAILED $Dataset $stem $(Get-Date -Format o) exit=$exitCode" |
            Add-Content -LiteralPath $status
        throw "$stem failed; inspect $stderr"
    }
    "COMPLETE $Dataset $stem $(Get-Date -Format o)" | Add-Content -LiteralPath $status
}
