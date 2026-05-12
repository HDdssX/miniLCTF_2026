### newletter

明显的漏洞：sort范围开大了，可以用于溢出到auth解锁move选项。

delete函数中，`chunk[idx].ptr = realloc(chunk[idx].ptr, 0); `在大多数情况下约等于free+清空指针，但传入空指针会生成大小0x20但size为0的堆块。

配合move实现uaf，可玩性就很高了。除了用tcachebin，malloc大于0x400的堆块，再delete空指针也可以实现与top_chunk的隔断得到unsorted_bin泄露libc。

```c
    if (ret == 0)
    {
    	puts("move failed!");
    	return;
    }//进入该分支，后续不会清空指针
    chunk[idx2].ptr = ptr;
    chunk[idx1].ptr = NULL;
    chunk[idx1].size = 0;
```

劫持执行流的方法多样，测试时使用的是house_of_some。