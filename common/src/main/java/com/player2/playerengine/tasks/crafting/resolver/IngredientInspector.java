package com.player2.playerengine.tasks.crafting.resolver;

import java.util.Optional;
import java.util.Set;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * Cross-version classification of a single recipe {@link Ingredient} (one crafting slot).
 *
 * <p>Hides the incompatible recipe/{@code Ingredient} introspection API between MC 1.20.1 and 1.21.1
 * behind one interface so the deterministic resolver code stays byte-identical across both branches.
 * The concrete implementation ({@code IngredientInspectorImpl}) carries the only legitimate
 * per-branch divergence (1.20.1 {@code toJson} tag-detection vs 1.21.1 {@code getValues}/{@code
 * isCustom}).
 *
 * <p><b>Slot classification:</b>
 * <ul>
 *   <li>{@link SlotKind#AGNOSTIC} — the slot is tag-based; it accepts any item of a {@link TagKey}
 *       (e.g. {@code #minecraft:planks}). {@link #tagOf(Ingredient)} returns that tag and the
 *       resolver may pool every held variant of it.</li>
 *   <li>{@link SlotKind#LOCKED} — the slot accepts a single concrete {@link Item} (variant-locked).
 *       {@link #tagOf(Ingredient)} is empty.</li>
 *   <li>{@link SlotKind#CUSTOM} — a custom/modded ingredient with no recoverable tag identity; only
 *       the enumerated {@link #acceptedItems(Ingredient)} are known. {@link #tagOf(Ingredient)} is
 *       empty.</li>
 * </ul>
 *
 * <p><b>Invariant:</b> implementations must only be called <i>after server tags are bound</i>
 * (post recipe/tag reload); otherwise tag expansion yields the BARRIER sentinel. The resolver runs
 * only during server task ticks, so this is always satisfied.
 *
 * <p>This type is part of the deterministic resolver package: it performs no Player2/AiTask/Joules
 * calls and only reads the Minecraft recipe/tag system.
 */
public interface IngredientInspector {

   /**
    * Classify a single recipe slot as {@link SlotKind#AGNOSTIC}, {@link SlotKind#LOCKED}, or
    * {@link SlotKind#CUSTOM}.
    */
   SlotKind classify(Ingredient slot);

   /**
    * The {@link TagKey} this slot accepts, present iff {@link #classify(Ingredient)} is
    * {@link SlotKind#AGNOSTIC}; {@link Optional#empty()} for LOCKED and CUSTOM slots.
    */
   Optional<TagKey<Item>> tagOf(Ingredient slot);

   /**
    * The full set of {@link Item}s this slot accepts — tag-expanded for AGNOSTIC slots, the single
    * item for LOCKED slots, and the enumerated items for CUSTOM slots.
    */
   Set<Item> acceptedItems(Ingredient slot);

   /** Kind of a single recipe ingredient slot. */
   enum SlotKind {
      AGNOSTIC,
      LOCKED,
      CUSTOM
   }
}
