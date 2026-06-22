package com.player2.playerengine.tasks.agentic;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.util.MiningRequirement;
import com.player2.playerengine.util.helpers.StorageHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * Thin deterministic wrapper over {@link MiningRequirement} and {@link StorageHelper} for
 * resolving which tool tier a block requires and whether the bot currently holds a sufficient one.
 *
 * <p><b>Design invariants (HARD — violating any is a defect):</b>
 * <ul>
 *   <li>No model calls, no {@code AiTaskClass}, no deepsearch. Resolution is 100% deterministic.</li>
 *   <li>Never calls {@code isCorrectToolForDrops} directly — that divergence is confined to
 *       {@link MiningRequirement#getMinimumRequirementForBlock}, which is read-only.</li>
 *   <li>No {@code Tier.getLevel()}, {@code Tier.getTag()}, or {@code TierSortingRegistry} —
 *       all are 1.20.1-only and absent from 1.21.1 NeoForge. This file is byte-identical across
 *       branches.</li>
 *   <li>Tier ceiling is {@link MiningRequirement#DIAMOND}. There is no NETHERITE requirement tier
 *       anywhere; netherite pickaxes satisfy DIAMOND inside {@link StorageHelper}, never emit as a
 *       requirement.</li>
 *   <li>{@link MiningRequirement#getMinimumRequirementForBlock} never returns {@code null} and never
 *       throws (falls back to DIAMOND with a warning). No null guard is added here.</li>
 * </ul>
 *
 * <p><b>HAND short-circuit:</b> when {@link #requirementFor} returns {@link MiningRequirement#HAND},
 * the block needs no specific tool — callers (e.g. {@code ToolAcquisitionTask}) must short-circuit
 * acquisition entirely rather than trying to acquire a "HAND" pickaxe.
 */
public final class ToolRequirementResolver {

    private ToolRequirementResolver() {}

    /**
     * Returns the minimum {@link MiningRequirement} for {@code block}.
     *
     * <p>Delegates to {@link MiningRequirement#getMinimumRequirementForBlock}. The method is
     * guaranteed non-null (falls back to {@code DIAMOND} for unmatched blocks). Returns
     * {@link MiningRequirement#HAND} when the block needs no specific tool for drops.
     *
     * @param block the target block
     * @return the minimum requirement (never null)
     */
    public static MiningRequirement requirementFor(Block block) {
        return MiningRequirement.getMinimumRequirementForBlock(block);
    }

    /**
     * Returns {@code true} when the bot holds at least one sufficient pickaxe anywhere
     * (hotbar or main inventory), including off-hand, via {@link StorageHelper#miningRequirementMet}.
     *
     * <p>Always returns {@code true} for {@link MiningRequirement#HAND} (no tool needed).
     *
     * @param controller the bot controller
     * @param req        the minimum requirement to satisfy
     * @return whether the bot holds a sufficient pickaxe
     */
    public static boolean isHeld(PlayerEngineController controller, MiningRequirement req) {
        return StorageHelper.miningRequirementMet(controller, req);
    }

    /**
     * Returns {@code true} when the bot holds a sufficient pickaxe in its main inventory only
     * (excluding off-hand / crafting slots), via {@link StorageHelper#miningRequirementMetInventory}.
     *
     * <p>Always returns {@code true} for {@link MiningRequirement#HAND} (no tool needed).
     *
     * @param controller the bot controller
     * @param req        the minimum requirement to satisfy
     * @return whether the bot holds a sufficient pickaxe in inventory
     */
    public static boolean isHeldInventoryOnly(PlayerEngineController controller, MiningRequirement req) {
        return StorageHelper.miningRequirementMetInventory(controller, req);
    }

    /**
     * Returns the concrete minimum {@link Item} (pickaxe) that satisfies {@code req}.
     *
     * <p>For {@link MiningRequirement#HAND} this returns {@link net.minecraft.world.item.Items#AIR}
     * — callers that short-circuit on HAND will never reach this in practice.
     *
     * @param req the requirement whose pickaxe item is needed
     * @return the minimum pickaxe item for this requirement (non-null)
     */
    public static Item minimumPickaxe(MiningRequirement req) {
        return req.getMinimumPickaxe();
    }

    /**
     * Convenience: returns {@code true} if {@code req} is {@link MiningRequirement#HAND}, meaning
     * the target block needs no specific tool for drops and tool acquisition can be skipped entirely.
     *
     * @param req the requirement to test
     * @return {@code true} if no pickaxe is required
     */
    public static boolean isHandOnly(MiningRequirement req) {
        return req == MiningRequirement.HAND;
    }
}
