# ecclcg 题解

这题是一个带噪声的椭圆曲线 LCG。公开数据里给出了模数 $p$，以及生成元 $G$ 和连续状态点 $P_i$ 的近似坐标。实际上每个坐标都被减去了一个很小的误差：

$$
\tilde x = x - e,\qquad |e|\le \Delta
$$

这里 $p$ 是 1024-bit，误差只有 192-bit。核心思路是利用相邻三点在椭圆曲线加法中的共线关系，把所有小误差整理成一组模 $p$ 的近似线性同余，然后用格和 Babai/CVP 恢复这些误差。

参考文献：https://link.springer.com/article/10.1007/s12095-021-00535-6

## 题目结构

题目随机生成一条曲线：

$$
E: y^2=x^3+ax+b\pmod p
$$

然后随机选取一个隐藏增量点 $G$ 和初始状态 $P_0$，生成：

$$
P_i=P_{i-1}+G
$$

公开文件里有：

- $p$
- 点数 $k=21$
- 误差界 $\Delta=2^{192}$
- 近似的 $G$
- 近似的状态点 $P_0,\ldots,P_{20}$
- AES-CTR 的 $iv,ct$
- 派生密钥用的 $key\_tag$

需要恢复的是曲线参数 $a,b$、真实的 $G$ 和 $P_0$。题目最后用这些值派生 AES key：

```python
material = a || b || Gx || Gy || P0x || P0y
key = SHA256(material + b'|' + key_tag)[:16]
```

所以只要把真实曲线和初始状态恢复出来，就能解密 flag。

## 误差记号

设公开的近似增量点为：

$$
\tilde G=(\gamma_x,\gamma_y)
$$

真实增量点写成：

$$
G=(\gamma_x+h_x,\gamma_y+h_y)
$$

设公开状态点为：

$$
\tilde P_i=(\alpha_i,\beta_i)
$$

真实状态点写成：

$$
P_i=(\alpha_i+e_i,\beta_i+f_i)
$$

其中所有误差都满足：

$$
|h_x|,|h_y|,|e_i|,|f_i|\le \Delta
$$

直接恢复所有误差看起来变量很多，但相邻状态满足：

$$
P_i=P_{i-1}+G
$$

这会给出足够多的模 $p$ 约束。

## 利用三点共线关系

在椭圆曲线上，若：

$$
P_i=P_{i-1}+G
$$

那么 $P_{i-1}$、$G$、$-P_i$ 三点共线。因此有斜率相等：

$$
\frac{y_{i-1}-G_y}{x_{i-1}-G_x}
=
\frac{y_{i-1}+y_i}{x_{i-1}-x_i}
$$

交叉相乘：

$$
(y_{i-1}+y_i)(x_{i-1}-G_x)
-
(y_{i-1}-G_y)(x_{i-1}-x_i)
\equiv 0\pmod p
$$

把公开值和误差代进去。为了让式子更短，定义：

$$
b_i=\beta_{i-1}+\beta_i
$$

$$
p_i=\gamma_x-\alpha_{i-1}
$$

$$
a_i=\alpha_{i-1}-\alpha_i
$$

$$
q_i=\beta_{i-1}-\gamma_y
$$

再定义几个小变量：

$$
M=h_x-e_0
$$

$$
E_i=e_{i-1}-e_i
$$

$$
F_i=f_{i-1}+f_i
$$

$$
N_i=f_{i-1}-h_y
$$

对第 $i$ 条转移来说：

$$
h_x-e_{i-1}=M+E_1+\cdots+E_{i-1}
$$

记：

$$
m_i=M+E_1+\cdots+E_{i-1}
$$

代入共线关系并展开，可以得到：

$$
b_i m_i+q_iE_i+p_iF_i+a_iN_i+m_iF_i+E_iN_i
\equiv
-(b_ip_i+a_iq_i)
\pmod p
$$

这里除了最后两项乘积，其余部分都是线性的。乘积项虽然不是线性的，但它本身也很小，因为参与乘法的误差都是 192-bit。

于是引入新变量：

$$
\Sigma_i=m_iF_i+E_iN_i
$$

就得到一组模 $p$ 的线性同余：

$$
b_i m_i+q_iE_i+p_iF_i+a_iN_i+\Sigma_i
\equiv
c_i
\pmod p
$$

其中：

$$
c_i=-(b_ip_i+a_iq_i)
$$

这是本题的关键降维：椭圆曲线加法里的有理式关系，被转成了关于小误差组合的线性同余。

## 构造格并做 CVP

未知量可以排成：

$$
(M,E_1,\ldots,E_r,F_1,\ldots,F_r,N_1,\ldots,N_r,\Sigma_1,\ldots,\Sigma_r)
$$

其中 $r=k-1=20$。为了让量级比较平衡，脚本中把前四类小变量乘上 $\Delta$，使用：

$$
(\Delta M,\Delta E_i,\Delta F_i,\Delta N_i,\Sigma_i)
$$

每一条转移给出一条模 $p$ 同余。除此之外，还加上：

$$
\Delta M,\Delta E_i,\Delta F_i,\Delta N_i\equiv 0\pmod \Delta
$$

确保恢复出来的缩放坐标能整除 $\Delta$。

