#!/usr/bin/env bash
#
# ============================================================================
#  paperSU 内核编译脚本 —— 覆盖 Linux 4.x ~ 6.x
# ============================================================================
#
# 设计思路：**从内核自己的 Makefile 读出 VERSION/PATCHLEVEL/SUBLEVEL**，
# 据此自动选择工具链、编译目标和编译标志。你不需要记住哪个版本该用 GCC、
# 哪个该用 LLVM，也不用维护好几份脚本。
#
#   ┌────────────┬──────────────┬────────────────────────┬──────────────────────┐
#   │ 内核       │ 默认工具链   │ 默认 make 目标          │ 产物（丢进 AK3 根）  │
#   ├────────────┼──────────────┼────────────────────────┼──────────────────────┤
#   │ 4.4–4.19   │ GCC          │ Image.gz-dtb            │ Image.gz-dtb         │
#   │ 5.4        │ Clang        │ Image                   │ Image(+dtb)          │
#   │ 5.10–5.15  │ Clang(LLVM=1)│ Image                   │ Image / Image.lz4    │
#   │ 6.x        │ Clang(LLVM=1)│ Image                   │ Image.lz4            │
#   └────────────┴──────────────┴────────────────────────┴──────────────────────┘
#
# 4.x 用 GCC、5.x/6.x 用 Clang(LLVM=1) 是 AOSP/GKI 的既定做法，不是硬性规定 ——
# 用 --toolchain 可以强制切换（很多 5.4 的厂商树仍用 GCC）。
#
# ── 用法 ─────────────────────────────────────────────────────────────────────
#   最小用法（自动探测一切）：
#     ./build-kernel.sh --kernel-dir ~/android/kernel --defconfig vendor_defconfig
#
#   带 paperSU 内置模式和签名（这就是给 paperSU 用的标准姿势）：
#     ./build-kernel.sh \
#       --kernel-dir ~/android/kernel \
#       --defconfig vendor_defconfig \
#       --ksu-src ~/android/PaperSU \
#       --ksu-package top.becuy.eric.papersu \
#       --ksu-size2 0x036d \
#       --ksu-hash2 14ea1b98c9f693f282a05022b8cf02dcbd2dd8e28c9d6b97bd6acfda1352f82f \
#       --ak3
#
#   只看它打算跑什么命令、不真的编译：
#     ./build-kernel.sh --kernel-dir ~/android/kernel --defconfig x --dry-run
#
# ── 参数 ─────────────────────────────────────────────────────────────────────
#   --kernel-dir <dir>     内核源码根（必填）
#   --defconfig <name>     配置名，如 vendor_defconfig（与 --config-from-device 二选一）
#   --config-from-device   从已 root 的设备抓 /proc/config.gz 当基线（adb 需可用）
#   --out <dir>            输出目录（默认 <kernel-dir>/../out-papersu）
#   --arch <arch>          默认 arm64
#   --jobs <n>             并行度（默认全部核心）
#   --toolchain <auto|gcc|clang|llvm>
#   --gcc-dir <dir>        GCC 工具链根（4.x 常用 AOSP 的 aarch64-linux-android-*）
#   --clang-dir <dir>      Clang 工具链根（5.x/6.x）
#   --cross-compile <pfx>  覆盖 CROSS_COMPILE 前缀
#   --target <name>        覆盖 make 目标（默认按版本给）
#   --kmi <kmi>            仅记录用（如 android12-5.10），会写进产物名
#   --thinlto-cache <dir>  6.x 的 thin LTO 缓存目录
#   --ksu-src <dir>        paperSU/KernelSU 仓库根（含 kernel/），启用内置模式
#   --ksu-package <pkg>    管理器包名 → KSU_MANAGER_PACKAGE
#   --ksu-size2 <hex>      管理器签名大小 → KSU_EXPECTED_SIZE2
#   --ksu-hash2 <hex>      管理器签名哈希 → KSU_EXPECTED_HASH2
#   --extra-config <a,b>   额外打开的内核配置（逗号分隔），如 CONFIG_KPM=y
#   --extra-make <a,b>     额外 make 参数
#   --ak3                  编译后自动组装 AnyKernel3 zip
#   --ak3-dir <dir>        AnyKernel3 包目录（默认 <本脚本上级>/AnyKernel3-paperSU）
#   --clean                先 make clean
#   --dry-run              只打印命令
#   -h|--help
#
# 需要 bash / make / 工具链。Windows 上请在 Git Bash、WSL 或 CI 里跑（本脚本不跑 Windows）。
#
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$HERE/.." && pwd)

