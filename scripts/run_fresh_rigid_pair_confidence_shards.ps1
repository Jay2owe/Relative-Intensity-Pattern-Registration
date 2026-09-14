param(
    [ValidateSet('development')]
    [string]$Split = 'development',
    [string]$RunPrefix = 'r03_category_confidence_shard',
    [ValidateRange(1, 9)]
    [int]$Shards = 4,
    [string]$ConfidenceRecipe = 'category_recommendation',
    [string]$RotationGains = '0,0.0001,0.00025,0.0005,0.001,0.002,0.005,0.01,0.02,0.03,0.05',
    [switch]$IncrementalRotation
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$root = Join-Path $project 'library\rigid_selector_tuning\fresh_recovery\pair_benchmark'
$classpathFile = Join-Path $root 'test-classpath.txt'
$manifest = Join-Path $root "input_manifest_$Split.csv"

Push-Location $project
try {
    & mvn '-q' '-DskipTests' 'test-compile' 'dependency:build-classpath' `
        "-Dmdep.outputFile=$classpathFile" '-Dmdep.includeScope=test'
    if ($LASTEXITCODE -ne 0) { throw 'Maven test compilation failed' }
    $classpath = "target\classes;target\test-classes;$((Get-Content -LiteralPath $classpathFile -Raw).Trim())"
    $series = @(Import-Csv -LiteralPath $manifest |
        Select-Object -ExpandProperty series_id -Unique |
        Sort-Object)
    if ($series.Count -ne 9) { throw "Expected 9 development series, found $($series.Count)" }

    $buckets = @()
    for ($index = 0; $index -lt $Shards; $index++) {
        $buckets += ,([System.Collections.Generic.List[string]]::new())
    }
    for ($index = 0; $index -lt $series.Count; $index++) {
        $buckets[$index % $Shards].Add($series[$index])
    }

    $processes = @()
    for ($index = 0; $index -lt $Shards; $index++) {
        $number = $index + 1
        $runId = '{0}_{1:d2}' -f $RunPrefix, $number
        $runRoot = Join-Path $root "runs\$Split\$runId"
        New-Item -ItemType Directory -Force -Path $runRoot | Out-Null
        $stdout = Join-Path $runRoot 'console.log'
        $stderr = Join-Path $runRoot 'console.err.log'
        $seriesList = [string]::Join(',', $buckets[$index])
        $systemProperties = @(
            '-Xmx3g',
            '-Djava.awt.headless=true',
            "-Drigid.run=$runId",
            "-Drigid.onlySeries=$seriesList",
            "-Drigid.confidenceRecipe=$ConfidenceRecipe",
            "-Drigid.rotationGains=$RotationGains",
            '-Drigid.skipAudit=true'
        )
        if ($IncrementalRotation) {
            $systemProperties += '-Drigid.incrementalRotation=true'
        }
        $arguments = $systemProperties + @(
            '-cp',
            ('"' + $classpath + '"'),
            'ripr.FreshRigidPairBenchmark',
            ('"' + $project + '"'),
            'confidence',
            $Split
        )
        $process = Start-Process -FilePath 'java.exe' -ArgumentList $arguments -PassThru `
            -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr
        $processes += $process
        Write-Output "started confidence shard $number/$Shards as PID $($process.Id): $seriesList"
    }

    Wait-Process -InputObject $processes
    $failed = @()
    foreach ($process in $processes) {
        $process.Refresh()
        if ($process.ExitCode -ne 0) { $failed += $process.Id }
    }
    if ($failed.Count -gt 0) { throw "Confidence shards failed: $([string]::Join(',', $failed))" }
    Write-Output "all $Shards confidence shards completed successfully"
} finally {
    Pop-Location
}
