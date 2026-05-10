# License Recovery

- 类型：Misc
- 分发物：`dist/minil2026-license-recovery-image.tar`

## 题面

某内部工具的导出镜像被保留了下来。镜像里还留着一个校验程序，但真正的重要内容并不在表面。请从给定文件中找回 flag。

## 目录说明

- `dist/`：当前可直接分发的镜像附件
- `build/`：按需生成的中间产物目录，仓库默认只保留占位
- `scripts/build_image.sh`：从源码构建并导出镜像包
- `tools/`：生成 `check` 与 `recover_flag.py` 的构建脚本
- `vendor/`：PNG 生成依赖

## 版权说明

本体灵感来源：https://github.com/thebabush/llvm-jutsu，按照 MIT 协议使用了其部分代码，在相应代码片段头附带了原作者版权声明。

## 构建方式

宿主机依赖：

- `bash`
- `python3`
- `g++`
- `docker`

构建单个附件：

```bash
bash scripts/build_image.sh \
  minil2026-license-recovery \
  6F4FEAF5 \
  'miniL{535a28d2-bcfa-4497-8519-f80443a056b4}'
```

`scripts/build_image.sh` 会在运行时自动生成 `build/` 下所需文件。