# ── 默认值 ───────────────────────────────────────────────────────────────────
KDIR="" ; DEFCONFIG="" ; OUT="" ; ARCH=arm64 ; JOBS=""
TOOLCHAIN=auto ; GCC_DIR="" ; CLANG_DIR="" ; CROSS_COMPILE="" ; TARGET="" ; KMI=""
THINLTO_CACHE="" ; KSU_SRC="" ; KSU_PACKAGE="" ; KSU_SIZE2="" ; KSU_HASH2=""
EXTRA_CONFIG="" ; EXTRA_MAKE="" ; AK3=0 ; AK3_DIR="$REPO_ROOT/AnyKernel3-paperSU"
DO_CLEAN=0 ; DRY_RUN=0 ; CONFIG_FROM_DEVICE=0

say()  { printf '%s\n' "$*"; }
warn() { printf 'WARN: %s\n' "$*" >&2; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
step() { printf '\n=== %s ===\n' "$*"; }

usage() { sed -n '2,90p' "$0" | sed 's/^# \{0,1\}//'; exit 0; }

# ── 参数解析 ─────────────────────────────────────────────────────────────────
need_val() { [ "$2" -ge 2 ] || die "$1 需要一个值"; }
while [ $# -gt 0 ]; do
  case "$1" in
    --kernel-dir) need_val "$1" $#; KDIR=$2; shift 2;;
    --defconfig) need_val "$1" $#; DEFCONFIG=$2; shift 2;;
    --config-from-device) CONFIG_FROM_DEVICE=1; shift;;
    --out) need_val "$1" $#; OUT=$2; shift 2;;
    --arch) need_val "$1" $#; ARCH=$2; shift 2;;
    --jobs) need_val "$1" $#; JOBS=$2; shift 2;;
    --toolchain) need_val "$1" $#; TOOLCHAIN=$2; shift 2;;
    --gcc-dir) need_val "$1" $#; GCC_DIR=$2; shift 2;;
    --clang-dir) need_val "$1" $#; CLANG_DIR=$2; shift 2;;
    --cross-compile) need_val "$1" $#; CROSS_COMPILE=$2; shift 2;;
    --target) need_val "$1" $#; TARGET=$2; shift 2;;
    --kmi) need_val "$1" $#; KMI=$2; shift 2;;
    --thinlto-cache) need_val "$1" $#; THINLTO_CACHE=$2; shift 2;;
    --ksu-src) need_val "$1" $#; KSU_SRC=$2; shift 2;;
    --ksu-package) need_val "$1" $#; KSU_PACKAGE=$2; shift 2;;
    --ksu-size2) need_val "$1" $#; KSU_SIZE2=$2; shift 2;;
    --ksu-hash2) need_val "$1" $#; KSU_HASH2=$2; shift 2;;
    --extra-config) need_val "$1" $#; EXTRA_CONFIG=$2; shift 2;;
    --extra-make) need_val "$1" $#; EXTRA_MAKE=$2; shift 2;;
    --ak3) AK3=1; shift;;
    --ak3-dir) need_val "$1" $#; AK3_DIR=$2; shift 2;;
    --clean) DO_CLEAN=1; shift;;
    --dry-run) DRY_RUN=1; shift;;
    -h|--help) usage;;
    *) die "未知参数：$1（用 --help 看用法）";;
  esac
done

# ── 前置检查 ─────────────────────────────────────────────────────────────────
[ -n "$KDIR" ] || die "必须给 --kernel-dir"
[ -d "$KDIR" ] || die "内核目录不存在：$KDIR"
[ -f "$KDIR/Makefile" ] || die "$KDIR 不像内核源码根（找不到 Makefile）"
[ -n "$DEFCONFIG" ] || [ "$CONFIG_FROM_DEVICE" -eq 1 ] || die "必须给 --defconfig 或 --config-from-device"
for c in sed awk; do command -v "$c" >/dev/null 2>&1 || die "缺少 $c"; done
# make 只在真要编译时要 —— --dry-run 是拿来「在没有工具链的机器上做规划」的
# （例如 Windows 的 Git Bash 里先看清楚要跑什么命令），所以这里不当硬性前提。
if [ "$DRY_RUN" -eq 0 ]; then
  command -v make >/dev/null 2>&1 || die "缺少 make（--dry-run 不需要它，可以先用 dry-run 看规划）"
