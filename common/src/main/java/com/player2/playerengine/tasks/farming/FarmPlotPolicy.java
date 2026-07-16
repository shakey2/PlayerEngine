package com.player2.playerengine.tasks.farming;

/** Fixed setup geometry, scan, edit, and watchdog limits. */
public final class FarmPlotPolicy {
    public static final int HYDRATION_RADIUS = 4;
    public static final int PLOT_WIDTH = HYDRATION_RADIUS * 2 + 1;
    public static final int PLOT_CELL_COUNT = PLOT_WIDTH * PLOT_WIDTH;
    public static final int SOIL_CELL_COUNT = PLOT_CELL_COUNT - 1;
    public static final int AUTO_SEARCH_RADIUS = 48;
    public static final int MAX_CANDIDATE_CENTERS = 512;
    public static final int MAX_WORLD_READS_PER_TICK = 2_048;
    public static final int MIN_NEW_SITE_GRASS_COLUMNS = 65;
    public static final int MAX_REPAIR_COLUMNS = 16;
    public static final int MAX_SURFACE_DELTA = 1;
    public static final int REPAIR_HEADROOM_BLOCKS = 3;
    public static final int REPAIR_SCAN_POSITIONS =
            PLOT_CELL_COUNT * (REPAIR_HEADROOM_BLOCKS + 2);
    public static final int MAX_REPAIR_PASSES = 3;
    public static final int MAX_REPAIR_RESOURCE_TRIPS = 2;
    /**
     * Complete initial clear bound: three headroom cells per plot column, the bounded
     * target-level repair set, and the ordinary grass/dirt center opening.
     */
    public static final int MAX_INITIAL_CLEAR_ACTIONS = PLOT_CELL_COUNT * REPAIR_HEADROOM_BLOCKS
            + MAX_REPAIR_COLUMNS + 1;
    /** Whole-operation bound across initial preparation and bounded post-travel repair passes. */
    public static final int MAX_CLEAR_ACTIONS = MAX_INITIAL_CLEAR_ACTIONS
            + MAX_REPAIR_PASSES * (PLOT_CELL_COUNT * REPAIR_HEADROOM_BLOCKS
            + MAX_REPAIR_COLUMNS + 1);
    public static final int MAX_FILL_ACTIONS =
            MAX_REPAIR_COLUMNS * (MAX_REPAIR_PASSES + 1);
    public static final int MAX_TILL_ACTIONS =
            SOIL_CELL_COUNT * (MAX_REPAIR_PASSES + 1);

    public static final int WHOLE_SETUP_TICKS = 12_000;
    public static final int SITE_SELECTION_TICKS = 200;
    public static final int RESOURCE_PHASE_TICKS = 6_000;
    public static final int WATER_SEARCH_LEGS = 3;
    public static final int WATER_SEARCH_TICKS_PER_LEG = 600;
    public static final int WATER_SEARCH_MAX_DISTANCE = 96;
    /** Water sources must be exposed at the live terrain/fluid surface, never buried in caves. */
    public static final int WATER_SURFACE_FEET_TOLERANCE = 1;
    /** Separate active-tick budget for returning from acquisition before completion or failure. */
    public static final int RESOURCE_RETURN_TICKS = 600;
    /** Rotate among deterministic non-center farm stances when one return target is damaged. */
    public static final int RESOURCE_RETURN_STANCE_TICKS = 75;
    public static final int HOE_MARKED_SEARCH_RADIUS = 96;
    public static final int HOE_MARKED_CHEST_ATTEMPTS = 8;
    public static final int REPAIR_TOOL_MARKED_SEARCH_RADIUS = 96;
    public static final int REPAIR_TOOL_MARKED_CHEST_ATTEMPTS_PER_FAMILY = 8;
    public static final int REPAIR_TOOL_MARKED_CHEST_ATTEMPTS_PER_CANDIDATE = 2;
    public static final int MUTATION_NO_PROGRESS_TICKS = 300;
    public static final int MUTATION_MAX_ATTEMPTS = 3;
    /** Active resume ticks reserved for an issued input's final world/inventory receipt. */
    public static final int TRANSIENT_RECEIPT_SETTLE_TICKS = 3;

    private FarmPlotPolicy() {
    }
}
