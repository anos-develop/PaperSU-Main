<?php
/**
 * 简易管理页：看发了多少、谁在用、停用某张卡密。
 *
 * 打开 /admin.php，用 config.php 里 ADMIN_PASS_HASH 对应的密码登录。
 * 这里【不显示完整卡密】—— 库里只有哈希，重新显示是不可能的，也正因如此库被拖了也不怕。
 */
declare(strict_types=1);
require_once __DIR__ . '/lib.php';
session_start();

$err = '';
if (($_POST['action'] ?? '') === 'login') {
    if (password_verify((string)($_POST['pass'] ?? ''), ADMIN_PASS_HASH)) {
        $_SESSION['auth'] = true;
        session_regenerate_id(true);
    } else {
        $err = '密码不对';
    }
}
if (($_GET['action'] ?? '') === 'logout') {
    session_destroy();
    header('Location: admin.php');
    exit;
}
if (empty($_SESSION['auth'])) {
    ?><!doctype html><meta charset="utf-8"><title>paperSU 授权管理</title>
    <style>body{font-family:system-ui;max-width:360px;margin:80px auto}input,button{font-size:16px;padding:8px;width:100%;box-sizing:border-box;margin-top:8px}</style>
    <h2>paperSU 授权管理</h2><?php if ($err) { echo '<p style="color:#c00">' . htmlspecialchars($err) . '</p>'; } ?>
    <form method="post"><input type="hidden" name="action" value="login"><input type="password" name="pass" placeholder="密码" autofocus><button>登录</button></form>
    <?php
    exit;
}

// 停用 / 启用
if (($_POST['action'] ?? '') === 'toggle') {
    $id = (int)($_POST['id'] ?? 0);
    $pdo = db();
    $pdo->prepare('UPDATE licenses SET disabled = 1 - disabled WHERE id = ?')->execute([$id]);
    header('Location: admin.php');
    exit;
}
// 改可用次数
if (($_POST['action'] ?? '') === 'uses') {
    $id = (int)($_POST['id'] ?? 0);
    $uses = max(1, min(100, (int)($_POST['max_uses'] ?? 1)));
    db()->prepare('UPDATE licenses SET max_uses = ? WHERE id = ?')->execute([$uses, $id]);
    header('Location: admin.php');
    exit;
}

$pdo = db();
$total = (int)$pdo->query('SELECT COUNT(*) FROM licenses')->fetchColumn();
$used = (int)$pdo->query('SELECT COUNT(*) FROM licenses WHERE used_count > 0')->fetchColumn();
$disabled = (int)$pdo->query('SELECT COUNT(*) FROM licenses WHERE disabled = 1')->fetchColumn();
$page = max(1, (int)($_GET['page'] ?? 1));
$per = 50;
$rows = $pdo->prepare('SELECT id,username,tier,expiry,order_id,max_uses,used_count,disabled,created_at,last_used_at
                       FROM licenses ORDER BY id DESC LIMIT ? OFFSET ?');
$rows->execute([$per, ($page - 1) * $per]);
?><!doctype html><meta charset="utf-8"><title>paperSU 授权管理</title>
<style>
body{font-family:system-ui;margin:24px;background:#fafafa}
table{border-collapse:collapse;width:100%;background:#fff}
th,td{border:1px solid #ddd;padding:6px 8px;font-size:13px;text-align:left}
th{background:#f0f0f0}.off{color:#c00}.ok{color:#0a0}
.bar{margin:12px 0;font-size:14px}
form.inline{display:inline}
</style>
<h2>paperSU 授权管理</h2>
<div class="bar">共 <b><?= $total ?></b> 张 ｜ 已用 <b><?= $used ?></b> ｜ 已停用 <b><?= $disabled ?></b>
 ｜ <a href="?action=logout">退出</a> ｜ <a href="admin.php">刷新</a></div>
<table>
<tr><th>#</th><th>用户</th><th>等级</th><th>到期</th><th>订单</th><th>用量</th><th>状态</th><th>签发</th><th>最近使用</th><th>操作</th></tr>
<?php foreach ($rows as $r): ?>
<tr>
  <td><?= (int)$r['id'] ?></td>
  <td><?= htmlspecialchars((string)$r['username']) ?></td>
  <td><?= htmlspecialchars((string)$r['tier']) ?></td>
  <td><?= htmlspecialchars((string)$r['expiry'] ?: '永久') ?></td>
  <td><?= htmlspecialchars((string)$r['order_id']) ?></td>
  <td><?= (int)$r['used_count'] ?> / <?= (int)$r['max_uses'] ?></td>
  <td class="<?= ((int)$r['disabled'] === 1) ? 'off' : 'ok' ?>"><?= ((int)$r['disabled'] === 1) ? '已停用' : '正常' ?></td>
  <td><?= htmlspecialchars((string)$r['created_at']) ?></td>
  <td><?= htmlspecialchars((string)$r['last_used_at']) ?></td>
  <td>
    <form class="inline" method="post"><input type="hidden" name="action" value="toggle"><input type="hidden" name="id" value="<?= (int)$r['id'] ?>"><button>停/启</button></form>
    <form class="inline" method="post"><input type="hidden" name="action" value="uses"><input type="hidden" name="id" value="<?= (int)$r['id'] ?>">
      <input name="max_uses" value="<?= (int)$r['max_uses'] ?>" size="3"><button>改次数</button></form>
  </td>
</tr>
<?php endforeach; ?>
</table>
<div class="bar"><a href="?page=<?= max(1, $page - 1) ?>">上一页</a> ｜ 第 <?= $page ?> 页 ｜ <a href="?page=<?= $page + 1 ?>">下一页</a></div>