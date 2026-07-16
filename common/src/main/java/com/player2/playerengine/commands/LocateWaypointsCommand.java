package com.player2.playerengine.commands;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.agentic.elliegps.EllieGPSStore;
import com.player2.playerengine.agentic.elliegps.InventoryWaypointData;
import com.player2.playerengine.agentic.elliegps.WaypointRecord;
import com.player2.playerengine.agentic.elliegps.WaypointReportFormatter;
import com.player2.playerengine.agentic.elliegps.WaypointSearchOrder;
import com.player2.playerengine.agentic.elliegps.WaypointSearchResult;
import com.player2.playerengine.agentic.elliegps.WaypointSearchService;
import com.player2.playerengine.agentic.elliegps.WaypointSearchStatus;
import com.player2.playerengine.agentic.elliegps.WaypointTypes;
import com.player2.playerengine.commands.base.Arg;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.containeraccess.ContainerResolver;
import com.player2.playerengine.containeraccess.StorageAccessCode;
import com.player2.playerengine.player2api.AiConversationFeedback;
import com.player2.playerengine.util.Debug;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Structured, type-aware EllieGPS relevance query for the current dimension. */
public class LocateWaypointsCommand extends Command {

    private static final int MAX_HITS = 3;

    public LocateWaypointsCommand() throws CommandException {
        super(
                "locate_waypoints",
                "locate_waypoints <query terms...>. Queries EllieGPS for inventory or farm waypoints"
                        + " matching the terms. Returns up to 3 current-dimension results with type,"
                        + " position, description, and stale state. Takes no coordinates.",
                new Arg<>(String.class, "query", "", 0, false));
    }

    @Override
    protected void call(PlayerEngineController mod, ArgParser parser) throws CommandException {
        if (!mod.getModSettings().getEllieGpsEnabled()) {
            mod.reportAgenticProgress(WaypointReportFormatter.ellieGpsDisabledPlayerComponent(), true);
            this.finishWithError(WaypointReportFormatter.ellieGpsDisabledModel());
            return;
        }

        EllieGPSStore store = EllieGPSStore.get();
        if (store != null && store.consumeQuarantineNote()) {
            mod.reportAgenticProgress(WaypointReportFormatter.quarantineNoteComponent(), true);
        }

        String[] units = parser.getArgUnits();
        String query = units.length > 0 ? String.join(" ", units).trim() : "";
        if (query.isEmpty()) {
            failEarly(mod, StorageAccessCode.INVALID_ARGUMENT,
                    "usage: locate_waypoints <query terms...>");
            return;
        }
        if (store == null) {
            failEarly(mod, StorageAccessCode.CONTAINER_UNREACHABLE,
                    "EllieGPS store not available (no world loaded)");
            return;
        }
        ServerLevel level = mod.getWorld();
        if (level == null) {
            failEarly(mod, StorageAccessCode.CONTAINER_UNREACHABLE, "no world available");
            return;
        }
        String dimensionId = level.dimension().location().toString();

        WaypointSearchResult search = WaypointSearchService.find(
                query,
                dimensionId,
                Set.of(WaypointTypes.INVENTORY, WaypointTypes.FARM),
                true,
                WaypointSearchOrder.RELEVANCE,
                null,
                MAX_HITS);
        if (search.status() == WaypointSearchStatus.FAILED_SCAN_LIMIT) {
            mod.reportAgenticProgress(WaypointReportFormatter.searchScanLimitComponent(), true);
            this.finishWithError(WaypointReportFormatter.searchScanLimitModel());
            return;
        }
        if (search.status() == WaypointSearchStatus.FAILED_STORE_UNAVAILABLE) {
            mod.reportAgenticProgress(
                    WaypointReportFormatter.searchStoreUnavailableComponent(), true);
            this.finishWithError(WaypointReportFormatter.searchStoreUnavailableModel());
            return;
        }
        if (search.status() == WaypointSearchStatus.FAILED_SEARCH_ERROR) {
            mod.reportAgenticProgress(WaypointReportFormatter.searchFailedComponent(), true);
            this.finishWithError(WaypointReportFormatter.searchFailedModel());
            return;
        }

        boolean fallback = search.status() == WaypointSearchStatus.FULL_STORE_FALLBACK;
        if (fallback) {
            mod.reportAgenticProgress(WaypointReportFormatter.searchFallbackComponent(), true);
        }
        List<WaypointRecord> results = search.records();
        if (results.isEmpty()) {
            mod.reportAgenticProgress(WaypointReportFormatter.noWaypointsMatchComponent(), true);
            this.finishWithNote(WaypointReportFormatter.boundModel(
                    WaypointReportFormatter.noWaypointsMatch()
                            + (fallback ? " " + WaypointReportFormatter.searchFallbackModel() : "")));
            return;
        }

        List<String> lines = new ArrayList<>();
        for (WaypointRecord record : results) {
            BlockPos canonical = record.canonicalBlockPos();
            String pos = canonical != null ? ContainerResolver.formatPos(canonical) : "(??,??,??)";
            String kind = record.type != null ? record.type : "unknown";
            InventoryWaypointData inventory = record.inventoryData();
            if (inventory != null && inventory.containerKind != null
                    && !inventory.containerKind.isEmpty()) {
                kind = inventory.containerKind;
            }
            lines.add(WaypointReportFormatter.locateHitLine(
                    pos, kind, record.description, record.stale));
        }

        String payload = WaypointReportFormatter.boundModel(String.join("\n", lines)
                + (fallback ? " " + WaypointReportFormatter.searchFallbackModel() : ""));
        AiConversationFeedback.enqueueInfo(mod, payload);
        mod.reportAgenticProgress(
                WaypointReportFormatter.locateFoundComponent(results.size(), query), true);
        Debug.logMessage("waypoint-locate ok query=\"" + query + "\" hits=" + results.size()
                + " dimension=" + dimensionId
                + " bot=" + mod.getEntity().getName().getString());
        if (fallback) {
            this.finishWithNote(WaypointReportFormatter.searchFallbackModel());
        } else {
            this.finish();
        }
    }

    private void failEarly(PlayerEngineController mod, StorageAccessCode code, String detail) {
        String bounded = WaypointReportFormatter.boundReason(detail);
        Debug.logWarning("waypoint-locate fail code=" + code.token()
                + " detail=" + bounded
                + " bot=" + mod.getEntity().getName().getString());
        mod.reportAgenticProgress(
                Component.translatable("message.playerengine.elliegps.locate_fail", bounded), true);
        this.finishWithError(code.token() + ": " + bounded);
    }
}
