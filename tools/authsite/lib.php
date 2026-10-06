<?php
/**
 * 公共部分：数据库、JSON 出口、鉴权。
 *
 * 卡密【只在数据库里存 sha256】，从不落明文。这样即使库被拖走，也拿不到能用的卡密 ——
 * 明文只存在于买家的发卡记录和你本机的私钥签名过程里。
 */
declare(strict_types=1);

if (!file_exists(__DIR__ . '/config.php')) {
    http_response_code(500);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode(['ok' => false, 'error' => 'missing config.php']);
    exit;
}
require_once __DIR__ . '/config.php';

function db(): PDO
{
    static $pdo = null;
    if ($pdo === null) {
        $pdo = new PDO(DB_DSN, DB_USER, DB_PASS, [
            PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            PDO::ATTR_EMULATE_PREPARES => false,
        ]);
        if (str_starts_with(DB_DSN, 'sqlite')) {
            $pdo->exec('PRAGMA journal_mode=WAL');
            $pdo->exec('PRAGMA busy_timeout=5000');
        }
    }
    return $pdo;
}

function out(array $data, int $code = 200): void
{
    http_response_code($code);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

function body(): array
{
    $raw = file_get_contents('php://input') ?: '';
    if (strlen($raw) > 262144) {
        out(['ok' => false, 'error' => 'payload too large'], 413);
    }
    $decoded = json_decode($raw, true);
    return is_array($decoded) ? $decoded : [];
}

function key_hash(string $cardKey): string
{
    return hash('sha256', trim($cardKey));
}

/** exe 推送时用。hash_equals 防时序侧信道。 */
function require_secret(): void
{
    $given = $_SERVER['HTTP_X_AUTH'] ?? '';
    if (API_SECRET === '' || !hash_equals(API_SECRET, $given)) {
        out(['ok' => false, 'error' => 'unauthorized'], 401);
    }
}

function client_ip(): string
{
    return (string)($_SERVER['REMOTE_ADDR'] ?? '0.0.0.0');
}

/** 简单的每分钟限流，按 IP。表不存在就自动建。 */
function rate_limit(string $bucket, int $perMinute): void
{
    $pdo = db();
    $pdo->exec('CREATE TABLE IF NOT EXISTS rate (
        bucket TEXT NOT NULL,
        ip TEXT NOT NULL,
        minute TEXT NOT NULL,
        hits INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY (bucket, ip, minute)
    )');
    $minute = gmdate('Y-m-d\TH:i');
    $pdo->prepare('INSERT INTO rate (bucket, ip, minute, hits) VALUES (?, ?, ?, 1)
                   ON CONFLICT(bucket, ip, minute) DO UPDATE SET hits = hits + 1')
        ->execute([$bucket, client_ip(), $minute]);
    $stmt = $pdo->prepare('SELECT hits FROM rate WHERE bucket=? AND ip=? AND minute=?');
    $stmt->execute([$bucket, client_ip(), $minute]);
    if ((int)$stmt->fetchColumn() > $perMinute) {
        out(['ok' => false, 'error' => 'too many requests'], 429);
    }
}