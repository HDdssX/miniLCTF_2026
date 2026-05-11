# Ezdomain

**MINIL** 域中发生了一系列神奇的事情……

---

### 题目附件下载地址

https://pan.baidu.com/s/1m0lLkhF3MKtxllngkNUCGg?pwd=LCTF
提取码: LCTF

解压密码：773a6305-e1c6-449c-b9b3-93575b72c148

---

### VMware 网卡配置

- VMnet1
    - 类型：Host-only（仅主机）
    - 子网地址：10.9.21.0/24

- VMnet8
    - 类型：NAT
    - 子网地址：192.168.162.0/24


### 虚拟机网卡设置

- WEB01
    - 网卡1：VMnet1
    - 网卡2：VMnet8
- DC01
    - 网卡：VMnet1

虚拟机启动后，请**等待约 5 分钟**，确保所有服务完成启动后再开始操作。

**入口 IP 为 192.168.162.10**