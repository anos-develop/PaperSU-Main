#!/usr/bin/env python3
"""paperSU 卡密签发工具（图形版）

生成、导出、校验纸SU 的离线卡密。私钥只在本机使用，全程不联网。

卡密格式： base64url(载荷) . base64url(签名)
载荷：     {"u":"用户名","t":"等级","exp":"yyyy-MM-dd","n":"随机数"}
签名：     ECDSA P-256 + SHA-256（openssl 默认 DER 编码，与应用端 SHA256withECDSA 一致）

每张卡密带一个随机 n，所以同一用户、同一到期日签两次也会得到两张不同的卡密 —— 重复不可能发生。
"""
import base64
import csv
import datetime
import io
import json
import os
import secrets
import subprocess
import sys
import tempfile
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

APP_TITLE = "paperSU 卡密签发"
OPENSSL_CANDIDATES = (
    r"D:\Program Files\Git\usr\bin\openssl.exe",
    r"D:\Program Files\Git\mingw64\bin\openssl.exe",
    "openssl",
)


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
    ossl = openssl_path()
    if not ossl:
        raise RuntimeError("找不到 openssl（装个 Git for Windows 就有了）")
    with tempfile.TemporaryDirectory() as tmp:
        pfile = os.path.join(tmp, "p.bin")
        sfile = os.path.join(tmp, "s.bin")
        with open(pfile, "wb") as fh:
            fh.write(payload)
        subprocess.run([ossl, "dgst", "-sha256", "-sign", priv_path, "-out", sfile, pfile],
                       check=True, capture_output=True)
        with open(sfile, "rb") as fh:
            return fh.read()


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
    ossl = openssl_path()
    if not ossl or not os.path.exists(pub_path):
        return False, "缺少 openssl 或公钥文件"
    with tempfile.TemporaryDirectory() as tmp:
        pfile = os.path.join(tmp, "p.bin")
        sfile = os.path.join(tmp, "s.bin")
        with open(pfile, "wb") as fh:
            fh.write(payload)
        with open(sfile, "wb") as fh:
            fh.write(signature)
        proc = subprocess.run(
            [ossl, "dgst", "-sha256", "-verify", pub_path, "-signature", sfile, pfile],
            capture_output=True,
        )
    if proc.returncode != 0:
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
        self.geometry("980x680")
        self.rows = []
        self._build()

    def _build(self):
        pad = {"padx": 8, "pady": 4}

        top = ttk.LabelFrame(self, text="密钥")
        top.pack(fill="x", **pad)
        self.priv_var = tk.StringVar(value=os.environ.get("PAPERSU_PRIVATE_KEY", ""))
        self.pub_var = tk.StringVar(value=os.environ.get("PAPERSU_PUBLIC_KEY", ""))
        for label, var in (("私钥 private_key.pem", self.priv_var), ("公钥 public_key.pem", self.pub_var)):
            row = ttk.Frame(top)
            row.pack(fill="x", padx=6, pady=2)
            ttk.Label(row, text=label, width=22).pack(side="left")
            ttk.Entry(row, textvariable=var).pack(side="left", fill="x", expand=True)
            ttk.Button(row, text="选择…", command=lambda v=var: self._pick(v)).pack(side="left", padx=4)

        form = ttk.LabelFrame(self, text="签发")
        form.pack(fill="x", **pad)
        self.user_var = tk.StringVar()
        self.tier_var = tk.StringVar(value="pro")
        self.exp_var = tk.StringVar(value=(datetime.date.today() + datetime.timedelta(days=365)).isoformat())
        self.count_var = tk.StringVar(value="1")
        self.forever_var = tk.BooleanVar(value=False)

        grid = ttk.Frame(form)
        grid.pack(fill="x", padx=6, pady=4)
        ttk.Label(grid, text="用户名 / 备注").grid(row=0, column=0, sticky="w")
        ttk.Entry(grid, textvariable=self.user_var, width=28).grid(row=0, column=1, padx=6)
        ttk.Label(grid, text="等级").grid(row=0, column=2, sticky="w")
        ttk.Combobox(grid, textvariable=self.tier_var, width=10,
                     values=("pro", "vip", "premium")).grid(row=0, column=3, padx=6)
        ttk.Label(grid, text="到期日").grid(row=0, column=4, sticky="w")
        ttk.Entry(grid, textvariable=self.exp_var, width=14).grid(row=0, column=5, padx=6)
        ttk.Checkbutton(grid, text="永不过期", variable=self.forever_var).grid(row=0, column=6, padx=6)
        ttk.Label(grid, text="张数").grid(row=0, column=7, sticky="w")
        ttk.Entry(grid, textvariable=self.count_var, width=6).grid(row=0, column=8, padx=6)
        ttk.Button(grid, text="生成", command=self.generate).grid(row=0, column=9, padx=8)

        cols = ("card", "user", "tier", "exp", "made")
        self.tree = ttk.Treeview(self, columns=cols, show="headings", height=15)
        for c, w, t in (("card", 430, "卡密"), ("user", 110, "用户"), ("tier", 70, "等级"),
                        ("exp", 100, "到期"), ("made", 140, "生成时间")):
            self.tree.heading(c, text=t)
            self.tree.column(c, width=w, anchor="w")
        self.tree.pack(fill="both", expand=True, **pad)

        bar = ttk.Frame(self)
        bar.pack(fill="x", **pad)
        ttk.Button(bar, text="导出 CSV", command=lambda: self.export("csv")).pack(side="left", padx=4)
        ttk.Button(bar, text="导出 TXT（一行一张）", command=lambda: self.export("txt")).pack(side="left", padx=4)
        ttk.Button(bar, text="复制全部卡密", command=self.copy_all).pack(side="left", padx=4)
        ttk.Button(bar, text="清空列表", command=self.clear).pack(side="left", padx=4)

        check = ttk.LabelFrame(self, text="校验（粘贴一张卡密，看它解析出来是什么）")
        check.pack(fill="both", **pad)
        self.check_var = tk.StringVar()
        ttk.Entry(check, textvariable=self.check_var).pack(fill="x", padx=6, pady=4)
        self.check_out = tk.StringVar(value="")
        ttk.Button(check, text="校验", command=self.do_check).pack(side="left", padx=6)
        ttk.Label(check, textvariable=self.check_out, wraplength=900, justify="left").pack(side="left", padx=6)

        self.status = tk.StringVar(value="就绪")
        ttk.Label(self, textvariable=self.status, anchor="w").pack(fill="x", padx=10, pady=4)

    def _pick(self, var):
        path = filedialog.askopenfilename(filetypes=[("PEM", "*.pem"), ("全部", "*.*")])
        if path:
            var.set(path)

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

    def do_check(self):
        ok, data = verify_card(self.check_var.get(), self.pub_var.get().strip())
        if ok:
            self.check_out.set("✓ 有效  " + json.dumps(data, ensure_ascii=False))
        else:
            self.check_out.set("✗ " + str(data))


if __name__ == "__main__":
    App().mainloop()