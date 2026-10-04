# paperSU 内核编译与 AnyKernel3 刷写教程

从零到一个能刷的内核 zip。覆盖 Linux **4.x ~ 6.x**。

---

## 0. 先建立正确的心智模型

AnyKernel3（AK3）**不编译内核**，它只负责「把内核塞进设备的分区」。所以整件事分两半：

```
   ┌──────────────────────────┐        ┌───────────────────────────┐
   │  1. 编译内核（Linux 上做）│        │ 2. 打包刷写（AK3 做的事）  │
   │  build-kernel.sh         │  ───▶  │ anykernel.sh + tools/     │
   │  产出 Image / Image.lz4  │  镜像  │ 解包 boot → 换内核 → 写回  │
   └──────────────────────────┘        └───────────────────────────┘
```

**AK3 的脚本可以是通用的，内核二进制从来不是。** 你编出来的 `Image` 只适用于
「这台设备 + 这个 ROM（甚至这个内核源码版本）」。AK3 的 `do.devicecheck`
只是防止刷错机型，救不了「同一机型但 ROM 不匹配」。

### 目录结构（一次看懂）

```
AnyKernel3-paperSU/          ← 会被 release.yml 打成 AnyKernel3-paperSU.zip
├── anykernel.sh             ← 我写的：刷写逻辑（唯一需要你按设备改的文件）
├── fetch-tools.sh           ← 我写的：取上游运行时
├── README.md                ← 我写的：包内速查
├── META-INF/                ← 上游：恢复模式/应用的入口
├── tools/                   ← 上游：ak3-core.sh + magiskboot 等（约 5.3 MB）
├── LICENSE                  ← 上游：BSD 许可，**必须留在 zip 里**
├── ramdisk/                 ← 你要加的 ramdisk 文件放这里
├── modules/                 ← LKM 的 .ko 放这里（按完整路径）
├── patch/                   ← 供 AK3 编辑命令用的补丁片段
└── Image / Image.lz4 / …    ← 【你放这里】内核编译产物，只放一个
```

---

## 1. 环境准备

### 你需要什么

| 项 | 说明 |
|---|---|
| Linux 环境 | 内核编译必须有。Windows 上用 **Git Bash 只能做 `--dry-run` 规划**，真编译要么 WSL、要么 CI |
| 内核源码 | 厂商内核仓库，或 GKI 的 `kernel/common` |
| 交叉工具链 | 4.x → **GCC**；5.4 → **Clang**；5.10+/6.x → **Clang + LLVM=1** |
| 基础工具 | `make bc bison flex libssl-dev libelf-dev zip cpio python3` |
| 你的 keystore | `manager/key.jks` —— 用来取出管理器签名，编进内核 |

> **最省事的路子**：用本仓库的
> [`.github/workflows/build-kernel-ak3.yml`](../.github/workflows/build-kernel-ak3.yml)。
> 它在 GitHub 的 Ubuntu runner 上跑完全程，你在 Windows 上点一次就行。
> 见第 6 节。

### 工具链从哪来

- **Clang**：AOSP 的 `platform/prebuilts/clang/host/linux-x86`，某个 `clang-r<rev>` 目录。
  CI 工作流用 gitiles 的 `+archive` 接口只下这一个目录（比 clone 整个仓库小得多）。
  > ⚠️ **这一点我无法在这台机器上验证**：`android.googlesource.com` 在这里直连被墙
  > （实测返回 000）。GitHub runner 在美国通常可达；真下不到就用
  > `toolchain_source=url` 自己给一个可达的 tar.gz。
- **GCC**（4.x 用）：AOSP 的 `prebuilts/gcc/linux-x86/aarch64/aarch64-linux-android-4.9`，
  或发行版的 `gcc-aarch64-linux-gnu`。

---

## 2. 编译内核（自动适配 4.x ~ 6.x）

脚本会**从内核自己的 `Makefile` 读出 `VERSION`/`PATCHLEVEL`**，据此自动选工具链和编译目标：

| 内核 | 自动选工具链 | 自动选目标 | 典型产物 |
|---|---|---|---|
| 4.4 / 4.9 / 4.14 / 4.19 | `gcc` | `Image.gz-dtb` | `Image.gz-dtb` |
| 5.4 | `clang` | `Image` | `Image`（+ 单独 dtb） |
| 5.10 / 5.15 | `llvm`（`LLVM=1`） | `Image` | `Image` / `Image.lz4` |
| 6.1 / 6.6 / 6.12 | `llvm`（`LLVM=1`） | `Image` | `Image.lz4` |

