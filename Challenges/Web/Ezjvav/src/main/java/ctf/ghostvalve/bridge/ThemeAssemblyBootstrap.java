package ctf.ghostvalve.bridge;

import ctf.ghostvalve.theme.ThemeCatalog;
import ctf.ghostvalve.theme.ThemeValveTicket;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.FilePermission;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Permission;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.ServletException;
import javax.servlet.ServletContext;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.net.SocketPermission;
import java.util.PropertyPermission;
import org.apache.catalina.Pipeline;
import org.apache.catalina.Valve;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

public final class ThemeAssemblyBootstrap {
    private static final String CLASS_NAME_REGEX = "^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*$";
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u+([0-9a-fA-F]{4})");
    private static final String FIXED_PREVIEW_CLASS = "ctf.ghostvalve.preview.ReviewAdapter";
    private static final String FIXED_PREVIEW_SOURCE = "preview/ReviewAdapter.java";
    private static final InheritableThreadLocal<Boolean> VALVE_GUARD = new InheritableThreadLocal<Boolean>();
    private static final InheritableThreadLocal<ValveScope> VALVE_SCOPE = new InheritableThreadLocal<ValveScope>();
    private static final ThreadLocal<ActivePlan> CURRENT_ACTIVE_PLAN = new ThreadLocal<ActivePlan>();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String BUNDLE_TICKET_SECRET = loadBundleTicketSecret();
    private static final String BUNDLE_NOTE_KEY = loadBundleNoteKey();
    private static final long EXPORT_WINDOW_MS = 12000L;
    private static final long PREVIEW_HANDLE_TTL_MS = 4000L;
    private static final long BUNDLE_TICKET_TTL_MS = 5000L;
    private static final long INSTALLED_EXPORT_PASS_TTL_MS = 5000L;
    private static final long DECOY_PASS_TTL_MS = 3500L;
    private static final String[] SOURCE_DENY_TOKENS = new String[] {
        "site.preview.channel",
        "site_preview_flow",
        "theme_publish",
        "theme_flow",
        "x-publication-receipt",
        "x-theme-",
        "x-theme-mode",
        "x-theme-key",
        "x-theme-tag",
        "x-theme-match",
        "themeledger",
        "socket",
        "127.0.0.1",
        "localhost",
        "memo(",
        "badge(",
        "folder(",
        "java.net",
        "inetsocketaddress"
    };
    private static final Set<String> CONSUMED_THEMES = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final Set<String> LEGACY_THEMES = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final ConcurrentHashMap<String, String> TOKEN_BINDINGS = new ConcurrentHashMap<String, String>();
    private static final ConcurrentHashMap<String, StagePlan> STAGE_PLANS = new ConcurrentHashMap<String, StagePlan>();
    private static final ConcurrentHashMap<String, PreviewHandle> PREVIEW_HANDLES = new ConcurrentHashMap<String, PreviewHandle>();
    private static final ConcurrentHashMap<String, PendingPlan> PENDING_PLANS = new ConcurrentHashMap<String, PendingPlan>();
    private static final ConcurrentHashMap<String, ActivePlan> ACTIVE_PLANS = new ConcurrentHashMap<String, ActivePlan>();
    private static final ConcurrentHashMap<String, ActivePlan> ACTIVE_THEME_PLANS = new ConcurrentHashMap<String, ActivePlan>();
    private static final ConcurrentHashMap<String, DecoyPass> DECOY_PASSES = new ConcurrentHashMap<String, DecoyPass>();
    private static final ConcurrentHashMap<String, String> RENDER_STAMPS = new ConcurrentHashMap<String, String>();
    private static final ConcurrentHashMap<String, String> CACHE_INDEXES = new ConcurrentHashMap<String, String>();
    private static final HelperClient HELPER = HelperClient.defaultClient();
    private static final int MAX_VALVE_SOURCE_BYTES = 12000;

    private ThemeAssemblyBootstrap() {
    }

    private static String loadBundleTicketSecret() {
        return readRequiredSecret(
            System.getProperty("ezjvav.ticket.secret.file", "/dev/shm/ezjvav.ticketsecret"),
            "bundle ticket secret"
        );
    }

    private static String loadBundleNoteKey() {
        String seed = readRequiredSecret(
            System.getProperty("ezjvav.relay.note.file", "/dev/shm/ezjvav.relaynote"),
            "relay note seed"
        );
        return sha256Hex(seed + ":memo").substring(0, 32);
    }

    private static String readRequiredSecret(String pathValue, String label) {
        Path file = new java.io.File(pathValue).toPath();
        try {
            if (!Files.isRegularFile(file)) {
                throw new IllegalStateException(label + " unavailable");
            }
            String value = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
            if (value.isEmpty()) {
                throw new IllegalStateException(label + " unavailable");
            }
            return value;
        } catch (IOException e) {
            throw new IllegalStateException(label + " unavailable", e);
        }
    }

    public static synchronized void preparePreviewPlan(Path themeRoot) throws IOException {
        if (themeRoot == null) {
            throw new IllegalArgumentException("theme");
        }
        String key = themeKey(themeRoot);
        resetThemeStageState(key);

        byte[] sourceBytes = readPlannedSource(themeRoot, FIXED_PREVIEW_SOURCE);
        if (sourceBytes == null) {
            return;
        }
        String nonce = ThemeValveTicket.read(themeRoot);
        String source = new String(sourceBytes, StandardCharsets.UTF_8);
        ensureAllowedSource(source);

        STAGE_PLANS.put(key, new StagePlan(
            key,
            FIXED_PREVIEW_CLASS,
            FIXED_PREVIEW_SOURCE,
            sha256Hex(sourceBytes),
            randomHex(12),
            nonce
        ));
    }

    public static synchronized String issuePreviewHandle(Path themeRoot) {
        if (themeRoot == null) {
            return null;
        }
        StagePlan plan = STAGE_PLANS.get(themeKey(themeRoot));
        if (plan == null) {
            return null;
        }
        String handle = randomHex(12);
        String witness = randomHex(4) + shortSha256("preview:" + plan.previewTicket + ":" + plan.nonce + ":" + handle);
        PREVIEW_HANDLES.put(handle, new PreviewHandle(plan.themeKey, plan.previewTicket, handle, witness, System.currentTimeMillis()));
        return handle;
    }

    public static synchronized String previewWitness(Path themeRoot, String handle) {
        PreviewHandle value = peekPreviewHandle(themeRoot, handle);
        return value == null ? null : value.witness;
    }

    public static synchronized void openTrustedStage(ServletContext context, Path themeRoot, String previewHandle, String reviewToken, String clientKey) throws Exception {
        if (context == null || themeRoot == null) {
            throw new IllegalArgumentException("site");
        }
        PreviewHandle gate = requirePreviewHandle(themeRoot, previewHandle);
        StagePlan plan = requireStagePlan(themeRoot, gate.previewTicket);
        String cleanClient = normalizeClientKey(clientKey);
        BridgeRuntime.enter(context, themeRoot);
        try {
            String nonce = plan.nonce;
            PendingPlan pending = createPendingPlan(plan.className, plan.sourcePath, nonce, cleanClient);
            if (!CONSUMED_THEMES.contains(pending.themeKey)) {
                PENDING_PLANS.put(pending.themeKey, pending);
            }
            ReviewValveBootstrap.mount(reviewToken);
            storeRenderLabel(reviewToken, nonce);
            storeBundleLabel(cleanClient, nonce);
        } finally {
            BridgeRuntime.exit();
        }
    }

    public static String acceptPreviewSignal(String previewHandle, String note, String headline, String accent) {
        PreviewHandle value = peekPreviewHandle(null, previewHandle);
        if (value == null) {
            return null;
        }
        return HELPER.previewVerify(previewHandle, note, headline, accent, value.witness);
    }

