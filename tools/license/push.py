#!/usr/bin/env python3
"""推送与拉单：给 gui.py 用，也可以单独跑。

    # 推送一张卡密到授权站
    python push.py --site https://vip-anos-rekey.adt.shdiv.net --secret <SECRET> \
                   --push-key "eyJ1Ijoi..."

    # 从店铺订单接口拉一次，给每个新订单签一张卡密，再推到授权站
    python push.py --site ... --secret ... --pull-url "https://.../api/orders?key=..." \
                   --priv <private_key.pem> --state handled.json
"""
import argparse
import json
import os
import urllib.error
import urllib.request

UA = "paperSU-license-tool/1.0"


def _request(url: str, data: bytes | None, headers: dict, timeout: int = 25):
    req = urllib.request.Request(url, data=data, headers=headers, method="POST" if data else "GET")
    req.add_header("User-Agent", UA)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", "replace")
            return resp.status, raw
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", "replace")
    except Exception as exc:
        return 0, str(exc)


def post_json(url: str, payload: dict, headers: dict | None = None, timeout: int = 25):
    hdr = {"Content-Type": "application/json; charset=utf-8"}
    if headers:
        hdr.update(headers)
    return _request(url, json.dumps(payload, ensure_ascii=False).encode("utf-8"), hdr, timeout)


def get_json(url: str, timeout: int = 25):
    status, raw = _request(url, None, {"Accept": "application/json"}, timeout)
    if status != 200:
        return None, "HTTP %s：%s" % (status, raw[:200])
    try:
        return json.loads(raw), ""
    except Exception as exc:
        return None, "返回的不是 JSON：%s（前 200 字：%s）" % (exc, raw[:200])


def issue_url(site: str) -> str:
    site = site.strip().rstrip("/")
    if site.endswith("/api/issue.php"):
        return site
    return site + "/api/issue.php"


def push_cards(site: str, secret: str, cards: list) -> tuple:
    """cards: [{"key":..., "user":..., "tier":..., "exp":..., "order":...}]"""
    if not cards:
        return False, "没有要推送的卡密"
    status, raw = post_json(issue_url(site), {"cards": cards}, {"X-Auth": secret})
    if status != 200:
        return False, "HTTP %s：%s" % (status, raw[:300])
    try:
        data = json.loads(raw)
    except Exception:
        return False, "返回的不是 JSON：%s" % raw[:300]
    if not data.get("ok"):
        return False, str(data.get("error") or raw[:300])
    return True, "新增 %s 张，跳过 %s 张（已存在）" % (data.get("added"), data.get("skipped"))


def pull_orders(url: str, list_path: str = "", id_field: str = "id",
                user_field: str = "user", tier: str = "vip") -> tuple:
    """GET 一个订单接口，返回 (orders, error)。

    list_path 是到数组的点号路径，比如 data.list；留空则自动找第一个数组。
    """
    data, err = get_json(url)
    if err:
        return None, err
    node = data
    if list_path:
        for part in list_path.split("."):
            if isinstance(node, dict) and part in node:
                node = node[part]
            else:
                return None, "在返回里找不到路径 %s" % list_path
    if isinstance(node, dict):
        for key in ("data", "list", "orders", "items", "rows"):
            if isinstance(node.get(key), list):
                node = node[key]
                break
    if not isinstance(node, list):
        return None, "返回里没有找到订单数组"
    orders = []
    for item in node:
        if not isinstance(item, dict):
            continue
        oid = str(item.get(id_field, "") or "")
        if not oid:
            continue
        orders.append({
            "order": oid,
            "user": str(item.get(user_field, "") or ""),
            "tier": str(item.get("tier", "") or tier),
            "exp": str(item.get("exp", "") or ""),
        })
    return orders, ""


def load_state(path: str) -> set:
    try:
        with open(path, "r", encoding="utf-8") as fh:
            return set(json.load(fh).get("handled", []))
    except Exception:
        return set()


def save_state(path: str, handled: set) -> None:
    with open(path, "w", encoding="utf-8") as fh:
        json.dump({"handled": sorted(handled)}, fh, ensure_ascii=False, indent=2)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--site", required=True)
    ap.add_argument("--secret", default="")
    ap.add_argument("--push-key", default="")
    ap.add_argument("--pull-url", default="")
    ap.add_argument("--list-path", default="")
    ap.add_argument("--id-field", default="id")
    ap.add_argument("--user-field", default="user")
    ap.add_argument("--tier", default="vip")
    ap.add_argument("--priv", default="")
    ap.add_argument("--state", default="handled.json")
    args = ap.parse_args()

    if args.push_key:
        ok, msg = push_cards(args.site, args.secret, [{"key": args.push_key, "tier": args.tier}])
        print(("OK  " if ok else "FAIL ") + msg)
        return

    if args.pull_url:
        import sys
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import gui
        orders, err = pull_orders(args.pull_url, args.list_path, args.id_field, args.user_field, args.tier)
        if err:
            print("FAIL " + err)
            return
        handled = load_state(args.state)
        fresh = [o for o in orders if o["order"] not in handled]
        print("拉到 %d 个订单，其中 %d 个是新的" % (len(orders), len(fresh)))
        if not fresh:
            return
        cards = []
        for o in fresh:
            card = gui.make_card(args.priv, o["user"] or o["order"], o["tier"], o["exp"])
            cards.append({"key": card, "user": o["user"], "tier": o["tier"],
                          "exp": o["exp"], "order": o["order"]})
            print("  订单 %s -> %s" % (o["order"], card[:48] + "..."))
        if args.secret:
            ok, msg = push_cards(args.site, args.secret, cards)
            print(("OK  " if ok else "FAIL ") + msg)
            if ok:
                handled.update(o["order"] for o in fresh)
                save_state(args.state, handled)
        else:
            for c in cards:
                print(c["key"])


if __name__ == "__main__":
    main()