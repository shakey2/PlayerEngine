package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.agentic.elliegps.WaypointRecord;
import com.player2.playerengine.agentic.elliegps.WaypointSearchService;
import com.player2.playerengine.agentic.elliegps.WaypointTypes;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Pure contract checks for ordered, bounded, checkpointed planting-item acquisition. */
public final class FarmPlantingItemAcquisitionTaskSelfTest {
    private FarmPlantingItemAcquisitionTaskSelfTest() {
    }

    public static void runAll() {
        checkpointIdentityIsDurable();
        sourceOrderIsNormative();
        multiRoundBoundsAreFiniteAndReachable();
        structureLookupIsLoadedAndBounded();
        receiptOwnershipIgnoresUnrelatedInventoryGrowth();
        wheatNaturalSourcesNeverMineGrassBlocks();
        wheatSourceHandCannotUseShears();
        registeredFarmColumnsFailClosed();
    }

    private static void structureLookupIsLoadedAndBounded() {
        require(PlantableSourcePlanner.loadedStructureLookupPermitted(
                        PlantableSourcePlanner.MAX_STRUCTURE_REFERENCE_TYPES,
                        PlantableSourcePlanner.MAX_STRUCTURE_REFERENCES,
                        true),
                "loaded village references at both hard bounds permit the exact lookup");
        require(!PlantableSourcePlanner.loadedStructureLookupPermitted(
                        PlantableSourcePlanner.MAX_STRUCTURE_REFERENCE_TYPES + 1,
                        0,
                        true)
                        && !PlantableSourcePlanner.loadedStructureLookupPermitted(
                        1,
                        PlantableSourcePlanner.MAX_STRUCTURE_REFERENCES + 1,
                        true),
                "oversized structure maps and village reference sets fail closed");
        require(!PlantableSourcePlanner.loadedStructureLookupPermitted(1, 1, false),
                "an unloaded referenced structure-start chunk fails closed");
    }

    private static void checkpointIdentityIsDurable() {
        BlockPos origin = new BlockPos(12, 64, -7);
        FarmPlantingItemAcquisitionTask.Checkpoint checkpoint =
                new FarmPlantingItemAcquisitionTask.Checkpoint();
        FarmPlantingItemAcquisitionTask first =
                new FarmPlantingItemAcquisitionTask(
                        Items.CARROT, 7, origin, "minecraft:overworld", checkpoint);
        FarmPlantingItemAcquisitionTask resumed =
                new FarmPlantingItemAcquisitionTask(
                        Items.CARROT, 7, origin, "minecraft:overworld", checkpoint);
        require(first.checkpoint() == resumed.checkpoint()
                        && checkpoint.isBound()
                        && checkpoint.absoluteTargetCount() == 7
                        && checkpoint.frozenOrigin().equals(origin)
                        && "minecraft:carrot".equals(checkpoint.targetItemId())
                        && first.phase() == FarmPlantingItemAcquisitionTask.Phase.CHECK_INVENTORY
                        && first.outcome() == FarmPlantingItemAcquisitionTask.Outcome.RUNNING,
                "a reconstructed child keeps the same logical acquisition identity");
        expectThrows(() -> new FarmPlantingItemAcquisitionTask(
                        Items.POTATO, 7, origin, "minecraft:overworld", checkpoint),
                "a checkpoint cannot be rebound to a different item");
        expectThrows(() -> new FarmPlantingItemAcquisitionTask(
                        Items.CARROT, 8, origin, "minecraft:overworld", checkpoint),
                "a checkpoint cannot be rebound to a different absolute target");
    }

    private static void sourceOrderIsNormative() {
        require(PlantableSourcePlanner.sourceOrder(true).equals(List.of(
                        PlantableSourcePlanner.SourceKind.MARKED_CHEST,
                        PlantableSourcePlanner.SourceKind.NATURAL_GRASS,
                        PlantableSourcePlanner.SourceKind.VILLAGE_CROP,
                        PlantableSourcePlanner.SourceKind.VILLAGE_CHEST)),
                "wheat uses marked storage, natural grass, village crop, then village chest");
        require(PlantableSourcePlanner.sourceOrder(false).equals(List.of(
                        PlantableSourcePlanner.SourceKind.MARKED_CHEST,
                        PlantableSourcePlanner.SourceKind.VILLAGE_CROP,
                        PlantableSourcePlanner.SourceKind.VILLAGE_CHEST)),
                "other plantables skip the wheat-only natural source phase");
    }

