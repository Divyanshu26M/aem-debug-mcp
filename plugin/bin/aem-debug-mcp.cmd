@echo off
rem Starts the aem-debug-mcp server (MCP over stdio). Nothing but the server may write to stdout.
setlocal
set "SCRIPT_DIR=%~dp0"
set "JAR=%SCRIPT_DIR%..\server\aem-debug-mcp.jar"

if not "%AEM_DEBUG_JAVA%"=="" (
    set "JAVA_EXE=%AEM_DEBUG_JAVA%"
) else if not "%JAVA_HOME%"=="" (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_EXE=java"
)

if not exist "%JAR%" (
    echo aem-debug-mcp: %JAR% not found. Build it with "mvn -DskipTests package" in the repository root. 1>&2
    exit /b 1
)

"%JAVA_EXE%" -jar "%JAR%" --version >nul 2>&1
if errorlevel 1 (
    echo aem-debug-mcp: "%JAVA_EXE%" cannot run the server. A JDK 17 or newer is required ^(a full JDK, so that jdk.jdi is available^). Set JAVA_HOME or AEM_DEBUG_JAVA. 1>&2
    exit /b 1
)

"%JAVA_EXE%" %AEM_DEBUG_JAVA_OPTS% -jar "%JAR%" %*
exit /b %ERRORLEVEL%
