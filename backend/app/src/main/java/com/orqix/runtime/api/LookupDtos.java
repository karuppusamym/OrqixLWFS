package com.orqix.runtime.api;

import java.util.List;
import java.util.UUID;

/** Lookup fields of request forms (CG-F02, docs/build/S140). */
public final class LookupDtos {

    private LookupDtos() {
    }

    /** @param releaseId the release whose form is shown (default: the active release) */
    public record LookupSearch(String search, UUID releaseId) {
    }

    /** One option: the value stored in the request, the label shown and an optional second line. */
    public record LookupOption(String value, String label, String detail) {
    }

    /**
     * @param truncated       more rows matched than are shown; type more to narrow the search
     * @param cached          the answer came from the short-lived cache
     * @param minSearchLength the search runs only from this many characters
     */
    public record LookupOptions(List<LookupOption> options, boolean truncated, boolean cached, int minSearchLength) {
    }
}
