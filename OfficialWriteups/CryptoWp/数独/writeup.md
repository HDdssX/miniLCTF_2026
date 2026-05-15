# ！？数独独数？！ 题解

每轮给出四个参数 $\alpha,\beta,\gamma,\delta$，要求提交两个合法的 $25\times25$ 数独矩阵 $A,B$，并让计算出的 $C$ 仍然是合法数独。

## 先化简乘法

题目中的计算为：

$$
c_{ij}
\equiv
\sum_{k=1}^{25}
\left(
\alpha a_{ik}b_{kj}
+\beta a_{ik}
+\gamma b_{kj}
+\delta
\right)
\pmod {25}
$$

合法数独的任意一行、任意一列都恰好包含 $1,\ldots,25$，所以：

$$
\sum_{k=1}^{25}a_{ik}
\equiv
\sum_{k=1}^{25}b_{kj}
\equiv
1+2+\cdots+25
\equiv 0\pmod {25}
$$

同时：

$$
\sum_{k=1}^{25}\delta=25\delta\equiv0\pmod {25}
$$

于是 $\beta,\gamma,\delta$ 三项全部消掉，只剩：

$$
C\equiv \alpha AB\pmod {25}
$$

因此每轮真正要看的只有 $\alpha$。

若：

$$
\gcd(\alpha,25)\ne1
$$

则 $\alpha x\bmod25$ 不能取遍 $0,\ldots,24$，所以 $\alpha AB$ 不可能成为合法数独。这种轮次直接提交 `-1`。

若：

$$
\gcd(\alpha,25)=1
$$

乘以 $\alpha$ 只是对剩余类做一次置换。只要预先构造一组 $A,B$ 使得：

$$
AB\bmod25
$$

本身是合法数独，这一组 $A,B$ 就能通过所有 $\alpha$ 可逆的轮次。

## 仿射数独构造

我们可以考虑利用仿射变换高效构造与检验可能的数独矩阵组合：

把 $25$ 个行号、列号都写成 $\mathbb F_5^2$ 里的向量。

设：

$$
x=(x_0,x_1)\in\mathbb F_5^2
$$

表示行号，其中 $x_0$ 是第几个 5 行带，$x_1$ 是带内第几行。列号同理写成：

$$
y=(y_0,y_1)\in\mathbb F_5^2
$$

取矩阵：

$$
M=
\begin{pmatrix}
a&b\\
c&d
\end{pmatrix}
$$

要求：

$$
\det M\ne0,\qquad b\ne0
$$

对每个符号 $t\in\mathbb F_5^2$，定义位置集合：

$$
P_t^{(M)}=\{(x,y):y=Mx+t\}
$$

这组集合刚好适配数独约束：

- 固定一行 $x$，每个 $t$ 给出唯一一列。
- 固定一列 $y$，因为 $M$ 可逆，每个 $t$ 给出唯一一行。
- 固定一个 $5\times5$ 宫时，$x_0,y_0$ 固定，$x_1,y_1$ 变化。映射

$$
(x_1,y_1)\mapsto y-Mx
$$

的行列式为 $-b$，所以 $b\ne0$ 时每个宫里每个 $t$ 也只出现一次。

再取一个双射：

$$
\pi:\mathbb F_5^2\rightarrow\mathbb Z/25\mathbb Z
$$

即可构造合法数独：

$$
A(x,y)=\pi(y-Mx)
$$

## 乘积保持结构

再取一个满足同样条件的矩阵 $N$ 和双射 $\tau$，定义：

$$
B(y,z)=\tau(z-Ny)
$$

考虑矩阵乘法：

$$
C(x,z)=\sum_y A(x,y)B(y,z)
$$

代入：

$$
C(x,z)=\sum_y \pi(y-Mx)\tau(z-Ny)
$$

令：

$$
t=y-Mx,\qquad y=Mx+t
$$

得到：

$$
C(x,z)=
\sum_t \pi(t)\tau((z-NMx)-Nt)
$$

定义：

$$
h(s)=\sum_t\pi(t)\tau(s-Nt)\pmod {25}
$$

于是：

$$
C(x,z)=h(z-NMx)
$$

形式又回到了仿射数独。只要：

$$
NM\text{ 可逆且右上角非零}
$$

并且：

$$
h:\mathbb F_5^2\rightarrow\mathbb Z/25\mathbb Z
$$

也是双射，那么 $C$ 就是合法数独。

可以取：

$$
M=
\begin{pmatrix}
0&1\\
1&0
\end{pmatrix},
\qquad
N=
\begin{pmatrix}
1&1\\
0&1
\end{pmatrix}
$$

此时：

$$
NM=
\begin{pmatrix}
1&1\\
1&0
\end{pmatrix}
$$

同样满足条件。

## 把搜索写成卷积

由于 $N$ 可逆，令：

$$
u=Nt,\qquad t=N^{-1}u
$$

再记：

$$
p(u)=\pi(N^{-1}u),\qquad q=\tau
$$

则：

$$
h(s)=\sum_{u\in\mathbb F_5^2}p(u)q(s-u)\pmod {25}
$$

也就是在 $\mathbb F_5^2$ 上找两个双射 $p,q$，使卷积：

$$
h=p*q
$$

仍然是双射。这个爆破需要的时间很短，通常数分钟内就能撞到满足条件的 $p,q$。

找到 $p,q$ 后，由：

$$
\pi(t)=p(Nt),\qquad \tau=q
$$

构造 $A,B$。

## 提交流程

预先构造一组固定的 $A,B$，并检查：

- 若 $\gcd(\alpha,25)\ne1$，提交 `-1`。
- 若 $\gcd(\alpha,25)=1$，提交这组固定的 $A,B$。
