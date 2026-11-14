package com.orqix.integration.secrets;

import java.util.Optional;
import java.util.regex.Matcher;

/**
 * A parsed {@code secretRef} (docs/build/S132). A plain name ({@code crm-token}) is a mounted file, as before. A
 * reference with a scheme names a secret in an external store:
 * <ul>
 * <li>{@code vault:<mount>/<path>#<key>} HashiCorp Vault KV v2</li>
 * <li>{@code aws-sm:<secret id or ARN>#<json key>} AWS Secrets Manager</li>
 * <li>{@code azure-kv:<vault>/<secret>[/<version>]#<json key>} Azure Key Vault</li>
 * <li>{@code gcp-sm:<project>/<secret>[/<version>]#<json key>} Google Secret Manager</li>
 * </ul>
 * The part after {@code #} is optional and selects one field of a JSON secret. A reference is a name, never a value.
 *
 * @param scheme  {@code file}, {@code vault}, {@code aws-sm}, {@code azure-kv} or {@code gcp-sm}
 * @param locator where the secret lives in that store
 * @param key     the field of a JSON secret, or null for the whole value
 */
public record SecretRef(String raw, String scheme, String locator, String key) {

    public static final String FILE = "file";
    public static final String VAULT = "vault";
    public static final String AWS = "aws-sm";
    public static final String AZURE = "azure-kv";
    public static final String GCP = "gcp-sm";

    /** Every accepted reference (API validation and database check use the same expression). */
    public static final String PATTERN = "^([a-z0-9][a-z0-9-]{0,62}|(vault|aws-sm|azure-kv|gcp-sm):"
            + "[A-Za-z0-9][A-Za-z0-9/_.+=@:-]{0,255}(#[A-Za-z0-9_.-]{1,128})?)$";

    private static final java.util.regex.Pattern COMPILED = java.util.regex.Pattern.compile(PATTERN);
    private static final java.util.regex.Pattern SPLIT = java.util.regex.Pattern
            .compile("^([a-z-]+):([^#]+)(?:#(.+))?$");

    /** @return the reference, or empty when it does not follow {@link #PATTERN} or walks out of its store path */
    public static Optional<SecretRef> parse(String raw) {
        if (raw == null || !COMPILED.matcher(raw).matches()) {
            return Optional.empty();
        }
        if (raw.indexOf(':') < 0) {
            return Optional.of(new SecretRef(raw, FILE, raw, null));
        }
        Matcher m = SPLIT.matcher(raw);
        if (!m.matches()) {
            return Optional.empty();
        }
        String locator = m.group(2);
        if (locator.contains("..") || locator.contains("//") || locator.endsWith("/")) {
            return Optional.empty();
        }
        String scheme = m.group(1);
        int segments = locator.split("/").length;
        boolean shaped = switch (scheme) {
            case VAULT -> segments >= 2; // mount and at least one path segment
            case AZURE, GCP -> segments == 2 || segments == 3;
            case AWS -> true;
            default -> false;
        };
        return shaped ? Optional.of(new SecretRef(raw, scheme, locator, m.group(3))) : Optional.empty();
    }

    /** @return true when the reference text is acceptable (used by request validation) */
    public static boolean valid(String raw) {
        return parse(raw).isPresent();
    }

    @Override
    public String toString() {
        return raw;
    }
}
