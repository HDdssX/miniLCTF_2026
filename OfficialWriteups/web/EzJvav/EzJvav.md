题目公开面上能拿到的东西其实不多：

两个附件：`ezjvav-source.jar`和`studio-agent.bin`

公开接口也比较有限，主要就是：

- `POST /api/studio/workspaces`
- `POST /api/studio/workspaces/{id}/materials`
- `GET /site/{id}/preview`
- `GET /site/{id}/export`
- 一些 `asset` 相关访问

把 `ezjvav-source.jar` 解开以后，先去看几个 servlet。  
这一步非常重要，因为这题源码里故意放了一些**看起来很像真利用链、但其实只是障眼法**的状态机。

比如：

- 某些 `/ctf/flag/...` 风格的路由
- 某些 `/catalog/...` 的 open / commit / status 链

这些链条如果只看名字，会很像那种“只差最后一步就拿到内部 receipt”的结构。  

但继续追进去会发现，它们都只在自己的内存状态里打转，根本碰不到真正发布流程的那一层状态。

这题真正有用的还是那四个公开站点发布接口。

继续往下看上传逻辑，会发现题目表面上像是在做一个普通主题平台，路径 contract 也看起来很正常：

但真正细看 `SiteWorkspaceService` 一类的逻辑以后，会发现还有两条隐藏允许路径：

- `preview/ThemeHook.java`
- `preview/ReviewAdapter.java`

看到这里，题目的真正入口就已经露出来了。

这题重点根本不在 ZIP Slip，而在于它**正大光明允许你把两份 Java 源码作为主题素材的一部分上传进去**。

很多人看到这里第一反应会是：既然都能上传 Java 源码了，那是不是直接动态编译 RCE？

但真把 preview worker 那一段看完以后，就会发现它没这么直给。

`ThemeHook.java` 虽然会被动态编译执行，但它所处环境非常受限：

- class allowlist 很小
- 不让碰文件
- 不让碰 socket
- 不让 exec
- 不让随便反射

所以它不是直接 RCE 点，更像一个受限的字符串加工器。

而它真正有价值的地方在于：  

预览阶段服务端会把两个内部参数偷偷拼进资源 URL 里：

```text
_h=<previewHandle>
_k=<witness>
```

同时 `ThemeHook` 又可以通过：

```java
model.getAssetCss()
```

把这串 URL 读到。

于是整件事一下就清楚了：

1. `ThemeHook` 不能直接拿系统权限
2. 但它能看到 `_h` 和 `_k`
3. 它还能把这两个值重新塞进页面会渲染的位置
4. 这样 preview 页面本身就会替我们泄露内部状态

这就是第一阶段真正的突破口。

同时，另一份 `preview/ReviewAdapter.java` 也很容易让人想多，以为这里还要继续做 RCE、偷 header 或者自己拼回执。

但看完后面的逻辑以后会发现，这一份源码最稳的写法其实恰恰是：

什么都别干，只写一个纯透传的 no-op Valve。

原因很简单：

1. 真正的内部 secret 并不在 `ReviewAdapter` 身上
2. `ReviewAdapter` 的核心用途只是让服务端认可这次审核组件安装
3. 后面的真正结果，还是服务端自己的发布链去产出

不要在这里节外生枝。

既然入口已经确定了，那下一步就是准备一个最小可用的 ZIP。

一个够用的上传包至少会包含：

- `manifest.json`
- `templates/preview.html`
- `assets/theme.css`
- `preview/ThemeHook.java`
- `preview/ReviewAdapter.java`

这一步需要做一个**足以把隐藏状态搬到页面上的利用主题**。

有了这个 ZIP 以后，就可以进行恶意上传，流程就比较清楚了。

这一步真正重要的不是“网页长什么样”，而是我们自己的 `ThemeHook` 会在渲染前把 `_h` / `_k` 从 `assetCss` 里拿出来，再编码进：

- `headline`
- `note`
- `accent`

这样 preview 页面一出来，HTML 里就已经带着我们要的阶段参数了。

到这里时，就能明白 `ThemeHook` 的价值不在执行，而在泄露内部状态。

很多人做到这里会觉得：既然 `_h` 和 `_k` 都出来了，那后面是不是只剩把它们直接塞回请求头？

其实还差一层 helper 校验。  

这也是 `studio-agent.bin` 这个附件存在的意义。

继续逆或者分析这个 helper，会发现服务端不是随便收一个 token，而是会根据：

- preview 阶段泄露出来的状态
- 你 ZIP 里自己埋进去的常量
- 若干切片和摘要逻辑

去计算一个真正的：

```text
reviewToken
```

因此这一层逻辑也需要直接放进 exp 里自动化。

进一步利用，就会有一个大坑，这是整题里最容易掉坑的地方。

正确顺序不是：

```text
preview -> peek -> apply -> export
```

而是：

```text
preview -> export -> peek -> apply -> export
```

原因在于第一次 `export` 会产出一个后面必须参与校验的中间值：

```text
bundleDigest
```

这个值通常会在响应头：

```text
X-Site-Index
```

里给出来。

如果你跳过这一步，后面的 `apply` 很可能会一直过不去。  

拿到 `reviewToken` 和第一次 `export` 的 `bundleDigest` 以后，才正式进入隐藏审核流。

先发：

```http
GET /site/{id}/preview
X-Theme-Mode: peek
X-Theme-Key: <reviewToken>
```

这一步不会直接给 flag，而是会返还下一阶段的状态，例如：

- `theme_flow=<challenge>` cookie
- `X-Theme-Tag: <marker>`
- `X-Theme-Flow: <installWitness>`

接下来再根据这些状态以及前面拿到的 `bundleDigest`，去算 install 阶段的 proof。

真正发 `apply` 时，请求大致会是：

```http
GET /site/{id}/preview
X-Theme-Mode: apply
X-Theme-Key: <reviewToken>
X-Theme-Tag: <marker>
X-Theme-Match: <proof>
Cookie: theme_flow=<challenge>
```

如果 proof 对了，这次发布就被真正推进到了“可以出回执”的阶段。

最后flag就能在**第二次 `export` 的响应头**里面找到。