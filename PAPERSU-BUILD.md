# paperSU 构建与改造说明

本文件记录本次改造的全部事实、命令与注意事项。除本文件外，另有两个未跟踪的辅助脚本：
`build-ksud.ps1`（Windows 构建 ksud）与 `papersu-signing.ps1`（签名身份与内核契约）。

---

## 1. 项目基底

| 项 | 值 |
|---|---|
| 基底仓库 | https://github.com/SukiSU-Ultra/SukiSU-Ultra |
| 分支 / 提交 | `main` @ `7fbbb1f1` |
| 本地分支 | `paperSU`（跟踪 `sukisu/main`） |
| 远端 | `origin` = KernelSU（原克隆），`sukisu` = SukiSU-Ultra |

**为什么不用 KernelSU 做基底**：SukiSU-Ultra 与 KernelSU 的共同祖先停在 2025-03-02，
SukiSU 领先 1886 个提交、改动 945 个文件，其中 **466 个文件双方都改过**；且内核模块目录结构
（`kernel/core|hook|infra|kpm|supercall…`）与 manager 源码包（`com.sukisu.ultra` vs
`me.weishu.kernelsu`）都不存在一一对应关系。强行 `git merge` 只会得到一棵编译不过的树。
SukiSU-Ultra 本身就是 KernelSU 的分叉，因此以它为基底既满足「KSU 源码修改」，又天然具备
GKI 与 Built-in 两种集成模式。

---

## 2. 品牌改造（包名 / 应用名）

SukiSU-Ultra 原生支持自定义包名与应用名（即 KernelSU PR #3560 引入的机制），
因此**不需要改散落在 1000+ 处的源码包引用**，只设两个 Gradle 属性即可。

改动位置：`manager/gradle.properties`

```properties
# ---- paperSU branding (paperSU is a rebrand built on SukiSU-Ultra, itself derived from KernelSU) ----
KSU_PACKAGE_NAME=top.becuy.eric.papersu
KSU_NAME=paperSU
```

对应 `manager/app/build.gradle.kts`：

```kotlin
val managerPackageName = project.findProperty("KSU_PACKAGE_NAME") ?: "com.sukisu.ultra"  // -> applicationId
val managerName        = project.findProperty("KSU_NAME")         ?: "SukiSU"             // -> app_name
namespace = "com.sukisu.ultra"   // 源码包名，保持不变
```

验证结果（`aapt2 dump badging`）：

```
package: name='top.becuy.eric.papersu' versionCode='40940' versionName='v1.0.3'
application-label:'paperSU'          # 全部 90+ 语言一致
launchable-activity: name='com.sukisu.ultra.ui.MainActivity'
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86_64'
```

> `KSU_PACKAGE_NAME` **同时**必须用于 ksud 构建（见第 3 节），否则 ksud 内嵌的包名
> 仍是 `com.sukisu.ultra`，与 manager 不一致。

---

## 3. Windows 构建 ksud

```powershell
.\build-ksud.ps1                                  # arm64-v8a
.\build-ksud.ps1 -Triple armeabi-v7a 对应的 triple
.\build-ksud.ps1 -Triple armv7-linux-androideabi  # armeabi-v7a
.\build-ksud.ps1 -Triple x86_64-linux-android
```

脚本会自动：定位最新 NDK → 设置 `ANDROID_NDK_HOME` / `LIBCLANG_PATH` / `CC_*` `CXX_*` `AR_*` /
`CARGO_TARGET_*_LINKER` / `BINDGEN_EXTRA_CLANG_ARGS*` → 设 `KSU_PACKAGE_NAME` →
`cargo build --release --manifest-path userspace\ksud\Cargo.toml` →
把产物拷成 `manager\app\src\main\jniLibs\<abi>\libksud.so`。

上游 `justfile` 用的是 `cross build`（需要 Docker）。Windows 上不需要 Docker，
但上游自带的 `.github/scripts/setup-rust-build.ps1` 在本机是坏的，本仓库已修复，见第 6 节。

已构建产物（内嵌 `ksuinit` 之后）：

| ABI | libksud.so | 说明 |
|---|---|---|
| arm64-v8a | 5,949,832 B | 含 ksuinit（+332,024 B） |
| armeabi-v7a | 3,694,320 B | 上游不为 arm 构建 ksuinit |
| x86_64 | 5,084,272 B | 含 ksuinit（+358,096 B） |

加 `-Ksuinit` 会先编 `ksuinit` 放到 `userspace/ksud/bin/<arch>/ksuinit`，再编 ksud 使其内嵌
（GKI 模式所需，详见第 7 节）：

```powershell
.\build-ksud.ps1 -Ksuinit                              # aarch64
.\build-ksud.ps1 -Ksuinit -Triple x86_64-linux-android
```

---

## 4. 构建 Manager APK

```powershell
cd manager
$env:JAVA_HOME = 'D:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug     # 调试包
.\gradlew.bat :app:assembleRelease   # 正式包（用 key.jks 签名）
```

产物：

| 产物 | 大小 |
|---|---|
| `manager\app\build\outputs\apk\debug\paperSU_v1.0.3_40940-debug.apk` | 33,219,811 B |
| `manager\app\build\outputs\apk\release\paperSU_v1.0.3_40940-release.apk` | 11,836,626 B |

Android Studio 请打开 **`manager`** 目录（它是独立 Gradle 工程，有自己的
`settings.gradle.kts` 与 wrapper），不要打开仓库根目录。

工具链版本要求（与 KernelSU 版本相同，本机已全部就绪）：
AGP 9.4.1 / Kotlin 2.4.20 / Gradle 9.7.1 / compileSdk 37 / build-tools 37.0.0 /
**NDK 29.0.14206865** / minSdk 26 / Java 21 源目标（Android Studio 自带 JBR 25 可用）。

---

## 5. 签名身份与内核契约（关键）

### 5.1 内核如何识别 manager

`kernel/manager/apk_sign.c`：

```c
bool is_manager_apk(char *path)
{
#ifdef KSU_MANAGER_PACKAGE
    // 包名必须匹配（若内核编译时定义了该宏）
    if (strncmp(pkg, KSU_MANAGER_PACKAGE, sizeof(KSU_MANAGER_PACKAGE))) return false;
#endif
    if (check_v2_signature(path, EXPECTED_SIZE, EXPECTED_HASH)) return true;      // 官方签名
#ifdef EXPECTED_SIZE2
    return check_v2_signature(path, EXPECTED_SIZE2, EXPECTED_HASH2);             // 第二个被接受的签名
#else
    return false;
#endif
}
```

即：**包名（可选）+ APK v2 签名证书（必需）**。默认值在 `kernel/Kbuild`：

```make
KSU_EXPECTED_SIZE := 0x35c
KSU_EXPECTED_HASH := 947ae944f3de4ed4c21a7e4f7953ecf351bfa2b36239da37a34111ad29993eef
```

### 5.2 paperSU 自己的签名身份

已生成：`manager\key.jks`（被 `manager/.gitignore` 的 `key.jks` 规则忽略，不会进版本库）

