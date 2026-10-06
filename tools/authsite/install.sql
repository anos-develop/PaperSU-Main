-- SQLite / MySQL 都能跑（AUTOINCREMENT 那行 MySQL 要改成 AUTO_INCREMENT）
CREATE TABLE IF NOT EXISTS licenses (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    card_hash    TEXT    NOT NULL UNIQUE,   -- sha256(完整卡密)，明文不落库
    username     TEXT    NOT NULL DEFAULT '',
    tier         TEXT    NOT NULL DEFAULT 'pro',
    expiry       TEXT    NOT NULL DEFAULT '',   -- yyyy-MM-dd，空=永不过期
    nonce        TEXT    NOT NULL DEFAULT '',
    order_id     TEXT    NOT NULL DEFAULT '',   -- 来源订单号，便于对账
    max_uses     INTEGER NOT NULL DEFAULT 1,
    used_count   INTEGER NOT NULL DEFAULT 0,
    disabled     INTEGER NOT NULL DEFAULT 0,
    note         TEXT    NOT NULL DEFAULT '',
    created_at   TEXT    NOT NULL,
    last_used_at TEXT    NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_licenses_username ON licenses (username);
CREATE INDEX IF NOT EXISTS idx_licenses_order ON licenses (order_id);