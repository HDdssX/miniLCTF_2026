package ctf.ghostvalve.bridge;

import java.nio.file.Path;
import javax.servlet.ServletContext;

public final class BridgeRuntime {
    private static final ThreadLocal<ServletContext> CURRENT = new ThreadLocal<ServletContext>();
    private static final ThreadLocal<Path> CURRENT_THEME_ROOT = new ThreadLocal<Path>();

    private BridgeRuntime() {
    }

    public static void enter(ServletContext context, Path themeRoot) {
        CURRENT.set(context);
        CURRENT_THEME_ROOT.set(themeRoot);
    }

    public static void exit() {
        CURRENT.remove();
        CURRENT_THEME_ROOT.remove();
    }

    public static ServletContext current() {
        return CURRENT.get();
    }

    public static Path currentThemeRoot() {
        return CURRENT_THEME_ROOT.get();
    }
}