    public static synchronized void prime(String className, String sourcePath, String nonce) throws Exception {
        PendingPlan legacy = createPendingPlan(className, sourcePath, nonce, null);
        if (!CONSUMED_THEMES.contains(legacy.themeKey)) {
            LEGACY_THEMES.add(legacy.themeKey);
        }
    }

    public static synchronized void collect(String className, String sourcePath, String nonce, String clientKey) throws Exception {
        PendingPlan pending = createPendingPlan(className, sourcePath, nonce, normalizeClientKey(clientKey));
        if (CONSUMED_THEMES.contains(pending.themeKey)) {
            return;
        }
        PENDING_PLANS.put(pending.themeKey, pending);
    }

    public static synchronized void record(String className, String sourcePath, String nonce, String clientKey) throws Exception {
        PendingPlan pending = createPendingPlan(className, sourcePath, nonce, normalizeClientKey(clientKey));
        if (CONSUMED_THEMES.contains(pending.themeKey)) {
            return;
        }
        PENDING_PLANS.put(pending.themeKey, pending);
    }

    public static synchronized void storeRenderLabel(String label, String nonce) {
        Path themeRoot = BridgeRuntime.currentThemeRoot();
        String normalizedNonce = currentNonce(themeRoot, nonce);
        if (themeRoot == null || normalizedNonce == null) {
            return;
        }
        RENDER_STAMPS.put(themeKey(themeRoot), shortSha256("render:" + normalizeReceiptLabel(label) + ":" + normalizedNonce));
    }

    public static synchronized void storeBundleLabel(String label, String nonce) {
        Path themeRoot = BridgeRuntime.currentThemeRoot();
        String normalizedNonce = currentNonce(themeRoot, nonce);
        if (themeRoot == null || normalizedNonce == null) {
            return;
        }
        CACHE_INDEXES.put(themeKey(themeRoot), shortSha256("index:" + normalizeReceiptLabel(label) + ":" + normalizedNonce));
    }

    public static synchronized String sealBundle(Path themeRoot, String digest) {
        String normalizedDigest = normalizeLedgerDigest(digest);
        if (themeRoot == null || normalizedDigest == null) {
            return normalizedDigest;
        }
        String key = themeKey(themeRoot);
        PendingPlan pending = PENDING_PLANS.get(key);
        if (pending == null || CONSUMED_THEMES.contains(key)) {
            return normalizedDigest;
        }
        String renderLabel = RENDER_STAMPS.get(key);
        String bundleLabel = CACHE_INDEXES.get(key);
        if (renderLabel == null || bundleLabel == null) {
            return normalizedDigest;
        }
        pending.bundleDigest = sha256Hex(normalizedDigest + ":" + renderLabel + ":" + bundleLabel + ":" + pending.nonce).substring(0, 32);
        pending.exportTouchedAt = System.currentTimeMillis();
        return pending.bundleDigest;
    }

    public static synchronized void bindToken(String token) {
        Path themeRoot = BridgeRuntime.currentThemeRoot();
        if (themeRoot == null || token == null || token.trim().isEmpty()) {
            return;
        }
        TOKEN_BINDINGS.put(token.trim(), themeRoot.toAbsolutePath().normalize().toString());
    }

    public static synchronized ChallengeBundle issueChallenge(String token) {
        if (token == null) {
            return null;
        }
        String themeKey = TOKEN_BINDINGS.get(token.trim());
        if (themeKey == null) {
            return null;
        }
        if (LEGACY_THEMES.contains(themeKey)) {
            String cookie = "legacy-" + shortSha256(token.trim());
            return new ChallengeBundle(cookie, shortSha256("legacy:" + token.trim()), null);
        }
        return null;
    }

    public static synchronized ChallengeBundle openPass(String token) {
        if (token == null) {
            return null;
        }
        String themeKey = TOKEN_BINDINGS.get(token.trim());
        if (themeKey == null) {
            return null;
        }
        PendingPlan pending = PENDING_PLANS.get(themeKey);
        if (pending == null) {
            return issueChallenge(token);
        }
        if (pending.challenge == null || pending.challenge.trim().isEmpty()) {
            pending.challenge = randomHex(10) + shortSha256(token.trim() + ":" + pending.nonce);
            pending.marker = shortSha256(pending.nonce + ":" + pending.clientKey + ":" + pending.challenge);
        }
        if (pending.installWitness == null || pending.installWitness.trim().isEmpty()) {
            pending.installWitness = randomHex(4) + shortSha256("install:" + pending.challenge + ":" + pending.marker + ":" + pending.nonce);
        }
        pending.challengeIssuedAt = System.currentTimeMillis();
        return new ChallengeBundle(pending.challenge, pending.marker, pending.installWitness);
    }

    public static synchronized ChallengeBundle openDecoyPass(String clientKey) {
        String client = normalizeDecoyClient(clientKey);
        if (client == null) {
            return null;
        }
        String cookie = randomHex(10);
        String marker = shortSha256("audit:" + client + ":" + cookie);
        String digest = sha256Hex(client + ":" + reverse(cookie) + ":" + marker).substring(4, 36);
        DECOY_PASSES.put(cookie, new DecoyPass(client, cookie, marker, digest, System.currentTimeMillis()));
        return new ChallengeBundle(cookie, marker, null);
    }

    public static synchronized String decoyStatus(String cookieValue) {
        DecoyPass pass = activeDecoyPass(cookieValue);
        if (pass == null) {
            return "idle";
        }
        return pass.completed ? "archived" : "pending";
    }

    public static synchronized boolean completePass(String token, String cookieValue, String proof, String marker) throws Exception {
        String client = normalizeDecoyClient(token);
        if (client == null || cookieValue == null || proof == null || marker == null) {
            return false;
        }
        DecoyPass pass = activeDecoyPass(cookieValue);
        if (pass == null || pass.completed) {
            return false;
        }
        if (!pass.client.equals(client)) {
            return false;
        }
        if (!pass.marker.equals(marker.trim().toLowerCase(Locale.ROOT))) {
            return false;
        }
        String expected = sha256Hex(pass.client + ":" + pass.cookieValue + ":" + pass.digest + ":" + pass.marker).substring(8, 40);
        if (!expected.equals(proof.trim().toLowerCase(Locale.ROOT))) {
            return false;
        }
        pass.completed = true;
        pass.completedAt = System.currentTimeMillis();
        return true;
    }

    public static synchronized boolean acceptPass(String token, String cookieValue, String proof, String marker) throws Exception {
        if (token == null || cookieValue == null || proof == null || marker == null) {
            return false;
        }
        String themeKey = TOKEN_BINDINGS.get(token.trim());
        if (themeKey == null) {
            return false;
        }
        PendingPlan pending = PENDING_PLANS.get(themeKey);
        if (pending == null || CONSUMED_THEMES.contains(themeKey)) {
            return false;
        }
        if (pending.challengeIssuedAt <= 0L || System.currentTimeMillis() - pending.challengeIssuedAt > 5000L) {
            pending.challenge = null;
            pending.marker = null;
            pending.installWitness = null;
            return false;
        }
        if (pending.challenge == null || !cookieValue.trim().equals(pending.challenge)) {
            return false;
        }
        if (pending.marker == null || !marker.trim().equals(pending.marker)) {
            return false;
        }
        if (pending.bundleDigest == null || pending.exportTouchedAt <= 0L || System.currentTimeMillis() - pending.exportTouchedAt > EXPORT_WINDOW_MS) {
            return false;
        }
        if (pending.installWitness == null || pending.installWitness.trim().isEmpty()) {
            return false;
        }
        if (!verifyInstallPass(pending.clientKey, token.trim(), pending.challenge, pending.marker, pending.bundleDigest, proof, pending.installWitness)) {
            return false;
        }
        ActivePlan active = compileAndInstall(pending, token.trim());
        issuePendingReceipt(active, pending.bundleDigest);
        CONSUMED_THEMES.add(themeKey);
        PENDING_PLANS.remove(themeKey);
        LEGACY_THEMES.remove(themeKey);
        return true;
    }

