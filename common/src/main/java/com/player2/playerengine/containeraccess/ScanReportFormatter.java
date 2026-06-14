package com.player2.playerengine.containeraccess;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/**
 * SINGLE owner of every AI-visible storage string: the three scan output formats AND the
 * transaction receipt/failure texts (Part C4.5, WS3-owned file; WS4 only CALLS the receipt
 * methods and never duplicates format strings).
 *
 * <p><b>FROZEN PUBLIC API (contract-first pass):</b> the enum, the two records, and the four
 * static method signatures below are frozen — WS4 codes against them while WS3 implements the
 * bodies. Do not rename, reorder, or retype anything public here.
 *
 * <p>Format ground rules (full worked examples in the plan, WS3 section):
 * <ul>
 *   <li>Plain text, not JSON; {@code minecraft:} namespace stripped in AI text via
 *       {@link StorageItemArgs#displayIdFor} (round-trip invariant), modded namespaces kept.</li>
 *   <li>Positions render through {@link ContainerResolver#formatPos}; the
 *       {@code +(x,y,z)} secondary-pos suffix appears in every mode/receipt whenever
 *       {@code secondaryPos != null}.</li>
 *   <li>Aggregate lines sorted by registryId ascending; all numbers are exact live counts.</li>
 *   <li>Header: {@code "storage " mode " " pos ["+(" secondaryPos ")"] " " kindToken
 *       [" " totalSlots " slots" [", " emptySlots " empty"]]} — slot/empty tail: light =
 *       {@code N slots, M empty}; deep = {@code N slots}; targeted = no tail.</li>
 *   <li>Receipt grammar: {@code verb " " pos ["+(" secondaryPos ")"] ": " entry (", " entry)*}
 *       with verb {@code "withdrew from" | "deposited to"}; entries
 *       {@code id " x" moved "/" requested} (explicit, fully satisfied),
 *       {@code id " x" moved " (all)"} (ALL, nothing short), or
 *       {@code id " x" moved " of " present " (" shortReason ")"} (ALL, short).</li>
 *   <li>Slot receipt: {@code verb " " pos ["+(" secondaryPos ")"] " slot " containerSlot ": "
 *       id " x" count ["; to bot slot " botSlot | "; from bot slot " botSlot]} (clause omitted
 *       when auto-placed).</li>
 *   <li>Failure text: {@code codeToken ": " detail} entries joined by {@code "; "}, command
 *       order; the detail always names the offending token/item and the live number.</li>
 * </ul>
 */
public final class ScanReportFormatter {

    /** Direction of an item+count or slot-precise transfer (also parameterizes the WS4 tasks). */
    public enum TransferDirection {
        WITHDRAW,
        DEPOSIT
    }

    /**
     * Per-entry result of a transfer.
     *
     * @param displayId   canonical display id ({@link StorageItemArgs#displayIdFor})
     * @param moved       items actually moved
     * @param requested   explicit requested count, or {@code -1} for an ALL (omitted-count)
     *                    entry (matches {@link StorageItemArgs#COUNT_ALL})
     * @param present     live total available at validation time (container for withdraw, bot
     *                    inventory for deposit)
     * @param shortReason why an ALL entry came up short (e.g. {@code "bot inventory full"},
     *                    {@code "container full"}); empty when nothing was short
     */
    public record EntryOutcome(String displayId, int moved, int requested, int present, String shortReason) {
    }

    /**
     * Per-entry validation failure.
     *
     * @param code      the failing {@link StorageAccessCode} (its {@code token()} leads the text)
     * @param displayId canonical display id of the offending entry (or the raw offending token
     *                  for parse-phase failures)
     * @param detail    human detail naming the offending token/item and the live number
     */
    public record EntryFailure(StorageAccessCode code, String displayId, String detail) {
    }

    private ScanReportFormatter() {
    }

    /**
     * Renders the mode-appropriate scan payload (the three frozen formats; light/deep ignore
     * {@code targets}, targeted renders have/want lines from them in request order).
     */
    public static String scanReport(ScanMode mode, ContainerSnapshot snap, List<StorageItemArgs.ItemQuery> targets) {
        StringBuilder out = new StringBuilder(header(mode, snap));
        out.append('\n');
        switch (mode) {
            case LIGHT -> out.append(lightBody(snap));
            case DEEP -> out.append(deepBody(snap));
            case TARGETED -> out.append(targetedBody(snap, targets));
        }
        return out.toString();
    }

