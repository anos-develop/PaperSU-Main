#!/usr/bin/env bash
#
# 把 AnyKernel3 的运行时取进本包（厂商化 / vendoring）。
#
# ── 为什么要有这个脚本 ────────────────────────────────────────────────────────
# 一个 AK3 zip 要能刷，必须同时具备：
#   META-INF/   恢复模式/应用调用的入口（updater-script + update-binary）
#   tools/      ak3-core.sh + magiskboot / busybox / magiskpolicy / fec / httools_static /
#               lptools_static / snapshotupdater_static（约 5.3 MB 的 ARM 二进制）
#
# 这些都是 **osm0sis 的作品**（AnyKernel3，BSD 风格许可 —— 见随包附带的 LICENSE），
# 不是我写的。把它们当自己的东西提交进仓库不合适，所以这里按**固定提交号**取进来：
#   · 可复现 —— 任何时候跑都得到同一份
#   · 可升级 —— 想跟上游就改 AK3_COMMIT
#   · 仓库干净 —— 不背 5 MB 第三方二进制
#
# ⚠️ 重新分发时 LICENSE 必须留在最终 zip 里（上游 README 明确要求）。
#
# ── 用法 ─────────────────────────────────────────────────────────────────────
#   ./fetch-tools.sh                 # 按下方固定提交号取
#   ./fetch-tools.sh --force         # 已存在也重新取
#   AK3_COMMIT=<sha> ./fetch-tools.sh
#
# Windows 上可在 Git Bash 里跑；或交给 CI（.github/workflows/release.yml 已经会调用）。
#
set -eu

AK3_REPO="${AK3_REPO:-https://github.com/osm0sis/AnyKernel3.git}"
# 本包编写与核对时对应的上游提交：2026-09-04，magisk utils v31.0(31000) beta
# （对应 AK_BASE_VERSION=20260904，见 META-INF/.../updater-script）
AK3_COMMIT="${AK3_COMMIT:-020dfeccf9d7e962a48400fc94d3e451df92eead}"

HERE=$(cd "$(dirname "$0")" && pwd)
FORCE=0
[ "${1:-}" = "--force" ] && FORCE=1

say() { printf '%s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

command -v git >/dev/null 2>&1 || die "需要 git（Windows 上用 Git Bash，或交给 CI 跑）"

# ── 已经取过就跳过 ────────────────────────────────────────────────────────────
if [ "$FORCE" -eq 0 ]; then
  if [ -f "$HERE/tools/ak3-core.sh" ] && [ -f "$HERE/META-INF/com/google/android/update-binary" ]; then
    say "[=] AnyKernel3 运行时已存在，跳过（要重新取用 --force）"
    say "    已有提交记录: $(cat "$HERE/.ak3-commit" 2>/dev/null || echo '(未记录)')"
    exit 0
  fi
fi

WORK=$(mktemp -d 2>/dev/null || mktemp -d -t ak3)
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT INT TERM

say "[+] 取 AnyKernel3 @ $AK3_COMMIT"
say "    （浅取：只拉这一个提交，避免把上游几百 MB 的二进制历史全拉下来）"
mkdir -p "$WORK/AnyKernel3"
(
  cd "$WORK/AnyKernel3"
  git init --quiet
  git remote add origin "$AK3_REPO"
  # GitHub 允许按 sha 浅取；不允许的话退回按默认分支浅取
  if ! git fetch --quiet --depth 1 origin "$AK3_COMMIT" 2>/dev/null; then
    git fetch --quiet --depth 1 origin HEAD || die "fetch 失败（网络 / 代理？）"
  fi
  git checkout --quiet FETCH_HEAD || die "checkout 失败"
)
ACTUAL=$(cd "$WORK/AnyKernel3" && git rev-parse HEAD)
[ "$ACTUAL" = "$AK3_COMMIT" ] || say "    注意：拿到的是 $ACTUAL（与固定值 $AK3_COMMIT 不同）"

SRC="$WORK/AnyKernel3"
[ -f "$SRC/tools/ak3-core.sh" ] || die "上游结构不对：tools/ak3-core.sh 不存在"
[ -f "$SRC/META-INF/com/google/android/update-binary" ] || die "上游结构不对：update-binary 不存在"
[ -f "$SRC/LICENSE" ] || die "上游结构不对：LICENSE 不存在"

# ── 复制 ─────────────────────────────────────────────────────────────────────
# META-INF / tools / LICENSE 是硬需求；modules、patch、ramdisk 是 AK3 约定要存在的
# 空占位目录（里面各有一个 0 字节 .placeholder），一起带上，避免刷写时报目录不存在。
say "[+] 复制运行时到 $HERE"
for item in META-INF tools LICENSE modules patch ramdisk; do
  if [ -e "$SRC/$item" ]; then
    rm -rf "$HERE/$item"
    cp -af "$SRC/$item" "$HERE/$item"
    say "    $item"
  else
    say "    (跳过 $item —— 上游没有)"
  fi
done

printf '%s\n' "$AK3_COMMIT" > "$HERE/.ak3-commit"

# ── 结果自检 ─────────────────────────────────────────────────────────────────
say ""
say "[+] 自检"
missing=0
for f in \
  META-INF/com/google/android/update-binary \
  META-INF/com/google/android/updater-script \
  tools/ak3-core.sh \
  tools/magiskboot \
  tools/busybox \
  LICENSE
do
  if [ -f "$HERE/$f" ]; then
    say "    OK   $f"
  else
    say "    缺失 $f"
    missing=$((missing + 1))
  fi
done
[ "$missing" -eq 0 ] || die "有 $missing 个必需文件没取到"

# 顺手报一下内核镜像识别清单是否还在（这是 4.x-6.x 通用的关键）
if grep -q 'Image\.lz4-dtb Image\.fit' "$HERE/tools/ak3-core.sh"; then
  say "    OK   内核镜像识别清单在位（zImage … Image.lz4-dtb Image.fit）"
else
  say "    警告 没在 ak3-core.sh 里找到预期的镜像清单 —— 上游可能改过结构，请核对"
fi

say ""
say "[+] 完成。接下来："
say "    1. 把内核编译产物（Image / Image.gz / Image.lz4 / zImage / Image.gz-dtb）"
say "       放到本目录根下 —— 只放一个，且要是为这台设备编的"
say "    2. 要带 .ko 就把 do.modules=1、do.systemless=1，并按完整路径放进 modules/"
say "    3. 打包：zip -r9 paperSU-Kernel-AnyKernel3.zip . -x .git .gitignore '*.zip' 'fetch-tools.sh' '.ak3-commit'"
say "       （仓库根的 .github/workflows/release.yml 会自动把 AnyKernel3-* 目录打成 zip）"
