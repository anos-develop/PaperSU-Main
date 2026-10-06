<?php
/**
 * 设备激活时调这里（可选）。
 *
 * POST /api/redeem.php   {"key":"<完整卡密>"}
 *   成功 -> {"ok":true,"tier":"vip","exp":"","uses_left":0}
 *   失败 -> {"ok":false,"error":"..."}
 *
 * 不收集任何设备信息：防共享靠 max_uses（这张卡密总共能用几次）。
 * 也就是说，卡密第一次激活后就用掉一次额度，换手机要重新激活就会再扣一次。
 */
declare(strict_types=1);
require_once dirname(__DIR__) . '/lib.php';

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    out(['ok' => false, 'error' => 'POST only'], 405);
}
rate_limit('redeem', REDEEM_RATE_PER_MIN);

$in = body();
$key = trim((string)($in['key'] ?? ''));
if ($key === '') {
    out(['ok' => false, 'error' => 'key required'], 400);
}

$pdo = db();
$pdo->beginTransaction();
try {
    $stmt = $pdo->prepare('SELECT * FROM licenses WHERE card_hash = ?');
    $stmt->execute([key_hash($key)]);
    $row = $stmt->fetch();
    if (!$row) {
        $pdo->rollBack();
        out(['ok' => false, 'error' => '这张卡密不在库里']);
    }
    if ((int)$row['disabled'] === 1) {
        $pdo->rollBack();
        out(['ok' => false, 'error' => '这张卡密已被停用']);
    }
    $exp = (string)$row['expiry'];
    if ($exp !== '' && $exp < gmdate('Y-m-d')) {
        $pdo->rollBack();
        out(['ok' => false, 'error' => '这张卡密已于 ' . $exp . ' 过期']);
    }
    if ((int)$row['used_count'] >= (int)$row['max_uses']) {
        $pdo->rollBack();
        out(['ok' => false, 'error' => '这张卡密的可用次数已用完']);
    }
    $upd = $pdo->prepare('UPDATE licenses SET used_count = used_count + 1, last_used_at = ? WHERE id = ? AND used_count < max_uses');
    $upd->execute([gmdate('c'), $row['id']]);
    if ($upd->rowCount() === 0) {
        $pdo->rollBack();
        out(['ok' => false, 'error' => '这张卡密的可用次数已用完']);
    }
    $pdo->commit();
} catch (Throwable $e) {
    if ($pdo->inTransaction()) { $pdo->rollBack(); }
    out(['ok' => false, 'error' => 'db error'], 500);
}

out([
    'ok' => true,
    'tier' => (string)$row['tier'],
    'exp' => (string)$row['expiry'],
    'uses_left' => max(0, (int)$row['max_uses'] - ((int)$row['used_count'] + 1)),
]);