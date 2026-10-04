### AnyKernel3 Ramdisk Mod Script
## osm0sis @ xda-developers (template) / paperSU (this configuration)
#
# ============================================================================
#  paperSU 内核刷写包 —— 通用布局，覆盖 Linux 4.x ~ 6.x
# ============================================================================
#
# 这个包只做一件事：把 ZIP **根目录**下的内核镜像写进设备的 boot 分区。
#
# AK3 核心（tools/ak3-core.sh）自己会处理各版本之间的差异，你**不需要**为
# 4.x / 5.x / 6.x 分别维护脚本。它按这个清单自动识别内核镜像名
# （ak3-core.sh:331，原文照抄）：
#
#   zImage zImage-dtb Image Image-dtb Image.gz Image.gz-dtb Image.bz2 Image.bz2-dtb
#   Image.lzo Image.lzo-dtb Image.lzma Image.lzma-dtb Image.xz Image.xz-dtb
#   Image.lz4 Image.lz4-dtb Image.fit
#
#   4.x 常见 → zImage / Image.gz-dtb
#   5.x 常见 → Image / Image.gz（+ 单独的 dtb）
#   6.x 常见 → Image.lz4（GKI）
# 把编译产物按**原名**丢到 ZIP 根目录即可，不用改这一行。
#
#
# ⚠️⚠️ 一个必须知道的坑：内核包不要用 BLOCK=auto ⚠️⚠️
#
#   ak3-core.sh 里 auto 的探测顺序是 **init_boot 优先于 boot**：
#
#       plistboot="boot BOOT LNX android_boot bootimg KERN-A kernel KERNEL";
#       plistinit="init_boot ramdisk";
#       auto) parttype="$plistinit $plistboot";;        # ← init_boot 排在前面
#
#   而 Android 13+ 的 GKI 设备上，**init_boot 里只有通用 ramdisk，没有内核**。
#   于是纯内核包用 BLOCK=auto 会把内核写进 init_boot —— 那是错的分区。
#
#   auto 是给「要改 ramdisk 的通用包」设计的（那种包确实想落到 init_boot）。
#   ✅ 内核包的正确取值是 BLOCK=boot，再配 IS_SLOT_DEVICE=auto 让它自己找 boot_a/boot_b。
#
#
# 刷写前请确认：ZIP 根目录有且只有一个内核镜像文件，且它是**为这台设备/这个 ROM 编的**。
# 这个脚本是通用的，但内核二进制从来不是通用的。
# ============================================================================

