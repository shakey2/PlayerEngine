package com.player2.playerengine.commands.base;

import com.player2.playerengine.TaskCatalogue;
import com.player2.playerengine.modintelligence.ModIntelligenceService;
import com.player2.playerengine.modintelligence.capability.CapabilityMap;
import com.player2.playerengine.modintelligence.capability.CapabilitySubjectKind;
import com.player2.playerengine.modintelligence.capability.EnrichmentSummary;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.helpers.FuzzySearchHelper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public class ItemList {
   public ItemTarget[] items;

   /**
    * Curated recipe-output name corpus built at world-join by
    * {@link #updateCraftingCorpus(Set)}. Used to generate targeted "did you mean" suggestions
    * for unknown item names — a tighter set than all of {@code BuiltInRegistries.ITEM}. Never
    * null; populated before any command is run in-game. Volatile for safe single-flag read.
    */
   private static volatile Set<String> craftingOutputCorpus = Collections.emptySet();

   public ItemList(ItemTarget[] items) {
      this.items = items;
   }

   /**
    * Replaces the curated crafting-output corpus with the display-id names derived from the
    * given item set. Called from {@code CraftingRecipeTracker.updateState()} after the tracker
    * rebuilds its recipe map, reusing that world-join seam so no second
    * {@code RecipeManager} scan is needed.
    *
    * <p>Each item is rendered via the same display-id convention used in
    * {@code StorageItemArgs}: vanilla ids with the {@code minecraft:} prefix stripped, modded
    * ids with namespace kept.
    *
    * @param recipeOutputItems the set of items that are outputs of known crafting recipes
    */
   public static void updateCraftingCorpus(Set<Item> recipeOutputItems) {
      if (recipeOutputItems == null || recipeOutputItems.isEmpty()) {
         return;
      }
      Set<String> corpus = new HashSet<>(recipeOutputItems.size() * 2);
      for (Item item : recipeOutputItems) {
         ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
         if (key == null) {
            continue;
         }
         if ("minecraft".equals(key.getNamespace())) {
            corpus.add(key.getPath());
         } else {
            corpus.add(key.toString());
         }
      }
      craftingOutputCorpus = Collections.unmodifiableSet(corpus);
   }

   /**
    * Build the full suggestion corpus for "did you mean" messages: the curated crafting-output
    * corpus (built at world-join) unioned with the {@code TaskCatalogue} resource names (for
    * backward compat with smelt/smith/gather-only items that have catalogue entries but no
    * crafting recipe).
    */
   private static Collection<String> buildSuggestionCorpus() {
      Collection<String> catalogueNames = TaskCatalogue.resourceNames();
      Set<String> corpus = craftingOutputCorpus;
      if (corpus.isEmpty()) {
         return catalogueNames;
      }
      Set<String> combined = new HashSet<>(corpus.size() + catalogueNames.size());
      combined.addAll(corpus);
      combined.addAll(catalogueNames);
      return combined;
   }

   /**
    * Resolve an item name token against {@code BuiltInRegistries.ITEM}.
    * Follows the {@code StorageItemArgs.resolveItem} pattern: prepend {@code minecraft:} when
    * no namespace is present, then {@link ResourceLocation#tryParse}, then
    * {@code BuiltInRegistries.ITEM.get(id)} with an AIR-sentinel check.
    *
    * @return the resolved {@link Item}, or {@code null} if the name is invalid or resolves to AIR
    */
   private static Item resolveItem(String token) {
      String idText = token.contains(":") ? token : "minecraft:" + token;
      ResourceLocation id = ResourceLocation.tryParse(idText.toLowerCase(Locale.ROOT));
      if (id != null) {
         Item item = BuiltInRegistries.ITEM.get(id);
         // AIR sentinel: get() returns AIR for unknown ids; treat as invalid (do not allow "air").
         if (item != Items.AIR) {
            return item;
         }
      }
      // Robustness net: the model frequently echoes a lang key (block.iceandfire.podium_oak) or a
      // bare modded path (podium_oak) it saw in its own context. Translate via the registry-backed
      // reverse index to the real registry id, then resolve that. Convention-proof, exact-match.
      String mapped = DescriptionIdIndex.resolveToRegistryId(token.toLowerCase(Locale.ROOT));
      if (mapped != null) {
         ResourceLocation mappedId = ResourceLocation.tryParse(mapped);
         if (mappedId != null) {
            Item mappedItem = BuiltInRegistries.ITEM.get(mappedId);
            if (mappedItem != Items.AIR) {
               return mappedItem;
            }
         }
      }
      return null;
   }

   /**
    * Attempt a synchronous ModIntelligence alias lookup for the given registry-id token. This is
    * OPTIONAL: if ModIntelligence has no entry, or if enrichment has no aliases, the method returns
    * {@code null} and the caller falls through to the plain Damerau-Levenshtein suggestion.
    *
    * <p>The lookup is a single {@link java.util.HashMap} read — it can never block, throw, or gate
    * any item (DESIGN.md §3 / plan invariant: ModIntelligence is suggestion-only).
    *
    * @param token the original user-supplied token (namespace automatically prepended if missing)
    * @return a non-blank hint string that may include the registry id and its aliases, or
    *         {@code null} when ModIntelligence has nothing useful
    */
   private static String queryModIntelligenceAlias(String token) {
      try {
         // Prefer the registry-backed reverse index so a bare modded path ("podium_oak") or a lang key
         // ("block.iceandfire.podium_oak") is keyed by its REAL id ("iceandfire:podium_oak") rather than
         // the unreachable "minecraft:podium_oak" the naive prepend produced (D3 dead-path fix). Only
         // fall back to the minecraft: prepend when the reverse index has nothing.
         String mapped = DescriptionIdIndex.resolveToRegistryId(token.toLowerCase(Locale.ROOT));
         String idText = mapped != null
               ? mapped
               : (token.contains(":") ? token : "minecraft:" + token);
         ResourceLocation id = ResourceLocation.tryParse(idText.toLowerCase(Locale.ROOT));
         if (id == null) {
            return null;
         }
         Optional<CapabilityMap> mapOpt = ModIntelligenceService.queryService()
               .get(CapabilitySubjectKind.ITEM, id.toString());
         if (mapOpt.isEmpty()) {
            return null;
         }
         EnrichmentSummary enrichment = mapOpt.get().getEnrichment();
         if (enrichment == null) {
            return null;
         }
         List<String> aliases = enrichment.getAliases();
         if (aliases == null || aliases.isEmpty()) {
            return null;
         }
         // Build a hint that includes the full registry id and its known aliases.
         StringBuilder sb = new StringBuilder(id.toString());
         sb.append(" (also known as: ");
         for (int i = 0; i < aliases.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(aliases.get(i));
         }
         sb.append(")");
         return sb.toString();
      } catch (Exception ignored) {
         // Never propagate — alias enrichment is suggestion-only; any failure is silent.
         return null;
      }
   }

   /**
    * Build a TRUTHFUL, audience-actionable rejection for an item token that did not resolve to a
    * registered item (after the reverse-index fallback in {@link #resolveItem} already failed). This
    * replaces the old "Did the user mean &lt;absurd vanilla item&gt;?" message that — for a modded or
    * lang-key input — returned a maximally-wrong fuzzy match (e.g. "baked_potato", "diamond") and
    * convinced the model the item could not exist, causing give-up (DESIGN.md §3 truthfulness).
    *
    * <p>The message is tailored to the SHAPE of the bad token:
    * <ul>
    *   <li><b>Lang key</b> ({@code block.x.y} / {@code item.x.y}) — tell the model it supplied a
    *       translation key, not a registry id, and how to obtain the real id.</li>
    *   <li><b>Namespaced miss</b> ({@code ns:path}, valid syntax, unregistered) — the namespace/path
    *       is wrong or the mod isn't present; do NOT offer an unrelated vanilla fuzzy suggestion.</li>
    *   <li><b>Bare name</b> (no {@code :}, no dot) — a vanilla-name typo is plausible, so a fuzzy
    *       suggestion is offered, but only when it is genuinely close (edit-distance gated).</li>
    * </ul>
    * In every case an alias hint from ModIntelligence is appended when one is available.
    */
   private static String rejectionFor(String token) {
      String aliasHint = queryModIntelligenceAlias(token);
      // queryModIntelligenceAlias already returns a formatted hint (id + "(also known as: ...)").
      String aliasSuffix = aliasHint != null ? " Related: " + aliasHint + "." : "";

      if (DescriptionIdIndex.looksLikeLangKey(token)) {
         return "Item does not exist: \"" + token + "\". That looks like a translation/lang key, not a"
               + " registry id. Use the full registry id in the form namespace:path (e.g."
               + " iceandfire:podium_oak). Find it via `inspect ITEM <id>` or an advanced tooltip"
               + " (F3+H)." + aliasSuffix;
      }
      if (token.contains(":")) {
         // Valid namespace:path syntax but unregistered — wrong id or the mod is absent. A vanilla
         // fuzzy suggestion here is actively misleading, so we suppress it.
         // Instead, surface real registry ids from the same namespace that share stem tokens with
         // the typed path (deterministic, on-device — no model call, no deepsearch).
         String candidateSuffix = "";
         ResourceLocation parsedId = ResourceLocation.tryParse(token.toLowerCase(Locale.ROOT));
         if (parsedId != null) {
            List<String> candidates = DescriptionIdIndex.candidatesInNamespace(
                  parsedId.getNamespace(), parsedId.getPath(), 8);
            if (!candidates.isEmpty()) {
               StringBuilder sb = new StringBuilder(" Did you mean one of: ");
               for (int i = 0; i < candidates.size(); i++) {
                  if (i > 0) sb.append(", ");
                  sb.append(candidates.get(i));
               }
               sb.append("?");
               candidateSuffix = sb.toString();
            }
         }
         return "Item does not exist: \"" + token + "\". No item with that registry id is registered —"
               + " check the namespace and path, or the mod may not be installed." + candidateSuffix + aliasSuffix;
      }
      // Bare name: a vanilla typo is plausible. Offer a fuzzy suggestion ONLY when it is genuinely
      // close (distance-gated) so we never emit an absurd "did you mean" for an unrelated item.
      String closestMatch = FuzzySearchHelper.getClosestMatchWithinThreshold(token, buildSuggestionCorpus());
      // Never suggest a token that resolveItem itself rejects (and never suggest the failing token back
      // to itself). buildSuggestionCorpus() unions the crafting-output corpus with TaskCatalogue resource
      // names, which CONTAINS generic catalogue aliases like "sign" that have no single registry item; an
      // unfiltered closest-match would echo "sign" (distance 0) as a fix for the very "sign" that just
      // failed. Suppress any suggestion that equals the token or that does not resolve to a real item.
      if (closestMatch != null
            && !closestMatch.equalsIgnoreCase(token)
            && resolveItem(closestMatch) != null) {
         return "Item does not exist: \"" + token + "\". Did you mean \"" + closestMatch + "\"?" + aliasSuffix;
      }
      // The closest match may be a generic catalogue alias (e.g. "planks", "chest", "boat") that
      // resolveItem rightly rejects (no single registry item) but that the TaskCatalogue still routes.
      // Without this, a genuine typo of such a name (e.g. "palnks" -> "planks") would get no hint. We
      // still require closestMatch != token, so the self-referential "sign" -> "sign" case stays suppressed.
      if (closestMatch != null
            && !closestMatch.equalsIgnoreCase(token)
            && TaskCatalogue.taskExists(closestMatch)) {
         return "Item does not exist: \"" + token + "\". Did you mean the generic name \"" + closestMatch + "\"?" + aliasSuffix;
      }
      return "Item does not exist: \"" + token + "\". If it is a modded item, use its full registry id"
            + " (namespace:path), e.g. iceandfire:podium_oak." + aliasSuffix;
   }

   public static ItemList parseRemainder(String line) throws CommandException {
      line = line.trim();
      if (line.startsWith("[") && line.endsWith("]")) {
         line = line.substring(1, line.length() - 1);
         String[] parts = line.split(",");
         // Use LinkedHashMap<Item, Integer> for dedup by Item identity (same registry item
         // regardless of name form) while preserving first-occurrence order.
         Map<Item, Integer> items = new LinkedHashMap<>();
         // Generic catalogue-resource names (e.g. "sign", "planks") have no single registry item, so
         // they cannot live in the Item-keyed dedup map above; collect them as catalogue-name targets
         // (dedup by name) so the catalogue dispatches the right task downstream.
         Map<String, Integer> catalogueItems = new LinkedHashMap<>();

         for (String part : parts) {
            part = part.trim();
            String[] itemQuantityPair = part.split(" ");
            if (itemQuantityPair.length > 2 || itemQuantityPair.length <= 0) {
               throw new CommandException(
                  "Resource array element must be either \"item count\" or \"item\", but \"" + part + "\" has " + itemQuantityPair.length + " parts."
               );
            }

            String nameToken = itemQuantityPair[0];
            int count = 1;
            if (itemQuantityPair.length > 1) {
               try {
                  count = Integer.parseInt(itemQuantityPair[1]);
               } catch (Exception var13) {
                  throw new CommandException("Failed to parse count for array element \"" + part + "\".");
               }
            }

            Item item = resolveItem(nameToken);
            if (item == null) {
               // Catalogue-alias fallback (mirrors the single-token branch below): route a generic
               // catalogue resource name (e.g. "sign") as a catalogue-name target instead of rejecting.
               if (TaskCatalogue.taskExists(nameToken)) {
                  catalogueItems.merge(nameToken, count, Integer::sum);
                  continue;
               }
               throw new CommandException(rejectionFor(nameToken));
            }

            items.merge(item, count, Integer::sum);
         }

         if (!items.isEmpty() || !catalogueItems.isEmpty()) {
            List<ItemTarget> targets = new ArrayList<>(items.size() + catalogueItems.size());
            for (Map.Entry<Item, Integer> entry : items.entrySet()) {
               targets.add(new ItemTarget(entry.getKey(), entry.getValue()));
            }
            for (Map.Entry<String, Integer> entry : catalogueItems.entrySet()) {
               targets.add(new ItemTarget(entry.getKey(), entry.getValue()));
            }
            return new ItemList(targets.toArray(new ItemTarget[0]));
         }
      } else {
         String[] items = line.split(" ");
         if (items.length >= 1) {
            String name = items[0];

            int countx = 1;
            if (items.length == 2) {
               try {
                  countx = Integer.parseInt(items[1]);
               } catch (NumberFormatException var12) {
                  throw new CommandException("Failed to parse the following argument into type " + Integer.class + ": " + items[1] + ".");
               }
            } else if (items.length > 2) {
               throw new CommandException("Invalid item argument structure: Must be of form `<item>` or `<item> <count>`");
            }

            Item item = resolveItem(name);
            if (item == null) {
               // Catalogue-alias fallback: a generic catalogue resource name (e.g. "sign", "planks",
               // "boat") has no single registry item (there is no minecraft:sign) so resolveItem returns
               // null — but the TaskCatalogue maps it to a concrete gather/craft task. Route it as a
               // catalogue-name ItemTarget so GetCommand/TaskCatalogue.getItemTask dispatches the right
               // task (e.g. CollectSignTask) instead of rejecting a valid generic name.
               if (TaskCatalogue.taskExists(name)) {
                  return new ItemList(new ItemTarget[]{new ItemTarget(name, countx)});
               }
               throw new CommandException(rejectionFor(name));
            }

            return new ItemList(new ItemTarget[]{new ItemTarget(item, countx)});
         }
      }

      return new ItemList(new ItemTarget[0]);
   }
}
