# Hdphp

## 题目原文

```php
<?php
if (isset($_GET['f']) && !preg_match('/flag|file|php|data|zip|phar|(proc|dev|bin|usr|var).{15,}/i', $_GET['f'])) {
    usleep(200000);
    include $_GET['f'];
} else {
    highlight_file(__FILE__);
}
```

## 解题思路

本题禁用了所有 PHP 协议，并设置了 `blacklist` 规则，漏洞点是 `LFI`。

预期解如下：

1. 如果 Nginx 请求体大小超过 `client_body_buffer_size`（默认为 `8k`），就会留下临时文件，例如 `/var/lib/nginx/temp/client_body/0000000001`。但该目录长度过长，也在过滤范围内，因此需要考虑爆破 `fd` 文件。
2. 正则只允许 `/proc` 后面跟不超过 `14` 个字符。对于 `/proc/$pid/fd/$fd` 来说，这个长度绰绰有余，但该文件会被标记为 `deleted`。PHP 会先执行 `realpath` 等一系列操作，最终导致读取失败。对于 `/proc/$pid/fd/a/../$fd`（刚好 `14` 个字符，或者使用 `%0a` 截断正则），由于路径规范化问题，PHP 会执行 `open` 并读取返回的文件。
3. 在大请求体开头写入 `<?php ?>`，并在后续被 `include` 包含后，即可造成 `RCE`。

设置 `usleep` 使简单的条件竞争失效，因此必须通过更大的请求体延长临时文件的存留时间。