> 这套对应关系是 AOSP/GKI 的既定做法，不是硬规定 —— 很多 5.4 厂商树仍用 GCC。
> 用 `--toolchain` 可以强制覆盖。

### 先做规划（Windows 上也能跑）

```bash
# Git Bash 里就能跑，不需要 make / 工具链
./kernel-build/build-kernel.sh \
  --kernel-dir /path/to/kernel \
  --defconfig vendor_defconfig \
  --dry-run
```

它会打印：探测到的内核版本、选了什么工具链、打算跑哪些 make 命令。
**先看清楚再动手**，比盲跑省时间。

### 真编译

```bash
./kernel-build/build-kernel.sh \
  --kernel-dir ~/android/kernel \
  --defconfig vendor_defconfig \
  --clang-dir ~/toolchain/clang \
  --out ~/android/out
```

带上 paperSU 内置模式与签名（这就是标准姿势）：

```bash
./kernel-build/build-kernel.sh \
  --kernel-dir ~/android/kernel \
  --defconfig vendor_defconfig \
  --clang-dir ~/toolchain/clang \
  --ksu-src ~/android/PaperSU \
  --ksu-package top.becuy.eric.papersu \
  --ksu-size2 0x036d \
  --ksu-hash2 14ea1b98c9f693f282a05022b8cf02dcbd2dd8e28c9d6b97bd6acfda1352f82f \
  --ak3
```

`--ak3` 会把产物**自动放进 `AnyKernel3-paperSU/` 并打好 zip**。

### 四个必须知道的坑

**① GKI 源码树要指向 `common/`**

GKI 的内核树长这样：`kernel/common/`、`kernel/msm-kernel/`…… 真正的内核源码在
`common/` 里。所以：

```bash
--kernel-dir ~/kernel/common          # ✅
--kernel-dir ~/kernel                 # ❌ 那里没有 VERSION/PATCHLEVEL
```

**② `CONFIG_KSU` 依赖 `CONFIG_KPROBES && CONFIG_EXT4_FS`**

```kconfig
config KSU
    bool "KernelSU"
    depends on KPROBES && EXT4_FS      # ← 少了这两个，KSU 会被静默关掉
```

`--ksu-src` 会自动帮你打开这三个，并且在 `olddefconfig` 之后**校验
`CONFIG_KSU=y` 是否真的生效** —— 没生效就明确报错，不会让你拿到一个「编译成功但里面没有 KSU」的内核。

**③ 管理器身份是 make 变量，不是配置项**

```
KSU_MANAGER_PACKAGE     管理器包名
KSU_EXPECTED_SIZE2      管理器签名大小
KSU_EXPECTED_HASH2      管理器签名 SHA-256
```

它们走 `make VAR=value`（`kernel/Kbuild` 里用 `ifdef` 处理），**写错名字不会报错**，
但内核里就没有签名 → 应用会一直显示「未安装」。而且：

> `KSU_EXPECTED_SIZE2` 给了就必须给 `KSU_EXPECTED_HASH2`，否则 Kbuild 直接
> `$(error)` 报错。脚本会提前拦下这种组合。

**④ 4.x 用 GCC，5.10+ 用 LLVM=1**

- 4.x：`CROSS_COMPILE=aarch64-linux-android-` 走 GCC
- 5.10+/6.x：`LLVM=1 LLVM_IAS=1`，需要 `clang` + `ld.lld` + `llvm-ar/nm/objcopy/...` 都在 PATH
- 工具链错配的典型症状：汇编语法报错（4.x 用 clang）、或 `unknown argument`（老内核遇到新 clang）

### 拿管理器签名（`--ksu-size2` / `--ksu-hash2` 从哪来）

从 `manager/key.jks` 算。仓库里有现成脚本：

```powershell
.\papersu-signing.ps1
```

它会打印证书大小（十六进制）与 SHA-256。当前 paperSU 的固定值是：

```
KSU_MANAGER_PACKAGE = top.becuy.eric.papersu
KSU_EXPECTED_SIZE2  = 0x036d
KSU_EXPECTED_HASH2  = 14ea1b98c9f693f282a05022b8cf02dcbd2dd8e28c9d6b97bd6acfda1352f82f
```

> **换了 keystore 就必须重算并重编内核**，否则应用不再被认主。

---

## 3. 组装 AnyKernel3 包

### 取运行时（首次必做）

```bash
cd AnyKernel3-paperSU
./fetch-tools.sh
```

它会按**固定提交号**浅取上游 AnyKernel3，把 `META-INF/`、`tools/`、`LICENSE`
与三个占位目录放好，然后自检。Windows 上在 **Git Bash** 里跑。

