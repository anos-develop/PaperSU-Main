<?php
/**
 * 查一张卡密的状态，不改动任何计数。给管理页和 exe 对账用。
 *
 * POST /api/check.php   {"key":"..."}   需要 X-Auth
 */
declare(strict_types=1);
require_once dirname(__DIR__) . '/lib.php';

require_secret();
$in = body();
$key = trim((string)($in['key'] ?? ''));
if ($key === '') {
    out(['ok' => false, 'error' => 'key required'], 400);
}
$stmt = db()->prepare('SELECT username,tier,expiry,used_count,max_uses,disabled,order_id,created_at,last_used_at
                       FROM licenses WHERE card_hash = ?');
$stmt->execute([key_hash($key)]);
$row = $stmt->fetch();
if (!$row) {
    out(['ok' => true, 'found' => false]);
}
$row['found'] = true;
$row['ok'] = true;
out($row);