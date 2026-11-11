# 生成本地联调用的 TLS 物料（只在仓库里写两个文件，不碰 Windows 证书存储）。
#
# 为什么是「根 CA + 叶子」两段式而不是一张自签证书：
#   debug 源集的 network_security_config 通过 <certificates src="@raw/test_ca"/> 把一张证书
#   钉成**信任锚**。Android 的 TrustManager 要求锚必须是 CA（Basic Constraints CA:TRUE 且
#   pathlen 允许），而早期这里生成的是一张「自签 + CA:TRUE + 无 serverAuth」的叶子，签出来的
#   服务端证书链校验直接报 `Trust anchor for certification path not found`
#   （2026-10-27 早期记录的坑，2026-10-29 又踩了一次：换了根 CA 却没重生成叶子，
#   p12 里还是旧的自签证书，debug 包连本地中继必然 TLS 失败）。
#
# 产物：
#   artifacts/fixture-ca.pem     根 CA 公钥  → 复制到 android/app/src/debug/res/raw/test_ca.pem
#   artifacts/fixture-ca-key.pem 根 CA 私钥  （gitignore）
#   artifacts/fixture-cert.pem   叶子证书（SAN: localhost / relay.local.test / 127.0.0.1）
#   artifacts/fixture-key.pem    叶子私钥    （gitignore）
#   artifacts/android-fixture.p12 旧路径兼容：仅当 scripts/local-e2e.ts 找不到 PEM 时才用
#
# scripts/local-e2e.ts 优先读 PEM 两件套（fixture-cert.pem + fixture-key.pem），
# 只有它们不存在时才回落到 p12 —— 所以这里**必须**同时写 PEM。
#
# 用法：pwsh -NoProfile -File scripts/init-android-fixture.ps1
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$artifactPath = Join-Path $taskRoot 'artifacts'
$publicPath = Join-Path $taskRoot 'android/app/src/debug/res/raw'
New-Item -ItemType Directory -Force -Path $artifactPath, $publicPath | Out-Null

