#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define BRIDGE_TAG_LEN __BRIDGE_TAG_LEN__
#define PACK_TAG_LEN __PACK_TAG_LEN__
#define BRIDGE_SALT __BRIDGE_SALT__
#define PACK_SALT __PACK_SALT__
#define PACK_MAGIC __PACK_MAGIC_C__
#define PACK_VERSION __PACK_VERSION_C__
#define PACK_MIN_ITEMS __PACK_MIN_ITEMS_C__
#define PACK_MAX_ITEMS __PACK_MAX_ITEMS_C__
#define BRIDGE_SEED_BASE __BRIDGE_SEED_BASE_C__
#define BRIDGE_SEED_MUL __BRIDGE_SEED_MUL_C__
#define BRIDGE_SEED_ROT __BRIDGE_SEED_ROT__
#define BRIDGE_SEED_XOR __BRIDGE_SEED_XOR_C__
#define BRIDGE_INDEX_MUL __BRIDGE_INDEX_MUL_C__
#define BRIDGE_ROT_MASK __BRIDGE_ROT_MASK__
#define BRIDGE_ROT_BASE __BRIDGE_ROT_BASE__
#define BRIDGE_TAIL_MUL __BRIDGE_TAIL_MUL_C__
#define PACK_SEED_BASE __PACK_SEED_BASE_C__
#define PACK_SEED_MUL __PACK_SEED_MUL_C__
#define PACK_SEED_ROT __PACK_SEED_ROT__
#define PACK_SEED_XOR __PACK_SEED_XOR_C__
#define PACK_INDEX_MUL __PACK_INDEX_MUL_C__
#define PACK_ROT_MASK __PACK_ROT_MASK__
#define PACK_ROT_BASE __PACK_ROT_BASE__
#define PACK_TAIL_MUL __PACK_TAIL_MUL_C__

static const uint8_t BRIDGE_TAG[BRIDGE_TAG_LEN] = { __BRIDGE_TAG_BYTES__ };
static const uint8_t PACK_TAG[PACK_TAG_LEN] = { __PACK_TAG_BYTES__ };

static uint32_t rotl32(uint32_t value, unsigned bits) {
    return (value << bits) | (value >> (32u - bits));
}

static int read_all(uint8_t **buffer, size_t *length) {
    size_t capacity = 4096;
    size_t used = 0;
    uint8_t *data = (uint8_t *) malloc(capacity);
    if (data == NULL) {
        return 1;
    }
    while (!feof(stdin)) {
        if (used == capacity) {
            size_t next = capacity * 2;
            uint8_t *grown = (uint8_t *) realloc(data, next);
            if (grown == NULL) {
                free(data);
                return 1;
            }
            data = grown;
            capacity = next;
        }
        size_t count = fread(data + used, 1, capacity - used, stdin);
        used += count;
        if (ferror(stdin)) {
            free(data);
            return 1;
        }
    }
    *buffer = data;
    *length = used;
    return 0;
}

static int write_all(const uint8_t *buffer, size_t length) {
    size_t written = 0;
    while (written < length) {
        size_t count = fwrite(buffer + written, 1, length - written, stdout);
        if (count == 0) {
            return 1;
        }
        written += count;
    }
    return fflush(stdout);
}

static int has_prefix(const uint8_t *data, size_t length, const uint8_t *prefix, size_t prefix_length) {
    if (data == NULL || prefix == NULL || prefix_length == 0 || length < prefix_length) {
        return 0;
    }
    return memcmp(data, prefix, prefix_length) == 0;
}

static uint32_t bridge_seed(const uint8_t *salt) {
    uint32_t value = BRIDGE_SEED_BASE;
    size_t index;
    for (index = 0; index < BRIDGE_SALT; index++) {
        value ^= (uint32_t) (salt[index] & 0xFFu) << ((index & 3u) * 8u);
        value = rotl32(value * BRIDGE_SEED_MUL, BRIDGE_SEED_ROT) ^ BRIDGE_SEED_XOR;
    }
    return value;
}

