package ctf.ghostvalve.market;

import ctf.ghostvalve.bridge.ThemeAssemblyBootstrap;
import ctf.ghostvalve.theme.JsonUtil;
import ctf.ghostvalve.theme.PathUtil;
import ctf.ghostvalve.theme.ThemeCatalog;
import ctf.ghostvalve.theme.ThemeManifest;
import ctf.ghostvalve.theme.ThemePaths;
import ctf.ghostvalve.theme.ThemeValveTicket;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class SiteWorkspaceService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_ENTRIES = 64;
    private static final long MAX_TOTAL_BYTES = 1_048_576L;

    public WorkspaceTicket openWorkspace(String contextPath) throws IOException {
        String siteId = nextSiteId();
        Path siteRoot = ThemePaths.themeRoot(siteId);
        Files.createDirectories(siteRoot.resolve("assets"));
        Files.createDirectories(siteRoot.resolve("templates"));
        Files.createDirectories(siteRoot.resolve("preview"));
        return WorkspaceTicket.open(siteId, contextPath);
    }

    public WorkspaceTicket publishMaterials(String siteId, InputStream archive, String contextPath) throws IOException {
        String normalizedSiteId = ThemePaths.normalizeThemeId(siteId);
        Path siteRoot = ThemePaths.themeRoot(normalizedSiteId);
        if (!Files.isDirectory(siteRoot)) {
            throw new IllegalArgumentException("Unknown workspace");
        }
        if (Files.exists(siteRoot.resolve("manifest.json"))) {
            throw new IOException("Workspace already contains a published package");
        }

        resetWorkspace(siteRoot);
        try {
            importZip(archive, siteRoot);
            Path manifestPath = siteRoot.resolve("manifest.json");
            if (!Files.exists(manifestPath)) {
                throw new IOException("manifest.json is required");
            }
            ThemeManifest.fromJson(new String(Files.readAllBytes(manifestPath), StandardCharsets.UTF_8));
            ThemeCatalog.write(siteRoot);
            ThemeValveTicket.create(siteRoot);
            ThemeAssemblyBootstrap.preparePreviewPlan(siteRoot);
        } catch (IOException e) {
            resetWorkspace(siteRoot);
            throw e;
        } catch (RuntimeException e) {
            resetWorkspace(siteRoot);
            throw new IOException(e.getMessage(), e);
        }

        return WorkspaceTicket.ready(normalizedSiteId, contextPath);
    }

    private static void resetWorkspace(Path siteRoot) throws IOException {
        Files.deleteIfExists(siteRoot.resolve("manifest.json"));
        PathUtil.deleteTree(siteRoot.resolve("assets"));
        PathUtil.deleteTree(siteRoot.resolve("templates"));
        PathUtil.deleteTree(siteRoot.resolve("preview"));
        Files.createDirectories(siteRoot.resolve("assets"));
        Files.createDirectories(siteRoot.resolve("templates"));
        Files.createDirectories(siteRoot.resolve("preview"));
    }

    private static void importZip(InputStream input, Path siteRoot) throws IOException {
        int entries = 0;
        long totalBytes = 0L;

        try (ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                entries++;
                if (entries > MAX_ENTRIES) {
                    throw new IOException("Too many archive entries");
                }

                String actualPath = decodeOnce(entry.getName());
                if (hasResidualPathEncoding(actualPath)) {
                    throw new IOException("Archive entry contains encoded path syntax");
                }
                Path target = siteRoot.resolve(actualPath).normalize();
                if (!target.startsWith(siteRoot)) {
                    throw new IOException("Resolved path escapes workspace");
                }
                String checkedPath = siteRoot.relativize(target).toString().replace('\\', '/');
                if (!isContractPath(checkedPath)) {
                    throw new IOException("Archive entry violates contract: " + checkedPath);
                }

                Files.createDirectories(target.getParent());
                long written = Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                totalBytes += written;
                if (totalBytes > MAX_TOTAL_BYTES) {
                    throw new IOException("Archive too large");
                }
            }
        }
    }

    private static String decodeOnce(String value) throws IOException {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (IllegalArgumentException e) {
            throw new IOException("Malformed encoded path");
        }
    }

    private static boolean hasResidualPathEncoding(String value) {
        String lowered = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return lowered.contains("%2e") || lowered.contains("%2f") || lowered.contains("%5c");
    }

    private static boolean isContractPath(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        if (value.indexOf('\\') >= 0 || value.startsWith("/") || value.contains("..")) {
            return false;
        }
        return "manifest.json".equals(value)
            || value.startsWith("assets/")
            || value.startsWith("templates/")
            || "preview/ThemeHook.java".equals(value)
            || "preview/ReviewAdapter.java".equals(value);
    }

    private static String nextSiteId() throws IOException {
        for (int attempt = 0; attempt < 8; attempt++) {
            byte[] bytes = new byte[8];
            RANDOM.nextBytes(bytes);
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                int unsigned = current & 0xff;
                if (unsigned < 0x10) {
                    builder.append('0');
                }
                builder.append(Integer.toHexString(unsigned));
            }
            String candidate = builder.toString();
            if (!Files.exists(ThemePaths.themeRoot(candidate))) {
                return candidate;
            }
        }
        throw new IOException("Unable to allocate workspace");
    }

    public static final class WorkspaceTicket {
        private final String siteId;
        private final String uploadUrl;
        private final String previewUrl;
        private final String exportUrl;
        private final String state;

        private WorkspaceTicket(String siteId, String uploadUrl, String previewUrl, String exportUrl, String state) {
            this.siteId = siteId;
            this.uploadUrl = uploadUrl;
            this.previewUrl = previewUrl;
            this.exportUrl = exportUrl;
            this.state = state;
        }

        private static WorkspaceTicket open(String siteId, String contextPath) {
            return new WorkspaceTicket(
                siteId,
                contextPath + "/api/studio/workspaces/" + siteId + "/materials",
                contextPath + "/site/" + siteId + "/preview",
                contextPath + "/site/" + siteId + "/export",
                "workspace-open"
            );
        }

        private static WorkspaceTicket ready(String siteId, String contextPath) {
            return new WorkspaceTicket(
                siteId,
                contextPath + "/api/studio/workspaces/" + siteId + "/materials",
                contextPath + "/site/" + siteId + "/preview",
                contextPath + "/site/" + siteId + "/export",
                "materials-staged"
            );
        }

        public String toJson() {
            return "{\"siteId\":\"" + JsonUtil.escape(siteId)
                + "\",\"uploadUrl\":\"" + JsonUtil.escape(uploadUrl)
                + "\",\"previewUrl\":\"" + JsonUtil.escape(previewUrl)
                + "\",\"exportUrl\":\"" + JsonUtil.escape(exportUrl)
                + "\",\"state\":\"" + JsonUtil.escape(state)
                + "\"}";
        }
    }
}
