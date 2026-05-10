# Recovery Pod

省流：

1. 连接题目后只能用 `help`、`cat`、`gitcat`、`quit`
2. 核心不是命令执行，而是利用有限读文件能力把 Git 元数据一点点抠出来
3. 正常提交历史里能找到一个恢复脚本 `scripts/recover_snapshot.py`
4. 真正的密文不在工作区，而是被塞进了 stash 的 untracked files 里
5. 对应的 key 也不在普通提交里，而是被写进了 `git notes`
6. 把密文、key 和恢复脚本逻辑拼起来，就能解出 flag

## 1. 连接题目

题目给的是一个 `nc` 服务，所以先直接连：

```bash
nc 127.0.0.1 1337
```

连上以后欢迎信息会告诉我们只开放了这几个命令：

- `help`
- `cat <path>`
- `gitcat <sha1>`
- `quit`

一开始看起来会有点别扭，因为：

- 不能 `ls`
- 不能 `find`
- 不能直接跑 `git log`
- 甚至连正常 shell 都没有

但换个角度想，其实做题方向已经很明显了：

1. `cat` 用来直接读 `.git` 下面的引用和日志文件
2. `gitcat` 用来读 Git object

所以这题本质上不是“逃逸受限终端”，而是“在极少指令下手工还原 Git 仓库信息”。

## 2. `.git` 目录的组织

如果我们平时只把 Git 当成黑盒工具，这题做起来会比较别扭。  
但实际上 `.git` 的结构很规整，核心先记住这几块就够了：

### `HEAD`

`HEAD` 一般长这样：

```text
ref: refs/heads/main
```

意思是：当前工作区挂在 `main` 这个分支上。

### `refs/`

`refs/heads/main` 里存的是当前分支头指向的 commit SHA-1，比如：

```text
94fa9139f6fbfeb19b47ef746b90b181b06902e1
```

也就是说，`main` 当前指向的就是这个 commit。

### `logs/`

`logs/HEAD` 和 `logs/refs/heads/main` 里存的是 reflog。  
它不是“提交内容”，而是“引用怎么变过”。每一行一般形如：

```text
<old_sha1> <new_sha1> <who> <timestamp> <tz>\t<message>
```

这对这题特别有用，因为哪怕我们没有 `git log`，也能先从 reflog 拿到一串值得看的 commit SHA-1。

### `objects/`

Git 最核心的数据都在这里。对象文件路径是：

```text
.git/objects/前2位/后38位
```

例如 SHA-1 是：

```text
94fa9139f6fbfeb19b47ef746b90b181b06902e1
```

那它对应的对象文件路径就是：

```text
.git/objects/94/fa9139f6fbfeb19b47ef746b90b181b06902e1
```

这些 object 文件本身不是明文，而是：

1. 先用 zlib deflate 压缩
2. 解压后得到 `类型 + 空格 + 长度 + \\0 + 正文`

例如 commit 对象解开后一般长这样：

```text
commit 219\0tree <sha1>
parent <sha1>
author ...
committer ...

commit message
```

而 tree 对象稍微特殊，它正文里不是纯文本列表，而是二进制结构，这个后面专门讲。

## 3. 从 HEAD、分支和 reflog 开始

既然没有 `git log`，那就手工读最基本的 Git 元数据。先看：

```text
debug> cat .git/HEAD
ref: refs/heads/main

debug> cat .git/refs/heads/main
94fa9139f6fbfeb19b47ef746b90b181b06902e1

debug> cat .git/logs/HEAD
0000000000000000000000000000000000000000 b88b63cf974002b8fa4b5a8b61aebe456d045f91 ops <ops@example.com> 1777302469 +0800        commit (initial): init ops playbooks repo
b88b63cf974002b8fa4b5a8b61aebe456d045f91 2a0c1b1f3e6d327d65376f61b1e93bb20c712076 ops <ops@example.com> 1777302481 +0800        commit: add deploy checklist and service inventory
2a0c1b1f3e6d327d65376f61b1e93bb20c712076 225b455a216fe9e7c0777892dcd93230141fc2f3 ops <ops@example.com> 1777302489 +0800        commit: document rollback procedure
225b455a216fe9e7c0777892dcd93230141fc2f3 e80492c401334db1d1a03db6350f3541f6d5cfa0 ops <ops@example.com> 1777302499 +0800        commit: add basic service probe script
e80492c401334db1d1a03db6350f3541f6d5cfa0 98815b2e21cfce7740cbd9801d05a7943eeebcb1 ops <ops@example.com> 1777302509 +0800        commit: tighten deploy and recovery notes
98815b2e21cfce7740cbd9801d05a7943eeebcb1 9563e3180e2991faec7d904497594aeb60794b35 ops <ops@example.com> 1777302518 +0800        commit: normalize recovery checks across runbooks
9563e3180e2991faec7d904497594aeb60794b35 94fa9139f6fbfeb19b47ef746b90b181b06902e1 ops <ops@example.com> 1777302533 +0800        commit: add snapshot recovery helper
```

