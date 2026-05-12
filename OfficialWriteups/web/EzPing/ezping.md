这题如果只看表面，确实是一眼命令注入；但真正的考点并不在命令注入本身，而在于：**WAF 检查的是原始字节流，业务代码处理的是按 `charset` 解码后的字符串。**

进一步审计完源码可以看出本质考点就只是 `找一个python支持但是与ascii完全不兼容 的编码方式`，比如 `cp037` ， `cp500` ，能看明白这个问一下 deepseek 都可以直接秒了。一样，这题就结束了。

首先审计代码，我们可以看到一个很明显的命令注入的地方，摆明了就是一个命令注入的题目：

```python
command = f"ping -c 1 {target}"
process = subprocess.run(command, shell=True, ...)
```

但是，在 `@app.before_request` 中，程序定义了一个看似无懈可击的 `waf_middleware`。

```python
raw_data = request.get_data()
blacklist = [b'flag', b'cat', b'ls', b'bash', b'sh', b'nc', b';', b'|', b'&', b'$', b'>', b'<', b'`', b'\n', b'\\']
```

它获取了请求的**纯字节流**，并用黑名单过滤了几乎所有的命令执行关键字和分隔符（`;`, `|`, `&`, `\n` 等）。 如果在常规的 UTF-8 编码下，你根本无法构造出任何有效的命令注入 Payload，因为你用来拼接命令的符号全被封死了。

这道题的精髓在于 `CustomRequest` 类的重写，以及 WAF 层和业务层对请求数据处理方式的差异。

```python
class CustomRequest(Request):
    @property
    def charset(self):
        return self.mimetype_params.get('charset', 'utf-8')
```

这段代码允许攻击者通过 HTTP 头主动控制 Flask 解码的字符集。

**WAF 层的视角：纯字节流** WAF 使用的是 `request.get_data()`，它拿到的是未经过任何解码的原始二进制字节（Bytes）。比如它的黑名单 `b'cat'` 在十六进制下是 `\x63\x61\x74`。

**业务层的视角：依赖 Charset 的解码字符串** 业务层使用的是 `request.get_data(as_text=True)`。当 `as_text=True` 时，Flask 会去读取 HTTP 请求头 `Content-Type` 中的 `charset` 字段（例如 `application/json; charset=utf-8`），并根据这个指定的字符集将字节流解码为字符串。

所以它的攻击原理就很清晰了：如果你在发送 Payload 时，将恶意的 JSON 字符串（例如 `{"target": "127.0.0.1; cat /flag"}`）编码为一个特殊的 `x` 格式，并在 HTTP 请求头中声明 `Content-Type: application/json; charset=x：`

因此只要保证这个 `x` 编码编码出来的结果和和字节流（`ascii编码`）的结果不一样，就可以轻而易举绕过这个 WAF 。而业务层接收到后会根据 `Charset` 的设置，自动用这个 `x` 解码，从而运行 payload 。

因此我们就可以把这一个抽象的问题转换成一个具体的问题：找一个**python支持**但是与**ascii完全不兼容**的编码方式。

这一题参考了 python 的[官方文档](https://docs.python.org/zh-cn/3/library/codecs.html#standard-encodings)，里面写了 python 支持的全部编码方式。

在这我们可以找到一个 **EBCDIC** 编码，符合全部条件。看明白源代码后，拿这个具体的问题去询问 deepseek 都可以获得答案并写出脚本。