package ctf.ghostvalve.theme;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

public final class ThemePaths {
    private static final String DEFAULT_ROOT = "/opt/ghost/data/themes";
    private static final String ID_REGEX = "^[a-f0-9]{16}$";

    private ThemePaths() {
    }

    public static Path storageRoot() {
        String property = System.getProperty("ghost.theme.root");
        if (property != null && !property.trim().isEmpty()) {
            return Paths.get(property.trim());
        }

        String env = System.getenv("GHOST_THEME_ROOT");
        if (env != null && !env.trim().isEmpty()) {
            return Paths.get(env.trim());
        }

        return Paths.get(DEFAULT_ROOT);
    }

    public static void ensureRoot() throws IOException {
        Files.createDirectories(storageRoot());
    }

    public static String normalizeThemeId(String themeId) {
        String normalized = themeId == null ? "" : themeId.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches(ID_REGEX)) {
            throw new IllegalArgumentException("Invalid theme id");
        }
        return normalized;
    }

    public static Path themeRoot(String themeId) {
        return storageRoot().resolve(normalizeThemeId(themeId)).normalize();
    }
}
