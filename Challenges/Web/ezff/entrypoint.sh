#!/bin/sh
#从环境变量获取FLAG,如果没有设置则使用默认值
FLAG=${FLAG:-"flag{default_flag_here}"}
#使用sed替换根目录/f1ag中的占位符
echo $FLAG > /flag
unset FLAG
#执行CMD中的命令启动Web服务
exec java -jar /app/ezff.jar