| 项 | 值 |
|---|---|
| alias | `papersu` |
| 口令 | `bZoyxH1Ta48BN7swtRkMCDWe` |
| 证书大小 | `0x036d`（877 字节） |
| 证书 SHA-256 | `14ea1b98c9f693f282a05022b8cf02dcbd2dd8e28c9d6b97bd6acfda1352f82f` |

签名配置写在**Gradle 用户目录**（`D:\Users\code\.gradle\gradle.properties`），不在仓库内：

```properties
KEYSTORE_FILE=key.jks
KEYSTORE_PASSWORD=bZoyxH1Ta48BN7swtRkMCDWe
KEY_ALIAS=papersu
KEY_PASSWORD=bZoyxH1Ta48BN7swtRkMCDWe
```

已验证 release APK 的签名与之完全一致：

```
V2 Signer: certificate SHA-256 digest: 14ea1b98c9f693f282a05022b8cf02dcbd2dd8e28c9d6b97bd6acfda1352f82f
```

**V2 签名非常关键**：`check_v2_signature()` 只认 APK Signature Scheme **v2**，v1/JAR 签名无效。

> 请务必备份 `manager\key.jks` 与上面的口令。丢失后将无法覆盖安装升级，
> 且换用新密钥必须重算证书哈希并重新编译内核。

### 5.3 编译内核时使用的宏

```sh
KSU_MANAGER_PACKAGE=top.becuy.eric.papersu
KSU_EXPECTED_SIZE2=0x036d
KSU_EXPECTED_HASH2=14ea1b98c9f693f282a05022b8cf02dcbd2dd8e28c9d6b97bd6acfda1352f82f
```

例如：

```sh
make -j$(nproc) O=out CONFIG_KSU=y \
  KSU_MANAGER_PACKAGE=top.becuy.eric.papersu \
  KSU_EXPECTED_SIZE2=0x036d \
  KSU_EXPECTED_HASH2=14ea1b98c9f693f282a05022b8cf02dcbd2dd8e28c9d6b97bd6acfda1352f82f
```

重新生成这些值：`.\papersu-signing.ps1`（会导出证书并打印 size/hash 与上面的宏）。

---

## 6. 本次修复的 Windows 陷阱

1. **`uapi` 符号链接被检出成普通文件**
   仓库用 git symlink（mode 120000）把 `uapi/` 共享给 `manager/app/src/main/cpp/uapi` 与
   `kernel/include/uapi`。本机 `core.symlinks=false` 且无管理员权限，git 把它们检出成内容为
   路径文本的普通文件，导致 CMake 报 `fatal error: 'uapi/ksu.h' file not found`。
   已改为**目录联接（junction）**：`mklink /J`，无需管理员权限。
   > 代价：`git status` 会显示这两个路径为 ` D`。**不要执行 `git checkout -- .`**，
   > 那会把它们还原成占位文件并再次弄坏构建。恢复命令：
   > ```powershell
   > # 若为占位文件则先删除文件（切勿加 -Recurse）
   > cmd /c mklink /J "manager\app\src\main\cpp\uapi" "<仓库根>\uapi"
   > cmd /c mklink /J "kernel\include\uapi"            "<仓库根>\uapi"
   > ```
   > 永久解法：开启 Windows 开发者模式后执行 `git config core.symlinks true` 并重新检出。

2. **bindgen 加载 `libclang.dll` 失败**（`LoadLibraryExW failed`）
   `clang-sys` 用默认标志 `LoadLibrary`，不会从 DLL 所在目录解析依赖，
   于是同目录的 `libxml2.dll` / `libwinpthread-1.dll` 找不到。
   解法：把 NDK 的 `toolchains\llvm\prebuilt\windows-x86_64\bin` 加入 `PATH`。

3. **bindgen 缺少 `--target`**
   `uapi/supercall.h` 第 4 行 `#include <linux/ioctl.h>`。不带 `--target` 时 libclang
   退回宿主（Windows/MSVC）目标，找不到 `linux/` 头文件，报
   `fatal error: 'linux/ioctl.h' file not found`。
   解法：`BINDGEN_EXTRA_CLANG_ARGS`（不带后缀，bindgen 必定读取）里加
   `--target=<triple><api>`，且 `--sysroot` 使用**正斜杠**（上游脚本也特意做了这个转换）。

4. **上游 `.github/scripts/setup-rust-build.ps1` 本身是坏的**
   * `$env:CC_$UTRIPLE = ...` 不是合法的 PowerShell 语法（动态变量名必须用 `Set-Item` /
     `[Environment]::SetEnvironmentVariable`）；
   * 文件是无 BOM 的 UTF-8 却含中文注释，Windows PowerShell 5.1 会按 GBK 解码，
     触发「字符串缺少终止符」这类假语法错误。
   已修复语法并加上 UTF-8 BOM。

5. **`$ErrorActionPreference='Stop'` 会杀死原生命令**
   Windows PowerShell 5.1 会把原生命令写到 stderr 的普通进度信息当成终止性错误
   （`NativeCommandError`）。cargo 全程往 stderr 写进度，因此脚本一开始就中止。
   `build-ksud.ps1` 改用 `Continue` 并把 stderr 经 `Write-Host` 转发。

6. **残留的 `.cargo/config.toml`**
   cargo 的 `[env]` 会覆盖进程环境变量，其值缺少 `--target`，
   会造成「明明设了环境变量却不生效」的假象。已删除（它由
   `scripts/setup_cargo_config.py` 生成且被 gitignore）。

---

## 7. GKI 与 Built-in 两种模式

两种模式的**源码都在本仓库内**，位于 `main` 分支，不需要再从别处「合并」：

| 模式 | 含义 | 源码 / 入口 |
|---|---|---|
| **GKI** | 内核走 kprobe hook，驱动以 **LKM（可加载内核模块）** 形式在开机后载入；manager 可刷写 boot 镜像 | `kernel/`（编成 `.ko`）；`userspace/ksud/src/lkm_image.rs`、`late_load.rs`、`boot_patch.rs`；`userspace/ksud/bin/<arch>/*_kernelsu.ko` |
| **Built-in** | 驱动直接编译进内核（`CONFIG_KSU=y`） | `kernel/setup.sh` 把 `kernel/` 接入内核源码树；`kernel/Kconfig`、`kernel/Kbuild` |

集成命令（来自 `docs/guide/how-to-integrate.md`）：

```sh
# GKI
curl -LSs ".../main/kernel/setup.sh" | bash -s main
# Built-in
curl -LSs ".../main/kernel/setup.sh" | bash -s builtin
```

> 注意：`builtin` 是一个**功能精简的派生分支**（相对 `main` 少 92,892 行，连
> `userspace/ksud/src/susfs`、`su.rs`、`utils.rs`、`ksuinit` 都没有），
> 它并不是 `main` 的超集。**`main` 自身即可同时支持两种集成方式**，
> 因此本仓库基于 `main`，没有引入 `builtin` 分支的代码。

### GKI 资产：ksuinit 已本地编好，`.ko` 需经 CI 产出

ksud 用 `rust-embed` 把 `userspace/ksud/bin/<arch>/` 整个目录编进二进制
（`userspace/ksud/src/assets.rs`，目录名是 `aarch64` / `arm` / `x86_64`，
**与 APK 的 `jniLibs` ABI 名 `arm64-v8a` 等不同**）。该目录被 `.gitignore` 忽略
（`**/*.ko`、`**/ksuinit`）。GKI 模式需要两类资产：

