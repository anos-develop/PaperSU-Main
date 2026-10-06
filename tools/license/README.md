# 卡密签发工具

纸SU 的卡密是**离线验证**的：卡密本身带一个 ECDSA P-256 签名，应用内置公钥，
在手机上本地验签。所以：

- 应用**不需要联网**，断网也能激活和使用
- 用户设备**不上报任何信息**（没有 IMEI、没有 Android ID、没有设备指纹）
- 你的服务器**挂了也不影响**已发出去的卡密
- 你这台电脑就是签发中心，**不需要服务器**

## 卡密长什么样

```
base64url(载荷) . base64url(签名)
```

载荷是一小段 JSON：

```json
{"u":"用户名","t":"等级","exp":"2027-12-31"}
```

例：

```
eyJ1IjoidGVzdCIsInQiOiJwcm8iLCJleHAiOiIyMDI3LTEyLTMxIn0.MEQCIBHgq3tw8Ht-Jl0Vp46eGB1qmTRWRPe6wgAY1RZsFSlWAiArkfPmGK_ZaZFZkblklxLT24nX8SzSr3Y7lqYidu3LWw
```

## 首次准备

```powershell
# 1. 生成密钥对（只做一次）
python tools\license\genkey.py

# 2. 它会打印一行 base64，把它填进
#    manager\app\src\main\java\com\sukisu\ultra\ui\license\LicenseManager.kt
#    的 PUBLIC_KEY_BASE64

# 3. 重新构建应用
```

**私钥文件**在 `tools/license/keys/private_key.pem`（默认）—— **绝对不能提交到仓库** ✓
`.gitignore` 已经屏蔽了 `*.pem` 和 `tools/license/keys/`。

**⇒ 换成仓库外的目录**（更安全）：

```powershell
$env:PAPERSU_KEY_DIR='D:\Users\code\Desktop\papersu-apk\license-keys'
python tools\license\genkey.py
python tools\license\sign.py --user alice --tier pro --exp 2027-12-31
```

## 签发卡密

```powershell
# 一年期的 pro
python tools\license\sign.py --user alice --tier pro --exp 2027-12-31

# 不过期
python tools\license\sign.py --user bob --tier vip

# 同时写到文件
python tools\license\sign.py --user carol --tier pro --exp 2027-06-30 --out carol.txt
```

**⇒ 输出就是卡密** ✓ —— 你把它发给买家（或粘进你的发卡系统）✓

## 接你已有的发卡系统

**你那边已经有发卡平台的话，只需要让它调一次签名** ✓ —— **⇒ 两种做法** ✓：

**A. PHP 里直接签**（需要 openssl 扩展，一般都有）

```php
<?php
function make_card_key(string $user, string $tier, string $exp): string {
    $payload = json_encode(['u'=>$user,'t'=>$tier,'exp'=>$exp],
                           JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);
    $priv = openssl_pkey_get_private(file_get_contents('/safe/path/private_key.pem'));
    openssl_sign($payload, $sig, $priv, OPENSSL_ALGO_SHA256);
    $b64u = fn($s) => rtrim(strtr(base64_encode($s), '+/', '-_'), '=');
    return $b64u($payload) . '.' . $b64u($sig);
}
```

**B. 让发卡系统调用一个 HTTP 接口**，那个接口跑这个 Python 脚本 ✓

**⇒ 关键点** ✓：**签名的内容必须和上面完全一致** ✓（JSON 用 `,` 和 `:` 不带空格 ✓，UTF-8 ✓，`exp` 用 `yyyy-MM-dd` ✓）—— **⇒ 签出来的卡密应用才认 ✓**

**⇒ 注意 `openssl_sign` 默认产出的就是 DER 签名 ✓ —— 和应用端的 `SHA256withECDSA` 一致 ✓**

## 应用侧怎么用

`manager/.../ui/license/LicenseManager.kt`：

```kotlin
LicenseManager.activate(context, cardKey)   // 验签 + 存下来
LicenseManager.current(context)             // 当前授权，过期自动失效
LicenseManager.clear(context)               // 移除本机授权
license.isPro                               // 高级功能开关就用它判断
```

**⇒ 高级功能加开关** ✓：**在对应位置判断 `LicenseManager.current(ctx)?.isPro == true`** ✓

## 换成在线验证（如果你更想那样）

**⇒ 我不建议** ✓ —— **服务器验证的代价**：用户必须联网 ✓、你服务器挂了所有人都用不了 ✓、你得存一份用户数据库（被拖库就全泄 ✓）✓

**⇒ 但如果你要** ✓：**把接口地址和返回格式告诉我就行** ✓ —— **⇒ `MinePager.kt` 里「账号登录」那块现在是占位的 ✓，接上就是** ✓