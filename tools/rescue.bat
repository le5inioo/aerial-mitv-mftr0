@echo off
rem Double-click entry for rescue.sh: find a bash, run the script, keep the window open so the
rem output can still be read (and photographed) while the TV shows nothing.
rem
rem   rescue.bat                 rescue the TV at the script's default address ($TV, else 192.168.2.11)
rem   rescue.bat --check         diagnose only, change nothing
rem   rescue.bat --scan          look for the TV on this subnet first
rem   rescue.bat 192.168.2.31    another address
chcp 65001 >nul
setlocal

set "BASH="
rem 1. A bash already on PATH — but not WSL's C:\Windows\System32\bash.exe, which cannot read a
rem    Windows path like D:\...\tools\rescue.sh.
for /f "delims=" %%i in ('where bash 2^>nul') do (
  if not defined BASH if /i not "%%~dpi"=="%SystemRoot%\System32\" set "BASH=%%i"
)
rem 2. The usual Git for Windows places.
if not defined BASH if exist "%ProgramFiles%\Git\bin\bash.exe" set "BASH=%ProgramFiles%\Git\bin\bash.exe"
if not defined BASH if exist "C:\Program Files (x86)\Git\bin\bash.exe" set "BASH=C:\Program Files (x86)\Git\bin\bash.exe"
if not defined BASH if exist "%LOCALAPPDATA%\Programs\Git\bin\bash.exe" set "BASH=%LOCALAPPDATA%\Programs\Git\bin\bash.exe"
rem 3. The portable Git that ships with this desk's tooling (versioned folder, so take any).
if not defined BASH for /d %%d in ("%USERPROFILE%\.workbuddy\binaries\PortableGit\versions\*") do (
  if not defined BASH if exist "%%~d\bin\bash.exe" set "BASH=%%~d\bin\bash.exe"
)

if not defined BASH (
  echo Git Bash not found. Install Git for Windows: https://git-scm.com/download/win
  echo Then double-click this file again, or run tools/rescue.sh from a shell.
  echo.
  pause
  exit /b 1
)

"%BASH%" "%~dp0rescue.sh" %*
set "RC=%ERRORLEVEL%"
echo.
if "%~1"=="" pause
exit /b %RC%
