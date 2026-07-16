package com.player2.playerengine.tasks.farming;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, ranked farm-site manifest. Every mutation carries its frozen target fingerprint and
 * selected standing position; callers must revalidate the snapshot immediately before CLEAR.
 */
public final class FarmSitePlan {

    public enum ClearPurpose {
        VEGETATION,
        LEVEL_SURFACE,
        REPLACE_STONE,
        OPEN_CENTER
    }

    public record ClearAction(
            BlockPos target,
            BlockPos stance,
            String expectedStateFingerprint,
            ClearPurpose purpose) {
        public ClearAction {
            target = immutable(target, "target");
            stance = immutable(stance, "stance");
            Objects.requireNonNull(expectedStateFingerprint, "expectedStateFingerprint");
            Objects.requireNonNull(purpose, "purpose");
        }
    }

    public record FillAction(
            BlockPos target,
            BlockPos stance,
            String expectedStateFingerprint) {
        public FillAction {
            target = immutable(target, "target");
            stance = immutable(stance, "stance");
            Objects.requireNonNull(expectedStateFingerprint, "expectedStateFingerprint");
        }
    }

    public record TillAction(
            BlockPos target,
            BlockPos stance,
            String expectedStateFingerprint) {
        public TillAction {
            target = immutable(target, "target");
            stance = immutable(stance, "stance");
            Objects.requireNonNull(expectedStateFingerprint, "expectedStateFingerprint");
        }
    }

    public record WaterAction(
            BlockPos target,
            BlockPos stance,
            BlockPos support,
            String expectedStateFingerprint) {
        public WaterAction {
            target = immutable(target, "target");
            stance = immutable(stance, "stance");
            support = immutable(support, "support");
            Objects.requireNonNull(expectedStateFingerprint, "expectedStateFingerprint");
        }
    }

    private final BlockPos anchor;
    private final BlockPos center;
    private final boolean explicit;
    private final boolean waterRequired;
    private final int grassColumns;
    private final int existingFarmland;
    private final int repairColumns;
    private final int estimatedEdits;
    private final int rankingEdits;
    private final List<ClearAction> clearActions;
    private final List<FillAction> fillActions;
    private final List<TillAction> tillActions;
    private final WaterAction waterAction;
    private final BlockPos acquisitionReturnStance;
    private final FarmSiteSnapshot snapshot;

    public FarmSitePlan(
            BlockPos anchor,
            BlockPos center,
            boolean explicit,
            boolean waterRequired,
            int grassColumns,
            int existingFarmland,
            int repairColumns,
            List<ClearAction> clearActions,
            List<FillAction> fillActions,
            List<TillAction> tillActions,
            WaterAction waterAction,
            BlockPos acquisitionReturnStance,
            FarmSiteSnapshot snapshot) {
        this.anchor = immutable(anchor, "anchor");
        this.center = immutable(center, "center");
        this.explicit = explicit;
        this.waterRequired = waterRequired;
        if (grassColumns < 0 || grassColumns > FarmPlotPolicy.PLOT_CELL_COUNT
                || existingFarmland < 0 || existingFarmland > FarmPlotPolicy.SOIL_CELL_COUNT
                || repairColumns < 0 || repairColumns > FarmPlotPolicy.MAX_REPAIR_COLUMNS) {
            throw new IllegalArgumentException("farm-site counts are outside fixed bounds");
        }
        this.grassColumns = grassColumns;
        this.existingFarmland = existingFarmland;
        this.repairColumns = repairColumns;
        this.clearActions = List.copyOf(Objects.requireNonNull(clearActions, "clearActions"));
        this.fillActions = List.copyOf(Objects.requireNonNull(fillActions, "fillActions"));
        this.tillActions = List.copyOf(Objects.requireNonNull(tillActions, "tillActions"));
        this.waterAction = waterAction;
        this.acquisitionReturnStance = immutable(
                acquisitionReturnStance, "acquisitionReturnStance");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        if (!this.center.equals(snapshot.center()) || explicit != snapshot.explicit()) {
            throw new IllegalArgumentException("manifest and snapshot candidate do not match");
        }
        if (waterRequired != (waterAction != null)) {
            throw new IllegalArgumentException("water action must exist exactly when water is required");
        }
        this.estimatedEdits = Math.addExact(
                Math.addExact(this.clearActions.size(), this.fillActions.size()),
                Math.addExact(this.tillActions.size(), waterRequired ? 1 : 0));
        int neutralGroundCoverClears = (int) this.clearActions.stream()
                .filter(action -> action.purpose() == ClearPurpose.VEGETATION)
                .count();
        this.rankingEdits = Math.subtractExact(this.estimatedEdits, neutralGroundCoverClears);
    }

    public BlockPos anchor() {
        return anchor;
    }

    public BlockPos center() {
        return center;
    }

    public boolean explicit() {
        return explicit;
    }

    public boolean waterRequired() {
        return waterRequired;
    }

    public int grassColumns() {
        return grassColumns;
    }

    public int existingFarmland() {
        return existingFarmland;
    }

    public int repairColumns() {
        return repairColumns;
    }

    public int estimatedEdits() {
        return estimatedEdits;
    }

    /** Site-fitness edit count; harmless ground-cover clears are intentionally score-neutral. */
    public int rankingEdits() {
        return rankingEdits;
    }

    public int requiredDirt() {
        return fillActions.size();
    }

    public int requiredHoeDamage() {
        return tillActions.size();
    }

    public List<ClearAction> clearActions() {
        return clearActions;
    }

    public List<FillAction> fillActions() {
        return fillActions;
    }

    public List<TillAction> tillActions() {
        return tillActions;
    }

    public WaterAction waterAction() {
        return waterAction;
    }

    /** Planner-validated feet position that is safe before any manifest mutation executes. */
    public BlockPos acquisitionReturnStance() {
        return acquisitionReturnStance;
    }

    public FarmSiteSnapshot snapshot() {
        return snapshot;
    }

    public String fingerprint() {
        return snapshot.fingerprint();
    }

    public boolean matches(FarmSiteSnapshot current) {
        return snapshot.sameFacts(current);
    }

    private static BlockPos immutable(BlockPos position, String name) {
        return Objects.requireNonNull(position, name).immutable();
    }
}
