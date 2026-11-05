param(
    [ValidateSet('start', 'stop')][string]$Action = 'start',
    [int]$BackendPort = 8586,
    [int]$FracturePort = 8090
)
$ErrorActionPreference = 'Stop'
$deployRoot = $PSScriptRoot
Set-Location $deployRoot
$runtimeRoot = Join-Path $deployRoot '.runtime'
$logRoot = Join-Path $deployRoot 'logs'
New-Item -ItemType Directory -Path $runtimeRoot, $logRoot -Force | Out-Null

function Get-OwnedProcess($name) {
    $recordFile = Join-Path $runtimeRoot "$name.json"
    if (-not (Test-Path -LiteralPath $recordFile)) { return $null }
    $record = Get-Content -LiteralPath $recordFile -Raw | ConvertFrom-Json
    $process = Get-Process -Id $record.id -ErrorAction SilentlyContinue
    if ($process -and $process.StartTime.ToUniversalTime().Ticks.ToString() -eq $record.started -and $process.Path -eq $record.path) {
        return $process
    }
    return $null
}

function Stop-OwnedProcess($name) {
    $process = Get-OwnedProcess $name
    if ($process) {
        # Windows virtualenv launchers can have a child Python process.
        & taskkill.exe /PID $process.Id /T /F | Out-Null
        if ($LASTEXITCODE -ne 0 -and (Get-OwnedProcess $name)) { throw "Could not stop $name." }
        $process.WaitForExit(10000) | Out-Null
        Write-Host "Stopped $name (PID $($process.Id))."
    }
    $recordFile = Join-Path $runtimeRoot "$name.json"
    if (Test-Path -LiteralPath $recordFile) { Remove-Item -LiteralPath $recordFile }
}

function Start-OwnedProcess($name, $executable, $arguments, $directory) {
    $process = Start-Process -FilePath $executable -ArgumentList $arguments -WorkingDirectory $directory -WindowStyle Hidden `
        -RedirectStandardOutput "$logRoot/$name.out.log" -RedirectStandardError "$logRoot/$name.err.log" -PassThru
    @{ id = $process.Id; started = $process.StartTime.ToUniversalTime().Ticks.ToString(); path = $process.Path } |
        ConvertTo-Json | Set-Content -LiteralPath "$runtimeRoot/$name.json" -Encoding UTF8
    return $process
}

function Wait-Ready($name, $url, $timeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (-not (Get-OwnedProcess $name)) { throw "$name exited. Check logs/$name.out.log and logs/$name.err.log." }
        try {
            $response = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 3
            if ($response.StatusCode -eq 200) { return }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    throw "$name not ready: $url. Check logs/$name.out.log and logs/$name.err.log."
}

try {
    if ($Action -eq 'stop') {
        Stop-OwnedProcess 'backend'
        Stop-OwnedProcess 'fracture'
        exit 0
    }
    if ($BackendPort -eq $FracturePort) { throw 'Backend and fracture ports must differ.' }
    # Reject occupied ports, rather than assuming an unrelated process is our service.
    foreach ($service in @(@{name='backend';port=$BackendPort}, @{name='fracture';port=$FracturePort})) {
        if (Get-OwnedProcess $service.name) { throw 'Services already running. Run stop.bat before starting again.' }
        if (Get-NetTCPConnection -State Listen -LocalPort $service.port -ErrorAction SilentlyContinue) {
            throw "Port $($service.port) is occupied. Stop the existing service first."
        }
    }
    if (-not (Test-Path -LiteralPath "$deployRoot/.env")) {
        throw 'Create .env from .env.example and configure the server database before starting.'
    }
    $jar = Join-Path $deployRoot 'lab-digital-platform.jar'
    if (-not (Test-Path -LiteralPath $jar)) { throw 'Missing lab-digital-platform.jar.' }
    $javaExe = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java.exe).Source }
    $javaVersion = (& { $ErrorActionPreference = 'Continue'; & $javaExe -version 2>&1 } | Out-String)
    if ($javaVersion -notmatch 'version "(\d+)' -or [int]$Matches[1] -lt 21) { throw 'Java 21 or newer is required. Set JAVA_HOME to the JDK directory.' }
    $pythonExe = Join-Path $deployRoot '.venv/Scripts/python.exe'
    if (-not (Test-Path -LiteralPath $pythonExe)) {
        $systemPython = (Get-Command python.exe).Source
        & $systemPython -m venv "$deployRoot/.venv"
        if ($LASTEXITCODE -ne 0) { throw 'Could not create Python virtual environment. Install Python 3.10 or newer.' }
    }
    & $pythonExe -c 'import sys; sys.exit(0 if sys.version_info >= (3,10) else 1)'
    if ($LASTEXITCODE -ne 0) { throw 'Python 3.10 or newer is required.' }
    & $pythonExe -c 'import importlib.util, sys; sys.exit(0 if all(importlib.util.find_spec(m) for m in sys.argv[1:]) else 1)' uvicorn fastapi cv2 numpy PIL
    if ($LASTEXITCODE -ne 0) {
        Write-Host 'Installing fracture dependencies (internet access needed on first start)...'
        & $pythonExe -m pip install -r "$deployRoot/fracture-service/requirements.txt"
        if ($LASTEXITCODE -ne 0) { throw 'Python dependency installation failed.' }
    }
    $started = @()
    try {
        $null = Start-OwnedProcess 'fracture' $pythonExe "-m uvicorn app:app --host 127.0.0.1 --port $FracturePort" "$deployRoot/fracture-service"
        $started += 'fracture'
        Wait-Ready 'fracture' "http://127.0.0.1:$FracturePort/health" 60
        $null = Start-OwnedProcess 'backend' $javaExe "-Dfile.encoding=UTF-8 -Xms256m -Xmx1024m -jar `"$jar`" --server.port=$BackendPort --fracture.service-url=http://127.0.0.1:$FracturePort" $deployRoot
        $started += 'backend'
        Wait-Ready 'backend' "http://127.0.0.1:$BackendPort/api/meta" 120
    } catch {
        foreach ($name in ($started | Sort-Object -Descending)) { Stop-OwnedProcess $name }
        throw
    }
    Write-Host "Ready: http://localhost:$BackendPort (frontend + API)"
    Write-Host "Fracture service: http://127.0.0.1:$FracturePort/health"
    Write-Host 'Use stop.bat to stop both services.'
} catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
