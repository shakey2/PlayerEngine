package com.player2.playerengine.tasks.crafting;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.TaskCatalogue;
import com.player2.playerengine.util.Debug;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.WoodType;
import com.player2.playerengine.util.helpers.ItemHelper;
import com.player2.playerengine.util.helpers.MaterialAvailability;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public final class CraftMacroSupport {
   private CraftMacroSupport() {
   }

   public static boolean isMacroEnabled(com.player2.playerengine.PlayerEngineController mod) {
      return mod.getModSettings().isFastCraftMacrosEnabled();
   }

   public static boolean isSupportedItem(Item item) {
      if (item == Items.CHEST || item == Items.CRAFTING_TABLE || item == Items.STICK) {
         return true;
      }
      if (Arrays.asList(ItemHelper.PLANKS).contains(item)) {
         return true;
      }
      return Arrays.asList(ItemHelper.WOOD_SIGN).contains(item);
   }

   public static boolean isSupportedTarget(ItemTarget target) {
      if (target.isCatalogueItem()) {
         String name = target.getCatalogueName();
         if (name.equals("sign") || name.equals("chest") || name.equals("crafting_table") || name.equals("stick") || name.equals("planks")) {
            return true;
         }
         return name.endsWith("_sign") || name.endsWith("_planks");
      }
      for (Item match : target.getMatches()) {
         if (isSupportedItem(match)) {
            return true;
         }
      }
      return false;
   }

   public static Optional<Item> resolveSingleOutputItem(PlayerEngineController mod, ItemTarget target) {
      if (target.getMatches().length == 1) {
         return Optional.of(target.getMatches()[0]);
      }
      if (target.isCatalogueItem()) {
         String name = target.getCatalogueName();
         if (name.equals("sign")) {
            // Bare "sign": pick the species deterministically from what is FUNDABLE right now (held wood
            // + minable logs in vicinity). An explicit "<wood>_sign" request does not reach this — its
            // fundability gate runs ONCE at task creation in CraftMacroTasks.tryCreateMacroTask
            // (adjustSignRequestForFunding), never on replan, so the species pin stays stable mid-run.
            return Optional.of(pickSignSpecies(mod).sign);
         }
         if (name.equals("planks")) {
            return Optional.of(Items.OAK_PLANKS);
         }
         if (name.equals("chest")) {
            return Optional.of(Items.CHEST);
         }
         if (name.equals("crafting_table")) {
            return Optional.of(Items.CRAFTING_TABLE);
         }
         if (name.equals("stick")) {
            return Optional.of(Items.STICK);
         }
         if (name.endsWith("_sign") && TaskCatalogue.taskExists(name)) {
            return Optional.of(TaskCatalogue.getItemMatches(name)[0]);
         }
         if (name.endsWith("_planks") && TaskCatalogue.taskExists(name)) {
            return Optional.of(TaskCatalogue.getItemMatches(name)[0]);
         }
      }
      return Optional.empty();
   }

   /** Plank demand of one sign craft (the recipe's six plank slots; the stick is funded separately). */
   public static final int SIGN_PLANK_NEED = 6;

   /**
    * Creation-time funding snapshot for ONE species: {@code heldScore} = held planks + 4 per held
    * log-equivalent (see {@link #heldWoodScore}); {@code mineableLogs} = reachable tracked log blocks
    * of this species within the cap-clamped gather radius (the SAME source-routing axis the COLLECT
    * stall guard uses, so "fundable" here is judged consistently with what a dispatched gather can
    * actually mine). A species is fundable iff {@code heldScore + 4*mineableLogs >= SIGN_PLANK_NEED}.
    */
   private record SpeciesFunding(ItemHelper.WoodItems wood, int heldScore, int mineableLogs) {
      int total() {
         return this.heldScore + 4 * this.mineableLogs;
      }

      boolean fundable() {
         return this.total() >= SIGN_PLANK_NEED;
      }
   }

   /**
    * Result of the creation-time explicit-sign funding gate: the (possibly substituted) request
    * target, plus the dual-audience substitution notice ({@code null} when honored unchanged).
    */
   public record SignRequestAdjustment(ItemTarget target, String notice) {
   }

   /**
    * Explicit-species funding gate (DESIGN.md S3 visible degradation, owner rule 4). Called ONCE from
    * {@code CraftMacroTasks.tryCreateMacroTask} at task creation — BEFORE the plan is built and the
    * species pin set, and never from {@code replan} — so a substitution can never re-roll mid-run.
    *
    * <p>A non-sign or bare-"sign" target passes through untouched (chest flow byte-behavior-identical;
    * bare "sign" is resolved by {@link #pickSignSpecies}). An explicit {@code <wood>_sign} request is
    * honored as-is when that species is fundable from FRESH held + vicinity state; when it is NOT
    * fundable but another species is, the request is substituted with the best fundable species and a
    * notice is returned for the player chat line + the model-visible completion feedback. When NO
    * species is fundable the request is honored unchanged so the macro's EXISTING bounded UNOBTAINABLE
    * termination reports the shortfall truthfully (no new self-stop path).
    */
   public static SignRequestAdjustment adjustSignRequestForFunding(PlayerEngineController mod, ItemTarget target) {
      Item requestedSign = explicitSignItem(target);
      if (requestedSign == null) {
         return new SignRequestAdjustment(target, null);
      }
      ItemHelper.WoodItems requested = woodForSign(requestedSign);
      if (requested == null) {
         return new SignRequestAdjustment(target, null);
      }
      List<SpeciesFunding> fundings = computeFundings(mod);
      SpeciesFunding requestedFunding = null;
      for (SpeciesFunding f : fundings) {
         if (f.wood() == requested) {
            requestedFunding = f;
            break;
         }
      }
      if (requestedFunding == null) {
         requestedFunding = fundingFor(mod, requested);
      }
      String reqName = ItemHelper.stripItemName(requestedSign);
      if (requestedFunding.fundable()) {
         Debug.logInternal("[[CRAFT-RESOLVER-DIAG]] SPECIES-PICK explicit=" + reqName + " "
               + describeFunding(requestedFunding) + " -> honored");
         return new SignRequestAdjustment(target, null);
      }
      SpeciesFunding best = selectBestFundable(mod, fundings);
      if (best == null || best.wood() == requested) {
         Debug.logInternal("[[CRAFT-RESOLVER-DIAG]] SPECIES-PICK explicit=" + reqName + " "
               + describeFunding(requestedFunding) + " -> NOT fundable; no fundable species, honoring"
               + " as-is (bounded terminate downstream) scores=" + describeScores(fundings));
         return new SignRequestAdjustment(target, null);
      }
      String subName = ItemHelper.stripItemName(best.wood().sign);
      Debug.logInternal("[[CRAFT-RESOLVER-DIAG]] SPECIES-PICK explicit=" + reqName + " "
            + describeFunding(requestedFunding) + " -> NOT fundable; substituted=" + subName
            + " scores=" + describeScores(fundings));
      String species = reqName.endsWith("_sign") ? reqName.substring(0, reqName.length() - "_sign".length()) : reqName;
      String notice = species + " wood not available nearby; making " + subName + " instead of " + reqName;
      return new SignRequestAdjustment(new ItemTarget(best.wood().sign, target.getTargetCount()), notice);
   }

   /**
    * Deterministically pick the wood species for a bare "sign" request from what is FUNDABLE at this
    * moment (owner rules 1+2): a fresh held-inventory + vicinity-minable search at sign-task creation,
    * never stale pre-chest state.
    *
    * <p>Tier 1 (held preference): among FUNDABLE species, one already in inventory whose own logs can
    * make up the {@value #SIGN_PLANK_NEED}-plank need — highest {@code heldScore} wins. Tier 2
    * (vicinity): the most available fundable species (highest total funding), ties broken by the
    * NEAREST tracked log (what a dispatched gather would mine first). No fundable species: best-effort
    * fallback to the highest partial score (OAK on all-zero) so the macro targets the least-bad
    * species and the EXISTING bounded UNOBTAINABLE termination reports truthfully.
    *
    * <p>Determinism: candidates iterate {@link WoodType#values()} with OAK moved first, NEVER the
    * {@code ItemHelper.getWoodItems()} collection whose HashMap iteration order is JVM-run-dependent;
    * selection replaces only on STRICT greater, so OAK wins every full tie across restarts.
    * Deterministic, zero model calls.
    */
   public static ItemHelper.WoodItems pickSignSpecies(PlayerEngineController mod) {
      List<SpeciesFunding> fundings = computeFundings(mod);
      SpeciesFunding best = selectBestFundable(mod, fundings);
      if (best != null) {
         Debug.logInternal("[[CRAFT-RESOLVER-DIAG]] SPECIES-PICK bare-sign chosen="
               + ItemHelper.stripItemName(best.wood().sign) + " " + describeFunding(best)
               + " scores=" + describeScores(fundings));
         return best.wood();
      }
      SpeciesFunding fallback = null;
      for (SpeciesFunding f : fundings) {
         if (f.total() > 0 && (fallback == null || f.total() > fallback.total())) {
            fallback = f;
         }
      }
      Debug.logInternal("[[CRAFT-RESOLVER-DIAG]] SPECIES-PICK bare-sign no fundable species; fallback="
            + (fallback != null ? ItemHelper.stripItemName(fallback.wood().sign) : "oak_sign(all-zero)")
            + " scores=" + describeScores(fundings));
      return fallback != null ? fallback.wood() : ItemHelper.getWoodItems(WoodType.OAK);
   }

   /**
    * Best fundable species, or {@code null} when none can fund the full plank need. Tier 1: fundable
    * AND already-held (highest heldScore). Tier 2: fundable from vicinity (highest total; nearest-log
    * tie-break). OAK-first list order + strict-greater replacement keeps full ties deterministic.
    */
   private static SpeciesFunding selectBestFundable(PlayerEngineController mod, List<SpeciesFunding> fundings) {
      SpeciesFunding bestHeld = null;
      for (SpeciesFunding f : fundings) {
         if (f.fundable() && f.heldScore() > 0 && (bestHeld == null || f.heldScore() > bestHeld.heldScore())) {
            bestHeld = f;
         }
      }
      if (bestHeld != null) {
         return bestHeld;
      }
      int bestTotal = 0;
      List<SpeciesFunding> tied = new ArrayList<>();
      for (SpeciesFunding f : fundings) {
         if (!f.fundable()) {
            continue;
         }
         if (f.total() > bestTotal) {
            bestTotal = f.total();
            tied.clear();
            tied.add(f);
         } else if (f.total() == bestTotal) {
            tied.add(f);
         }
      }
      if (tied.isEmpty()) {
         return null;
      }
      if (tied.size() == 1) {
         return tied.get(0);
      }
      return nearestLogSpecies(mod, tied);
   }

   /**
    * Tie-break by proximity: ONE multi-block nearest-block query over the tied species' log blocks,
    * mapped back to its species (what a dispatched gather would mine first). Falls back to the first
    * tied entry (OAK-first order) when the scanner has nothing.
    */
   private static SpeciesFunding nearestLogSpecies(PlayerEngineController mod, List<SpeciesFunding> tied) {
      if (mod.getWorld() == null) {
         return tied.get(0);
      }
      List<SpeciesFunding> candidates = new ArrayList<>();
      List<Block> logBlocks = new ArrayList<>();
      for (SpeciesFunding f : tied) {
         Item log = f.wood().log;
         if (log == null) {
            continue;
         }
         Block block = Block.byItem(log);
         if (block == Blocks.AIR) {
            continue;
         }
         candidates.add(f);
         logBlocks.add(block);
      }
      if (candidates.isEmpty()) {
         return tied.get(0);
      }
      Optional<BlockPos> nearest = mod.getBlockScanner().getNearestBlock(logBlocks.toArray(new Block[0]));
      if (nearest.isPresent()) {
         Block found = mod.getWorld().getBlockState(nearest.get()).getBlock();
         for (int i = 0; i < logBlocks.size(); i++) {
            if (logBlocks.get(i) == found) {
               return candidates.get(i);
            }
         }
      }
      return tied.get(0);
   }

   /** Funding snapshots for every sign-capable species, OAK first then enum order (tie determinism). */
   private static List<SpeciesFunding> computeFundings(PlayerEngineController mod) {
      List<SpeciesFunding> fundings = new ArrayList<>();
      ItemHelper.WoodItems oak = ItemHelper.getWoodItems(WoodType.OAK);
      if (oak != null && oak.sign != null) {
         fundings.add(fundingFor(mod, oak));
      }
      for (WoodType type : WoodType.values()) {
         ItemHelper.WoodItems w = ItemHelper.getWoodItems(type);
         if (w == null || w.sign == null || w == oak) {
            continue;
         }
         fundings.add(fundingFor(mod, w));
      }
      return fundings;
   }

   private static SpeciesFunding fundingFor(PlayerEngineController mod, ItemHelper.WoodItems w) {
      return new SpeciesFunding(w, heldWoodScore(mod, w), mineableLogCount(mod, w));
   }

   /**
    * Reachable tracked NATURAL log blocks of this species within the cap-clamped gather radius — the
    * same {@link MaterialAvailability} mineable-yield term (and the same radius clamp) the COLLECT
    * reachability guard uses, so "fundable from vicinity" and "the gather can reach it" never disagree.
    * Counts only {@code w.log} (the block a narrowed species gather targets), no stripped/wood
    * variants. Tracked positions are live-validated in loaded chunks (mined-away logs no longer
    * count). Cheap: one tracked-position map lookup per species plus per-position block-state
    * reads in loaded chunks, no chunk re-scan, no threads.
    */
   private static int mineableLogCount(PlayerEngineController mod, ItemHelper.WoodItems w) {
      if (w.log == null || mod.getPlayer() == null || mod.getWorld() == null) {
         return 0;
      }
      Vec3 origin = mod.getPlayer().position();
      double dropRadius = mod.getModSettings().getAggregateCountDropRadius();
      double localSourceBlockRadius = Math.min(
            mod.getModSettings().getAggregateLocalSourceBlockRadius(),
            mod.getModSettings().getAgenticMaxTravelRadius());
      return MaterialAvailability.count(mod, new ItemTarget(w.log, 1), origin, dropRadius, localSourceBlockRadius)
            .mineableYield();
   }

   /**
    * The concrete sign item of an EXPLICIT-species sign request ({@code oak_sign}), or {@code null}
    * for anything else: bare "sign" stays a species-pick request; non-sign targets pass through;
    * hanging signs fall out naturally (their items are not in {@code WOOD_SIGN} and
    * {@link #woodForSign} matches {@code w.sign} only).
    */
   private static Item explicitSignItem(ItemTarget target) {
      if (target.isCatalogueItem() && target.getCatalogueName().equals("sign")) {
         return null;
      }
      if (target.getMatches().length == 1 && Arrays.asList(ItemHelper.WOOD_SIGN).contains(target.getMatches()[0])) {
         return target.getMatches()[0];
      }
      if (target.isCatalogueItem()) {
         String name = target.getCatalogueName();
         if (name.endsWith("_sign") && TaskCatalogue.taskExists(name)) {
            Item[] matches = TaskCatalogue.getItemMatches(name);
            if (matches != null && matches.length >= 1 && Arrays.asList(ItemHelper.WOOD_SIGN).contains(matches[0])) {
               return matches[0];
            }
         }
      }
      return null;
   }

   /** The {@link WoodType} family whose STANDARD sign is {@code sign}, or {@code null}. */
   private static ItemHelper.WoodItems woodForSign(Item sign) {
      for (WoodType type : WoodType.values()) {
         ItemHelper.WoodItems w = ItemHelper.getWoodItems(type);
         if (w != null && w.sign == sign) {
            return w;
         }
      }
      return null;
   }

   /** One species' funding arithmetic for the SPECIES-PICK diag line. */
   private static String describeFunding(SpeciesFunding f) {
      return "(held=" + f.heldScore() + " mineableLogs=" + f.mineableLogs()
            + " total=" + f.total() + " need=" + SIGN_PLANK_NEED + ")";
   }

   /** Compact nonzero score table for the SPECIES-PICK diag line. */
   private static String describeScores(List<SpeciesFunding> fundings) {
      StringBuilder sb = new StringBuilder("[");
      for (SpeciesFunding f : fundings) {
         if (f.total() <= 0) {
            continue;
         }
         if (sb.length() > 1) {
            sb.append(", ");
         }
         sb.append(ItemHelper.stripItemName(f.wood().sign))
               .append(":h").append(f.heldScore())
               .append("+4*m").append(f.mineableLogs())
               .append("=").append(f.total());
      }
      if (sb.length() == 1) {
         sb.append("all-zero");
      }
      return sb.append("]").toString();
   }

   /** Held-wood score for one species: planks + 4 per log-equivalent (each crafts into 4 planks). */
   private static int heldWoodScore(PlayerEngineController mod, ItemHelper.WoodItems w) {
      int logEquivalents = heldCount(mod, w.log)
            + heldCount(mod, w.strippedLog)
            + heldCount(mod, w.wood)
            + heldCount(mod, w.strippedWood);
      return heldCount(mod, w.planks) + 4 * logEquivalents;
   }

   /** Null-safe held count (some {@code WoodItems} fields are null for partial families, e.g. bamboo). */
   private static int heldCount(PlayerEngineController mod, Item item) {
      return (item == null) ? 0 : mod.getItemStorage().getItemCount(item);
   }
}