| 资产 | 来源 | 本机状态 |
|---|---|---|
| `ksuinit` | Rust 二进制，`cargo build --package ksuinit` | ✅ 已编好并内嵌（aarch64 607,360 B / x86_64 661,272 B） |
| `<kmi>_kernelsu.ko` | Linux 内核模块，需 Android DDK | ❌ 本机无法编译 |

`ksuinit` 的编译参数（已实现于 `build-ksud.ps1 -Ksuinit`）：

```powershell
$builtins = "$(clang --print-resource-dir)\lib\linux\libclang_rt.builtins-aarch64-android.a"
$env:RUSTFLAGS = "-C target-feature=+crt-static -C link-arg=-Wl,-z,max-page-size=16384" +
                 " -C link-arg=-Wno-unused-command-line-argument -C link-arg=$builtins"
cargo build --package ksuinit --target=aarch64-linux-android --release
# -> userspace/ksud/bin/aarch64/ksuinit
```

**`.ko` 为什么本机编不了**（已实测确认）：`scripts/prepare-ddk-x64.sh` 依赖
`/opt/ddk` 下每个 KMI 的预编译内核树，`.github/workflows/ddk-lkm.yml` 运行在
`ghcr.io/ylarod/ddk-min` 容器里；本机 **WSL 无发行版**（`wsl -l -q` 返回帮助文本）、
**Docker 未安装**，因此 Windows 原生与 WSL 都走不通。

**已为此补齐的 CI 链路**：`build-lkm.yml` 原本只给 `workflow_call` 声明了
`expected_size2/expected_hash2`，手动触发时这两个值为空 → 编出的 `.ko` **只认官方
SukiSU 签名**。现已为 `workflow_dispatch` 加上 paperSU 默认值：

```yaml
workflow_dispatch:
  inputs:
    expected_size2: { default: '0x036d' }
    expected_hash2: { default: '14ea1b98c9f693f282a05022b8cf02dcbd2dd8e28c9d6b97bd6acfda1352f82f' }
```

完整流程：

```
1. 推送到 GitHub → Actions → "Build LKM for KernelSU" → Run workflow（默认已带 paperSU 签名）
2. 下载 aarch64-<kmi>-lkm / x86_64-<kmi>-lkm 产物到同一目录
3. .\install-lkm.ps1 -Source <该目录>          # 按 ELF 架构自动归位到 bin/<arch>/
4. .\build-ksud.ps1                            # 重新内嵌
   .\build-ksud.ps1 -Triple x86_64-linux-android
   .\build-ksud.ps1 -Triple armv7-linux-androideabi
5. cd manager; .\gradlew.bat :app:assembleRelease
```

`install-lkm.ps1 -WhatIfOnly` 可先预览而不落盘。

Built-in 模式不受此限制，只需内核源码 + `CONFIG_KSU=y`，无需任何 `.ko`。


---

## 8. D 盘布局与环境（按要求全部放 D 盘，原 C 盘路径留 junction）

| 真实位置（D 盘） | 原 C 盘路径（现为 junction） | 大小 |
|---|---|---|
| `D:\Users\code\.gradle` | `C:\Users\code\.gradle` | 3.71 GB |
| `D:\Users\code\.cargo` | `C:\Users\code\.cargo` | 1.23 GB |
| `D:\Users\code\.rustup` | `C:\Users\code\.rustup` | 1.66 GB |
| `D:\Users\code\Android\Sdk` | `C:\Users\code\AppData\Local\Android\Sdk` | 8.86 GB |

junction 对程序完全透明，因此 PATH、Android Studio 的 SDK 配置、你其他 Android 项目
都不需要改动。C 盘可用空间从 10.4 GB 提升到 25.9 GB。

环境变量（用户级）：

```
ANDROID_HOME     = D:\Users\code\Android\Sdk
ANDROID_SDK_ROOT = D:\Users\code\Android\Sdk
```

### 网络环境：Steam++ hosts 模式（2026-10-03 更新，重要）

这台机器访问 GitHub 用的是 **Steam++ / Watt Toolkit 的 hosts 模式**，不是 HTTP 代理。
它的工作方式是：把 Steam + GitHub 共 40 多个域名写进 hosts 指向 `127.0.0.1`
（`github.com`、`api.github.com`、`objects.githubusercontent.com` 等），
再由它自己在**本机 80/443 端口**上做转发，并用它自己的 CA 做 TLS 中间人
（证书库里可见 `CN=SteamTools Certificate, O=BeyondDimension`）。

> **因此所有 HTTP 代理设置都必须去掉。** 之前配的 Clash `7890` 已经不再转发任何流量
> （实测每个站点经它都无响应），而 hosts 会把 `github.com` 解析回 `127.0.0.1` ——
> 两者叠加会让 git 直接握手失败（`schannel: failed to receive handshake`）。

已做的改动（都是**注释掉**、附原因，随时可恢复）：

| 位置 | 改动 |
|---|---|
| 全局 git | `http.proxy` / `https.proxy` 已 `--unset` |
| `D:\Users\code\.gradle\gradle.properties` | 5 行 `systemProp.*proxy*` 已注释 |
| `D:\Users\code\.cargo\config.toml` | `[http] proxy` 已注释（`[net]` 保留） |

**各工具为什么表现不同**（这点很关键，别被误导）：

| 工具 | 用的 CA | 结果 |
|---|---|---|
| git | Windows 证书库（schannel） | ✅ 信任 Steam++ 的 CA，正常 |
| curl / 自带 CA 包的工具 | 自带 CA bundle | ❌ 不信任 Steam++ CA，即使网络能通也报错 |
| Java / Gradle | JRE 自己的 cacerts | 视仓库而定，见下 |

**依赖源实测结论：不需要任何镜像。** 注意别被 `maven.google.com` 误导 ——
它返回 000 且解析到真实 Google IP，**是被墙**，但 **Gradle 的 `google()` 根本不用它**，
而是用 `dl.google.com/dl/android/maven2/`：

| 仓库 | 真实 URL | 直连结果 |
|---|---|---|
| `google()` | `dl.google.com/dl/android/maven2` | ✅ 200 |
| `mavenCentral()` | `repo1.maven.org` | ✅ 200 |
| `jitpack.io` | jitpack.io | ✅ 200 |
| `gradlePluginPortal` | plugins.gradle.org | ✅ 303（正常重定向） |
| crates.io / index / static | — | ✅ 全 200 |

验证方式：停掉 Gradle 守护进程 → 把 `kotlin-stdlib-jdk7-2.2.10.jar` 从缓存里**真的移走**
（断言原路径已消失）→ 重新构建 → 构建成功且**该文件被回填到原缓存路径**，
证明无代理下 Gradle 确实能从网络下载依赖。另跑过一次 `--refresh-dependencies` 全量重解析，
零网络错误。

Android SDK 中补装了项目要求的 `build-tools;37.0.0` 与 `ndk;29.0.14206865`。

`manager/local.properties`：`sdk.dir=D\:\\Users\\code\\Android\\Sdk`

