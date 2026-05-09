package ctf.ghostvalve.market;

import ctf.ghostvalve.bridge.ThemeAssemblyBootstrap;
import ctf.ghostvalve.theme.ThemeCatalog;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import javax.servlet.ServletContext;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class SiteDeliveryService {
    public ExportPackage prepareExport(String siteId, Path siteRoot) throws IOException {
        String index = ThemeAssemblyBootstrap.sealBundle(siteRoot, ThemeCatalog.bundleDigest(siteRoot));
        List<String> paths = ThemeCatalog.verifiedPaths(siteRoot);
        return new ExportPackage(siteId + ".zip", index, siteRoot, paths);
    }

    public AssetPayload openAsset(Path siteRoot, String requestedPath, ServletContext servletContext) throws IOException {
        if (requestedPath == null || requestedPath.trim().isEmpty()) {
            return null;
        }

        String decoded = URLDecoder.decode(requestedPath, "UTF-8");
        if (!decoded.startsWith("assets/") || decoded.contains("..") || decoded.contains("\\")) {
            return null;
        }

        byte[] assetBytes = ThemeCatalog.readVerified(siteRoot, decoded);
        if (assetBytes == null) {
            return null;
        }

        String mimeType = servletContext.getMimeType(Paths.get(decoded).getFileName().toString());
        return new AssetPayload(assetBytes, mimeType != null ? mimeType : "application/octet-stream");
    }

    public static final class ExportPackage {
        private final String fileName;
        private final String index;
        private final Path siteRoot;
        private final List<String> paths;

        private ExportPackage(String fileName, String index, Path siteRoot, List<String> paths) {
            this.fileName = fileName;
            this.index = index;
            this.siteRoot = siteRoot;
            this.paths = paths;
        }

        public String getFileName() {
            return fileName;
        }

        public String getIndex() {
            return index;
        }

        public void writeTo(ZipOutputStream zip) throws IOException {
            for (String path : paths) {
                byte[] bytes = ThemeCatalog.readVerified(siteRoot, path);
                if (bytes == null) {
                    continue;
                }
                zip.putNextEntry(new ZipEntry(path));
                zip.write(bytes);
                zip.closeEntry();
            }
        }
    }

    public static final class AssetPayload {
        private final byte[] bytes;
        private final String mimeType;

        private AssetPayload(byte[] bytes, String mimeType) {
            this.bytes = bytes;
            this.mimeType = mimeType;
        }

        public byte[] getBytes() {
            return bytes;
        }

        public String getMimeType() {
            return mimeType;
        }
    }
}
