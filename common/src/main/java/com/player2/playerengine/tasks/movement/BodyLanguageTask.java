package com.player2.playerengine.tasks.movement;



import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.automaton.api.utils.Rotation;
import com.player2.playerengine.automaton.api.utils.input.Input;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class BodyLanguageTask extends Task {
    private static final Logger LOGGER = LogManager.getLogger();

    public enum Type { GREETING, NOD_HEAD, SHAKE_HEAD, VICTORY }

    private final Type type;
    private final static float shakeNodHeadAngle = 40;
    private final static int shakeNodHeadTime = 10; // ticks


    private final PrimitiveSequenceTask seqGreeting = makeGreeting();
    private final PrimitiveSequenceTask seqNodHead  = makeNodHead(2);
    private final PrimitiveSequenceTask seqShake    = makeShakeHead(2);
    private final PrimitiveSequenceTask seqVictory  = makeVictoryDance();

    private Task actuallyRunningTask;

    /** Type-safe constructor — callers must resolve the action to a {@link Type} before constructing. */
    public BodyLanguageTask(Type type) {
        this.type = type;
    }

    /**
     * Resolves an action string to a {@link Type}.  Throws {@link IllegalArgumentException} on any
     * unknown value so no caller can silently default to GREETING — callers must validate first and
     * report the error to both audiences before constructing a task.
     */
    public static Type resolveType(String t) {
        try {
            return Type.valueOf(t.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown bodylang action: '" + t + "'. Valid: greeting, nod_head, shake_head, victory.");
        }
    }

    @Override
    protected void onStart() {
        boolean followWasActive = false;
        if (this.controller != null) {
            Task utTask = this.controller.getUserTaskChain().getCurrentTask();
            followWasActive = (utTask instanceof FollowPlayerTask) && utTask.isActive();
        }
        LOGGER.info("[FollowDiag] GESTURE-START: type={} followWasActiveInUserTaskChain={}",
                type, followWasActive);
        switch (type) {
            case GREETING:
                actuallyRunningTask = seqGreeting;
                break;
            case NOD_HEAD:
                actuallyRunningTask = seqNodHead;
                break;
            case SHAKE_HEAD:
                actuallyRunningTask = seqShake;
                break;
            case VICTORY:
                actuallyRunningTask = seqVictory;
                break;
        }
    }

    @Override
    protected Task onTick() {
        return actuallyRunningTask;
    }

    @Override
    public boolean isFinished() {
        return  actuallyRunningTask != null && actuallyRunningTask.isFinished();
    }

    @Override
    protected boolean isEqual(Task other) {
        if (!(other instanceof BodyLanguageTask)) return false;
        BodyLanguageTask o = (BodyLanguageTask) other;
        return o.type == this.type;
    }

    @Override
    protected void onStop(Task next) {
        LOGGER.info("[FollowDiag] GESTURE-STOP: type={} finished={} nextTask={}",
                type, this.isFinished(),
                (next != null) ? next.getClass().getSimpleName() : "(none)");
        PlayerEngineController mod = this.controller;
        mod.getInputControls().release(Input.SNEAK);
        mod.getInputControls().release(Input.JUMP);
        mod.getInputControls().release(Input.SPRINT);
        mod.getInputControls().release(Input.MOVE_FORWARD);
        mod.getInputControls().release(Input.MOVE_BACK);
        mod.getInputControls().release(Input.MOVE_LEFT);
        mod.getInputControls().release(Input.MOVE_RIGHT);
    }

    @Override
    protected String toDebugString() {
        return "BodyLanguage(" + type + ")";
    }


    private static PrimitiveSequenceTask makeGreeting() {
        // A recognizable bow: sneak-hold pitches the player forward visually; the lookRelative
        // pitch-down + pitch-up reinforces the head-bow read.  Two repetitions = greeting bow.
        int holdTicks = 8, pauseTicks = 6;
        int bowCount = 2;
        PrimitiveSequenceTask.Sequence.Builder b = PrimitiveSequenceTask.builder();
        for (int i = 0; i < bowCount; i++) {
            b.hold(Input.SNEAK).waitTicks(holdTicks).release(Input.SNEAK).waitTicks(pauseTicks);
        }
        return b.build();
    }

    private static PrimitiveSequenceTask makeNodHead(int nods) {
        PrimitiveSequenceTask.Sequence.Builder b = PrimitiveSequenceTask.builder();
        for (int i = 0; i < nods; i++) {
            b.lookRelative(new Rotation(0, +shakeNodHeadAngle), shakeNodHeadTime);
            b.lookRelative(new Rotation(0, -shakeNodHeadAngle), shakeNodHeadTime);
        }
        b.waitTicks(2);
        return b.build();
    }

    private static PrimitiveSequenceTask makeShakeHead(int shakes) {
        PrimitiveSequenceTask.Sequence.Builder b = PrimitiveSequenceTask.builder();
        for (int i = 0; i < shakes; i++) {
            b.lookRelative(new Rotation(+shakeNodHeadAngle, 0), shakeNodHeadTime);
            b.lookRelative(new Rotation(-shakeNodHeadAngle, 0), shakeNodHeadTime);
        }
        return b.build();
    }

    private static PrimitiveSequenceTask makeVictoryDance() {
        return PrimitiveSequenceTask.builder()
                .jump()
                .waitTicks(4)
                .lookRelative(new Rotation(180f, 0), 20)
                .lookRelative(new Rotation(-180f, 0), 20)
                .hold(Input.SNEAK).waitTicks(6).release(Input.SNEAK)
                .jump()
                .build();
    }
}
