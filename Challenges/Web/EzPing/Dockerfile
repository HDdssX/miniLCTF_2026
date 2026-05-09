# 使用官方 Python 镜像（轻量级版本）
FROM python:3.9-slim

# 设置工作目录
WORKDIR /app

RUN apt-get update && apt-get install -y iputils-ping && rm -rf /var/lib/apt/lists/*

# 复制依赖文件并安装老版本框架（构造漏洞环境）
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 提前在根目录创建一个包含占位符的 flag 文件
RUN echo "FLAG_PLACEHOLDER" > /flag

# 复制所有代码到容器
COPY . .

# 复制 entrypoint 脚本并设置权限
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# 暴露 Flask 默认端口
EXPOSE 5000

# 设置 entrypoint 和默认命令
ENTRYPOINT ["/entrypoint.sh"]
CMD ["python", "app.py"]