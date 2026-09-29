# Signs a release APK with Arnav Island for Android's release key, and checks the result.
# The key is made once, on first use, and kept in %LOCALAPPDATA%\ArnavIslandAndroid\Signing: a keystore and its password,
# the password sealed with Windows DPAPI for this Windows account. Back both files up together. The app installs updates
# only when they are signed with this key, so a lost key means people have to reinstall by hand.
param(
    [Parameter(Mandatory = $true)][string]$UnsignedApk,
    [Parameter(Mandatory = $true)][string]$OutputApk,
    [string]$JavaHome = 'C:\Program Files\Android\Android Studio\jbr',
    [string]$AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk"
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Security
$signingDirectory = Join-Path $env:LOCALAPPDATA 'ArnavIslandAndroid\Signing'
New-Item -ItemType Directory -Path $signingDirectory -Force | Out-Null
$store = Join-Path $signingDirectory 'release.jks'
$secret = Join-Path $signingDirectory 'password.dpapi'
if ((Test-Path -LiteralPath $store) -ne (Test-Path -LiteralPath $secret)) { throw 'The signing identity is incomplete. Restore its backup; do not make a new release key.' }
$env:JAVA_HOME = $JavaHome
# keytool and apksigner report progress on stderr; their exit codes decide.
$ErrorActionPreference = 'Continue'
try {
    if (!(Test-Path -LiteralPath $store)) {
        $bytes = New-Object byte[] 48
        $random = [Security.Cryptography.RandomNumberGenerator]::Create(); $random.GetBytes($bytes); $random.Dispose()
        $password = [Convert]::ToBase64String($bytes)
        $sealed = [Security.Cryptography.ProtectedData]::Protect([Text.Encoding]::UTF8.GetBytes($password), $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        [IO.File]::WriteAllBytes($secret, $sealed)
        $env:ARNAVISLAND_SIGN_PASSWORD = $password
        & (Join-Path $JavaHome 'bin\keytool.exe') -genkeypair -keystore $store -storetype JKS -alias arnavisland -keyalg RSA -keysize 4096 -validity 10000 -dname 'CN=Arnav Island for Android, O=Arnav Dugad' -storepass:env ARNAVISLAND_SIGN_PASSWORD -keypass:env ARNAVISLAND_SIGN_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw 'Making the release key failed' }
    } else {
        $plain = [Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes($secret), $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        $env:ARNAVISLAND_SIGN_PASSWORD = [Text.Encoding]::UTF8.GetString($plain)
        [Array]::Clear($plain, 0, $plain.Length)
    }
    $tools = Get-ChildItem (Join-Path $AndroidSdk 'build-tools') | Sort-Object { [version]($_.Name -replace '-.*$', '') } -Descending | Select-Object -First 1
    $signer = Join-Path $tools.FullName 'apksigner.bat'
    & $signer sign --ks $store --ks-key-alias arnavisland --ks-pass env:ARNAVISLAND_SIGN_PASSWORD --key-pass env:ARNAVISLAND_SIGN_PASSWORD --out $OutputApk $UnsignedApk
    if ($LASTEXITCODE -ne 0) { throw 'Signing the APK failed' }
    & $signer verify --verbose --print-certs $OutputApk | Select-String -Pattern 'Verified using|Signer #1 certificate (DN|SHA-256)'
    if ($LASTEXITCODE -ne 0) { throw 'The signed APK did not verify' }
} finally {
    Remove-Item Env:ARNAVISLAND_SIGN_PASSWORD -ErrorAction SilentlyContinue
    $password = $null
}
