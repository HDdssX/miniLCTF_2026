#include <stdlib.h>
#include <unistd.h>
#include <string.h>
#include <stdio.h>
#include <fcntl.h>
#include <time.h>
#include <malloc.h>

typedef struct {
    char *ptr;
    size_t size;
} heap_chunk;


heap_chunk chunk[10];
size_t last_size;
char auth[8];


void init()
{
    setbuf(stdin, 0);
    setbuf(stdout, 0);
    setbuf(stderr, 0);
    strcpy(auth, "user");
}


void sort_chunk()
{
    int i;
    int j;
    heap_chunk tmp;

    for (i = 0; i < 10; i++)
    {
        for (j = 0; j < 10 - i; j++)
        {
            if (chunk[j].size != 0 && chunk[j + 1].size == 0)
            {
                tmp = chunk[j];
                chunk[j] = chunk[j + 1];
                chunk[j + 1] = tmp;
            }
            else if (chunk[j].size != 0 && chunk[j + 1].size != 0 && chunk[j].size < chunk[j + 1].size)
            {
                tmp = chunk[j];
                chunk[j] = chunk[j + 1];
                chunk[j + 1] = tmp;
            }
        }
    }
}

size_t calc_usable_size(size_t size)
{
    size_t chunk_size;

    if (size + 0x8 <= 0x20)
        chunk_size = 0x20;
    else
        chunk_size = (size + 0x8 + 0xf) & ~0xf;

    return chunk_size - 0x8;
}

void add()
{   
    int idx;
    size_t size;
    printf("idx >> ");
    scanf("%d", &idx);
    getchar();
    if (idx < 0 || idx >= 10)
    {
        puts("Invalid idx!");
        return;
    }

    printf("size >> ");
    scanf("%zu", &size);
    getchar();
    if (size < 0x20 || size > 0x8000)
    {
        puts("Invalid size!");
        return;
    }
    if (size <= last_size+0x10)
    {
        puts("Size must be larger than last one!");
        return;
    }

    chunk[idx].ptr = malloc(size);
    chunk[idx].size = size;
    last_size = size;
    printf("content >> ");
    fgets(chunk[idx].ptr, size, stdin);
}

void delete()
{
    int idx;

    printf("idx >> ");
    scanf("%d", &idx);
    getchar();
    if (idx < 0 || idx >= 10)
    {
        puts("Invalid idx!");
        return;
    }

    if (chunk[idx].ptr != NULL && chunk[idx].size && chunk[idx].size < last_size )
    {
        return;
    }

    chunk[idx].ptr = realloc(chunk[idx].ptr, 0);
    chunk[idx].size = 0;
}



void edit()
{
    int idx;
    size_t size;

    printf("idx >> ");
    scanf("%d", &idx);
    getchar();
    if (idx < 0 || idx >= 10)
    {
        puts("Invalid idx!");
        return;
    }
    if (chunk[idx].ptr == NULL)
    {
        puts("No such letter!");
        return ;
    }

    size = calc_usable_size(chunk[idx].size);
    printf("content >> ");
    fgets(chunk[idx].ptr, size, stdin);
}

void show()
{
    int idx;

    printf("idx >> ");
    scanf("%d", &idx);
    getchar();
    if (idx < 0 || idx >= 10)
    {
        puts("Invalid idx!");
        return;
    }
    if (chunk[idx].ptr == NULL)
    {
        puts("No such letter!");
        return ;
    }
    printf("%s", chunk[idx].ptr);
}

void move()
{
    int idx1;
    int idx2;
    char *ptr;
    int ret;
    size_t size;

    if (strcmp(auth, "rf"))
    {
        exit(0);
    }

    printf("idx1 >> ");
    scanf("%d", &idx1);
    getchar();
    printf("idx2 >> ");
    scanf("%d", &idx2);
    getchar();
    if (idx1 < 0 || idx1 >= 10 || idx2 < 0 || idx2 >= 10)
    {
        puts("Invalid idx!");
        return;
    }
    if (chunk[idx1].ptr == NULL)
    {
        puts("No such letter!");
        return;
    }

    chunk[idx2] = chunk[idx1];
    size = malloc_usable_size(chunk[idx1].ptr);
    ptr = malloc(size);

    ret = snprintf(ptr, size, "%s", chunk[idx1].ptr);
    if (ret == 0)
    {
        puts("move failed!");
        return;
    }

    chunk[idx2].ptr = ptr;
    chunk[idx1].ptr = NULL;
    chunk[idx1].size = 0;
}

int main()
{
    init();
    int choice;
    char ch;
    while (1)
    {
        puts("--- New letter System ---");
        puts("1. Add a letter");
        puts("2. Delete a letter");
        puts("3. Edit the letter");
        puts("4. Show the letter");
        puts("5. Sort letters");
        puts("6. Exit");
        printf("Choice >> ");
        scanf("%d",&choice);
        ch=getchar();
        switch (choice)
        {
            case 1:
                add();
                break;
            case 2:
                delete();
                break;
            case 3:
                edit();
                break;
            case 4:
                show();
                break;
            case 5:
                sort_chunk();
                break;
            case 6:
                puts("Bye!");
                exit(0);
            case 7:
                move();
                break;
            default:
                puts("Invalid choice!");
                break;
        }
    }
}
