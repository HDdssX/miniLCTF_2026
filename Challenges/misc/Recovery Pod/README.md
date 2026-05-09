# Recovery Pod

- 类型：Misc
- 本地端口：`1337/tcp`
- 连接方式：`nc 127.0.0.1 1337`

## 题面

一个用于运维恢复的调试 Pod 被错误暴露在外网。

终端功能受限，但挂载的 Git 仓库还保留着开发和运维痕迹。

请使用 NetCat 连接并恢复隐藏信息。

## 启动方式

```bash
mv ./site/.git_rename ./site/.git
# 恢复 Git 仓库，这是为了使用 Git 管理 .git 文件夹的无奈之举

docker compose up --build
nc 127.0.0.1 1337
```

如果误用浏览器访问该端口，服务会返回一个固定跳转，只用于提示选手改用 `nc`。

## 目录说明

- `app.py`：受限调试终端，仅开放 `help`、`cat`、`gitcat`、`quit`
- `build_assets/entrypoint.sh`：运行时写入 Git notes，并把加密后的 Flag 放进 stash
- `site/`：挂载到容器中的仓库内容
- `site/.git_rename/`：题目核心 .git 数据文件夹，为了被 Git 管理而重命名，启动时需恢复为 `.git`
