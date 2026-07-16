package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.agentic.elliegps.WaypointMutationResult;
import com.player2.playerengine.agentic.elliegps.WaypointMutationStatus;
import com.player2.playerengine.agentic.elliegps.FarmWaypointData;
import com.player2.playerengine.agentic.elliegps.WaypointRecord;
import com.player2.playerengine.agentic.elliegps.WaypointTypes;
import com.player2.playerengine.tasks.base.TaskSuspensionCause;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/** Server-free checks for the ordered planting root's resume and terminal barriers. */
public final class PlantFarmTaskSelfTest {
    private static final String DIMENSION = "minecraft:overworld";

    private PlantFarmTaskSelfTest() {
    }

    public static void runAll() {
        committedAutomaticCandidateCannotSwitch();
        transientDetachPreservesDurableCursorAndReceipt();
        issuedMutationRequiresTerminalReconciliation();
        terminalOutcomeResolutionIsExactOnce();
    }

    private static void committedAutomaticCandidateCannotSwitch() {
        BlockPos firstCenter = new BlockPos(8, 64, 8);
        BlockPos secondCenter = new BlockPos(40, 64, 40);
        PlantFarmTask task = task(List.of(
                new FarmPlantingRequest("minecraft:carrot", 1)));
        set(task, "candidates", List.of(farm(firstCenter), farm(secondCenter)));
        invoke(task, "freezeCandidate", new Class<?>[]{WaypointRecord.class}, farm(firstCenter));
        set(task, "destinationCommitted", true);
        set(task, "phase", PlantFarmPhase.ACQUIRE);

        require(!PlantFarmTask.mayTryNextCandidate(false, true, false, true),
                "a committed live farm closes automatic candidate selection");
        invoke(task, "rejectCandidateOrFail",
                new Class<?>[]{PlantFarmReason.class}, PlantFarmReason.FARM_NEEDS_REPAIR);

        require((int) get(task, "candidateIndex") == 0,
                "acquisition-era rejection cannot advance to another farm");
        require(firstCenter.equals(get(task, "center")),
                "the first committed farm center remains frozen");
        require(task.isFinished()
                        && task.outcome().reason() == PlantFarmReason.FARM_NEEDS_REPAIR,
                "post-commit rejection terminates against the frozen farm");
        require(PlantFarmTask.mayTryNextCandidate(false, false, false, true),
                "pre-commit automatic evaluation may still try the next candidate");
    }

    private static void transientDetachPreservesDurableCursorAndReceipt() {
        BlockPos center = new BlockPos(-12, 70, 5);
        PlantFarmTask task = task(List.of(
                new FarmPlantingRequest("minecraft:carrot", 2),
                new FarmPlantingRequest("minecraft:wheat_seeds", 3)));
        invoke(task, "freezeCandidate", new Class<?>[]{WaypointRecord.class}, farm(center));
        set(task, "destinationCommitted", true);
        set(task, "requestIndex", 1);
        int[] plantedCounts = (int[]) get(task, "plantedCounts");
        plantedCounts[0] = 2;
        set(task, "wholeTicks", 7_345);
        set(task, "phase", PlantFarmPhase.PLANT);
        set(task, "mutationAttempted", true);

        FarmPlantingItemResolver.Result resolved =
                FarmPlantingItemResolver.resolve(Items.WHEAT_SEEDS);
        require(resolved.supported(), "wheat seed fixture resolves after registry bootstrap");
        PlantFarmBlockTask pendingReceipt = new PlantFarmBlockTask(
                center.offset(1, 1, 0), center.offset(2, 1, 0), resolved.descriptor());
        set(pendingReceipt, "attempts", 1);
        set(task, "pendingMutationReceipt", pendingReceipt);
        set(task, "pendingReceiptTicks", 19);

        require(task.prepareForTransientResume(TaskSuspensionCause.HIGHER_PRIORITY_CHAIN),
                "priority interruption accepts the durable root checkpoint");
        task.afterChildrenStopped();
        invoke(task, "onStart", new Class<?>[0]);

        require(center.equals(get(task, "center")),
                "resume retains the frozen farm center");
        require((int) get(task, "requestIndex") == 1
                        && ((int[]) get(task, "plantedCounts"))[0] == 2,
                "resume retains the ordered request cursor and completed prefix");
        require((int) get(task, "wholeTicks") == 7_345,
                "resume retains the cumulative whole-operation deadline clock");
        require(get(task, "pendingMutationReceipt") == pendingReceipt
                        && pendingReceipt.hasIssuedMutation()
                        && (int) get(task, "pendingReceiptTicks") == 19,
                "resume retains the exact issued receipt object and settlement age");
        require(get(task, "phase") == PlantFarmPhase.SCAN,
                "resume reconciles live farm state before continuing the retained cursor");
        require(PlantFarmTask.resumePhase(true, false, true) == PlantFarmPhase.RESCAN,
                "a retained terminal-failure obligation resumes through rescan");
    }

