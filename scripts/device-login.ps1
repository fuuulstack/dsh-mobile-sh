<#
一键把 DSH Mobile 登录表单填完并提交（真机/模拟器通用，坐标从 uiautomator 现取，不写死）。

要点：
- 明文只从文件读取并 push 到设备，命令行与终端输出里不出现密码/动态码；
  输入用设备侧 `$(cat 文件)` 替换，规避 PowerShell 与 adb 的双重引号拆解。
- 每填一格都收一次软键盘再重新 dump：Android 上键盘会盖住下面的输入框，
  不收键盘直接点下一格会把文本打进上一格（实测踩过）。
- 动态码由服务端生成（scripts/relay-totp-file.sh），避免本机时钟与密钥抄写偏差。

用法：
  node scripts/relay-totp-file.sh 的产物放到 artifacts/totp.txt，然后
  pwsh -File scripts/device-login.ps1 -Serial YOUR_DEVICE_SERIAL
#>
param(
    [string]$Adb = 'E:\SDK\android-sdk\platform-tools\adb.exe',
    [string]$Serial = '',
    [string]$EndpointFile = 'artifacts/endpoint.txt',
    [string]$EmailFile = 'artifacts/email.txt',
    [string]$PasswordFile = 'artifacts/pw.txt',
    [string]$TotpFile = 'artifacts/totp.txt',
    [string]$Package = 'com.dsh.mobile.v2',
    [string]$Ime = 'org.pocketworkstation.pckeyboard/.LatinIME',
    [switch]$KeepFiles
)
$ErrorActionPreference = 'Stop'
$dumpPath = Join-Path $env:TEMP 'dsh-login-ui.xml'
$tmp = '/data/local/tmp/dsh-login'
$Labels = @('中继地址', '邮箱', '密码', '动态验证码')

function Adb([string[]]$params) {
    if ($Serial) { $all = @('-s', $Serial) + $params } else { $all = $params }
    & $Adb @all 2>&1
}
function Dump() {
    Adb @('shell', 'uiautomator', 'dump', '/sdcard/dsh-login.xml') | Out-Null
    Adb @('pull', '/sdcard/dsh-login.xml', $dumpPath) | Out-Null
    Get-Content $dumpPath -Raw
}
function Segments([string]$xml) { $xml -split '<node' }
function CenterOf([string]$seg) {
    if ($seg -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
        return @([int](([int]$matches[1] + [int]$matches[3]) / 2), [int](([int]$matches[2] + [int]$matches[4]) / 2))
    }
    return $null
}
function TextOf([string]$seg) {
    if ($seg -match 'text="([^"]+)"') { return $matches[1] }
    return ''
}
function DescOf([string]$seg) {
    if ($seg -match 'content-desc="([^"]+)"') { return $matches[1] }
    return ''
}
# 标签后面紧跟的第一个 EditText 就是它的输入框（表单顺序稳定）
function FieldCenters([string]$xml) {
    $map = [ordered]@{}
    $pending = $null
    foreach ($seg in (Segments $xml)) {
        $text = TextOf $seg
        if ($text -and $Labels -contains $text) { $pending = $text; continue }
        if ($pending -and $seg -match 'class="android\.widget\.(EditText|Multitext)') {
            $c = CenterOf $seg
            if ($c) { $map[$pending] = $c; $pending = $null }
        }
    }
    return $map
}
function ButtonCenter([string]$xml, [string]$label) {
    foreach ($seg in (Segments $xml)) { if ((TextOf $seg) -eq $label) { $c = CenterOf $seg; if ($c) { return $c } } }
    return $null
}
# 只对「当前已聚焦」的输入框操作：清空 → 输入（文本由设备侧 shell 替换，明文不进命令行）
function PutText([string]$remoteFile) {
    Adb @('shell', 'input', 'keyevent', '123') | Out-Null   # 光标移到行尾
    for ($i = 0; $i -lt 44; $i++) { Adb @('shell', 'input', 'keyevent', '67') | Out-Null }  # 全清
    Adb @('shell', 'input text "$(cat ' + $remoteFile + ')"') | Out-Null
    Start-Sleep -Milliseconds 300
}

foreach ($f in @($EndpointFile, $EmailFile, $PasswordFile, $TotpFile)) {
    if (-not (Test-Path $f)) { throw "缺少凭据文件 $f（动态码用 scripts/relay-totp-file.sh 生成）" }
}
$prevIme = (Adb @('shell', 'settings', 'get', 'secure', 'default_input_method') | Select-Object -First 1)
Adb @('shell', 'ime', 'set', $Ime) | Out-Null
# 关掉系统级自动替换/首字母大写（拉丁键盘驱动下这两项会静默改写输入文本）
Adb @('shell', 'settings', 'put', 'secure', 'auto_replace', '0') | Out-Null
Adb @('shell', 'settings', 'put', 'secure', 'auto_capitalize', '0') | Out-Null
Adb @('shell', 'am', 'force-stop', $Package) | Out-Null
Adb @('shell', 'am', 'start', '-n', "$Package/.MainActivity") | Out-Null
Start-Sleep -Seconds 4