### 构建临时文件也放 D 盘

junction 无法重定向 `%TEMP%`（它由环境变量决定），所以构建临时文件默认仍会写
`C:\Users\code\AppData\Local\Temp`。已做两处重定向：

* `manager/gradle.properties`：
  `org.gradle.jvmargs=-Xmx2048m -Djava.io.tmpdir=D:/Users/code/.papersu-work/tmp`
  —— 注意**项目级配置会覆盖 Gradle 用户目录的同名配置**，所以必须写在这里，写在
  `D:\Users\code\.gradle\gradle.properties` 里是无效的；
* `build-ksud.ps1`：编译前把 `TEMP`/`TMP` 也指向同一目录。

实测效果：`assembleDebug --rerun-tasks` 全量构建期间，C 盘 Temp 仅增长 **0.4 MB**，
而 Kotlin daemon 的标记文件出现在 D 盘临时目录 —— 重定向确已生效。

> 排查 C 盘空间时发现：`%TEMP%` 目前 5.74 GB，其中最大的单项是 `es4mss3o`
> （**4.18 GB**，修改时间 2026-10-02，**早于本次工作**，来源不明），
> 另有迅雷的 `ThunderEv`、`XLLiveUD`。这些我没有动 —— 用途不明的文件不该由我删。
> 你可以自行确认后清理。

---

## 9. 明确未改动的内容

按要求保留了 KernelSU/SukiSU 的赞助与支持开发相关内容，例如：

* `.github/FUNDING.yml`：`github: tiann`、`patreon: weishu`、`open_collective: sukisu-ultra`
* `manager/.../ui/screen/about/AboutScreen.kt` 中的 GitHub / Telegram 链接
* `LICENSE`、`LICENSE_icon_English`、`LICENSE_icon_SC`

改动分两类，共 **20 项已跟踪修改 + 9 项未跟踪新增**：

**① 环境、品牌与 CI 契约（7 项）**

```
 M .github/scripts/setup-rust-build.ps1   # 修复 Windows 语法 bug + UTF-8 BOM
 M .github/workflows/build-lkm.yml        # 手动触发 LKM 时使用 paperSU 签名
 M .github/workflows/ksud.yml             # ksud 内嵌 paperSU 包名（原来只有 PR 构建才设）
 M .github/workflows/build-manager.yml    # 非 PR 构建的 LKM 签名回退到 paperSU
 D kernel/include/uapi                    # 占位文件 -> junction（见 6.1）
 D manager/app/src/main/cpp/uapi           # 同上
 M manager/gradle.properties               # +4 行品牌配置
```

**② 7kimisu 主题移植（17 项修改 + 11 项新增：10 个 .kt + 资源目录）** —— 见第 11 节

未跟踪的新增文件：`build-ksud.ps1`、`install-lkm.ps1`、`papersu-signing.ps1`、本文件。

被 gitignore 的本地生成物：`manager/key.jks`（签名密钥）、
`userspace/ksud/bin/<arch>/ksuinit`、`manager/app/src/main/jniLibs/*/libksud.so`。

---

## 10. 待办

* [x] **主题功能**：地址已纠正为 `7kimisu`（你给的 `7kimis` 少一个 `u`），
      首批已移植并接好设置开关，详见第 11 节。
* [x] **GKI 的 `ksuinit`**：已本地编好并内嵌（`build-ksud.ps1 -Ksuinit`）。
* [ ] **GKI 的 `.ko`**：链路已补齐（`build-lkm.yml` 已带 paperSU 签名默认值 +
      `install-lkm.ps1` 归位脚本），等你在 GitHub Actions 上跑一次
      "Build LKM for KernelSU" 并下载产物回填。见第 7 节。
* [x] **壁纸 + 界面透明度**：已移植并接好开关（见 11.5）。
* [ ] **7kimisu 其余个性化功能**：扁平化/状态卡、字体字重、导航图标、
      公告弹窗、隐身模式等，按需再移植（见 11.6）。

---

## 11. 7kimisu 主题功能移植

### 11.1 地址纠正

你给的 `https://github.com/hartgerinktarcila-design/7kimis.git` 返回
`Repository not found`。经 GitHub API 查询，该用户**存在**，仓库名是 **`7kimisu`**
（末尾多一个 `u`）：

```
https://github.com/hartgerinktarcila-design/7kimisu    (最新 v2.29, GPL-3.0)
```

### 11.2 两个项目的关系（决定了这是「移植」而非「拷贝」）

7kimisu 是**基于上游 KernelSU** 的第三方修改版（源码包 `com.sevenk.core`），
而 paperSU 基于 **SukiSU-Ultra**（`com.sukisu.ultra`）。两者不是超集关系：

| 能力 | 7kimisu | paperSU (SukiSU) |
|---|---|---|
| 个性化 / 特效 | 壁纸、下雪、巨魔雨、字体字重、状态卡、隐身模式、公告弹窗、导航图标（34 个文件） | — |
| 内核刷写 | — | `ui/kernelFlash/*`（AnyKernel3 分区刷写） |
| SuSFS / KPM | — | `ui/screen/susfs/*`、`ui/screen/kpm/*` |

### 11.3 已移植的内容（debug 与 release 构建均已实测通过）

| 文件 | 说明 |
|---|---|
| `ui/component/GravityField.kt` | 重力场：传感器 + 帧循环物理（重力加速 + 空气阻力 → 终速），纯 Compose |
| `ui/component/Snowfall.kt` | 下雪特效：6 种程序化六角雪花贴图 + 2D 自转 + 双轴 3D 翻滚 |
| `ui/component/TrollRain.kt` | 巨魔雨特效：图标翻滚下落 |
| `ui/theme/DialogColors.kt` | 弹窗底色不透明化（解决「界面透明」时弹窗看不清） |
| `res/drawable-nodpi/troll_face.png` | 巨魔雨图标资源 |
| `ui/util/WallpaperStore.kt` | 壁纸存取：槽位/自愈/内置图/视频判定/私有目录（571 行） |
| `ui/util/WallpaperHost.kt` | 壁纸宿主：在内容底下画图片或视频，再压暗色（283 行） |
| `ui/util/WallpaperPrefs.kt` | 壁纸设置变更 → Compose 状态的桥接 |
| `ui/util/WallpaperSeedPolicy.kt` | 首次播种的纯函数决策（可离线断言） |
| `ui/util/PrivateDir.kt` | 私有目录辅助 |
| `ui/util/NavIcons.kt` | 自定义底部导航图标：相册选图 → 采样缩放 → 原子写盘（129 行） |
| `res/drawable-nodpi/wallpaper_default_*.jpg` | 内置竖屏/横屏默认壁纸 |

**三处适配改动**：

1. 包名 `com.sevenk.core` → `com.sukisu.ultra`；
2. **minSdk 兼容修复（重要）**：7kimisu 的 minSdk 是 31，直接使用了
   `View.getDisplay()`（API 30+），而 paperSU 是 **minSdk 26** —— 在 Android 12 以下
   开启特效会崩。已改为带版本判断，低于 API 30 时回退到
   `WindowManager.defaultDisplay`（`legacyDisplayRotation()`）；
