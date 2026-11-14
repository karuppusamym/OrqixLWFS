package com.orqix.compiler.internal.model;

import java.util.Map;

/**
 * A step's "for each" policy (CG-E06, docs/build/S138): {@code config.forEach} on http, externalTask and aiTask steps.
 * The step runs once per item of a typed request list; Flowable multi-instance does the iteration. Every item has its
 * own effect key, worker job or AI decision, so a retry never repeats another item's work.
 *
 * @param path        JSON Pointer of the {@code forEach} object, for issues
 * @param itemInput   input name that receives the item of a list of single values, or null
 * @param itemMapping input name to row column for a list of rows (sorted), empty for single values
 * @param batchSize   parallel only: items running at the same time
 * @param when        {@code all}, {@code firstSuccess} or {@code atLeast}
 * @param count       for {@code atLeast}: the number of items that must succeed; else null
 * @param collect     collected output name to the per-item result it collects (sorted)
 */
public record StepForEach(String path, String itemsRef, String itemInput, Map<String, String> itemMapping,
                          int maxItems, boolean parallel, int batchSize, String when, Integer count,
                          Map<String, String> collect) {

    /** Hard cap of items per step occurrence. */
    public static final int MAX_ITEMS = 500;
    /** Default and highest number of parallel items at the same time (the S14 child for-each bound). */
    public static final int MAX_BATCH = 25;

    public static final String ALL = "all";
    public static final String FIRST_SUCCESS = "firstSuccess";
    public static final String AT_LEAST = "atLeast";

    /** Outputs every for-each step has besides its collected lists. */
    public static final String ITEM_COUNT = "itemCount";
    public static final String COMPLETED_COUNT = "completedCount";
    public static final String STOPPED_EARLY = "stoppedEarly";

    /** @return true when the step can end before every item ran */
    public boolean stopsEarly() {
        return !ALL.equals(when);
    }

    /** @return the number of succeeded items that ends the step early (1 for firstSuccess), or 0 for all */
    public int target() {
        return FIRST_SUCCESS.equals(when) ? 1 : AT_LEAST.equals(when) && count != null ? count : 0;
    }

    /**
     * @return true for parallel items: they run in batches of {@code batchSize}, one engine job per batch that sends
     *         the batch's items at the same time (only calls run items in parallel)
     */
    public boolean batched() {
        return parallel;
    }

    /** @return the request list field name */
    public String itemsField() {
        return itemsRef.substring("/input/".length());
    }
}
