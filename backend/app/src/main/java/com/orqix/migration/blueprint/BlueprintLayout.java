package com.orqix.migration.blueprint;

import java.util.List;

/**
 * Strategy that turns the page texts of one blueprint document into a first structured transcription (docs/build/S91).
 * <p>
 * A layout is deterministic, pure (no I/O, no network, no code execution) and conservative: it records only what the
 * text states, marks every element {@link Transcription#PROPOSED} with its source page, and leaves anything it cannot
 * read empty so the findings report it. It never decides publication; a person reviews every element.
 * <p>
 * Only {@link GenericOutlineLayout} ships today. A parser for the genuine Pega Blueprint export layout is a separate
 * implementation, to be written against a real sample; no compatibility with that layout is claimed here.
 */
public interface BlueprintLayout {

    /** Stable identifier recorded with the transcription and in the report (for example {@code generic-outline}). */
    String id();

    /** Revision of the recognition rules, recorded with every transcription it produced. */
    String revision();

    /** One page of extracted text; {@code number} starts at 1. */
    record Page(int number, String text, String sha256) { }

    /**
     * @param transcription  proposed transcription (may be empty)
     * @param unclassified   per page (index = page number - 1): nonblank lines the layout did not attribute to any element
     */
    record Result(Transcription transcription, List<Integer> unclassified) {
        public Result { unclassified = List.copyOf(unclassified); }
    }

    Result transcribe(List<Page> pages);
}