3. 接入 paperSU 的设置链路，新增两个可在界面上切换的开关。

### 11.4 主题页新增的开关（共 3 个）

位于**主题设置页**（`ui/screen/colorpalette/`），Miuix 与 Material 两种 UI 模式都有：

| 开关 | 默认 | 说明 |
|---|---|---|
| Snowfall effect | 关 | 下雪特效 |
| Troll rain | 关 | 巨魔雨特效 |
| Default wallpaper | 关 | 内置壁纸 + 界面透明（见 11.5） |
| Home / Superuser / Module / Settings icon | 关 | 自定义底部导航图标（4 个开关，见下） |

状态持久化在 `settings` SharedPreferences（`enable_snowfall` / `enable_troll_rain` /
`wallpaper_*`）。特效在 `MainActivity` → `MainScreen` 的根 `Box` 中叠加渲染，
**不接受指针输入**，因此不会影响任何既有交互。

为接入而动过的文件（15 个）：`SettingsRepository`、`SettingsRepositoryImpl`、
`SettingsUiState`、`SettingsViewModel`、`ColorPaletteUiState`、`ColorPaletteScreen`、
`ColorPaletteScreenMiuix`、`ColorPaletteScreenMaterial`、`ui/theme/Theme.kt`、
`ui/theme/MiuixTheme.kt`、`ui/theme/MaterialTheme.kt`、`MainActivityUiState`、
`MainActivityViewModel`、`MainActivity`、`ui/component/bottombar/BottomBarMiuix.kt`、
`ui/component/bottombar/BottomBarMaterial.kt`、`res/values/strings.xml`（17 个）。

**自定义导航图标的接线（本轮新增）**：`NavIcons` 存在应用私有目录的 PNG 由两个底栏读取；
**未设置或读取失败时回退到内置矢量图标**，因此默认外观与改动前完全一致。

* Miuix：只改 `BottomBarMiuix.kt` 里**唯一**绘制图标的那段 lambda（悬浮底栏路径），
  **完全没有碰 `FloatingBottomBar`**；
* Material：给 `NavigationIconWithBadge` 增加一个默认值为 `null` 的 `customIcon` 参数，
  其它调用方不受影响；
* 选图用 `ActivityResultContracts.GetContent()`；关闭开关即 `NavIcons.clear()`。

> 中途踩到两个真实类型错误并修正：`item` 实际是 miuix 的 `NavigationItem` 而不是枚举
> （没有 `name`），且它的 `label` 是 `String` 而不是 `@StringRes Int`。改用索引映射
> （`BottomBarDestination.entries` 的顺序与 `NavIcons.keys` 严格一致）。

验证证据：

```
BUILD SUCCESSFUL（assembleDebug 1m50s / assembleRelease 2m37s）
APK 资源中已含: string/settings_enable_snowfall, settings_enable_snowfall_summary,
                string/settings_enable_troll_rain, settings_enable_troll_rain_summary
package: name='top.becuy.eric.papersu'   application-label:'paperSU'
V2 Signer SHA-256: 14ea1b98...f82f（与 manager/key.jks 一致）
```

### 11.5 壁纸系统与界面透明度（本轮新增）

**关键耦合**：只移植壁纸是**看不到效果**的。7kimisu 的界面本身不透明
（Scaffold/Miuix 的 `background`/`surface` 都是实色），壁纸会被完全盖住。
必须同时移植「界面透明」——它把大面积容器色按 alpha 复制：

| 位置 | 内容 |
|---|---|
| `ui/theme/MiuixTheme.kt` | `Colors.translucent(alpha)`：background/surface 用 alpha，卡片类用 `alpha + (1-alpha)*0.28`（卡片更实以保证可读） |
| `ui/theme/MaterialTheme.kt` | `ColorScheme.translucent(alpha)`：同上，覆盖 surfaceDim/Bright/Container 系列 |
| 生效条件 | `WallpaperStore.hasWallpaper(slot) && repo.uiTranslucent` |

因此**没有壁纸时 `surfaceAlpha == 1f`，主题与改动前完全一致**（这是一个安全门控）。
默认值沿用 7kimisu：`ui_translucent = true`、`ui_translucent_alpha = 0.02f`。

接线方式：`MainActivity` 里用 `WallpaperHost { when (uiMode) { ...Scaffold... } }`
包住两种 UI 模式的主 Scaffold —— 壁纸在内容**底下**，透明的 Scaffold 让它透出来。
没设壁纸时 `WallpaperHost` 只做 `content()`，零开销。

开关行为：`Default wallpaper` 打开 → `WallpaperStore.applyBuiltin(ksuApp)` 把内置图
写进私有目录；关闭 → `WallpaperStore.clear()`。状态由 `wallpaperKind != "none"` 推导。

适配改动：包名改写；**未移植 `LocalImmersiveBars`**（7kimisu 独有，我们的 `BlurExt`
没有对应机制，属于顶栏/底栏融合壁纸的可选增强）。

验证证据：

```
compileDebugKotlin 实际执行（非 UP-TO-DATE），debug 37s / release 2m48s 均成功
APK 资源: drawable/wallpaper_default_portrait, wallpaper_default_landscape,
          string/settings_wallpaper_default, settings_wallpaper_default_summary
package: name='top.becuy.eric.papersu'   application-label:'paperSU'
V2 Signer SHA-256: 14ea1b98...f82f
```

> 顺带一个有意思的验证：`art_default.jpg` 只在 KDoc 注释里被引用，
> **release 包中被资源压缩器正确移除了**（debug 包里还在）—— 说明 R8 的资源压缩
> 确实在工作。

### 11.6 尚未移植的部分（按需再做）

7kimisu 的其余个性化功能仍完整保留在 `D:\Users\code\.papersu-work\7kimisu`：

* `PersonalizeParts`（扁平化 / 状态卡）、`SegmentedSettingsItems`
* `ThemeLock`（主题锁定，7kimisu 里 `ENABLED = false` 本身是空操作）
* `AnnouncementPopup` + `AnnouncementPrefs`（开屏公告）
* `ModuleIcons` / `ModuleCardArt` / `StatusDecoration`
* ~~字体与字重~~ —— **经核实这项功能并不存在**：两边的 `ui/theme/Type.kt` 内容完全相同，
  7kimisu 里的 `fontWeight` 都是各屏幕内联写死的，没有集中的用户可调设置。
* `ui/security/Stealth*`（隐身模式）—— 见第 12.4 节：因缺少内核侧支持，
  已改为**管理器侧等效实现**；7kimisu 的原始版本需要它自己的内核分叉。

### 11.7 许可证与署名（GPL 要求，请勿删除）

7kimisu 是 **GPL-3.0-or-later**（其中 `kernel/` 为 GPL-2.0-only），
与 paperSU 的许可血统一致，可以合入；但 **GPL 要求保留版权声明**。已做两件事：

1. 每个移植文件顶部都加了来源、作者与许可证注释；
2. 本节记录出处与作者。

> 你提到「赞助和支持开发的不要乱动」—— 这条同样适用于 7kimisu/GPL：
> 再分发 APK 时必须一并提供对应完整源码，并保留上述署名。

---

## 12. 图标、背景、启动动画、改名与隐藏模式

