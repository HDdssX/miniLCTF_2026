#!/bin/sh

# 动态接收平台注入的 FLAG
FLAG=${FLAG:-"flag{default_flag_here}"}

# 写入受保护的文件
echo "$FLAG" > /flag
chown root:root /flag
chmod 400 /flag

# 销毁环境变量防止泄露
unset FLAG
export FLAG=cleared

# 降权启动 Node.js
exec su ctf -c "node /app/app.js"