实现里先把这些同余写成矩阵关系，求整数核得到满足同余的格：

```python
big = matrix(ZZ, q, n + q)
for i, row in enumerate(rows):
    for j, v in enumerate(row):
        big[i, j] = v
    big[i, n + i] = -mods[i]
ker = big.right_kernel(ZZ).basis_matrix()
```

随后对投影出来的格基做 HNF/LLL/BKZ，把基约短。因为真实误差向量非常小，它会是某个已知目标附近的最近格点，所以最后用 Babai 近似 CVP 找 closest vector。

直观上，这一步是在找一组很小的：

$$
(\Delta M,\Delta E_i,\Delta F_i,\Delta N_i,\Sigma_i)
$$

使得所有线性同余同时成立。随机错误分支虽然也可能满足部分同余，但很难同时做到坐标这么小，并且后续还要满足乘积校验和椭圆曲线加法校验。

## 校验并拆回原始误差

CVP 得到候选向量后，先做几个强校验。

首先，缩放过的坐标必须能被 $\Delta$ 整除：

```python
M = X[0] // Delta
E = [...]
F = [...]
N = [...]
Sigma = [...]
```

然后检查人为引入的乘积变量是否真的成立：

$$
\Sigma_i=m_iF_i+E_iN_i
$$

如果这一步失败，说明 CVP 找到的是错误候选。

接着由：

$$
N_i=f_{i-1}-h_y
$$

$$
F_i=f_{i-1}+f_i
$$

可以恢复 $h_y$。相邻两条关系给出：

$$
F_i-N_i-N_{i+1}=2h_y
$$

所以：

$$
h_y=\frac{F_i-N_i-N_{i+1}}2
$$

所有 $i$ 算出来的 $h_y$ 必须一致。拿到 $h_y$ 后，先由 $N_i$ 恢复 $f_0,\ldots,f_{r-1}$：

$$
f_{i-1}=N_i+h_y
$$

最后一个 $f_r$ 可以从最后一条 $F_r$ 得到：

$$
f_r=F_r-f_{r-1}
$$

最后还要检查：

$$
|h_y|,|f_i|\le \Delta
$$

## 恢复 x 方向误差

前面的变量只给出了：

$$
M=h_x-e_0
$$

以及差分：

$$
E_i=e_{i-1}-e_i
$$

所以 $x$ 方向还剩一个平移自由度，可以令 $e_0=t$。于是：

$$
h_x=M+t
$$

$$
e_i=t-E_1-\cdots-E_i
$$

用第一条转移的 $x$ 坐标加法公式就能确定 $t$。脚本里在 $Z/pZ$ 上构造一元多项式：

```python
T = PolynomialRing(Zmod(p), 't').gen()
e0 = T
hx = M + e0
...
fpoly = (Y0 - GY) ** 2 - (X1 + X0 + GX) * (X0 - GX) ** 2
roots = fpoly.roots(multiplicities=False)
```

这个式子来自：

$$
\left(\frac{Y_0-G_y}{X_0-G_x}\right)^2
\equiv
X_1+X_0+G_x
\pmod p
$$

对每个根 $t$，恢复：

$$
h_x=M+t
$$

$$
e_0=t,\quad e_i=e_{i-1}-E_i
$$

并检查：

$$
|h_x|,|e_i|\le \Delta
$$

## 恢复曲线参数

现在真实的 $G$ 和若干真实状态点都已经知道。曲线满足：

$$
y^2=x^3+ax+b
$$

拿 $G$ 和 $P_0$ 两个点相减即可求 $a$：

$$
a=
\frac{(G_y^2-G_x^3)-(P_{0,y}^2-P_{0,x}^3)}
{G_x-P_{0,x}}
\pmod p
$$

再回代求：

$$
b=G_y^2-G_x^3-aG_x\pmod p
$$

恢复后必须检查所有候选点都在曲线上，并且连续加法关系成立：

```python
cur = P0
for i in range(1, len(points)):
    cur = add(cur, G, a)
    assert cur == points[i]
```

这一步是最终过滤。只有同时满足曲线方程和全部 EC-LCG 状态转移的候选，才是真正的解。

## 解密

恢复出 $a,b,G,P_0$ 后，按照题目完全相同的方式派生 key：

```python
material = b''.join(to_bytes(x) for x in (a, b, G[0], G[1], P0[0], P0[1]))
key = sha256(material + b'|' + key_tag.encode()).digest()[:16]
iv = int(iv_hex, 16)
pt = AES.new(key, AES.MODE_CTR, nonce=b'', initial_value=iv).decrypt(ct)
```

这里 `to_bytes` 的长度要按 $p$ 的字节长度固定为 128 字节，不能用变长编码，否则 hash 材料会不一致。


## 总结

本题的主线是：

$$
\text{带噪 EC-LCG}
\rightarrow
\text{三点共线关系}
\rightarrow
\text{小误差线性同余}
\rightarrow
\text{LLL/BKZ + Babai CVP}
\rightarrow
\text{恢复误差和曲线}
\rightarrow
\text{AES-CTR 解密}
$$

善于利用利用连续状态点之间的加法关系。由于公开坐标只有 192-bit 级别噪声，而模数是 1024-bit，正确误差向量在同余格里非常短，足够被格约简和 CVP 找出来。
