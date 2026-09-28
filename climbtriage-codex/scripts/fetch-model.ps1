param([string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot))
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$assetDir = Join-Path $ProjectRoot 'android\app\src\main\assets'
New-Item -ItemType Directory -Path $assetDir -Force | Out-Null
$modelPath = Join-Path $assetDir 'pose_landmarker_lite.task'
$modelUrl = 'https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task'
if (-not (Test-Path -LiteralPath $modelPath)) {
    Invoke-WebRequest -Uri $modelUrl -OutFile $modelPath -UseBasicParsing
}
$hash = (Get-FileHash -LiteralPath $modelPath -Algorithm SHA256).Hash.ToLowerInvariant()
$expectedSha256 = '59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a'
if ($hash -ne $expectedSha256) { throw 'Pose task checksum changed. Review the model revision before updating the pin.' }
$provenance = @{url=$modelUrl; sha256=$hash; version='float16/1'; fetched_utc=[DateTime]::UtcNow.ToString('o')}
$manifestPath = Join-Path $ProjectRoot 'docs\pose-model-download.json'
if (-not (Test-Path -LiteralPath $manifestPath)) { $provenance | ConvertTo-Json | Set-Content -LiteralPath $manifestPath -Encoding utf8 }
Write-Output "Pose model SHA256: $hash"
