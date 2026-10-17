package com.orqix.migration.camunda;

/** The uploaded file cannot be analysed. {@code tooLarge} maps to 413, everything else to 422 SOURCE_INVALID. */
public class SourceException extends RuntimeException {

    private final boolean tooLarge;

    public SourceException(String message, boolean tooLarge) {
        super(message, null, false, false);
        this.tooLarge = tooLarge;
    }

    public static SourceException invalid(String message) {
        return new SourceException(message, false);
    }

    public boolean tooLarge() {
        return tooLarge;
    }
}
