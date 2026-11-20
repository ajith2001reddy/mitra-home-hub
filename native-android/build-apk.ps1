$ErrorActionPreference = 'Stop'

$gradle = Get-Command gradle -ErrorAction SilentlyContinue
$debugApk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
$outputApk = Join-Path $PSScriptRoot 'build\mitra-home-hub-debug.apk'

if (-not $gradle) {
  throw 'Gradle was not found on PATH. Install Android Studio and Gradle, then set JAVA_HOME to a supported JDK.'
}

Push-Location $PSScriptRoot
try {
  & $gradle.Source ':app:assembleDebug' '--no-daemon'
  if ($LASTEXITCODE -ne 0) { throw 'Android build failed.' }
  New-Item -ItemType Directory -Force -Path (Split-Path $outputApk) | Out-Null
  Copy-Item -LiteralPath $debugApk -Destination $outputApk -Force
  Get-Item -LiteralPath $outputApk | Select-Object FullName,Length,LastWriteTime
}
finally {
  Pop-Location
}
