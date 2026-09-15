@echo off
REM ============================================================================
REM  YesterCar / install-g4p.cmd
REM
REM  Installs the bundled G4P 4.3.11 jar so that `mvn` can resolve
REM  org.g4p:g4p:4.3.11. Installs into TWO repositories on purpose:
REM
REM    1) the project-local repository  .mvn/repo        -> used by tools\build.cmd
REM    2) the global repository         %USERPROFILE%\.m2\repository
REM                                                      -> used by IntelliJ IDEA,
REM                                                         which by default ignores
REM                                                         .mvn/maven.config
REM
REM  Why this is needed:
REM    G4P is distributed only through SourceForge, which is unreachable from this
REM    network, so it is not on Maven Central or any mirror. The jar ships with the
REM    repository at third_party\G4P\library\G4P.jar.
REM
REM  When to run this:
REM    * after a fresh `git clone`;
REM    * after deleting .mvn\repo;
REM    * whenever the build reports: Could not find artifact org.g4p:g4p:jar:4.3.11
REM      (including "java: package g4p_controls does not exist" inside IDEA)
REM
REM  NOTE: keep this file pure ASCII. cmd.exe reads .cmd files using the OEM code
REM  page, so non-ASCII characters here will be garbled and may break parsing.
REM ============================================================================

setlocal enabledelayedexpansion

set "PROJECT_DIR=%~dp0.."
pushd "%PROJECT_DIR%"

set "G4P_JAR=third_party\G4P\library\G4P.jar"
set "MVN_REPO=.mvn\repo"
set "GLOBAL_REPO=%USERPROFILE%\.m2\repository"

if not exist "%G4P_JAR%" (
    echo [ERROR] G4P jar not found: %G4P_JAR%
    echo         Expected the bundled G4P distribution to be present.
    popd
    exit /b 1
)

REM Processing 4 requires JDK 17. JAVA_HOME on this machine defaults to JDK 11,
REM which cannot even parse the project, so point it at 17 for this invocation.
if exist "C:\Program Files\Java\jdk-17" set "JAVA_HOME=C:\Program Files\Java\jdk-17"

REM --- 1) project-local repository -------------------------------------------------
echo [1/3] Installing G4P into %MVN_REPO% ...
call mvn -B install:install-file ^
    "-Dfile=%G4P_JAR%" ^
    "-DgroupId=org.g4p" ^
    "-DartifactId=g4p" ^
    "-Dversion=4.3.11" ^
    "-Dpackaging=jar" ^
    "-DlocalRepositoryPath=%MVN_REPO%"
if errorlevel 1 (
    echo [ERROR] Failed to install G4P into %MVN_REPO%
    popd
    exit /b 1
)

REM --- 2) global repository (what IntelliJ IDEA uses by default) -------------------
echo.
echo [2/3] Installing G4P into %GLOBAL_REPO% ...
REM Pass maven.repo.local explicitly: an explicit -D wins over .mvn\maven.config,
REM which is what we want here.
call mvn -B install:install-file ^
    "-Dfile=%G4P_JAR%" ^
    "-DgroupId=org.g4p" ^
    "-DartifactId=g4p" ^
    "-Dversion=4.3.11" ^
    "-Dpackaging=jar" ^
    "-Dmaven.repo.local=%GLOBAL_REPO%"
if errorlevel 1 (
    echo [WARNING] Could not install into %GLOBAL_REPO%.
    echo           Command-line builds will still work; IDEA may not.
)

REM --- 3) clean up stale Maven bookkeeping files -----------------------------------
REM Maven records the origin of locally installed artifacts in _remote.repositories,
REM and records FAILED remote lookups in *.lastUpdated. Both can make resolution fail
REM later even when the jar is present, so remove them from both repositories.
echo.
echo [3/3] Cleaning stale Maven bookkeeping files ...
for %%R in ("%MVN_REPO%" "%GLOBAL_REPO%") do (
    if exist "%%~R\org\g4p\g4p\4.3.11\_remote.repositories" (
        del /q "%%~R\org\g4p\g4p\4.3.11\_remote.repositories"
        echo   removed _remote.repositories in %%~R
    )
    if exist "%%~R\org\g4p\g4p\4.3.11\*.lastUpdated" (
        del /q "%%~R\org\g4p\g4p\4.3.11\*.lastUpdated"
        echo   removed .lastUpdated markers in %%~R
    )
)

echo.
echo [OK] G4P 4.3.11 installed into both repositories.
echo      Next: run tools\build.cmd , and in IDEA do
echo      Maven tool window -^> Reload All Maven Projects.
popd
exit /b 0
