package ctf.ghostvalve.vault;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public final class __CLASS_NAME__ extends VaultMain.B {
    public __CLASS_NAME__() {
    }

    public Object a(int lane, String[] values) {
        if (lane == 0) {
            return buildReview(values);
        }
        if (lane == 1) {
            return Boolean.valueOf(checkInstall(values));
        }
        return null;
    }

    private static String buildReview(String[] values) {
        if (values == null || values.length < 4) {
            return null;
        }
        String handle = cleanHex(values[0]);
        String payload = text(values[1]).toLowerCase(Locale.ROOT);
        String headline = text(values[2]);
        String accent = text(values[3]).toLowerCase(Locale.ROOT);
        if (handle == null) {
            return null;
        }
        int split = payload.indexOf(glue());
        if (split <= 0 || split >= payload.length() - 1) {
            return null;
        }
        String token = cleanHex(payload.substring(0, split));
        String client = cleanHex(payload.substring(split + 1));
        if (token == null || client == null) {
            return null;
        }
        String headlineTail = tail(handle, 4) + head(token, 4);
        String accentTail = flag() + head(client, 3) + head(handle, 3);
        if (!headline.endsWith(headlineTail) || !accentTail.equals(accent)) {
            return null;
        }
        return slice(sha(chain(stitch(114, 101, 118, 105, 101, 119), head(handle, 3), tail(handle, 4), client, token)), 0, 24);
    }

    private static boolean checkInstall(String[] values) {
        if (values == null || values.length < 6) {
            return false;
        }
        String client = cleanHex(values[0]);
        String token = cleanHex(values[1]);
        String flow = cleanHex(values[2]);
        String marker = cleanHex(values[3]);
        String digest = cleanHex(values[4]);
        String proof = cleanHex(values[5]);
        if (client == null || token == null || flow == null || marker == null || digest == null || proof == null) {
            return false;
        }
        String expected = slice(sha(chain(client, token, flip(flow), marker, digest)), 8, 40);
        return expected.equals(proof);
    }

    private static String chain(String first, String second, String third, String fourth, String fifth) {
        String cut = glue();
        return text(first) + cut + text(second) + cut + text(third) + cut + text(fourth) + cut + text(fifth);
    }

    private static String flag() {
        return stitch(35);
    }

    private static String glue() {
        return stitch(58);
    }

    private static String head(String value, int count) {
        return value.substring(0, Math.min(count, value.length()));
    }

    private static String tail(String value, int count) {
        return value.substring(Math.max(0, value.length() - count));
    }

    private static String flip(String value) {
        return new StringBuilder(text(value)).reverse().toString();
    }

    private static String cleanHex(String value) {
        String normalized = text(value).toLowerCase(Locale.ROOT);
        return normalized.matches("^[a-f0-9]{16,64}$") ? normalized : null;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String slice(String value, int start, int end) {
        if (value == null) {
            return null;
        }
        int from = Math.max(0, Math.min(start, value.length()));
        int to = Math.max(from, Math.min(end, value.length()));
        return value.substring(from, to);
    }

    private static String stitch(int... values) {
        StringBuilder builder = new StringBuilder(values.length + 1);
        for (int current : values) {
            builder.append((char) current);
        }
        return builder.toString();
    }

    private static String sha(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance(stitch(83, 72, 65, 45, 50, 53, 54));
            byte[] raw = digest.digest(text(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(raw.length * 2);
            for (byte current : raw) {
                int unsigned = current & 0xff;
                if (unsigned < 0x10) {
                    builder.append('0');
                }
                builder.append(Integer.toHexString(unsigned));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(stitch(115, 104, 97, 50, 53, 54), e);
        }
    }
}