### 12.1 应用图标（2.jpg）与默认大背景（1.jpg）

素材由 Pillow 脚本 `D:\Users\code\.papersu-work\make_assets.py` 生成，可重跑。

| 产物 | 说明 |
|---|---|
| `mipmap-{mdpi..xxxhdpi}/ic_launcher.webp` | 正方图标 48/72/96/144/192 |
| `mipmap-*/ic_launcher_round.webp` | 圆形遮罩版 |
| `mipmap-*/ic_launcher_art.webp` | **自适应图标前景**：把画面放进 108 里的 72 安全区，四周用图像自身的模糊外扩补边 |
| `mipmap-*/ic_launcher_art_alt.webp` | 替代图标（更紧的人物特写），让已有的 `alternativeIcon` 开关仍有可见效果 |
| `drawable-nodpi/wallpaper_default_portrait.jpg` | 1080×1944（源图仅 474px 宽，用 LANCZOS 平滑放大） |
| `drawable-nodpi/wallpaper_default_landscape.jpg` | 1200×675（居中偏上裁切，保住人物） |

裁剪：`2.jpg` 474×631 → 取 `(0,55)-(474,529)` 的 474 正方，人物面部落在上 1/3，
是图标构图的合适位置。调色板色由画面边缘采样得 `#A4BDD4`，写入 `ic_launcher_background`。

**首次启动自动生效**：`MainActivity.onCreate` 里调
`WallpaperStore.seedDefaultIfNeeded(applicationContext)`（放在后台线程，
因为要把内置图拷进私有目录）。它是幂等的（记录 `wallpaper_seeded`），
写入 `wallpaper_*` 后由 `WallpaperPrefs` 通知重组，界面随即透出背景。

> 连带效果：一旦有壁纸，`uiTranslucent`（默认 true、alpha 0.02）就生效，
> 界面转为半透明 —— 这正是「让背景图透出来」所需，与 7kimisu 的默认表现一致。

### 12.2 启动动画（原为 KSU 标识）

原来的 `Theme.KernelSU.Starting` → `windowSplashScreenAnimatedIcon = @drawable/ic_launcher_splash`，
而 `ic_launcher_splash.xml` 是动画矢量图，动画对象是 `ic_logo_vector`（KSU 的圆角方 + 四边描边 + 双方块）。

现在替换为 paperSU 自己的：

| 文件 | 内容 |
|---|---|
| `drawable/ic_logo_papersu.xml` | 圆角方底 + paperSU 的「P」字形（`papersu_p`）+ 一条强调下划线（`papersu_underline`） |
| `animator/anim_papersu_p.xml` | `trimPathEnd` 0→1，360ms —— 逐笔写出「P」 |
| `animator/anim_papersu_underline.xml` | 340ms 起，160ms —— 随后扫出下划线 |
| `drawable/ic_launcher_splash.xml` | 指向上面两者 |

⚠️ 一个容易踩的坑（我已修正）：矢量路径的**静态值必须写成 `trimPathEnd="1"`**（完整可见）。
原版 KSU 矢量就是这么写的。如果写成 `0`，那么在不播放启动动画的系统上
splash 图标会**完全空白**；动画运行时 `valueFrom="0"` 仍会覆盖初始值，所以逐笔绘制的效果不受影响。

另外新增 `drawable/ic_papersu_monogram.xml`（同样「P」字形），用作
`<monochrome>` 层 —— 否则 Android 13+ 的**主题图标**会显示 KSU 标识。

### 12.3 改名 PaperSU

`manager/gradle.properties` 的 `KSU_NAME` 由 `paperSU` 改为 **`PaperSU`**。
已验证 `application-label:'PaperSU'`，APK 文件名为 `PaperSU_v1.0.3_40940-release.apk`。

### 12.4 隐藏模式（管理器侧等效实现）

**先说要害**：7kimisu 的隐藏模式是**内核级**的 —— 开启后内核不再上报 MANAGER 标志，
别的应用探测不到 root。而 SukiSU 基底完全没有这套内核代码：

| 项 | paperSU（SukiSU） | 7kimisu |
|---|---|---|
| `Natives.stealthState/stealthSet` | 无 | 有 |
| `kernel/manager/stealth.c` | 无 | 有 |
| `KERNEL_SU_UAPI_VERSION` | **4** | **5**（`5: add stealth get/set`）|

所以完整移植意味着改 uapi、加内核模块、改 JNI 与用户态，
而且**必须刷入用这份源码编译的内核才生效**。本版改为**管理器侧等效实现**，
交付你描述的那套可观察行为（开启后首页显示未安装、拨号输入密语解除）：

* `manager/app/src/main/cpp/ksu.cc`：新增 `g_stealth_mask`；`is_manager()`
  在掩码开启时直接返回 `false`。**因为 `Natives.isManager` 有 12 个消费点
  （`HomeViewModel` 等），一处改动就让整个界面自然退化成「未安装」**，无需逐屏修改。
* `manager/app/src/main/cpp/jni.cc` + `Natives.kt`：新增
  `Java_com_sukisu_ultra_Natives_nativeSetStealthMask` / `external fun nativeSetStealthMask`。
* `ui/security/Stealth.kt`：开关状态、掩码应用、密令读写、`restartUiFresh()`。
* `ui/security/StealthReceiver.kt`：拨号密令接收器。
* `ui/security/StealthBootReceiver.kt`：重启后重新上掩码
  （`MainActivity.onCreate` 每次启动也会应用，这层是双保险）。
* `SettingsRepository/Impl`：`stealthEnabled`、`stealthCode`。
* 设置页（Miuix 的 `SwitchPreference` / Material 的 `SegmentedSwitchItem`）：
  一个「Hidden mode」开关，摘要里实时显示当前密令，用户直接知道该拨什么号。
* Manifest：`StealthReceiver`（`SECRET_CODE` + `android_secret_code` scheme）与
  `StealthBootReceiver`（`BOOT_COMPLETED`）；`RECEIVE_BOOT_COMPLETED` 权限基底已有。

**安全部分按 7kimisu v2.29 忠实移植**（那正是补掉漏洞的版本）：

1. **先验发送方、再看内容**。`SECRET_CODE` 不是受保护广播，任何应用都能伪造；
   判据是「系统 1000 / 电话 1001 / 自己」放行，其余 uid **必须能查出包身份**且
   是默认拨号器或系统应用才放行。
2. **不采用「uid < 10000 一律放行」**（v2.29 修掉的正是这条）——
   `adb shell` 的 uid 恰好是 2000，等于留了 `am broadcast` 后门。
3. **密令内容不写日志**（只记"收到密令/不匹配"）。
4. **连错 5 次暂停 30 秒**（纯内存计数）。
5. API < 34 无法取得发送方信息时**放行** —— 宁可放宽也不能把用户锁在自己 App 外面。

**与前作相比的两处有意简化**（因为改成了应用侧）：
* 不需要 root shell、也不需要把密令额外写一份到 `/data/adb`。
  卸载会同时清掉 prefs 和掩码，**不存在「卸载重装后被锁死在隐藏里」**这个
  7kimisu 必须防的风险，所以 `StealthCodeStore` 整体省略。
