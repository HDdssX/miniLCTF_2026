package ctf.ghostvalve.theme;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ThemeValveTicket {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String REAL_FILE_NAME = ".preview-note";
    private static final String LEGACY_FILE_NAME = ".preview-valve-ticket";
    private static final ConcurrentMap<String, String> TICKETS = new ConcurrentHashMap<String, String>();

    private ThemeValveTicket() {
    }

    public static String create(Path themeRoot) throws IOException {
        Path file = ticketFile(themeRoot);
        Path legacy = legacyTicketFile(themeRoot);
        Files.createDirectories(file.getParent());
        String value = nextTicket();
        TICKETS.put(key(themeRoot), value);
        Files.write(legacy, nextTicket().getBytes(StandardCharsets.UTF_8));
        return value;
    }

    public static String read(Path themeRoot) throws IOException {
        String value = TICKETS.get(key(themeRoot));
        if (value == null || value.trim().isEmpty()) {
            throw new IOException("ticket");
        }
        return value.trim();
    }

    public static Path ticketFile(Path themeRoot) {
        return themeRoot.resolve("preview").resolve(REAL_FILE_NAME).normalize();
    }

    public static Path legacyTicketFile(Path themeRoot) {
        return themeRoot.resolve("preview").resolve(LEGACY_FILE_NAME).normalize();
    }

    private static String nextTicket() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte current : bytes) {
            int unsigned = current & 0xff;
            if (unsigned < 0x10) {
                builder.append('0');
            }
            builder.append(Integer.toHexString(unsigned));
        }
        return builder.toString();
    }

    private static String key(Path themeRoot) {
        return themeRoot.toAbsolutePath().normalize().toString();
    }
}
