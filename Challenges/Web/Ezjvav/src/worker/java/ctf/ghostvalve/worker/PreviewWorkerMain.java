package ctf.ghostvalve.worker;

import ctf.ghostvalve.model.PreviewModel;
import ctf.ghostvalve.protocol.PreviewPatch;
import ctf.ghostvalve.spi.ThemeHook;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.FilePermission;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.ReflectPermission;
import java.net.SocketPermission;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Permission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.PropertyPermission;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

public final class PreviewWorkerMain {
    private static final int MAX_SOURCE_BYTES = 8192;
    private static final long EXECUTION_TIMEOUT_MS = 1200L;
    private static final Set<String> CLASS_ALLOWLIST = new HashSet<String>(Arrays.asList(
        "ThemeHook",
        "java/lang/Object",
        "java/lang/String",
        "java/lang/Exception",
        "ctf/ghostvalve/spi/ThemeHook",
        "ctf/ghostvalve/model/PreviewModel",
        "ctf/ghostvalve/protocol/PreviewPatch"
    ));
    private static final InheritableThreadLocal<Boolean> RESTRICTED = new InheritableThreadLocal<Boolean>();
    private static final InheritableThreadLocal<ReadPolicy> READ_POLICY = new InheritableThreadLocal<ReadPolicy>();
    private static final List<ClassLoader> UNTRUSTED_LOADERS = Collections.synchronizedList(new ArrayList<ClassLoader>());
    private static volatile ReadPolicy STACK_READ_POLICY;
    private static final Object SECURITY_LOCK = new Object();

