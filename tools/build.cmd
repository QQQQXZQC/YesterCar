@echo off
REM ============================================================================
REM  YesterCar / build.cmd
REM
REM  Convenience wrapper: builds and tests the project with JDK 17 and the
REM  project-local Maven repository, without depending on the caller's shell
REM  environment.
REM
REM  Usage:
REM    tools\build.cmd            clean test
REM    tools\build.cmd package    clean package  (also produces the fat jar)
REM
REM  NOTE: keep this file pure ASCII (cmd.exe reads .cmd with the OEM code page).
REM ============================================================================

setlocal

set "PROJECT_DIR=%~dp0.."
pushd "%PROJECT_DIR%"

REM Processing 4 / this project require JDK 17. JAVA_HOME on this machine
REM defaults to JDK 11 (D:\A\jdk-11.0.25), which cannot build the project.
if exist "C:\Program Files\Java\jdk-17" set "JAVA_HOME=C:\Program Files\Java\jdk-17"

set "GOAL=%~1"
if "%GOAL%"=="" set "GOAL=test"

call mvn -B clean %GOAL%
set "RC=%ERRORLEVEL%"

popd
exit /b %RC%