为什么先读这三个？

- `.git/HEAD` 告诉我们当前在哪个引用上
- `.git/refs/heads/main` 告诉我们当前分支头的提交 SHA-1
- `.git/logs/HEAD` 则会把历史变动链也暴露出来

看完 reflog 以后，我们手里有一串提交 SHA-1 了。接下来就该轮到 `gitcat` 出场。

## 3. 理解 `gitcat`

这里的 `gitcat` 实际上只是把对应的 object 文件原样读出来，然后做了一层 base64。

所以我们拿到手的东西还不是可读文本，而是：
1. base64 编码过
2. 里面包着 zlib 压缩数据

也就是说，本地要自己做两步：
1. `base64.b64decode(...)`
2. `zlib.decompress(...)`

这一步做完后，才是真正的 Git object 内容。

我们写个脚本来自动化解析 `gitcat` 的输出：

```python
#!/usr/bin/env python3
import base64, sys, zlib

while True:
    try:
        line = input()
    except EOFError:
        break
    line = line.strip()
    if not line:
        continue

    try:
        raw = base64.b64decode(line)
    except Exception as e:
        print(f"base64 error: {e}", file=sys.stderr)
        continue

    obj = zlib.decompress(raw)
    header, body = obj.split(b"\x00", 1)
    otype, size = header.decode().split(" ", 1)

    print(f"type = {otype}")
    print(f"size = {size}")

    if otype in {"commit", "blob", "tag"}:
        print(body.decode("utf-8", "replace"))
    elif otype == "tree":
        pos = 0
        while pos < len(body):
            sp = body.find(b" ", pos)
            nul = body.find(b"\x00", sp)
            mode = body[pos:sp].decode()
            name = body[sp+1:nul].decode()
            sha1 = body[nul+1:nul+21].hex()
            print(mode, sha1, name)
            pos = nul + 21
    else:
        sys.stdout.buffer.write(body)
        sys.stdout.buffer.flush()
    print("---")
```

这个脚本做了三件事：

1. 解 base64
2. 解 zlib
3. 根据对象类型决定如何展示

其中 tree 的解析尤其重要，因为它不是简单文本，而是这样的：

```text
<mode><space><name><NUL><20-byte raw sha1>
```

注意最后那段不是 40 个十六进制字符，而是 **20 字节二进制 SHA-1**。 

要解码，我们需要：
1. 先找到空格，拿到 `mode`
2. 再找到 `\x00`，拿到文件名 `name`
3. `\x00` 后面的 20 字节直接 `.hex()`，才能变成平时熟悉的 40 位 SHA-1

比如 tree 里一条记录的逻辑含义可能是：

```text
100644 README.md <blob_sha1>
040000 scripts <tree_sha1>
```

其中：

- `100644` 表示普通文件
- `40000` 表示子目录（其实对应另一个 tree）

所以 tree 本质上就是“目录项列表”，只是用了二进制编码。

## 4. 拿到恢复脚本

有了 reflog 里的 SHA-1 之后，就可以从最新提交开始，一个个展开：

1. 读 commit 对象
2. 找到它对应的 tree
3. 继续展开 tree
4. 找 blob 看文件内容

```text
debug> gitcat 94fa9139f6fbfeb19b47ef746b90b181b06902e1
eAGNjkkKAjEQAD3nFX0XpLMnIOJXepIOI0xMyETR3ztP8FKHgoJKrdbHBCXjaQ5msCEHMk4mRCpBEaJzkjRLrUou2ZP0nIiC6DT4OSFap1nLgKxilIU4+RzRmOhtNMSLQx/Noq2g11zbgNZ3uB6484dq3/iSWr2B9N5rVFZrOGNAFIc9tib/HQjKGfYn9X1tEwan9ubxhZW3zkP8AH96RTI=

debug> gitcat 58d8a461c00af82a00661a3e132fdfd7a17ecaa8
eAErKUpNVTA0tmQwNDAwMzFRCHJ1dPF11ctNYfjYfq1IrC1YymjDJN+wtw/E+3lF95kYAIFCcn5eWmY6Q6XvtsaTNzI4djBkO/6tOcUee1BlI0RBUWleUn5+djHD3Y59ZsvNOX7kffkgU8UpH7UjNDEFoqQ4uSizoKSYob9n6RE5McNLiyyXt7PPYFrz063xEwBkATiv

debug> gitcat f187d6721686531a32b0924d56ede0178f0d15be
eAE9kM1OAzEMhDnnKSz13OWAhMQTAOKC4AHa/LjdKCGO7GxXfXumW8QlsuLMfDMJVQI9vTw/7Ei67Xv11yBSzLn3Nlibr6RL267oJEoqy8iNKXGvciXfEq5qDT4WGt6KTc7tdvQdpbNze/I941xFCysGizOnpd7n4cdipAzmJnqF+c0Pe/D39MHcqatESJSNbBYdxE2W80xDkKdWWemk8kMePjY0x8GJuqQJ+k/lEyMywyMtMYfKBH4sRnLBAs8D+pUma+V05pvmi6NootnbDGJgdOabQ/UxtzMwQaVwoyhteHyE3vu+ce1wtKi5jy388W9+3IgHY73kyDbZfATmf6vgIcvBmu/oN6Z+PbpfAduNLw==
```

