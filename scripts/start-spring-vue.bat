@echo off
REM 固定端口：前端 3780 / 后端 8586 / 断口分析 8090
cd /d "%~dp0.."
echo Building Vue and Spring Boot application...
call npm --prefix frontend run build
if errorlevel 1 exit /b 1
call mvn -q -f backend\pom.xml package
if errorlevel 1 exit /b 1
echo Starting fracture-service on :8090
start "fracture-service" cmd /k "cd /d ""%~dp0..\fracture-service"" && python -m uvicorn app:app --host 127.0.0.1 --port 8090"
echo Starting Spring Boot on :8586
start "lab-backend" cmd /k "cd /d ""%~dp0..\backend"" && java -Dfile.encoding=UTF-8 -jar target\lab-digital-platform.jar"
echo Starting Vue on :3780
start "lab-frontend" cmd /k "cd /d ""%~dp0..\frontend"" && npm run dev"
echo Open http://localhost:3780
