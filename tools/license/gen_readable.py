#!/usr/bin/env python3
"""生成人眼可读的卡密，形如 VIP-2025-0821-XYZ。

    python gen_readable.py --count 100
    python gen_readable.py --count 500 --prefix VIP --date 2026-1006 --tail 4
    python gen_readable.py --count 100 --out cards.txt

注意：这个格式【没有签名】，应用光看字符串是没法验证真伪的。
要让它在应用里生效，必须二选一：
  A. 授权站存一张 明文卡密 -> 签名授权 的映射表，应用激活时联网换一次（推荐）
  B. 应用内部直接塞一份白名单（等于把名单放进 APK，谁都能扒出来）—— 不建议
"""
import argparse
import datetime
import os
import secrets
import string

# 去掉容易看错的 O/0/I/1，方便用户手输
ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"


def make_one(prefix: str, date_part: str, tail_len: int) -> str:
    tail = "".join(secrets.choice(ALPHABET) for _ in range(tail_len))
    return "%s-%s-%s" % (prefix, date_part, tail)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--count", type=int, default=100)
    ap.add_argument("--prefix", default="VIP")
    ap.add_argument("--date", default="", help="默认用今天，形如 2026-1006")
    ap.add_argument("--tail", type=int, default=3, help="末段长度，默认 3")
    ap.add_argument("--out", default="")
    args = ap.parse_args()

    date_part = args.date or datetime.date.today().strftime("%Y-%m%d")
    space = len(ALPHABET) ** args.tail

    if args.count > space:
        raise SystemExit("末段 %d 位只有 %d 种组合，装不下 %d 个（把 --tail 调大）"
                         % (args.tail, space, args.count))
    if args.count > space // 2:
        print("提示：%d 个取自从 %d 种组合，撞号概率不低，建议 --tail 调大"
              % (args.count, space))

    seen = set()
    while len(seen) < args.count:
        seen.add(make_one(args.prefix, date_part, args.tail))
    cards = sorted(seen)

    for c in cards:
        print(c)

    if args.out:
        with open(args.out, "w", encoding="utf-8") as fh:
            fh.write("\n".join(cards) + "\n")
        print("\n已写入 %s（共 %d 个）" % (args.out, len(cards)), flush=True)


if __name__ == "__main__":
    main()