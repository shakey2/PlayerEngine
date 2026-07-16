package com.player2.playerengine.tasks.farming;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Pure deterministic 9x9 footprint geometry with the center source omitted from soil order. */
public final class FarmPlotGeometry {
    private FarmPlotGeometry() {
    }

    public static List<BlockPos> allCells(BlockPos center) {
        Objects.requireNonNull(center, "center");
        ArrayList<BlockPos> cells = new ArrayList<>(FarmPlotPolicy.PLOT_CELL_COUNT);
        for (int dz = -FarmPlotPolicy.HYDRATION_RADIUS;
             dz <= FarmPlotPolicy.HYDRATION_RADIUS; dz++) {
            boolean reverse = ((dz + FarmPlotPolicy.HYDRATION_RADIUS) & 1) != 0;
            if (reverse) {
                for (int dx = FarmPlotPolicy.HYDRATION_RADIUS;
                     dx >= -FarmPlotPolicy.HYDRATION_RADIUS; dx--) {
                    cells.add(center.offset(dx, 0, dz).immutable());
                }
            } else {
                for (int dx = -FarmPlotPolicy.HYDRATION_RADIUS;
                     dx <= FarmPlotPolicy.HYDRATION_RADIUS; dx++) {
                    cells.add(center.offset(dx, 0, dz).immutable());
                }
            }
        }
        return List.copyOf(cells);
    }

    public static List<BlockPos> soilCells(BlockPos center) {
        ArrayList<BlockPos> cells = new ArrayList<>(FarmPlotPolicy.SOIL_CELL_COUNT);
        for (BlockPos cell : allCells(center)) {
            if (!cell.equals(center)) {
                cells.add(cell);
            }
        }
        return List.copyOf(cells);
    }

    public static boolean contains(BlockPos center, BlockPos position) {
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(position, "position");
        return position.getY() == center.getY()
                && Math.abs(position.getX() - center.getX()) <= FarmPlotPolicy.HYDRATION_RADIUS
                && Math.abs(position.getZ() - center.getZ()) <= FarmPlotPolicy.HYDRATION_RADIUS;
    }

    /**
     * Deterministic candidate centers within the horizontal auto radius. The closest 512 are
     * retained after distance and coordinate ordering; no world reads occur here.
     */
    public static List<BlockPos> autoCandidateCenters(BlockPos anchor) {
        Objects.requireNonNull(anchor, "anchor");
        int radius = FarmPlotPolicy.AUTO_SEARCH_RADIUS;
        long radiusSquared = (long) radius * radius;
        LinkedHashSet<BlockPos> sampled = new LinkedHashSet<>();
        // Fine local coverage handles the common nearby case; a five-block coarse grid covers the
        // full advertised 48-block radius without exceeding the 512-candidate safety bound.
        for (int dz = -8; dz <= 8; dz++) {
            for (int dx = -8; dx <= 8; dx++) {
                if ((long) dx * dx + (long) dz * dz <= 64L) {
                    sampled.add(anchor.offset(dx, 0, dz).immutable());
                }
            }
        }
        for (int dz = -45; dz <= 45; dz += 5) {
            for (int dx = -45; dx <= 45; dx += 5) {
                if ((long) dx * dx + (long) dz * dz <= radiusSquared) {
                    sampled.add(anchor.offset(dx, 0, dz).immutable());
                }
            }
        }
        sampled.add(anchor.offset(radius, 0, 0).immutable());
        sampled.add(anchor.offset(-radius, 0, 0).immutable());
        sampled.add(anchor.offset(0, 0, radius).immutable());
        sampled.add(anchor.offset(0, 0, -radius).immutable());
        ArrayList<BlockPos> candidates = new ArrayList<>(sampled);
        candidates.sort(candidateComparator(anchor));
        if (candidates.size() > FarmPlotPolicy.MAX_CANDIDATE_CENTERS) {
            throw new IllegalStateException("farm candidate sampling exceeded the fixed cap");
        }
        return List.copyOf(candidates);
    }

    /** Every position whose facts are frozen for a candidate: 11x11 by five vertical layers. */
    public static List<BlockPos> scanEnvelope(BlockPos center) {
        Objects.requireNonNull(center, "center");
        int radius = FarmPlotPolicy.HYDRATION_RADIUS + 1;
        ArrayList<BlockPos> positions = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1) * 5);
        for (int dy = -1; dy <= 3; dy++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    positions.add(center.offset(dx, dy, dz).immutable());
                }
            }
        }
        return List.copyOf(positions);
    }

    /**
     * Candidate standing positions for an action target. Positions over eventual soil are preferred
     * to positions outside the plot; the center source is never used as stance support.
     */
    public static List<BlockPos> stanceCandidates(BlockPos center, BlockPos actionTarget) {
        return stanceCandidates(center, actionTarget, 1, true);
    }

    /**
     * Clear actions may also stand on top of an adjacent one-block rise. The extra head cell is
     * part of the frozen scan envelope, and the planner separately proves the support is still
     * present when that action executes.
     */
    public static List<BlockPos> clearStanceCandidates(BlockPos center, BlockPos actionTarget) {
        ArrayList<BlockPos> candidates = new ArrayList<>(stanceCandidates(center, actionTarget));
        candidates.addAll(stanceCandidates(center, actionTarget, 2, false));
        return List.copyOf(candidates);
    }

    private static List<BlockPos> stanceCandidates(
            BlockPos center,
            BlockPos actionTarget,
            int feetOffset,
            boolean excludeCenterSupport) {
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(actionTarget, "actionTarget");
        BlockPos surface = new BlockPos(actionTarget.getX(), center.getY(), actionTarget.getZ());
        ArrayList<BlockPos> inside = new ArrayList<>(4);
        ArrayList<BlockPos> outside = new ArrayList<>(4);
        for (Direction direction : List.of(
                Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            BlockPos horizontalSupport = surface.relative(direction);
            if (excludeCenterSupport && horizontalSupport.equals(center)) {
                continue;
            }
            BlockPos feet = horizontalSupport.above(feetOffset).immutable();
            if (contains(center, horizontalSupport)) {
                inside.add(feet);
            } else {
                outside.add(feet);
            }
        }
        inside.addAll(outside);
        return List.copyOf(inside);
    }

    private static Comparator<BlockPos> candidateComparator(BlockPos anchor) {
        return Comparator
                .comparingLong((BlockPos position) -> horizontalDistanceSquared(anchor, position))
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getZ);
    }

    public static long horizontalDistanceSquared(BlockPos first, BlockPos second) {
        long dx = (long) first.getX() - second.getX();
        long dz = (long) first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

}
