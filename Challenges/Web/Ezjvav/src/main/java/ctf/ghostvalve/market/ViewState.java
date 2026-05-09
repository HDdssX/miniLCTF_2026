package ctf.ghostvalve.market;

import ctf.ghostvalve.bridge.BridgeRuntime;
import java.nio.file.Path;
import javax.servlet.ServletContext;

public final class ViewState {
    private ViewState() {
    }

    public static void enter(ServletContext context, Path themeRoot) {
        BridgeRuntime.enter(context, themeRoot);
    }

    public static void exit() {
        BridgeRuntime.exit();
    }

    public static ServletContext current() {
        return BridgeRuntime.current();
    }

    public static Path currentThemeRoot() {
        return BridgeRuntime.currentThemeRoot();
    }
}
