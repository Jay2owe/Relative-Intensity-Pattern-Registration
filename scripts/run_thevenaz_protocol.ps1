param(
    [int]$Trials = 100,
    [string]$OutputDirectory = "library/benchmark/thevenaz_protocol_2026-08-20"
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$output = [System.IO.Path]::GetFullPath((Join-Path $project $OutputDirectory))
$work = Join-Path $project 'target/thevenaz-protocol'
$deps = Join-Path $work 'deps'
New-Item -ItemType Directory -Force -Path $deps, $output | Out-Null

function Get-RemoteFile {
    param([string]$Url, [string]$Destination)
    if (Test-Path -LiteralPath $Destination) { return }
    & curl.exe -L --fail --connect-timeout 20 --max-time 120 --retry 2 `
        --output $Destination $Url
    if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath $Destination)) {
        throw "download failed: $Url"
    }
}

function Find-JdkTool {
    param([string]$Name)
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin/$Name.exe"
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    $harnessJava = Join-Path $env:USERPROFILE '.imagej-plugin-test-harness/Fiji.app/java'
    if (Test-Path -LiteralPath $harnessJava) {
        $candidate = Get-ChildItem -LiteralPath $harnessJava -Recurse -File `
            -Filter "$Name.exe" -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending | Select-Object -First 1
        if ($candidate) { return $candidate.FullName }
    }
    throw "$Name was not found; a JDK is required"
}

# Official author paper. Table III is read from the PDF; the native 256x256 Fig. 3 raster is
# recovered from the PostScript because the PDF copy is downsampled.
$paperPdf = Join-Path $deps 'thevenaz9801.pdf'
$paperPostscript = Join-Path $deps 'thevenaz9801.ps'
Get-RemoteFile 'https://bigwww.epfl.ch/publications/thevenaz9801.pdf' $paperPdf
Get-RemoteFile 'https://bigwww.epfl.ch/publications/thevenaz9801.ps' $paperPostscript

# Prefer the binary distributed by the authors. If that host is unavailable, build the official
# 2.0.0 source tag. Both routes stay test-local under target.
$turboJar = Join-Path $deps 'TurboReg_-official.jar'
if (!(Test-Path -LiteralPath $turboJar)) {
    try {
        $turboZip = Join-Path $deps 'turboreg-official.zip'
        $turboExtract = Join-Path $deps 'turboreg-official'
        Get-RemoteFile 'https://bigwww.epfl.ch/thevenaz/turboreg/turboreg.zip' $turboZip
        Expand-Archive -LiteralPath $turboZip -DestinationPath $turboExtract -Force
        $distributedJar = Get-ChildItem -LiteralPath $turboExtract -Recurse -File `
            -Filter 'TurboReg_.jar' | Select-Object -First 1
        if (!$distributedJar) { throw 'the official TurboReg archive contained no TurboReg_.jar' }
        Copy-Item -LiteralPath $distributedJar.FullName -Destination $turboJar -Force
    }
    catch {
        Write-Host 'Author binary unavailable; building the official TurboReg-2.0.0 source tag.'
        $ijJar = Join-Path $deps 'ij-1.54p.jar'
        $sourceZip = Join-Path $deps 'TurboReg-2.0.0.zip'
        $sourceRoot = Join-Path $deps 'TurboReg-2.0.0-source'
        $classes = Join-Path $deps 'TurboReg-2.0.0-classes'
        Get-RemoteFile 'https://repo1.maven.org/maven2/net/imagej/ij/1.54p/ij-1.54p.jar' $ijJar
        Get-RemoteFile `
            'https://codeload.github.com/fiji-BIG/TurboReg/zip/refs/tags/TurboReg-2.0.0' `
            $sourceZip
        Expand-Archive -LiteralPath $sourceZip -DestinationPath $sourceRoot -Force
        New-Item -ItemType Directory -Force -Path $classes | Out-Null
        $javac = Find-JdkTool 'javac'
        $jarTool = Find-JdkTool 'jar'
        $sourceBase = Join-Path $sourceRoot 'TurboReg-TurboReg-2.0.0/src/main'
        & $javac --release 8 -cp $ijJar -d $classes (Join-Path $sourceBase 'java/TurboReg_.java')
        if ($LASTEXITCODE -ne 0) { throw 'TurboReg compilation failed' }
        & $jarTool cf $turboJar -C $classes . -C (Join-Path $sourceBase 'resources') .
        if ($LASTEXITCODE -ne 0) { throw 'TurboReg jar creation failed' }
    }
}

# Maven is intentionally bootstrapped under target when it is not installed globally.
$maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if (!$maven) { $maven = Get-Command mvn -ErrorAction SilentlyContinue }
if ($maven) {
    $mavenCommand = $maven.Source
}
else {
    $mavenVersion = '3.9.9'
    $mavenZip = Join-Path $work "tools/apache-maven-$mavenVersion-bin.zip"
    $mavenHome = Join-Path $work "tools/apache-maven-$mavenVersion"
    if (!(Test-Path -LiteralPath (Join-Path $mavenHome 'bin/mvn.cmd'))) {
        New-Item -ItemType Directory -Force -Path (Split-Path $mavenZip) | Out-Null
        Get-RemoteFile `
            "https://repo1.maven.org/maven2/org/apache/maven/apache-maven/$mavenVersion/apache-maven-$mavenVersion-bin.zip" `
            $mavenZip
        Expand-Archive -LiteralPath $mavenZip -DestinationPath (Split-Path $mavenZip) -Force
    }
    $mavenCommand = Join-Path $mavenHome 'bin/mvn.cmd'
}

$javacForMaven = Find-JdkTool 'javac'
$savedJavaHome = $env:JAVA_HOME
$env:JAVA_HOME = Split-Path (Split-Path $javacForMaven)
try {
    Push-Location $project
    try {
        & $mavenCommand -DskipTests test-compile
        if ($LASTEXITCODE -ne 0) { throw 'project test compilation failed' }
        $classpathFile = Join-Path $work 'test-classpath.txt'
        & $mavenCommand dependency:build-classpath `
            "-Dmdep.outputFile=$classpathFile" '-Dmdep.includeScope=test'
        if ($LASTEXITCODE -ne 0) { throw 'could not build the test classpath' }
        $classpath = (Join-Path $project 'target/classes') + ';' +
            (Join-Path $project 'target/test-classes') + ';' +
            (Get-Content -LiteralPath $classpathFile -Raw).Trim()
        $java = Join-Path (Split-Path $javacForMaven) 'java.exe'
        & $java "-Dthevenaz.trials=$Trials" -cp $classpath `
            ripr.ThevenazProtocolBenchmark $project $output $turboJar $paperPdf $paperPostscript
        exit $LASTEXITCODE
    }
    finally {
        Pop-Location
    }
}
finally {
    $env:JAVA_HOME = $savedJavaHome
}
