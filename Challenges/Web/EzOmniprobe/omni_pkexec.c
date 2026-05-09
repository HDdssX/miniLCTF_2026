#define _GNU_SOURCE

#include <ctype.h>
#include <dlfcn.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

typedef void (*gconv_init_t)(void);

static int is_path_safe(const char *value) {
    if (value == NULL || *value == '\0') {
        return 0;
    }

    for (const unsigned char *p = (const unsigned char *)value; *p != '\0'; ++p) {
        if (!(isalnum(*p) || *p == '/' || *p == '.' || *p == '_' || *p == '-')) {
            return 0;
        }
    }

    return 1;
}

static int is_module_name_safe(const char *value) {
    if (value == NULL || *value == '\0') {
        return 0;
    }

    for (const unsigned char *p = (const unsigned char *)value; *p != '\0'; ++p) {
        if (!(isalnum(*p) || *p == '.' || *p == '_' || *p == '-')) {
            return 0;
        }
    }

    return 1;
}

static int load_module_name(const char *module_dir, char *module_name, size_t module_name_len) {
    char config_path[PATH_MAX];
    char line[512];

    if (snprintf(config_path, sizeof(config_path), "%s/gconv-modules", module_dir) >= (int)sizeof(config_path)) {
        return -1;
    }

    FILE *fp = fopen(config_path, "r");
    if (fp == NULL) {
        return -1;
    }

    while (fgets(line, sizeof(line), fp) != NULL) {
        char from[128];
        char to[128];
        char module[128];
        int cost = 0;

        if (sscanf(line, "module %127s %127s %127s %d", from, to, module, &cost) == 4 &&
            strcmp(from, "OMNI-LEGACY//") == 0 &&
            strcmp(to, "INTERNAL") == 0 &&
            is_module_name_safe(module)) {
            snprintf(module_name, module_name_len, "%s", module);
            fclose(fp);
            return 0;
        }
    }

    fclose(fp);
    return -1;
}

static void print_help(const char *argv0) {
    fprintf(stderr, "Usage: %s [--help]\n", argv0);
    fprintf(stderr, "Legacy policykit locale bridge.\n");
    fprintf(stderr, "Compatibility mode:\n");
    fprintf(stderr, "  CHARSET=OMNI-LEGACY//\n");
    fprintf(stderr, "  SHELL=omni\n");
    fprintf(stderr, "  OMNI_GCONV_PATH=/absolute/path/to/module_dir\n");
    fprintf(stderr, "  Requires a gconv-modules line like:\n");
    fprintf(stderr, "    module OMNI-LEGACY// INTERNAL omni 2\n");
    fprintf(stderr, "  Loads $OMNI_GCONV_PATH/<module>.so and calls gconv_init().\n");
}

int main(int argc, char **argv) {
    if (argc > 1 && (strcmp(argv[1], "--help") == 0 || strcmp(argv[1], "-h") == 0)) {
        print_help(argv[0]);
        return 0;
    }

    if (setgid(0) != 0 || setuid(0) != 0) {
        fputs("omni_pkexec: privilege sync failed\n", stderr);
        return 1;
    }

    const char *charset = getenv("CHARSET");
    const char *shell = getenv("SHELL");
    const char *module_dir = getenv("OMNI_GCONV_PATH");
    char module_name[128];

    if (charset == NULL ||
        strcmp(charset, "OMNI-LEGACY//") != 0 ||
        shell == NULL ||
        strcmp(shell, "omni") != 0 ||
        !is_path_safe(module_dir) ||
        load_module_name(module_dir, module_name, sizeof(module_name)) != 0) {
        print_help(argv[0]);
        return 1;
    }

    char module_path[PATH_MAX];
    if (snprintf(module_path, sizeof(module_path), "%s/%s.so", module_dir, module_name) >= (int)sizeof(module_path)) {
        fputs("omni_pkexec: module path too long\n", stderr);
        return 1;
    }

    void *handle = dlopen(module_path, RTLD_NOW);
    if (handle == NULL) {
        fprintf(stderr, "omni_pkexec: %s\n", dlerror());
        return 1;
    }

    gconv_init_t init = (gconv_init_t)dlsym(handle, "gconv_init");
    if (init == NULL) {
        fputs("omni_pkexec: invalid gconv module\n", stderr);
        dlclose(handle);
        return 1;
    }

    init();
    dlclose(handle);
    return 0;
}
