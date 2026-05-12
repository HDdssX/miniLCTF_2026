#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define BAG 8
#define STAGES 5

#define SMALL 1
#define BIG 2
#define ETHER 3
#define SWORD 4
#define STAFF 5
#define ARMOR 6
#define RING 8
#define KEY 9
#define GEM 10

typedef struct {
    const char *name;
    int id;
    int price;
    int sell;
    int once;
    int atk;
    int mag;
    int hp;
    int mp;
} Item;

typedef struct {
    const char *name;
    int hp;
    int atk;
    int gold;
} Mob;

static char arena_pad[0x1000];


static struct {
    int gold;
    int atk;
    int mag;
    int max_hp;
    int max_mp;
    int note;
    int banner_len;
    int stage;
    int bag[BAG];
    int bag_cnt;
    unsigned own;
    char pad[0x100];

} g;

static const Item shop[] = {
    {"Small Potion", SMALL, 20, 10, 0, 0, 0, 0, 0},
    {"Ether", ETHER, 25, 12, 0, 0, 0, 0, 0},
    {"Iron Sword", SWORD, 60, 30, 1, 6, 0, 0, 0},
    {"Sage Staff", STAFF, 60, 30, 1, 0, 8, 0, 0},
    {"Chain Armor", ARMOR, 70, 35, 1, 0, 0, 30, 0},
    {"Moon Ring", RING, 70, 35, 1, 0, 0, 0, 15},
    {"Old Key", KEY, 18, 9, 0, 0, 0, 0, 0},
    {"Blue Gem", GEM, 22, 11, 0, 0, 0, 0, 0},
};

static const Item big_potion = {"Big Potion", BIG, 0, 35, 0, 0, 0, 0, 0};

static const Mob mobs[STAGES] = {
    {"Slime", 18, 4, 50},
    {"Wolf", 42, 7, 60},
    {"Knight", 78, 11, 80},
    {"Warlock", 120, 15, 100},
    {"Dragon", 420, 70, 150},
};

static int read_int(void) {
    char buf[64];
    if (!fgets(buf, sizeof(buf), stdin)) exit(0);
    return (int)strtol(buf, NULL, 10);
}

static const Item *get_item(int id) {
    size_t i;
    for (i = 0; i < sizeof(shop) / sizeof(shop[0]); i++) {
        if (shop[i].id == id) return &shop[i];
    }
    if (id == BIG) return &big_potion;
    return NULL;
}

static void show_bag(void) {
    int i;
    printf("Gold:%d  Atk:%d  Mag:%d  HP:%d  MP:%d\n",
           g.gold, g.atk, g.mag, g.max_hp, g.max_mp);
    for (i = 0; i < BAG; i++) {
        const Item *it = get_item(g.bag[i]);
        printf("[%d] %s\n", i, it ? it->name : "Empty");
    }
}

static void add_bonus(const Item *it, int sign) {
    g.atk += it->atk * sign;
    g.mag += it->mag * sign;
    g.max_hp += it->hp * sign;
    g.max_mp += it->mp * sign;
}

static void *brew(void *arg) {
    int i = (int)(intptr_t)arg;
    if (i < 0 || i >= BAG) return NULL;
    if (g.bag[i] != SMALL) return NULL;
    puts("[brew] ...");
    sleep(2);
    g.bag[i] = 0;
    g.bag[i] = BIG;
    puts("[brew] done");
    return NULL;
}

static void buy_item(void) {
    int c;
    const Item *it;
    unsigned bit;

    puts("\n1.Small Potion  20");
    puts("2.Ether         25");
    puts("3.Iron Sword    60");
    puts("4.Sage Staff    60");
    puts("5.Chain Armor   70");
    puts("6.Moon Ring     70");
    puts("7.Old Key       18");
    puts("8.Blue Gem      22");
    show_bag();
    printf("> ");
    c = read_int();
    if (c < 1 || c > 8) return;
    it = &shop[c - 1];
    if (g.bag_cnt >= BAG || g.gold < it->price) return;
    bit = 1u << c;
    if (it->once && (g.own & bit)) return;
    g.gold -= it->price;
    g.bag[g.bag_cnt] = it->id;
    g.bag_cnt++;
    if (it->once) {
        g.own |= bit;
        add_bonus(it, 1);
    }
}