    public static synchronized String peekPendingReceipt(String token) {
        if (token == null) {
            return null;
        }
        String key = TOKEN_BINDINGS.get(token.trim());
        if (key == null) {
            return null;
        }
        ActivePlan active = ACTIVE_THEME_PLANS.get(key);
        if (active == null || !token.trim().equals(active.token)) {
            return null;
        }
        return active.pendingReceiptId;
    }

    public static synchronized String consumePendingReceipt(Path themeRoot, String pendingReceiptId, String bundleDigest) throws IOException {
        if (Boolean.TRUE.equals(VALVE_GUARD.get())) {
            throw new IOException("relay unavailable");
        }
        if (themeRoot == null || pendingReceiptId == null || pendingReceiptId.trim().isEmpty()) {
            return null;
        }
        String key = themeKey(themeRoot);
        ActivePlan active = ACTIVE_THEME_PLANS.get(key);
        if (active == null || active.pendingReceiptId == null || active.pendingReceiptConsumed) {
            return null;
        }
        if (!active.pendingReceiptId.equals(pendingReceiptId.trim())) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (active.installedAt <= 0L || active.pendingReceiptIssuedAt <= 0L || active.pendingReceiptIssuedAt < active.installedAt) {
            return null;
        }
        if (now - active.pendingReceiptIssuedAt > INSTALLED_EXPORT_PASS_TTL_MS) {
            return null;
        }
        String normalizedDigest = normalizeLedgerDigest(bundleDigest);
        if (normalizedDigest == null || active.pendingBundleDigest == null) {
            return null;
        }
        active.pendingBundleDigest = sha256Hex(active.pendingBundleDigest + ":" + normalizedDigest + ":" + active.installNonce).substring(0, 32);
        active.pendingReceiptConsumed = true;
        return readBundleRelay(active);
    }

    private static void validateClassName(String className) {
        if (className == null || className.trim().isEmpty()) {
            throw new IllegalArgumentException("class");
        }
        String normalized = className.trim();
        if (!normalized.matches(CLASS_NAME_REGEX)) {
            throw new IllegalArgumentException("class");
        }
    }

    private static String normalizeSourcePath(String sourcePath) {
        if (sourcePath == null) {
            throw new IllegalArgumentException("path");
        }
        String normalized = sourcePath.trim().replace('\\', '/');
        if (FIXED_PREVIEW_SOURCE.equals(normalized)) {
            return normalized;
        }
        if (normalized.isEmpty()
            || normalized.startsWith("/")
            || normalized.contains("..")
            || !(normalized.startsWith("assets/") || normalized.startsWith("templates/"))
            || !normalized.endsWith(".java")) {
            throw new IllegalArgumentException("path");
        }
        return normalized;
    }

