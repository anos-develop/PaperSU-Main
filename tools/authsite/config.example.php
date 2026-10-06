<?php
/**
 * 复制成 config.php 再改。
 *
 * 默认用 SQLite，不用配数据库；要跟店铺共用 MySQL 就改 DB_DSN。
 */
declare(strict_types=1);

// SQLite（默认，直接能用）
define('DB_DSN', 'sqlite:' . __DIR__ . '/data/licenses.sqlite');
define('DB_USER', '');
define('DB_PASS', '');

// MySQL（跟 acg-faka 同一个库也行，换库名）
// define('DB_DSN', 'mysql:host=127.0.0.1;dbname=papersu;charset=utf8mb4');
// define('DB_USER', 'papersu');
// define('DB_PASS', '换成你的密码');

/**
 * exe 推送卡密时带的共享密钥。随机 64 位十六进制。
 *   php -r "echo bin2hex(random_bytes(32));"
 */
define('API_SECRET', 'PUT_A_RANDOM_64_HEX_SECRET_HERE');

/** 管理页密码的 bcrypt 哈希。生成：php -r "echo password_hash('你的密码', PASSWORD_DEFAULT);" */
define('ADMIN_PASS_HASH', 'PUT_A_BCRYPT_HASH_HERE');

/** 同一个 IP 每分钟最多几次兑换请求。 */
define('REDEEM_RATE_PER_MIN', 20);

/**
 * 签名私钥的路径。【必须放在网站根目录之外】，权限 600，
 * 属主和 php-fpm 跑的用户一致（一般是 www-data）。
 *
 * 例：/etc/papersu/private_key.pem
 *
 * 只有 api/sign.php 会读它 —— 也就是「发货时现场签发」那个接口。
 * 如果你只用卡密池（exe 预先生成好再导入），可以不配这一项。
 */
define('SIGNING_KEY_PATH', '');