package com.orqix.compiler.internal.bpmn;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.JsonNode;

/**
 * Shape sizes of the exported BPMN diagram (STU04, docs/build/S97-bpmn-export.md).
 * <p>
 * Read from {@code contracts/bpmn-shape-sizes.json} (packaged as {@code orqix/schemas/bpmn-shape-sizes.json}), the same
 * file the Studio canvas imports in {@code frontend/src/features/studio/model/shapeSizes.ts}, so the two cannot drift
 * (S106). A node's saved canvas position ({@code uiLayout.positions}) is the top-left corner of its bounding box.
 */
public final class BpmnShapeSizes {

    static final String RESOURCE = "orqix/schemas/bpmn-shape-sizes.json";

    private static final JsonNode SIZES = load();

    /** Events (start, end, intermediate, boundary): square, 48 by default. */
    public static final int EVENT = size("event", "width");
    /** Gateways: square, 64 by default. */
    public static final int GATEWAY = size("gateway", "width");
    /** Tasks and call activities: 200 wide by default. */
    public static final int TASK_WIDTH = size("task", "width");
    /** Tasks and call activities: 80 high by default. */
    public static final int TASK_HEIGHT = size("task", "height");

    static {
        if (size("event", "height") != EVENT || size("gateway", "height") != GATEWAY) {
            throw new IllegalStateException(RESOURCE + ": events and gateways must be square");
        }
    }

    private BpmnShapeSizes() {
    }

    private static JsonNode load() {
        try (InputStream in = BpmnShapeSizes.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing from the compiler jar");
            }
            return StrictJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int size(String shape, String side) {
        JsonNode value = SIZES.path(shape).path(side);
        if (!value.isIntegralNumber() || value.intValue() < 8 || value.intValue() > 1000) {
            throw new IllegalStateException(RESOURCE + ": " + shape + "." + side + " must be a whole number of pixels");
        }
        return value.intValue();
    }
}