$xml = Dump
$all = (Segments $xml | ForEach-Object { TextOf $_ }) -join '|'
if ($all -notmatch '登录并连接') {
    "已在登录页之外（检测到已登录界面），跳过填写"
    if ($prevIme) { Adb @('shell', 'ime', 'set', ($prevIme -join '').Trim()) | Out-Null }
    exit 0
}
Adb @('shell', "mkdir -p $tmp") | Out-Null
foreach ($p in @($EndpointFile, $EmailFile, $PasswordFile, $TotpFile)) { Adb @('push', $p, "$tmp/") | Out-Null }
Adb @('shell', "chmod 600 $tmp/*") | Out-Null

$files = @{ '中继地址' = "$tmp/" + (Split-Path $EndpointFile -Leaf); '邮箱' = "$tmp/" + (Split-Path $EmailFile -Leaf); '密码' = "$tmp/" + (Split-Path $PasswordFile -Leaf); '动态验证码' = "$tmp/" + (Split-Path $TotpFile -Leaf) }
# 键盘会盖住下方输入框，坐标点不到（实测文本会打进上一格）。
# 所以只在第一格 tap 一次，之后用输入法「下一项」（keyevent 66）移动焦点。
$first = (FieldCenters (Dump))['中继地址']
if (-not $first) { throw '找不到「中继地址」输入框，界面可能已变' }
Adb @('shell', 'input', 'tap', "$($first[0])", "$($first[1])") | Out-Null
Start-Sleep -Milliseconds 800
for ($i = 0; $i -lt $Labels.Count; $i++) {
    $label = $Labels[$i]
    PutText $files[$label]
    # 输入法会静默自动更正（实测密码尾字符被首字母大写改写过，登录 401 查不出来）。
    # 密码框会显示最后一个字符，用它做尾字符校验，不匹配就在同一格里重打。
    if ($label -eq '密码') {
        $expect = (Get-Content $PasswordFile -Raw).TrimEnd("`r", "`n")[-1]
        for ($try = 0; $try -lt 3; $try++) {
            $mask = $null
            foreach ($seg in (Segments (Dump))) {
                $t = TextOf $seg
                if ($t -match '^[\u2022\u2027]{6,}\S?$') { $mask = $t; break }
            }
            if (-not $mask) { "读不到密码框掩码，跳过校验"; break }
            if ($mask[-1] -eq $expect) { "密码格尾字符校验通过（长度 $($mask.Length)）"; break }
            "密码格尾字符不符（显示 $($mask[-1])，应为 $expect），重打"
            PutText $files[$label]
        }
    }
    if ($label -eq '动态验证码') {
        $hit = $false
        foreach ($seg in (Segments (Dump))) { if ((TextOf $seg) -match '^\d{6}$') { $hit = $true; break } }
        if ($hit) { '动态码格已填 6 位' } else { '动态码格没吃到文本' }
    }
    if ($i -lt $Labels.Count - 1) {
        Adb @('shell', 'input', 'keyevent', '66') | Out-Null   # 下一项
        Start-Sleep -Milliseconds 500
    } else {
        Adb @('shell', 'input', 'keyevent', '4') | Out-Null      # 最后一格收键盘
        Start-Sleep -Milliseconds 400
    }
}
# 提交前必须真的看见按钮：键盘收起后取坐标，看不见就向上滚一屏再取。
# 上一版用「dump 里没有登录并连接」推断已提交，键盘挡住按钮时误判成功（实测踩过）。
$btn = $null
for ($try = 0; $try -lt 3 -and -not $btn; $try++) {
    $btn = ButtonCenter (Dump) '登录并连接'
    if (-not $btn) { Adb @('shell', 'input', 'swipe', '608', '1900', '608', '900', '300') | Out-Null; Start-Sleep -Milliseconds 600 }
}
if (-not $btn) { throw '找不到「登录并连接」按钮（已试过滚动）' }
"已点击登录并连接，等待结果…"
Adb @('shell', 'input', 'tap', "$($btn[0])", "$($btn[1])") | Out-Null
$deadline = (Get-Date).AddSeconds(45)
$status = ''
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 3
    Adb @('shell', 'input', 'keyevent', '4') | Out-Null   # 收起键盘再判定，避免遮挡造成假阳性
    $xml = Dump
    $texts = @(Segments $xml | ForEach-Object { TextOf $_ })
    $descs = @(Segments $xml | ForEach-Object { DescOf $_ })
    # 只认正面证据：主界面右上角「更多」菜单出现，且登录按钮确实消失
    if ($descs -contains '更多' -and $texts -notcontains '登录并连接') { '登录通过，已进入主界面'; $status = 'ok'; break }
    $note = ($texts | Where-Object { $_ -match '正在验证|失败|过期|无法|配对|连接' } | Select-Object -First 1)
    if ($note) { $status = $note; break }
}
if ($status -eq 'ok') {
    if (-not $KeepFiles) { Adb @('shell', "rm -rf $tmp") | Out-Null }
    if ($prevIme) { Adb @('shell', 'ime', 'set', ($prevIme -join '').Trim()) | Out-Null }
    exit 0
}
"登录未通过：$status"
if (-not $KeepFiles) { Adb @('shell', "rm -rf $tmp") | Out-Null }
if ($prevIme) { Adb @('shell', 'ime', 'set', ($prevIme -join '').Trim()) | Out-Null }
exit 1
