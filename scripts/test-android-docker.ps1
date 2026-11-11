param([string]$SdkPath = $env:ANDROID_HOME, [string]$JavaPath = $env:JAVA_HOME, [string]$GradlePath = 'gradle', [string]$AdbPath = 'adb')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
Push-Location $taskRoot
$fixtureProcess = $null
function Assert-NativeExit([string]$Step) { if ($LASTEXITCODE -ne 0) { throw "$Step failed ($LASTEXITCODE)." } }
try {
    if (!$SdkPath -or !$JavaPath) { throw 'Set ANDROID_HOME (SDK 35) and JAVA_HOME (JDK 17), or pass their paths.' }
    $env:ANDROID_HOME = $SdkPath
    $env:JAVA_HOME = $JavaPath
    & $AdbPath get-state | Out-Null
    Assert-NativeExit 'Android device/emulator discovery'
    & ./scripts/init-android-fixture.ps1
    & $GradlePath -p android assembleDebug assembleDebugAndroidTest --console=plain
    Assert-NativeExit 'Debug APK build'
    & $AdbPath install -r android/app/build/outputs/apk/debug/app-debug.apk
    Assert-NativeExit 'APK installation'
    & $AdbPath install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    Assert-NativeExit 'Instrumentation installation'
    $statePath = Join-Path $taskRoot 'artifacts/android-fixture-state.json'
    if (Test-Path -LiteralPath $statePath) { Remove-Item -LiteralPath $statePath }
    $nodePath = (Get-Command node).Source
    $fixtureProcess = Start-Process -FilePath $nodePath -ArgumentList @('node_modules/tsx/dist/cli.mjs', 'scripts/android-fixture.ts', '--docker') -WorkingDirectory $taskRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskRoot 'artifacts/android-docker-fixture.log') -RedirectStandardError (Join-Path $taskRoot 'artifacts/android-docker-fixture-error.log')
    $until = [DateTime]::UtcNow.AddSeconds(15)
    while (!(Test-Path -LiteralPath $statePath)) {
        if ($fixtureProcess.HasExited) { throw 'TLS fixture failed; read artifacts/android-docker-fixture-error.log.' }
        if ([DateTime]::UtcNow -gt $until) { throw 'TLS fixture readiness timeout.' }
        Start-Sleep -Milliseconds 100
    }
    & $AdbPath reverse tcp:8443 tcp:8443
    Assert-NativeExit 'ADB reverse'
    & $AdbPath shell pm clear com.dsh.mobile.v2
    Assert-NativeExit 'Clear test app data'
    $fixtureState = Get-Content -Raw -LiteralPath $statePath | ConvertFrom-Json
    $instrumentOutput = & $AdbPath shell am instrument -w -r -e pairCode $fixtureState.pairCode -e email $fixtureState.email -e password $fixtureState.password -e totpCode $fixtureState.totpCode -e hostAlias $fixtureState.hostAlias com.dsh.mobile.v2.test/androidx.test.runner.AndroidJUnitRunner
    Assert-NativeExit 'Instrumentation'
    $instrumentOutput | Tee-Object -FilePath artifacts/android-docker-test-result.txt
    if (($instrumentOutput -join "`n") -notmatch 'OK \(1 test\)') { throw 'Android chat round trip failed.' }
} finally {
    if ($fixtureProcess -and !$fixtureProcess.HasExited) { $fixtureProcess.Kill($true); $fixtureProcess.WaitForExit() }
    & $AdbPath reverse --remove tcp:8443 | Out-Null
    Pop-Location
}