    private PreviewWorkerMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage");
            System.exit(2);
            return;
        }
        Path themeRoot = Paths.get(args[0]).normalize();
        PreviewModel model = decodeModel(args[1]);
        PreviewPatch envelope = applyIfPresent(themeRoot, model);
        ObjectOutputStream output = new ObjectOutputStream(System.out);
        output.writeObject(envelope);
        output.flush();
    }

    private static PreviewModel decodeModel(String data) throws IOException, ClassNotFoundException {
        byte[] raw = Base64.getUrlDecoder().decode(data);
        ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(raw));
        Object object = input.readObject();
        if (!(object instanceof PreviewModel)) {
            throw new IOException("invalid model");
        }
        return (PreviewModel) object;
    }

    private static PreviewPatch applyIfPresent(Path themeRoot, PreviewModel model) throws IOException {
        Path sourceFile = themeRoot.resolve("preview/ThemeHook.java").normalize();
        if (!sourceFile.startsWith(themeRoot) || !Files.exists(sourceFile)) {
            return PreviewPatch.empty();
        }

        byte[] sourceBytes = Files.readAllBytes(sourceFile);
        if (sourceBytes.length > MAX_SOURCE_BYTES) {
            throw new IOException("preview hook rejected by size policy");
        }

        String source = new String(sourceBytes, StandardCharsets.UTF_8);

        Path outputDir = Files.createTempDirectory("ezjvav-preview-");
        try {
            compile(sourceFile, outputDir);
            if (containsDisallowedClassReferences(outputDir)) {
                throw new IOException("preview hook rejected by bytecode policy");
            }
            ensureSecurityManager();
            return execute(themeRoot, outputDir, model);
        } finally {
            deleteTree(outputDir);
        }
    }

    private static void compile(Path sourceFile, Path outputDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("compiler unavailable");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> sources = fileManager.getJavaFileObjectsFromFiles(Arrays.asList(sourceFile.toFile()));
            List<String> options = Arrays.asList(
                "-encoding", "UTF-8",
                "-source", "8",
                "-target", "8",
                "-classpath", System.getProperty("java.class.path"),
                "-d", outputDir.toString()
            );
            Boolean compiled = compiler.getTask(null, fileManager, diagnostics, options, null, sources).call();
            if (!Boolean.TRUE.equals(compiled)) {
                throw new IOException(formatDiagnostics(diagnostics));
            }
        }
    }

    private static PreviewPatch execute(Path themeRoot, Path outputDir, final PreviewModel model) throws IOException {
        try (URLClassLoader loader = new URLClassLoader(new URL[] { outputDir.toUri().toURL() }, PreviewWorkerMain.class.getClassLoader())) {
            ReadPolicy readPolicy = ReadPolicy.create(outputDir);
            STACK_READ_POLICY = readPolicy;
            UNTRUSTED_LOADERS.add(loader);
            Class<?> hookClass = Class.forName("ThemeHook", true, loader);
            if (!ThemeHook.class.isAssignableFrom(hookClass)) {
                throw new IOException("preview hook must implement ThemeHook");
            }
            final ThemeHook hook;
            try {
                hook = (ThemeHook) hookClass.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new IOException("preview hook creation failed", e);
            }
            ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "preview-hook");
                    thread.setDaemon(true);
                    return thread;
                }
            });
            try {
                Future<PreviewPatch> future = executor.submit(new Callable<PreviewPatch>() {
                    @Override
                    public PreviewPatch call() throws Exception {
                        RESTRICTED.set(Boolean.TRUE);
                        READ_POLICY.set(readPolicy);
                        try {
                            PreviewPatch envelope = hook.apply(model);
                            return envelope != null ? envelope : PreviewPatch.empty();
                        } finally {
                            READ_POLICY.remove();
                            RESTRICTED.remove();
                        }
                    }
                });
                return future.get(EXECUTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new IOException("preview hook timed out", e);
            } catch (ExecutionException e) {
                throw new IOException("preview hook execution failed", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("preview hook interrupted", e);
            } finally {
                executor.shutdownNow();
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("preview hook load failed", e);
        }
    }

    private static void ensureSecurityManager() {
        SecurityManager current = System.getSecurityManager();
        if (current instanceof WorkerSecurityManager) {
            return;
        }
        synchronized (SECURITY_LOCK) {
            SecurityManager installed = System.getSecurityManager();
            if (installed == null) {
                System.setSecurityManager(new WorkerSecurityManager());
            }
        }
    }

    private static boolean containsDisallowedClassReferences(Path outputDir) throws IOException {
        try (Stream<Path> walk = Files.walk(outputDir)) {
            List<Path> classes = walk
                .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".class"))
                .collect(Collectors.toList());
            if (classes.size() != 1) {
                return true;
            }
            for (Path classFile : classes) {
                byte[] classBytes = Files.readAllBytes(classFile);
                for (String className : classReferences(classBytes)) {
                    if (!CLASS_ALLOWLIST.contains(className)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static List<String> classReferences(byte[] classBytes) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(classBytes));
        if (input.readInt() != 0xCAFEBABE) {
            throw new IOException("class");
        }
        input.readUnsignedShort();
        input.readUnsignedShort();
        int count = input.readUnsignedShort();
        Object[] constants = new Object[count];
        List<Integer> classIndexes = new ArrayList<Integer>();
        for (int index = 1; index < count; index++) {
            int tag = input.readUnsignedByte();
            switch (tag) {
                case 1:
                    constants[index] = input.readUTF();
                    break;
                case 3:
                case 4:
                    input.skipBytes(4);
                    break;
                case 5:
                case 6:
                    input.skipBytes(8);
                    index++;
                    break;
                case 7:
                    classIndexes.add(Integer.valueOf(input.readUnsignedShort()));
                    break;
                case 8:
                case 16:
                case 19:
                case 20:
                    input.skipBytes(2);
                    break;
                case 9:
                case 10:
                case 11:
                case 12:
                case 17:
                case 18:
                    input.skipBytes(4);
                    break;
                case 15:
                    input.skipBytes(3);
                    break;
                default:
                    throw new IOException("class");
            }
        }
        List<String> refs = new ArrayList<String>();
        for (Integer classIndex : classIndexes) {
            int utfIndex = classIndex.intValue();
            if (utfIndex <= 0 || utfIndex >= constants.length || !(constants[utfIndex] instanceof String)) {
                throw new IOException("class");
            }
            refs.add((String) constants[utfIndex]);
        }
        return refs;
    }

    private static String formatDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
        StringBuilder message = new StringBuilder("preview hook compile failed");
        int count = 0;
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (count == 0) {
                message.append(": ");
            } else {
                message.append(" | ");
            }
            message.append(diagnostic.getKind()).append(" line ").append(diagnostic.getLineNumber()).append(": ").append(diagnostic.getMessage(Locale.ENGLISH));
            count++;
            if (count == 2) {
                break;
            }
        }
        return message.toString();
    }

    private static void deleteTree(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            List<Path> items = walk.sorted((a, b) -> b.compareTo(a)).collect(Collectors.toList());
            for (Path item : items) {
                Files.deleteIfExists(item);
            }
        }
    }

    private static boolean isRestricted() {
        return Boolean.TRUE.equals(RESTRICTED.get()) || hasUntrustedStack();
    }

    private static boolean isAllowedRead(String file) {
        ReadPolicy policy = READ_POLICY.get();
        if (policy == null) {
            policy = STACK_READ_POLICY;
        }
        return policy != null && policy.allows(file);
    }

    private static boolean isUntrustedLoader(ClassLoader loader) {
        if (loader == null) {
            return false;
        }
        synchronized (UNTRUSTED_LOADERS) {
            for (ClassLoader current : UNTRUSTED_LOADERS) {
                if (loader == current) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final class ReadPolicy {
        private final List<Path> directories;
        private final List<Path> files;

        private ReadPolicy(List<Path> directories, List<Path> files) {
            this.directories = directories;
            this.files = files;
        }

        static ReadPolicy create(Path outputDir) {
            List<Path> directories = new ArrayList<Path>();
            List<Path> files = new ArrayList<Path>();
            addDirectory(directories, outputDir);
            addDirectory(directories, systemPath("java.home"));
            String classPath = System.getProperty("java.class.path", "");
            String separator = System.getProperty("path.separator", ":");
            for (String entry : classPath.split(Pattern.quote(separator))) {
                if (entry == null || entry.trim().isEmpty()) {
                    continue;
                }
                Path path = Paths.get(entry).toAbsolutePath().normalize();
                if (Files.isDirectory(path)) {
                    addDirectory(directories, path);
                } else {
                    addFile(files, path);
                }
            }
            return new ReadPolicy(Collections.unmodifiableList(directories), Collections.unmodifiableList(files));
        }

        boolean allows(String file) {
            if (file == null || file.trim().isEmpty()) {
                return false;
            }
            final Path path;
            try {
                path = Paths.get(file).toAbsolutePath().normalize();
            } catch (RuntimeException e) {
                return false;
            }
            for (Path allowedFile : files) {
                if (path.equals(allowedFile)) {
                    return true;
                }
            }
            for (Path directory : directories) {
                if (path.startsWith(directory)) {
                    return true;
                }
            }
            return false;
        }

        private static Path systemPath(String key) {
            String value = System.getProperty(key, "");
            if (value == null || value.trim().isEmpty()) {
                return null;
            }
            return Paths.get(value).toAbsolutePath().normalize();
        }

        private static void addDirectory(List<Path> directories, Path directory) {
            if (directory != null) {
                directories.add(directory.toAbsolutePath().normalize());
            }
        }

        private static void addFile(List<Path> files, Path file) {
            if (file != null) {
                files.add(file.toAbsolutePath().normalize());
            }
        }
    }

    private static final class WorkerSecurityManager extends SecurityManager {
        private boolean hasUntrustedClassOnStack() {
            Class<?>[] context = getClassContext();
            if (context == null) {
                return false;
            }
            for (Class<?> type : context) {
                if (type == null) {
                    continue;
                }
                if (isUntrustedLoader(type.getClassLoader())) {
                    return true;
                }
                String name = type.getName();
                if ("ThemeHook".equals(name) || name.startsWith("ThemeHook$")) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void checkPermission(Permission perm) {
            if (!isRestricted() || perm == null) {
                return;
            }
            String name = perm.getName() == null ? "" : perm.getName();
            String actions = perm.getActions() == null ? "" : perm.getActions().toLowerCase(Locale.ROOT);
            if (perm instanceof FilePermission) {
                if (actions.contains("read") && !isAllowedRead(name)) {
                    throw new SecurityException("worker read denied");
                }
                if (actions.contains("write") || actions.contains("delete") || actions.contains("execute")) {
                    throw new SecurityException("worker fs denied");
                }
            }
            if (perm instanceof SocketPermission) {
                throw new SecurityException("worker socket denied");
            }
            if (perm instanceof PropertyPermission && actions.contains("write")) {
                throw new SecurityException("worker property denied");
            }
            if (perm instanceof ReflectPermission) {
                throw new SecurityException("worker reflect denied");
            }
            if (perm instanceof RuntimePermission) {
                if ("setSecurityManager".equals(name)
                    || "createClassLoader".equals(name)
                    || "setContextClassLoader".equals(name)
                    || name.startsWith("modifyThread")
                    || name.startsWith("accessDeclaredMembers")
                    || name.startsWith("loadLibrary")) {
                    throw new SecurityException("worker runtime denied");
                }
            }
        }

        @Override
        public void checkAccess(Thread thread) {
            if (isRestricted()) {
                throw new SecurityException("worker thread denied");
            }
        }

        @Override
        public void checkAccess(ThreadGroup group) {
            if (isRestricted()) {
                throw new SecurityException("worker threadgroup denied");
            }
        }

        @Override
        public void checkExec(String cmd) {
            if (isRestricted()) {
                throw new SecurityException("worker exec denied");
            }
        }

        @Override
        public void checkRead(String file) {
            if (isRestricted() && !isAllowedRead(file)) {
                throw new SecurityException("worker read denied");
            }
        }

        @Override
        public void checkRead(String file, Object context) {
            if (isRestricted() && !isAllowedRead(file)) {
                throw new SecurityException("worker read denied");
            }
        }

        @Override
        public void checkWrite(String file) {
            if (isRestricted()) {
                throw new SecurityException("worker write denied");
            }
        }

        @Override
        public void checkDelete(String file) {
            if (isRestricted()) {
                throw new SecurityException("worker delete denied");
            }
        }

        @Override
        public void checkConnect(String host, int port) {
            if (isRestricted()) {
                throw new SecurityException("worker connect denied");
            }
        }

        @Override
        public void checkListen(int port) {
            if (isRestricted()) {
                throw new SecurityException("worker listen denied");
            }
        }

        @Override
        public void checkAccept(String host, int port) {
            if (isRestricted()) {
                throw new SecurityException("worker accept denied");
            }
        }
    }

    private static boolean hasUntrustedStack() {
        SecurityManager manager = System.getSecurityManager();
        if (manager instanceof WorkerSecurityManager) {
            return ((WorkerSecurityManager) manager).hasUntrustedClassOnStack();
        }
        return false;
    }
}
