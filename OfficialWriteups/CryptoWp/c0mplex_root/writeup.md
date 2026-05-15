# c0mplex_root 题解

这题看起来是高斯整数上的小根问题，但真正的入口藏在 RSA 参数里。公开文件没有直接给出高斯模数 $p + qi$，只给了普通 RSA 的 $n, e, wrapped\_key\_ct$，以及一个高斯整数系数的四次多项式。因此解题主线是：

1. 先从异常 RSA 参数中分解 $n$。
2. 用 RSA 的两个素因子还原高斯模数。
3. 把高斯整数模多项式转成普通整数模多项式。
4. 求出小根对应的普通模根，再还原实部和虚部。
5. 用小根解开 key mask，最后 AES-CTR 解密。

## 题目生成逻辑

题目里定义了一个高斯整数类：

$$
G = a + bi,\qquad a,b \in \mathbb Z
$$

其范数为：

$$
N(a+bi)=a^2+b^2
$$

生成时使用 RSA 的两个素数 $rsa\_p, rsa\_q$ 组成高斯模数：

$$
\pi = p + qi
$$

同时随机选取一个 128-bit 级别的小根：

$$
x_0 = a + bi,\qquad 2^{127} \le a,b < 2^{128}
$$

然后构造四次多项式：

$$
f(x)=c_0+c_1x+c_2x^2+c_3x^3+c_4x^4
$$

其中常数项被刻意设置成：

$$
c_0 = Q\pi - \sum_{j=1}^4 c_jx_0^j
$$

所以必然有：

$$
f(x_0)=Q\pi
$$

也就是：

$$
f(x_0)\equiv 0 \pmod {p+qi}
$$

这里的关键是：$p + qi$ 没有直接输出，但 $p, q$ 又正好是 RSA 模数 $n = pq$ 的两个素因子。

## 公开信息与目标

$output.txt$ 里主要有：

- RSA 公钥 $n, e$
- RSA 加密后的 $wrapped\_key\_ct$
- AES-CTR 的 $iv, ct$
- 高斯整数多项式系数 $coeffs$
- 小根范围 $bound = 2^{128}$

真正需要恢复的是小根：

$$
x_0=a+bi
$$

因为题目用它生成了 mask：

$$
\text{mask}=\text{SHA256}(a\Vert b)[:16]
$$

再用：

$$
\text{wrapped}=\text{key}\oplus \text{mask}
$$

最后 $wrapped$ 被 RSA 加密成 $wrapped\_key\_ct$。

所以只有同时拿到 RSA 私钥和小根 $(a,b)$，才能得到 AES key。

## RSA 弱点

题目中 $e$ 非常接近 $n$，这通常暗示不是常规随机选取的公钥指数，而是由较小的私钥指数 $d$ 反推得到：

$$
ed \equiv 1 \pmod{\varphi(n)}
$$

写成整数等式：

$$
ed-k\varphi(n)=1
$$

当 $d$ 足够小，比如满足 Boneh-Durfee 攻击（建议搜索）可覆盖的范围时，可以通过格方法恢复 $d$，进而分解 $n$。

这一步的作用有两个：

- 解开 $wrapped\_key\_ct$，得到被 mask 保护的 16 字节 $wrapped$。
- 得到 RSA 素因子 $p, q$，从而构造高斯模数 $p + qi$。

需要注意，普通 RSA 分解只给出两个素因子集合，不告诉我们高斯模数里谁是实部、谁是虚部。因此后面要分别尝试：

$$
p+qi,\qquad q+pi
$$

## 数学原理之为什么高斯模数能转换为整数模数

直接在高斯整数商环：

$$
\mathbb Z[i]/(p+qi)
$$

里求根当然可行，但实现复杂。更方便的方法是把它映射到普通整数模环。

因为在模 $p + qi$ 下：

$$
p+qi\equiv 0
$$

所以：

$$
qi\equiv -p
$$

只要把等式放到范数模数：

$$
M=p^2+q^2
$$

下看，就可以令：

$$
i\equiv t\pmod M,\qquad t=-p q^{-1}\pmod M
$$

于是任意高斯整数：

$$
u+vi
$$

都可以映射成：

$$
u+vt\pmod M
$$
（可以思考一下为什么能这样映射，这是双射吗，两者等价吗）

这给出了一个从高斯整数模 $p+qi$ 到整数模 $M$ 的计算方式。将多项式系数逐项映射后，可以得到：

$$
F(X)=\sum_j (c_{j,r}+c_{j,i}t)X^j \pmod M
$$

如果：

