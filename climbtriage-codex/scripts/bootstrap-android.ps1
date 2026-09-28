param([switch]$AcceptAndroidSdkLicenses)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolRoot = Join-Path $projectRoot '.tools'
New-Item -ItemType Directory -Path $toolRoot -Force | Out-Null

function Download-Checked([string]$Url, [string]$Path, [string]$Sha256) {
    if (-not (Test-Path -LiteralPath $Path)) {
        & curl.exe --fail --silent --show-error --location --retry 2 --output $Path $Url
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $Url" }
    }
    if ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Sha256.ToLowerInvariant()) { throw "Checksum mismatch: $Path" }
}

$jdkExtract = Join-Path $toolRoot 'jdk-extracted'
$jdkDir = Get-ChildItem -LiteralPath $jdkExtract -Directory -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName
if (-not $jdkDir) {
    $release = Invoke-RestMethod -Uri 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
    $package = $release[0].binary.package
    $jdkZip = Join-Path $toolRoot 'jdk.zip'
    Download-Checked $package.link $jdkZip $package.checksum
    Expand-Archive -LiteralPath $jdkZip -DestinationPath $jdkExtract -Force
    $jdkDir = Get-ChildItem -LiteralPath $jdkExtract -Directory | Select-Object -First 1 -ExpandProperty FullName
}
$env:JAVA_HOME = $jdkDir
$gradleDir = Join-Path $toolRoot 'gradle-8.14.3'
if (-not (Test-Path -LiteralPath $gradleDir)) {
    $gradleZip = Join-Path $toolRoot 'gradle-8.14.3.zip'
    $gradleHash = (Invoke-RestMethod -Uri 'https://services.gradle.org/distributions/gradle-8.14.3-bin.zip.sha256').Trim()
    Download-Checked 'https://services.gradle.org/distributions/gradle-8.14.3-bin.zip' $gradleZip $gradleHash
    Expand-Archive -LiteralPath $gradleZip -DestinationPath $toolRoot -Force
}
$sdkDir = Join-Path $toolRoot 'android-sdk'
$manager = Join-Path $sdkDir 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path -LiteralPath $manager)) {
    $repository = [xml](Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/repository2-3.xml' -UseBasicParsing).Content
    $cli = $repository.SelectNodes('//*[local-name()="remotePackage"]') | Where-Object { $_.path -eq 'cmdline-tools;latest' } | Select-Object -First 1
    $archive = $cli.SelectNodes('.//*[local-name()="archive"]') | Where-Object { $_.'host-os' -eq 'windows' } | Select-Object -First 1
    $relative = $archive.complete.url
    $sdkZip = Join-Path $toolRoot 'cmdline-tools.zip'
    & curl.exe --fail --silent --show-error --location --retry 2 --continue-at - --output $sdkZip ('https://dl.google.com/android/repository/' + $relative)
    if ($LASTEXITCODE -ne 0) { throw 'Android tools download failed' }
    $checksum = $archive.complete.checksum
    $algorithm = if ($checksum.type -eq 'sha256') { 'SHA256' } else { 'SHA1' }
    if ((Get-FileHash -LiteralPath $sdkZip -Algorithm $algorithm).Hash.ToLowerInvariant() -ne $checksum.InnerText.Trim().ToLowerInvariant()) { throw 'Android tools checksum mismatch' }
    $cliExtract = Join-Path $toolRoot 'cli-extracted'
    Expand-Archive -LiteralPath $sdkZip -DestinationPath $cliExtract -Force
    New-Item -ItemType Directory -Path (Join-Path $sdkDir 'cmdline-tools') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $cliExtract 'cmdline-tools') -Destination (Join-Path $sdkDir 'cmdline-tools\latest') -Recurse -Force
}
$env:ANDROID_HOME = $sdkDir
$env:ANDROID_SDK_ROOT = $sdkDir
$env:GRADLE_USER_HOME = Join-Path $toolRoot 'gradle-home'
if (-not $AcceptAndroidSdkLicenses) { throw 'Read Android SDK license terms, then rerun with -AcceptAndroidSdkLicenses if accepted.' }
$platformJar = Join-Path $sdkDir 'platforms\android-35\android.jar'
$aapt = Join-Path $sdkDir 'build-tools\35.0.0\aapt2.exe'
if (-not ((Test-Path -LiteralPath $platformJar) -and (Test-Path -LiteralPath $aapt))) {
    $androidCli = Join-Path $sdkDir 'cmdline-tools\latest\bin\android.exe'
    if (Test-Path -LiteralPath $androidCli) {
        & $androidCli --no-metrics --sdk=$sdkDir sdk install 'platforms/android-35' 'build-tools/35.0.0' 'platform-tools'
    } else {
        1..100 | ForEach-Object { 'y' } | & $manager --sdk_root=$sdkDir --licenses
        if ($LASTEXITCODE -ne 0) { throw 'SDK license command failed' }
        & $manager --sdk_root=$sdkDir 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
    }
    if ($LASTEXITCODE -ne 0) { Write-Warning 'SDK installer returned nonzero; checking actual installed artifacts before Gradle validation.' }
}
if (-not ((Test-Path -LiteralPath $platformJar) -and (Test-Path -LiteralPath $aapt))) { throw 'Required SDK artifacts are missing' }
$escapedSdk = $sdkDir.Replace('\','/')
"sdk.dir=$escapedSdk" | Set-Content -LiteralPath (Join-Path $projectRoot 'android\local.properties') -Encoding ascii
& (Join-Path $PSScriptRoot 'fetch-model.ps1')
Push-Location (Join-Path $projectRoot 'android')
try {
    & (Join-Path $gradleDir 'bin\gradle.bat') --no-daemon --console=plain wrapper testDebugUnitTest assembleDebug lintDebug
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
} finally { Pop-Location }