### AnyKernel setup
# global properties
properties() { '
kernel.string=paperSU Kernel
# do.devicecheck=0 让这个包能刷到任何设备；但**包里的内核是设备/ROM 专属的**。
# 想锁死设备就改成 1，并填上 device.name1（怎么取这个值见教程第 5 节）。
do.devicecheck=0
# 纯内核包：不带任何 .ko。若要用 LKM 的 .ko，改成 do.modules=1 + do.systemless=1，
# 并把 .ko 按**完整路径**放进 modules/（例：modules/system/lib/modules/kernelsu.ko）。
# do.systemless=1 会把 modules/ 打包成一个 "ak3-helper" Magisk/KernelSU 模块。
do.modules=0
do.systemless=1
do.cleanup=1
do.cleanuponabort=0
device.name1=
# 留空 = 不限制 Android 版本 / 安全补丁级别。
# 想限制就写，例如：supported.versions=13 - 16
#                  supported.patchlevels=2024-01 -
supported.versions=
supported.patchlevels=
supported.vendorpatchlevels=
'; } # end properties


### AnyKernel install
## boot files attributes
boot_attributes() {
set_perm_recursive 0 0 755 644 $RAMDISK/*;
set_perm_recursive 0 0 750 750 $RAMDISK/init* $RAMDISK/sbin;
} # end attributes

# boot shell variables
BLOCK=boot;                 # 内核所在分区。见文件顶部说明：内核包**不要**用 auto
IS_SLOT_DEVICE=auto;        # 自动探测 A/B 槽位后缀（boot_a / boot_b / 无后缀）
RAMDISK_COMPRESSION=auto;   # 按解包时探测到的格式回包（4.x 多为 gz，6.x 多为 lz4）
PATCH_VBMETA_FLAG=auto;     # 自动处理 AVBv2 标志（需要时用 1 强制、0 保持原样）

# import functions/variables and setup patching - see for reference (DO NOT REMOVE)
. tools/ak3-core.sh;

# ---- paperSU: 刷写前提示（通用包必须让人意识到内核是设备专属的）----
ui_print " ";
ui_print "  paperSU kernel package";
ui_print "  layout : universal (Linux 4.x - 6.x, slot auto)";
ui_print "  note   : the kernel inside this zip is built for ONE device/ROM.";
ui_print "           Do not flash it on a different model.";
ui_print " ";

# boot install
dump_boot;    # 解包 boot（含 ramdisk）

# paperSU: 内置（built-in）模式的 KernelSU 在**内核里**，不需要动 ramdisk，
# 所以这一段默认什么都不改 —— 这也是最不容易出事的选择。
#
# 确实想加 ramdisk 改动就写在这里，例如：
#   backup_file init.rc;
#   insert_line init.rc "papersu-tweak" after "on post-fs-data" "    <你的 init 命令>";
#
# 但更稳的做法是**别改 ramdisk**，改用 /overlay.d（Magisk 与 KernelSU 都支持），
# 这样 OTA / 换内核都不会把你的改动冲掉。

write_boot;   # 重新打包并写回 boot
## end boot install


## ── 可选：还要改 ramdisk（Android 13+ 的 GKI 设备，ramdisk 在 init_boot 里）─────
## 只有当设备**确实有 init_boot 分区**、并且你要往里面加东西时才打开这一段。
## 判断方法：`ls /dev/block/bootdevice/by-name/ | grep init_boot` 有输出 = 有。
##
## 注意 reset_ak 会重置上面的状态，让这一段独立处理另一个分区。
## 多槽位包用 `reset_ak keep` 可以保留已加的 ramdisk 改动。
#
#init_boot_attributes() {
#set_perm_recursive 0 0 755 644 $RAMDISK/*;
#set_perm_recursive 0 0 750 750 $RAMDISK/init* $RAMDISK/sbin;
#} # end attributes
#
#BLOCK=init_boot;
#IS_SLOT_DEVICE=auto;
#RAMDISK_COMPRESSION=auto;
#PATCH_VBMETA_FLAG=auto;
#
#reset_ak;
#
#dump_boot;
#write_boot;
## end init_boot install


## ── 可选：换 dtb / dlkm（vendor_boot 或 vendor_kernel_boot）─────────────────
## 多数情况**什么都不用写**：只要 ZIP 根目录放了 dtb 文件，或者存在 vendor_ramdisk/ 与
## vendor_patch/ 目录，AK3 会自己判断该走 hdr v4（init_boot + vendor_kernel_boot）
## 还是 v3（vendor_boot），并把文件搬过去（见 ak3-core.sh 的自动多分区段）：
##
##   if [ -e ".../init_boot$SLOT" -a -e ".../vendor_kernel_boot$SLOT" ] && [ -f dtb -o -d vendor_ramdisk ]; then
##     mv -f dtb vendor_kernel_boot-files
##   elif [ -e ".../vendor_boot$SLOT" ] && [ -f dtb -o -d vendor_ramdisk ]; then
##     mv -f dtb vendor_boot-files
##
## 所以：换 dtb 就把 dtb 丢到 ZIP 根目录，别在这里写代码。
## 只有要单独刷某个分区（例如只刷 dtbo）时，才用下面这种写法：
#
#reset_ak;
#flash_generic dtbo;