把输出复制到本地，喂给刚才的脚本，看到：

```text
eAGNjkkKAjEQAD3nFX0XpLMnIOJXepIOI0xMyETR3ztP8FKHgoJKrdbHBCXjaQ5msCEHMk4mRCpBEaJzkjRLrUou2ZP0nIiC6DT4OSFap1nLgKxilIU4+RzRmOhtNMSLQx/Noq2g11zbgNZ3uB6484dq3/iSWr2B9N5rVFZrOGNAFIc9tib/HQjKGfYn9X1tEwan9ubxhZW3zkP8AH96RTI=
type = commit
size = 219
tree 58d8a461c00af82a00661a3e132fdfd7a17ecaa8
parent 9563e3180e2991faec7d904497594aeb60794b35
author ops <ops@example.com> 1777302533 +0800
committer ops <ops@example.com> 1777302533 +0800

add snapshot recovery helper

---
eAErKUpNVTA0tmQwNDAwMzFRCHJ1dPF11ctNYfjYfq1IrC1YymjDJN+wtw/E+3lF95kYAIFCcn5eWmY6Q6XvtsaTNzI4djBkO/6tOcUee1BlI0RBUWleUn5+djHD3Y59ZsvNOX7kffkgU8UpH7UjNDEFoqQ4uSizoKSYob9n6RE5McNLiyyXt7PPYFrz063xEwBkATiv
type = tree
size = 139
100644 f187d6721686531a32b0924d56ede0178f0d15be README.md
40000 794db681c9d86808b8006b41fd7cca075dc124b1 config
40000 dd88be36a73708f86ef4f01c7a091f5ab8556164 runbooks
40000 8f8ca5c41e1631d2a239a787079802acf94681f2 scripts
---
eAE9kM1OAzEMhDnnKSz13OWAhMQTAOKC4AHa/LjdKCGO7GxXfXumW8QlsuLMfDMJVQI9vTw/7Ei67Xv11yBSzLn3Nlibr6RL267oJEoqy8iNKXGvciXfEq5qDT4WGt6KTc7tdvQdpbNze/I941xFCysGizOnpd7n4cdipAzmJnqF+c0Pe/D39MHcqatESJSNbBYdxE2W80xDkKdWWemk8kMePjY0x8GJuqQJ+k/lEyMywyMtMYfKBH4sRnLBAs8D+pUma+V05pvmi6NootnbDGJgdOabQ/UxtzMwQaVwoyhteHyE3vu+ce1wtKi5jy388W9+3IgHY73kyDbZfATmf6vgIcvBmu/oN6Z+PbpfAduNLw==
type = blob
size = 396
# ops-playbooks

Internal runbooks for routine deploy and rollback tasks.

## Scope

- api
- worker
- scheduler
- status relay

## Ground rules

- Keep procedures short enough to follow from a restricted pod.
- Prefer reproducible checks over tribal knowledge.
- Record hashes before replacing a broken container.

## Helper scripts

- `scripts/check_services.sh`
- `scripts/recover_snapshot.py`
```

然后我们直接 `cat scripts/recover_snapshot.py`，拿到这个脚本：

