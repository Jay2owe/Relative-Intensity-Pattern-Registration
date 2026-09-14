param(
    [ValidateSet('generate', 'run', 'joint', 'translation', 'confidence', 'audit')]
    [string]$Stage = 'run',
    [ValidateSet('development', 'validation', 'locked')]
    [string]$Split = 'development',
    [string]$RunId = 'r00_full_factorial',
    [string]$OnlySeries = '',
    [string]$OnlyMotion = '',
    [string]$OnlyCondition = '',
    [string]$OnlyRecipe = '',
    [int]$MaxFrames = 0,
    [string]$RotationGains = '',
    [string]$ConfidenceRecipe = '',
    [ValidateSet('PHASE', 'DENSE_FLUOR')]
    [string]$ImageClass = 'PHASE',
    [switch]$AllowFrozenPolicy,
    [switch]$IncrementalRotation
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$root = Join-Path $project 'library\rigid_selector_tuning\fresh_recovery\pair_benchmark'
$classpathFile = Join-Path $root 'test-classpath.txt'
New-Item -ItemType Directory -Force -Path $root | Out-Null

Push-Location $project
try {
    & mvn '-q' '-DskipTests' 'test-compile' 'dependency:build-classpath' `
        "-Dmdep.outputFile=$classpathFile" '-Dmdep.includeScope=test'
    if ($LASTEXITCODE -ne 0) { throw 'Maven test compilation failed' }
    $classpath = "target\classes;target\test-classes;$((Get-Content -LiteralPath $classpathFile -Raw).Trim())"
    $java = @('-Xmx6g', '-Djava.awt.headless=true', "-Drigid.run=$RunId",
        "-Drigid.pair.imageClass=$ImageClass")
    if ($OnlySeries) { $java += "-Drigid.onlySeries=$OnlySeries" }
    if ($OnlyMotion) { $java += "-Drigid.onlyMotion=$OnlyMotion" }
    if ($OnlyCondition) { $java += "-Drigid.onlyCondition=$OnlyCondition" }
    if ($OnlyRecipe) { $java += "-Drigid.onlyRecipe=$OnlyRecipe" }
    if ($MaxFrames -gt 0) { $java += "-Drigid.pair.maxFrames=$MaxFrames" }
    if ($RotationGains) { $java += "-Drigid.rotationGains=$RotationGains" }
    if ($ConfidenceRecipe) { $java += "-Drigid.confidenceRecipe=$ConfidenceRecipe" }
    if ($AllowFrozenPolicy) { $java += '-Drigid.allowFrozenPolicy=true' }
    if ($IncrementalRotation) { $java += '-Drigid.incrementalRotation=true' }
    if ($Stage -eq 'joint') { $java += '-Drigid.onlyArm=JOINT_RIGID' }
    if ($Stage -eq 'translation') { $java += '-Drigid.onlyArm=TRANSLATION_ONLY' }
    $command = if ($Stage -in @('joint', 'translation')) { 'run' } else { $Stage }
    $java += @('-cp', $classpath, 'ripr.FreshRigidPairBenchmark',
        $project, $command, $Split)
    & java @java
    if ($LASTEXITCODE -ne 0) { throw "Fresh rigid pair stage $Stage failed" }
} finally {
    Pop-Location
}
