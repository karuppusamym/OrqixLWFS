package com.orqix.integration;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.orqix.shared.config.OrqixProperties;

/**
 * Egress (SSRF) policy (S6 section 6), applied when a binding is saved and before every dispatch: https only (plain
 * http only for configured dev hosts), no userinfo, exact {@code host:port} allowlist, and DNS must resolve only to
 * public addresses unless the host is explicitly approved as private. Redirects are never followed by the executor.
 */
@Component
public class EgressPolicy {

    /** Why a target was refused; {@link #DNS_FAILURE} at dispatch means "not sent" (retryable). */
    public enum Denial {
        INVALID_URL, SCHEME_NOT_ALLOWED, USERINFO_NOT_ALLOWED, HOST_NOT_ALLOWED, PRIVATE_ADDRESS, DNS_FAILURE
    }

    public static final class EgressDeniedException extends Exception {

        private final Denial denial;

        public EgressDeniedException(Denial denial, String message) {
            super(message, null, false, false);
            this.denial = denial;
        }

        public Denial denial() {
            return denial;
        }
    }

    /** Hook for tests: resolves host names (default: system DNS). */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final OrqixProperties properties;
    private volatile Resolver resolver = InetAddress::getAllByName;

    public EgressPolicy(OrqixProperties properties) {
        this.properties = properties;
    }

    public void setResolver(Resolver resolver) {
        this.resolver = resolver == null ? InetAddress::getAllByName : resolver;
    }

    /** Validates {@code baseUrl}; returns the normalized {@code host:port}. */
    public String check(String baseUrl) throws EgressDeniedException {
        URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (URISyntaxException e) {
            throw new EgressDeniedException(Denial.INVALID_URL, "The base URL is not a valid URL.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getRawUserInfo() != null || (uri.getRawAuthority() != null && uri.getRawAuthority().contains("@"))) {
            throw new EgressDeniedException(Denial.USERINFO_NOT_ALLOWED, "Credentials in the URL are not allowed.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank() || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new EgressDeniedException(Denial.INVALID_URL, "The base URL must be scheme://host[:port][/path].");
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        int port = uri.getPort() != -1 ? uri.getPort() : ("http".equals(scheme) ? 80 : 443);
        String hostPort = (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
        if ("http".equals(scheme)) {
            if (!properties.egress().plainHttpHostSet().contains(hostPort)) {
                throw new EgressDeniedException(Denial.SCHEME_NOT_ALLOWED, "Only https is allowed for this host.");
            }
        } else if (!"https".equals(scheme)) {
            throw new EgressDeniedException(Denial.SCHEME_NOT_ALLOWED, "Only https is allowed.");
        }
        if (!properties.egress().allowedHostSet().contains(hostPort)) {
            throw new EgressDeniedException(Denial.HOST_NOT_ALLOWED, "The host is not on the egress allowlist.");
        }
        boolean privateApproved = properties.egress().privateHostSet().contains(hostPort)
                || properties.egress().privateHostSet().contains(host);
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new EgressDeniedException(Denial.DNS_FAILURE, "The host name does not resolve.");
        }
        if (addresses == null || addresses.length == 0) {
            throw new EgressDeniedException(Denial.DNS_FAILURE, "The host name does not resolve.");
        }
        if (!privateApproved) {
            for (InetAddress address : addresses) {
                if (!isPublic(address)) {
                    throw new EgressDeniedException(Denial.PRIVATE_ADDRESS,
                            "The host resolves to a private or reserved address.");
                }
            }
        }
        return hostPort;
    }

    /** False for loopback, link-local (incl. 169.254.169.254), private, CGNAT, multicast, unspecified and ULA. */
    static boolean isPublic(InetAddress address) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            if (first == 0 || first == 127 || first >= 224) {
                return false; // this network, loopback, multicast, reserved, broadcast
            }
            if (first == 100 && second >= 64 && second <= 127) {
                return false; // CGNAT 100.64.0.0/10
            }
            if (first == 169 && second == 254) {
                return false; // link-local, cloud metadata
            }
            if (first == 192 && second == 0 && (b[2] & 0xff) == 0) {
                return false; // IETF protocol assignments
            }
            if (first == 198 && (second == 18 || second == 19)) {
                return false; // benchmarking
            }
            return true;
        }
        if (address instanceof Inet6Address) {
            int first = b[0] & 0xff;
            if ((first & 0xfe) == 0xfc) {
                return false; // unique local fc00::/7
            }
            if (first == 0xfe && ((b[1] & 0xc0) == 0x80 || (b[1] & 0xc0) == 0xc0)) {
                return false; // link-local fe80::/10, deprecated site-local fec0::/10
            }
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                mapped &= b[i] == 0;
            }
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try {
                    return isPublic(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }
}