static void sell_item(void) {
    int i;
    const Item *it;

    show_bag();
    puts("slot?");
    printf("> ");
    i = read_int();
    if (i < 0 || i > g.bag_cnt) return;
    it = get_item(g.bag[i]);
    if (!it) return;
    if (it->once) add_bonus(it, -1);
    g.gold += it->sell;
    g.bag[i] = 0;
    g.bag_cnt--;
}

static void upgrade_item(void) {
    pthread_t t;
    int i;

    show_bag();
    puts("small potion slot?");
    printf("> ");
    i = read_int();
    if (pthread_create(&t, NULL, brew, (void *)(intptr_t)i) == 0) {
        pthread_detach(t);
    }
}

static void use_item(int *hp, int *mp) {
    int i;
    puts("slot?");
    printf("> ");
    i = read_int();
    if (i < 0 || i >= BAG) return;
    if (g.bag[i] == SMALL) {
        *hp += 25;
        if (*hp > g.max_hp) *hp = g.max_hp;
    } else if (g.bag[i] == BIG) {
        *hp += 60;
        if (*hp > g.max_hp) *hp = g.max_hp;
    } else if (g.bag[i] == ETHER) {
        *mp += 20;
        if (*mp > g.max_mp) *mp = g.max_mp;
    } else {
        return;
    }
    g.bag[i] = 0;
    g.bag_cnt--;
}

static void battle(void) {
    int s = g.stage + 1;
    int hp, mp, ehp, c;
    const Mob *m;
    char board[0x40] = "challenger rank";
    size_t n = g.banner_len;

    puts("\nArena board:");
    write(1, board, n);
    puts("");

    if (s > STAGES) {
        puts("all clear");
        return;
    }
    m = &mobs[s - 1];
    hp = g.max_hp;
    mp = g.max_mp;
    ehp = m->hp;
    printf("\nStage %d: %s\n", s, m->name);

    while (hp > 0 && ehp > 0) {
        printf("HP:%d/%d MP:%d/%d Enemy:%d\n", hp, g.max_hp, mp, g.max_mp, ehp);
        puts("1.attack 2.cast 3.item 4.run");
        printf("> ");
        c = read_int();
        if (c == 1) ehp -= g.atk;
        else if (c == 2 && mp >= 8) {
            mp -= 8;
            ehp -= 12 + g.mag;
        } else if (c == 3) {
            use_item(&hp, &mp);
        } else if (c == 4) {
            return;
        }
        if (ehp > 0) hp -= m->atk;
    }

    if (hp <= 0) exit(0);
    g.stage++;
    g.gold += m->gold;
}

static void shop_menu(void) {
    for (;;) {
        puts("\n1.buy 2.sell 3.upgrade 4.back");
        printf("> ");
        switch (read_int()) {
            case 1: buy_item(); break;
            case 2: sell_item(); break;
            case 3: upgrade_item(); break;
            case 4: return;
            default: break;
        }
    }
}

__attribute__((noinline)) static void quit_game(void) {
    volatile char buf[0x40];

    puts("bye:");
    read(0, (void *)buf, 0x20 + g.stage * 4);
}

static void game(void) {
    char name[0x40];
    ssize_t r;

    for (;;) {
        printf("\nGold:%d Next:%d\n", g.gold, g.stage + 1);
        puts("1.battle 2.shop 3.name 4.exit");
        printf("> ");
        switch (read_int()) {
            case 1: battle(); break;
            case 2: shop_menu(); break;
            case 3:
                memset(name, 0, sizeof(name));
                puts("name:");
                r = read(0, name, sizeof(name));
                if (r > 0 && name[r - 1] == '\n') name[r - 1] = 0;
                if (r > 0) printf("Let's battle, %s\n", name);
                break;
            case 4: quit_game(); break;
            default: break;
        }
    }
}

int main(void) {
    (void)arena_pad;

    setvbuf(stdin, NULL, _IONBF, 0);
    setvbuf(stdout, NULL, _IONBF, 0);
    alarm(120);

    g.gold = 0;
    g.atk = 10;
    g.mag = 6;
    g.max_hp = 100;
    g.max_mp = 30;
    g.banner_len = 0x40;
    g.stage = 0;

    puts("Tiny Arena");
    game();
    return 0;
}
