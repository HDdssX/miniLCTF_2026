# Schrodinger's Env

## Java 层

Java 层非常薄，只负责：

- 读取输入框内容
- 传给 JNI
- 同时把 `AssetManager` 传给 native

关键点是 native 导出函数：

- `getResultNative(ticket, getAssets())`

核心逻辑全部在 `libchal1.so` 中。

## Native 层

native 层负责：

- 规范化接入码并做 FNV 校验
- 读取 `/proc/self/maps`
- 读取系统属性 `ro.security.magic_token`
- 从 `assets/compat_profile.dat` 解出期望 token
- 用 `maps` 和 `token` 两个环境标签进入 KDF
- 解密真 flag 或回退到诱饵输出

## 第一步：恢复正确接入码

native 会先对输入做规范化：

- 只保留字母和数字
- 全部转成小写

之后对规范化结果做 `FNV-1a 64` 比较，目标常量为：

- `0xF625741C0FFE8C21`

如果只看这个常量，确实可以误以为这是一道 FNV preimage 题；  
但结合题目标题 `Schrodinger's Env`，很容易注意到标题本身就是明显提示。

将标题规范化：

- `Schrodinger's Env` -> `schrodingersenv`

正好命中校验值，因此正确接入码就是标题本身，或任何规范化后等价的形式。

## 第二步：识别两个环境点

通过逆向 JNI 主流程，可以看到输入校验通过后，程序会继续取两个环境特征。

### 环境点一：`/proc/self/maps`

native 会读取：`/proc/self/maps`

并寻找标记字符串：`/system/framework/XposedBridge.jar`

但这里的设计不是把整行 `maps` 文本直接喂入后续逻辑，而是做了 canonicalize：

- 命中标记 -> `hooked:maps`
- 未命中 -> `clean:maps`

### 环境点二：系统属性

native 同时会读取系统属性：`ro.security.magic_token`

如果这个属性不存在、为空，或者值不正确，则进入：`clean:token`

如果值与期望 token 相同，则进入：`hooked:token`

## 第三步：恢复正确 token

题目没有把 token 明文硬编码在代码里，而是把线索放进了 APK 资源文件：`assets/compat_profile.dat`

native 会读取这个文件并做一个很小的解码过程：

- 文件头必须是 `CFG1`
- 第 5 个字节表示长度
- 第 6 个字节表示 seed
- 后续数据按固定规则异或还原

还原后可以得到正确 token：`masochistic.sdk::grant_key_v1`

## 第四步：理解真假 flag 分支

用 `maps_feature` 和 `token_feature` 做哈希和混合

1. 派生出 16 字节 key
2. 用这个 key 去解密硬编码密文
3. 检查解密结果是否以 `MSDK|` 开头

如果前缀命中：

- 返回其后的内容，作为真 flag

如果前缀不命中：

- 走诱饵分支，解出 `fakeflag{You_Are_Too_Clean_Bro}`

## 第五步：拿到真 flag

最终要同时满足两个条件：

- `maps_feature = hooked:maps`
- `token_feature = hooked:token`

也就是：

1. 让进程的 `maps` 中能命中 `XposedBridge.jar`
2. 让 `ro.security.magic_token` 返回正确 token
3. 输入正确接入码

此时程序才会派生出正确 key，最终解出：`miniL{hook_the_detector_not_the_branch}`