    /** Renders the one-line combined receipt for an item+count transfer (frozen receipt grammar). */
    public static String transferReceipt(TransferDirection dir, ResolvedContainer rc, List<EntryOutcome> entries) {
        StringJoiner joined = new StringJoiner(", ");
        for (EntryOutcome entry : entries) {
            joined.add(entryText(entry));
        }
        return verb(dir) + " " + posText(rc.canonicalPos(), rc.secondaryPos()) + ": " + joined;
    }

    /** Renders the semicolon-joined, code-token-first failure text (command-given order). */
    public static String transferFailure(List<EntryFailure> failures) {
        StringJoiner joined = new StringJoiner("; ");
        for (EntryFailure failure : failures) {
            joined.add(failure.code().token() + ": " + failure.detail());
        }
        return joined.toString();
    }

    /**
     * Renders the one-line slot-precise receipt.
     *
     * @param botSlot the explicit bot main-inventory slot, or {@code null} when auto-placed
     *                (the {@code "; to/from bot slot N"} clause is omitted)
     */
    public static String slotReceipt(TransferDirection dir, ResolvedContainer rc, int containerSlot,
                                     String displayId, int count, Integer botSlot) {
        StringBuilder sb = new StringBuilder(verb(dir))
                .append(' ')
                .append(posText(rc.canonicalPos(), rc.secondaryPos()))
                .append(" slot ")
                .append(containerSlot)
                .append(": ")
                .append(displayId)
                .append(" x")
                .append(count);
        if (botSlot != null) {
            sb.append(dir == TransferDirection.WITHDRAW ? "; to bot slot " : "; from bot slot ").append(botSlot);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ header + bodies

    /**
     * The frozen header for all three modes:
     * {@code "storage " mode " " pos ["+(" secondaryPos ")"] " " kindToken
     * [" " totalSlots " slots" [", " emptySlots " empty"]]}.
     */
    private static String header(ScanMode mode, ContainerSnapshot snap) {
        StringBuilder sb = new StringBuilder("storage ")
                .append(mode.token())
                .append(' ')
                .append(posText(snap.canonicalPos(), snap.secondaryPos()))
                .append(' ')
                .append(snap.kind().token());
        if (mode == ScanMode.LIGHT) {
            sb.append(' ').append(snap.totalSlots()).append(" slots, ").append(snap.emptySlots()).append(" empty");
        } else if (mode == ScanMode.DEEP) {
            // Deep lists the empties in the body as compressed ranges; targeted has no tail.
            sb.append(' ').append(snap.totalSlots()).append(" slots");
        }
        return sb.toString();
    }

    /** LIGHT body: {@code id xN} aggregate entries joined by {@code "; "}; {@code (empty)} when bare. */
    private static String lightBody(ContainerSnapshot snap) {
        if (snap.aggregate().isEmpty()) {
            return "(empty)";
        }
        StringJoiner joined = new StringJoiner("; ");
        for (ItemCount line : snap.aggregate()) {
            joined.add(displayOf(line.registryId()) + " x" + line.count());
        }
        return joined.toString();
    }

    /**
     * DEEP body: the non-empty slots as {@code slot:id xN} joined by {@code "|"} ({@code *}
     * after the id is reserved for extra-data stacks — {@code hasExtraData} ships always-false
     * in C4.5), then {@code "empty: " ranges} with compressed runs ({@code 3-25,40}). The slot
     * line is omitted for a fully empty container and the empty line for a completely full one,
     * so neither line ever renders without content.
     */
    private static String deepBody(ContainerSnapshot snap) {
        List<IndexedSlot> slots = snap.slots() == null ? List.of() : snap.slots();
        StringBuilder body = new StringBuilder();
        if (!slots.isEmpty()) {
            StringJoiner joined = new StringJoiner("|");
            for (IndexedSlot slot : slots) {
                joined.add(slot.slot() + ":" + displayOf(slot.registryId())
                        + (slot.hasExtraData() ? "*" : "") + " x" + slot.count());
            }
            body.append(joined);
        }
        String ranges = compressedEmptyRanges(snap.totalSlots(), slots);
        if (!ranges.isEmpty()) {
            if (body.length() > 0) {
                body.append('\n');
            }
            body.append("empty: ").append(ranges);
        }
        // Defensive only (a zero-slot container has neither line); real containers always
        // produce at least one of the two lines above.
        return body.length() == 0 ? "(empty)" : body.toString();
    }

    /**
     * TARGETED body, request order: explicit-count entries as {@code id have/want} plus
     * {@code " (short N)"} on a shortfall; omitted-count entries as the plain live total
     * ({@code id have} — 0 means absent, which is a successful scan result, not an error).
     */
    private static String targetedBody(ContainerSnapshot snap, List<StorageItemArgs.ItemQuery> targets) {
        if (targets == null || targets.isEmpty()) {
            return "(empty)"; // defensive: the command rejects targeted without items
        }
        // Snapshot aggregate keys are FULL registry ids (the C5 contract shape).
        Map<String, Integer> have = new HashMap<>();
        for (ItemCount line : snap.aggregate()) {
            have.put(line.registryId(), line.count());
        }
        StringJoiner joined = new StringJoiner("; ");
        for (StorageItemArgs.ItemQuery query : targets) {
            String fullId = BuiltInRegistries.ITEM.getKey(query.item()).toString();
            int present = have.getOrDefault(fullId, 0);
            StringBuilder entry = new StringBuilder(query.displayId()).append(' ').append(present);
            if (!query.isAll()) {
                entry.append('/').append(query.countOrAll());
                if (present < query.countOrAll()) {
                    entry.append(" (short ").append(query.countOrAll() - present).append(')');
                }
            }
            joined.add(entry.toString());
        }
        return joined.toString();
    }

    // ------------------------------------------------------------------ shared pieces

    /** The frozen receipt verb per direction. */
    private static String verb(TransferDirection dir) {
        return dir == TransferDirection.WITHDRAW ? "withdrew from" : "deposited to";
    }

    /**
     * One transfer-receipt entry (frozen grammar). A non-empty {@code shortReason} always wins
     * (it is the only form that can express a shortfall — ALL entries that came up short AND
     * the {@code container_changed} race on an explicit entry); otherwise ALL entries render
     * {@code " (all)"} and explicit entries {@code moved "/" requested}.
     */
    private static String entryText(EntryOutcome entry) {
        boolean isShort = entry.shortReason() != null && !entry.shortReason().isEmpty();
        if (isShort) {
            return entry.displayId() + " x" + entry.moved() + " of " + entry.present()
                    + " (" + entry.shortReason() + ")";
        }
        if (entry.requested() == StorageItemArgs.COUNT_ALL) {
            return entry.displayId() + " x" + entry.moved() + " (all)";
        }
        return entry.displayId() + " x" + entry.moved() + "/" + entry.requested();
    }

    /** Canonical pos rendering with the {@code +(x,y,z)} secondary suffix (all modes/receipts). */
    private static String posText(BlockPos canonical, BlockPos secondary) {
        String text = ContainerResolver.formatPos(canonical);
        return secondary == null ? text : text + "+" + ContainerResolver.formatPos(secondary);
    }

    /**
     * Renders a snapshot registry-id string through {@link StorageItemArgs#displayIdFor} (the
     * round-trip invariant: every id printed here parses back through the grammar to the same
     * {@code Item}). Snapshot ids originate from {@code BuiltInRegistries.ITEM.getKey} in the
     * same runtime, so the lookup always resolves.
     */
    private static String displayOf(String registryId) {
        ResourceLocation id = ResourceLocation.tryParse(registryId);
        if (id == null) {
            return registryId; // defensive; unreachable for getKey-produced ids
        }
        return StorageItemArgs.displayIdFor(BuiltInRegistries.ITEM.get(id));
    }

    /**
     * Compressed empty-slot ranges for the deep format: every index in
     * {@code 0..totalSlots-1} not present in {@code slots}, runs rendered {@code a-b}, singles
     * {@code a}, joined by {@code ","}. Empty string when the container is full.
     */
    private static String compressedEmptyRanges(int totalSlots, List<IndexedSlot> slots) {
        boolean[] occupied = new boolean[Math.max(0, totalSlots)];
        for (IndexedSlot slot : slots) {
            if (slot.slot() >= 0 && slot.slot() < occupied.length) {
                occupied[slot.slot()] = true;
            }
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < occupied.length) {
            if (occupied[i]) {
                i++;
                continue;
            }
            int start = i;
            while (i < occupied.length && !occupied[i]) {
                i++;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            int end = i - 1;
            sb.append(start);
            if (end > start) {
                sb.append('-').append(end);
            }
        }
        return sb.toString();
    }
}