$caKey = [System.Security.Cryptography.RSA]::Create(2048)
$leafKey = [System.Security.Cryptography.RSA]::Create(2048)
try {
    # ---- 根 CA ----
    $caReq = [System.Security.Cryptography.X509Certificates.CertificateRequest]::new(
        'CN=DSH Local Fixture CA', $caKey,
        [System.Security.Cryptography.HashAlgorithmName]::SHA256,
        [System.Security.Cryptography.RSASignaturePadding]::Pkcs1)
    # pathlen:0 —— 只允许它签叶子，不允许再签中间 CA。
    $caReq.CertificateExtensions.Add([System.Security.Cryptography.X509Certificates.X509BasicConstraintsExtension]::new($true, $false, 0, $true))
    $caReq.CertificateExtensions.Add([System.Security.Cryptography.X509Certificates.X509KeyUsageExtension]::new(
        [System.Security.Cryptography.X509Certificates.X509KeyUsageFlags]::KeyCertSign -bor
        [System.Security.Cryptography.X509Certificates.X509KeyUsageFlags]::CrlSign, $true))
    $caReq.CertificateExtensions.Add([System.Security.Cryptography.X509Certificates.X509SubjectKeyIdentifierExtension]::new($caReq.PublicKey, $false))
    $ca = $caReq.CreateSelfSigned([DateTimeOffset]::UtcNow.AddMinutes(-5), [DateTimeOffset]::UtcNow.AddYears(10))
    try {
        # ---- 叶子（由 CA 签，不是自签）----
        $leafReq = [System.Security.Cryptography.X509Certificates.CertificateRequest]::new(
            'CN=localhost', $leafKey,
            [System.Security.Cryptography.HashAlgorithmName]::SHA256,
            [System.Security.Cryptography.RSASignaturePadding]::Pkcs1)
        $leafReq.CertificateExtensions.Add([System.Security.Cryptography.X509Certificates.X509BasicConstraintsExtension]::new($false, $false, 0, $true))
        $leafReq.CertificateExtensions.Add([System.Security.Cryptography.X509Certificates.X509KeyUsageExtension]::new(
            [System.Security.Cryptography.X509Certificates.X509KeyUsageFlags]::DigitalSignature -bor
            [System.Security.Cryptography.X509Certificates.X509KeyUsageFlags]::KeyEncipherment, $true))
        # 缺 serverAuth 的证书会被 Android 当作「不能用于 TLS 服务端」而拒（早期记录中的原始坑）。
        # 注意 OID 必须真的加进去：给个空集合会生成 `X509v3 Extended Key Usage: 0.`（实测），
        # 校验照样失败。1.3.6.1.5.5.7.3.1 = id-kp-serverAuth。
        $eku = [System.Security.Cryptography.OidCollection]::new()
        [void]$eku.Add([System.Security.Cryptography.Oid]::new('1.3.6.1.5.5.7.3.1', 'serverAuth'))
        $leafReq.CertificateExtensions.Add([System.Security.Cryptography.X509Certificates.X509EnhancedKeyUsageExtension]::new($eku, $true))
        $names = [System.Security.Cryptography.X509Certificates.SubjectAlternativeNameBuilder]::new()
        $names.AddDnsName('localhost')
        $names.AddDnsName('relay.local.test')
        $names.AddIpAddress([System.Net.IPAddress]::Loopback)
        $leafReq.CertificateExtensions.Add($names.Build())

        $serial = [byte[]]::new(16)
        [System.Security.Cryptography.RandomNumberGenerator]::Fill($serial)
        $serial[0] = $serial[0] -band 0x7F   # 正数
        $notBefore = [DateTimeOffset]::UtcNow.AddMinutes(-5)
        $notAfter = [DateTimeOffset]::UtcNow.AddYears(2)
        $leaf = $leafReq.Create($ca, $notBefore, $notAfter, $serial)
        try {
            # 写文件：CA 公钥给 APK 钉信任锚，叶子+私钥给 local-e2e 当服务端证书。
            [System.IO.File]::WriteAllText((Join-Path $artifactPath 'fixture-ca.pem'), $ca.ExportCertificatePem())
            [System.IO.File]::WriteAllText((Join-Path $publicPath 'test_ca.pem'), $ca.ExportCertificatePem())

            # PEM 私钥（PKCS#8）。换行必须是 \n，Node 的 tls 才认。
            $leafKeyPem = $leafKey.ExportPkcs8PrivateKeyPem()
            [System.IO.File]::WriteAllText((Join-Path $artifactPath 'fixture-key.pem'), $leafKeyPem)
            [System.IO.File]::WriteAllText((Join-Path $artifactPath 'fixture-cert.pem'), $leaf.ExportCertificatePem())
            $caKeyPem = $caKey.ExportPkcs8PrivateKeyPem()
            [System.IO.File]::WriteAllText((Join-Path $artifactPath 'fixture-ca-key.pem'), $caKeyPem)

            # 旧路径兼容：p12 里放叶子 + 私钥（local-e2e 只在 PEM 缺失时才回落到它）。
            $p12 = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new(
                $leaf.Export([System.Security.Cryptography.X509Certificates.X509ContentType]::Cert))
            $withKey = [System.Security.Cryptography.X509Certificates.RSACertificateExtensions]::CopyWithPrivateKey($p12, $leafKey)
            try {
                [System.IO.File]::WriteAllBytes(
                    (Join-Path $artifactPath 'android-fixture.p12'),
                    $withKey.Export([System.Security.Cryptography.X509Certificates.X509ContentType]::Pkcs12, 'local-fixture-only'))
            } finally { $withKey.Dispose(); $p12.Dispose() }
        } finally { $leaf.Dispose() }
    } finally { $ca.Dispose() }
} finally {
    $caKey.Dispose()
    $leafKey.Dispose()
}
Write-Output 'Local TLS fixture generated (CA + leaf). Rebuild debug APK before running instrumentation. Never use this key outside local tests.'