static int decode_bridge(const uint8_t *encoded, size_t encoded_length, uint8_t **decoded, size_t *decoded_length) {
    size_t index;
    uint32_t seed;
    uint8_t *output;
    if (encoded == NULL || encoded_length <= BRIDGE_SALT + 4) {
        return 1;
    }
    seed = bridge_seed(encoded);
    *decoded_length = encoded_length - BRIDGE_SALT;
    output = (uint8_t *) malloc(*decoded_length);
    if (output == NULL) {
        return 1;
    }
    for (index = 0; index < *decoded_length; index++) {
        uint32_t value = seed ^ ((uint32_t) index * BRIDGE_INDEX_MUL);
        value = rotl32(value, (unsigned) ((index & BRIDGE_ROT_MASK) + BRIDGE_ROT_BASE));
        value ^= (uint32_t) (encoded[index % BRIDGE_SALT] & 0xFFu) << ((index & 1u) * 8u);
        value ^= (uint32_t) ((index >> 1u) * BRIDGE_TAIL_MUL);
        output[index] = (uint8_t) ((encoded[index + BRIDGE_SALT] ^ value) & 0xFFu);
    }
    if (*decoded_length <= 8
        || output[0] != 0xCAu
        || output[1] != 0xFEu
        || output[2] != 0xBAu
        || output[3] != 0xBEu) {
        free(output);
        return 1;
    }
    *decoded = output;
    return 0;
}

static uint32_t pack_seed(const uint8_t *salt) {
    uint32_t value = PACK_SEED_BASE;
    size_t index;
    for (index = 0; index < PACK_SALT; index++) {
        value ^= (uint32_t) (salt[index] & 0xFFu) << ((index & 3u) * 8u);
        value = rotl32(value * PACK_SEED_MUL, PACK_SEED_ROT) ^ PACK_SEED_XOR;
    }
    return value;
}

static uint8_t pack_mask(uint32_t seed, size_t index, const uint8_t *salt) {
    uint32_t value = seed + ((uint32_t) index * PACK_INDEX_MUL);
    value = rotl32(value, (unsigned) ((index & PACK_ROT_MASK) + PACK_ROT_BASE));
    value ^= (uint32_t) (salt[index % PACK_SALT] & 0xFFu) << ((index & 3u) * 8u);
    value ^= (uint32_t) ((index + 1u) * PACK_TAIL_MUL);
    return (uint8_t) (value & 0xFFu);
}

static int read_u8(const uint8_t *data, size_t length, size_t *offset, uint8_t *value) {
    if (*offset + 1 > length) {
        return 1;
    }
    *value = data[*offset];
    *offset += 1;
    return 0;
}

static int read_u16(const uint8_t *data, size_t length, size_t *offset, uint16_t *value) {
    if (*offset + 2 > length) {
        return 1;
    }
    *value = (uint16_t) (((uint16_t) data[*offset] << 8) | (uint16_t) data[*offset + 1]);
    *offset += 2;
    return 0;
}

static int read_u32(const uint8_t *data, size_t length, size_t *offset, uint32_t *value) {
    if (*offset + 4 > length) {
        return 1;
    }
    *value = ((uint32_t) data[*offset] << 24)
        | ((uint32_t) data[*offset + 1] << 16)
        | ((uint32_t) data[*offset + 2] << 8)
        | (uint32_t) data[*offset + 3];
    *offset += 4;
    return 0;
}

static int read_i32(const uint8_t *data, size_t length, size_t *offset, int32_t *value) {
    uint32_t raw;
    if (read_u32(data, length, offset, &raw) != 0) {
        return 1;
    }
    *value = (int32_t) raw;
    return 0;
}

static int skip_bytes(size_t length, size_t *offset, size_t count) {
    if (*offset + count > length) {
        return 1;
    }
    *offset += count;
    return 0;
}

