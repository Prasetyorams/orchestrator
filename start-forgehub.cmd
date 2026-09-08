@echo off
REM ============================================================
REM  ForgeHub - orkestrator JakForge
REM
REM  Klik dua kali berkas ini untuk menjalankan ForgeHub, lalu
REM  buka http://localhost:8080 di peramban.
REM
REM  Masuk pertama kali:  FH_Admin  /  forgehub
REM
REM  Data disimpan di %LOCALAPPDATA%\JakForge\ForgeHub dan TIDAK
REM  ikut terhapus saat proyek ini dibangun ulang.
REM ============================================================

setlocal

set "SERVER=%~dp0server"
set "EXE=%SERVER%\bin\Debug\net10.0\ForgeHub.Server.exe"

if not exist "%EXE%" (
    echo ForgeHub belum dibangun. Membangun sekarang...
    echo.
    dotnet build "%SERVER%\ForgeHub.Server.csproj" -v:quiet --nologo
    if errorlevel 1 (
        echo.
        echo Pembangunan gagal. Pastikan .NET SDK 10 terpasang:
        echo   https://dotnet.microsoft.com/download
        pause
        exit /b 1
    )
)

echo Menjalankan ForgeHub di http://localhost:8080
echo Tutup jendela ini untuk menghentikannya.
echo.

REM Dijalankan dari folder server supaya wwwroot dan appsettings.json ketemu.
pushd "%SERVER%"
"%EXE%"
popd

endlocal
