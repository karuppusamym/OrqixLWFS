package com.orqix.catalog.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * The sandboxed document an extension runs in (S111). Only the fixed bridge and the stored bundle may execute, each
 * admitted by its SHA-256 in the policy. The document has no network, form, frame, worker or same-origin capability;
 * it talks to the host only through the bridge's postMessage protocol.
 */
public final class UiExtensionFrame {
    public static final String BRIDGE = load();
    private static final String STYLE = "body{margin:0;padding:8px;font:14px/1.45 system-ui,sans-serif;color:CanvasText;background:Canvas}";
    /** Powerful browser features the frame never gets; the host iframe's {@code allow} attribute repeats this list. */
    public static final String PERMISSIONS = "camera=(), microphone=(), geolocation=(), payment=(), usb=(), fullscreen=(), "
            + "display-capture=(), clipboard-read=(), clipboard-write=(), publickey-credentials-get=(), autoplay=()";

    private UiExtensionFrame() { }

    public record Document(String html, String policy) { }

    public static Document build(String bundle) {
        String html = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"referrer\" content=\"no-referrer\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Extension</title>"
                + "<style>" + STYLE + "</style></head><body><div id=\"root\"></div>"
                + "<script>" + BRIDGE + "</script><script>" + bundle + "</script></body></html>";
        return new Document(html, policy(bundle));
    }

    /** Content-Security-Policy for the frame; {@code sandbox} without {@code allow-same-origin} gives it an opaque origin. */
    public static String policy(String bundle) {
        return "default-src 'none'; script-src 'sha256-" + hash(BRIDGE) + "' 'sha256-" + hash(bundle) + "'; "
                + "style-src 'unsafe-inline'; img-src data:; font-src 'none'; connect-src 'none'; media-src 'none'; "
                + "object-src 'none'; frame-src 'none'; worker-src 'none'; manifest-src 'none'; form-action 'none'; "
                + "base-uri 'none'; frame-ancestors 'self'; sandbox allow-scripts";
    }

    /** Rejects bundles that could close the inline script element or open markup inside it. */
    public static boolean embeddable(String bundle) {
        String lower = bundle.toLowerCase(java.util.Locale.ROOT);
        return !lower.contains("</script") && !lower.contains("<script") && !lower.contains("<!--");
    }

    static String hash(String text) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String load() {
        try (InputStream in = UiExtensionFrame.class.getResourceAsStream("/ui-extension/bridge.js")) {
            if (in == null) throw new IllegalStateException("ui-extension/bridge.js is missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
