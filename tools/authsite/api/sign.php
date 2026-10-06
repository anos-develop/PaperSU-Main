<?php
/**
 * 现场签发一张卡密。给「发货时自动签发」的方案用（acg-faka 插件或任何外部系统）。
 *
 * POST /api/sign.php
 *   X-Auth: <API_SECRET>
 *   {"order_id":"20261006-0001","user":"alice","tier":"vip","exp":"2027-12-31"}
 *
 * 返回
 *   {"ok":true,"card":"eyJ1Ijoi...","reused":false}
 *   {"ok":false,"error":"..."}
 *
 * 幂等：同一个 order_id 再调一次，返回的是【第一次签的那张】，
 *       不会重复扣库存、也不会给同一个订单两张不同的卡。这靠 order_id 唯一索引保证。
 *
 * 私钥放哪：绝对不要放进网站目录。config.php 里用 SIGNING_KEY_PATH 指向 web 根目录外面的
 * 文件，权限 600，属主和 php-fpm 同一个用户。
 */
declare(strict_types=1);
require_once dirname(__DIR__) . '/lib.php';

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    out(['ok' => false, 'error' => 'POST only'], 405);
}
require_secret();

$in = body();
$orderId = trim((string)($in['order_id'] ?? ''));
if ($orderId === '') {
    out(['ok' => false, 'error' => 'order_id required'], 400);
}

// ---------- 幂等：这个订单已经签过了吗 ----------
$stmt = db()->prepare('SELECT card_hash, tier, expiry FROM licenses WHERE order_id = ? LIMIT 1');
$stmt->execute([$orderId]);
$existing = $stmt->fetch();
if ($existing) {
    // 卡密明文没有存库（只存了哈希），所以没法把原卡再吐一次。
    // 对同一个订单重放，标准做法是让调用方从它自己那边取回已经发出去的那张。
    out([
        'ok' => false,
        'error' => '这个订单已经签发过了',
        'already_signed' => true,
        'tier' => (string)$existing['tier'],
        'exp' => (string)$existing['expiry'],
    ], 409);
}

// ---------- 签名 ----------
if (!defined('SIGNING_KEY_PATH') || SIGNING_KEY_PATH === '') {
    out(['ok' => false, 'error' => 'config.php 里没有配 SIGNING_KEY_PATH'], 500);
}
if (!is_readable(SIGNING_KEY_PATH)) {
    out(['ok' => false, 'error' => '读不到签名私钥'], 500);
}
$pem = file_get_contents(SIGNING_KEY_PATH);
if ($pem === false) {
    out(['ok' => false, 'error' => '读不到签名私钥'], 500);
}

$user = (string)($in['user'] ?? '');
$tier = (string)($in['tier'] ?? 'vip');
$exp  = (string)($in['exp'] ?? '');
if ($exp !== '' && !preg_match('/^\d{4}-\d{2}-\d{2}$/', $exp)) {
    out(['ok' => false, 'error' => 'exp 要写成 yyyy-MM-dd'], 400);
}

// 载荷必须跟应用端完全一致：紧凑 JSON、UTF-8、字段 u/t/exp/n
$payload = json_encode(
    ['u' => $user, 't' => $tier, 'exp' => $exp, 'n' => bin2hex(random_bytes(4))],
    JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES
);

$privateKey = openssl_pkey_get_private($pem);
if ($privateKey === false) {
    out(['ok' => false, 'error' => '私钥格式不对（需要 EC PRIVATE KEY 的 PEM）'], 500);
}
$signature = '';
if (!openssl_sign($payload, $signature, $privateKey, OPENSSL_ALGO_SHA256)) {
    out(['ok' => false, 'error' => '签名失败'], 500);
}

$b64u = static function (string $raw): string {
    return rtrim(strtr(base64_encode($raw), '+/', '-_'), '=');
};
$card = $b64u($payload) . '.' . $b64u($signature);

// ---------- 入库（只存哈希）----------
$maxUses = max(1, min(100, (int)($in['max_uses'] ?? 1)));
$insert = db()->prepare(
    'INSERT INTO licenses (card_hash, username, tier, expiry, nonce, order_id, max_uses, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)'
);
$nonce = '';
if (preg_match('/"n":"([0-9a-f]+)"/', $payload, $m)) {
    $nonce = $m[1];
}
try {
    $insert->execute([key_hash($card), $user, $tier, $exp, $nonce, $orderId, $maxUses, gmdate('c')]);
} catch (Throwable $e) {
    // order_id 唯一索引撞了 —— 说明同一瞬间有第二个请求也进来了，说明是重放
    out(['ok' => false, 'error' => '这个订单已经签发过了', 'already_signed' => true], 409);
}

out(['ok' => true, 'card' => $card, 'reused' => false]);