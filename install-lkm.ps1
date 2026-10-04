# install-lkm.ps1 - stage SukiSU LKM images (*_kernelsu.ko) into the place ksud
# embeds them from, so the paperSU Manager can flash / late-load the LKM (GKI mode).
#
# WHY THIS IS A SEPARATE STEP
#   The LKM cannot be built on Windows: it needs a Linux kernel build tree.
#   Upstream builds it in the ghcr.io/ylarod/ddk-min container
#   (.github/workflows/ddk-lkm.yml) driven by .github/workflows/build-lkm.yml,
#   which now defaults to paperSU's own signing certificate.
#
# RECIPE
#   1. Push this repo to GitHub (or run the workflow on a fork).
#   2. Actions -> "Build LKM for KernelSU" -> Run workflow.
#      The defaults already carry paperSU's signature:
#        expected_size2 = 0x036d
#        expected_hash2 = 14ea1b98c9f693f282a05022b8cf02dcbd2dd8e28c9d6b97bd6acfda1352f82f
#   3. Download the "aarch64-<kmi>-lkm" (and optionally "x86_64-<kmi>-lkm")
#      artifacts into one folder, e.g. D:\Users\code\.papersu-work\lkm\
#   4. .\install-lkm.ps1 -Source D:\Users\code\.papersu-work\lkm
#   5. Re-run .\build-ksud.ps1 for each ABI so ksud embeds them, then rebuild the APK.
#
# Usage:
#   .\install-lkm.ps1 -Source <folder>
#   .\install-lkm.ps1 -Source <folder> -WhatIfOnly     # just show what would happen

param(
    [Parameter(Mandatory = $true)]
    [string]$Source,
    [switch]$WhatIfOnly
)

$ErrorActionPreference = 'Continue'
$repo = $PSScriptRoot

if (-not (Test-Path $Source)) { throw "Source folder not found: $Source" }

# Minimal ELF machine detection - same check ksud's build.rs performs.
function Get-ElfArch([string]$Path) {
    try { $b = [System.IO.File]::ReadAllBytes($Path) } catch { return $null }
    if ($b.Length -lt 20) { return $null }
    if (-not ($b[0] -eq 0x7F -and $b[1] -eq 0x45 -and $b[2] -eq 0x4C -and $b[3] -eq 0x46)) { return $null }
    $machine = $b[18] -bor ($b[19] -shl 8)
    switch ($machine) {
        183  { 'aarch64' }   # EM_AARCH64
        62   { 'x86_64' }    # EM_X86_64
        40   { 'arm' }       # EM_ARM
        default { "unknown(0x{0:x})" -f $machine }
    }
}

$kos = @(Get-ChildItem $Source -Recurse -File -Filter '*_kernelsu.ko' -ErrorAction SilentlyContinue)
if ($kos.Count -eq 0) {
    Write-Host "No *_kernelsu.ko found under $Source"
    Write-Host 'Expected CI artifact layout: aarch64-<kmi>-lkm/<kmi>_kernelsu.ko'
    exit 1
}

$staged = 0
foreach ($ko in $kos) {
    $arch = Get-ElfArch $ko.FullName
    if (-not $arch -or $arch -like 'unknown*') {
        Write-Host ("SKIP  {0}  (not a recognised ELF .ko: {1})" -f $ko.Name, $arch)
        continue
    }
    $kmi = $ko.Name -replace '_kernelsu\.ko$', ''
    if ([string]::IsNullOrWhiteSpace($kmi)) {
        Write-Host ("SKIP  {0}  (cannot derive KMI name)" -f $ko.Name); continue
    }
    $dstDir = Join-Path $repo "userspace\ksud\bin\$arch"
    $dst = Join-Path $dstDir $ko.Name
    if ($WhatIfOnly) {
        Write-Host ("WOULD {0,-34} -> userspace/ksud/bin/{1}/{2}" -f $ko.Name, $arch, $ko.Name)
    } else {
        New-Item -ItemType Directory -Path $dstDir -Force | Out-Null
        Copy-Item $ko.FullName $dst -Force
        Write-Host ("OK    {0,-34} -> userspace/ksud/bin/{1}/   ({2:N0} B)" -f $ko.Name, $arch, $ko.Length)
    }
    $staged++
}

Write-Host ''
Write-Host ("staged {0} LKM image(s) for KMI: {1}" -f $staged, (($kos | ForEach-Object { $_.Name -replace '_kernelsu\.ko$','' } | Sort-Object -Unique) -join ', '))

if (-not $WhatIfOnly -and $staged -gt 0) {
    Write-Host ''
    Write-Host 'Now re-embed them into ksud and rebuild the APK:'
    Write-Host '  .\build-ksud.ps1                                   # arm64-v8a'
    Write-Host '  .\build-ksud.ps1 -Triple x86_64-linux-android      # x86_64 (if you staged x64)'
    Write-Host '  .\build-ksud.ps1 -Triple armv7-linux-androideabi   # armeabi-v7a'
    Write-Host '  cd manager; .\gradlew.bat :app:assembleRelease'
}
