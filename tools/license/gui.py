#!/usr/bin/env python3
"""paperSU 卡密签发工具（图形版）

生成、导出、校验纸SU 的离线卡密。私钥只在本机使用，签发全程不联网。

卡密格式： base64url(载荷) . base64url(签名)
载荷：     {"u":"用户名","t":"等级","exp":"yyyy-MM-dd","n":"随机数"}
签名：     ECDSA P-256 + SHA-256（openssl 默认 DER，与应用端 SHA256withECDSA 一致）

每张卡密带一个随机 n，所以同一用户、同一到期日签两次也会得到两张不同的卡密 —— 重复无从发生。

界面分成三页：
    签发          密钥、参数、卡密列表、导出
    授权站 / 店铺  推送到授权站；从店铺订单接口拉单并自动签发推送
    校验          粘贴一张卡密，看它解析出来是什么
"""
import base64
import csv
import datetime
import json
import os
import secrets
import subprocess
import sys
import tempfile
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

import push
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

APP_TITLE = "paperSU 卡密签发"
# 只是给 genkey/sign 那两个命令行脚本留个后备；exe 本身已经不需要 openssl 了。
# 不写死绝对路径，靠 PATH 找。
OPENSSL_CANDIDATES = ("openssl", "openssl.exe")


def openssl_path() -> str:
    for candidate in OPENSSL_CANDIDATES:
        try:
            subprocess.run([candidate, "version"], capture_output=True, check=True)
            return candidate
        except Exception:
            continue
    return ""


def b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def b64url_decode(text: str) -> bytes:
    pad = "=" * (-len(text) % 4)
    return base64.urlsafe_b64decode(text + pad)


def sign_payload(payload: bytes, priv_path: str) -> bytes:
    """ECDSA P-256 over SHA-256, DER encoded.

    这就是 Android 端 Signature.getInstance("SHA256withECDSA") 期待的形式，
    已经跟 openssl 交叉验证过。不再依赖外部 openssl，所以打包成 exe 在哪台机器上都能签。
    """
    with open(priv_path, "rb") as fh:
        key = serialization.load_pem_private_key(fh.read(), password=None)
    return key.sign(payload, ec.ECDSA(hashes.SHA256()))


def make_card(priv_path: str, user: str, tier: str, exp: str) -> str:
    payload = json.dumps(
        {"u": user, "t": tier, "exp": exp, "n": secrets.token_hex(4)},
        separators=(",", ":"),
        ensure_ascii=False,
    ).encode("utf-8")
    return b64url(payload) + "." + b64url(sign_payload(payload, priv_path))