* 没有"内核太旧/内核不是我们的"那几档文案，只有"已关闭/本来就没开"两档。

**必须知道的能力边界**：这是**界面伪装**，不是内核级隐身。
别的应用直接查内核仍能得到真实信息。要真正的内核级隐藏，得走 12.4 开头那条完整移植路线。

**改密令的编辑器已做**：设置页里是一个行内输入框（Miuix 用 `component/miuix/EditText`，
Material 用 `OutlinedTextField`），只保留数字、上限 12 位；**清空即回退到默认 70707**
（`Stealth.clearCode()`），摘要实时显示当前生效的密令。

### 12.5 LKM 修补流程（本轮只核验与追链，未改动源码）

`git status` 交叉验证：**这几轮完全没有碰过下列任何文件**。

| 项 | 证据 |
|---|---|
| LKM 修补链路 | `ui/kernelFlash/*` 16 个文件齐全（`KernelFlashState.kt` 451 行、`RemoteToolsDownloader.kt` 256 行、AnyKernel3 流程、槽位选择对话框） |
| ksud 侧 LKM 取用 | `userspace/ksud/src/lkm_image.rs` 内嵌 LKM 与 AArch64 引导对象 |
| LKM 模式判定 | `Natives.isLkmMode` / `isLkmBundled` / `isLateLoadMode` 均在 |
| 越狱模式 | `autoJailbreak` / `useSoftReboot` 在 10 个文件中正常使用；`magica.BootCompletedReceiver` 与 `RECEIVE_BOOT_COMPLETED` 在 manifest 里 |

#### 为什么本地补不齐 `.ko`（已查到确切机制）

ksud 是用 **rust-embed 编译期内嵌**取 LKM 的：

```rust
fn embedded_module_name(kmi: &str) -> String {
    #[cfg(target_os = "android")]      { format!("{kmi}_kernelsu.ko") }
    #[cfg(not(target_os = "android"))] { format!("aarch64/{kmi}_kernelsu.ko") }
}
assets::get_asset_data(&name)   // 取不到就报 "no embedded KernelSU module for KMI ..."
```

注意分支条件是 **`target_os`，不是架构** —— Android 版在「该架构自己的嵌入根目录」里找
`<kmi>_kernelsu.ko`；`aarch64/` 前缀只出现在宿主机单测里
（`assets.rs` 中 `#[folder = "bin/aarch64"]` / `bin/arm` / `bin/x86_64` / `bin` 各是一个 RustEmbed 结构）。

而 `userspace/ksud/bin/` 下**一个 `.ko` 都没有**，所以取资产必然失败。
**关键点：补了 `.ko` 之后必须重编 ksud**（资源是编译期内嵌的），只放文件不重编无效。

#### 资产契约已端到端验证闭合

```
CI  ddk-lkm.yml   把 KSU_EXPECTED_SIZE2/HASH2（= paperSU 签名）作为 make 参数传入
    产物          aarch64-<kmi>-lkm / x86_64-<kmi>-lkm，内含 <kmi>_kernelsu.ko
install-lkm.ps1   按 ELF e_machine 分流（183→aarch64 / 62→x86_64）
                  → userspace/ksud/bin/<arch>/<kmi>_kernelsu.ko
重编 ksud         rust-embed 把 bin/<arch> 作为根内嵌
运行期            assets::get_asset_data("<kmi>_kernelsu.ko") ✓
```

两个平台的产物**文件名完全相同**，所以只能靠 ELF 架构区分 ——
`install-lkm.ps1` 正是这么做的（这就是它不能只按文件名分流的原因）。

#### ✅ 最省事的正解：跑一次 `build-manager` 工作流

`.github/workflows/build-manager.yml` 本身就是全链编排，**不需要你在本地补任何东西**：

```
generate-key ──┬─> build-lkm     (expected_size2/hash2 = 0x036d / 14ea1b98…f82f)
               │        ↓
               └─> build-ksud    (needs: [build-lkm, build-ksuinit])
                        ↓          ← 顺序正确：ksud 在 LKM 之后构建，.ko 才会被内嵌
                   build-manager (下载 ksud 产物 → 出 APK)
                        ↓
                   repack-manager
```

它有 `workflow_dispatch`，所以 **Actions → Build Manager → Run workflow**，
跑完下载的就是一个**能正常修补（GKI/LKM 模式）的完整 APK**，且 LKM 里带的是 paperSU 自己的签名。

这也说明 `.ko` 本来就该由 CI 产出（每个 KMI 一份）：本地无 Docker、无 WSL 发行版、
DDK 硬编码 `/opt/ddk` —— 这条路本就不通，不是配置问题。

### 12.6 版权提醒（必须知悉）

你提供的两张图带有 **`© SHANGHAI HENIAN INFORMATION TECHNOLOGY CO.,LTD`** 水印
与「洛天依」标识，属于他人享有著作权的美术作品。把它们打进 APK 并分发有侵权风险
（7kimisu 的作者当初让用户"自己选自己的图"正是为了避开这一点）。
这是你的项目、你的决定，我按你说的做了，但请自行评估是否需要授权或替换素材。

### 12.7 本轮验证证据

```
compileDebugKotlin / buildCMakeDebug[arm64-v8a, armeabi-v7a, x86_64] 均实际执行
BUILD SUCCESSFUL（debug 12s / release 2m35s）
application-label:'PaperSU'
APK: PaperSU_v1.0.3_40940-release.apk  12.6 MB
合并后 manifest 含 .ui.security.StealthReceiver（SECRET_CODE + android_secret_code）
                    与 .ui.security.StealthBootReceiver（BOOT_COMPLETED）
新资源均在包内: mipmap/ic_launcher_art(_alt)、drawable/ic_papersu_monogram、
                drawable/ic_logo_papersu、animator/anim_papersu_*、
                string/settings_stealth_*、string/stealth_*
Verified using v2 scheme (APK Signature Scheme v2): true
V2 Signer SHA-256: 14ea1b98c9f693f282a05022b8cf02dcbd2dd8e28c9d6b97bd6acfda1352f82f
```

> 未做真机验证的部分：图标的启动器显示效果、启动动画的实际观感、
> 隐藏模式的端到端行为（开关 → 首页变未安装 → 拨号解除）。
> 这些我只能保证编译、资源打包与静态逻辑正确。

---

## 13. 网页版 root 管理（webadmin，移植自 7kimisu）

服务**跑在 ksud 里**（不是 App 里）。原因：写在 App 里时，用户把管理器从最近任务划掉、
或用系统"一键清理"，进程一死端口就关，浏览器直接"拒绝连接"。放进 ksud 后
划掉 App、一键清理、重启手机都不影响，也不需要前台服务和常驻通知。

### 13.1 安全边界（这是本次移植最看重的部分，已逐条验证）

