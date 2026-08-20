param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [ValidateSet('development', 'locked', 'both')]
    [string]$Dataset = 'both',
    [switch]$Resume,
    [switch]$Rewrite
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path -LiteralPath $ProjectRoot).Path
$runRoot = Join-Path $project 'library\benchmark\v2\runs\external_parameter_sweep_v1'
$frozenPath = Join-Path $runRoot 'frozen_winners.csv'
$declaration = Join-Path $project 'docs\external_parameter_sweep_sealed_set_declaration.md'
$runner = Join-Path $project 'scripts\run_external_parameter_sweep.ps1'
foreach ($required in @($frozenPath, $declaration, $runner)) {
    if (!(Test-Path -LiteralPath $required)) { throw "Missing gate file: $required" }
}

$winners = Import-Csv -LiteralPath $frozenPath
if ($winners.Count -ne 30) { throw "Expected 30 frozen engine/image-type winners, found $($winners.Count)" }
$datasets = if ($Dataset -eq 'both') { @('development', 'locked') } else { @($Dataset) }

foreach ($oneDataset in $datasets) {
    foreach ($winner in $winners) {
        # The all-defaults table already contains every row for an installed-default winner.
        # Reuse it so a winning default is not executed twice on the locked set.
        if ($winner.is_installed_default -eq 'true') { continue }
        $arguments = @(
            '-ProjectRoot', $project,
            '-Dataset', $oneDataset,
            '-Config', $winner.config_id,
            '-OnlyImageClass', $winner.image_series_class,
            '-FullEngineRows'
        )
        if ($Resume) { $arguments += '-Resume' }
        if ($Rewrite) { $arguments += '-Rewrite' }
        & $runner @arguments
        if (-not $?) { throw "Frozen run failed: $oneDataset $($winner.image_series_class) $($winner.config_id)" }
    }
}
