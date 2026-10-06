# paperSU 授权站

配套 `papersu-license.exe` 的授权服务。**卡密是离线签名的**（应用本地验签），这个站是
**可选的**追加入口：登记发了哪些卡密、限制一张卡密能用几次、出问题能停用。

## 它不做什么

- **不存卡密明文**。库里只有 `sha256(卡密)`。库被拖走也拿不到能用的卡密。
- **不收集设备信息**。没有 IMEI、没有 Android ID、没有指纹。
- **不是必须的**。应用断网也能激活已签发的卡密；这个站挂了不影响已激活的用户。

## 装

```bash
# 1. 传上去
把整个 authsite 目录丢到 vip-anos-rekey.adt.shdiv.net 的根目录

# 2. 配置
cp config.example.php config.php
#   DB_DSN      默认 SQLite，不用管；想用 MySQL 就改那两行注释
#   API_SECRET  共享密钥，生成：php -r "echo bin2hex(random_bytes(32));"
#   ADMIN_PASS_HASH  管理页密码：php -r "echo password_hash('你的密码', PASSWORD_DEFAULT);"

# 3. 建表（SQLite 首次访问会自动建；MySQL 手动导入）
mysql -u用户 -p 库名 < install.sql

# 4. 目录权限（SQLite 需要写权限）
mkdir -p data && chmod 700 data
```

## 接口

| 方法 | 路径 | 鉴权 | 用途 |
|---|---|---|---|
| POST | `/api/issue.php` | `X-Auth` | exe 推送新签发的卡密 |
| POST | `/api/redeem.php` | 无（限流） | 设备激活时扣一次可用次数 |
| POST | `/api/check.php` | `X-Auth` | 查一张卡密的状态 |
| GET | `/admin.php` | 密码 | 管理页 |

**推送示例**

```bash
curl -X POST https://vip-anos-rekey.adt.shdiv.net/api/issue.php \
  -H "X-Auth: 你的API_SECRET" -H "Content-Type: application/json" \
  -d '{"cards":[{"key":"eyJ1Ijoi...","user":"anos","tier":"vip","exp":"","order":"20260101-001"}]}'
```

**兑换示例**

```bash
curl -X POST https://vip-anos-rekey.adt.shdiv.net/api/redeem.php \
  -H "Content-Type: application/json" -d '{"key":"eyJ1Ijoi..."}'
# 成功 {"ok":true,"tier":"vip","exp":"","uses_left":0}
```

## Nginx 伪静态（避免 config.php / data 被下载）

```nginx
location ~ ^/(config\.php|data/|lib\.php|install\.sql) { return 404; }
location ~ /\.(?!well-known) { return 404; }
```

**⇒ 这条一定要加** ✓ —— **⇒ 否则别人可以直接下载 `config.php` 拿到你的密钥** ✗

## 一张卡密能用几次

`max_uses` 列。默认 1 —— 也就是**第一次激活后就作废**，换手机需要你到管理页把次数调大。

**⇒ 这是不用设备标识实现防共享的办法** ✓ —— **⇒ 代价是用户重装得找你一次** ✓