    private static void issuedMutationRequiresTerminalReconciliation() {
        require(PlantFarmTask.failureTransition(
                        true, true, PlantFarmPhase.PLANT, true, true)
                        == PlantFarmTask.FailureTransition.RESCAN,
                "failure after issued input routes to RESCAN");
        require(PlantFarmTask.cancellationTransition(true, true, true, true)
                        == PlantFarmTask.FailureTransition.RESCAN,
                "cancellation after issued input uses the same RESCAN barrier");
        require(PlantFarmTask.phaseAfterObservedRescan() == PlantFarmPhase.COMMIT,
                "successful terminal RESCAN must flow to COMMIT");
        require(PlantFarmTask.failureTransition(
                        false, true, PlantFarmPhase.PLANT, true, true)
                        == PlantFarmTask.FailureTransition.TERMINAL,
                "a pre-input failure may terminate directly");
        require(PlantFarmTask.failureTransition(
                        true, true, PlantFarmPhase.COMMIT, true, true)
                        == PlantFarmTask.FailureTransition.TERMINAL,
                "commit failure terminates instead of looping reconciliation");
    }

    private static void terminalOutcomeResolutionIsExactOnce() {
        PlantFarmTask failed = task(List.of(
                new FarmPlantingRequest("minecraft:carrot", 1)));
        invoke(failed, "finalizeFailure",
                new Class<?>[]{PlantFarmReason.class, PlantFarmPhase.class},
                PlantFarmReason.INTERACTION_DENIED, PlantFarmPhase.RESOLVE);
        PlantFarmOutcome firstFailure = failed.outcome();
        invoke(failed, "finalizeFailure",
                new Class<?>[]{PlantFarmReason.class, PlantFarmPhase.class},
                PlantFarmReason.STORE_UNAVAILABLE, PlantFarmPhase.SCAN);
        invoke(failed, "succeed", new Class<?>[0]);
        require(failed.outcome() == firstFailure
                        && failed.outcome().reason() == PlantFarmReason.INTERACTION_DENIED,
                "first failed terminal outcome wins and remains identity-stable");

        BlockPos center = new BlockPos(3, 65, -9);
        PlantFarmTask succeeded = task(List.of(
                new FarmPlantingRequest("minecraft:carrot", 1)));
        invoke(succeeded, "freezeCandidate",
                new Class<?>[]{WaypointRecord.class}, farm(center));
        ((int[]) get(succeeded, "plantedCounts"))[0] = 1;
        set(succeeded, "openSlotsAfter", 79);
        set(succeeded, "waypointResult", new WaypointMutationResult(
                WaypointMutationStatus.NO_CHANGE, null, null));
        invoke(succeeded, "succeed", new Class<?>[0]);
        PlantFarmOutcome firstSuccess = succeeded.outcome();
        invoke(succeeded, "succeed", new Class<?>[0]);
        invoke(succeeded, "finalizeFailure",
                new Class<?>[]{PlantFarmReason.class, PlantFarmPhase.class},
                PlantFarmReason.INTERNAL_CONTRACT_FAILURE, PlantFarmPhase.COMMIT);
        require(succeeded.outcome() == firstSuccess && succeeded.isSuccessful(),
                "first successful terminal outcome wins and remains identity-stable");
    }

    private static PlantFarmTask task(List<FarmPlantingRequest> requests) {
        return new PlantFarmTask(
                BlockPos.ZERO,
                DIMENSION,
                requests,
                null,
                FarmPlantingPolicy.preserve(),
                null);
    }

    private static WaypointRecord farm(BlockPos center) {
        WaypointRecord record = new WaypointRecord();
        record.id = WaypointRecord.idFor(DIMENSION, center);
        record.type = WaypointTypes.FARM;
        record.dimension = DIMENSION;
        record.pos = new int[]{center.getX(), center.getY(), center.getZ()};
        record.data = new FarmWaypointData(4, 80, 80, List.of(), null);
        return record;
    }

    private static Object get(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("missing lifecycle field " + fieldName, failure);
        }
    }

    private static void set(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("cannot set lifecycle field " + fieldName, failure);
        }
    }

    private static Object invoke(
            Object target,
            String methodName,
            Class<?>[] parameterTypes,
            Object... arguments) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method.invoke(target, arguments);
        } catch (InvocationTargetException wrapped) {
            Throwable failure = wrapped.getCause();
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new AssertionError("lifecycle method failed: " + methodName, failure);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("missing lifecycle method " + methodName, failure);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("PlantFarmTask self-test failed: " + message);
        }
    }
}
