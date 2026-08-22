@echo off
setlocal EnableExtensions
set "APP_HOME=%~dp0"
set "GRADLE_VERSION=8.13"
set "GRADLE_GIT_TAG=v8.13.0"
set "WRAPPER_DIR=%APP_HOME%gradle\wrapper"
set "WRAPPER_JAR=%WRAPPER_DIR%\gradle-wrapper.jar"
set "WRAPPER_CHECKSUM=%WRAPPER_DIR%\gradle-wrapper-%GRADLE_VERSION%.jar.sha256"
set "WRAPPER_URL=https://raw.githubusercontent.com/gradle/gradle/%GRADLE_GIT_TAG%/gradle/wrapper/gradle-wrapper.jar"
set "CHECKSUM_URL=https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-wrapper.jar.sha256"

if not exist "%WRAPPER_DIR%" mkdir "%WRAPPER_DIR%"

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue';" ^
  "$checksumPath='%WRAPPER_CHECKSUM%';" ^
  "if (Test-Path $checksumPath) { $expected=(Get-Content -Raw $checksumPath).Trim().ToLower() } else {" ^
  "  $expected=((Invoke-WebRequest -UseBasicParsing '%CHECKSUM_URL%').Content).Trim().ToLower();" ^
  "  Set-Content -NoNewline -Encoding ascii -Path $checksumPath -Value $expected" ^
  "};" ^
  "if ($expected -notmatch '^[0-9a-f]{64}$') { throw 'Invalid Gradle Wrapper SHA-256' };" ^
  "$actual=''; if (Test-Path '%WRAPPER_JAR%') { $actual=(Get-FileHash -Algorithm SHA256 '%WRAPPER_JAR%').Hash.ToLower() };" ^
  "if ($actual -ne $expected) {" ^
  "  Write-Host 'Gradle Wrapper %GRADLE_VERSION%を検証付きで取得しています...';" ^
  "  Invoke-WebRequest -UseBasicParsing '%WRAPPER_URL%' -OutFile '%WRAPPER_JAR%.tmp';" ^
  "  $actual=(Get-FileHash -Algorithm SHA256 '%WRAPPER_JAR%.tmp').Hash.ToLower();" ^
  "  if ($actual -ne $expected) { Remove-Item '%WRAPPER_JAR%.tmp' -ErrorAction SilentlyContinue; throw 'Gradle Wrapper SHA-256 mismatch' };" ^
  "  Move-Item -Force '%WRAPPER_JAR%.tmp' '%WRAPPER_JAR%'" ^
  "}"
if errorlevel 1 exit /b 1

if defined JAVA_HOME (
  set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA_EXE=java.exe"
)

"%JAVA_EXE%" -Dorg.gradle.appname=gradlew -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
endlocal