    private static void multiRoundBoundsAreFiniteAndReachable() {
        require(FarmPlantingItemAcquisitionTask.MAX_WORLD_ROUNDS == 16,
                "world discovery has the finite sixteen-round seed-yield budget");
        require(PlantableSourcePlanner.MAX_SCANNER_CYCLE_VISITS
                        == FarmPlantingItemAcquisitionTask.MAX_WORLD_ROUNDS
                        * PlantableSourcePlanner.MAX_SCANNER_VISITS,
                "durable stage cursors can traverse the entire bounded scanner cycle");
        require(FarmPlantingItemAcquisitionTask.WHOLE_TIMEOUT_TICKS
                        > FarmPlantingItemAcquisitionTask.MAX_WORLD_ROUNDS
                        * FarmPlantingItemAcquisitionTask.WORLD_ROUND_WANDER_TICKS,
                "the whole deadline reserves time for every finite wander plus interactions");
        require(FarmPlantingItemAcquisitionTask.WHOLE_TIMEOUT_TICKS < 24_000,
                "acquisition remains inside the root operation deadline");
        require(PlantableSourcePlanner.MAX_NATURAL_GRASS
                        == PlantableSourcePlanner.MAX_SCANNER_VISITS
                        && PlantableSourcePlanner.MAX_VILLAGE_CROPS
                        == PlantableSourcePlanner.MAX_SCANNER_VISITS
                        && PlantableSourcePlanner.MAX_VILLAGE_CHESTS
                        == PlantableSourcePlanner.MAX_SCANNER_VISITS,
                "eligible candidates are never discarded behind an advanced scanner cursor");
    }

    private static void receiptOwnershipIgnoresUnrelatedInventoryGrowth() {
        require(!FarmPlantingItemAcquisitionTask.transferReceiptAllowsAdvance(
                        false, 0, true),
                "a player gift cannot retire a detached transfer receipt");
        require(FarmPlantingItemAcquisitionTask.transferReceiptAllowsAdvance(
                        false, 1, false)
                        && FarmPlantingItemAcquisitionTask.transferReceiptAllowsAdvance(
                        true, 0, false),
                "only authoritative moved or terminal transfer state retires its source");
        require(!FarmPlantingItemAcquisitionTask.breakReceiptOwnsMutation(
                        false, false, false, true)
                        && !FarmPlantingItemAcquisitionTask.breakReceiptOwnsMutation(
                        false, true, false, true),
                "a gift cannot claim an unissued or unchanged break target");
        require(FarmPlantingItemAcquisitionTask.breakReceiptOwnsMutation(
                        true, false, false, false)
                        && FarmPlantingItemAcquisitionTask.breakReceiptOwnsMutation(
                        false, true, true, false),
                "child success or issued plus verified removal owns the break mutation");
    }

    private static void wheatNaturalSourcesNeverMineGrassBlocks() {
        Block[] blocks = PlantableSourcePlanner.naturalGrassBlocks();
        require(blocks.length == 2
                        && blocks[0] == Blocks.GRASS
                        && blocks[1] == Blocks.TALL_GRASS,
                "only short and tall grass are natural wheat-seed sources");
        for (Block block : blocks) {
            require(block != Blocks.GRASS_BLOCK,
                    "grass blocks are never natural wheat-seed sources");
        }
    }

    private static void wheatSourceHandCannotUseShears() {
        require(!VerifiedBreakBlockTask.safeUnmodifiedDropHand(
                        new ItemStack(Items.SHEARS)),
                "shears cannot alter natural grass source loot");
        require(VerifiedBreakBlockTask.safeUnmodifiedDropHand(
                        new ItemStack(Items.DIRT)),
                "an ordinary carried stack preserves natural grass loot");
        require(VerifiedBreakBlockTask.safeUnmodifiedDropHand(ItemStack.EMPTY),
                "an empty hand preserves natural grass loot");
    }

    private static void registeredFarmColumnsFailClosed() {
        WaypointRecord farm = new WaypointRecord();
        farm.type = WaypointTypes.FARM;
        farm.dimension = "minecraft:overworld";
        farm.pos = new int[]{20, 64, 20};
        RegisteredFarmExclusionSnapshot snapshot =
                RegisteredFarmExclusionSnapshot.fromRecords(
                        "minecraft:overworld", List.of(farm));
        require(snapshot.available() && snapshot.footprintCount() == 1,
                "registered farm snapshot is available and finite");
        require(snapshot.contains(new BlockPos(24, 200, 16)),
                "the 9x9 registered column excludes sources regardless of Y");
        require(!snapshot.contains(new BlockPos(25, 64, 20)),
                "the first horizontal position outside radius four remains eligible");
        require(RegisteredFarmExclusionSnapshot.unavailable().contains(BlockPos.ZERO),
                "an unavailable exclusion snapshot fails closed");
        require(!RegisteredFarmExclusionSnapshot.fromRecords(
                        "minecraft:overworld",
                        Collections.nCopies(
                                WaypointSearchService.MAX_AUTHORITATIVE_RECORDS + 1,
                                farm)).available(),
                "an oversized authoritative farm snapshot fails closed at its hard cap");
    }

    private static void expectThrows(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new IllegalStateException(
                "Farm planting acquisition self-test failed: " + message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(
                    "Farm planting acquisition self-test failed: " + message);
        }
    }
}
