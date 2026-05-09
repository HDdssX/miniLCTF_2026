package ctf.ghostvalve.theme;

import ctf.ghostvalve.market.ViewState;
import ctf.ghostvalve.model.PreviewModel;
import ctf.ghostvalve.protocol.PreviewPatch;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletContext;

public final class HookCompiler {
    private static final long WORKER_TIMEOUT_MS = 4000L;
    private static final List<String> ALLOWED_CLASSES = Arrays.asList(
        "ctf.ghostvalve.protocol.PreviewPatch",
        "java.lang.String"
    );

    private HookCompiler() {
    }

    public static HookOutcome applyIfPresent(Path themeRoot, PreviewModel model, ServletContext servletContext) throws IOException {
        Path sourceFile = themeRoot.resolve("preview/ThemeHook.java").normalize();
        if (!sourceFile.startsWith(themeRoot) || !Files.exists(sourceFile)) {
            return HookOutcome.none();
        }

        WorkerResult workerResult = executeWorker(themeRoot, model);
        if (workerResult.exitCode != 0) {
            return HookOutcome.failed("preview worker failed: " + workerResult.stderr);
        }
        if (workerResult.stdout.length == 0) {
            return HookOutcome.failed("preview worker produced no output");
        }

        ViewState.enter(servletContext, themeRoot);
        try {
            Object object = deserialize(workerResult.stdout);
            if (!(object instanceof PreviewPatch)) {
                return HookOutcome.failed("preview worker returned unexpected payload");
            }
            PreviewPatch patch = (PreviewPatch) object;
            if (patch != null) {
                patch.applyTo(model);
            }
            return HookOutcome.applied();
        } finally {
            ViewState.exit();
        }
    }

    private static WorkerResult executeWorker(Path themeRoot, PreviewModel model) throws IOException {
        List<String> command = Arrays.asList(
            javaBinary(),
            "-Xms32m",
            "-Xmx64m",
            "-cp",
            System.getProperty("ezjvav.worker.jar", "/opt/ghost/worker/preview-runner.jar")
                + System.getProperty("path.separator")
                + System.getProperty("ezjvav.protocol.jar", "/opt/ghost/shared/protocol.jar"),
            "ctf.ghostvalve.worker.PreviewWorkerMain",
            themeRoot.toString(),
            serializeModel(model)
        );
        Process process = new ProcessBuilder(command).start();
        ExecutorService pool = Executors.newFixedThreadPool(2, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "preview-worker-io");
                thread.setDaemon(true);
                return thread;
            }
        });
        try {
            Future<byte[]> stdout = pool.submit(readAll(process.getInputStream()));
            Future<byte[]> stderr = pool.submit(readAll(process.getErrorStream()));
            if (!process.waitFor(WORKER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new WorkerResult(-1, new byte[0], "timeout");
            }
            return new WorkerResult(process.exitValue(), get(stdout), new String(get(stderr), StandardCharsets.UTF_8).trim());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new WorkerResult(-1, new byte[0], "interrupted");
        } finally {
            pool.shutdownNow();
        }
    }

    private static Callable<byte[]> readAll(final InputStream input) {
        return new Callable<byte[]>() {
            @Override
            public byte[] call() throws Exception {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int read;
                while ((read = input.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                }
                return buffer.toByteArray();
            }
        };
    }

    private static byte[] get(Future<byte[]> future) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("worker io interrupted", e);
        } catch (ExecutionException e) {
            throw new IOException("worker io failed", e);
        }
    }

    private static Object deserialize(byte[] data) throws IOException {
        try (AllowedObjectInputStream input = new AllowedObjectInputStream(new ByteArrayInputStream(data))) {
            return input.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException("preview payload rejected", e);
        }
    }

    private static String serializeModel(PreviewModel model) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ObjectOutputStream output = new ObjectOutputStream(buffer);
        output.writeObject(model);
        output.flush();
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.toByteArray());
    }

    private static String javaBinary() {
        String home = System.getProperty("java.home");
        return home + java.io.File.separator + "bin" + java.io.File.separator + "java";
    }

    public static final class HookOutcome {
        private final String state;
        private final String message;

        private HookOutcome(String state, String message) {
            this.state = state;
            this.message = message;
        }

        public static HookOutcome none() {
            return new HookOutcome("none", "not configured");
        }

        public static HookOutcome applied() {
            return new HookOutcome("loaded", "worker patch applied");
        }

        public static HookOutcome failed(String message) {
            return new HookOutcome("failed", message);
        }

        public String getState() {
            return state;
        }

        public String getMessage() {
            return message;
        }

        public boolean isRenderable() {
            return "none".equals(state) || "loaded".equals(state);
        }
    }

    private static final class WorkerResult {
        private final int exitCode;
        private final byte[] stdout;
        private final String stderr;

        private WorkerResult(int exitCode, byte[] stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr == null ? "" : stderr;
        }
    }

    private static final class AllowedObjectInputStream extends ObjectInputStream {
        private AllowedObjectInputStream(InputStream input) throws IOException {
            super(input);
        }

        @Override
        protected Class<?> resolveClass(ObjectStreamClass descriptor) throws IOException, ClassNotFoundException {
            String name = descriptor.getName();
            if (!ALLOWED_CLASSES.contains(name)) {
                throw new IOException("disallowed class: " + name.toLowerCase(Locale.ROOT));
            }
            return super.resolveClass(descriptor);
        }
    }
}
