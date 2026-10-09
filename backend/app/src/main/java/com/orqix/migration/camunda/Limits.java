package com.orqix.migration.camunda;

/** Safe-parsing limits (S11 section 1). */
public record Limits(int maxBytes, int maxElements, int maxDepth) {

    public static final Limits DEFAULT = new Limits(1_048_576, 2_000, 64);
}
