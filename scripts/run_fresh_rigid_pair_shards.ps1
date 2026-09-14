param(
    [ValidateSet('development')]
    [string]$Split = 'development',
    [string]$RunPrefix = 'r01_corrected_full_factorial_shard',
    [ValidateRange(1, 16)]
    [int]$Shards = 8
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$root = Join-Path $project 'library\rigid_selector_tuning\fresh_recovery\pair_benchmark'
$classpathFile = Join-Path $root 'test-classpath.txt'
$recipeManifest = Join-Path $project 'library\rigid_selector_tuning\fresh_recovery\recipe_manifest.csv'

Push-Location $project
try {
    & mvn '-q' '-DskipTests' 'test-compile' 'dependency:build-classpath' `
        "-Dmdep.outputFile=$classpathFile" '-Dmdep.includeScope=test'
    if ($LASTEXITCODE -ne 0) { throw 'Maven test compilation failed' }
    $classpath = "target\classes;target\test-classes;$((Get-Content -LiteralPath $classpathFile -Raw).Trim())"
    $recipes = @(Import-Csv -LiteralPath $recipeManifest |
        Where-Object recipe_id -ne 'category_recommendation' |
        Sort-Object recipe_id |
        ForEach-Object recipe_id)
    if ($recipes.Count -ne 128) { throw "Expected 128 alternatives, found $($recipes.Count)" }

    $buckets = @()
    for ($index = 0; $index -lt $Shards; $index++) {
        $buckets += ,([System.Collections.Generic.List[string]]::new())
    }
    for ($index = 0; $index -lt $recipes.Count; $index++) {
        $buckets[$index % $Shards].Add($recipes[$index])
    }

    $processes = @()
    for ($index = 0; $index -lt $Shards; $index++) {
        $number = $index + 1
        $runId = '{0}_{1:d2}' -f $RunPrefix, $number
        $runRoot = Join-Path $root "runs\$Split\$runId"
        New-Item -ItemType Directory -Force -Path $runRoot | Out-Null
        $stdout = Join-Path $runRoot 'console.log'
        $stderr = Join-Path $runRoot 'console.err.log'
        $recipeList = [string]::Join(',', $buckets[$index])
        $arguments = @(
            '-Xmx2g',
            '-Djava.awt.headless=true',
            "-Drigid.run=$runId",
            "-Drigid.onlyRecipe=$recipeList",
            '-cp',
            ('"' + $classpath + '"'),
            'ripr.FreshRigidPairBenchmark',
            ('"' + $project + '"'),
            'run',
            $Split
        )
        $process = Start-Process -FilePath 'java.exe' -ArgumentList $arguments -PassThru `
            -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr
        $processes += $process
        Write-Output "started shard $number/$Shards as PID $($process.Id): $($buckets[$index].Count) recipes"
    }

    Wait-Process -InputObject $processes
    $failed = @()
    foreach ($process in $processes) {
        $process.Refresh()
        if ($process.ExitCode -ne 0) { $failed += $process.Id }
    }
    if ($failed.Count -gt 0) { throw "Pair shards failed: $([string]::Join(',', $failed))" }
    Write-Output "all $Shards pair shards completed successfully"
} finally {
    Pop-Location
}
