@echo off
REM Restart backend (:8586) and frontend (:3780) only. ASCII-only on purpose.
cd /d "%~dp0.."

pushd backend
start "lab-backend" cmd /k java -Dfile.encoding=UTF-8 -jar target\lab-digital-platform.jar --server.port=8586
popd

pushd frontend
start "lab-frontend" cmd /k npm run dev
popd

echo Backend :8586 and Frontend :3780 launching...
