package com.player2.playerengine.tasks.movement;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.automaton.api.pathing.goals.Goal;
import com.player2.playerengine.automaton.api.pathing.goals.GoalNear;
import com.player2.playerengine.tasks.base.ITaskRequiresGrounded;
import com.player2.playerengine.tasks.base.Task;
import net.minecraft.core.BlockPos;

/**
 * Paths until the bot's feet block lies within {@code maxBlocks} (Euclidean) of {@code center}.
 * Unlike {@link GetToBlockTask} with {@link com.player2.playerengine.automaton.api.pathing.goals.GoalBlock},
 * this does not require standing on one exact block, which avoids long detours when placing against
 * supports from the side.
 */
public class ApproachNearBlockPosTask extends CustomBaritoneGoalTask implements ITaskRequiresGrounded {

    private final BlockPos center;
    private final int maxBlocks;

    public ApproachNearBlockPosTask(BlockPos center, int maxBlocks) {
        super(false);
        this.center = center.immutable();
        this.maxBlocks = Math.max(1, maxBlocks);
    }

    @Override
    protected Goal newGoal(PlayerEngineController mod) {
        return new GoalNear(this.center, this.maxBlocks);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof ApproachNearBlockPosTask t
                && t.center.equals(this.center)
                && t.maxBlocks == this.maxBlocks;
    }

    @Override
    protected String toDebugString() {
        return "ApproachNearBlockPosTask center=" + this.center + " maxBlocks=" + this.maxBlocks;
    }
}
