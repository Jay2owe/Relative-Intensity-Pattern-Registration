param(
    [string]$Images = '',
    [string[]]$Methods = @('automatic', 'enhanced_correlation'),
    [string]$Output = '',
    [ValidateSet('PHASE_CONTRAST', 'BRIGHTFIELD_DIC', 'DENSE_FLUORESCENCE',
        'SPARSE_LOW_LIGHT_FLUORESCENCE', 'FIDUCIAL_STATIC')]
    [string]$ImageType = 'DENSE_FLUORESCENCE',
    [ValidateSet('STEADY_DIRECTIONAL_DRIFT', 'CURVED_OSCILLATING_DRIFT',
        'SUBPIXEL_RANDOM_WALK', 'INTERMITTENT_JUMPS')]
    [string]$MotionType = 'INTERMITTENT_JUMPS',
    [ValidateRange(2, 45)]
    [int]$MaxHeapGb = 8,
    [switch]$Preview,
    [switch]$Resume,
    [switch]$ListMethods
)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$runner = Join-Path $PSScriptRoot 'reusable_registration_benchmark.py'
$python = Get-Command python -ErrorAction Stop

if ($ListMethods) {
    & $python.Source $runner --list-methods
    exit $LASTEXITCODE
}
if (!$Images) { throw 'Supply -Images with a folder containing TIFF stacks.' }
if (!$Output) {
    $stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
    $Output = Join-Path $project "benchmark-runs\$stamp"
}

$arguments = @(
    $runner,
    '--images', $Images,
    '--methods', ($Methods -join ','),
    '--output', $Output,
    '--image-type', $ImageType,
    '--motion-type', $MotionType,
    '--max-heap-gb', $MaxHeapGb
)
if ($Preview) { $arguments += '--preview' }
if ($Resume) { $arguments += '--resume' }

& $python.Source @arguments
if ($LASTEXITCODE -ne 0) { throw "Registration benchmark failed with exit code $LASTEXITCODE." }