fi

KDIR=$(cd "$KDIR" && pwd)
[ -n "$OUT" ] || OUT="$(dirname "$KDIR")/out-papersu"
case "$OUT" in /*) ;; *) OUT="$PWD/$OUT";; esac
[ -n "$JOBS" ] || JOBS=$( { command -v nproc >/dev/null 2>&1 && nproc; } || { command -v sysctl >/dev/null 2>&1 && sysctl -n hw.ncpu; } || echo 4 )

# ── 版本探测：这是"覆盖 4.x-6.x"的核心 ───────────────────────────────────────
KVER_MAJOR=$(sed -n 's/^VERSION *= *//p'         "$KDIR/Makefile" | head -n1)
KVER_MINOR=$(sed -n 's/^PATCHLEVEL *= *//p'      "$KDIR/Makefile" | head -n1)
KVER_SUB=$(sed -n 's/^SUBLEVEL *= *//p'          "$KDIR/Makefile" | head -n1)
[ -n "$KVER_MAJOR" ] && [ -n "$KVER_MINOR" ] || die "无法从 $KDIR/Makefile 读出 VERSION/PATCHLEVEL"
KVER="$KVER_MAJOR.$KVER_MINOR"
KVER_FULL="$KVER${KVER_SUB:+.$KVER_SUB}"

case "$KVER_MAJOR" in
  4|5|6) ;;
  *) die "探测到 Linux $KVER_FULL，本脚本只覆盖 4.x / 5.x / 6.x（其它版本请自行改默认值）";;
esac

# 按版本给默认值；命令行显式指定的优先
case "$KVER" in
  4.*)      DEF_TOOLCHAIN=gcc  ; DEF_TARGET=Image.gz-dtb ;;
  5.4)      DEF_TOOLCHAIN=clang; DEF_TARGET=Image ;;
  5.*)      DEF_TOOLCHAIN=llvm ; DEF_TARGET=Image ;;
  6.*)      DEF_TOOLCHAIN=llvm ; DEF_TARGET=Image ;;
  *)        DEF_TOOLCHAIN=llvm ; DEF_TARGET=Image ;;
esac

if [ "$TOOLCHAIN" = "auto" ]; then TOOLCHAIN=$DEF_TOOLCHAIN; fi
if [ -z "$TARGET" ]; then TARGET=$DEF_TARGET; fi
case "$TOOLCHAIN" in gcc|clang|llvm) ;; *) die "--toolchain 只能是 auto/gcc/clang/llvm";; esac

step "环境"
say "  内核版本   : $KVER_FULL   →  主干 $KVER"
say "  内核目录   : $KDIR"
say "  输出目录   : $OUT"
say "  架构       : $ARCH"
say "  并行度     : $JOBS"
say "  工具链     : $TOOLCHAIN"
say "  make 目标  : $TARGET"
if [ -n "$KMI" ]; then say "  KMI        : $KMI"; fi

# 版本相关提醒（不阻断，只提示）
if [ "$KVER_MAJOR" = "4" ] && [ "$TOOLCHAIN" != "gcc" ]; then
  warn "4.x 通常用 GCC。你选了 $TOOLCHAIN —— 如果编译报错（尤其 asm 语法），换回 --toolchain gcc。"
fi
if [ "$KVER_MAJOR" -ge 5 ] && [ "$TOOLCHAIN" = "gcc" ]; then
  warn "$KVER 通常用 Clang。你选了 gcc —— 部分厂商树可以，但 GKI 分支一般不行。"
fi

# ── 工具链准备 ───────────────────────────────────────────────────────────────
MAKE_ARGS=("ARCH=$ARCH" "O=$OUT")

