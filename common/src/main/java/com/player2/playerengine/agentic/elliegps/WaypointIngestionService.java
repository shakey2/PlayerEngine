package com.player2.playerengine.agentic.elliegps;

import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.containeraccess.ContainerSnapshot;
import com.player2.playerengine.containeraccess.ItemCount;
import com.player2.playerengine.player2api.AiTaskClass;
import com.player2.playerengine.player2api.ConversationHistory;
import com.player2.playerengine.player2api.Player2APIService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Orchestrates the ingestion of a {@link ContainerSnapshot} into a {@link WaypointRecord}
 * (Part C5, WS4).
 *
 * <p>The entry point is {@link #ingest(PlayerEngineController, ContainerSnapshot, String,
 * WaypointRecord)}. It:
 * <ol>
 *   <li>Applies the slot threshold (Decision 5): when used slots exceed
 *       {@link com.player2.playerengine.PlayerEngineSettings#getEllieGpsSnapshotSlotThreshold()}
 *       the snapshot is omitted and the record is keyword-only.</li>
 *   <li>Builds keywords deterministically via {@link WaypointItemCategorizer} (always; never
 *       waits for or uses LLM output for keywords — Decision 6).</li>
 *   <li>Builds a deterministic description from container kind, position, top item lines, and
 *       empty-slot count (Decision 7). This description is always written to the record first.</li>
 *   <li>Optionally schedules an async {@link AiTaskClass#SUMMARIZATION} description-polish call
 *       on the dedicated {@code elliegps-ingestion} executor (config-gated;
 *       {@link com.player2.playerengine.PlayerEngineSettings#getEllieGpsUseModelDescription()}).
 *       Callers schedule this only after the deterministic record commits. On success, the
 *       callback marshals back to the server thread, revalidates its captured fingerprint, and
 *       commits a defensive copy through the checked store API.
 *       Budget/network/validation failures → keep deterministic description, log WARN.
 *       The LLM call NEVER blocks the server thread (AgenticPlannerService pattern).</li>
 * </ol>
 *
 * <h3>Threading contract</h3>
 * {@link #ingest} is called on the server thread and performs construction only. After a checked
 * commit, callers may invoke {@link #schedulePolishAfterCommit}; the async completion marshals its
 * store update back to the server thread. All store and index mutations remain server-thread-only.
 */
public final class WaypointIngestionService {

    // -------------------------------------------------------------------------
    // Dedicated daemon executor for SUMMARIZATION description polish (Decision 7)
    // -------------------------------------------------------------------------

    /**
     * Single-thread daemon executor for the async SUMMARIZATION LLM description polish.
     *
     * <p>Mirrors {@code AgenticPlannerService.PLANNER_EXECUTOR}. Daemon so it never blocks
     * JVM shutdown. Single-thread so polish calls are serialized and never spam the API.
     */
    private static final ExecutorService INGESTION_EXECUTOR =
            Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "elliegps-ingestion");
                    t.setDaemon(true);
                    return t;
                }
            });

    /** Maximum number of top items to include in the deterministic description. */
    private static final int MAX_DESC_ITEMS = 5;

    private WaypointIngestionService() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Builds a {@link WaypointRecord} from the given snapshot. Callers must commit the returned
     * record before invoking {@link #schedulePolishAfterCommit(PlayerEngineController, WaypointRecord)}.
     *
     * <p>Calling this method on the server thread is safe: it performs no model call and returns
     * promptly. Separating construction from scheduling prevents a failed/conflicting base commit
     * from leaving an async callback capable of updating an unrelated record at the same ID.
     *
     * @param mod              the controller (provides settings, API service, and server for marshal)
     * @param snapshot         the LIGHT-scan result from {@link com.player2.playerengine.containeraccess.ContainerScanService}
     * @param originToken      one of {@link WaypointRecord#ORIGIN_BOT_PLACED},
     *                         {@link WaypointRecord#ORIGIN_EXPLICIT_CREATE}, or
     *                         {@link WaypointRecord#ORIGIN_HEURISTIC_PASS}
     * @param existingRecord   the existing record at this position if one exists (to preserve
     *                         {@code createdGameTime}), or {@code null} for a fresh registration
     * @return the fully built {@link WaypointRecord} with the deterministic description and
     *         keywords already set; never null
     */
    public static WaypointRecord ingest(PlayerEngineController mod,
                                        ContainerSnapshot snapshot,
                                        String originToken,
                                        WaypointRecord existingRecord) {
        String dimensionId = snapshot.dimensionId();
        BlockPos canonical = snapshot.canonicalPos();
        BlockPos secondary = snapshot.secondaryPos();
        String kindToken   = snapshot.kind().token();

        // --- Slot threshold check (Decision 5) ---
        int usedSlots = snapshot.totalSlots() - snapshot.emptySlots();
        int threshold = mod.getModSettings().getEllieGpsSnapshotSlotThreshold();
        boolean snapshotOmitted = usedSlots > threshold;

        // --- Deterministic keywords (always; never from LLM — Decision 6) ---
        List<String> keywords = WaypointItemCategorizer.categorize(snapshot.aggregate());

        // --- Snapshot payload (null when keyword-only) ---
        InventoryWaypointData.Snapshot snapshotPayload = null;
        if (!snapshotOmitted) {
            snapshotPayload = new InventoryWaypointData.Snapshot(
                    snapshot.totalSlots(),
                    snapshot.emptySlots(),
                    List.copyOf(snapshot.aggregate()), // immutable copy
                    snapshot.gameTime());
        }

        // --- Inventory data ---
        int[] secondaryArr = null;
        if (secondary != null) {
            secondaryArr = new int[]{ secondary.getX(), secondary.getY(), secondary.getZ() };
        }
        InventoryWaypointData invData = new InventoryWaypointData(secondaryArr, kindToken, snapshotPayload);

        // --- Deterministic description (Decision 7) ---
        String deterministicDesc = buildDeterministicDescription(
                kindToken, canonical, snapshot.aggregate(), snapshot.emptySlots(), snapshotOmitted);

        // --- Build the record ---
        WaypointRecord record = new WaypointRecord();
        record.id             = WaypointRecord.idFor(dimensionId, canonical);
        record.type           = WaypointTypes.INVENTORY;
        record.dimension      = dimensionId;
        record.pos            = new int[]{ canonical.getX(), canonical.getY(), canonical.getZ() };
        record.description    = deterministicDesc;
        record.keywords       = keywords;
        record.origin         = (originToken != null) ? originToken : WaypointRecord.ORIGIN_EXPLICIT_CREATE;
        record.stale          = false;
        record.data           = invData;

        // Preserve createdGameTime on refresh, set updatedGameTime
        long now = snapshot.gameTime();
        record.createdGameTime = (existingRecord != null && existingRecord.createdGameTime > 0)
                ? existingRecord.createdGameTime
                : now;
        record.updatedGameTime = now;

        // Carry the retained original JSON envelope across the refresh so a newer build's
        // unknown envelope fields survive an audit/create refresh on this build (Decision 2).
        record.inheritUnknownFieldsFrom(existingRecord);

        return record;
    }

    /**
     * Schedules optional description polish only after {@code committedRecord} is authoritative.
     * The callback is fingerprint-guarded against deletion or a newer refresh.
     *
     * @return true when an async request was scheduled
     */
    public static boolean schedulePolishAfterCommit(PlayerEngineController mod,
                                                     WaypointRecord committedRecord) {
        if (mod == null || committedRecord == null
                || !mod.getModSettings().getEllieGpsUseModelDescription()
                || !WaypointTypes.INVENTORY.equals(committedRecord.type)) {
            return false;
        }
        return schedulePolish(mod, committedRecord,
                committedRecord.keywords != null ? committedRecord.keywords : List.of());
    }

    // -------------------------------------------------------------------------
    // Internal: deterministic description
    // -------------------------------------------------------------------------

    /**
     * Builds the deterministic description from container kind, position, top items, and empty
     * slots. This is always available immediately and is never null.
     *
     * <p>Example: {@code "double chest at (120,64,-35): 320 iron ingot, 64 copper ingot, 10 empty slots"}
     */
    private static String buildDeterministicDescription(String kindToken, BlockPos canonical,
                                                         List<ItemCount> aggregate,
                                                         int emptySlots,
                                                         boolean snapshotOmitted) {
        StringBuilder sb = new StringBuilder();
        // Container kind and position
        sb.append(kindToken.replace('_', ' '));
        sb.append(" at (");
        sb.append(canonical.getX()).append(',')
          .append(canonical.getY()).append(',')
          .append(canonical.getZ()).append(')');

        // Item lines
        if (!aggregate.isEmpty()) {
            sb.append(": ");
            int shown = 0;
            for (ItemCount ic : aggregate) {
                if (shown >= MAX_DESC_ITEMS) {
                    sb.append(", ...");
                    break;
                }
                if (shown > 0) sb.append(", ");
                // Strip namespace for readability; replace _ with space
                String displayName = displayName(ic.registryId());
                sb.append(ic.count()).append(' ').append(displayName);
                shown++;
            }
        }

        // Empty slots
        if (emptySlots > 0) {
            sb.append(aggregate.isEmpty() ? ": " : ", ");
            sb.append(emptySlots).append(" empty slot").append(emptySlots == 1 ? "" : "s");
        }

        if (snapshotOmitted) {
            sb.append(" (keyword-only; snapshot threshold exceeded)");
        }

        return sb.toString();
    }

    /**
     * Formats a registry id for human-readable display by stripping the namespace and replacing
     * underscores with spaces. E.g. {@code "minecraft:iron_ingot"} → {@code "iron ingot"}.
     */
    private static String displayName(String registryId) {
        if (registryId == null) return "";
        int colon = registryId.indexOf(':');
        String path = (colon >= 0) ? registryId.substring(colon + 1) : registryId;
        return path.replace('_', ' ');
    }

    // -------------------------------------------------------------------------
    // Internal: async description polish
    // -------------------------------------------------------------------------

    /**
     * Schedules the async SUMMARIZATION description-polish call.
     *
     * <p>The call runs on {@link #INGESTION_EXECUTOR} (never the server thread). On success,
     * updates the store and reindexes via {@code server.execute(...)}.  On any failure —
     * including budget hard-limit — keeps the deterministic description and logs WARN.
     *
     * @param mod           the controller (provides API service and server reference for marshal)
     * @param record        the record already written to the store with the deterministic description;
     *                      the async path will update only the description field if polish succeeds
     * @param keywords      the deterministic keywords (passed to the prompt for context)
     */
    private static boolean schedulePolish(PlayerEngineController mod,
                                          WaypointRecord record,
                                          List<String> keywords) {
        // Resolve the server for the marshal callback BEFORE leaving the server thread
        MinecraftServer server = resolveServer(mod);
        if (server == null) {
            PlayerEngine.LOGGER.debug("EllieGPS ingestion: skipping description polish (server not available)");
            return false;
        }

        Player2APIService api = mod.getPlayer2APIService();
        if (api == null) {
            PlayerEngine.LOGGER.debug("EllieGPS ingestion: skipping description polish (API service not available)");
            return false;
        }

        // Capture the values we need for the off-thread call (no world access on the executor thread)
        final String recordId          = record.id;
        final String deterministicDesc = record.description;
        final long capturedUpdatedTime = record.updatedGameTime;
        final List<String> kwSnapshot  = List.copyOf(keywords);

        INGESTION_EXECUTOR.submit(() -> {
            String polished = null;
            try {
                ConversationHistory history = new ConversationHistory(WaypointIngestionPrompt.systemPrompt());
                history.addUserMessage(
                        WaypointIngestionPrompt.userPrompt(deterministicDesc, kwSnapshot), api);
                String reply = api.completeConversationToString(history, AiTaskClass.SUMMARIZATION);
                polished = WaypointIngestionValidator.validate(reply);
            } catch (IllegalArgumentException valEx) {
                PlayerEngine.LOGGER.warn(
                        "EllieGPS ingestion: description polish rejected for id={}: {}",
                        recordId, valEx.getMessage());
            } catch (Exception e) {
                // Budget hard-limit (surfaced as exception by Player2APIService) or any network failure
                PlayerEngine.LOGGER.warn(
                        "EllieGPS ingestion: description polish failed for id={}: {}",
                        recordId, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }

            if (polished == null) {
                // Keep deterministic description — no store update needed
                return;
            }

            // Marshal back to the server thread for store + index update (Decision 7)
            final String finalPolished = polished;
            server.execute(() -> {
                EllieGPSStore store = EllieGPSStore.get();
                if (store == null) {
                    PlayerEngine.LOGGER.debug(
                            "EllieGPS ingestion: polish for id={} arrived after world unload, discarding",
                            recordId);
                    return;
                }
                // Find the record in the store (it may have been deleted/modified since dispatch).
                // Store snapshots are defensive; never mutate the returned object in place.
                WaypointRecord current = null;
                for (WaypointRecord r : store.all()) {
                    if (recordId.equals(r.id)) {
                        current = r;
                        break;
                    }
                }
                if (current == null
                        || !WaypointTypes.INVENTORY.equals(current.type)
                        || current.updatedGameTime != capturedUpdatedTime
                        || !Objects.equals(current.description, deterministicDesc)) {
                    PlayerEngine.LOGGER.debug(
                            "EllieGPS ingestion: polish for id={} arrived after the record changed, discarding",
                            recordId);
                    return;
                }
                WaypointRecord candidate = current.copy();
                candidate.description = finalPolished;
                WaypointMutationResult mutation = store.upsert(candidate);
                switch (mutation.status()) {
                    case COMMITTED, NO_CHANGE -> PlayerEngine.LOGGER.debug(
                            "EllieGPS ingestion: applied polished description to id={}", recordId);
                    case COMMITTED_INDEX_DEGRADED, NO_CHANGE_INDEX_DEGRADED -> {
                        mod.reportAgenticProgress(
                                WaypointReportFormatter.descriptionPolishIndexDegradedComponent(), false);
                        com.player2.playerengine.player2api.AiConversationFeedback.enqueueInfo(mod,
                                "EllieGPS description was saved, but its search index update failed.");
                    }
                    case FAILED_JSON_COMMIT -> {
                        mod.reportAgenticProgress(
                                WaypointReportFormatter.descriptionPolishCommitFailedComponent(), false);
                        com.player2.playerengine.player2api.AiConversationFeedback.enqueueInfo(mod,
                                "EllieGPS kept the existing deterministic description because the polished description could not be saved.");
                    }
                    case NOT_FOUND, NOT_FOUND_INDEX_DEGRADED, REJECTED_TYPE_CONFLICT,
                            REJECTED_TARGET_CONFLICT, FAILED_STORE_UNAVAILABLE -> PlayerEngine.LOGGER.debug(
                            "EllieGPS ingestion: discarded polished description for id={} status={}",
                            recordId, mutation.status());
                }
            });
        });
        return true;
    }

    /**
     * Resolves the {@link MinecraftServer} from the controller for the marshal callback.
     * Returns {@code null} when unavailable (e.g. integrated server not yet started).
     */
    private static MinecraftServer resolveServer(PlayerEngineController mod) {
        try {
            if (mod.getPlayer() != null && mod.getPlayer().getServer() != null) {
                return mod.getPlayer().getServer();
            }
        } catch (Exception e) {
            PlayerEngine.LOGGER.debug("EllieGPS ingestion: could not resolve server: {}", e.getMessage());
        }
        return null;
    }
}
