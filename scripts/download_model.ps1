# download_model.ps1
# Downloads the Depth Anything V2 ViT-S quantized ONNX model from the tiefling project
# and places it in app/src/main/assets/ ready for the Android build.
#
# Run from the ParallaxWallpaper/ root:
#   powershell -ExecutionPolicy Bypass -File scripts/download_model.ps1

$ErrorActionPreference = "Stop"

$model   = "depthanythingv2-vits-dynamic-quant.onnx"
$destDir = Join-Path $PSScriptRoot "..\app\src\main\assets"
$dest    = Join-Path $destDir $model

# GitHub raw / LFS media URL for the tiefling repo
$url = "https://media.githubusercontent.com/media/combatwombat/tiefling/main/site/public/models/$model"

Write-Host "Destination : $dest"
Write-Host "Source URL  : $url"
Write-Host ""

if (Test-Path $dest) {
    $size = (Get-Item $dest).Length
    if ($size -gt 1MB) {
        Write-Host "[OK] Model already present ($([math]::Round($size/1MB,1)) MB). Nothing to do." -ForegroundColor Green
        exit 0
    }
    Write-Host "[WARN] Existing file looks incomplete ($size bytes). Re-downloading..." -ForegroundColor Yellow
    Remove-Item $dest -Force
}

New-Item -ItemType Directory -Force -Path $destDir | Out-Null

Write-Host "Downloading (~26 MB) ..." -ForegroundColor Cyan
try {
    $wc = New-Object System.Net.WebClient
    $wc.DownloadFile($url, $dest)
} catch {
    Write-Host ""
    Write-Host "[FALLBACK] Media URL failed. Trying raw GitHub..." -ForegroundColor Yellow
    $urlFallback = "https://github.com/combatwombat/tiefling/raw/main/site/public/models/$model"
    $wc.DownloadFile($urlFallback, $dest)
}

$size = (Get-Item $dest).Length
Write-Host ""
Write-Host "[DONE] Saved $([math]::Round($size/1MB,1)) MB to $dest" -ForegroundColor Green
Write-Host "You can now build and run the app."