prepare_toolchain() {
  case "$TOOLCHAIN" in
    gcc)
      local pfx="$CROSS_COMPILE"
      [ -n "$pfx" ] || pfx="${GCC_DIR:+$GCC_DIR/bin/}aarch64-linux-android-"
      if [ -n "$GCC_DIR" ]; then
        [ -d "$GCC_DIR" ] || die "--gcc-dir 不存在：$GCC_DIR"
        export PATH="$GCC_DIR/bin:$PATH"
      fi
      MAKE_ARGS+=("CROSS_COMPILE=$pfx")
      say "  CROSS_COMPILE = $pfx"
      ;;
    clang)
      # 5.4 时代的写法：CC=clang + CLANG_TRIPLE，配合 GCC 的 binutils
      local pfx="${CROSS_COMPILE:-aarch64-linux-gnu-}"
      if [ -n "$CLANG_DIR" ]; then
        [ -d "$CLANG_DIR" ] || die "--clang-dir 不存在：$CLANG_DIR"
        export PATH="$CLANG_DIR/bin:$PATH"
      fi
      # dry-run 只是做规划，本机没装工具链也应该能跑
      [ "$DRY_RUN" -eq 1 ] || command -v clang >/dev/null 2>&1 || die "PATH 里没有 clang（用 --clang-dir 指定工具链根）"
      MAKE_ARGS+=("CC=clang" "CLANG_TRIPLE=${ARCH}-linux-gnu-" "CROSS_COMPILE=$pfx")
      say "  CC=clang  CLANG_TRIPLE=${ARCH}-linux-gnu-  CROSS_COMPILE=$pfx"
      ;;
    llvm)
      # 5.10+ / 6.x 的写法：LLVM=1 一把梭（ld.lld / llvm-ar / llvm-nm / ... 全自动）
      local pfx="${CROSS_COMPILE:-aarch64-linux-gnu-}"
      if [ -n "$CLANG_DIR" ]; then
        [ -d "$CLANG_DIR" ] || die "--clang-dir 不存在：$CLANG_DIR"
        export PATH="$CLANG_DIR/bin:$PATH"
      fi
      [ "$DRY_RUN" -eq 1 ] || command -v clang >/dev/null 2>&1 || die "PATH 里没有 clang（用 --clang-dir 指定工具链根）"
      [ "$DRY_RUN" -eq 1 ] || command -v ld.lld >/dev/null 2>&1 || warn "PATH 里没有 ld.lld —— LLVM=1 需要它（ndk/toolchain 的 bin 目录要进 PATH）"
      MAKE_ARGS+=("LLVM=1" "LLVM_IAS=1" "CROSS_COMPILE=$pfx")
      say "  LLVM=1 LLVM_IAS=1 CROSS_COMPILE=$pfx"
      if [ -n "$THINLTO_CACHE" ]; then MAKE_ARGS+=("thinlto-cache-dir=$THINLTO_CACHE"); fi
      ;;
  esac
}

# ── paperSU / KernelSU 内置模式集成 ──────────────────────────────────────────
# 需要打开的配置。注意 Kconfig 里：config KSU  depends on KPROBES && EXT4_FS
# 少了这两个，CONFIG_KSU 会被静默关掉（olddefconfig 阶段），编出来的内核里没有 KSU。
KSU_CONFIGS="CONFIG_KSU=y,CONFIG_KPROBES=y,CONFIG_EXT4_FS=y,CONFIG_KSU_MANUAL_SU=y"

setup_ksu() {
  [ -n "$KSU_SRC" ] || return 0
  [ -d "$KSU_SRC/kernel" ] || die "--ksu-src 里找不到 kernel/ 目录：$KSU_SRC"
  [ -f "$KSU_SRC/kernel/setup.sh" ] || die "--ksu-src 里找不到 kernel/setup.sh：$KSU_SRC"

  step "集成 paperSU（内置模式）"
  # setup.sh 的约定：它把 $GKI_ROOT/KernelSU/kernel 软链到 <drivers>/kernelsu，
  # 并往 drivers/Makefile 与 drivers/Kconfig 里各加一行。所以这里把我们的仓库
  # 软链成 <kernel-tree>/KernelSU 即可。
  say "  ln -sfn $KSU_SRC $KDIR/KernelSU"
  if [ "$DRY_RUN" -eq 0 ]; then
    ln -sfn "$KSU_SRC" "$KDIR/KernelSU"
    ( cd "$KDIR" && sh KernelSU/kernel/setup.sh ) || die "setup.sh 失败"
  fi

  # 管理器身份：这些是 **make 变量**（kernel/Kbuild 里用 ifdef/$(info) 处理），
  # 不是内核配置项。写错名字不会报错，但内核里就没有签名 → 应用会一直显示"未安装"。
  if [ -n "$KSU_SIZE2" ] && [ -z "$KSU_HASH2" ]; then
    die "给了 --ksu-size2 就必须同时给 --ksu-hash2（Kbuild 里是硬报错：KSU_EXPECTED_HASH2 must be set when KSU_EXPECTED_SIZE2 is set）"
  fi
  if [ -n "$KSU_HASH2" ] && [ -z "$KSU_SIZE2" ]; then
    die "给了 --ksu-hash2 就必须同时给 --ksu-size2"
  fi
  if [ -n "$KSU_PACKAGE" ]; then MAKE_ARGS+=("KSU_MANAGER_PACKAGE=$KSU_PACKAGE") && say "  KSU_MANAGER_PACKAGE=$KSU_PACKAGE"; fi
  [ -n "$KSU_SIZE2" ]   && MAKE_ARGS+=("KSU_EXPECTED_SIZE2=$KSU_SIZE2")     && say "  KSU_EXPECTED_SIZE2=$KSU_SIZE2"
  [ -n "$KSU_HASH2" ]   && MAKE_ARGS+=("KSU_EXPECTED_HASH2=$KSU_HASH2")     && say "  KSU_EXPECTED_HASH2=$KSU_HASH2"
  if [ -z "$KSU_PACKAGE" ] && [ -z "$KSU_HASH2" ]; then
    warn "没给 --ksu-package/--ksu-hash2 —— 内核会带上游默认签名，paperSU 应用不会被认主。"
    warn "  从 manager/key.jks 取指纹的办法见教程；papersu-signing.ps1 也能直接算。"
  fi
}

