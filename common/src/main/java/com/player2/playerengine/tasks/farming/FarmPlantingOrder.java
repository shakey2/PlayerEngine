package com.player2.playerengine.tasks.farming;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Pure deterministic ordering that fills the far edge first and retreats toward one exit edge. */
public final class FarmPlantingOrder {
    private static final List<Direction> EXIT_TIE_ORDER = List.of(
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);

    private FarmPlantingOrder() {
    }

    /** Selects the cardinal plot edge nearest the operation's frozen approach anchor. */
    public static Direction selectExitEdge(BlockPos center, BlockPos approachAnchor) {
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(approachAnchor, "approachAnchor");
        Direction selected = null;
        long selectedDistance = Long.MAX_VALUE;
        for (Direction direction : EXIT_TIE_ORDER) {
            BlockPos edge = center.relative(direction, FarmPlotPolicy.HYDRATION_RADIUS).above();
            long distance = FarmPlotGeometry.horizontalDistanceSquared(edge, approachAnchor);
            if (selected == null || distance < selectedDistance) {
                selected = direction;
                selectedDistance = distance;
            }
        }
        return selected;
    }

    /**
     * Orders crop target positions ({@code soil.above()}) as a retreating snake.
     *
     * <p>The result is independent of input iteration order. Duplicate targets collapse, while a
     * target outside the frozen 80 crop cells is rejected as a caller contract failure.</p>
     */
    public static List<BlockPos> retreatOrder(
            BlockPos center,
            Iterable<BlockPos> openCropTargets,
            Direction exitEdge) {
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(openCropTargets, "openCropTargets");
        requireHorizontal(exitEdge);

        Set<BlockPos> allowed = new LinkedHashSet<>();
        for (BlockPos soil : FarmPlotGeometry.soilCells(center)) {
            allowed.add(soil.above().immutable());
        }
        LinkedHashSet<BlockPos> unique = new LinkedHashSet<>();
        for (BlockPos requested : openCropTargets) {
            BlockPos target = Objects.requireNonNull(requested, "open crop target").immutable();
            if (!allowed.contains(target)) {
                throw new IllegalArgumentException(
                        "planting target is outside the frozen farm footprint: " + target);
            }
            unique.add(target);
        }

        ArrayList<BlockPos> ordered = new ArrayList<>(unique);
        ordered.sort(retreatComparator(center, exitEdge));
        return List.copyOf(ordered);
    }

    private static Comparator<BlockPos> retreatComparator(BlockPos center, Direction exitEdge) {
        return (left, right) -> {
            int leftDepth = depthFromExit(center, left, exitEdge);
            int rightDepth = depthFromExit(center, right, exitEdge);
            int depthOrder = Integer.compare(rightDepth, leftDepth);
            if (depthOrder != 0) {
                return depthOrder;
            }

            int leftCross = crossCoordinate(left, exitEdge);
            int rightCross = crossCoordinate(right, exitEdge);
            int crossOrder = Integer.compare(leftCross, rightCross);
            if ((leftDepth & 1) != 0) {
                crossOrder = -crossOrder;
            }
            if (crossOrder != 0) {
                return crossOrder;
            }
            int xOrder = Integer.compare(left.getX(), right.getX());
            return xOrder != 0 ? xOrder : Integer.compare(left.getZ(), right.getZ());
        };
    }

    private static int depthFromExit(BlockPos center, BlockPos target, Direction exitEdge) {
        int radius = FarmPlotPolicy.HYDRATION_RADIUS;
        return switch (exitEdge) {
            case NORTH -> target.getZ() - (center.getZ() - radius);
            case SOUTH -> center.getZ() + radius - target.getZ();
            case WEST -> target.getX() - (center.getX() - radius);
            case EAST -> center.getX() + radius - target.getX();
            default -> throw new IllegalArgumentException("exit edge must be horizontal");
        };
    }

    private static int crossCoordinate(BlockPos target, Direction exitEdge) {
        return exitEdge.getAxis() == Direction.Axis.Z ? target.getX() : target.getZ();
    }

    private static void requireHorizontal(Direction direction) {
        if (direction == null || direction.getAxis().isVertical()) {
            throw new IllegalArgumentException("exit edge must be horizontal");
        }
    }
}
