param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location $projectRoot
$bundleRoot = Join-Path $projectRoot ('artifacts/build-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$jarSource = Join-Path $projectRoot 'backend/target/lab-digital-platform.jar'
if (-not $SkipBuild) {
    & npm.cmd --prefix frontend run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    # Build in isolation so a running local JAR does not lock the output file.
    $buildRoot = Join-Path $bundleRoot 'build-source'
    New-Item -ItemType Directory -Path "$buildRoot/backend", "$buildRoot/frontend" -Force | Out-Null
    Copy-Item -LiteralPath 'backend/pom.xml' -Destination "$buildRoot/backend"
    Copy-Item -LiteralPath 'backend/src' -Destination "$buildRoot/backend" -Recurse
    Copy-Item -LiteralPath 'frontend/dist' -Destination "$buildRoot/frontend" -Recurse
    & mvn.cmd -q -f "$buildRoot/backend/pom.xml" package
    if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
    $jarSource = Join-Path $buildRoot 'backend/target/lab-digital-platform.jar'
}
$deployRoot = Join-Path $bundleRoot 'deploy'
New-Item -ItemType Directory -Path "$deployRoot/fracture-service", "$deployRoot/config" -Force | Out-Null
Copy-Item -LiteralPath $jarSource -Destination $deployRoot
foreach ($file in @('app.py', 'macro.py', 'analyze.py', 'requirements.txt')) {
    Copy-Item -LiteralPath "fracture-service/$file" -Destination "$deployRoot/fracture-service"
}
Copy-Item -LiteralPath '.env.example' -Destination "$deployRoot/.env.example"
Copy-Item -Path 'scripts/deploy/*' -Destination $deployRoot
# Write a portable data directory instead of copying workstation-specific paths.
$captureConfig = @'
# Standalone mode. For a central server, configure the independent stations below.
data.dir=data
data.station-id=local
data.settle-seconds=2
# data.clients=127.0.0.1,::1

# Use real capture-computer IPs and paths readable by the SERVER account.
# station.ids=machine1,machine2
# station.machine1.name=Bench 1
# station.machine1.clients=192.168.1.101
# station.machine1.dir=//LAB-PC-01/TestPilotData
# station.machine2.name=Bench 2
# station.machine2.clients=192.168.1.102
# station.machine2.dir=//LAB-PC-02/TestPilotData
'@
[IO.File]::WriteAllText("$deployRoot/config/wance.properties", $captureConfig + "`n", [Text.UTF8Encoding]::new($false))
$archive = "$bundleRoot.zip"
Compress-Archive -LiteralPath $deployRoot -DestinationPath $archive
Write-Host "Deployment ZIP: $archive"
Write-Host "Unpacked directory: $deployRoot"
