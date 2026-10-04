# papersu-signing.ps1 - create / inspect the paperSU manager signing identity and
# print the exact kernel build flags that make the kernel trust this manager.
#
# WHY THIS EXISTS
#   kernel/manager/apk_sign.c decides whether an APK is "the manager":
#     * if the kernel was compiled with KSU_MANAGER_PACKAGE, the APK's package
#       name must match it, and
#     * the APK's v2 signing certificate must match EXPECTED_SIZE/EXPECTED_HASH,
#       or the second accepted signer EXPECTED_SIZE2/EXPECTED_HASH2.
#   So a rebranded, self-signed manager needs its certificate size + sha256
#   compiled into the kernel via KSU_EXPECTED_SIZE2 / KSU_EXPECTED_HASH2.
#   These defaults live in kernel/Kbuild (official SukiSU signer).
#
# Usage:
#   .\papersu-signing.ps1 -Create                     # generate key.jks, random password
#   .\papersu-signing.ps1 -Create -StorePass X -KeyPass X
#   .\papersu-signing.ps1                             # re-inspect and print flags

param(
    [switch]$Create,
    [string]$StorePass,
    [string]$KeyPass,
    [string]$Alias     = 'papersu',
    [string]$StoreFile = 'key.jks'
)

$ErrorActionPreference = 'Continue'
$repo    = $PSScriptRoot
$manager = Join-Path $repo 'manager'
$jbrBin  = 'D:\Program Files\Android\Android Studio\jbr\bin'
$keytool = Join-Path $jbrBin 'keytool.exe'
if (-not (Test-Path $keytool)) { throw "keytool not found: $keytool" }

$storePath = Join-Path $manager $StoreFile
$work      = 'D:\Users\code\.papersu-work'
New-Item -ItemType Directory -Path $work -Force | Out-Null
$certDer   = Join-Path $work 'papersu-cert.der'

if ($Create) {
    if (-not $StorePass) {
        $StorePass = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })
    }
    if (-not $KeyPass) { $KeyPass = $StorePass }

    if (Test-Path $storePath) { throw "$storePath already exists - refusing to overwrite your signing identity" }

    & $keytool -genkeypair -v `
        -alias $Alias -keyalg RSA -keysize 2048 -validity 10000 `
        -dname 'CN=paperSU, OU=paperSU, O=paperSU, L=City, ST=State, C=CN' `
        -keystore $storePath -storetype JKS `
        -storepass $StorePass -keypass $KeyPass 2>&1 | ForEach-Object { Write-Host $_ }
    if (-not (Test-Path $storePath)) { throw 'keytool failed to create the keystore' }

    Write-Host ''
    Write-Host '==================== SAVE THIS ===================='
    Write-Host "keystore : $storePath"
    Write-Host "alias    : $Alias"
    Write-Host "storePass: $StorePass"
    Write-Host "keyPass  : $KeyPass"
    Write-Host '==================================================='
    Write-Host ''
} else {
    if (-not (Test-Path $storePath)) { throw "$storePath not found - run with -Create first" }
    if (-not $StorePass) { throw 'Pass -StorePass so the certificate can be exported' }
}

# ---------------------------------------------------------------- export + hash
& $keytool -exportcert -alias $Alias -keystore $storePath -storepass $StorePass -file $certDer 2>&1 |
    ForEach-Object { Write-Host $_ }
if (-not (Test-Path $certDer)) { throw 'failed to export the certificate' }

$bytes  = [System.IO.File]::ReadAllBytes($certDer)
$sizeDec = $bytes.Length
$sizeHex = '0x{0:x4}' -f $sizeDec
$sha    = [System.Security.Cryptography.SHA256]::Create()
$hash   = ($sha.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join ''

Write-Host ''
Write-Host '============ manager signing certificate ============'
Write-Host "size (decimal): $sizeDec"
Write-Host "size (hex)    : $sizeHex"
Write-Host "sha256        : $hash"
Write-Host '====================================================='
Write-Host ''
Write-Host 'Add these to the Gradle USER home gradle.properties'
Write-Host "(currently D:\Users\code\.gradle\gradle.properties), NOT to the repo:"
Write-Host "  KEYSTORE_FILE=$StoreFile"
Write-Host "  KEYSTORE_PASSWORD=<store password>"
Write-Host "  KEY_ALIAS=$Alias"
Write-Host "  KEY_PASSWORD=<key password>"
Write-Host ''
Write-Host 'Build the kernel / LKM with these extra defines:'
Write-Host "  KSU_MANAGER_PACKAGE=top.becuy.eric.papersu"
Write-Host "  KSU_EXPECTED_SIZE2=$sizeHex"
Write-Host "  KSU_EXPECTED_HASH2=$hash"
Write-Host ''
Write-Host 'Examples:'
Write-Host "  make ... CONFIG_KSU=y KSU_MANAGER_PACKAGE=top.becuy.eric.papersu KSU_EXPECTED_SIZE2=$sizeHex KSU_EXPECTED_HASH2=$hash"
Write-Host "  (kernel/setup.sh integration: pass the same variables to the kernel build)"
