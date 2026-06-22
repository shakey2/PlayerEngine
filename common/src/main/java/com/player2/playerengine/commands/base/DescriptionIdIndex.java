package com.player2.playerengine.commands.base;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * Registry-backed reverse index for translating the strings a model is likely to echo back from its
 * own context (translation/lang keys like {@code block.iceandfire.podium_oak}, or bare modded item
 * paths like {@code podium_oak}) into the canonical registry id ({@code iceandfire:podium_oak}) that
 * the {@code @get} existence gate ({@link ItemList#resolveItem}) actually accepts.
 *
 * <p>Background (the modded-podium give-up bug): when the model asked for a modded item it tried the
 * lang key it saw in its inventory snapshot ({@code block.iceandfire.podium_oak}) and a bare name
 * ({@code podium_oak}). Neither resolved, and the vanilla-only fuzzy fallback returned absurd
 * suggestions ("baked_potato", "diamond"), convincing the model the item could not exist. This index
 * makes those two human/model-natural input forms resolve to the real registry id, transparently.
 *
 * <h2>Why a reverse map (not string surgery)</h2>
 * {@link Item#getDescriptionId()} is the AUTHORITATIVE lang key, honouring any mod override. Naively
 * rewriting {@code block.<ns>.<path>} → {@code <ns>:<path>} is fragile (namespace-vs-path dot
 * ambiguity, {@code '/'}→{@code '.'} path substitution in {@code Util.makeDescriptionId}, custom
 * overridden keys). Instead we build the map straight from each registered item's live
 * {@code getDescriptionId()} → its registry key, so the mapping is exact and convention-proof.
 *
 * <h2>Lifecycle</h2>
 * Lazily built once on first use from {@link BuiltInRegistries#ITEM}, which is frozen long before any
 * command runs — no world-join hook needed and no second {@code RecipeManager} scan. Idempotent and
 * thread-safe via a volatile double-checked publish.
 */
public final class DescriptionIdIndex {
   private DescriptionIdIndex() {}

   /** lang key (Item#getDescriptionId) -> registry id string ("ns:path"). Exact, convention-proof. */
   private static volatile Map<String, String> descriptionIdToRegistryId = null;

   /** bare modded path (e.g. "podium_oak") -> registry id; only populated when the path is UNIQUE. */
   private static volatile Map<String, String> uniqueModdedPathToRegistryId = null;

   /**
    * namespace -> list of ALL registered paths in that namespace (e.g. "irons_spellbooks" ->
    * ["wizard_chestplate", "wizard_helmet", ...]). Populated inside the same single registry scan
    * as the other maps, published before the double-checked flag. Used by
    * {@link #candidatesInNamespace} to suggest same-namespace registry ids on a namespaced miss
    * without an extra registry scan at rejection time.
    */
   private static volatile Map<String, List<String>> namespaceToPaths = null;

   private static void ensureBuilt() {
      if (descriptionIdToRegistryId != null) {
         return;
      }
      synchronized (DescriptionIdIndex.class) {
         if (descriptionIdToRegistryId != null) {
            return;
         }
         Map<String, String> descMap = new HashMap<>();
         Map<String, String> pathMap = new HashMap<>();
         Set<String> ambiguousPaths = new HashSet<>();
         Map<String, List<String>> nsMap = new HashMap<>();
         for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
            if (key == null) {
               continue;
            }
            String registryId = key.toString();
            // Lang-key reverse entry (covers "block.iceandfire.podium_oak" -> "iceandfire:podium_oak").
            String descId = item.getDescriptionId();
            if (descId != null && !descId.isEmpty()) {
               // First write wins on the rare duplicate lang key; the registry id is the truth either way.
               descMap.putIfAbsent(descId, registryId);
            }
            // Bare-path reverse entry for MODDED items only. A vanilla bare name already resolves via
            // the minecraft: prepend in ItemList.resolveItem, and letting vanilla paths in here could
            // mask a vanilla item; so restrict to non-minecraft namespaces. Track collisions so an
            // ambiguous bare path (same path in two mods) is never silently mis-resolved.
            if (!"minecraft".equals(key.getNamespace())) {
               String path = key.getPath();
               if (ambiguousPaths.contains(path)) {
                  // already known ambiguous
               } else if (pathMap.containsKey(path) && !pathMap.get(path).equals(registryId)) {
                  pathMap.remove(path);
                  ambiguousPaths.add(path);
               } else {
                  pathMap.put(path, registryId);
               }
            }
            // Namespace-to-paths grouping: collect ALL registered paths per namespace (incl. minecraft).
            // Used at rejection time to surface same-namespace candidates without a second registry scan.
            nsMap.computeIfAbsent(key.getNamespace(), k -> new ArrayList<>()).add(key.getPath());
         }
         // Freeze each per-namespace list.
         Map<String, List<String>> frozenNsMap = new HashMap<>(nsMap.size() * 2);
         for (Map.Entry<String, List<String>> e : nsMap.entrySet()) {
            frozenNsMap.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
         }
         namespaceToPaths = Collections.unmodifiableMap(frozenNsMap);
         uniqueModdedPathToRegistryId = Collections.unmodifiableMap(pathMap);
         // publish descMap LAST: it is the flag the double-checked read tests.
         descriptionIdToRegistryId = Collections.unmodifiableMap(descMap);
      }
   }

   /**
    * Resolve a token that may be a lang key, a bare modded path, or already a registry id, to its
    * canonical registry id string. Returns {@code null} when nothing in the index matches.
    *
    * <ol>
    *   <li>Exact lang-key match (e.g. {@code block.iceandfire.podium_oak}).</li>
    *   <li>{@code block.}/{@code item.} prefix fast-path: strip the prefix and re-test as a lang key
    *       (covers prefixed inputs whose remainder is itself a key fragment).</li>
    *   <li>Unique modded bare-path match (e.g. {@code podium_oak} when only one mod registers it).</li>
    * </ol>
    *
    * @param token the user/model-supplied token (case preserved by the caller; lang keys are lowercase
    *              by MC convention so callers should not upper-case)
    * @return the canonical "ns:path" registry id, or {@code null} if unmatched
    */
   public static String resolveToRegistryId(String token) {
      if (token == null || token.isEmpty()) {
         return null;
      }
      ensureBuilt();
      String exact = descriptionIdToRegistryId.get(token);
      if (exact != null) {
         return exact;
      }
      // block./item. prefix fast-path: never the authority, only a convenience before the path map.
      // The exact-match at the top of this method already tried the token verbatim, so re-querying the
      // SAME prefix (e.g. "block." + stripped when the token began with "block.") is a guaranteed miss.
      // Only the OPPOSITE prefix can yield a new hit: an item registered under "item.<ns>.<path>" whose
      // model echoed it as "block.<ns>.<path>" (or vice-versa). Try only that opposite prefix.
      if (token.startsWith("block.")) {
         String stripped = token.substring(6);
         String viaStrip = descriptionIdToRegistryId.get("item." + stripped);
         if (viaStrip != null) {
            return viaStrip;
         }
      } else if (token.startsWith("item.")) {
         String stripped = token.substring(5);
         String viaStrip = descriptionIdToRegistryId.get("block." + stripped);
         if (viaStrip != null) {
            return viaStrip;
         }
      }
      // Bare modded path (unique only — never guess on an ambiguous cross-mod path).
      String viaPath = uniqueModdedPathToRegistryId.get(token);
      if (viaPath != null) {
         return viaPath;
      }
      return null;
   }

   /** True when the token looks like a translation/lang key ({@code block.x.y} / {@code item.x.y}). */
   public static boolean looksLikeLangKey(String token) {
      return token != null && (token.startsWith("block.") || token.startsWith("item.")) && token.indexOf('.', 6) > 0;
   }

   /**
    * Returns up to {@code limit} real {@code "ns:path"} registry ids in {@code namespace} whose path
    * shares a stem token or substring with {@code typedPath}, ranked by relevance:
    * <ol>
    *   <li>Most shared underscore-split stem tokens with {@code typedPath} (descending).</li>
    *   <li>Path starts with the same prefix as {@code typedPath} up to the first {@code _}
    *       (or the full path when there is no {@code _}).</li>
    *   <li>Substring containment of any stem token from {@code typedPath}.</li>
    *   <li>Edit-distance tiebreak via
    *       {@link FuzzySearchHelper#getClosestMatchWithinThreshold} applied to the already
    *       namespace-filtered set (secondary, never the primary signal).</li>
    * </ol>
    *
    * <p>Returns an empty list when the namespace is absent from the registry (mod not installed) or
    * when no path in the namespace shares any stem token or substring with {@code typedPath}. In
    * that case the caller should leave the existing rejection message unchanged.
    *
    * <p>This method reads only the frozen {@link #namespaceToPaths} map populated by
    * {@link #ensureBuilt()} — no additional registry scan occurs at rejection time.
    *
    * @param namespace  the namespace extracted from the typed {@code ns:path} token
    * @param typedPath  the path portion of the typed token (may be wrong / unregistered)
    * @param limit      maximum number of candidates to return (plan specifies ~8)
    * @return an immutable list of at most {@code limit} registry id strings, ranked by relevance
    */
   public static List<String> candidatesInNamespace(String namespace, String typedPath, int limit) {
      if (namespace == null || namespace.isEmpty() || typedPath == null || limit <= 0) {
         return Collections.emptyList();
      }
      ensureBuilt();
      List<String> paths = namespaceToPaths.get(namespace.toLowerCase(Locale.ROOT));
      if (paths == null || paths.isEmpty()) {
         // Namespace not present in the registry — mod not installed.
         return Collections.emptyList();
      }
      String lowerTyped = typedPath.toLowerCase(Locale.ROOT);
      Set<String> typedStems = stemTokens(lowerTyped);

      // Score each registered path in the namespace.
      List<ScoredPath> scored = new ArrayList<>(paths.size());
      for (String path : paths) {
         String lowerPath = path.toLowerCase(Locale.ROOT);
         // Skip the exact typed path (it was already rejected as unregistered, never suggest it).
         if (lowerPath.equals(lowerTyped)) {
            continue;
         }
         Set<String> pathStems = stemTokens(lowerPath);
         int sharedCount = sharedStemCount(typedStems, pathStems);
         if (sharedCount == 0) {
            // No shared stem tokens — check substring containment of any typed stem token.
            boolean substringMatch = false;
            for (String stem : typedStems) {
               if (lowerPath.contains(stem)) {
                  substringMatch = true;
                  break;
               }
            }
            if (!substringMatch) {
               // No overlap at all — skip.
               continue;
            }
         }
         boolean prefixMatch = hasSamePrimaryPrefix(lowerTyped, lowerPath);
         scored.add(new ScoredPath(path, sharedCount, prefixMatch));
      }

      if (scored.isEmpty()) {
         return Collections.emptyList();
      }

      // Sort: most shared stems first, then prefix match, then by edit-distance as tiebreak.
      // Edit-distance tiebreak is computed inline (private helper below) on the already
      // namespace-filtered set, consistent with the plan's "FuzzySearchHelper as secondary"
      // principle — it gates no candidates, only orders equally-primary-scored ones.
      final String normalizedTyped = lowerTyped;
      scored.sort(Comparator
            .<ScoredPath, Integer>comparing(sp -> sp.sharedStems, Comparator.reverseOrder())
            .thenComparingInt(sp -> sp.prefixMatch ? 0 : 1)
            .thenComparingInt(sp -> simpleEditDistance(normalizedTyped, sp.path.toLowerCase(Locale.ROOT))));

      // Cap to limit and format as "ns:path".
      int resultSize = Math.min(limit, scored.size());
      List<String> result = new ArrayList<>(resultSize);
      for (int i = 0; i < resultSize; i++) {
         result.add(namespace + ":" + scored.get(i).path);
      }
      return Collections.unmodifiableList(result);
   }

   /**
    * Splits {@code path} on {@code '_'} and returns the set of non-empty tokens (stem set).
    * When there is no {@code '_'}, returns a singleton set containing {@code path} itself.
    * This is the primary signal for same-namespace candidate ranking.
    */
   private static Set<String> stemTokens(String path) {
      if (path.indexOf('_') < 0) {
         return Collections.singleton(path);
      }
      String[] parts = path.split("_", -1);
      Set<String> stems = new HashSet<>(parts.length * 2);
      for (String p : parts) {
         if (!p.isEmpty()) {
            stems.add(p);
         }
      }
      return stems.isEmpty() ? Collections.singleton(path) : stems;
   }

   /** Returns the number of stem tokens {@code a} and {@code b} have in common. */
   private static int sharedStemCount(Set<String> a, Set<String> b) {
      int count = 0;
      for (String s : a) {
         if (b.contains(s)) {
            count++;
         }
      }
      return count;
   }

   /**
    * Returns {@code true} when {@code path} starts with the same "primary prefix" as
    * {@code typed} — defined as the substring up to (but not including) the first {@code '_'},
    * or the full string when there is no {@code '_'}. This ranks armor siblings like
    * {@code wizard_chestplate} above unrelated single-token {@code wizard_*} ids.
    */
   private static boolean hasSamePrimaryPrefix(String typed, String path) {
      int idx = typed.indexOf('_');
      String prefix = idx >= 0 ? typed.substring(0, idx) : typed;
      return !prefix.isEmpty() && path.startsWith(prefix);
   }

   /**
    * Minimal Levenshtein edit-distance used only as a tiebreak comparator in
    * {@link #candidatesInNamespace}. Not exposed publicly — the authoritative
    * fuzzy-search helper is {@link FuzzySearchHelper#getClosestMatchWithinThreshold}.
    * Uses O(min(a,b)) space.
    */
   private static int simpleEditDistance(String a, String b) {
      int m = a.length(), n = b.length();
      if (m == 0) return n;
      if (n == 0) return m;
      // Ensure a is the shorter string to minimise memory usage.
      if (m > n) { String tmp = a; a = b; b = tmp; int t = m; m = n; n = t; }
      int[] prev = new int[m + 1];
      int[] curr = new int[m + 1];
      for (int i = 0; i <= m; i++) prev[i] = i;
      for (int j = 1; j <= n; j++) {
         curr[0] = j;
         for (int i = 1; i <= m; i++) {
            int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
            curr[i] = Math.min(Math.min(curr[i - 1] + 1, prev[i] + 1), prev[i - 1] + cost);
         }
         int[] swap = prev; prev = curr; curr = swap;
      }
      return prev[m];
   }

   /** Simple holder for a candidate path and its ranking scores. */
   private static final class ScoredPath {
      final String path;
      final int sharedStems;
      final boolean prefixMatch;

      ScoredPath(String path, int sharedStems, boolean prefixMatch) {
         this.path = path;
         this.sharedStems = sharedStems;
         this.prefixMatch = prefixMatch;
      }
   }
}
