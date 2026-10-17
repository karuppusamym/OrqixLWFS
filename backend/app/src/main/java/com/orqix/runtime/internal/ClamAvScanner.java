package com.orqix.runtime.internal;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.orqix.shared.config.AttachmentSettings;
import org.springframework.stereotype.Component;

/**
 * ClamAV over the clamd TCP protocol (INSTREAM): chunks prefixed with their length, a zero length ends the stream, and
 * the answer is {@code stream: OK}, {@code stream: <signature> FOUND} or {@code ... ERROR}. Disabled unless
 * {@code orqix.attachments.clamav.enabled=true}. Any connection problem or unexpected answer is {@code ERROR}, never
 * {@code CLEAN}: a file that could not be scanned is not served. Only the outcome is logged, never content or names.
 */
@Component
@ConditionalOnProperty(name = "orqix.attachments.clamav.enabled", havingValue = "true")
public class ClamAvScanner implements AttachmentScanner {

    private static final Logger log = LoggerFactory.getLogger(ClamAvScanner.class);
    private static final int CHUNK = 64 * 1024;
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();

    private static ScheduledThreadPoolExecutor deadlines() {
        var executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            var thread = new Thread(runnable, "attachment-scan-deadline");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private final AttachmentSettings.ClamAv settings;

    public ClamAvScanner(AttachmentSettings settings) {
        this.settings = settings.clamav();
    }

    @Override
    public Status scan(byte[] content) {
        int timeout = (int) settings.timeout().toMillis();
        try (Socket socket = new Socket()) {
            long deadline = System.nanoTime() + settings.timeout().toNanos();
            // SO_TIMEOUT covers reads only. Closing at the deadline also interrupts blocked writes/connects.
            var close = DEADLINES.schedule(() -> { try { socket.close(); } catch (IOException ignored) { } }, timeout, TimeUnit.MILLISECONDS);
            try {
            socket.connect(new InetSocketAddress(settings.host(), settings.port()), timeout);
            socket.setSoTimeout(timeout);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            for (int offset = 0; offset < content.length; offset += CHUNK) {
                int length = Math.min(CHUNK, content.length - offset);
                out.writeInt(length);
                out.write(content, offset, length);
            }
            out.writeInt(0);
            out.flush();
            String answer = read(socket.getInputStream());
            if (System.nanoTime() >= deadline) return Status.ERROR;
            if (answer.equals("stream: OK")) {
                return Status.CLEAN;
            }
            if (answer.startsWith("stream: ") && answer.endsWith(" FOUND")) {
                return Status.INFECTED;
            }
            log.warn("ClamAV scan did not complete: unexpected answer");
            return Status.ERROR;
            } finally { close.cancel(false); }
        } catch (IOException e) {
            log.warn("ClamAV scan failed: {}", e.getClass().getSimpleName());
            return Status.ERROR;
        }
    }

    private static String read(InputStream in) throws IOException {
        var buffer = new ByteArrayOutputStream();
        for (int i = 0; i < 512; i++) {
            int value = in.read();
            if (value == 0) return buffer.toString(StandardCharsets.US_ASCII).trim();
            if (value < 0 || value > 127) throw new IOException("Incomplete scan response");
            buffer.write(value);
        }
        throw new IOException("Scan response exceeds bounds");
    }
}