static int validate_pack(const uint8_t *decoded, size_t decoded_length) {
    size_t offset = 0;
    uint32_t magic;
    uint16_t version;
    uint16_t count;
    uint16_t index;
    int real_count = 0;
    if (read_u32(decoded, decoded_length, &offset, &magic) != 0 || magic != PACK_MAGIC) {
        return 1;
    }
    if (read_u16(decoded, decoded_length, &offset, &version) != 0 || version != PACK_VERSION) {
        return 1;
    }
    if (read_u16(decoded, decoded_length, &offset, &count) != 0 || count < PACK_MIN_ITEMS || count > PACK_MAX_ITEMS) {
        return 1;
    }
    for (index = 0; index < count; index++) {
        uint8_t real = 0;
        uint16_t size = 0;
        int32_t class_size = 0;
        if (read_u8(decoded, decoded_length, &offset, &real) != 0) {
            return 1;
        }
        if (real) {
            real_count++;
        }
        if (read_u16(decoded, decoded_length, &offset, &size) != 0 || size == 0 || size > 512) {
            return 1;
        }
        if (skip_bytes(decoded_length, &offset, size) != 0) {
            return 1;
        }
        if (read_u16(decoded, decoded_length, &offset, &size) != 0 || size == 0 || size > 128) {
            return 1;
        }
        if (skip_bytes(decoded_length, &offset, size) != 0) {
            return 1;
        }
        if (read_u16(decoded, decoded_length, &offset, &size) != 0 || size == 0 || size > 128) {
            return 1;
        }
        if (skip_bytes(decoded_length, &offset, size) != 0) {
            return 1;
        }
        if (skip_bytes(decoded_length, &offset, 8) != 0) {
            return 1;
        }
        if (skip_bytes(decoded_length, &offset, 4) != 0) {
            return 1;
        }
        if (read_i32(decoded, decoded_length, &offset, &class_size) != 0 || class_size < 64 || class_size > 32768) {
            return 1;
        }
        if (skip_bytes(decoded_length, &offset, (size_t) class_size) != 0) {
            return 1;
        }
    }
    return real_count == 1 && offset == decoded_length ? 0 : 1;
}

static int decode_pack(const uint8_t *encoded, size_t encoded_length, uint8_t **decoded, size_t *decoded_length) {
    size_t index;
    uint32_t seed;
    uint8_t *output;
    if (encoded == NULL || encoded_length <= PACK_SALT + 16) {
        return 1;
    }
    seed = pack_seed(encoded);
    *decoded_length = encoded_length - PACK_SALT;
    output = (uint8_t *) malloc(*decoded_length);
    if (output == NULL) {
        return 1;
    }
    for (index = 0; index < *decoded_length; index++) {
        output[index] = (uint8_t) ((encoded[index + PACK_SALT] ^ pack_mask(seed, index, encoded)) & 0xFFu);
    }
    if (validate_pack(output, *decoded_length) != 0) {
        free(output);
        return 1;
    }
    *decoded = output;
    return 0;
}

int main(int argc, char **argv) {
    uint8_t *encoded = NULL;
    size_t encoded_length = 0;
    uint8_t *decoded = NULL;
    size_t decoded_length = 0;
    int result = 1;

    if (argc != 1) {
        return 1;
    }
    if (read_all(&encoded, &encoded_length) != 0) {
        return 1;
    }
    if (encoded_length == 0) {
        free(encoded);
        return 0;
    }
    if (has_prefix(encoded, encoded_length, BRIDGE_TAG, BRIDGE_TAG_LEN)) {
        result = decode_bridge(encoded + BRIDGE_TAG_LEN, encoded_length - BRIDGE_TAG_LEN, &decoded, &decoded_length);
    } else if (has_prefix(encoded, encoded_length, PACK_TAG, PACK_TAG_LEN)) {
        result = decode_pack(encoded + PACK_TAG_LEN, encoded_length - PACK_TAG_LEN, &decoded, &decoded_length);
    }
    if (result == 0) {
        result = write_all(decoded, decoded_length);
    }
    free(encoded);
    free(decoded);
    return result == 0 ? 0 : 1;
}
