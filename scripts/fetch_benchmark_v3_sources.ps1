param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [switch]$Extract,
    [switch]$Resume
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path -LiteralPath $ProjectRoot).Path
$manifest = Join-Path $project 'library\benchmark\v3\protocol\publication_download_manifest.csv'
$downloadRoot = Join-Path $project 'library\benchmark\v3\downloads\publication_test'
$status = Join-Path $downloadRoot 'download_status.log'
New-Item -ItemType Directory -Path $downloadRoot -Force | Out-Null

function Write-Status([string]$Message) {
    $line = "$(Get-Date -Format o) $Message"
    $written = $false
    for ($attempt = 1; $attempt -le 40 -and !$written; $attempt++) {
        try {
            $line | Add-Content -LiteralPath $status
            $written = $true
        } catch [IO.IOException] {
            if ($attempt -eq 40) { throw }
            Start-Sleep -Milliseconds 250
        }
    }
    Write-Host $line
}

foreach ($row in (Import-Csv -LiteralPath $manifest)) {
    $folder = Join-Path $downloadRoot $row.record_id
    New-Item -ItemType Directory -Path $folder -Force | Out-Null
    $target = Join-Path $folder $row.download_name
    $valid = $false
    if (Test-Path -LiteralPath $target) {
        $observed = (Get-FileHash -LiteralPath $target -Algorithm MD5).Hash.ToLowerInvariant()
        $valid = $observed -eq $row.md5.ToLowerInvariant()
        if (!$valid -and !$Resume) {
            throw "Existing download has wrong MD5: $target ($observed)"
        }
    }
    if (!$valid) {
        $escaped = [Uri]::EscapeDataString($row.remote_filename).Replace('%2F', '/')
        $url = "https://zenodo.org/records/$($row.record_id)/files/$escaped`?download=1"
        Write-Status "START record=$($row.record_id) file=$($row.remote_filename) bytes=$($row.size_bytes)"
        & curl.exe --fail --location --retry 8 --retry-all-errors --retry-delay 10 `
            --continue-at - --output $target $url
        if ($LASTEXITCODE -ne 0) { throw "curl failed for $url with exit $LASTEXITCODE" }
        $observed = (Get-FileHash -LiteralPath $target -Algorithm MD5).Hash.ToLowerInvariant()
        if ($observed -ne $row.md5.ToLowerInvariant()) {
            throw "Downloaded MD5 mismatch: $target expected=$($row.md5) observed=$observed"
        }
        Write-Status "COMPLETE record=$($row.record_id) file=$($row.download_name) md5=$observed"
    } else {
        Write-Status "VERIFIED record=$($row.record_id) file=$($row.download_name)"
    }
    if ($Extract -and $row.format_role -eq 'archive') {
        $destination = Join-Path $folder ([IO.Path]::GetFileNameWithoutExtension($row.download_name))
        $marker = Join-Path $destination '.extraction_complete'
        if (!(Test-Path -LiteralPath $marker)) {
            Write-Status "START extract=$($row.download_name)"
            New-Item -ItemType Directory -Path $destination -Force | Out-Null
            Expand-Archive -LiteralPath $target -DestinationPath $destination -Force
            New-Item -ItemType File -Path $marker -Force | Out-Null
            Write-Status "COMPLETE extract=$destination"
        }
    }
}
Write-Status 'DONE publication source downloads'
