param(
    [ValidateSet('generate', 'joint', 'oracle', 'split', 'automatic', 'evidence', 'hybrid', 'confidence', 'audit',
        'fresh-generate', 'fresh-joint', 'fresh-oracle', 'fresh-confidence', 'fresh-audit')]
    [string]$Stage = 'joint',
    [ValidateSet('development', 'integration', 'validation', 'locked', 'qualification',
        'phase-development', 'phase-validation')]
    [string]$Split = 'development',
    [string]$RunId = 'r02_full_factorial',
    [string]$OnlyMotion = '',
    [string]$OnlySeries = '',
    [string]$OnlyCondition = '',
    [string]$OnlyRecipe = '',
    [string]$OnlyArm = '',
    [string]$OnlyImageClass = '',
    [int]$FrameLimit = 0,
    [string]$OutlierMads = '',
    [string]$HybridDenseRecipe = '',
    [string]$HybridPolicy = '',
    [string]$RotationGains = '',
    [string]$RotationGain = '',
    [string]$ConfidenceRecipe = '',
    [int]$MaxCases = 0,
    [int]$BenchmarkThreads = 0,
    [int]$ScreenFrames = 0,
    [switch]$AllowDiagnosticIntegration,
    [switch]$MotionAwareRepair,
    [switch]$IncrementalRotation,
    [switch]$SkipCompile,
    [switch]$TranslationOnlyControl,
    [switch]$ConsecutiveScreen,
    [switch]$GlobalRotationProposal
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$tuning = Join-Path $project 'library\rigid_selector_tuning'
$classpathFile = Join-Path $tuning 'test-classpath.txt'

Push-Location $project
try {
    if (-not $SkipCompile) {
        & mvn '-q' '-DskipTests' 'test-compile' 'dependency:build-classpath' `
            "-Dmdep.outputFile=$classpathFile" '-Dmdep.includeScope=test'
        if ($LASTEXITCODE -ne 0) { throw 'Maven test compilation failed' }
    }
    if (-not (Test-Path -LiteralPath $classpathFile)) {
        throw "Missing test classpath file: $classpathFile"
    }
    $classpath = "target\classes;target\test-classes;$((Get-Content -LiteralPath $classpathFile -Raw).Trim())"
    $java = @('-Xmx6g', '-Djava.awt.headless=true', "-Drigid.run=$RunId")
    if ($OnlyMotion) { $java += "-Drigid.onlyMotion=$OnlyMotion" }
    if ($OnlySeries) { $java += "-Drigid.onlySeries=$OnlySeries" }
    if ($OnlyCondition) { $java += "-Drigid.onlyCondition=$OnlyCondition" }
    if ($OnlyRecipe) { $java += "-Drigid.onlyRecipe=$OnlyRecipe" }
    if ($OnlyArm) { $java += "-Drigid.onlyArm=$OnlyArm" }
    if ($OnlyImageClass) { $java += "-Drigid.onlyImageClass=$OnlyImageClass" }
    if ($FrameLimit -gt 0) { $java += "-Drigid.frameLimit=$FrameLimit" }
    if ($OutlierMads) { $java += "-Drigid.outlierMads=$OutlierMads" }
    if ($HybridDenseRecipe) { $java += "-Drigid.hybridDenseRecipe=$HybridDenseRecipe" }
    if ($HybridPolicy) { $java += "-Drigid.hybridPolicy=$HybridPolicy" }
    if ($RotationGains) { $java += "-Drigid.rotationGains=$RotationGains" }
    if ($RotationGain) { $java += "-Drigid.rotationGain=$RotationGain" }
    if ($ConfidenceRecipe) { $java += "-Drigid.confidenceRecipe=$ConfidenceRecipe" }
    if ($MaxCases -gt 0) { $java += "-Drigid.maxCases=$MaxCases" }
    if ($BenchmarkThreads -gt 0) { $java += "-Drigid.threads=$BenchmarkThreads" }
    if ($ScreenFrames -gt 0) { $java += "-Drigid.screenFrames=$ScreenFrames" }
    if ($AllowDiagnosticIntegration) { $java += '-Drigid.allowDiagnosticIntegration=true' }
    if ($MotionAwareRepair) { $java += '-Drigid.motionAwareRepair=true' }
    if ($IncrementalRotation) { $java += '-Drigid.incrementalRotation=true' }
    if ($TranslationOnlyControl) { $java += '-Drigid.translationOnlyControl=true' }
    if ($ConsecutiveScreen) { $java += '-Drigid.consecutiveScreen=true' }
    if ($GlobalRotationProposal) { $java += '-Drigid.globalRotationProposal=true' }
    if ($Stage -in @('joint', 'fresh-joint')) { $java += '-Drigid.onlyArm=JOINT_RIGID' }
    if ($Stage -in @('oracle', 'fresh-oracle')) {
        $java += '-Drigid.onlyArm=TRUTH_ANGLE_REMOVED_TRANSLATION'
    }
    $command = switch ($Stage) {
        'joint' { 'run' }
        'oracle' { 'run' }
        'fresh-joint' { 'fresh-run' }
        'fresh-oracle' { 'fresh-run' }
        default { $Stage }
    }
    $java += @('-cp', $classpath, 'ripr.RigidSelectorFactorialBenchmark',
        $project, $command, $Split)
    & java @java
    if ($LASTEXITCODE -ne 0) { throw "Rigid selector stage $Stage failed" }
} finally {
    Pop-Location
}
