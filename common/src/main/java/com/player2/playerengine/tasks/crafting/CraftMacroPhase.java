package com.player2.playerengine.tasks.crafting;

public enum CraftMacroPhase {
   PLAN,
   COLLECT_MISSING_MATERIALS,
   CRAFT_2X2_INTERMEDIATES,
   FIND_OR_PLACE_TABLE,
   MOVE_TO_TABLE,
   LOOK_AT_TABLE,
   CRAFT_3X3_OUTPUT,
   VERIFY_OUTPUT,
   DONE
}
