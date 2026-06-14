package com.player2.playerengine.containeraccess;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * The registry-first item grammar parser shared by {@code scan_storage} (targeted),
 * {@code withdraw_from_storage} and {@code deposit_to_storage} (Decision 11 — deliberately NOT
 * the legacy bracket-list arg parser or the task-catalogue lookup, which cannot deliver the
 * multi-item bare grammar, throw during arg parsing before {@code call()} runs, reject
 * uncatalogued registry ids, and default omitted counts to 1 instead of all-available).
 *
 * <pre>
 * items     := entry ("," entry)*
 * entry     := item_id | item_id count
 * item_id   := [namespace ":"] path        (namespace defaults to "minecraft")
 * count     := integer 1..3456             (3456 = 54 slots x 64; above -> invalid_count)
 * omitted count = ALL sentinel: withdraw/deposit -> all available; targeted scan -> report total
 * </pre>
 *
 * <p><b>Round-trip invariant:</b> every id printed by {@link ScanReportFormatter} (vanilla ids
 * with the {@code minecraft:} prefix stripped, modded ids with namespace kept) parses back
 * through this grammar to the same {@code Item} — {@link #displayIdFor(Item)} is the single
 * source of that rendering for both sides.
 */
public final class StorageItemArgs {

    /** Sentinel for an omitted count: "as many as possible" (best-effort, per entry). Matches
     * the {@code requested == -1} convention of {@link ScanReportFormatter.EntryOutcome}. */
    public static final int COUNT_ALL = -1;

    /** Hard count ceiling: 54 slots x 64 (the largest supported container view, full). */
    public static final int MAX_COUNT = 3456;

    private StorageItemArgs() {
    }

    /**
     * One parsed item request.
     *
     * @param item       the resolved registry item (never AIR)
     * @param displayId  the canonical display id ({@code minecraft:} stripped, modded namespace
     *                   kept) — use this in every AI-visible string, never the raw input token
     * @param countOrAll explicit count {@code 1..3456}, or {@link #COUNT_ALL}
     */
    public record ItemQuery(Item item, String displayId, int countOrAll) {

        public boolean isAll() {
            return this.countOrAll == COUNT_ALL;
        }
    }

    /**
     * One typed parse failure ({@code invalid_item_name} / {@code invalid_count} /
     * {@code invalid_argument}) naming the offending token. The command converts these to a
     * single {@code finishWithError} enumerating EVERY failure (multi-entry semantics, parse
     * phase) — never a {@code CommandException}.
     */
    public record ParseFailure(StorageAccessCode code, String token, String detail) {
    }

    /**
     * Parse outcome: either the merged, command-ordered queries (failures empty) or every
     * failing entry (queries empty). Never both.
     */
    public record ParseResult(List<ItemQuery> queries, List<ParseFailure> failures) {

        public boolean ok() {
            return this.failures.isEmpty();
        }
    }

    /**
     * Parses the raw arg-unit tail of a command line.
     *
     * <p><b>Tokenization rule (Decision 11, load-bearing):</b>
     * {@code ArgParser.splitLineIntoKeywords} splits on spaces, so the tail of
     * {@code ... iron_ingot 60, oak_planks} arrives as units
     * {@code ["iron_ingot","60,","oak_planks"]} with the comma glued to the count. This method
     * therefore first JOINS the tail units with single spaces, then splits on {@code ","}, and
     * only then tokenizes each entry. Never parse per-unit — {@code "60,"} would become a
     * spurious {@code invalid_count}.
     *
     * <p>Duplicate entries for the same item merge by summing explicit counts; ALL + ALL merges
     * to a single ALL; mixing ALL and an explicit count for the same item is
     * {@code invalid_argument}. First-occurrence command order is preserved.
     */
    public static ParseResult parse(String[] tailUnits) {
        List<ParseFailure> failures = new ArrayList<>();
        if (tailUnits == null || tailUnits.length == 0) {
            failures.add(new ParseFailure(
                    StorageAccessCode.INVALID_ARGUMENT,
                    "",
                    "no items given; expected \"<item>\" or \"<item> <count>\" entries separated by \",\""));
            return new ParseResult(List.of(), List.copyOf(failures));
        }

        // Join-then-split (see the method doc) — the only tokenization that recovers entry
        // boundaries from the space-split arg units.
        String joined = String.join(" ", tailUnits);
        String[] entries = joined.split(",", -1);

        // Item identity is the merge key (Item-level matching everywhere in C4.5; Decision 8).
        Map<Item, ItemQuery> merged = new LinkedHashMap<>();

        for (String rawEntry : entries) {
            String entry = rawEntry.trim();
            if (entry.isEmpty()) {
                failures.add(new ParseFailure(
                        StorageAccessCode.INVALID_ARGUMENT,
                        rawEntry,
                        "empty item entry (stray comma?)"));
                continue;
            }
            String[] parts = entry.split("\\s+");
            if (parts.length > 2) {
                failures.add(new ParseFailure(
                        StorageAccessCode.INVALID_ARGUMENT,
                        entry,
                        "entry \"" + entry + "\" must be \"<item>\" or \"<item> <count>\""));
                continue;
            }

            Item item = resolveItem(parts[0]);
            if (item == null) {
                failures.add(new ParseFailure(
                        StorageAccessCode.INVALID_ITEM_NAME,
                        parts[0],
                        "\"" + parts[0] + "\" did not resolve to a registered item"));
                continue;
            }
            String displayId = displayIdFor(item);

            int count = COUNT_ALL;
            if (parts.length == 2) {
                Integer parsedCount = parseCount(parts[1]);
                if (parsedCount == null) {
                    failures.add(new ParseFailure(
                            StorageAccessCode.INVALID_COUNT,
                            parts[1],
                            "count for \"" + displayId + "\" must be 1.." + MAX_COUNT
                                    + ", got \"" + parts[1] + "\""));
                    continue;
                }
                count = parsedCount;
            }

            ItemQuery existing = merged.get(item);
            if (existing == null) {
                merged.put(item, new ItemQuery(item, displayId, count));
            } else if (existing.isAll() && count == COUNT_ALL) {
                // ALL + ALL -> ALL (already recorded).
            } else if (existing.isAll() || count == COUNT_ALL) {
                failures.add(new ParseFailure(
                        StorageAccessCode.INVALID_ARGUMENT,
                        displayId,
                        "\"" + displayId + "\" appears both with and without a count"));
            } else {
                long sum = (long) existing.countOrAll() + count;
                if (sum > MAX_COUNT) {
                    failures.add(new ParseFailure(
                            StorageAccessCode.INVALID_COUNT,
                            displayId,
                            "merged count for \"" + displayId + "\" is " + sum
                                    + ", above the maximum of " + MAX_COUNT));
                } else {
                    merged.put(item, new ItemQuery(item, displayId, (int) sum));
                }
            }
        }

        if (!failures.isEmpty()) {
            return new ParseResult(List.of(), List.copyOf(failures));
        }
        return new ParseResult(List.copyOf(merged.values()), List.of());
    }

    /**
     * The canonical AI-visible rendering of an item id: vanilla ids with the
     * {@code minecraft:} prefix stripped, modded ids with namespace kept. The round-trip
     * invariant requires {@link ScanReportFormatter} to render ONLY through this helper.
     */
    public static String displayIdFor(Item item) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        return "minecraft".equals(key.getNamespace()) ? key.getPath() : key.toString();
    }

    /** Resolves an item token against the registry; {@code null} when it does not resolve. */
    private static Item resolveItem(String token) {
        String idText = token.contains(":") ? token : "minecraft:" + token;
        ResourceLocation id = ResourceLocation.tryParse(idText.toLowerCase(Locale.ROOT));
        if (id == null) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        // AIR sentinel (Decision 11): BuiltInRegistries.ITEM.get returns AIR for unknown ids,
        // so an AIR result is classified invalid_item_name. This DELIBERATELY also classifies
        // a literal "air" input as invalid — air is not a movable/storable item. Do not "fix"
        // this by special-casing AIR back in.
        return item == Items.AIR ? null : item;
    }

    /** Strict integer count in {@code 1..MAX_COUNT}; {@code null} on anything else. */
    private static Integer parseCount(String token) {
        int value;
        try {
            value = Integer.parseInt(token.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (value < 1 || value > MAX_COUNT) {
            return null;
        }
        return value;
    }
}
