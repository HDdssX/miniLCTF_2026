package ctf.ghostvalve.theme;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ThemeCatalog {
    private static final ConcurrentMap<String, List<Entry>> SNAPSHOTS = new ConcurrentHashMap<String, List<Entry>>();

    private ThemeCatalog() {
    }

    public static void write(Path themeRoot) throws IOException {
        List<String> paths = new ArrayList<String>();
        Path manifest = themeRoot.resolve("manifest.json").normalize();
        if (Files.isRegularFile(manifest)) {
            paths.add("manifest.json");
        }
        collect(paths, themeRoot, themeRoot.resolve("assets"));
        collect(paths, themeRoot, themeRoot.resolve("templates"));
        Collections.sort(paths);

        List<Entry> entries = new ArrayList<Entry>();
        for (String path : paths) {
            Path file = resolve(themeRoot, path);
            if (file != null && Files.isRegularFile(file)) {
                entries.add(new Entry(path, Files.size(file), sha256(file)));
            }
        }
        SNAPSHOTS.put(snapshotKey(themeRoot), Collections.unmodifiableList(entries));
    }

    public static byte[] readVerified(Path themeRoot, String relativePath) throws IOException {
        Entry entry = find(themeRoot, relativePath);
        if (entry == null || !entry.matches(themeRoot)) {
            return null;
        }
        Path file = resolve(themeRoot, entry.path);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        return Files.readAllBytes(file);
    }

    public static List<String> verifiedPaths(Path themeRoot) throws IOException {
        List<String> paths = new ArrayList<String>();
        for (Entry entry : snapshot(themeRoot)) {
            if (entry.matches(themeRoot)) {
                paths.add(entry.path);
            }
        }
        Collections.sort(paths);
        return paths;
    }

    public static String bundleDigest(Path themeRoot) throws IOException {
        StringBuilder builder = new StringBuilder();
        for (Entry entry : snapshot(themeRoot)) {
            if (entry.matches(themeRoot)) {
                builder.append(entry.path).append('\n');
                builder.append(entry.size).append('\n');
                builder.append(entry.digest).append('\n');
            }
        }
        return sha256(builder.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 32);
    }

    private static void collect(List<String> paths, Path themeRoot, Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            List<Path> files = walk
                .filter(Files::isRegularFile)
                .collect(Collectors.toList());
            for (Path file : files) {
                String relative = themeRoot.relativize(file).toString().replace('\\', '/');
                if (isPublicPath(relative)) {
                    paths.add(relative);
                }
            }
        }
    }

    private static Entry find(Path themeRoot, String relativePath) throws IOException {
        String normalized = normalizeRelative(relativePath);
        if (normalized == null || !isPublicPath(normalized)) {
            return null;
        }
        for (Entry entry : snapshot(themeRoot)) {
            if (entry.path.equals(normalized)) {
                return entry;
            }
        }
        return null;
    }

    private static List<Entry> snapshot(Path themeRoot) {
        List<Entry> entries = SNAPSHOTS.get(snapshotKey(themeRoot));
        return entries != null ? entries : Collections.<Entry>emptyList();
    }

    private static String snapshotKey(Path themeRoot) {
        return themeRoot.toAbsolutePath().normalize().toString();
    }

    private static Path resolve(Path themeRoot, String relativePath) {
        String normalized = normalizeRelative(relativePath);
        if (normalized == null) {
            return null;
        }
        Path file = themeRoot.resolve(normalized).normalize();
        return file.startsWith(themeRoot) ? file : null;
    }

    private static String normalizeRelative(String relativePath) {
        if (relativePath == null || relativePath.isEmpty() || relativePath.startsWith("/")) {
            return null;
        }
        if (relativePath.indexOf('\\') >= 0 || relativePath.contains("..")) {
            return null;
        }
        return relativePath.replace('\\', '/');
    }

    private static boolean isPublicPath(String path) {
        return "manifest.json".equals(path) || path.startsWith("assets/") || path.startsWith("templates/");
    }

    private static String sha256(Path file) throws IOException {
        return sha256(Files.readAllBytes(file));
    }

    private static String sha256(byte[] data) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] raw) {
        StringBuilder builder = new StringBuilder(raw.length * 2);
        for (byte current : raw) {
            int unsigned = current & 0xff;
            if (unsigned < 0x10) {
                builder.append('0');
            }
            builder.append(Integer.toHexString(unsigned));
        }
        return builder.toString();
    }

    private static final class Entry {
        private final String path;
        private final long size;
        private final String digest;

        private Entry(String path, long size, String digest) {
            this.path = path;
            this.size = size;
            this.digest = digest;
        }

        private boolean matches(Path themeRoot) throws IOException {
            Path file = resolve(themeRoot, path);
            return file != null && Files.isRegularFile(file) && Files.size(file) == size && sha256(file).equals(digest);
        }
    }
}
