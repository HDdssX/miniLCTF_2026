### n4n0sleep

程序主体是在子进程进行shellcode执行。沙箱白名单open,read和nanosleep，并且关闭了stdin,stdout,stderr。相比于简单的orw做法，使用nanosleep代替write。可以用时间直接编码也可以爆破（预期解）。

可以编写shellcode形如：

```asm
    cmp xxxx, xxxx			;比较字符
    jne done								;如果当前字符对了直接退出
    mov qword ptr [rsp + 0x100], 0			
    mov qword ptr [rsp + 0x108], 200000000
    lea rdi, [rsp + 0x100]
    xor esi, esi
    mov eax, 35
    syscall									;否则休眠0.2秒
```

经典的死循环做法也能得到flag但远程表现不稳定（？）。