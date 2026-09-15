@echo off
REM ============================================================================
REM  YesterCar / install-g4p.cmd
REM
REM  Installs the bundled G4P 4.3.11 jar into the project-local Maven repository
REM  (.mvn/repo), so that `mvn` can resolve org.g4p:g4p:4.3.11.
REM
REM  Why this is needed:
REM    G4P is distributed only through SourceForge, which is unreachable from
REM    this network, so it is not on Maven Central or any mirror. The jar ships
REM    with the repository at third_party\G4P\library\G4P.jar.
REM
REM  When to run this:
REM    * after a fresh `git clone`;
REM    * after deleting .mvn\repo;
REM    * whenever the build reports: Could not find artifact org.g4p:g4p:jar:4.3.11
REM
REM  Teammates who already have G4P in their global ~\.m2 repository do NOT need
REM  to run this, provided they build without the project-local repository pin.
REM
REM  NOTE: keep this file pure ASCII. cmd.exe reads .cmd files using the OEM code
REM  page, so non-ASCII characters here will be garbled and may break parsing.
REM ============================================================================

setlocal

set "PROJECT_DIR=%~dp0.."
pushd "%PROJECT_DIR%"

set "G4P_JAR=third_party\G4P\library\G4P.jar"
set "MVN_REPO=.mvn\repo"

if not exist "%G4P_JAR%" (
    echo [ERROR] G4P jar not found: %G4P_JAR%
    echo         Expected the bundled G4P distribution to be present.
    popd
    exit /b 1
)

REM Processing 4 requires JDK 17. JAVA_HOME on this machine defaults to JDK 11,
REM which cannot even parse the project, so point it at 17 for this invocation.
if exist "C:\Program Files\Java\jdk-17" set "JAVA_HOME=C:\Program Files\Java\jdk-17"

echo [1/2] Installing G4P into %MVN_REPO% ...
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

REM Maven records the origin of locally installed artifacts here. Left in place it
REM can make resolution fail when the repository id does not match.
if exist "%MVN_REPO%\org\g4p\g4p\4.3.11\_remote.repositories" (
    del /q "%MVN_REPO%\org\g4p\g4p\4.3.11\_remote.repositories"
)

echo [2/2] Verifying that the build resolves G4P ...
call mvn -B -q dependency:resolve
if errorlevel 1 (
    echo [ERROR] Dependencies still do not resolve. See docs\开发交接文档.md section 3.
    popd
    exit /b 1
)

echo.
echo [OK] G4P 4.3.11 installed and the dependency tree resolves.
popd
exit /b 0
