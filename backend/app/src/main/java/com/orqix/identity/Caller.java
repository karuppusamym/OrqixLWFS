package com.orqix.identity;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.UUID;

/**
 * The authenticated principal of a request: the Orqix subject plus the IdP group values snapshotted into the session
 * at login (S6 sections 1-2). Machine clients (bearer JWT) have no groups.
 *
 * @param groups IdP group claim values, at most 200 values of at most 256 characters
 */
public record Caller(UUID subjectId, String displayName, String email, String issuer, String externalSubject,
        List<String> groups, boolean machine) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public Caller {
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    /** Stable key of the group snapshot for policy caching. */
    public String groupsKey() {
        return String.join("\u001f", groups.stream().sorted().toList());
    }
}
