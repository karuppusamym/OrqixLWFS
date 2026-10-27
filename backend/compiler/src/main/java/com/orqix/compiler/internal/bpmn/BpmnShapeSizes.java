package com.orqix.compiler.internal.bpmn;

/**
 * Shape sizes of the exported BPMN diagram (STU04, docs/build/S97-bpmn-export.md).
 * <p>
 * These are the same sizes the canvas notation uses; the frontend counterpart is the constant in
 * {@code frontend/src/features/studio/model/shapeSizes.ts} (created by the canvas-notation work, STU01). The two files are
 * kept equal by hand and by the evidence note; neither side imports the other. A node's saved canvas position
 * ({@code uiLayout.positions}) is the top-left corner of its bounding box of this size.
 */
public final class BpmnShapeSizes {

    /** Events (start, end, intermediate, boundary): 48 x 48. */
    public static final int EVENT = 48;
    /** Gateways: 64 x 64. */
    public static final int GATEWAY = 64;
    /** Tasks and call activities: 200 wide. */
    public static final int TASK_WIDTH = 200;
    /** Tasks and call activities: 80 high. */
    public static final int TASK_HEIGHT = 80;

    private BpmnShapeSizes() {
    }
}
