param([string[]]$Tasks = @('testDebugUnitTest','assembleDebug','lintDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolRoot = Join-Path $projectRoot '.tools'
$env:JAVA_HOME = Get-ChildItem -LiteralPath (Join-Path $toolRoot 'jdk-extracted') -Directory | Select-Object -First 1 -ExpandProperty FullName
$env:GRADLE_USER_HOME = Join-Path $toolRoot 'gradle-home'
$env:ANDROID_HOME = Join-Path $toolRoot 'android-sdk'
Push-Location (Join-Path $projectRoot 'android')
try {
    & (Join-Path $toolRoot 'gradle-8.14.3\bin\gradle.bat') --no-daemon --console=plain @Tasks
    if ($LASTEXITCODE -ne 0) { throw 'Android verification failed' }
} finally { Pop-Location }
