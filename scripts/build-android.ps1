param([string]$SdkPath = $env:ANDROID_HOME, [string]$JavaPath = $env:JAVA_HOME, [string]$GradlePath = 'gradle', [ValidateSet('Debug', 'Release')][string]$Configuration = 'Release')
$ErrorActionPreference = 'Stop'
if (!$SdkPath) { throw 'Set ANDROID_HOME or pass -SdkPath.' }
if (!$JavaPath) { throw 'Set JAVA_HOME or pass -JavaPath (JDK 17).' }
$env:ANDROID_HOME = $SdkPath
$env:JAVA_HOME = $JavaPath
$taskRoot = Split-Path -Parent $PSScriptRoot
& $GradlePath -p (Join-Path $taskRoot 'android') "assemble$Configuration" --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Android build failed.' }
$artifactPath = Join-Path $taskRoot 'artifacts'
New-Item -ItemType Directory -Force -Path $artifactPath | Out-Null
$variant = $Configuration.ToLowerInvariant()
$apkName = if ($Configuration -eq 'Release') { 'dsh-chat-0.1.0.apk' } else { 'dsh-chat-0.1.0-debug.apk' }
Copy-Item -LiteralPath (Join-Path $taskRoot "android/app/build/outputs/apk/$variant/app-$variant.apk") -Destination (Join-Path $artifactPath $apkName)
Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $artifactPath $apkName)
