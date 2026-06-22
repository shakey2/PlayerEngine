package com.player2.playerengine.tasks.crafting;

public enum CraftMacroStepKind {
   CRAFT_PLANKS_IN_INVENTORY,
   CRAFT_STICKS_IN_INVENTORY,
   CRAFT_CRAFTING_TABLE_IN_INVENTORY,
   /**
    * A generic 2x2-fittable intermediate or output that is crafted directly in the player inventory
    * (no crafting table required). Distinct from {@link #CRAFT_CRAFTING_TABLE_IN_INVENTORY} so that
    * a 2x2 intermediate is NOT skipped when a crafting table is nearby (the table-skip logic in
    * {@code CraftMacroResourceTask} tests for {@code CRAFT_CRAFTING_TABLE_IN_INVENTORY} specifically;
    * this constant does NOT match those sites, causing the expected fall-through to the generic
    * held-count satisfaction check at lines 1352-1358).
    */
   CRAFT_2X2_IN_INVENTORY,
   CRAFT_OUTPUT_IN_TABLE
}