| 约束 | 实现 | 验证 |
|---|---|---|
| **只绑 `127.0.0.1`** | 两个监听点都是 `SocketAddr::from((Ipv4Addr::LOCALHOST, …))` | 全文件无 `0.0.0.0`/`UNSPECIFIED`/`INADDR_ANY`；三个 ABI 的二进制里也搜不到 `0.0.0.0` |
| **256 位 token + 路径鉴权** | `TOKEN_BYTES = 32`、`TOKEN_LEN = 64`；URL 形如 `http://127.0.0.1:18427/papersu/<64 位十六进制>`，**所有请求（含 app.js/css）都必须带** | `ct_eq` 恒时比较；`valid_token` 校验形状；约 25 条单测断言"少一位/多一位/非十六进制都不算" |
| **鉴权失败零信息** | 一律 `401` + 固定响应体 `Unauthorized`，不区分"路径错"还是"token 错"，不跳转 | `webadmin.rs:548` |
| **默认关闭** | `Config` 派生 `Default` ⇒ `enabled = false`；`load()` 从 `Self::default()` 起、只有显式 `enabled=1` 才开；`start_if_enabled()` 关闭时**直接短路，连监听都不创建** | 三重保证，见 `Config::load` / `start_if_enabled` |
| **配置文件私有** | `webadmin.conf` 先 `0600` 再 rename（避免"已是最终名字但还没收紧权限"的窗口） | 有单测断言权限就是 `0600` |

**为什么环回还不够**：回环只挡得住别的设备，**挡不住同一台手机上的其它 App**。
7kimisu 的文档明确记录了他们的旧版（v2.12 及以前）只有"固定端口 + 固定前缀 `/x7k9f`"，
于是本机任意 App 都能 `POST /x7k9f/api/app/root` 给自己授 root —— 现在的 token 方案就是修这个的。
**所以 token 不是可选项，别去掉。**

### 13.2 用法

从 App：设置页 →「Web manager」开关 → 打开后可「Open in browser / Copy private link /
Reset access key / Diagnose」。重置密钥时会**同时把新链接复制到剪贴板**，
所以不存在"重置完拿不到新链接"把自己锁在外面的情况（因此不需要确认对话框）。

从 root shell（不依赖 App）：

```
ksud webadmin on           # 开启并拉起常驻进程，同时打印带密钥的完整地址
ksud webadmin off          # 关闭（密钥保留，下次打开还是同一个链接）
ksud webadmin url          # 只打印地址（含密钥）
ksud webadmin status       # 状态（enabled / 端口 / 密钥是否已设置，**不含密钥本体**）
ksud webadmin reset-token  # 换一把密钥，旧链接立即失效
ksud webadmin sync         # 比对"在跑的 ksud"与"盘上的 ksud"，不一致才重启（不服务中断）
ksud webadmin restart      # 强制重启常驻进程（升级后救活用）
```

**想从电脑访问**：用 `adb forward tcp:18427 tcp:18427`，然后在电脑浏览器打开带密钥的地址。
**不要**把它改成监听 `0.0.0.0` —— 那等于把 root 管理面板摊给同网段所有人。

### 13.3 移植范围

| 文件 | 规模 | 说明 |
|---|---|---|
| `userspace/ksud/src/webadmin.rs` | 2400 行 | HTTP 服务端（**只依赖我们已有的 `restorecon`**） |
| `userspace/ksud/src/webadmin_ksud.rs` | 1577 行 | ksud 侧后端（模块/超级用户/配置/上传） |
| `userspace/ksud/assets/webadmin/*` | 10 个文件 | 前端（`index.html`、`app.js` 57 KB、`app.css`、`themes.css`、`modbridge.js`、`icons.svg` + 4 个第三方许可） |
| `ksucalls.rs` 新增 | ~205 行 | allow/deny 列表、`manager_appid`、`read/write_cstr`、`struct_bytes/from_bytes`、以及 **`ioctl_as_manager`**（fork + `setresuid`(管理器 uid) + 关旧句柄 + 重扫 fd + pipe 回传） |
| `module.rs` 新增 | ~420 行 | `ExecOptions`、`run_busybox_capture`（双线程收管道 + 超时杀整个进程组）、`parse_exec_options`、`list_package_names`、`packages_info`、`run_action_capture`、`exec_in_module`、`list_modules_json` |
| 接线 | — | `main.rs`(3 mod)、`cli.rs`(8 子命令 + dispatch)、`init_event.rs`(post-fs-data 自启) |
| `ui/util/WebAdminCli.kt` | 195 行 | App ↔ ksud 的唯一通道；**密钥不进日志、不进 argv** |
| 设置页 | 两侧 | 开关 + 打开/复制/重置/诊断 |
| `SettingsRepository(.Impl)` | — | `webAdminEnabled` |

**数据目录**：`/data/adb/ksu/`（我们的 `WORKING_DIR`），即 `webadmin.conf` / `.pid` /
`.log` / `.status` / `webadmin-serve.out`。

### 13.4 四处**有意偏离** 7kimisu 的地方（都已在代码里注明）

1. **不移植 `res_label`（713 行 APK 资源解析器）** —— 它只影响"超级用户列表显示应用名而不是
   包名"这个外观效果，而它本身是喂**不可信 APK** 的解析器。上游注释本来就写着
   "appLabel 用包名兜底"，所以按兜底实现，不引入新的解析攻击面。
2. **stealth 两个函数做成诚实 stub** —— 7kimisu 的隐藏模式是**内核级**的
   （`KSU_IOCTL_STEALTH_*`，它的 UAPI 5）；我们的在**管理器侧**（`Natives.isManager` 掩码），
   ksud 根本看不到。所以读操作返回 `false`，写操作返回明确的"请在应用设置里切换"。
3. **`set_stealth_code` 改为拒绝，而不是写文件** —— 7kimisu 把密令写到
   `/data/adb/sevenk/stealth_code` 由它的 App 读回；**我们的 App 没有这个读取方**。
   照抄写文件会"看起来成功但什么都没改"，这种假成功比直接报错危险得多。
4. **顺带删除 `atomic.rs`（207 行）** —— 移植它只因上面第 3 条的写文件需求；改掉后它成了死代码
   （已核实 `webadmin.rs::Config::save` 自己做完整原子写，不依赖它）。

### 13.5 验证证据

```
Rust: 0 error 0 warning（arm64 / armv7 / x86_64 三个 ABI 全部编译通过）
  libksud.so  arm64-v8a 6,260,320 B | armeabi-v7a 3,918,656 B | x86_64 5,373,584 B
  三个 ABI 的二进制里都含: webadmin / reset-token / 127.0.0.1 / /papersu / paperSU

前端资源确实内嵌（rust-embed #[folder = "assets/webadmin"]）：
  二进制中可见 __PAPERSU_BASE__、__PAPERSU_MODULE__、licenses/Lucide、themes.css、modbridge
  （index.html/app.js 的正文搜不到是正常的：Cargo.toml 启用了 compression 特性，资源被压缩存储；
   资源路径元数据在，且 rust-embed 在目录缺失时会直接编译失败）

App: BUILD SUCCESSFUL（debug 54s / release 2m40s）
  PaperSU_v1.0.3_40940-release.apk  13 MB  application-label:'PaperSU'
  19 个 webadmin_* 字符串资源在包内；APK 内三个 ABI 的 libksud.so 均已更新
```

> 未做真机验证：从设备浏览器实际打开页面、以及 `ksud webadmin on` 在真机上的
> 常驻/跨重启表现。这些我只能保证编译、资源内嵌与静态安全属性正确。
