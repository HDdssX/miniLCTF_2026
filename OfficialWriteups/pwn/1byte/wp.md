### 1byte

程序所给的一字节溢出不足以控制main的返回地址。漏洞在于idx = read_long可以输入负数实现负索引越界写，可以选择覆盖read的返回地址。输入len 8字节时可以写入完整的/bin/sh，配合给的gadget实现ret2syscall。