    private static String normalizeClientKey(String clientKey) {
        if (clientKey == null) {
            throw new IllegalArgumentException("client");
        }
        String normalized = clientKey.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("^[a-f0-9]{16,64}$")) {
            throw new IllegalArgumentException("client");
        }
        return normalized;
    }

    private static String normalizeDecoyClient(String clientKey) {
        if (clientKey == null) {
            return null;
        }
        String normalized = clientKey.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("^[a-z0-9_.:-]{4,80}$") ? normalized : null;
    }

    private static PendingPlan createPendingPlan(String className, String sourcePath, String nonce, String clientKey) throws Exception {
        validateClassName(className);
        String normalizedSourcePath = normalizeSourcePath(sourcePath);

        ServletContext servletContext = BridgeRuntime.current();
        Path themeRoot = BridgeRuntime.currentThemeRoot();
        if (servletContext == null || themeRoot == null) {
            throw new IllegalStateException("bridge runtime missing");
        }

        String themeKey = themeKey(themeRoot);
        String normalizedNonce = normalizeNonce(nonce);
        byte[] sourceBytes = readPlannedSource(themeRoot, normalizedSourcePath);
        if (sourceBytes == null) {
            throw new IOException("source");
        }
        validateStageBinding(themeRoot, className.trim(), normalizedSourcePath, sourceBytes);
        String source = new String(sourceBytes, StandardCharsets.UTF_8);
        ensureAllowedSource(source);
        return new PendingPlan(themeKey, themeRoot, extractContext(servletContext), className.trim(), normalizedSourcePath, normalizedNonce, clientKey);
    }

    private static void resetThemeStageState(final String key) {
        STAGE_PLANS.remove(key);
        PENDING_PLANS.remove(key);
        CONSUMED_THEMES.remove(key);
        LEGACY_THEMES.remove(key);
        ACTIVE_THEME_PLANS.remove(key);
        TOKEN_BINDINGS.entrySet().removeIf(new java.util.function.Predicate<java.util.Map.Entry<String, String>>() {
            @Override
            public boolean test(java.util.Map.Entry<String, String> entry) {
                return key.equals(entry.getValue());
            }
        });
        PREVIEW_HANDLES.entrySet().removeIf(new java.util.function.Predicate<java.util.Map.Entry<String, PreviewHandle>>() {
            @Override
            public boolean test(java.util.Map.Entry<String, PreviewHandle> entry) {
                PreviewHandle value = entry.getValue();
                return value == null || key.equals(value.themeKey);
            }
        });
        RENDER_STAMPS.remove(key);
        CACHE_INDEXES.remove(key);
    }

    private static byte[] readPlannedSource(Path themeRoot, String sourcePath) throws IOException {
        String normalized = normalizeSourcePath(sourcePath);
        if (FIXED_PREVIEW_SOURCE.equals(normalized)) {
            Path file = themeRoot.resolve(normalized).normalize();
            if (!file.startsWith(themeRoot) || !Files.isRegularFile(file)) {
                return null;
            }
            return Files.readAllBytes(file);
        }
        return ThemeCatalog.readVerified(themeRoot, normalized);
    }

    private static DecoyPass activeDecoyPass(String cookieValue) {
        if (cookieValue == null) {
            return null;
        }
        String key = cookieValue.trim();
        if (key.isEmpty()) {
            return null;
        }
        DecoyPass pass = DECOY_PASSES.get(key);
        if (pass == null) {
            return null;
        }
        if (pass.createdAt + DECOY_PASS_TTL_MS < System.currentTimeMillis()) {
            DECOY_PASSES.remove(key);
            return null;
        }
        return pass;
    }

    private static String normalizeNonce(String nonce) {
        if (nonce == null) {
            throw new IllegalArgumentException("nonce");
        }
        String normalized = nonce.trim();
        if (!normalized.matches("^[a-f0-9]{16,64}$")) {
            throw new IllegalArgumentException("nonce");
        }
        return normalized;
    }

    private static String safeNonce(String nonce) {
        try {
            return normalizeNonce(nonce);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String currentNonce(Path themeRoot, String nonce) {
        return safeNonce(nonce);
    }

    private static String normalizeLedgerDigest(String digest) {
        if (digest == null) {
            return null;
        }
        String normalized = digest.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("^[a-f0-9]{16,64}$")) {
            return null;
        }
        return normalized;
    }

    private static String normalizeReceiptLabel(String label) {
        String normalized = label == null ? "default" : label.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return "default";
        }
        if (!normalized.matches("^[a-z0-9._:-]{1,64}$")) {
            return shortSha256(normalized);
        }
        return normalized;
    }

    private static String themeKey(Path themeRoot) {
        return themeRoot.toAbsolutePath().normalize().toString();
    }

    private static StagePlan requireStagePlan(Path themeRoot, String previewTicket) throws IOException {
        StagePlan plan = STAGE_PLANS.get(themeKey(themeRoot));
        if (plan == null) {
            throw new IOException("plan");
        }
        if (previewTicket != null && !plan.previewTicket.equals(previewTicket.trim().toLowerCase(Locale.ROOT))) {
            throw new IOException("stage");
        }
        return plan;
    }

    private static PreviewHandle requirePreviewHandle(Path themeRoot, String handle) throws IOException {
        PreviewHandle value = peekPreviewHandle(themeRoot, handle);
        if (value == null) {
            throw new IOException("stage");
        }
        String normalized = handle.trim().toLowerCase(Locale.ROOT);
        value.redeemed = true;
        PREVIEW_HANDLES.remove(normalized);
        return value;
    }

    private static PreviewHandle peekPreviewHandle(Path themeRoot, String handle) {
        if (handle == null || handle.trim().isEmpty()) {
            return null;
        }
        String normalized = handle.trim().toLowerCase(Locale.ROOT);
        PreviewHandle value = PREVIEW_HANDLES.get(normalized);
        if (value == null || value.redeemed) {
            return null;
        }
        if (themeRoot != null && !themeKey(themeRoot).equals(value.themeKey)) {
            return null;
        }
        if (value.issuedAt + PREVIEW_HANDLE_TTL_MS < System.currentTimeMillis()) {
            PREVIEW_HANDLES.remove(normalized);
            return null;
        }
        return value;
    }

    private static boolean verifyInstallPass(String clientKey, String token, String challenge, String marker, String bundleDigest, String proof, String witness) {
        return HELPER.installVerify(clientKey, token, challenge, marker, bundleDigest, proof, witness);
    }

    private static void validateStageBinding(Path themeRoot, String className, String sourcePath, byte[] sourceBytes) throws IOException {
        StagePlan plan = requireStagePlan(themeRoot, null);
        if (!plan.className.equals(className) || !plan.sourcePath.equals(sourcePath)) {
            throw new IOException("plan mismatch");
        }
        String digest = sha256Hex(sourceBytes);
        if (!plan.sourceDigest.equals(digest)) {
            throw new IOException("plan digest");
        }
    }

    private static void ensureAllowedSource(String source) throws IOException {
        if (source == null || source.getBytes(StandardCharsets.UTF_8).length > MAX_VALVE_SOURCE_BYTES) {
            throw new IOException("custom valve rejected by source size policy");
        }
        String normalized = normalizeSource(source);
        for (String token : SOURCE_DENY_TOKENS) {
            if (normalized.contains(token)) {
                throw new IOException("custom valve rejected by source interface policy");
            }
        }
    }

    private static String normalizeSource(String source) {
        String current = decodeUnicodeEscapes(source == null ? "" : source).toLowerCase(Locale.ROOT);
        String previous;
        do {
            previous = current;
            current = foldLiteralConcats(current);
        } while (!current.equals(previous));
        return current;
    }

    private static String decodeUnicodeEscapes(String source) {
        Matcher matcher = UNICODE_ESCAPE.matcher(source);
        StringBuffer buffer = new StringBuffer(source.length());
        while (matcher.find()) {
            char value = (char) Integer.parseInt(matcher.group(1), 16);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String foldLiteralConcats(String source) {
        StringBuilder buffer = new StringBuilder(source.length());
        boolean changed = false;
        int index = 0;
        while (index < source.length()) {
            if (source.charAt(index) != '"') {
                buffer.append(source.charAt(index));
                index++;
                continue;
            }

            StringLiteral first = readStringLiteral(source, index);
            if (first == null) {
                buffer.append(source.charAt(index));
                index++;
                continue;
            }

            int plus = skipWhitespace(source, first.end);
            if (plus >= source.length() || source.charAt(plus) != '+') {
                buffer.append(source, index, first.end);
                index = first.end;
                continue;
            }

            int secondStart = skipWhitespace(source, plus + 1);
            StringLiteral second = readStringLiteral(source, secondStart);
            if (second == null) {
                buffer.append(source, index, first.end);
                index = first.end;
                continue;
            }

            changed = true;
            buffer.append('"').append(first.content).append(second.content).append('"');
            index = second.end;
        }
        return changed ? buffer.toString() : source;
    }

    private static int skipWhitespace(String value, int index) {
        int current = index;
        while (current < value.length() && Character.isWhitespace(value.charAt(current))) {
            current++;
        }
        return current;
    }

    private static StringLiteral readStringLiteral(String value, int start) {
        if (start < 0 || start >= value.length() || value.charAt(start) != '"') {
            return null;
        }
        StringBuilder content = new StringBuilder();
        boolean escaped = false;
        for (int index = start + 1; index < value.length(); index++) {
            char current = value.charAt(index);
            if (escaped) {
                content.append('\\').append(current);
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '"') {
                return new StringLiteral(content.toString(), index + 1);
            }
            content.append(current);
        }
        return null;
    }

    private static final class StringLiteral {
        private final String content;
        private final int end;

        private StringLiteral(String content, int end) {
            this.content = content;
            this.end = end;
        }
    }

    private static StandardContext extractContext(ServletContext servletContext) throws Exception {
        java.lang.reflect.Field facadeField = servletContext.getClass().getDeclaredField("context");
        facadeField.setAccessible(true);
        Object applicationContext = facadeField.get(servletContext);
        java.lang.reflect.Field contextField = applicationContext.getClass().getDeclaredField("context");
        contextField.setAccessible(true);
        return (StandardContext) contextField.get(applicationContext);
    }

    private static Path stageDirectory(Path themeRoot, String className, String sourcePath, String nonce) {
        Path previewRoot = themeRoot.resolve("preview").normalize();
        String digest = shortSha256(className + "|" + sourcePath + "|" + nonce);
        Path directory = previewRoot.resolve("stage2-" + digest).normalize();
        if (!directory.startsWith(previewRoot)) {
            throw new IllegalStateException("stage");
        }
        return directory;
    }

    private static void resetDirectory(Path outputDir) throws IOException {
        if (Files.exists(outputDir)) {
            deleteTree(outputDir);
        }
        Files.createDirectories(outputDir);
    }

    private static ActivePlan compileAndInstall(PendingPlan pending, String token) throws Exception {
        byte[] sourceBytes = readPlannedSource(pending.themeRoot, pending.sourcePath);
        if (sourceBytes == null) {
            throw new IOException("source");
        }

        String source = new String(sourceBytes, StandardCharsets.UTF_8);
        ensureAllowedSource(source);

        Path outputDir = stageDirectory(pending.themeRoot, pending.className, pending.sourcePath, pending.nonce);
        resetDirectory(outputDir);

        Path sourceFile = writeSource(outputDir, pending.className, source);
        compile(sourceFile, outputDir);
        ensureAllowedClasses(outputDir, pending.className);

        Valve valve = instantiateValve(outputDir, pending.className);
        ensureGuardInstalled();
        pending.liveKey = randomHex(12);
        ActivePlan active = new ActivePlan(pending.className, pending.themeRoot, outputDir, pending.liveKey, token, randomHex(8), System.currentTimeMillis());
        ACTIVE_PLANS.put(pending.className, active);
        ACTIVE_THEME_PLANS.put(pending.themeKey, active);
        Pipeline pipeline = pending.context.getPipeline();
        pipeline.addValve(new GuardedValve(valve, new ValveScope(pending.themeRoot, outputDir), active));
        return active;
    }

    private static void issuePendingReceipt(ActivePlan active, String bundleDigest) {
        if (active == null) {
            return;
        }
        active.pendingBundleDigest = normalizeLedgerDigest(bundleDigest);
        active.pendingReceiptId = randomHex(10) + shortSha256(active.liveKey + ":" + active.className + ":" + active.installNonce);
        active.pendingReceiptIssuedAt = System.currentTimeMillis();
        active.pendingReceiptConsumed = false;
    }

    private static String issueBundleTicket(String liveKey) {
        long expiresAt = System.currentTimeMillis() + BUNDLE_TICKET_TTL_MS;
        String issuedAt = Long.toHexString(expiresAt);
        String proof = sha256Hex(BUNDLE_TICKET_SECRET + ":" + liveKey + ":" + issuedAt).substring(0, 32);
        return issuedAt + "." + liveKey + "." + proof;
    }

    private static String readBundleRelay(ActivePlan active) throws IOException {
        String ticket = issueBundleTicket(active.liveKey);
        String challenge;
        String response;
        int port = Integer.getInteger("ezjvav.bundle.port", 24631).intValue();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1500);
            socket.setSoTimeout(3500);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 OutputStreamWriter writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)) {
                writeLine(writer, opHello() + " " + BUNDLE_NOTE_KEY);
                challenge = expect(reader.readLine(), opStep());
                String proof = sha256Hex(BUNDLE_NOTE_KEY + ":" + active.liveKey + ":" + ticket + ":" + challenge).substring(6, 38);
                writeLine(writer, opRead() + " " + active.liveKey + " " + ticket + " " + proof);
                response = expect(reader.readLine(), opDone());
            }
        }
        return response;
    }

    private static void writeLine(OutputStreamWriter writer, String value) throws IOException {
        writer.write(value);
        writer.write('\n');
        writer.flush();
    }

    private static String expect(String value, String prefix) throws IOException {
        if (value == null) {
            throw new IOException("relay closed");
        }
        String expected = prefix + " ";
        if (!value.startsWith(expected)) {
            throw new IOException("relay rejected");
        }
        return value.substring(expected.length()).trim();
    }

    private static String opHello() {
        return "HELO";
    }

    private static String opRead() {
        return "READ";
    }

    private static String opDone() {
        return "DONE";
    }

    private static String opStep() {
        return "STEP";
    }

    private static Path writeSource(Path outputDir, String className, String source) throws IOException {
        String relative = className.replace('.', '/') + ".java";
        Path sourceFile = outputDir.resolve(relative).normalize();
        if (!sourceFile.startsWith(outputDir)) {
            throw new IOException("source");
        }
        Files.createDirectories(sourceFile.getParent());
        Files.write(sourceFile, source.getBytes(StandardCharsets.UTF_8));
        return sourceFile;
    }

    private static void compile(Path sourceFile, Path outputDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("compiler unavailable");
        }
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(Arrays.asList(sourceFile.toFile()));
            List<String> options = new ArrayList<String>();
            options.add("-encoding");
            options.add("UTF-8");
            options.add("-source");
            options.add("8");
            options.add("-target");
            options.add("8");
            options.add("-proc:none");
            options.add("-classpath");
            options.add(buildClassPath());
            options.add("-d");
            options.add(outputDir.toString());
            Boolean ok = compiler.getTask(null, fileManager, null, options, null, units).call();
            if (!Boolean.TRUE.equals(ok)) {
                throw new IOException("custom valve compile failed");
            }
        }
    }

    private static String buildClassPath() {
        Set<String> entries = new LinkedHashSet<String>();
        String system = System.getProperty("java.class.path", "");
        if (system != null && !system.trim().isEmpty()) {
            for (String entry : system.split(java.util.regex.Pattern.quote(System.getProperty("path.separator", ":")))) {
                addEntry(entries, entry);
            }
        }
        addLoaderEntries(entries, Thread.currentThread().getContextClassLoader());
        addDirectoryEntries(entries, System.getProperty("catalina.home", "/opt/tomcat"), "lib");
        addDirectoryEntries(entries, System.getProperty("catalina.home", "/opt/tomcat"), "bin");
        StringBuilder classPath = new StringBuilder();
        String separator = System.getProperty("path.separator", ":");
        for (String entry : entries) {
            if (classPath.length() > 0) {
                classPath.append(separator);
            }
            classPath.append(entry);
        }
        return classPath.toString();
    }

    private static void addLoaderEntries(Set<String> entries, ClassLoader loader) {
        ClassLoader current = loader;
        while (current != null) {
            if (current instanceof URLClassLoader) {
                URL[] urls = ((URLClassLoader) current).getURLs();
                if (urls != null) {
                    for (URL url : urls) {
                        try {
                            addEntry(entries, new java.io.File(url.toURI()).getAbsolutePath());
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            current = current.getParent();
        }
    }

    private static void addDirectoryEntries(Set<String> entries, String root, String child) {
        if (root == null || root.trim().isEmpty()) {
            return;
        }
        Path directory = new java.io.File(root, child).toPath();
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.jar")) {
            for (Path jar : stream) {
                addEntry(entries, jar.toAbsolutePath().normalize().toString());
            }
        } catch (IOException ignored) {
        }
    }

    private static void addEntry(Set<String> entries, String entry) {
        if (entry != null && !entry.trim().isEmpty()) {
            entries.add(new java.io.File(entry).getAbsolutePath());
        }
    }

    private static void ensureAllowedClasses(Path outputDir, String className) throws IOException {
        try (java.util.stream.Stream<Path> walk = Files.walk(outputDir)) {
            List<Path> classes = walk
                .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".class"))
                .collect(java.util.stream.Collectors.toList());
            if (classes.size() != 1) {
                throw new IOException("custom valve rejected by class layout policy");
            }
            for (Path classFile : classes) {
                byte[] classBytes = Files.readAllBytes(classFile);
                String targetInternalName = className.replace('.', '/');
                for (String item : classReferences(classBytes)) {
                    if (!isAllowedValveReference(item, targetInternalName)) {
                        throw new IOException("custom valve rejected by bytecode allowlist policy");
                    }
                }
                inspectClassShape(classBytes, className);
            }
        }
    }

    private static boolean isAllowedValveReference(String internalName, String targetInternalName) {
        if (internalName == null) {
            return false;
        }
        if (internalName.equals(targetInternalName)) {
            return true;
        }
        return "java/lang/Object".equals(internalName)
            || "java/lang/String".equals(internalName)
            || "java/lang/StringBuilder".equals(internalName)
            || "java/lang/Integer".equals(internalName)
            || "java/lang/Exception".equals(internalName)
            || "java/lang/Throwable".equals(internalName)
            || "java/io/BufferedReader".equals(internalName)
            || "java/io/InputStreamReader".equals(internalName)
            || "java/io/OutputStreamWriter".equals(internalName)
            || "java/io/PrintWriter".equals(internalName)
            || "java/io/IOException".equals(internalName)
            || "java/security/MessageDigest".equals(internalName)
            || "[B".equals(internalName)
            || "javax/servlet/ServletException".equals(internalName)
            || "org/apache/catalina/Valve".equals(internalName)
            || "org/apache/catalina/connector/Request".equals(internalName)
            || "org/apache/catalina/connector/Response".equals(internalName)
            || "org/apache/catalina/valves/ValveBase".equals(internalName);
    }

    private static void inspectClassShape(byte[] classBytes, String className) throws IOException {
        ClassShape shape = ClassShape.parse(classBytes);
        String targetInternalName = className.replace('.', '/');
        boolean isTarget = targetInternalName.equals(shape.internalName);
        if (!isTarget) {
            throw new IOException("custom valve rejected by class identity policy");
        }
        boolean hasTargetConstructor = false;
        for (MethodShape method : shape.methods) {
            if ("<clinit>".equals(method.name) || "finalize".equals(method.name)) {
                throw new IOException("custom valve rejected by lifecycle policy");
            }
            if (isTarget && "<init>".equals(method.name) && "()V".equals(method.descriptor)) {
                hasTargetConstructor = true;
                if (!isEmptyValveConstructor(method.code)) {
                    throw new IOException("custom valve rejected by constructor policy");
                }
            }
        }
        for (MemberRef methodRef : methodReferences(classBytes)) {
            if (isDeniedValveMethod(methodRef)) {
                throw new IOException("custom valve rejected by method policy");
            }
        }
        if (isTarget && !hasTargetConstructor) {
            throw new IOException("custom valve constructor missing");
        }
    }

    private static boolean isDeniedValveMethod(MemberRef methodRef) {
        if (methodRef == null) {
            return false;
        }
        if ("org/apache/catalina/connector/Request".equals(methodRef.owner)) {
            return "setAttribute".equals(methodRef.name)
                || "getCookies".equals(methodRef.name)
                || "getHeader".equals(methodRef.name);
        }
        if ("org/apache/catalina/connector/Response".equals(methodRef.owner)) {
            return "addHeader".equals(methodRef.name)
                || "setHeader".equals(methodRef.name)
                || "addCookie".equals(methodRef.name);
        }
        return false;
    }

    private static boolean isEmptyValveConstructor(byte[] code) {
        if (code == null) {
            return false;
        }
        if (code.length == 5 && unsigned(code[0]) == 0x2a && unsigned(code[1]) == 0xb7 && unsigned(code[4]) == 0xb1) {
            return true;
        }
        return code.length == 6
            && unsigned(code[0]) == 0x2a
            && (unsigned(code[1]) == 0x03 || unsigned(code[1]) == 0x04)
            && unsigned(code[2]) == 0xb7
            && unsigned(code[5]) == 0xb1;
    }

    private static int unsigned(byte value) {
        return value & 0xff;
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
        for (int i = 1; i < count; i++) {
            int tag = input.readUnsignedByte();
            switch (tag) {
                case 1:
                    constants[i] = input.readUTF();
                    break;
                case 3:
                case 4:
                    input.skipBytes(4);
                    break;
                case 5:
                case 6:
                    input.skipBytes(8);
                    i++;
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

    private static List<MemberRef> methodReferences(byte[] classBytes) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(classBytes));
        if (input.readInt() != 0xCAFEBABE) {
            throw new IOException("class");
        }
        input.readUnsignedShort();
        input.readUnsignedShort();
        int count = input.readUnsignedShort();
        Object[] utf8 = new Object[count];
        ClassNameRef[] classes = new ClassNameRef[count];
        NameAndTypeRef[] names = new NameAndTypeRef[count];
        List<MemberIndexRef> refs = new ArrayList<MemberIndexRef>();
        for (int i = 1; i < count; i++) {
            int tag = input.readUnsignedByte();
            switch (tag) {
                case 1:
                    utf8[i] = input.readUTF();
                    break;
                case 3:
                case 4:
                    input.skipBytes(4);
                    break;
                case 5:
                case 6:
                    input.skipBytes(8);
                    i++;
                    break;
                case 7:
                    classes[i] = new ClassNameRef(input.readUnsignedShort());
                    break;
                case 8:
                case 16:
                case 19:
                case 20:
                    input.skipBytes(2);
                    break;
                case 9:
                    input.skipBytes(4);
                    break;
                case 10:
                case 11:
                    refs.add(new MemberIndexRef(input.readUnsignedShort(), input.readUnsignedShort()));
                    break;
                case 12:
                    names[i] = new NameAndTypeRef(input.readUnsignedShort(), input.readUnsignedShort());
                    break;
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
        List<MemberRef> methods = new ArrayList<MemberRef>();
        for (MemberIndexRef ref : refs) {
            if (ref == null || ref.classIndex <= 0 || ref.classIndex >= classes.length || ref.nameAndTypeIndex <= 0 || ref.nameAndTypeIndex >= names.length) {
                throw new IOException("class");
            }
            ClassNameRef ownerRef = classes[ref.classIndex];
            NameAndTypeRef nameRef = names[ref.nameAndTypeIndex];
            if (ownerRef == null || nameRef == null) {
                throw new IOException("class");
            }
            String owner = readUtf8(utf8, ownerRef.nameIndex);
            String name = readUtf8(utf8, nameRef.nameIndex);
            String descriptor = readUtf8(utf8, nameRef.descriptorIndex);
            methods.add(new MemberRef(owner, name, descriptor));
        }
        return methods;
    }

    private static String readUtf8(Object[] constants, int index) throws IOException {
        if (index <= 0 || index >= constants.length || !(constants[index] instanceof String)) {
            throw new IOException("class");
        }
        return (String) constants[index];
    }

    private static List<String> utf8Constants(byte[] classBytes) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(classBytes));
        if (input.readInt() != 0xCAFEBABE) {
            throw new IOException("class");
        }
        input.readUnsignedShort();
        input.readUnsignedShort();
        int count = input.readUnsignedShort();
        List<String> items = new ArrayList<String>();
        for (int i = 1; i < count; i++) {
            int tag = input.readUnsignedByte();
            switch (tag) {
                case 1:
                    int length = input.readUnsignedShort();
                    byte[] data = new byte[length + 2];
                    data[0] = (byte) ((length >>> 8) & 0xff);
                    data[1] = (byte) (length & 0xff);
                    input.readFully(data, 2, length);
                    items.add(readModifiedUtf(data));
                    break;
                case 3:
                case 4:
                    input.skipBytes(4);
                    break;
                case 5:
                case 6:
                    input.skipBytes(8);
                    i++;
                    break;
                case 7:
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
        return items;
    }

    private static String readModifiedUtf(byte[] data) {
        try {
            return new DataInputStream(new ByteArrayInputStream(data)).readUTF();
        } catch (IOException e) {
            return new String(data, 2, data.length - 2, StandardCharsets.ISO_8859_1);
        }
    }

    private static final class MemberRef {
        private final String owner;
        private final String name;
        private final String descriptor;

        private MemberRef(String owner, String name, String descriptor) {
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor;
        }
    }

    private static final class MemberIndexRef {
        private final int classIndex;
        private final int nameAndTypeIndex;

        private MemberIndexRef(int classIndex, int nameAndTypeIndex) {
            this.classIndex = classIndex;
            this.nameAndTypeIndex = nameAndTypeIndex;
        }
    }

    private static final class ClassNameRef {
        private final int nameIndex;

        private ClassNameRef(int nameIndex) {
            this.nameIndex = nameIndex;
        }
    }

    private static final class NameAndTypeRef {
        private final int nameIndex;
        private final int descriptorIndex;

        private NameAndTypeRef(int nameIndex, int descriptorIndex) {
            this.nameIndex = nameIndex;
            this.descriptorIndex = descriptorIndex;
        }
    }

    private static final class ClassShape {
        private final String internalName;
        private final List<MethodShape> methods;

        private ClassShape(String internalName, List<MethodShape> methods) {
            this.internalName = internalName;
            this.methods = methods;
        }

        private static ClassShape parse(byte[] classBytes) throws IOException {
            ClassReader reader = new ClassReader(classBytes);
            if (reader.u4() != 0xCAFEBABE) {
                throw new IOException("class");
            }
            reader.u2();
            reader.u2();
            int count = reader.u2();
            Object[] constants = new Object[count];
            for (int index = 1; index < count; index++) {
                int tag = reader.u1();
                switch (tag) {
                    case 1:
                        constants[index] = reader.utf8();
                        break;
                    case 3:
                    case 4:
                        reader.skip(4);
                        break;
                    case 5:
                    case 6:
                        reader.skip(8);
                        index++;
                        break;
                    case 7:
                        constants[index] = Integer.valueOf(reader.u2());
                        break;
                    case 8:
                    case 16:
                    case 19:
                    case 20:
                        reader.skip(2);
                        break;
                    case 9:
                    case 10:
                    case 11:
                    case 12:
                    case 17:
                    case 18:
                        reader.skip(4);
                        break;
                    case 15:
                        reader.skip(3);
                        break;
                    default:
                        throw new IOException("class");
                }
            }

            reader.u2();
            int thisClass = reader.u2();
            reader.u2();
            String internalName = className(constants, thisClass);
            int interfaces = reader.u2();
            reader.skip(interfaces * 2);
            skipMembers(reader);

            int methodCount = reader.u2();
            List<MethodShape> methods = new ArrayList<MethodShape>();
            for (int methodIndex = 0; methodIndex < methodCount; methodIndex++) {
                reader.u2();
                String name = utf8(constants, reader.u2());
                String descriptor = utf8(constants, reader.u2());
                int attributeCount = reader.u2();
                byte[] code = null;
                for (int attributeIndex = 0; attributeIndex < attributeCount; attributeIndex++) {
                    String attributeName = utf8(constants, reader.u2());
                    int length = reader.u4();
                    int next = reader.position + length;
                    if ("Code".equals(attributeName)) {
                        code = readCode(reader);
                    }
                    reader.position = next;
                }
                methods.add(new MethodShape(name, descriptor, code));
            }
            return new ClassShape(internalName, methods);
        }

        private static void skipMembers(ClassReader reader) throws IOException {
            int fields = reader.u2();
            for (int fieldIndex = 0; fieldIndex < fields; fieldIndex++) {
                reader.skip(6);
                skipAttributes(reader);
            }
        }

        private static void skipAttributes(ClassReader reader) throws IOException {
            int attributes = reader.u2();
            for (int index = 0; index < attributes; index++) {
                reader.u2();
                reader.skip(reader.u4());
            }
        }

        private static byte[] readCode(ClassReader reader) throws IOException {
            reader.u2();
            reader.u2();
            int length = reader.u4();
            byte[] code = reader.bytes(length);
            int exceptionTable = reader.u2();
            reader.skip(exceptionTable * 8);
            skipAttributes(reader);
            return code;
        }

        private static String className(Object[] constants, int classIndex) throws IOException {
            Object value = constants[classIndex];
            if (!(value instanceof Integer)) {
                throw new IOException("class");
            }
            return utf8(constants, ((Integer) value).intValue());
        }

        private static String utf8(Object[] constants, int index) throws IOException {
            Object value = constants[index];
            if (!(value instanceof String)) {
                throw new IOException("class");
            }
            return (String) value;
        }
    }

    private static final class MethodShape {
        private final String name;
        private final String descriptor;
        private final byte[] code;

        private MethodShape(String name, String descriptor, byte[] code) {
            this.name = name;
            this.descriptor = descriptor;
            this.code = code;
        }
    }

    private static final class ClassReader {
        private final byte[] data;
        private int position;

        private ClassReader(byte[] data) {
            this.data = data;
        }

        private int u1() throws IOException {
            ensure(1);
            return data[position++] & 0xff;
        }

        private int u2() throws IOException {
            ensure(2);
            int value = ((data[position] & 0xff) << 8) | (data[position + 1] & 0xff);
            position += 2;
            return value;
        }

        private int u4() throws IOException {
            ensure(4);
            int value = ((data[position] & 0xff) << 24)
                | ((data[position + 1] & 0xff) << 16)
                | ((data[position + 2] & 0xff) << 8)
                | (data[position + 3] & 0xff);
            position += 4;
            return value;
        }

        private String utf8() throws IOException {
            int length = u2();
            byte[] raw = new byte[length + 2];
            raw[0] = (byte) ((length >>> 8) & 0xff);
            raw[1] = (byte) (length & 0xff);
            byte[] value = bytes(length);
            System.arraycopy(value, 0, raw, 2, value.length);
            return readModifiedUtf(raw);
        }

        private byte[] bytes(int length) throws IOException {
            ensure(length);
            byte[] value = Arrays.copyOfRange(data, position, position + length);
            position += length;
            return value;
        }

        private void skip(int length) throws IOException {
            ensure(length);
            position += length;
        }

        private void ensure(int length) throws IOException {
            if (length < 0 || position + length > data.length) {
                throw new IOException("class");
            }
        }
    }

    private static Valve instantiateValve(Path outputDir, String className) throws Exception {
        ClassLoader parent = Thread.currentThread().getContextClassLoader();
        URLClassLoader loader = new URLClassLoader(new URL[] { outputDir.toUri().toURL() }, parent);
        Class<?> type = Class.forName(className, false, loader);
        if (!Valve.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException("not a valve");
        }
        Object value = type.getDeclaredConstructor().newInstance();
        return (Valve) value;
    }

    private static void ensureGuardInstalled() {
        SecurityManager current = System.getSecurityManager();
        if (current instanceof InvokeGuardSecurityManager) {
            return;
        }
        if (current == null) {
            System.setSecurityManager(new InvokeGuardSecurityManager());
        }
    }

    private static final class GuardedValve extends ValveBase {
        private final Valve delegate;
        private final NextBridgeValve bridgeNext = new NextBridgeValve();
        private final ValveScope scope;
        private final ActivePlan activePlan;

        private GuardedValve(Valve delegate, ValveScope scope, ActivePlan activePlan) {
            super(true);
            this.delegate = delegate;
            this.scope = scope;
            this.activePlan = activePlan;
        }

        @Override
        public void invoke(Request request, Response response) throws IOException, ServletException {
            bridgeNext.setRealNext(getNext());
            delegate.setNext(bridgeNext);
            VALVE_GUARD.set(Boolean.TRUE);
            VALVE_SCOPE.set(scope);
            CURRENT_ACTIVE_PLAN.set(activePlan);
            try {
                delegate.invoke(request, response);
            } finally {
                CURRENT_ACTIVE_PLAN.remove();
                VALVE_SCOPE.remove();
                VALVE_GUARD.remove();
                bridgeNext.clearRealNext();
            }
        }
    }

    private static final class NextBridgeValve extends ValveBase {
        private Valve realNext;

        private NextBridgeValve() {
            super(true);
        }

        private void setRealNext(Valve realNext) {
            this.realNext = realNext;
        }

        private void clearRealNext() {
            this.realNext = null;
        }

        @Override
        public void invoke(Request request, Response response) throws IOException, ServletException {
            boolean guarded = Boolean.TRUE.equals(VALVE_GUARD.get());
            ValveScope scope = VALVE_SCOPE.get();
            VALVE_GUARD.remove();
            VALVE_SCOPE.remove();
            try {
                if (realNext != null) {
                    realNext.invoke(request, response);
                }
            } finally {
                if (guarded) {
                    VALVE_GUARD.set(Boolean.TRUE);
                    if (scope != null) {
                        VALVE_SCOPE.set(scope);
                    }
                }
            }
        }
    }

    private static final class InvokeGuardSecurityManager extends SecurityManager {
        private String normalizePath(String file) {
            if (file == null) {
                return "";
            }
            try {
                return new java.io.File(file).getAbsoluteFile().toPath().normalize().toString();
            } catch (Exception e) {
                return file;
            }
        }

        private boolean allowPlatformRead(String file) {
            String path = normalizePath(file);
            String javaHome = normalizePath(System.getProperty("java.home", "/opt/java/openjdk"));
            String catalinaBase = normalizePath(System.getProperty("catalina.base", "/opt/tomcat"));
            String webInf = normalizePath(new java.io.File(catalinaBase, "webapps/ROOT/WEB-INF").getPath());
            String catalinaLib = normalizePath(new java.io.File(System.getProperty("catalina.home", "/opt/tomcat"), "lib").getPath());
            return path.startsWith(javaHome)
                || path.startsWith(webInf)
                || path.startsWith(catalinaLib);
        }

        private boolean allowBundleSocket(String value) {
            return false;
        }

        private boolean allowBundleConnect(String host, int port) {
            return false;
        }

        @Override
        public void checkPermission(Permission permission) {
            if (!Boolean.TRUE.equals(VALVE_GUARD.get()) || permission == null) {
                return;
            }
            String name = permission.getName() == null ? "" : permission.getName();
            String actions = permission.getActions() == null ? "" : permission.getActions().toLowerCase(Locale.ROOT);
            ValveScope scope = VALVE_SCOPE.get();
            if (permission instanceof FilePermission) {
                if (actions.contains("read")) {
                    if ((scope == null || !scope.allowsRead(name)) && !allowPlatformRead(name)) {
                        throw new SecurityException("custom valve read denied");
                    }
                }
                if (actions.contains("write") || actions.contains("delete")) {
                    if (scope == null || !scope.allowsWrite(name)) {
                        throw new SecurityException("custom valve write denied");
                    }
                }
                if (actions.contains("execute")) {
                    throw new SecurityException("custom valve exec denied");
                }
            }
            if (permission instanceof SocketPermission) {
                if (allowBundleSocket(name)) {
                    return;
                }
                throw new SecurityException("custom valve socket denied");
            }
            if (permission instanceof PropertyPermission && actions.contains("write")) {
                throw new SecurityException("custom valve property denied");
            }
            if (permission instanceof java.lang.reflect.ReflectPermission
                && "suppressAccessChecks".equals(name)) {
                throw new SecurityException("custom valve reflect denied");
            }
            if (permission instanceof RuntimePermission) {
                if ("setSecurityManager".equals(name)
                    || "createClassLoader".equals(name)
                    || "setContextClassLoader".equals(name)
                    || name.startsWith("accessDeclaredMembers")
                    || name.startsWith("loadLibrary")
                    || name.startsWith("modifyThread")) {
                    throw new SecurityException("custom valve runtime denied");
                }
            }
        }

        @Override
        public void checkPermission(java.security.Permission permission, Object context) {
            checkPermission(permission);
        }

        @Override
        public void checkRead(String file) {
            if (Boolean.TRUE.equals(VALVE_GUARD.get())) {
                ValveScope scope = VALVE_SCOPE.get();
                if ((scope == null || !scope.allowsRead(file)) && !allowPlatformRead(file)) {
                    throw new SecurityException("custom valve read denied");
                }
            }
        }

        @Override
        public void checkRead(String file, Object context) {
            checkRead(file);
        }

        @Override
        public void checkWrite(String file) {
            if (Boolean.TRUE.equals(VALVE_GUARD.get())) {
                ValveScope scope = VALVE_SCOPE.get();
                if (scope == null || !scope.allowsWrite(file)) {
                    throw new SecurityException("custom valve write denied");
                }
            }
        }

        @Override
        public void checkDelete(String file) {
            checkWrite(file);
        }

        @Override
        public void checkConnect(String host, int port) {
            if (Boolean.TRUE.equals(VALVE_GUARD.get()) && !allowBundleConnect(host, port)) {
                throw new SecurityException("custom valve socket denied");
            }
        }

        @Override
        public void checkAccept(String host, int port) {
            if (Boolean.TRUE.equals(VALVE_GUARD.get())) {
                throw new SecurityException("custom valve socket denied");
            }
        }

        @Override
        public void checkListen(int port) {
            if (Boolean.TRUE.equals(VALVE_GUARD.get())) {
                throw new SecurityException("custom valve socket denied");
            }
        }

        @Override
        public void checkExec(String command) {
            if (Boolean.TRUE.equals(VALVE_GUARD.get())) {
                throw new SecurityException("custom valve exec denied");
            }
        }
    }

    private static String shortSha256(String value) {
        return sha256Hex(value).substring(0, 16);
    }

    private static String reverse(String value) {
        return new StringBuilder(value == null ? "" : value).reverse().toString();
    }

    private static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] raw = digest.digest(value);
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
            throw new IllegalStateException("sha256", e);
        }
    }

    private static String randomHex(int bytes) {
        byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
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

    public static final class ChallengeBundle {
        private final String cookieValue;
        private final String marker;
        private final String witness;

        private ChallengeBundle(String cookieValue, String marker, String witness) {
            this.cookieValue = cookieValue;
            this.marker = marker;
            this.witness = witness;
        }

        public String getCookieValue() {
            return cookieValue;
        }

        public String getMarker() {
            return marker;
        }

        public String getWitness() {
            return witness;
        }
    }

    private static final class StagePlan {
        private final String themeKey;
        private final String className;
        private final String sourcePath;
        private final String sourceDigest;
        private final String previewTicket;
        private final String nonce;

        private StagePlan(String themeKey, String className, String sourcePath, String sourceDigest, String previewTicket, String nonce) {
            this.themeKey = themeKey;
            this.className = className;
            this.sourcePath = sourcePath;
            this.sourceDigest = sourceDigest;
            this.previewTicket = previewTicket;
            this.nonce = nonce;
        }
    }

    private static final class PreviewHandle {
        private final String themeKey;
        private final String previewTicket;
        private final String handle;
        private final String witness;
        private final long issuedAt;
        private boolean redeemed;

        private PreviewHandle(String themeKey, String previewTicket, String handle, String witness, long issuedAt) {
            this.themeKey = themeKey;
            this.previewTicket = previewTicket;
            this.handle = handle;
            this.witness = witness;
            this.issuedAt = issuedAt;
        }
    }

    private static final class ActivePlan {
        private final String className;
        private final Path themeRoot;
        private final Path stageDir;
        private final String liveKey;
        private final String token;
        private final String installNonce;
        private final long installedAt;
        private String pendingReceiptId;
        private String pendingBundleDigest;
        private long pendingReceiptIssuedAt;
        private boolean pendingReceiptConsumed;

        private ActivePlan(String className, Path themeRoot, Path stageDir, String liveKey, String token, String installNonce, long installedAt) {
            this.className = className;
            this.themeRoot = themeRoot;
            this.stageDir = stageDir;
            this.liveKey = liveKey;
            this.token = token;
            this.installNonce = installNonce;
            this.installedAt = installedAt;
        }
    }

    private static final class ValveScope {
        private final Path themeRoot;
        private final Path stageDir;

        private ValveScope(Path themeRoot, Path stageDir) {
            this.themeRoot = themeRoot.toAbsolutePath().normalize();
            this.stageDir = stageDir.toAbsolutePath().normalize();
        }

        private boolean allowsRead(String file) {
            Path path = normalize(file);
            return path != null && (path.startsWith(themeRoot) || path.startsWith(stageDir));
        }

        private boolean allowsWrite(String file) {
            Path path = normalize(file);
            return path != null && path.startsWith(stageDir);
        }

        private static Path normalize(String file) {
            if (file == null || file.trim().isEmpty()) {
                return null;
            }
            try {
                return new java.io.File(file).toPath().toAbsolutePath().normalize();
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private static final class PendingPlan {
        private final String themeKey;
        private final Path themeRoot;
        private final StandardContext context;
        private final String className;
        private final String sourcePath;
        private final String nonce;
        private final String clientKey;
        private String challenge;
        private String marker;
        private String liveKey;
        private String bundleDigest;
        private String installWitness;
        private long challengeIssuedAt;
        private long exportTouchedAt;

        private PendingPlan(String themeKey, Path themeRoot, StandardContext context, String className, String sourcePath, String nonce, String clientKey) {
            this.themeKey = themeKey;
            this.themeRoot = themeRoot;
            this.context = context;
            this.className = className;
            this.sourcePath = sourcePath;
            this.nonce = nonce;
            this.clientKey = clientKey;
        }
    }

    private static final class DecoyPass {
        private final String client;
        private final String cookieValue;
        private final String marker;
        private final String digest;
        private final long createdAt;
        private boolean completed;
        private long completedAt;

        private DecoyPass(String client, String cookieValue, String marker, String digest, long createdAt) {
            this.client = client;
            this.cookieValue = cookieValue;
            this.marker = marker;
            this.digest = digest;
            this.createdAt = createdAt;
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(path)) {
            List<Path> items = walk.sorted((a, b) -> b.compareTo(a)).collect(java.util.stream.Collectors.toList());
            for (Path item : items) {
                Files.deleteIfExists(item);
            }
        }
    }
}
