#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

long long idx;
size_t len;

void init()
{
    setbuf(stdout, NULL);
    setbuf(stdin, NULL);
    setbuf(stderr, NULL);
}

static long read_long(void)
{
    char line[0x20];
    size_t used = 0;

    while (used + 1 < sizeof(line)) {
        char ch;
        ssize_t got = read(0, &ch, 1);

        if (got <= 0) {
            _exit(0);
        }

        if (ch == '\n') {
            break;
        }

        line[used++] = ch;
    }

    line[used] = '\0';
    return strtol(line, NULL, 0);
}

void lose()
{
    const char *msg = "Something wrong.\n";
    __asm__ volatile (
        "mov $1, %%rax\n"
        "mov $1, %%rdi\n"
        "mov %0, %%rsi\n"
        "mov $17, %%rdx\n"
        "syscall\n"
        :
        : "r"(msg)
        : "rax", "rdi", "rsi", "rdx"
    );
    exit(0);
}

__attribute__((naked)) void gadget(void)
{
    __asm__(
        "pop %rdi\n"
        "ret\n"
        "pop %rsi\n"
        "ret\n"
        "pop %rdx\n"
        "ret\n"
        "pop %rax\n"
        "ret\n"
    );
}

void syst3m(const char *cmd)
{
    write(1, cmd, strlen(cmd));
    write(1, "\n", 1);
}

__attribute__((noinline)) void backdoor(void)
{
    syst3m("/bin/5h");
}


int main(void)
{

    char buf[0x50];
    init();

    write(1, "Where would you like to start?\n> ", 33);
    idx = read_long();

    if (idx > 0x50) {
        lose();
    }
    write(1, "What's the length?\n> ", 21);
    len=read_long();

    size_t maxlen=0x69-idx;

    write(1, "Let's go\n> ", 11);

    if(len>maxlen)
        read(0,&buf[idx],maxlen);
    else
        read(0,&buf[idx],len);


    return 0;
}
