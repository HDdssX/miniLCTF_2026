package ctf.ghostvalve.vault;

public final class __CLASS_NAME__ {
    private __CLASS_NAME__() {
    }

    public static Object __METHOD_NAME__(int lane, String[] values) {
        if (lane == __PREVIEW_ROUTE__) {
            return VaultMain.__DISPATCH_METHOD__(0, values);
        }
        if (lane == __INSTALL_ROUTE__) {
            return VaultMain.__DISPATCH_METHOD__(1, values);
        }
        return null;
    }
}
