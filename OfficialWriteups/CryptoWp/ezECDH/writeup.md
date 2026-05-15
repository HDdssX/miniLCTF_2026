# ezECDH 题解

## 曲线阶

对 $E:y^2=x^3+x$，当：

$$
p\equiv 3\pmod 4
$$

时，这条曲线是超奇异曲线。题目参数下有：

$$
\#E(\mathbb F_p)=p+1
$$

设 $G$ 所在子群阶为 $n$，则：

$$
n\mid p+1
$$

于是：

$$
p\equiv -1\pmod n,\qquad p^2\equiv 1\pmod n
$$

嵌入度为 $2$

这就是本题的预期入口：椭圆曲线离散对数可以通过 MOV attack 转到 $\mathbb F_{p^2}$ 的乘法群里。

## MOV attack

把曲线扩域到 $\mathbb F_{p^2}$：

$$
E(\mathbb F_p)\hookrightarrow E(\mathbb F_{p^2})
$$

选一个合适的 $n$-torsion 点 $Q$，用 Weil pairing：

$$
e_n:E[n]\times E[n]\rightarrow \mu_n
$$

由于 pairing 是双线性的，若：

$$
A=aG
$$

则：

$$
e_n(A,Q)=e_n(aG,Q)=e_n(G,Q)^a
$$

记：

$$
\alpha=e_n(G,Q),\qquad \beta=e_n(A,Q)
$$

就得到：

$$
\beta=\alpha^a
$$

也就是说，原本的 ECDLP 被转成了有限域乘法群中的离散对数：

$$
a=\log_\alpha\beta
$$

题目参数下这一部分可以直接交给 Sage 处理。若随机选到的 $Q$ 让 pairing 退化，重新取点即可。

## 只有 x 坐标的影响

题目只输出：

$$
G_x,\quad A_x,\quad B_x
$$

用 `lift_x` 还原点时会有正负号不确定。

如果 $A$ 被取成 $-A$，恢复出来的是 $-a$；如果 $B$ 被取成 $-B$，算出的共享点会差一个负号。但 key 只使用 $x(S)$，而：

$$
x(P)=x(-P)
$$

所以这个符号问题不会影响最终解密。

## 解密流程

恢复标量 $a$ 后计算：

$$
S=aB
$$

然后按题目代码派生 key：

```python
FIELD_BYTES = (ZZ(p).nbits() + 7) // 8
key_material = x.to_bytes(FIELD_BYTES, "big")
key = sha256(key_material).digest()
```

这里 $x$ 必须按 $p$ 的字节长度固定编码，不能随手用变长整数转字节。最后用公开的 `iv` 和 `ct` 做 AES-CBC 解密并去 padding。

