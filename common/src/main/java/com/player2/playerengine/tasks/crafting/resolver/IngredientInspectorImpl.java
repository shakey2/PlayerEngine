package com.player2.playerengine.tasks.crafting.resolver;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * Cross-version implementation of {@link IngredientInspector}.
 *
 * <p><b>Byte-identical across the 1.20.1 and 1.21.1 PlayerEngine branches.</b> It relies ONLY on the
 * version-stable public {@code Ingredient} API — {@link Ingredient#isEmpty()} and
 * {@link Ingredient#getItems()} (which returns a tag-expanded {@code ItemStack[]} in both Loom
 * mappings). The internal {@code Ingredient.Value}/{@code TagValue}/{@code ItemValue} types and the
 * {@code getValues()}/{@code isCustom()}/{@code toJson()} accessors are deliberately NOT used: they are
 * private or absent in the actual 1.21.1 compile mapping (the decompiled-sources view disagrees with
 * the remapped classpath), and the resolver never consumes the concrete tag identity anyway.
 *
 * <p><b>Classification</b> is by accepted-item cardinality, read from the recipe/tag system via
 * {@code getItems()} (never from hardcoded item arrays): a slot that accepts more than one distinct
 * item is {@link SlotKind#AGNOSTIC} (pool across the accepted set, e.g. the {@code #minecraft:planks}
 * slot of a chest); a slot that accepts exactly one item is {@link SlotKind#LOCKED}; an empty/
 * unresolvable slot is {@link SlotKind#CUSTOM}. This matches the resolver's actual need — pool whenever
 * multiple variants are accepted — and is robust to tag-vs-explicit-list expression and to mapping
 * drift. Variant-lock for a specific output is enforced upstream by recipe selection
 * ({@code RecipeAccess.selectRecipeForResult}), not here.
 *
 * <p>This type is part of the deterministic resolver package: it performs no Player2/AiTask/Joules
 * calls and only reads the Minecraft recipe/tag system.
 */
public class IngredientInspectorImpl implements IngredientInspector {

   @Override
   public SlotKind classify(Ingredient slot) {
      if (slot == null || slot.isEmpty()) {
         return SlotKind.CUSTOM;
      }
      int distinct = acceptedItems(slot).size();
      if (distinct == 0) {
         return SlotKind.CUSTOM;
      }
      return distinct > 1 ? SlotKind.AGNOSTIC : SlotKind.LOCKED;
   }

   @Override
   public Optional<TagKey<Item>> tagOf(Ingredient slot) {
      // The concrete tag identity is not recoverable from the version-stable public Ingredient API
      // (the internal value/tag accessors are private or absent in the 1.21.1 mapping). The resolver
      // does not consume the tag itself — it pools by the accepted-item set from acceptedItems() — so
      // this returns empty. Agnostic-vs-locked is decided by accepted-item cardinality in classify().
      return Optional.empty();
   }

   @Override
   public Set<Item> acceptedItems(Ingredient slot) {
      // getItems() is tag-expanded and identical in signature on both branches; null-safe for an empty
      // slot. This is the single source of truth for a slot's accepted variants.
      Set<Item> items = new LinkedHashSet<>();
      if (slot == null) {
         return items;
      }
      for (ItemStack stack : slot.getItems()) {
         if (stack != null && !stack.isEmpty()) {
            items.add(stack.getItem());
         }
      }
      return items;
   }
}
