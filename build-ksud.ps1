# build-ksud.ps1 - Windows-native ksud / ksuinit build for paperSU (SukiSU-Ultra based).
#
# Upstream's `justfile` shells out to `cross` (requires Docker). On Windows plain
# cargo works; this script reproduces .github/workflows/ksud.yml and ksuinit.yml,
# wiring the NDK toolchain itself.
#
# Usage:
#   .\build-ksud.ps1                                        # ksud, arm64-v8a
#   .\build-ksud.ps1 -Triple x86_64-linux-android
#   .\build-ksud.ps1 -Triple armv7-linux-androideabi
#   .\build-ksud.ps1 -Ksuinit                               # + build/stage ksuinit first
#   .\build-ksud.ps1 -Ksuinit -Triple x86_64-linux-android
#
# Two different directory-naming schemes are involved and they are NOT the same:
#   * userspace/ksud/bin/<aarch64|arm|x86_64>/   <- rust-embed assets (ksuinit, *.ko)
#   * manager/app/src/main/jniLibs/<arm64-v8a|armeabi-v7a|x86_64>/  <- APK native libs
#
# ksuinit is a Rust binary and builds fine here. The LKM (*_kernelsu.ko) can NOT be
# built on Windows: it needs a Linux kernel build tree / the Android DDK
# (scriptsprepare-ddk-x64.sh uses /opt/ddk, .github/workflows/ddk-lkm.yml runs in a
# ghcr.io/ylarod/ddk-min container). See install-lkm.ps1.

param(
    [string]$Triple      = 'aarch64-linux-android',
    [int]   $ApiLevel    = 26,
    [string]$PackageName = 'top.becuy.eric.papersu',
    [switch]$Ksuinit
)

$ErrorActionPreference = 'Continue'
# NOTE: deliberately not 'Stop'. Under Windows PowerShell 5.1, a native command
# writing ordinary progress text to stderr (cargo does this constantly) is turned
# into a terminating NativeCommandError when ErrorActionPreference is 'Stop'.
$repo = $PSScriptRoot

# Keep cargo/rustc scratch files off the C: drive (junctions cannot redirect %TEMP%).
$buildTmp = 'D:\Users\code\.papersu-work\tmp'
if (-not (Test-Path $buildTmp)) { New-Item -ItemType Directory -Path $buildTmp -Force | Out-Null }
$env:TEMP = $buildTmp
$env:TMP = $buildTmp

# ---------------------------------------------------------------- locate SDK/NDK
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME }
       elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
       else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }

$ndkRoot = Get-ChildItem (Join-Path $sdk 'ndk') -Directory -ErrorAction SilentlyContinue |
           Sort-Object Name -Descending | Select-Object -First 1
if (-not $ndkRoot) { throw "No NDK found under $sdk\ndk" }
$ndk = $ndkRoot.FullName

$llvm    = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64'
$llvmBin = Join-Path $llvm 'bin'

$ndkTriple = if ($Triple -eq 'armv7-linux-androideabi') { 'armv7a-linux-androideabi' } else { $Triple }
$clang     = Join-Path $llvmBin "$ndkTriple$ApiLevel-clang.cmd"
$clangpp   = Join-Path $llvmBin "$ndkTriple$ApiLevel-clang++.cmd"
if (-not (Test-Path $clang)) { throw "Compiler not found: $clang" }

$uTriple  = $Triple.Replace('-', '_')
$uuTriple = $uTriple.ToUpper()

# ------------------------------------------------------- directory-name mappings
$rustEmbedDir = switch -Wildcard ($Triple) {
    'aarch64*' { 'aarch64' }
    'armv7*'   { 'arm' }
    'x86_64*'  { 'x86_64' }
    default    { throw "Unsupported triple: $Triple" }
}
$jniAbi = switch -Wildcard ($Triple) {
    'aarch64*' { 'arm64-v8a' }
    'armv7*'   { 'armeabi-v7a' }
    'x86_64*'  { 'x86_64' }
    default    { throw "Unsupported triple: $Triple" }
}
# upstream ksuinit.yml only builds these two architectures
$ksuinitArch = switch -Wildcard ($Triple) {
    'aarch64*' { 'aarch64' }
    'x86_64*'  { 'x86_64' }
    default    { $null }
}

# ------------------------------------------------------- toolchain wiring
# build.rs assembles the AArch64 LKM bootstrap object with the NDK clang that it
# locates through these variables.
$env:ANDROID_NDK_HOME = $ndk
$env:ANDROID_NDK_ROOT = $ndk

# bindgen (clang-sys) loads libclang.dll with default LoadLibrary flags, so its
# sibling DLLs (libxml2.dll, libwinpthread-1.dll) are NOT resolved from the same
# directory and the load fails with LoadLibraryExW error. Putting the NDK bin
# directory on PATH fixes it.
$env:LIBCLANG_PATH = $llvmBin
$env:PATH = "$llvmBin;$env:PATH"

# Dynamic env-var names must go through SetEnvironmentVariable: "$env:CC_$X" is
# not valid PowerShell syntax.
$llvmAr = Join-Path $llvmBin 'llvm-ar.exe'
[Environment]::SetEnvironmentVariable("CC_$uTriple",  $clang,   'Process')
[Environment]::SetEnvironmentVariable("CXX_$uTriple", $clangpp, 'Process')
[Environment]::SetEnvironmentVariable("AR_$uTriple",  $llvmAr,  'Process')
[Environment]::SetEnvironmentVariable("CARGO_TARGET_${uuTriple}_LINKER", $clang, 'Process')

