#define _GNU_SOURCE

#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

#ifndef SYS_open
#error "This challenge expects SYS_open on the target architecture."
#endif

static int install_seccomp(void)
{
    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_X86_64, 0, 5),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JGE | BPF_K, 0x40000000u, 3, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_read, 3, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_open, 2, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_nanosleep, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog prog = {
        .len = (unsigned short)(sizeof(filter) / sizeof(filter[0])),
        .filter = filter,
    };

    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0)
    {
        return -1;
    }
    if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) != 0)
    {
        return -1;
    }

    return 0;
}

static void close_fds(void)
{
#ifdef SYS_close_range
    syscall(SYS_close_range, 0u, ~0u, 0u);
#else
    for (int fd = 0; fd < 1024; ++fd)
    {
        close(fd);
    }
#endif
}

static int load_shellcode(void **shellcode)
{
    char *buf;
    size_t got = 0;

    buf = mmap(NULL, 0x100, PROT_READ | PROT_WRITE,
               MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (buf == MAP_FAILED)
    {
        return -1;
    }

    while (got < 0x100)
    {
        ssize_t n = read(STDIN_FILENO, buf + got, 0x100 - got);
        if (n <= 0)
        {
            return -1;
        }
        got += (size_t)n;
    }

    if (mprotect(buf, 0x100, PROT_READ | PROT_EXEC) != 0)
    {
        return -1;
    }

    *shellcode = buf;
    return 0;
}

static void run_shellcode(void *shellcode)
{
    alarm(1);
    prctl(PR_SET_TSC, PR_TSC_SIGSEGV, 0, 0, 0);
    close_fds();

    if (install_seccomp() != 0)
    {
        _exit(1);
    }

    ((void (*)(void))shellcode)();
    __builtin_trap();
}

int main(void)
{
    void *shellcode;
    pid_t pid;

    if (load_shellcode(&shellcode) != 0)
    {
        return 1;
    }

    pid = fork();
    if (pid < 0)
    {
        return 1;
    }

    if (pid == 0)
    {
        run_shellcode(shellcode);
    }

    waitpid(pid, NULL, 0);
    if (write(STDOUT_FILENO, ".", 1) < 0)
    {
        return 1;
    }
    return 0;
}
