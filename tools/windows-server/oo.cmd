@echo off
rem OpenOrchestrator di Windows Server tanpa Docker. Semua kerjanya di oo.cjs;
rem berkas ini hanya mencari Node (runtime\node portabel, atau yang terpasang).
rem
rem   oo pasang [--dump D:\cadangan.dump]   sekali, dari Command Prompt "Run as administrator"
rem   oo mulai ^| hentikan ^| periksa
rem   oo pulihkan D:\cadangan.dump
setlocal
set "NODE=%~dp0runtime\node\node.exe"
if exist "%NODE%" goto ada
set "NODE=node"
where node >nul 2>&1
if errorlevel 1 (
  echo Node.js tidak ditemukan. Pasang Node.js 22 LTS, atau ekstrak versi ZIP-nya ke %~dp0runtime\node
  exit /b 1
)
:ada
"%NODE%" "%~dp0oo.cjs" %*
exit /b %ERRORLEVEL%