```python
#!/usr/bin/env python3
from __future__ import annotations

import base64
import re
import sys
from pathlib import Path


def load_ciphertext(arg: str) -> bytes:
    path = Path(arg)
    if path.exists():
        text = path.read_text(encoding="utf-8", errors="replace")
    else:
        text = arg

    for line in text.splitlines():
        if line.startswith("cipher_b64="):
            text = line.split("=", 1)[1].strip()
            break

    text = re.sub(r"\s+", "", text)
    return base64.b64decode(text)


def xor_bytes(data: bytes, key: bytes) -> bytes:
    return bytes(data[i] ^ key[i % len(key)] for i in range(len(data)))


def main() -> int:
    if len(sys.argv) != 3:
        print(f"usage: {Path(sys.argv[0]).name} <key> <cipher-b64-or-file>", file=sys.stderr)
        return 1

    key = sys.argv[1].encode()
    if not key:
        print("key must not be empty", file=sys.stderr)
        return 1

    cipher = load_ciphertext(sys.argv[2])
    plain = xor_bytes(cipher, key)
    sys.stdout.write(plain.decode("utf-8", "replace"))
    if not plain.endswith(b"\n"):
        sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

审计下这段代码，会发现逻辑并不复杂：

1. 输入一个 key
2. 再输入一段 base64 密文
3. 把密文解码后做循环异或
4. 输出还原结果

所以到这里，题目的问题已经被拆成两个很明确的小问题：

1. 密文在哪
2. key 在哪

## 5. 寻找密文

很多人做到这里会本能地继续在普通提交历史里翻，但这题真正的藏东西位置并不在这里。

题目名叫 `Recovery Pod`，而且提示也一直在强调“运维恢复痕迹”，所以很自然就该去看 stash。先读：

```text
cat .git/refs/stash
```

如果这里有值，就说明仓库里确实存了 stash。  
接下来和前面一样，把 stash 对应的 commit 用 `gitcat` 展开。

```text
type = commit
size = 337
tree 58d8a461c00af82a00661a3e132fdfd7a17ecaa8
parent 94fa9139f6fbfeb19b47ef746b90b181b06902e1
parent 5232f6542354b0c4ebc91b14edd8fa17b7403689
parent 066cda2343823666f3c654811d7a7738ba6905ad
author runtime <runtime@example.com> 1778412498 +0000
committer runtime <runtime@example.com> 1778412498 +0000

On main: runtime encrypted snapshot
```

这里要注意一个 Git 知识：
stash 往往不是单一父提交，它可能带多个 parent，其中一个 parent 专门保存 untracked files。

```text
type = commit
size = 220
tree cf44dbd951b57faba70670be87d572dc8a71ca68
author runtime <runtime@example.com> 1778412498 +0000
committer runtime <runtime@example.com> 1778412498 +0000

untracked files on main: 94fa913 add snapshot recovery helper
---

type = tree
size = 40
40000 d12a5000aca8b912cb9f78c1667ea93453ee727a runtime_cache
---

type = tree
size = 38
100644 e20a32526011d11a9f4f98baab3a0ced59f56e23 bundle.txt
---

type = blob
size = 100
snapshot_kind=runtime-cache
cipher_b64=VVEPWnlKVVYPWgICV1EVVVRaVBpaCVcGHVYFAwEZUgIJClJSUVJUAV0HHg==
```

找到一个关键文件：`runtime_cache/bundle.txt`，到这里，密文就到手了。

## 6. 寻找密钥

如果只找到 `bundle.txt`，还不够，因为恢复脚本还需要 key。  
而这题第二个隐藏点就在 `git notes`。

继续读：

```text
cat .git/refs/notes/commits
```

如果这个引用存在，就说明仓库里还维护了 notes。  
接下来同样把这个引用对应的 commit、tree、blob 一层层展开。

Git notes 的结构特点是：  
它通常会把“某个提交对应的备注”挂在一个以提交 SHA-1 命名的对象上。  
所以展开以后，我们会看到一个非常像“给当前提交做批注”的 blob。

```text
type = commit
size = 189
tree ed868afc20878004acbc25aba44ef2188303df81
author runtime <runtime@example.com> 1778412498 +0000
committer runtime <runtime@example.com> 1778412498 +0000

Notes added by 'git notes add'

---
type = tree
size = 68
100644 e34d3e30552d0fb343ff8d39c4a20179a82c06bb 94fa9139f6fbfeb19b47ef746b90b181b06902e1

---
type = blob
size = 53
cache snapshot key: 88a3516b8cc5df87d9d791d70e5b04ed
```

这就是恢复快照所需的 key。

## 7. 解密

到这里，已经凑齐整条链了：

1. `scripts/recover_snapshot.py` 给出了恢复脚本
2. `runtime_cache/bundle.txt` 给出了 `cipher_b64`
3. notes 给出了 key

```bash
python recover_snapshot.py 88a3516b8cc5df87d9d791d70e5b04ed VVEPWnlKVVYPWgICV1EVVVRaVBpaCVcGHVYFAwEZUgIJClJSUVJUAV0HHg==

miniL{c479a737-b0c0-c831-30a1-7f123adcbced}
```
