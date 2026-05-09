package ctf.ghostvalve.market;

import ctf.ghostvalve.bridge.ThemeAssemblyBootstrap;
import java.nio.file.Path;
import javax.servlet.ServletContext;

public final class EntryView {
    private EntryView() {
    }

    public static void enter(ServletContext context, Path siteRoot, String previewHandle, String reviewToken, String clientKey) throws Exception {
        String cleanStage = requireHex(previewHandle, "stage");
        String cleanToken = requireHex(reviewToken, "token");
        String cleanClient = requireHex(clientKey, "client");
        if (context == null || siteRoot == null) {
            throw new IllegalArgumentException("site");
        }
        ThemeAssemblyBootstrap.openTrustedStage(context, siteRoot, cleanStage, cleanToken, cleanClient);
    }

    private static String requireHex(String value, String label) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("^[a-f0-9]{16,64}$")) {
            throw new IllegalArgumentException(label);
        }
        return normalized;
    }
}