# bindgen args. THREE things matter here:
#  * sysroot must use forward slashes (upstream scripts do the same on purpose);
#  * an explicit --target is REQUIRED - without it libclang falls back to the
#    host (Windows/MSVC) target and then <linux/ioctl.h> cannot be resolved;
#  * the unsuffixed BINDGEN_EXTRA_CLANG_ARGS is always honoured by bindgen.
$sysrootFwd = "$llvm\sysroot".Replace('\', '/')
$includeFwd = "$llvm\sysroot\usr\include\$Triple".Replace('\', '/')
$bindgenArgs = "--target=$ndkTriple$ApiLevel --sysroot=$sysrootFwd -I$includeFwd"
[Environment]::SetEnvironmentVariable('BINDGEN_EXTRA_CLANG_ARGS', $bindgenArgs, 'Process')
[Environment]::SetEnvironmentVariable("BINDGEN_EXTRA_CLANG_ARGS_$uTriple", $bindgenArgs, 'Process')
[Environment]::SetEnvironmentVariable("BINDGEN_EXTRA_CLANG_ARGS_$Triple", $bindgenArgs, 'Process')

# ksud embeds the manager package name; build.rs falls back to com.sukisu.ultra.
$env:KSU_PACKAGE_NAME = $PackageName

Write-Host "NDK             : $ndk"
Write-Host "triple          : $Triple (API $ApiLevel)"
Write-Host "rust-embed dir  : userspace/ksud/bin/$rustEmbedDir"
Write-Host "APK jniLibs dir : manager/app/src/main/jniLibs/$jniAbi"
Write-Host "KSU_PACKAGE_NAME: $PackageName"
Write-Host ""

Push-Location $repo
try {
    # ------------------------------------------------------------- ksuinit
    if ($Ksuinit) {
        if (-not $ksuinitArch) {
            Write-Host "[ksuinit] 上游只构建 aarch64 / x86_64，跳过 $Triple"
        }
        else {
            $resourceDir = (& $clang --print-resource-dir) -join ''
            $resourceDir = $resourceDir.Trim()
            $builtins = Join-Path $resourceDir "lib\linux\libclang_rt.builtins-$ksuinitArch-android.a"
            if (-not (Test-Path $builtins)) { throw "builtins not found: $builtins" }

            Write-Host "[ksuinit] resource-dir : $resourceDir"
            Write-Host "[ksuinit] builtins     : $builtins"

            $env:RUSTFLAGS = "-C target-feature=+crt-static -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wno-unused-command-line-argument -C link-arg=$builtins"
            & cargo build --package ksuinit --target=$Triple --release 2>&1 |
                ForEach-Object { Write-Host $_ }
            if ($LASTEXITCODE -ne 0) { throw "ksuinit build failed with exit code $LASTEXITCODE" }
            # do not leak the ksuinit-only flags into the ksud link
            [Environment]::SetEnvironmentVariable('RUSTFLAGS', $null, 'Process')

            $exe = Join-Path $repo "target\$Triple\release\ksuinit"
            $dst = Join-Path $repo "userspace\ksud\bin\$rustEmbedDir"
            New-Item -ItemType Directory -Path $dst -Force | Out-Null
            Copy-Item $exe (Join-Path $dst 'ksuinit') -Force
            Write-Host "[ksuinit] staged -> userspace/ksud/bin/$rustEmbedDir/ksuinit ($((Get-Item $exe).Length) B)"
        }
    }

    # --------------------------------------------------------------- ksud
    # Route the native command's stderr through Write-Host so PS 5.1 does not
    # raise NativeCommandError, while keeping $LASTEXITCODE intact.
    & cargo build --target $Triple --release --manifest-path (Join-Path $repo 'userspace\ksud\Cargo.toml') 2>&1 |
        ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) { throw "cargo build failed with exit code $LASTEXITCODE" }

    $exe = Join-Path $repo "target\$Triple\release\ksud"
    $dst = Join-Path $repo "manager\app\src\main\jniLibs\$jniAbi"
    New-Item -ItemType Directory -Path $dst -Force | Out-Null
    Copy-Item $exe (Join-Path $dst 'libksud.so') -Force

    $mb = [math]::Round((Get-Item (Join-Path $dst 'libksud.so')).Length / 1MB, 1)
    Write-Host ""
    Write-Host "staged libksud.so ($mb MB) -> $dst"

    # ----------------------------------------------------------------- report
    $koDir = Join-Path $repo "userspace\ksud\bin\$rustEmbedDir"
    $koFiles = @(Get-ChildItem $koDir -Filter '*_kernelsu.ko' -ErrorAction SilentlyContinue)
    if ($koFiles.Count -eq 0) {
        Write-Host "[GKI] userspace/ksud/bin/$rustEmbedDir/ has no *_kernelsu.ko"
        Write-Host "      -> Built-in mode is ready; GKI (LKM) needs the .ko, see install-lkm.ps1"
    } else {
        Write-Host "[GKI] embedded LKM images: $($koFiles.Name -join ', ')"
    }
    Write-Host "next: cd manager; .\gradlew.bat :app:assembleDebug"
}
finally {
    Pop-Location
}
