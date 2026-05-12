# snake_minil

## 隐藏 blob 与四种变换

在 `.rdata` 中可以定位到一段 `0x480` 字节的隐藏 blob。相关逻辑附近还能看到一个变换字母表：

```text
PALS
```

四种操作分别是：

```text
A: 每个字节 +0x1e
S: 每个字节 -0x66
L: 每个字节循环左移 3 位，也就是 rol(byte, 3)
P: 整个 blob 循环左移 6 字节
```

本题中按键与隐藏变换的映射为：

```text
U -> P
D -> A
L -> L
R -> S
```

注意：程序不是按 `moveDir` 去重，而是按 `lastChangedKey` 去重。`lastChangedKey` 初始为 `None`，所以开局第一次有效按键一定会触发一次隐藏变换。本题里虽然初始方向已经是 `Right`，但第一下 `R` 仍然必须显式输入，对应先执行一次 `S`。之后连续相同按键不会重复触发变换；与当前移动方向相反的按键会直接失效，也不会触发变换。

## 前 10 个食物与最短路线

题目里前 10 个食物位置是固定的：

```text
(17,0)
(12,10)
(3,11)
(15,9)
(13,18)
(4,10)
(12,10)
(14,16)
(3,7)
(19,14)
```

蛇初始位置是 `(10,10)`，初始方向为 `Right`。

采用“水平优先”的最短路：每一段先走横向，再走纵向。这样得到完整按键序列：

```text
RRRRRRRUUUUUUUUUU
LLLLLDDDDDDDDDD
LLLLLLLLLD
RRRRRRRRRRRRUU
LLDDDDDDDDD
LLLLLLLLLUUUUUUUU
RRRRRRRR
RRDDDDDD
LLLLLLLLLLLUUUUUUUUU
RRRRRRRRRRRRRRRRDDDDDDD
```

拼接后总长度为 `143` 个 tick。把连续重复按键压缩后，只保留会触发隐藏变换的有效按键序列，得到：

```text
RULDLDRULDLURDLURD
```

根据按键到隐藏变换的映射，对应的隐藏操作序列是：

```text
SPLALASPLALPSALPSA
```

## 目标 MD5

每次吃到食物后，程序都会计算当前 blob 的 MD5，并与目标值比较。

把初始 blob 按 `SPLALASPLALPSALPSA` 依次变换后，MD5 为：

```text
cac0dfcf4b795ee7436b17721d2411e1
```

等价验证脚本：

```python
import hashlib
from pathlib import Path

blob = Path("encrypted_blob.bin").read_bytes()

for op in "SPLALASPLALPSALPSA":
    if op == "A":
        blob = bytes((x + 0x1e) & 0xff for x in blob)
    elif op == "S":
        blob = bytes((x - 0x66) & 0xff for x in blob)
    elif op == "L":
        blob = bytes(((x << 3) & 0xff) | (x >> 5) for x in blob)
    elif op == "P":
        blob = bytes(blob[(i + 6) % len(blob)] for i in range(len(blob)))

print(hashlib.md5(blob).hexdigest())
```

输出应为：

```text
cac0dfcf4b795ee7436b17721d2411e1
```

路线正确后，会进入隐藏 finalizer，用当前 blob 继续处理最后一段 16 字节密文。

补充一点：调试态下 binary 还会接受一个 decoy MD5：

```text
f7e0250119112e4c140ee5a21fa40025
```

它对应真实路线前缀 `SPLA`，也就是在第二个食物附近就会触发的假成功分支。该分支只会显示误导性结果：

```text
The forest accepts your route.
Flag: miniL{route_only}
```

如果是正常无调试运行，这条假链路不会参与最终判定。

##  派生校验 key

MD5 命中后，程序不会使用一个明文写死的 TEA key，而是从当前 blob 中派生 key。

等价逻辑如下：

```python
import struct

MASK = 0xffffffff

def rol32(v, n):
    return ((v << n) & MASK) | (v >> (32 - n))

def u32le(buf, off):
    return struct.unpack("<I", buf[off:off+4])[0]

key = [0x243f6a88, 0x85a308d3, 0x13198a2e, 0x03707344]

for i, byte in enumerate(blob):
    lane = i & 3
    mix = (byte + 0x9e3779b9 + ((key[(lane + 1) & 3] << 6) & MASK) + (key[(lane + 3) & 3] >> 2)) & MASK
    key[lane] = rol32((key[lane] ^ mix) & MASK, ((i + lane) & 7) + 5)

for i in range(4):
    key[i] ^= u32le(blob, 0x40 + i * 4)

print([hex(x) for x in key])
```

正确 blob 派生出的 key 为：

```text
032a05e4 866a4de5 a815d7ef 1a8c3ff3
```

##  隐藏 Finalizer 与 Flag

最终 16 字节密文仍然是：

```text
a4a0bcf4021dbd97e715f4cb73902a68
```

当前版本不是拿用户输入去正向校验它，而是命中 MD5 后对这 16 字节做一次逆运算，直接还原最终 flag。

逆向等价脚本如下：

```python
import struct

MASK = 0xffffffff
DELTA = 0x9e3779b9
KEY = [0x032a05e4, 0x866a4de5, 0xa815d7ef, 0x1a8c3ff3]
TARGET = bytes.fromhex("a4a0bcf4021dbd97e715f4cb73902a68")

def mix(x, s, k):
    return (((((x << 4) & MASK) ^ (x >> 5)) + x) & MASK) ^ ((s + k) & MASK)

f0, f1, f2, f3 = struct.unpack("<4I", TARGET)

v2 = f1 ^ f2
v0 = f0 ^ v2
v3 = f3 ^ f0
v1 = f1 ^ v3

s = (DELTA * 64) & MASK
for _ in range(32):
    v3 = (v3 - mix(v2, s, KEY[(s >> 11) & 3])) & MASK
    s = (s - DELTA) & MASK
    v2 = (v2 - mix(v3, s, KEY[s & 3])) & MASK

s = (DELTA * 32) & MASK
for _ in range(32):
    v1 = (v1 - mix(v0, s, KEY[(s >> 11) & 3])) & MASK
    s = (s - DELTA) & MASK
    v0 = (v0 - mix(v1, s, KEY[s & 3])) & MASK

print(struct.pack("<4I", v0, v1, v2, v3))
```

输出为：

```text
b'miniL{r0ut3_Snk}'
```

