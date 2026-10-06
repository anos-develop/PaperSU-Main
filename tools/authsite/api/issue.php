<?php
/**
 * exe 推送新签发的卡密到这里。
 *
 * POST /api/issue.php
 *   X-Auth: <API_SECRET>
 *   {"cards":[{"key":"<完整卡密>","user":"anos","tier":"vip","exp":"","order":"20260101-001"}]}
 *
 * 只存 sha256，明文立刻丢掉。重复推送不报错（幂等）。
 */
declare(strict_types=1);
require_once dirname(__DIR__) . '/lib.php';

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    out(['ok' => false, 'error' => 'POST only'], 405);
}
require_secret();

$in = body();
$cards = $in['cards'] ?? null;
if (!is_array($cards) || $cards === []) {
    out(['ok' => false, 'error' => 'cards[] required'], 400);
}
if (count($cards) > 2000) {
    out(['ok' => false, 'error' => 'at most 2000 per call'], 400);
}

$now = gmdate('c');
$insert = db()->prepare(
    'INSERT INTO licenses (card_hash, username, tier, expiry, nonce, order_id, max_uses, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(card_hash) DO NOTHING'
);

$added = 0;
$skipped = 0;
db()->beginTransaction();
try {
    foreach ($cards as $card) {
        if (!is_array($card)) { $skipped++; continue; }
        $key = trim((string)($card['key'] ?? ''));
        if ($key === '' || !str_contains($key, '.')) { $skipped++; continue; }
        $uses = (int)($card['max_uses'] ?? 1);
        if ($uses < 1) { $uses = 1; }
        if ($uses > 100) { $uses = 100; }
        $insert->execute([
            key_hash($key),
            (string)($card['user'] ?? ''),
            (string)($card['tier'] ?? 'pro'),
            (string)($card['exp'] ?? ''),
            (string)($card['nonce'] ?? ''),
            (string)($card['order'] ?? ''),
            $uses,
            $now,
        ]);
        if ($insert->rowCount() > 0) { $added++; } else { $skipped++; }
    }
    db()->commit();
} catch (Throwable $e) {
    db()->rollBack();
    out(['ok' => false, 'error' => 'db error'], 500);
}
out(['ok' => true, 'added' => $added, 'skipped' => $skipped]);