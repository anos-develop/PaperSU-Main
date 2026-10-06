-- sign.php 需要 order_id 唯一（幂等），单独补这一步
-- SQLite / MySQL 通用（MySQL 用 VARCHAR(64) 也行）
CREATE UNIQUE INDEX IF NOT EXISTS idx_licenses_order_unique
    ON licenses (order_id)
    WHERE order_id <> '';
-- MySQL 不支持带 WHERE 的部分索引，用这句替代：
-- ALTER TABLE licenses ADD UNIQUE KEY idx_licenses_order_unique (order_id);