$$
f(a+bi)\equiv 0\pmod {p+qi}
$$

那么：

$$
z=a+bt\pmod M
$$

就是整数模多项式 $F(X)$ 的根：

$$
F(z)\equiv 0\pmod M
$$

这一转换是本题最重要的降维步骤：把二维的高斯整数小根问题，变成先求一维模根，再用二维格还原 $(a,b)$。

## 求 $F(X)$ 在模 $M$ 下的根

$M = p^2 + q^2$ 的规模约等于 RSA 模数，但它不是素数，不能直接当成有限域处理。稳妥做法是：

1. 分解 $M$。
2. 对每个素数幂 $\ell^e$，在 $Z / \ell^e Z$ 上求 $F(X)$ 的根。
3. 用 CRT 把每个局部根组合成模 $M$ 的候选根。

因为 $F$ 只有四次，每个素数幂下的根数量通常不多；如果某个素数幂下无根，就可以直接剪掉当前高斯模数顺序。

这里的分解对象是：

$$
M=p^2+q^2
$$

它可能会有比较大的因子。实际实现时可以使用 Sage 的 $factor$，必要时了解 ECM 这类整数分解方法。

## 从整数根 $z$ 还原高斯小根

得到整数模根 $z$ 后，还不能直接得到 $(a,b)$。我们只知道：

$$
a+bt\equiv z\pmod M
$$

等价于：

$$
a+bt-z=kM
$$

整理为：

$$
a=z+kM-bt
$$

由于 $a,b$ 都小于 $2^{128}$，而 $M$ 约为 1024-bit 规模，正确的 $(a,b)$ 对应一个非常短的二维表示。

可以考虑格：

$$
L=
\begin{pmatrix}
M & 0\\
-t & 1
\end{pmatrix}
$$

它的整数线性组合形如：

$$
(kM-bt,\ b)
$$

目标是让第一维接近 $-z$，这样：

$$
a=z+(kM-bt)
$$

会落入小范围。实际可用 LLL 先约简二维格，再对目标向量 $(-z,0)$ 做 Babai 近似最近向量，得到候选 $(a,b)$。

恢复后必须检查：

$$
0<a,b<2^{128}
$$

以及：

$$
(a+bt-z)\bmod M=0
$$

最后还应代回原高斯多项式验证：

$$
f(a+bi)\equiv 0\pmod {p+qi}
$$

这样可以排除 CRT 组合得到的其他模根。

## 解密流程

恢复 RSA 私钥后：

$$
\text{wrapped}=\text{wrapped\_key\_ct}^d\bmod n
$$

恢复小根 $(a,b)$ 后，按照题目里的字节拼接方式计算：

$$
\text{mask}=\text{SHA256}(a\Vert b)[:16]
$$

再得到：

$$
\text{key}=\text{wrapped}\oplus\text{mask}
$$

最后用公开的 $iv$ 和 $ct$ 做 AES-CTR 解密。CTR 模式不需要 padding，判断候选是否正确时直接看明文格式即可。

## 实现注意点

- $coeffs$ 是 $(real, imag)$ 形式，表示高斯整数系数。
- $output.txt$ 的值是 Python 字面量，用 $ast.literal\_eval$ 解析较方便。
- $q$ 在模 $M=p^2+q^2$ 下可逆，因为 $gcd(q, p^2+q^2)=1$。
- RSA 分解得到的两个素因子要尝试两种顺序，否则 $t$ 可能不对应题目实际的 $p+qi$。
- $M$ 的分解和 $n$ 的分解是两回事，不能混淆。
- 局部求根时如果某个素数幂没有根，应尽早剪枝。
- CRT 合并后可能有多个 $z$，每个都要经过二维还原和原方程校验。
- $a,b$ 的字节长度必须按 $bound$ 对应的 16 字节处理，不能使用变长编码，否则 hash mask 会不一致。
- AES-CTR 的 $iv$ 在题目中作为 counter 初值使用，不是 CBC IV，也无 padding。

## 总结

本题的关键是识别出两层隐藏关系：

- RSA 的小私钥指数泄露了 $p,q$。
- 高斯整数模 $p+qi$ 可以通过范数 $p^2+q^2$ 降到普通整数模。

完成这两步后，题目就变成了比较清晰的组合：

$$
\text{Boneh-Durfee} \rightarrow \text{构造 }F(X)\bmod M
\rightarrow \text{局部求根 + CRT}
\rightarrow \text{二维 CVP 还原 }(a,b)
\rightarrow \text{RSA + AES 解密}
$$
