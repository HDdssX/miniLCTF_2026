### level5

upgrade_item函数存在漏洞：创建了新线程而没有使用任何锁，存在条件竞争。

```c
static void *brew(void *arg) {
    int i = (int)(intptr_t)arg;
    if (i < 0 || i >= BAG) return NULL;
    if (g.bag[i] != SMALL) return NULL;
    puts("[brew] ...");
    sleep(2);
    g.bag[i] = 0;
    g.bag[i] = BIG;					//sleep内sell，这里仍然会添加物品
    puts("[brew] done");
    return NULL;
}
```

可以在sleep的2秒内sell_item，结束后可以sell第二次把bag_cnt打成负数。此时再购买物品可以负索引将stage写成10（最大），如果进行quit_game恰好可以覆盖rbp。将rbp覆盖成一定地址，就可以在set_name处实现任意写。

```c
static void battle(void) {
    int s = g.stage + 1;
    int hp, mp, ehp, c;
    const Mob *m;
    char board[0x40] = "challenger rank";
    size_t n = g.banner_len;
    puts("\nArena board:");
    write(1, board, n);			//此处可泄露
    puts("");
```

最后banner_len修改成大值可泄露libc，stage重新写入大值栈溢出ret2libc。