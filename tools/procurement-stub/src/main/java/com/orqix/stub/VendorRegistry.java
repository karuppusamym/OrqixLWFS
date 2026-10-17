package com.orqix.stub;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic vendor fixtures behind the demo MCP tools (docs/build/S13-agents-mcp.md section 9). The same vendor name
 * always yields the same answer, so a live demo, an end-to-end scenario and a unit test all see identical data. A few
 * named vendors cover the demo paths; every other name derives its values from a hash of the lower-cased name.
 *
 * <ul>
 *   <li>{@code Acme Ltd}: risk 72 (high), no sanctions hit: goes to compliance review</li>
 *   <li>{@code Globex Corporation}: risk 18 (low): approved automatically</li>
 *   <li>{@code Initech}: risk 45 (medium): approved automatically</li>
 *   <li>{@code Umbrella Trading}: risk 91 (high) and a sanctions hit</li>
 *   <li>any name containing "sanctioned": risk 95 (high) and a sanctions hit</li>
 * </ul>
 */
final class VendorRegistry {

    /** Result of {@code vendor_risk_lookup}. {@code riskBand} is low (under 40), medium (40 to 69) or high (70 up). */
    record Vendor(String vendorName, String registryId, int riskScore, String riskBand, boolean sanctionsHit) {
    }

    /** Result of {@code vendor_financials}. */
    record Financials(String vendorName, long annualRevenueUsd, String creditRating) {
    }

    private record Known(int score, boolean sanctions) {
    }

    private static final Map<String, Known> KNOWN = Map.of(
            "acme ltd", new Known(72, false),
            "globex corporation", new Known(18, false),
            "initech", new Known(45, false),
            "umbrella trading", new Known(91, true),
            "northwind traders", new Known(12, false));

    private VendorRegistry() {
    }

    static Vendor risk(String vendorName) {
        String name = vendorName.strip();
        String normalized = name.toLowerCase(Locale.ROOT);
        byte[] hash = sha256(normalized);
        Known known = KNOWN.get(normalized);
        int score;
        boolean sanctions;
        if (known != null) {
            score = known.score();
            sanctions = known.sanctions();
        } else if (normalized.contains("sanctioned")) {
            score = 95;
            sanctions = true;
        } else {
            score = 5 + (hash[0] & 0xff) % 91;
            sanctions = false;
        }
        String registryId = "VR-" + HexFormat.of().withUpperCase().formatHex(hash, 1, 4);
        return new Vendor(name, registryId, score, band(score), sanctions);
    }

    static Financials financials(String vendorName) {
        String name = vendorName.strip();
        Vendor vendor = risk(name);
        byte[] hash = sha256(name.toLowerCase(Locale.ROOT));
        long revenue = (100L + ((hash[4] & 0xff) * 256L + (hash[5] & 0xff)) % 900L) * 100_000L;
        String rating = vendor.riskScore() < 40 ? "A" : vendor.riskScore() < 70 ? "BBB" : "BB";
        return new Financials(name, revenue, rating);
    }

    static String band(int score) {
        return score >= 70 ? "high" : score >= 40 ? "medium" : "low";
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