def verify_card(card: str, pub_path: str):
    """Return (ok, payload_dict_or_reason)."""
    card = card.strip()
    if "." not in card:
        return False, "格式不对：应该是 载荷.签名"
    head, _, tail = card.rpartition(".")
    try:
        payload = b64url_decode(head)
        signature = b64url_decode(tail)
    except Exception as exc:
        return False, "base64 解不开：%s" % exc
    if not os.path.exists(pub_path):
        return False, "找不到公钥文件：%s" % pub_path
    try:
        with open(pub_path, "rb") as fh:
            pub = serialization.load_pem_public_key(fh.read())
        pub.verify(signature, payload, ec.ECDSA(hashes.SHA256()))
    except Exception:
        return False, "签名对不上 —— 不是本密钥签发的卡密"
    try:
        data = json.loads(payload.decode("utf-8"))
    except Exception as exc:
        return False, "载荷不是合法 JSON：%s" % exc
    exp = (data.get("exp") or "").strip()
    if exp:
        try:
            if datetime.date.fromisoformat(exp) < datetime.date.today():
                return False, "已过期（%s）" % exp
        except ValueError:
            return False, "到期日格式不对：%s" % exp
    return True, data


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title(APP_TITLE)
        self.geometry("1120x800")
        self.minsize(940, 640)
        self.rows = []
        self._build()

    # ------------------------------------------------------------------ 界面
    def _build(self):
        pad = {"padx": 10, "pady": 6}
        nb = ttk.Notebook(self)
        nb.pack(fill="both", expand=True, padx=10, pady=(10, 0))

        self._build_sign_tab(nb, pad)
        self._build_site_tab(nb, pad)
        self._build_check_tab(nb, pad)

        self.status = tk.StringVar(value="就绪")
        bar = ttk.Frame(self)
        bar.pack(fill="x", padx=12, pady=8)
        ttk.Label(bar, textvariable=self.status, anchor="w").pack(side="left")

    # ---------------------------------------------------------- 第 1 页：签发
    def _build_sign_tab(self, nb, pad):
        tab = ttk.Frame(nb)
        nb.add(tab, text="    签发    ")

        keys = ttk.LabelFrame(tab, text="密钥")
        keys.pack(fill="x", **pad)
        self.priv_var = tk.StringVar(value=os.environ.get("PAPERSU_PRIVATE_KEY", ""))
        self.pub_var = tk.StringVar(value=os.environ.get("PAPERSU_PUBLIC_KEY", ""))
        for label, var in (("私钥 private_key.pem", self.priv_var),
                           ("公钥 public_key.pem", self.pub_var)):
            row = ttk.Frame(keys)
            row.pack(fill="x", padx=6, pady=2)
            ttk.Label(row, text=label, width=22).pack(side="left")
            ttk.Entry(row, textvariable=var).pack(side="left", fill="x", expand=True)
            ttk.Button(row, text="选择", width=6,
                       command=lambda v=var: self._pick(v)).pack(side="left", padx=4)

        form = ttk.LabelFrame(tab, text="参数")
        form.pack(fill="x", **pad)
        self.user_var = tk.StringVar()
        self.tier_var = tk.StringVar(value="pro")
        self.exp_var = tk.StringVar(
            value=(datetime.date.today() + datetime.timedelta(days=365)).isoformat())
        self.count_var = tk.StringVar(value="1")
        self.forever_var = tk.BooleanVar(value=False)

        grid = ttk.Frame(form)
        grid.pack(fill="x", padx=6, pady=4)
        ttk.Label(grid, text="用户名 / 备注").grid(row=0, column=0, sticky="w")
        ttk.Entry(grid, textvariable=self.user_var, width=26).grid(row=0, column=1, padx=6)
        ttk.Label(grid, text="等级").grid(row=0, column=2, sticky="w")
        ttk.Combobox(grid, textvariable=self.tier_var, width=10, state="readonly",
                     values=("pro", "vip", "premium")).grid(row=0, column=3, padx=6)
        ttk.Label(grid, text="到期日").grid(row=0, column=4, sticky="w")
        ttk.Entry(grid, textvariable=self.exp_var, width=13).grid(row=0, column=5, padx=6)
        ttk.Checkbutton(grid, text="永不过期", variable=self.forever_var).grid(row=0, column=6, padx=6)
        ttk.Label(grid, text="张数").grid(row=0, column=7, sticky="w")
        ttk.Entry(grid, textvariable=self.count_var, width=6).grid(row=0, column=8, padx=6)
        ttk.Button(grid, text="生成", command=self.generate).grid(row=0, column=9, padx=8)

        cols = ("card", "user", "tier", "exp", "made")
        holder = ttk.Frame(tab)
        holder.pack(fill="both", expand=True, **pad)
        self.tree = ttk.Treeview(holder, columns=cols, show="headings")
        for c, w, t in (("card", 470, "卡密"), ("user", 110, "用户"), ("tier", 70, "等级"),
                        ("exp", 100, "到期"), ("made", 150, "生成时间")):
            self.tree.heading(c, text=t)
            self.tree.column(c, width=w, anchor="w")
        vs = ttk.Scrollbar(holder, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=vs.set)
        vs.pack(side="right", fill="y")
        self.tree.pack(side="left", fill="both", expand=True)

        bar = ttk.Frame(tab)
        bar.pack(fill="x", **pad)
        ttk.Button(bar, text="导出 CSV", command=lambda: self.export("csv")).pack(side="left", padx=4)
        ttk.Button(bar, text="导出 TXT（一行一张）",
                   command=lambda: self.export("txt")).pack(side="left", padx=4)
        ttk.Button(bar, text="复制全部卡密", command=self.copy_all).pack(side="left", padx=4)
        ttk.Button(bar, text="清空列表", command=self.clear).pack(side="left", padx=4)

    # -------------------------------------------------- 第 2 页：授权站 / 店铺
    def _build_site_tab(self, nb, pad):
        tab = ttk.Frame(nb)
        nb.add(tab, text="    授权站 / 店铺    ")

        site = ttk.LabelFrame(tab, text="授权站（可选 —— 卡密本身离线就能用，这里只是登记 + 限制一张卡能用几次）")
        site.pack(fill="x", **pad)
        self.site_var = tk.StringVar(value="https://vip-anos-rekey.adt.shdiv.net")
        self.secret_var = tk.StringVar()
        r1 = ttk.Frame(site)
        r1.pack(fill="x", padx=6, pady=3)
        ttk.Label(r1, text="授权站地址", width=22).pack(side="left")
        ttk.Entry(r1, textvariable=self.site_var).pack(side="left", fill="x", expand=True)
        r2 = ttk.Frame(site)
        r2.pack(fill="x", padx=6, pady=3)
        ttk.Label(r2, text="共享密钥 API_SECRET", width=22).pack(side="left")
        ttk.Entry(r2, textvariable=self.secret_var, show="*").pack(side="left", fill="x", expand=True)
        ttk.Button(r2, text="推送「签发」页列表里全部",
                   command=self.push_all).pack(side="left", padx=6)
        self.site_status = tk.StringVar(value="")
        ttk.Label(site, textvariable=self.site_status, wraplength=1020,
                  justify="left").pack(fill="x", padx=6, pady=3)

        store = ttk.LabelFrame(tab, text="店铺对接（拉订单 → 自动签发 → 自动推送到授权站）")
        store.pack(fill="x", **pad)
        self.pull_var = tk.StringVar()
        self.path_var = tk.StringVar()
        self.idf_var = tk.StringVar(value="id")
        self.userf_var = tk.StringVar(value="user")
        for label, var in (("订单接口 URL", self.pull_var),
                           ("数组路径（可空）", self.path_var),
                           ("订单号字段", self.idf_var),
                           ("用户字段", self.userf_var)):
            rr = ttk.Frame(store)
            rr.pack(fill="x", padx=6, pady=3)
            ttk.Label(rr, text=label, width=22).pack(side="left")
            ttk.Entry(rr, textvariable=var).pack(side="left", fill="x", expand=True)
        ttk.Button(store, text="拉一次并推送", command=self.pull_once).pack(anchor="w", padx=6, pady=4)
        self.pull_status = tk.StringVar(value="")
        ttk.Label(store, textvariable=self.pull_status, wraplength=1020,
                  justify="left").pack(fill="x", padx=6, pady=3)

        note = ttk.LabelFrame(tab, text="说明")
        note.pack(fill="both", expand=True, **pad)
        text = tk.Text(note, wrap="word", height=12, relief="flat", background="#fafafa")
        text.pack(fill="both", expand=True, padx=6, pady=6)
        text.insert("1.0", (
            "授权站是可选的。卡密自带签名，应用本地验签，断网也能激活；\n"
            "这个站的作用是登记发了哪些卡密、限制一张卡能用几次、出问题能停用。\n\n"
            "它不存卡密明文（库里只有 sha256），也不收集任何设备信息。\n"
            "防共享靠 max_uses，默认 1 —— 也就是第一次激活后作废，\n"
            "用户重装或换机时你到管理页把次数调大即可。\n\n"
            "店铺对接：填订单接口 URL 后点「拉一次并推送」，\n"
            "已处理过的订单号记在 exe 同目录的 handled.json，重复拉不会重发。\n"
            "acg-faka 的订单接口地址和字段名给我之后，这些框就不用手填了。"
        ))
        text.configure(state="disabled")

    # ---------------------------------------------------------- 第 3 页：校验
    def _build_check_tab(self, nb, pad):
        tab = ttk.Frame(nb)
        nb.add(tab, text="    校验    ")

        box = ttk.LabelFrame(tab, text="粘贴一张卡密，看它解析出来是什么")
        box.pack(fill="x", **pad)
        self.check_var = tk.StringVar()
        ttk.Entry(box, textvariable=self.check_var).pack(fill="x", padx=6, pady=6)
        ttk.Button(box, text="校验", command=self.do_check).pack(anchor="w", padx=6)
        self.check_out = tk.StringVar(value="")
        ttk.Label(box, textvariable=self.check_out, wraplength=1020,
                  justify="left").pack(fill="x", padx=6, pady=6)

        out = ttk.LabelFrame(tab, text="结果")
        out.pack(fill="both", expand=True, **pad)
        self.check_text = tk.Text(out, wrap="word", relief="flat", background="#fafafa")
        self.check_text.pack(fill="both", expand=True, padx=6, pady=6)
        self.check_text.insert("1.0", "校验结果会显示在这里。\n")
        self.check_text.configure(state="disabled")

    # ------------------------------------------------------------------ 动作
    def _pick(self, var):
        path = filedialog.askopenfilename(filetypes=[("PEM", "*.pem"), ("全部", "*.*")])
        if path:
            var.set(path)

    def _state_path(self):
        if getattr(sys, "frozen", False):
            base = os.path.dirname(os.path.abspath(sys.argv[0]))
        else:
            base = os.path.dirname(os.path.abspath(__file__))
        return os.path.join(base, "handled.json")

    def generate(self):
        priv = self.priv_var.get().strip()
        if not priv or not os.path.exists(priv):
            messagebox.showerror(APP_TITLE, "先选私钥文件（private_key.pem）")
            return
        try:
            count = max(1, min(1000, int(self.count_var.get())))
        except ValueError:
            messagebox.showerror(APP_TITLE, "张数要填数字")
            return
        exp = "" if self.forever_var.get() else self.exp_var.get().strip()
        if exp:
            try:
                datetime.date.fromisoformat(exp)
            except ValueError:
                messagebox.showerror(APP_TITLE, "到期日要写成 yyyy-MM-dd")
                return
        made = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        user = self.user_var.get().strip()
        tier = self.tier_var.get().strip() or "pro"
        try:
            for _ in range(count):
                card = make_card(priv, user, tier, exp)
                self.rows.append({"card": card, "user": user, "tier": tier,
                                  "exp": exp or "永不过期", "made": made})
                self.tree.insert("", "end", values=(card, user, tier, exp or "永不过期", made))
        except Exception as exc:
            messagebox.showerror(APP_TITLE, "签发失败：%s" % exc)
            return
        self.status.set("已生成 %d 张，累计 %d 张" % (count, len(self.rows)))

    def export(self, kind):
        if not self.rows:
            messagebox.showinfo(APP_TITLE, "列表是空的")
            return
        ext = ".csv" if kind == "csv" else ".txt"
        path = filedialog.asksaveasfilename(defaultextension=ext,
                                            initialfile="papersu-cards" + ext)
        if not path:
            return
        with open(path, "w", encoding="utf-8-sig", newline="") as fh:
            if kind == "csv":
                writer = csv.DictWriter(fh, fieldnames=["card", "user", "tier", "exp", "made"])
                writer.writeheader()
                writer.writerows(self.rows)
            else:
                for row in self.rows:
                    fh.write(row["card"] + "\n")
        self.status.set("已导出 %d 张到 %s" % (len(self.rows), path))
        messagebox.showinfo(APP_TITLE, "导出好了：\n%s\n\n可以直接粘进发卡系统的卡密池。" % path)

    def copy_all(self):
        if not self.rows:
            return
        self.clipboard_clear()
        self.clipboard_append("\n".join(r["card"] for r in self.rows))
        self.status.set("已复制 %d 张到剪贴板" % len(self.rows))

    def clear(self):
        self.rows.clear()
        for item in self.tree.get_children():
            self.tree.delete(item)
        self.status.set("已清空")

    def push_all(self):
        if not self.rows:
            messagebox.showinfo(APP_TITLE, "「签发」页的列表是空的，先生成几张")
            return
        cards = [{"key": r["card"], "user": r["user"], "tier": r["tier"],
                  "exp": "" if r["exp"] == "永不过期" else r["exp"]} for r in self.rows]
        self.site_status.set("推送中…")
        self.update_idletasks()
        ok, msg = push.push_cards(self.site_var.get(), self.secret_var.get(), cards)
        self.site_status.set(("成功：" if ok else "失败：") + msg)

    def pull_once(self):
        url = self.pull_var.get().strip()
        if not url:
            messagebox.showinfo(APP_TITLE, "先填店铺的订单接口 URL")
            return
        priv = self.priv_var.get().strip()
        if not priv or not os.path.exists(priv):
            messagebox.showerror(APP_TITLE, "先到「签发」页选私钥文件")
            return
        self.pull_status.set("拉取中…")
        self.update_idletasks()
        orders, err = push.pull_orders(url, self.path_var.get().strip(),
                                       self.idf_var.get().strip() or "id",
                                       self.userf_var.get().strip() or "user",
                                       self.tier_var.get().strip() or "vip")
        if err:
            self.pull_status.set("失败：" + err)
            return
        state = self._state_path()
        handled = push.load_state(state)
        fresh = [o for o in orders if o["order"] not in handled]
        if not fresh:
            self.pull_status.set("拉到 %d 个订单，没有新的（已处理的记在 %s）"
                                 % (len(orders), os.path.basename(state)))
            return
        made = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        cards = []
        for o in fresh:
            try:
                card = make_card(priv, o["user"] or o["order"], o["tier"], o["exp"])
            except Exception as exc:
                self.pull_status.set("签发失败：%s" % exc)
                return
            cards.append({"key": card, "user": o["user"], "tier": o["tier"],
                          "exp": o["exp"], "order": o["order"]})
            self.rows.append({"card": card, "user": o["user"], "tier": o["tier"],
                              "exp": o["exp"] or "永不过期", "made": made})
            self.tree.insert("", "end", values=(card, o["user"], o["tier"],
                                                o["exp"] or "永不过期", made))
        ok, msg = push.push_cards(self.site_var.get(), self.secret_var.get(), cards)
        if ok:
            handled.update(o["order"] for o in fresh)
            push.save_state(state, handled)
        self.pull_status.set(("成功：" if ok else "失败：") + "新签 %d 张；%s" % (len(fresh), msg))

    def do_check(self):
        ok, data = verify_card(self.check_var.get(), self.pub_var.get().strip())
        line = ("有效  " + json.dumps(data, ensure_ascii=False)) if ok else ("无效  " + str(data))
        self.check_out.set(line)
        self.check_text.configure(state="normal")
        self.check_text.insert("end", line + "\n")
        self.check_text.see("end")
        self.check_text.configure(state="disabled")


if __name__ == "__main__":
    # windowed 打包（没有控制台）时，一旦启动就崩是什么都看不到的，
    # 所以这里兜一层，把异常弹成对话框。
    try:
        App().mainloop()
    except Exception:
        import traceback
        detail = traceback.format_exc()
        try:
            from tkinter import messagebox
            messagebox.showerror(APP_TITLE, "启动失败：\n\n" + detail)
        except Exception:
            pass
        with open(os.path.join(os.path.dirname(os.path.abspath(sys.argv[0])),
                               "papersu-license-error.log"), "w", encoding="utf-8") as fh:
            fh.write(detail)
        raise