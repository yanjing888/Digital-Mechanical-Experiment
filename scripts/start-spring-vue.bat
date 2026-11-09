@echo off
REM Ports: frontend 3780 / backend 8586 / fracture 8090
cd /d "%~dp0.."
REM Maven can overwrite an active JAR before repackage fails on Windows.
netstat -ano | findstr /c:":8586 " | findstr /c:"LISTENING" >nul 2>&1
if not errorlevel 1 (
    echo [ERROR] Backend port 8586 is already in use. Stop the backend before building.
    echo [INFO] For a deployment ZIP without interrupting the running backend, run:
    echo powershell -NoProfile -ExecutionPolicy Bypass -File scripts\package-deploy.ps1
    exit /b 1
)
echo Building Vue and Spring Boot application...
call npm --prefix frontend run build
if errorlevel 1 exit /b 1
call mvn -q -f backend\pom.xml package
if errorlevel 1 exit /b 1
echo Starting fracture-service on :8090
start "fracture-service" cmd /k "cd /d ""%~dp0..\fracture-service"" && python -m uvicorn app:app --host 127.0.0.1 --port 8090"
echo Starting Spring Boot on :8586
start "lab-backend" cmd /k "cd /d ""%~dp0..\backend"" && java -Dfile.encoding=UTF-8 -jar target\lab-digital-platform.jar --server.port=8586"
echo Starting Vue on :3780
start "lab-frontend" cmd /k "cd /d ""%~dp0..\frontend"" && npm run dev"
echo Open http://localhost:3780
