这一题起手其实没有太多花活，先把首页打开，把前端 JS 和页面请求扫一遍。

能比较直接看到或者顺着页面行为摸到的接口，主要就是：

- `GET /api/me`
- `GET /api/stats`
- `POST /api/verify`
- `POST /api/run`
- `GET /api/run?cursor=N`

先访问：

```http
GET /api/me
```

这一请求最重要的不是响应体，而是响应头里的：

```text
X-Session-Id
```

这一步非常关键。  
因为后面不管是竞态、提权，还是读 `/api/run` 的回显，全部都绑在这个会话上。  

如果你每次都让服务端重新给你发一个新 `sid`，那后面很多行为你连“是不是同一个人”都追不稳。

所以这题起手第一件事不是爆破，不是扫隐藏路由，而是：

> 先固定好自己的 `X-Session-Id`，让后续所有请求都在同一个 session 里跑。

固定好 session 以后，下一步看：

```http
GET /api/stats
```

这个接口会给出类似 `last_verify_activity` 一类的状态信息。

这种字段在正常业务里其实挺怪的，因为它不像是给普通用户看的，更像是在告诉你：

后台还有个东西在按固定频率持续跑。

再结合：

```http
POST /api/verify
```

这个接口本身的语义又不像“同步校验成功就直接升权”的风格，所以可以判断：

1. 外部请求可能只是把自己放进一个待验证状态
2. 后面还有另一个固定节奏的内部请求，会来处理这个状态
3. 只有这两个动作在时间上撞到一起，权限才会真的变化

也就是说，这题前半段大概率不是传统认证逻辑，而是 race condition。

做法其实很简单粗暴：

1. 先拿一个固定的 `sid`
2. 多线程高频去打：

```http
POST /api/verify
```

3. 同时不断轮询：

```http
GET /api/me
```

4. 直到 `role` 从 `guest` 变成 `admin`

拿到 admin 以后，再进一步扫描可以发现两个隐藏路由：

```http
GET /admin
GET /admin/debug/source
```

`/admin/debug/source`直接暴露了源码

把源码读出来以后，很快就能看到 `/api/run` 的核心逻辑：

```js
app.post('/api/run', async (req, res) => {
    const store = als.getStore();
    if (!store || store.role !== 'admin') return res.status(403).json({ error: 'Denied' });

    const { code } = req.body;
    if (typeof code !== 'string' || code.length > 255) return res.status(400).send('WAF: Length');

    const blacklist = /then|constructor|process|require|eval|=>|catch/g;
    if (blacklist.test(code)) return res.status(400).send('WAF: Keyword');

    try {
        const acorn = require('acorn');
        const ast = acorn.parse(code, { ecmaVersion: 2020 });
        if (containsBlockedAstPattern(ast)) return res.status(400).send('WAF: Fold');
    } catch (e) {
        return res.status(400).send('Syntax Error');
    }

    const context = vm.createContext(Object.create(null));
    try {
        const result = await vm.runInContext(code, context, { timeout: 1000 });
        store.lastRunOutput = String(result);
        res.status(204).end();
    } catch (e) {
        res.status(500).send('Runtime Error');
    }
});
```

这段代码第一眼看过去，会先注意到几层限制：

1. 代码长度限制 `255`
2. 正则黑名单：
   - `then`
   - `constructor`
   - `process`
   - `require`
   - `eval`
   - `catch`
3. AST 常量折叠检查
4. 真正执行在 `vm.createContext(Object.create(null))`

如果只盯着前面三层，会以为重点是“怎么拆关键字绕 WAF”。  
但我当时继续往下一看，真正刺眼的其实是这句：

```js
const result = await vm.runInContext(code, context, { timeout: 1000 });
```

也就是说，这题真正的洞不在“它用了 vm”，而在：它把 `vm.runInContext(...)` 的返回值交给了 `await` 去消费。

JavaScript 里，`await` 不只是等 Promise，它会把任何看起来像 thenable 的对象都当成 Promise 风格的东西去处理。

所以这里最自然的思路就变成了：不去明着写危险对象，而是返回一个“看起来像 thenable”的对象，让宿主在处理这个 thenable 的过程中，把宿主自己的回调函数传进来，最后再从这个主回调上往回扒出更高权限对象。

这就是整道题沙箱逃逸的本质。

换句话说，这题不是靠“直接把 `process` 写出来”赢，而是靠：让危险行为在运行时自然发生，绕过字符串和 AST 两层黑名单。

payload 的构造思路是这样的：

1. 返回一个 `Proxy` 对象
2. 当宿主因为 `await` 去取它的某个关键属性时，触发 `get` trap
3. 在 trap 里拿到宿主回调
4. 再借宿主回调的原型链和属性枚举，把关键能力在运行时拼出来

成功RCE。

但是事情并没有结束：源码里能看到，执行结果会先被写到：

```js
store.lastRunOutput
```

然后再通过：

```http
GET /api/run?cursor=N
```

一位一位取出来。

也就是说，后半段虽然已经进入命令执行，但它本质上仍然是个 Oracle，只不过这回读的是“命令结果”，不是直接读文件。

因此我们需要循环单字符读取 Oracle，才能获得回显的结果。

但是在看题的时候又会发现，flag是有权限限制的。而服务进程本身是低权限用户。  

所以做到这里拿到的还只是**低权限系统命令执行**，还没真正碰到最终 flag。

后面继续审源码和镜像文件时，会发现一个很关键的 setuid 程序：

```text
/usr/local/bin/omni_pkexec
```

以及对应的 `omni_pkexec.c`。

Web + Pwn。

看到这一步，其实后半段路线就已经很明确了：写一个恶意 gconv 模块，让它在 `gconv_init()` 里帮我们把 `/flag` 读出来。

后面具体实现不难，不再赘述。