> 为什么不让这些文件直接进仓库：它们是 osm0sis 的作品，且 `tools/` 是 **5.3 MB
> 第三方 ARM 二进制**。`.gitignore` 已挡住；CI 会在打包前自动取。

### 放内核

把编译产物丢到 `AnyKernel3-paperSU/` 的**根**（不是子目录）：

```
Image          Image.lz4      Image.gz       Image.gz-dtb      zImage
```

- **只放一个。** AK3 按固定清单找**第一个**匹配的名字，同时存在多个会刷错。
- AK3 自己识别这些名字（`ak3-core.sh` 里的清单是
  `zImage zImage-dtb Image Image-dtb Image.gz Image.gz-dtb … Image.lz4 Image.lz4-dtb Image.fit`），
  **不用改脚本**。
- 要换 dtb 就把 `dtb` 也放根下，AK3 会自己判断走 `vendor_boot` 还是 `vendor_kernel_boot`。

### 打包

```bash
zip -r9 paperSU-Kernel-AnyKernel3.zip . \
  -x .git .gitignore '*.zip' fetch-tools.sh .ak3-commit README.md '*placeholder'
```

（或者直接用 `build-kernel.sh --ak3` 自动打包。）

---

## 4. 关于 `BLOCK=auto` —— 一个会让你刷错分区的坑

`anykernel.sh` 里默认写的是 **`BLOCK=boot`**，这是**刻意的**。

`ak3-core.sh` 里 `auto` 的探测顺序是 **`init_boot` 优先于 `boot`**：

```sh
plistboot="boot BOOT LNX android_boot bootimg KERN-A kernel KERNEL";
plistinit="init_boot ramdisk";
auto) parttype="$plistinit $plistboot";;        # ← init_boot 排在最前面
```

而 **Android 13+ 的 GKI 设备上 `init_boot` 里只有通用 ramdisk、没有内核**。
所以纯内核包用 `auto` 会把内核写进错的分区。

`auto` 是给「要改 ramdisk 的通用包」设计的 —— 那种包确实想落到 `init_boot`。

**内核包的正确取值**：

```sh
BLOCK=boot;                 # 内核在 boot
IS_SLOT_DEVICE=auto;        # 让 AK3 自己找 boot_a / boot_b
```

如果**同时**还想改 ramdisk（Android 13+ 的 ramdisk 在 `init_boot`），就打开
`anykernel.sh` 里注释掉的 `init_boot` 段，用 `reset_ak` 切过去再处理一遍。

---

## 5. 刷写

三种方式都认这个 zip（管理器靠「`anykernel.sh` + `tools/` 同时存在」识别它）：

### 方式 A：paperSU 管理器（推荐）

安装页 → 选这个 zip → 选槽位 → 刷。刷前**先备份原 boot**。

### 方式 B：TWRP / 其它恢复模式

直接刷 zip。

### 方式 C：Magisk / KernelSU 应用

从存储刷入。

### A/B 槽位

`IS_SLOT_DEVICE=auto` 会自动认当前槽位。想刷**另一个**槽位（OTA 后保留 root 的常见做法），
在 `anykernel.sh` 里加：

```sh
SLOT_SELECT=inactive;
```

### 想带 LKM 的 `.ko`

改 `anykernel.sh` 的 properties：

```
do.modules=1
do.systemless=1
```

再把 `.ko` 按**完整路径**放进 `modules/`（例：`modules/system/lib/modules/kernelsu.ko`）。
`do.systemless=1` 会做成一个 "ak3-helper" Magisk/KernelSU 模块，换内核时自动移除，不会冲突。

> **内置（built-in）和 LKM 二选一**：内置模式内核里已经有 KSU；LKM 模式内核里没有，
> 靠这个模块提供。两个都上会打架。

---

## 6. 用 CI 一键完成（Windows 最省事的路）

[`.github/workflows/build-kernel-ak3.yml`](../.github/workflows/build-kernel-ak3.yml)
把「取源码 → 装工具链 → 编译 → 打包」全做完了。

**用法**：仓库 → Actions → `Build Kernel + AnyKernel3` → Run workflow，填：

| 输入 | 例 |
|---|---|
| `kernel_repo` | 你的内核仓库 URL |
| `kernel_ref` | `main` |
| `defconfig` | `vendor_defconfig`（GKI 是 `gki_defconfig`） |
| `kmi` | `android12-5.10`（只影响产物名） |
| `ksu_enable` | ✅ |
| `ksu_package` / `ksu_size2` / `ksu_hash2` | 已预填成 paperSU 的值 |
| `toolchain_source` | `aosp`（默认） |

