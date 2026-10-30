package com.orqix.migration.blueprint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.orqix.integration.DocumentExtraction;

/**
 * Strategy that turns the page texts of one blueprint document into a first structured transcription (docs/build/S91).
 * <p>
 * A layout is deterministic, pure (no I/O, no network, no code execution) and conservative: it records only what the
 * text states, marks every element {@link Transcription#PROPOSED} with its source page, and leaves anything it cannot
 * read empty so the findings report it. It never decides publication; a person reviews every element.
 * <p>
 * Two layouts ship: {@link PegaBlueprintLayout} for Pega's "Application Overview Document" Blueprint export (written
 * against one genuine export) and {@link GenericOutlineLayout} for outline documents. {@link #forPages} picks one.
 */
public interface BlueprintLayout {

    /** Stable identifier recorded with the transcription and in the report (for example {@code generic-outline}). */
    String id();

    /** Revision of the recognition rules, recorded with every transcription it produced. */
    String revision();

    /**
     * One page of extracted text; {@code number} starts at 1. {@code geometry} (positioned runs and small filled shapes)
     * is null when the extraction did not provide it.
     */
    record Page(int number, String text, String sha256, DocumentExtraction.PageGeometry geometry) {
        public Page(int number, String text, String sha256) { this(number, text, sha256, null); }
    }

    /**
     * @param transcription  proposed transcription (may be empty)
     * @param unclassified   per page (index = page number - 1): nonblank lines the layout did not attribute to any element
     * @param pageTexts      null, or per page a reading-order rendering of the page (for example a column-by-column
     *                       lifecycle) that replaces the raw extracted text as the stored source text, so every element
     *                       excerpt can be found in it; unclassified counts lines of this rendering
     * @param caseTypes      every case type name the document names, in document order
     * @param caseType       the case type that was transcribed, or null when none was found or the requested one is absent
     */
    record Result(Transcription transcription, List<Integer> unclassified, List<String> pageTexts, List<String> caseTypes, String caseType) {
        public Result {
            unclassified = List.copyOf(unclassified);
            pageTexts = pageTexts == null ? null : List.copyOf(pageTexts);
            caseTypes = List.copyOf(caseTypes);
        }
        public Result(Transcription transcription, List<Integer> unclassified) {
            this(transcription, unclassified, null, transcription.caseType() == null ? List.of() : List.of(transcription.caseType().name()),
                    transcription.caseType() == null ? null : transcription.caseType().name());
        }
        /** Case types the document names besides the transcribed one. */
        public List<String> otherCaseTypes() {
            List<String> others = new ArrayList<>(caseTypes);
            if (caseType != null) others.remove(caseType);
            return Collections.unmodifiableList(others);
        }
    }

    /** Whether this layout can read the document (used by {@link #forPages}); the generic outline reads anything. */
    default boolean recognizes(List<Page> pages) { return true; }

    /**
     * @param caseType name of the case type to transcribe when the document defines several; null for the first. A
     *                 layout whose documents define one case type may ignore it; the caller checks the result's names.
     */
    Result transcribe(List<Page> pages, String caseType);

    default Result transcribe(List<Page> pages) { return transcribe(pages, null); }

    /** The Pega Blueprint export layout when it recognizes the document, otherwise the generic outline layout. */
    static BlueprintLayout forPages(List<Page> pages) {
        BlueprintLayout pega = new PegaBlueprintLayout();
        return pega.recognizes(pages) ? pega : new GenericOutlineLayout();
    }
}
