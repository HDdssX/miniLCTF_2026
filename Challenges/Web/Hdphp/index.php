<?php
if (isset($_GET['f']) && !preg_match('/flag|file|php|data|zip|phar|(proc|dev|bin|usr|var).{15,}/i', $_GET['f'])) {
    usleep(200000);
    include $_GET['f'];
} else {
    highlight_file(__FILE__);
}
