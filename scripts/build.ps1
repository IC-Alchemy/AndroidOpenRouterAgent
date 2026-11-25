$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$gradleWrapper = Join-Path $projectRoot "gradlew.bat"
$apkPath = Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk"

if (-not (Test-Path $gradleWrapper)) {
    throw "Gradle wrapper not found: $gradleWrapper"
}

Push-Location $projectRoot
try {
    & $gradleWrapper ":app:assembleDebug"
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
} finally {
    Pop-Location
}

if (-not (Test-Path $apkPath)) {
    throw "Build completed, but the APK was not found: $apkPath"
}

Write-Host "Debug APK created: $apkPath"
