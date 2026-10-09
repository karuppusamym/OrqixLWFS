package com.orqix.shared.pagination;

import com.orqix.shared.problem.ApiException;

/** List {@code limit} parameter: 1-100, default 25 (S3 section 7). */
public final class Limits {

    public static final int DEFAULT = 25;
    public static final int MAX = 100;

    private Limits() {
    }

    public static int of(Integer requested) {
        if (requested == null) {
            return DEFAULT;
        }
        if (requested < 1 || requested > MAX) {
            throw ApiException.malformed("limit must be between 1 and 100.");
        }
        return requested;
    }
}