prepare_toolchain
setup_ksu
if [ -n "$EXTRA_MAKE" ]; then for kv in $(printf '%s' "$EXTRA_MAKE" | tr ',' ' '); do MAKE_ARGS+=("$kv"); done; fi

# ── 配置阶段 ─────────────────────────────────────────────────────────────────
# scripts/config 能把 CONFIG_X=y 写成配置项，并在 olddefconfig 时解析依赖。
set_config_lines() {
  local lines="$1" c name val
  [ -n "$lines" ] || return 0
  for c in $(printf '%s' "$lines" | tr ',' ' '); do
    name=${c%%=*}; val=${c#*=}
    if [ "$name" = "$c" ]; then val=y; fi
    if [ -x "$KDIR/scripts/config" ]; then
      case "$val" in
        y)  run "$KDIR/scripts/config" --file "$OUT/.config" --enable  "$name" >/dev/null;;
        n)  run "$KDIR/scripts/config" --file "$OUT/.config" --disable "$name" >/dev/null;;
        m)  run "$KDIR/scripts/config" --file "$OUT/.config" --module  "$name" >/dev/null;;
        *)  run "$KDIR/scripts/config" --file "$OUT/.config" --set-str "$name" "$val" >/dev/null;;
      esac
      say "    配置 $c"
    else
      warn "内核树里没有 scripts/config，$c 未应用（请手动加到 defconfig）"
    fi
  done
}

run() {
  if [ "$DRY_RUN" -eq 1 ]; then printf '  [dry-run] %s\n' "$*"; else "$@"; fi
}

step "配置内核"
run mkdir -p "$OUT"
if [ "$CONFIG_FROM_DEVICE" -eq 1 ]; then
  command -v adb >/dev/null 2>&1 || die "--config-from-device 需要 adb"
  say "  从设备抓 /proc/config.gz 作为基线"
  if [ "$DRY_RUN" -eq 0 ]; then
    adb shell 'su -c "cat /proc/config.gz"' | gzip -dc > "$OUT/.config" || die "抓配置失败（设备要已 root）"
  fi
else
  say "  defconfig: $DEFCONFIG"
  # 注意：defconfig 目标要在内核树里、用 O= 输出，所以 -C 和 O= 都要给
  if [ "$DRY_RUN" -eq 1 ]; then
    printf '  [dry-run] make -C %s %s %s\n' "$KDIR" "$DEFCONFIG" "${MAKE_ARGS[*]}"
  else
    ( cd "$KDIR" && make "${MAKE_ARGS[@]}" "$DEFCONFIG" ) || die "defconfig 失败：$DEFCONFIG 存在吗？"
  fi
fi

set_config_lines "$KSU_CONFIGS"
set_config_lines "$EXTRA_CONFIG"

if [ "$DRY_RUN" -eq 0 ]; then
  ( cd "$KDIR" && make "${MAKE_ARGS[@]}" olddefconfig ) || die "olddefconfig 失败"
  # 关键校验：KSU 依赖 KPROBES/EXT4_FS，缺了会被静默关掉
  if [ -n "$KSU_SRC" ]; then
    if grep -q '^CONFIG_KSU=y' "$OUT/.config"; then
      say "  ✅ CONFIG_KSU=y 已生效"
    else
      die "CONFIG_KSU 没能打开 —— 检查 CONFIG_KPROBES / CONFIG_EXT4_FS 是否可用（Kconfig 里 KSU 依赖它们）"
    fi
  fi
