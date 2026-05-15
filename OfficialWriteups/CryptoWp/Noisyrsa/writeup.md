# Noisyrsa 题解

这题第一眼看起来像 RSA，但真正给出来的 $c$ 不是 RSA 密文，而是一组被随机数、外层素数和分块信息搅过的样本。

题目里实际有两层模数：

```python
n_ = q * r
n = p * n_
```

flag 是在内层 RSA 模数 $n\_$ 下加密的：

```python
c = pow(flag, e, n_)
```

而输出的 `cipher[i]` 是：

```python
s * (low_i + high_i * M) * (1 + noise_i * p) % n
```

所以第一步是先把外层的 $p$ 和这些密文碎片还原出来。


## 用 LLL 恢复小向量 $z_i$

先构造一个模 $n$ 的关系格，大致形式是：

```python
for i, value in enumerate(samples):
    lattice[i][i] = 1
    lattice[i][-1] = value * 2^512
lattice[-1][-1] = n * 2^512
```

LLL 之后能得到若干短的模关系（和 $z_i$ 正交的向量也会和 $C_i$ 正交，故和 $z_i$ 正交的向量空间也是和 $C_i$ 正交的向量空间）。由于

$$
C_i \equiv s z_i \pmod p
$$

这些模关系在 $p$ 上也会约束 $z_i$。再对这些关系做一次“正交格”的 LLL，就可以恢复出一组很短的向量（在这些正交空间里找一个最短向量，基本上就是 $z_i$ ，这样就逆变出 $z_i$ 了）：

```python
z = [10401469912284249, 15486884245919427, ...]
```

这组数正好就是

$$
z_i = a_i + M b_i
$$

恢复出 $z_i$ 后，外层素数 $p$ 也很好拿。因为：

$$
z_0 C_1 - z_1 C_0 \equiv
z_0 s z_1 - z_1 s z_0
\equiv 0 \pmod p
$$

于是：

```python
p = gcd(z[0] * samples[1] - z[1] * samples[0], n)
```

这样就能分掉外层：

```python
n_inner = n // p
```


## 从 $z_i$ 到 $M$ 和密文块

现在已经知道：

$$
z_i = a_i + M b_i
$$

其中 $a_i$ 很小，$M$ 也不大。对这组 $z_i$ 再做一次类似的 LLL，可以恢复出 $-a_i$ 这组短向量（这里自行体悟）。之后：

```python
M = gcd(z[0] - a[0], z[1] - a[1], ...)
b_i = (z_i - a_i) // M
```


然后就能拿到所有低位块 $a_i$ 和高位块 $b_i$。

需要注意的是，20 个低位块一共给了：

$$
20 \times 24 = 480
$$

bit。

20 个高位块一共给了：

$$
20 \times 27 = 540
$$

bit。

所以我们能恢复 $c$ 的低 `1020` bit。RSA 模数是 1024 bit，因此最高还差最多 4 bit，最后枚举 `0..15` 就行。

重组密文的逻辑是：

```python
c_low_1020 = 0
for i in reversed(range(20)):
    c_low_1020 = (c_low_1020 << 27) + b[i]
for i in reversed(range(20)):
    c_low_1020 = (c_low_1020 << 24) + a[i]
```

## 恢复 RSA 的 $phi$

RSA 这里还有一个弱点：

```python
d = getPrime(250 bits)
b = getPrime(244 bits)
e = b * inverse(d, phi) % phi
```

也就是：

$$
ed \equiv b \pmod{\varphi(n')}
$$

写成整数形式：

$$
ed - b = k\varphi(n')
$$

因为 $d$ 和 $b$ 都明显偏小，可以用二维格恢复 $d$ （连分数也行）。构造：

```python
LLL([[2^512, e], [0, n_inner]])
```

短向量会给出类似：

$$
(d\cdot 2^{512},\; ed-k n')
$$

而

$$
ed-k n' = b-k(n'-\varphi(n'))
$$

第二项的绝对值约等于 $k * (n' - phi)$。有了 $d$ 后可以估出$phi$。

## 枚举最高 4 bit 并解密

有了 $phi$ 就能算私钥：

```python
d = inverse(e, phi)
```

前面重组出来的是 $c$ 的低 1020 bit，所以补上最高 4 

转成 bytes 后检查 `miniL{` 即可。
