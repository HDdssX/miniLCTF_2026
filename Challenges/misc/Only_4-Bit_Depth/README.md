# Only 4-Bit Depth?

- 类型：Misc
- 本地端口：`8000`
- 连接方式：访问 `http://127.0.0.1:8000`

## 题面

这里有一张 BMP，以及一个“文本转 BMP”的 blackbox。

当颜色分量不再连续，而是只取少量固定档位时，像素本身也能成为一种编码。

## 启动方式

```bash
docker compose up --build
```

启动后访问：

```text
http://127.0.0.1:8000
```

## 目录说明

- `app.py`：Flask 服务，提供页面和 `POST /api/render`
- `codec.py`：文本到 BMP 的编码逻辑
- `build/`：启动脚本与 Flag 初始化逻辑

## 部署说明

- `docker-compose.yml` 默认映射 `8000` 并注入本地测试 Flag。
- `build/entrypoint.sh` 会先生成题目 BMP，再清空环境变量中的 Flag。