fi

# ── 编译 ─────────────────────────────────────────────────────────────────────
if [ "$DO_CLEAN" -eq 1 ]; then { step "make clean"; run bash -c "cd '$KDIR' && make ${MAKE_ARGS[*]} clean"; }; fi

step "开始编译（$TARGET）"
if [ "$DRY_RUN" -eq 1 ]; then
  printf '  [dry-run] cd %s && make %s -j%s %s\n' "$KDIR" "${MAKE_ARGS[*]}" "$JOBS" "$TARGET"
else
  ( cd "$KDIR" && make "${MAKE_ARGS[@]}" -j"$JOBS" "$TARGET" ) || die "编译失败。先看上面的第一条 error；4.x/5.x 的工具链错配是最常见原因。"
fi

# ── 产物定位 ─────────────────────────────────────────────────────────────────
step "产物"
BOOTDIR="$OUT/arch/$ARCH/boot"
IMAGES="zImage zImage-dtb Image Image.gz Image.gz-dtb Image.lz4 Image.bz2 Image.xz Image.fit"
found=""
if [ "$DRY_RUN" -eq 0 ]; then
  for i in $IMAGES; do
    if [ -f "$BOOTDIR/$i" ]; then
      printf '  %-16s %10s B\n' "$i" "$(wc -c < "$BOOTDIR/$i" | tr -d ' ')"
      if [ -z "$found" ]; then found="$BOOTDIR/$i"; fi
    fi
  done
  if [ -f "$BOOTDIR/dtb" ]; then printf '  %-16s %10s B\n' dtb "$(wc -c < "$BOOTDIR/dtb" | tr -d ' ')"; fi
  if [ -z "$found" ]; then die "在 $BOOTDIR 里没找到任何内核镜像 —— 目标名对吗？（试 --target Image 或 zImage）"; fi
  say ""
  say "  主镜像: $found"
  say "  丢进 AnyKernel3 包根目录的就是它（只放这一个）"
fi

# ── 可选：组装 AnyKernel3 刷写包 ─────────────────────────────────────────────
if [ "$AK3" -eq 1 ]; then
  step "组装 AnyKernel3 刷写包"
  [ -d "$AK3_DIR" ] || die "找不到 AK3 包目录：$AK3_DIR"
  [ -f "$AK3_DIR/anykernel.sh" ] || die "$AK3_DIR 里没有 anykernel.sh"
  if [ ! -f "$AK3_DIR/tools/ak3-core.sh" ]; then
    say "  tools/ 还没厂商化，先跑 fetch-tools.sh"
    run bash "$AK3_DIR/fetch-tools.sh"
  fi
  if [ "$DRY_RUN" -eq 0 ]; then
    # 先清掉旧的镜像文件：AK3 是"按清单找第一个匹配"，残留的旧镜像会被刷进去
    for i in $IMAGES dtb dtbo; do rm -f "$AK3_DIR/$i"; done
    cp -f "$found" "$AK3_DIR/$(basename "$found")"
    if [ -f "$BOOTDIR/dtb" ]; then cp -f "$BOOTDIR/dtb" "$AK3_DIR/dtb"; fi
    NAME="paperSU-Kernel-${KMI:-$KVER_FULL}-AnyKernel3"
    ZIP="$(dirname "$OUT")/$NAME.zip"
    rm -f "$ZIP"
    ( cd "$AK3_DIR" && zip -r9 "$ZIP" . -x '.git' '.gitignore' '*.zip' 'fetch-tools.sh' '.ak3-commit' 'README.md' '*placeholder' >/dev/null ) \
      || die "打包失败（需要 zip 命令）"
    say "  ✅ $ZIP"
    say "     大小 $(wc -c < "$ZIP" | tr -d ' ') B"
  fi
fi

step "完成"
say "  内核    : $KVER_FULL ($ARCH)"
say "  输出目录: $OUT"
if [ "$AK3" -eq 1 ]; then say "  刷写包  : (见上)"; fi
say ""
say "  刷写前提醒：这个内核只适用于你这台设备的这个 ROM。"
say "  刷错分区的急救办法见教程的「出事了怎么办」一节。"
