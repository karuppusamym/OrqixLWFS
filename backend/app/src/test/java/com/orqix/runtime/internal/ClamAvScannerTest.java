package com.orqix.runtime.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.DataInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import com.orqix.shared.config.AttachmentSettings;

class ClamAvScannerTest {
    private AttachmentSettings settings(int port, Duration timeout) {
        return new AttachmentSettings(1024, 2, 2048, Duration.ofHours(1), 2, 10,
                new AttachmentSettings.ClamAv(true, "127.0.0.1", port, timeout));
    }

    private AttachmentScanner.Status scan(String answer, boolean terminated, Duration timeout) throws Exception {
        try (var server = new ServerSocket(0)) {
            var release = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();
            var worker = Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    var in = new DataInputStream(socket.getInputStream());
                    assertThat(new String(in.readNBytes(10), StandardCharsets.US_ASCII)).isEqualTo("zINSTREAM\0");
                    int length = in.readInt();
                    assertThat(in.readNBytes(length)).isEqualTo("content".getBytes(StandardCharsets.UTF_8));
                    assertThat(in.readInt()).isZero();
                    socket.getOutputStream().write((answer + (terminated ? "\0" : "")).getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    // A correct NUL-framed reply must finish without waiting for EOF.
                    release.await();
                } catch (Throwable error) { failure.set(error); }
            });
            try {
                return new ClamAvScanner(settings(server.getLocalPort(), timeout)).scan("content".getBytes(StandardCharsets.UTF_8));
            } finally {
                release.countDown();
                worker.join(1000);
                assertThat(failure.get()).isNull();
            }
        }
    }

    @Test void nulFramedCleanAndInfectedResultsDoNotWaitForSocketClose() throws Exception {
        assertThat(scan("stream: OK", true, Duration.ofSeconds(1))).isEqualTo(AttachmentScanner.Status.CLEAN);
        assertThat(scan("stream: Test-Signature FOUND", true, Duration.ofSeconds(1))).isEqualTo(AttachmentScanner.Status.INFECTED);
    }

    @Test void malformedOversizedAndMissingTerminatorsFailClosed() throws Exception {
        assertThat(scan("unexpectedOK", true, Duration.ofSeconds(1))).isEqualTo(AttachmentScanner.Status.ERROR);
        assertThat(scan("x".repeat(513), true, Duration.ofSeconds(1))).isEqualTo(AttachmentScanner.Status.ERROR);
        long started = System.nanoTime();
        assertThat(scan("stream: OK", false, Duration.ofMillis(100))).isEqualTo(AttachmentScanner.Status.ERROR);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test void unreachableConfiguredScannerNeverApprovesContent() throws Exception {
        int port;
        try (var server = new ServerSocket(0)) { port = server.getLocalPort(); }
        assertThat(new ClamAvScanner(settings(port, Duration.ofMillis(100))).scan(new byte[] { 1 }))
                .isEqualTo(AttachmentScanner.Status.ERROR);
    }

    @Test void deadlineAlsoInterruptsWritesToAScannerThatDoesNotConsumeTheStream() throws Exception {
        try (var server = new ServerSocket(0)) {
            var release = new CountDownLatch(1);
            var worker = Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) { release.await(); }
                catch (Exception ignored) { }
            });
            long started = System.nanoTime();
            try {
                assertThat(new ClamAvScanner(settings(server.getLocalPort(), Duration.ofMillis(100)))
                        .scan(new byte[10 * 1024 * 1024])).isEqualTo(AttachmentScanner.Status.ERROR);
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            } finally { release.countDown(); worker.join(1000); }
        }
    }
}
