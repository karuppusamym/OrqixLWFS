package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.orqix.runtime.internal.AttachmentScanner;
import com.orqix.runtime.internal.ClamAvScanner;
import com.orqix.shared.config.AttachmentSettings;

/** The ClamAV INSTREAM client against a fake clamd: clean, found, garbage and no server at all. */
class ClamAvScannerTest {

    private static AttachmentSettings settings(int port) {
        return new AttachmentSettings(10485760, 25, 52428800, Duration.ofHours(24), 10, 30,
                new AttachmentSettings.ClamAv(true, "127.0.0.1", port, Duration.ofSeconds(3)));
    }

    /** Serves one scan: reads the command and chunks to the zero length, answers with {@code reply}. */
    private static Thread server(ServerSocket socket, String reply, int[] received) {
        Thread t = new Thread(() -> {
            try (var client = socket.accept()) {
                DataInputStream in = new DataInputStream(client.getInputStream());
                byte[] command = in.readNBytes("zINSTREAM\0".length());
                assertThat(new String(command, StandardCharsets.US_ASCII)).isEqualTo("zINSTREAM\0");
                int length;
                while ((length = in.readInt()) > 0) {
                    received[0] += in.readNBytes(length).length;
                }
                OutputStream out = client.getOutputStream();
                out.write((reply + "\0").getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (Exception ignored) {
                // the client side asserts the outcome
            }
        });
        t.start();
        return t;
    }

    private AttachmentScanner.Status scan(String reply, int size, int[] received) throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            Thread t = server(socket, reply, received);
            AttachmentScanner.Status status = new ClamAvScanner(settings(socket.getLocalPort())).scan(new byte[size]);
            t.join(3000);
            return status;
        }
    }

    @Test
    void cleanFoundAndUnexpectedAnswers() throws Exception {
        int[] received = new int[1];
        assertThat(scan("stream: OK", 200_000, received)).isEqualTo(AttachmentScanner.Status.CLEAN);
        assertThat(received[0]).isEqualTo(200_000);
        assertThat(scan("stream: Eicar-Test-Signature FOUND", 10, new int[1])).isEqualTo(AttachmentScanner.Status.INFECTED);
        assertThat(scan("INSTREAM size limit exceeded. ERROR", 10, new int[1])).isEqualTo(AttachmentScanner.Status.ERROR);
    }

    @Test
    void anUnreachableScannerIsAnErrorNeverClean() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        assertThat(new ClamAvScanner(settings(port)).scan(new byte[10])).isEqualTo(AttachmentScanner.Status.ERROR);
        assertThat(AttachmentScanner.Status.ERROR.blocksDownload()).isTrue();
        assertThat(AttachmentScanner.Status.NOT_SCANNED.blocksDownload()).isFalse();
    }
}
