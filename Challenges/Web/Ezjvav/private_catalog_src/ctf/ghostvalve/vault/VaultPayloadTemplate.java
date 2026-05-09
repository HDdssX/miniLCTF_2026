package ctf.ghostvalve.vault;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class __CLASS_NAME__ {
    private static volatile byte[] __FIELD_NAME__;
    private static final long A = __LANE__L;
    private static final int B = __FOLD__;

    private __CLASS_NAME__() {
    }

    public static String __METHOD_NAME__(String noteKey) {
        String clean = normalize(noteKey);
        if (clean == null) {
            return queue();
        }
        byte[] source = __FIELD_NAME__;
        if (source == null) {
            return queue();
        }
        byte[] decoded = new byte[source.length];
        long mixed = mix(clean);
        int base = (int) (mixed ^ (mixed >>> 32)) ^ B;
        for (int index = 0; index < source.length; index++) {
            int mask = Integer.rotateLeft(base + index * 0x45d9f3b, (index & 7) + 1);
            mask ^= clean.charAt(index % clean.length());
            mask ^= index * 0x27d4eb2d;
            decoded[index] = (byte) (((source[index] & 0xff) ^ mask) & 0xff);
        }
        return new String(decoded, StandardCharsets.UTF_8);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("^[a-f0-9]{16,64}$") ? normalized : null;
    }

    private static long mix(String noteKey) {
        long value = A ^ 0x9e3779b97f4a7c15L;
        for (int index = 0; index < noteKey.length(); index++) {
            value ^= (long) noteKey.charAt(index) << ((index & 7) * 8);
            value = Long.rotateLeft(value * 0x94d049bb133111ebL, 11);
        }
        return value;
    }

    private static String queue() {
        return word(113, 117, 101, 117, 101, 100);
    }

    private static String word(int... values) {
        StringBuilder builder = new StringBuilder(values.length);
        for (int value : values) {
            builder.append((char) value);
        }
        return builder.toString();
    }
}
