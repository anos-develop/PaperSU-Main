# AnyKernel3-paperSU

paperSU 的内核刷写包。**通用布局**，覆盖 Linux 4.x ~ 6.x；按仓库根
`.github/workflows/release.yml` 的约定，这个目录会被自动打成
`AnyKernel3-paperSU.zip`。

## 这个目录里什么是我写的、什么不是

| 文件 | 来源 | 说明 |
|---|---|---|
| `anykernel.sh` | **本仓库** | 刷写逻辑，已按通用布局配好并写清各版本差异 |
| `fetch-tools.sh` | **本仓库** | 把上游运行时取进来（见下） |
| `META-INF/` | [osm0sis/AnyKernel3](https://github.com/osm0sis/AnyKernel3) | 恢复模式/应用调用的入口 |
| `tools/` | 同上 | `ak3-core.sh` + `magiskboot` / `busybox` / `magiskpolicy` / `fec` / `httools_static` / `lptools_static` / `snapshotupdater_static`（约 5.3 MB ARM 二进制） |
| `LICENSE` | 同上 | **BSD 风格许可**（不是 GPL）。重新分发时**必须留在 zip 里** |
| `modules/` `patch/` `ramdisk/` | 同上 | AK3 约定要存在的占位目录 |

上游提交固定在 `fetch-tools.sh` 里的 `AK3_COMMIT`
（当前 `020dfeccf9d7e962a48400fc94d3e451df92eead`，magisk utils v31.0，`AK_BASE_VERSION=20260904`）。

## 先取运行时（首次必做）

```bash
./fetch-tools.sh            # 按固定提交号取，浅取只拉一个提交
./fetch-tools.sh --force    # 重新取
```

Windows 上在 **Git Bash** 里跑；CI 里 `release.yml` 也会自动调用。

## 放内核

把**内核编译产物**丢到这个目录的**根**（不是子目录）：

```
Image            # 5.x / 6.x 常见
Image.lz4        # 6.x GKI 常见
Image.gz         # 5.x
Image.gz-dtb     # 4.x 常见
zImage           # 4.x / 32 位
```

- **只放一个**。`anykernel.sh` 依赖 `ak3-core.sh` 的清单按顺序找第一个匹配的名字；
  同时存在多个会刷到你没预期的那个。
- AK3 自己就会识别上面这些名字（`ak3-core.sh` 里的清单是
  `zImage zImage-dtb Image Image-dtb Image.gz Image.gz-dtb … Image.lz4 Image.lz4-dtb Image.fit`），
  **不需要改脚本**。
- 要换 dtb 就把 `dtb` 也放到根，AK3 会自动判断该走 `vendor_boot` 还是
  `vendor_kernel_boot` 并搬过去。

## 打包

```bash
zip -r9 paperSU-Kernel-AnyKernel3.zip . \
  -x .git .gitignore '*.zip' fetch-tools.sh .ak3-commit README.md '*placeholder'
```

或者直接用仓库的 `kernel-build/build-kernel.sh --ak3`，它会编译完自动帮你放好并打包。

## 刷写

三种方式都认这个 zip：

1. **paperSU 管理器** → 安装页 → 选这个 zip（管理器靠"`anykernel.sh` + `tools/` 同时存在"识别它）
2. **TWRP / 其它恢复模式** → 直接刷
3. **Magisk / KernelSU 应用** → 从存储刷入

## 一个必须知道的坑：不要用 `BLOCK=auto`

`anykernel.sh` 里默认是 `BLOCK=boot`，**这是刻意的**。

`ak3-core.sh` 里 `auto` 的探测顺序是 **`init_boot` 优先于 `boot`**：

```sh
plistboot="boot BOOT LNX android_boot bootimg KERN-A kernel KERNEL";
plistinit="init_boot ramdisk";
auto) parttype="$plistinit $plistboot";;        # ← init_boot 在前
```

而 Android 13+ 的 GKI 设备上 **`init_boot` 里只有通用 ramdisk，没有内核**。
所以纯内核包用 `auto` 会把内核写进错的分区。

`auto` 是给「要改 ramdisk 的通用包」设计的 —— 那种包确实想落到 `init_boot`。

内核包的正确取值就是 `BLOCK=boot` + `IS_SLOT_DEVICE=auto`（让 AK3 自己找
`boot_a` / `boot_b`）。

## 想带 LKM 的 .ko

改 `anykernel.sh` 的 properties：

```
do.modules=1
do.systemless=1
```

然后把 `.ko` 按**完整路径**放进 `modules/`，例如
`modules/system/lib/modules/kernelsu.ko`。`do.systemless=1` 会把它做成一个
"ak3-helper" Magisk/KernelSU 模块，换内核时自动移除，不会冲突。

> 注意：LKM 模式下内核里**没有**内置 KernelSU，靠这个模块提供。两种模式二选一。

## 出问题怎么办

- **刷完不开机**：进 fastboot，`fastboot flash boot <原厂 boot.img>` 刷回去。
  刷之前**一定要备份原 boot**（管理器里有"备份/恢复"；TWRP 里也能备份 boot 分区）。
- **想看刷写过程到底改了什么**：把 zip 名字后面加 `-debugging`，
  AK3 会把 `/tmp` 打成 `.tgz` 方便事后查（上游 README 明确支持这个开关）。
- **想保留现场**：`do.cleanup=0` 会让它不删 `/tmp/anykernel`。
