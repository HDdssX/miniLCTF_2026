#!/bin/bash

# 从环境变量获取 FLAG，如果没有设置则使用默认值
FLAG=${FLAG:-"flag{default_flag_here}"}

# 使用 sed 替换根目录 /flag 中的占位符
sed -i "s|FLAG_PLACEHOLDER|${FLAG}|g" /flag

unset FLAG

# 执行 CMD 中的命令启动 Web 服务
exec "$@"