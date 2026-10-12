@echo off
REM Start only (no build). Ports: frontend 3780 / backend 8586 / fracture 8090
cd /d "%~dp0.."
echo Starting fracture-service on :8090
start "fracture-service" cmd /k "cd /d ""%~dp0..\fracture-service"" && python -m uvicorn app:app --host 127.0.0.1 --port 8090"
echo Starting Spring Boot on :8586
start "lab-backend" cmd /k "cd /d ""%~dp0..\backend"" && java -Dfile.encoding=UTF-8 -jar target\lab-digital-platform.jar"
echo Starting Vue on :3780
start "lab-frontend" cmd /k "cd /d ""%~dp0..\frontend"" && npm run dev"
echo Open http://localhost:3780
