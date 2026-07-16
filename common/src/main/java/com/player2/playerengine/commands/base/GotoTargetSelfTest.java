package com.player2.playerengine.commands.base;

import com.player2.playerengine.util.Dimension;

/** Deterministic coordinate-retention checks for direct {@code goto} parsing. */
public final class GotoTargetSelfTest {
    private GotoTargetSelfTest() {}

    public static void runAll() throws CommandException {
        GotoTarget xyz = GotoTarget.parseRemainder("11 71 -2");
        require(xyz.getType() == GotoTarget.GotoTargetCoordType.XYZ, "three numbers must select XYZ");
        require(xyz.getX() == 11 && xyz.getY() == 71 && xyz.getZ() == -2,
                "XYZ coordinates must be assigned before target construction");

        GotoTarget spaced = GotoTarget.parseRemainder("(  11\t71   -2  )");
        require(spaced.getX() == 11 && spaced.getY() == 71 && spaced.getZ() == -2,
                "direct parsing must tolerate repeated whitespace inside parentheses");

        GotoTarget xz = GotoTarget.parseRemainder("11 -2 nether");
        require(xz.getType() == GotoTarget.GotoTargetCoordType.XZ, "two numbers must select XZ");
        require(xz.getX() == 11 && xz.getZ() == -2 && xz.getDimension() == Dimension.NETHER,
                "XZ coordinates and dimension must survive parsing");

        GotoTarget y = GotoTarget.parseRemainder("71");
        require(y.getType() == GotoTarget.GotoTargetCoordType.Y && y.getY() == 71,
                "one number must retain the requested Y level");

        GotoTarget dimension = GotoTarget.parseRemainder("overworld");
        require(dimension.getType() == GotoTarget.GotoTargetCoordType.NONE
                        && dimension.getDimension() == Dimension.OVERWORLD,
                "dimension-only goto must remain dimension-only");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
