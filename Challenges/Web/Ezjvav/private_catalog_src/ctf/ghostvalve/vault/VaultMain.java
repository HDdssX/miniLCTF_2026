package ctf.ghostvalve.vault;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class VaultMain {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int PACK_MAGIC = __PACK_MAGIC__;
    private static final int PACK_VERSION = __PACK_VERSION__;
    private static final int PACK_MIN_ITEMS = __PACK_MIN_ITEMS__;
    private static final int PACK_MAX_ITEMS = __PACK_MAX_ITEMS__;
    private static final Object K = new Object();
    private static final Object H = new Object();
    private static volatile B U;
    private static volatile NativeHelper N;

    private VaultMain() {
    }

    public static void main(String[] args) throws Exception {
        byte[] secretBytes = null;
        try {
            String noteKey = readNoteKey(args);
            String bundleTicketSecret = readBundleTicketSecret(args);
            secretBytes = readSecretBytes(args.length > 0 ? args[0] : null);
            PackBlob pack = loadPack();
            List<RuntimeItem> items = materialize(pack, secretBytes, noteKey);
            Arrays.fill(secretBytes, (byte) 0);
            secretBytes = null;
            serve(items, noteKey, bundleTicketSecret, readPort(args));
        } finally {
            if (secretBytes != null) {
                Arrays.fill(secretBytes, (byte) 0);
            }
        }
    }

    static Object __BRIDGE_DISPATCH_METHOD__(int route, String[] values) {
        try {
            return x().a(route, values);
        } catch (Exception e) {
            return null;
        }
    }

    private static String readNoteKey(String[] args) {
        String seed = "memo-missing";
        if (args.length > 1 && args[1] != null && !args[1].trim().isEmpty()) {
            File file = new File(args[1].trim());
            if (file.isFile()) {
                try {
                    seed = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
                } catch (IOException ignored) {
                    seed = "memo-missing";
                } finally {
                    file.delete();
                }
            } else {
                seed = args[1].trim();
            }
        }
        if (seed == null || seed.isEmpty()) {
            seed = "memo-missing";
        }
        return sha256Hex(seed + ":memo").substring(0, 32);
    }

    private static B x() throws Exception {
        B current = U;
        if (current != null) {
            return current;
        }
        synchronized (K) {
            current = U;
            if (current != null) {
                return current;
            }
            final Y loader = new Y(VaultMain.class.getClassLoader());
            current = scanHiddenDecoded(new HiddenDecoder<B>() {
                @Override
                public B accept(byte[] decoded) throws Exception {
                    if (decoded == null || decoded.length <= 8) {
                        return null;
                    }
                    try {
                        Class<?> type = loader.q(decoded);
                        if (!B.class.isAssignableFrom(type)) {
                            return null;
                        }
                        return (B) type.getDeclaredConstructor().newInstance();
                    } catch (Throwable ignored) {
                        return null;
                    }
                }
            });
            if (current != null) {
                U = current;
                return current;
            }
            throw new IOException("bridge");
        }
    }

    private static String readBundleTicketSecret(String[] args) {
        String value = "bundle-ticket-missing";
        if (args.length > 2 && args[2] != null && !args[2].trim().isEmpty()) {
            File file = new File(args[2].trim());
            if (file.isFile()) {
                try {
                    value = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
                } catch (IOException ignored) {
                    value = "bundle-ticket-missing";
                }
            } else {
                value = args[2].trim();
            }
        }
        if (value == null || value.isEmpty()) {
            value = "bundle-ticket-missing";
        }
        return value;
    }

    private static byte[] readSecretBytes(String value) throws IOException {
        if (value == null || value.trim().isEmpty()) {
            return placeholderStage().getBytes(StandardCharsets.UTF_8);
        }
        File file = new File(value.trim());
        if (!file.isFile()) {
            return value.trim().getBytes(StandardCharsets.UTF_8);
        }
        try {
            return java.nio.file.Files.readAllBytes(file.toPath());
        } finally {
            file.delete();
        }
    }

    private static int readPort(String[] args) {
        if (args.length <= 3 || args[3] == null || args[3].trim().isEmpty()) {
            return 24631;
        }
        try {
            return Integer.parseInt(args[3].trim());
        } catch (NumberFormatException e) {
            return 24631;
        }
    }

    private static PackBlob loadPack() throws IOException {
        try {
            PackBlob blob = scanHiddenDecoded(new HiddenDecoder<PackBlob>() {
                @Override
                public PackBlob accept(byte[] decoded) {
                    return parsePack(decoded);
                }
            });
            if (blob != null) {
                return blob;
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("vault pack", e);
        }
        throw new IOException("vault pack");
    }

    private static <T> T scanHiddenDecoded(HiddenDecoder<T> decoder) throws Exception {
        CodeSource source = VaultMain.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IOException("hidden");
        }
        try (JarFile jar = openJar(source)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry == null || entry.isDirectory() || !looksLikeHiddenEntry(entry.getName(), entry.getSize())) {
                    continue;
                }
                byte[] encoded;
                try (InputStream input = jar.getInputStream(entry)) {
                    encoded = readAll(input);
                }
                byte[] decoded = runNativeHelper(encoded);
                if (decoded == null || decoded.length == 0) {
                    continue;
                }
                T value = decoder.accept(decoded);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private static boolean looksLikeHiddenEntry(String name, long size) {
        if (name == null || size >= 0L && size < 96L) {
            return false;
        }
        int slash = name.lastIndexOf('/');
        int dot = name.lastIndexOf('.');
        return slash >= 0
            && dot > slash + 2
            && dot < name.length() - 2
            && name.startsWith("ctf/ghostvalve/vault/")
            && name.charAt(slash + 1) == '.';
    }

    private static PackBlob parsePack(byte[] decoded) {
        if (decoded == null || decoded.length <= 16) {
            return null;
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(decoded))) {
            if (input.readInt() != PACK_MAGIC || input.readUnsignedShort() != PACK_VERSION) {
                return null;
            }
            int count = input.readUnsignedShort();
            if (count < PACK_MIN_ITEMS || count > PACK_MAX_ITEMS) {
                return null;
            }
            List<PackSpec> specs = new ArrayList<PackSpec>(count);
            int realCount = 0;
            for (int index = 0; index < count; index++) {
                boolean real = input.readBoolean();
                String className = readString(input);
                String fieldName = readString(input);
                String methodName = readString(input);
                long lane = input.readLong();
                int fold = input.readInt();
                int size = input.readInt();
                if (size < 64 || size > 32768) {
                    return null;
                }
                byte[] classBytes = new byte[size];
                input.readFully(classBytes);
                specs.add(new PackSpec(className, fieldName, methodName, lane, fold, real, classBytes));
                if (real) {
                    realCount++;
                }
            }
            return realCount == 1 ? new PackBlob(specs) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static List<RuntimeItem> materialize(PackBlob blob, byte[] secretBytes, String noteKey) throws Exception {
        VaultClassLoader loader = new VaultClassLoader();
        List<RuntimeItem> items = new ArrayList<RuntimeItem>(blob.specs.size());
        for (PackSpec spec : blob.specs) {
            Class<?> type = loader.define(spec.className, spec.classBytes);
            byte[] payload = spec.real ? Arrays.copyOf(secretBytes, secretBytes.length) : decoyPayload();
            bindEncryptedPayload(type, spec.fieldName, encryptPayload(payload, noteKey, spec.lane, spec.fold));
            Arrays.fill(payload, (byte) 0);
            Method method = type.getDeclaredMethod(spec.methodName, String.class);
            method.setAccessible(true);
            items.add(new RuntimeItem(type.getName(), method, spec.real));
            Arrays.fill(spec.classBytes, (byte) 0);
        }
        return items;
    }

    private static void bindEncryptedPayload(Class<?> type, String fieldName, byte[] payload) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, payload);
    }

    private static byte[] encryptPayload(byte[] payload, String noteKey, long lane, int fold) {
        String clean = normalizeNote(noteKey);
        if (clean == null) {
            clean = "deadbeefdeadbeef";
        }
        byte[] output = new byte[payload.length];
        long mixed = mixNote(clean, lane);
        int base = (int) (mixed ^ (mixed >>> 32)) ^ fold;
        for (int index = 0; index < payload.length; index++) {
            int mask = Integer.rotateLeft(base + index * 0x45d9f3b, (index & 7) + 1);
            mask ^= clean.charAt(index % clean.length());
            mask ^= index * 0x27d4eb2d;
            output[index] = (byte) (((payload[index] & 0xff) ^ mask) & 0xff);
        }
        return output;
    }

    private static long mixNote(String noteKey, long lane) {
        long value = lane ^ 0x9e3779b97f4a7c15L;
        for (int index = 0; index < noteKey.length(); index++) {
            value ^= (long) noteKey.charAt(index) << ((index & 7) * 8);
            value = Long.rotateLeft(value * 0x94d049bb133111ebL, 11);
        }
        return value;
    }

    private static void serve(List<RuntimeItem> items, String noteKey, String bundleTicketSecret, int port) throws IOException {
        Set<String> consumedTickets = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        while (true) {
            Socket socket = server.accept();
            try {
                handle(socket, items, noteKey, bundleTicketSecret, consumedTickets);
            } catch (Exception ignored) {
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void handle(Socket socket, List<RuntimeItem> items, String noteKey, String bundleTicketSecret, Set<String> consumedTickets) throws Exception {
        socket.setSoTimeout(3500);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             OutputStreamWriter writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)) {
            String hello = reader.readLine();
            String helloPrefix = opHello() + " ";
            if (hello == null || !hello.startsWith(helloPrefix)) {
                writeLine(writer, opHold() + " " + placeholderStage());
                return;
            }
            String presentedNote = hello.substring(helloPrefix.length()).trim().toLowerCase(Locale.ROOT);
            if (!noteKey.equals(presentedNote)) {
                writeLine(writer, opWait() + " " + randomHex(12));
                return;
            }

            String challenge = randomHex(12);
            writeLine(writer, opStep() + " " + challenge);

            String line = reader.readLine();
            if (line == null) {
                return;
            }
            String[] parts = line.trim().split("\\s+");
            if (parts.length != 4 || !opRead().equals(parts[0])) {
                writeLine(writer, opHold() + " " + placeholderStage());
                return;
            }
            String liveKey = cleanHex(parts[1]);
            String ticket = parts[2] == null ? null : parts[2].trim().toLowerCase(Locale.ROOT);
            String proof = cleanHex(parts[3]);
            if (liveKey == null || ticket == null || proof == null) {
                writeLine(writer, opHold() + " " + placeholderStage());
                return;
            }
            if (!validateBundleTicket(bundleTicketSecret, liveKey, ticket, consumedTickets)) {
                writeLine(writer, opHold() + " " + placeholderStage());
                return;
            }
            String expected = sha256Hex(noteKey + ":" + liveKey + ":" + ticket + ":" + challenge).substring(6, 38);
            if (!expected.equals(proof)) {
                writeLine(writer, opHold() + " " + placeholderStage());
                return;
            }
            writeLine(writer, opDone() + " " + readRuntime(items, noteKey));
        }
    }

    private static boolean validateBundleTicket(String secret, String liveKey, String ticket, Set<String> consumedTickets) {
        String[] parts = ticket.split("\\.");
        if (parts.length != 3) {
            return false;
        }
        String expires = parts[0].trim().toLowerCase(Locale.ROOT);
        String ticketLive = cleanHex(parts[1]);
        String proof = cleanHex(parts[2]);
        if (ticketLive == null || proof == null || !ticketLive.equals(liveKey) || !expires.matches("^[a-f0-9]{1,16}$")) {
            return false;
        }
        long expiresAt;
        try {
            expiresAt = Long.parseLong(expires, 16);
        } catch (NumberFormatException e) {
            return false;
        }
        if (expiresAt < System.currentTimeMillis()) {
            return false;
        }
        String expected = sha256Hex(secret + ":" + liveKey + ":" + expires).substring(0, 32);
        if (!expected.equals(proof)) {
            return false;
        }
        return consumedTickets.add(ticket);
    }

    private static String readRuntime(List<RuntimeItem> items, String noteKey) throws Exception {
        String fallback = null;
        for (RuntimeItem item : items) {
            String value = readRuntimeItem(item, noteKey);
            if (value == null || value.isEmpty()) {
                continue;
            }
            if (fallback == null) {
                fallback = value;
            }
            if (looksRealSecret(value)) {
                return value;
            }
        }
        return fallback == null ? placeholderStage() : fallback;
    }

    private static String readRuntimeItem(RuntimeItem item, String noteKey) throws Exception {
        Object value = item.method.invoke(null, noteKey);
        if (!(value instanceof String)) {
            return null;
        }
        return ((String) value).trim();
    }

    private static boolean looksRealSecret(String value) {
        return value != null && value.startsWith(secretPrefix()) && value.endsWith("}") && value.length() < 160;
    }

    private static String secretPrefix() {
        return stitch(109, 105, 110, 105, 76, 123);
    }

    private static String placeholderStage() {
        return stitch(113, 117, 101, 117, 101, 100);
    }

    private static String opHello() {
        return stitch(72, 69, 76, 79);
    }

    private static String opRead() {
        return stitch(82, 69, 65, 68);
    }

    private static String opDone() {
        return stitch(68, 79, 78, 69);
    }

    private static String opHold() {
        return stitch(72, 79, 76, 68);
    }

    private static String opWait() {
        return stitch(87, 65, 73, 84);
    }

    private static String opStep() {
        return stitch(83, 84, 69, 80);
    }

    private static String randomHex(int count) {
        byte[] bytes = new byte[Math.max(1, count / 2)];
        RANDOM.nextBytes(bytes);
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte current : bytes) {
            int value = current & 0xff;
            if (value < 0x10) {
                builder.append('0');
            }
            builder.append(Integer.toHexString(value));
        }
        while (builder.length() < count) {
            builder.append('0');
        }
        return builder.substring(0, count);
    }

    private static byte[] decoyPayload() {
        return (stitch(97, 114, 99, 104, 45) + randomHex(18)).getBytes(StandardCharsets.UTF_8);
    }

    private static String cleanHex(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("^[a-f0-9]{16,64}$") ? normalized : null;
    }

    private static String normalizeNote(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("^[a-f0-9]{16,64}$") ? normalized : null;
    }

    private static String stitch(int... values) {
        StringBuilder builder = new StringBuilder(values.length);
        for (int value : values) {
            builder.append((char) value);
        }
        return builder.toString();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] raw = digest.digest(value.getBytes(StandardCharsets.UTF_8));
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
            throw new IllegalStateException("sha-256", e);
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read > 0) {
                output.write(buffer, 0, read);
            }
        }
        return output.toByteArray();
    }

    private static void writeLine(OutputStreamWriter writer, String value) throws IOException {
        writer.write(value);
        writer.write('\n');
        writer.flush();
    }

    private static String readString(DataInputStream input) throws IOException {
        int size = input.readUnsignedShort();
        if (size <= 0 || size > 512) {
            throw new IOException("string");
        }
        byte[] raw = new byte[size];
        input.readFully(raw);
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static JarFile openJar(CodeSource source) throws IOException {
        try {
            return new JarFile(new File(source.getLocation().toURI()));
        } catch (URISyntaxException e) {
            return new JarFile(new File(source.getLocation().getPath()));
        }
    }

    private static byte[] runNativeHelper(byte[] encoded) throws IOException {
        if (encoded == null || encoded.length == 0) {
            return null;
        }
        return nativeHelper().invoke(encoded);
    }

    private static NativeHelper nativeHelper() throws IOException {
        NativeHelper current = N;
        if (current != null) {
            return current;
        }
        synchronized (H) {
            current = N;
            if (current != null) {
                return current;
            }
            CodeSource source = VaultMain.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                throw new IOException("native");
            }
            try (JarFile jar = openJar(source)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry == null || entry.isDirectory() || !looksLikeHiddenEntry(entry.getName(), entry.getSize())) {
                        continue;
                    }
                    byte[] raw;
                    try (InputStream input = jar.getInputStream(entry)) {
                        raw = readAll(input);
                    }
                    current = NativeHelper.open(raw);
                    if (current == null) {
                        continue;
                    }
                    N = current;
                    return current;
                }
            }
            throw new IOException("native");
        }
    }

    private static int __WAIT_HELPER__(Process process) throws IOException {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("native wait", e);
        }
    }

    private static final class PackBlob {
        private final List<PackSpec> specs;

        private PackBlob(List<PackSpec> specs) {
            this.specs = specs;
        }
    }

    private static final class NativeHelper {
        private final Path executable;

        private NativeHelper(Path executable) {
            this.executable = executable;
        }

        private static NativeHelper open(byte[] raw) throws IOException {
            if (raw == null || raw.length < 128) {
                return null;
            }
            Path path = Files.createTempFile("ezjvav-", ".bin");
            boolean keep = false;
            try {
                Files.write(path, raw);
                File file = path.toFile();
                if (!file.setExecutable(true, false) && !file.canExecute()) {
                    return null;
                }
                NativeHelper helper = new NativeHelper(path);
                if (!helper.probe()) {
                    return null;
                }
                file.deleteOnExit();
                keep = true;
                return helper;
            } catch (IOException e) {
                return null;
            } finally {
                if (!keep) {
                    Files.deleteIfExists(path);
                }
            }
        }

        private boolean probe() throws IOException {
            Process process = new ProcessBuilder(executable.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();
            try {
                process.getOutputStream().close();
                try (InputStream input = process.getInputStream()) {
                    readAll(input);
                }
                return __WAIT_HELPER__(process) == 0;
            } finally {
                process.destroy();
            }
        }

        private byte[] invoke(byte[] payload) throws IOException {
            Process process = new ProcessBuilder(executable.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();
            try {
                try (OutputStream output = process.getOutputStream()) {
                    output.write(payload);
                }
                byte[] response;
                try (InputStream input = process.getInputStream()) {
                    response = readAll(input);
                }
                return __WAIT_HELPER__(process) == 0 && response.length > 0 ? response : null;
            } finally {
                process.destroy();
            }
        }
    }

    private static final class PackSpec {
        private final String className;
        private final String fieldName;
        private final String methodName;
        private final long lane;
        private final int fold;
        private final boolean real;
        private final byte[] classBytes;

        private PackSpec(String className, String fieldName, String methodName, long lane, int fold, boolean real, byte[] classBytes) {
            this.className = className;
            this.fieldName = fieldName;
            this.methodName = methodName;
            this.lane = lane;
            this.fold = fold;
            this.real = real;
            this.classBytes = classBytes;
        }
    }

    private static final class RuntimeItem {
        private final String className;
        private final Method method;
        private final boolean real;

        private RuntimeItem(String className, Method method, boolean real) {
            this.className = className;
            this.method = method;
            this.real = real;
        }
    }

    private static final class Y extends ClassLoader {
        private Y(ClassLoader parent) {
            super(parent);
        }

        private Class<?> q(byte[] bytes) {
            return defineClass(bytes, 0, bytes.length);
        }
    }

    public static abstract class B {
        public abstract Object a(int route, String[] values);
    }

    private interface HiddenDecoder<T> {
        T accept(byte[] decoded) throws Exception;
    }

    private static final class VaultClassLoader extends ClassLoader {
        private VaultClassLoader() {
            super(VaultMain.class.getClassLoader());
        }

        private Class<?> define(String className, byte[] bytes) {
            return defineClass(className, bytes, 0, bytes.length);
        }
    }
}
