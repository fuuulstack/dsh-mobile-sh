# 生成正式发布密钥并产出签名 APK。
#
# 用法（PowerShell，项目根）：
#   ./scripts/make-release-key.ps1 -KeystorePath E:\keys\dsh-chat-release.jks
#   $env:ANDROID_KEYSTORE='E:\keys\dsh-chat-release.jks'; $env:ANDROID_KEYSTORE_PASSWORD='...'
#   $env:ANDROID_KEY_ALIAS='dsh-chat'; $env:ANDROID_KEY_PASSWORD='...'
#   ./scripts/build-android.ps1 -SdkPath E:\SDK\android-sdk -JavaPath E:\SDK\jbr17 -GradlePath <gradle.bat>
#
# 密钥不入仓库；遗失即无法更新同一应用身份，请离线备份。
param(
    [Parameter(Mandatory = $true)][string]$KeystorePath,
    [int]$ValidityDays = 10950,
    [string]$Alias = "dsh-chat",
    [string]$DName = "CN=DSH Chat, OU=Mobile, O=Self-hosted, C=CN"
)

$ErrorActionPreference = 'Stop'
$ks = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($KeystorePath)
if (Test-Path $ks) { Write-Host "已存在：$ks（如需重新生成请先移走）" ; exit 1 }

$password = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 28 | ForEach-Object { [char]$_ })
$storePass = [byte[]][char[]]$password
$keyFile = New-TemporaryFile

try {
    # keytool 来自 JDK；密码经临时文件传入，避免留在命令行历史。
    [System.IO.File]::WriteAllBytes($keyFile, $storePass)
    & "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v ``
        -keystore $ks -storetype JKS -alias $Alias ``
        -keyalg RSA -keysize 4096 -validity $ValidityDays ``
        -dname $DName ``
        -storepass:file + $keyFile.FullName -keypass:file + $keyFile.FullName
    if ($LASTEXITCODE -ne 0) { throw "keytool 失败（exit $LASTEXITCODE）" }

    Write-Host ""
    Write-Host "密钥已生成：$ks"
    Write-Host "请立即把以下值存入密码管理器（本输出不再出现第二次）："
    Write-Host "  ANDROID_KEYSTORE          = $ks"
    Write-Host "  ANDROID_KEYSTORE_PASSWORD = $password"
    Write-Host "  ANDROID_KEY_ALIAS         = $Alias"
    Write-Host "  ANDROID_KEY_PASSWORD      = $password"
    Write-Host ""
    Write-Host "构建正式签名 APK：设置上面四个环境变量后运行 scripts/build-android.ps1。"
    Write-Host "密钥请离线备份；丢失后无法再更新同一应用身份。"
}
finally {
    Remove-Item $keyFile -Force -ErrorAction SilentlyContinue
}