跑完在 Artifacts 里下载 `paperSU-Kernel-AnyKernel3.zip`。

### 关于这个工作流，我要如实说明

- 它**从未真正跑过**（我这里没有 GitHub runner）。我做的是：9 个 `run:` 块全部通过
  `bash -n` 语法检查；它传给 `build-kernel.sh` 的 9 个参数与脚本支持集**逐一核对一致**；
  YAML 缩进与制表符检查通过。
- **YAML 我没用真正的解析器验证过** —— 环境里没有 PyYAML/js-yaml。push 后 Actions
  页面会立刻告诉你语法对不对。
- 默认工具链来源 `android.googlesource.com` 在这台机器上**不可达**，所以我**没能验证
  那两个 URL 真能下到东西**。工作流会在失败时明确报错停下（不会静默继续）。

---

## 7. 出事了怎么办

### 刷完不开机（最常见）

进 **fastboot**，刷回原厂 boot：

```bash
fastboot flash boot stock_boot.img
fastboot reboot
```

A/B 设备注意槽位：`fastboot flash boot_a stock_boot.img`（或 `_b`）。

**所以刷之前一定要备份原 boot。** 三条路：

1. 管理器里通常有「备份/恢复」；
2. TWRP → Backup → 勾 `Boot`；
3. 手动：`dd if=/dev/block/bootdevice/by-name/boot of=/sdcard/boot-backup.img`（root shell 里）

### 想看刷写过程到底改了什么

把 zip 名字后面加 **`-debugging`**：

```
paperSU-Kernel-AnyKernel3-debugging.zip
```

AK3 会把 `/tmp` 打成 `.tgz`，事后在开机状态或桌面上查（上游 README 明确支持）。

### 想保留刷写现场

在 `anykernel.sh` 里设 `do.cleanup=0`，它就不删 `/tmp/anykernel`。

### 应用显示「未安装 / 未认主」

内核编出来了但管理器没被认主。按顺序查：

1. 内核里 `CONFIG_KSU=y` 真的生效了吗？（看 `out/.config`，或编译日志里
   `-- KernelSU Manager signature size/hash` 那两行）
2. 三个 `KSU_*` 变量**名字对不对**？（写错不报错，但没签名）
3. 签名值是不是当前 `manager/key.jks` 的？（换过 keystore 必须重算重编）
4. 包名是不是 `top.becuy.eric.papersu`？

### 4.x 编译报汇编语法错

工具链错配。4.x 用 `--toolchain gcc`，别用 clang。

### 5.10+/6.x 报 `ld.lld: not found` 或 `LLVM` 相关错

`LLVM=1` 需要整套 llvm 工具在 PATH。确认 `clang`、`ld.lld`、`llvm-ar`、`llvm-nm`、
`llvm-objcopy`、`llvm-objdump`、`llvm-readelf`、`llvm-strip` 都能找到 ——
用 `--clang-dir` 指向工具链根，脚本会把它的 `bin` 加进 PATH。

---

## 8. 速查表

```bash
# 看规划（Windows Git Bash 也行）
./kernel-build/build-kernel.sh --kernel-dir DIR --defconfig NAME --dry-run

# 4.x + GCC
./kernel-build/build-kernel.sh --kernel-dir DIR --defconfig NAME \
  --toolchain gcc --gcc-dir ~/gcc-aarch64

# 5.10+/6.x + LLVM
./kernel-build/build-kernel.sh --kernel-dir DIR --defconfig NAME \
  --clang-dir ~/toolchain/clang --out ~/out

# 带 paperSU 内置模式 + 自动打包
./kernel-build/build-kernel.sh --kernel-dir DIR --defconfig NAME \
  --clang-dir ~/toolchain/clang \
  --ksu-src ~/PaperSU --ksu-package top.becuy.eric.papersu \
  --ksu-size2 0x036d --ksu-hash2 14ea1b98c9f693f282a05022b8cf02dcbd2dd8e28c9d6b97bd6acfda1352f82f \
  --ak3

# 取 AK3 运行时
cd AnyKernel3-paperSU && ./fetch-tools.sh

# 全部参数
./kernel-build/build-kernel.sh --help
```

---

## 9. 许可与署名

`AnyKernel3` 由 **osm0sis @ xda-developers** 开发，采用 **BSD 风格许可**
（见包内 `LICENSE`）。重新分发时必须：

1. **把 `LICENSE` 留在 zip 里**；
2. 保留 `anykernel.sh` 顶部的原作者署名。

paperSU 的 `kernel/` 部分沿用其上游许可（SukiSU-Ultra / KernelSU 血统）。
把两者合到一起分发时，两边的署名都要保留。
