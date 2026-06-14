package com.player2.playerengine.tasks.crafting.resolver;

import com.player2.playerengine.tasks.crafting.CraftMacroStep;
import com.player2.playerengine.util.ItemTarget;
import java.util.List;

/**
 * Immutable result of one deterministic {@code MaterialResolver.resolve} call: the remaining work to
 * produce a target item right now, computed purely from current inventory plus recipe arithmetic.
 *
 * <p>Byte-identical across the 1.20.1 and 1.21.1 PlayerEngine branches. This type is part of the
 * deterministic resolver package: no Player2/AiTask/Joules calls anywhere.
 *
 * @param status           overall verdict — {@link Status#READY} (target already on hand, no work),
 *                         {@link Status#NEEDS_WORK} (sub-crafts and/or acquisitions remain), or
 *                         {@link Status#UNOBTAINABLE} (no reachable means; {@link #failureReason} set).
 * @param remainingSteps   ordered remaining sub-craft steps, deepest sub-crafts first and the target
 *                         craft last; empty when {@link Status#READY}.
 * @param externalNeeded   raw acquisitions still owed, as named catalogue {@link ItemTarget}s (e.g.
 *                         {@code new ItemTarget("log", n)}); empty when everything remaining is an
 *                         inventory sub-craft.
 * @param remainingDeficit the TIERED progress metric, NOT a target-only count. It is the total
 *                         remaining work across all tiers:
 *                         {@code Σ remainingSteps[i].craftsNeeded + Σ externalNeeded[j].count}
 *                         — the sum of per-tier sub-crafts still owed (e.g. logs→planks crafts,
 *                         planks→chest crafts, …) plus raw acquisitions still owed. A target-only
 *                         count would stay flat while a productive intermediate is crafted (e.g.
 *                         log→planks toward a chest does not change the chest count) and would
 *                         falsely trip the no-progress guard; with this tiered definition any
 *                         productive intermediate craft strictly decreases the value by at least one,
 *                         which is the monotonic-shrink property the convergence guard relies on.
 * @param failureReason    human-meaningful reason, populated when {@link Status#UNOBTAINABLE} so the
 *                         executor can surface it to both the player and the model
 *                         (per {@code DESIGN.md} §3); typically empty otherwise.
 */
public record ResolverResult(
      Status status,
      List<CraftMacroStep> remainingSteps,
      List<ItemTarget> externalNeeded,
      int remainingDeficit,
      String failureReason) {

   /**
    * Overall verdict for a resolve.
    *
    * <ul>
    *   <li>{@link #READY} — the target is already on hand in the requested count; no remaining work.</li>
    *   <li>{@link #NEEDS_WORK} — sub-crafts and/or external acquisitions remain.</li>
    *   <li>{@link #UNOBTAINABLE} — the target cannot be produced from reachable means; the macro
    *       terminates cleanly via {@code terminateMacro(failureReason)}.</li>
    * </ul>
    */
   public enum Status {
      READY,
      NEEDS_WORK,
      UNOBTAINABLE
   }
}
