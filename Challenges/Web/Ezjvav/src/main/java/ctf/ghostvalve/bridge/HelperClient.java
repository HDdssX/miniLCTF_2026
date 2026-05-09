package ctf.ghostvalve.bridge;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class HelperClient {
    private static final int MAX_FRAME = 8192;
    private static final byte VERSION = 1;
    private static final byte DEFAULT_PING_OPCODE = 1;
    private static final byte DEFAULT_PREVIEW_OPCODE = 2;
    private static final byte DEFAULT_INSTALL_OPCODE = 3;
    private static final byte STATUS_OK = 1;
    private static final long PROCESS_TIMEOUT_MS = 2500L;
    private static final String DEFAULT_HELPER_PATH = "/opt/ghost/bridge/ghost-helper";

    private final String helperPath;
    private final byte pingOpcode;
    private final byte previewOpcode;
    private final byte installOpcode;
    private final int[] previewOrder;
    private final int[] installOrder;

    private HelperClient(String helperPath) {
        this.helperPath = helperPath == null || helperPath.trim().isEmpty()
            ? DEFAULT_HELPER_PATH
            : helperPath.trim();
        this.pingOpcode = readOpcode("ezjvav.helper.op.ping", DEFAULT_PING_OPCODE);
        this.previewOpcode = readOpcode("ezjvav.helper.op.preview", DEFAULT_PREVIEW_OPCODE);
        this.installOpcode = readOpcode("ezjvav.helper.op.install", DEFAULT_INSTALL_OPCODE);
        this.previewOrder = readOrder("ezjvav.helper.order.preview", 5);
        this.installOrder = readOrder("ezjvav.helper.order.install", 7);
    }

    static HelperClient defaultClient() {
        return new HelperClient(System.getProperty("ezjvav.helper.bin", DEFAULT_HELPER_PATH));
    }

    String previewVerify(String previewHandle, String note, String headline, String accent, String witness) {
        Response response = invoke(previewOpcode, reorder(previewOrder, previewHandle, note, headline, accent, witness));
        if (response == null || response.status != STATUS_OK || response.fields.isEmpty()) {
            return null;
        }
        return new String(response.fields.get(0), StandardCharsets.UTF_8).trim().toLowerCase(java.util.Locale.ROOT);
    }

    boolean installVerify(String clientKey, String token, String challenge, String marker, String bundleDigest, String proof, String witness) {
        Response response = invoke(installOpcode, reorder(installOrder, clientKey, token, challenge, marker, bundleDigest, proof, witness));
        return response != null && response.status == STATUS_OK;
    }

    boolean ping() {
        Response response = invoke(pingOpcode);
        return response != null && response.status == STATUS_OK;
    }

    private Response invoke(byte opcode, String... fields) {
        Process process = null;
        try {
            process = new ProcessBuilder(helperPath)
                .redirectErrorStream(true)
                .start();
            try (OutputStream output = process.getOutputStream()) {
                output.write(buildRequest(opcode, fields));
                output.flush();
            }
            byte[] responseBytes;
            try (InputStream input = process.getInputStream()) {
                responseBytes = readAll(input);
            }
            if (!process.waitFor(PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0 || responseBytes.length < 8) {
                return null;
            }
            return parseResponse(responseBytes);
        } catch (Exception e) {
            return null;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String[] reorder(int[] order, String... canonical) {
        if (order.length != canonical.length) {
            return canonical;
        }
        String[] wire = new String[canonical.length];
        for (int index = 0; index < order.length; index++) {
            int canonicalIndex = order[index];
            if (canonicalIndex < 0 || canonicalIndex >= canonical.length) {
                return canonical;
            }
            wire[index] = canonical[canonicalIndex];
        }
        return wire;
    }

    private static byte readOpcode(String key, byte fallback) {
        String value = System.getProperty(key, "").trim();
        if (value.isEmpty()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value);
            return (byte) (parsed & 0xff);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int[] readOrder(String key, int length) {
        String value = System.getProperty(key, "").trim();
        if (value.isEmpty()) {
            int[] identity = new int[length];
            for (int i = 0; i < length; i++) {
                identity[i] = i;
            }
            return identity;
        }
        String[] parts = value.split(",");
        if (parts.length != length) {
            return identity(length);
        }
        int[] order = new int[length];
        boolean[] seen = new boolean[length];
        for (int index = 0; index < parts.length; index++) {
            try {
                int parsed = Integer.parseInt(parts[index].trim());
                if (parsed < 0 || parsed >= length || seen[parsed]) {
                    return identity(length);
                }
                seen[parsed] = true;
                order[index] = parsed;
            } catch (NumberFormatException e) {
                return identity(length);
            }
        }
        return order;
    }

    private static int[] identity(int length) {
        int[] identity = new int[length];
        for (int i = 0; i < length; i++) {
            identity[i] = i;
        }
        return identity;
    }

    private static byte[] buildRequest(byte opcode, String... fields) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(body)) {
            output.writeByte(VERSION);
            output.writeByte(opcode & 0xff);
            output.writeShort(fields.length);
            for (String field : fields) {
                byte[] raw = normalizeField(field);
                if (raw.length == 0 || raw.length > 0xffff) {
                    throw new IOException("field");
                }
                output.writeShort(raw.length);
                output.write(raw);
            }
        }
        byte[] payload = body.toByteArray();
        if (payload.length == 0 || payload.length > MAX_FRAME) {
            throw new IOException("frame");
        }
        ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 4);
        try (DataOutputStream output = new DataOutputStream(frame)) {
            output.writeInt(payload.length);
            output.write(payload);
        }
        return frame.toByteArray();
    }

    private static Response parseResponse(byte[] raw) throws IOException {
        if (raw.length < 8) {
            throw new IOException("response");
        }
        int declared = ((raw[0] & 0xff) << 24)
            | ((raw[1] & 0xff) << 16)
            | ((raw[2] & 0xff) << 8)
            | (raw[3] & 0xff);
        if (declared <= 0 || declared != raw.length - 4 || declared > MAX_FRAME) {
            throw new IOException("length");
        }
        int offset = 4;
        if (raw[offset++] != VERSION) {
            throw new IOException("version");
        }
        byte status = raw[offset++];
        int count = ((raw[offset++] & 0xff) << 8) | (raw[offset++] & 0xff);
        List<byte[]> fields = new ArrayList<byte[]>(count);
        for (int index = 0; index < count; index++) {
            if (offset + 2 > raw.length) {
                throw new IOException("field-len");
            }
            int size = ((raw[offset++] & 0xff) << 8) | (raw[offset++] & 0xff);
            if (size <= 0 || offset + size > raw.length) {
                throw new IOException("field");
            }
            byte[] value = new byte[size];
            System.arraycopy(raw, offset, value, 0, size);
            fields.add(value);
            offset += size;
        }
        if (offset != raw.length) {
            throw new IOException("tail");
        }
        return new Response(status, fields);
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read > 0) {
                output.write(buffer, 0, read);
            }
        }
        return output.toByteArray();
    }

    private static byte[] normalizeField(String value) {
        String text = value == null ? "" : value.trim();
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static final class Response {
        private final byte status;
        private final List<byte[]> fields;

        private Response(byte status, List<byte[]> fields) {
            this.status = status;
            this.fields = fields;
        }
    }
}
